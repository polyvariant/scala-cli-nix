{ scala-cli, openjdk, makeWrapper, runCommand, stdenv, lib, clang, which }:
let
  supportedVersion = 7;

  fetchAll = deps: builtins.map (dep: { inherit dep; path = builtins.fetchurl dep; }) deps;

  fetchDeps = lockFile:
    let
      json = builtins.fromJSON (builtins.readFile lockFile);
      lockVersion = json.version or null;
      versionCheck =
        if lockVersion != supportedVersion
        then builtins.throw "scala-cli-nix: unsupported lockfile version ${builtins.toString lockVersion} (expected ${builtins.toString supportedVersion}). Re-run 'scala-cli-nix lock' to regenerate."
        else true;
      native = target: target.native or null;
      test = target: target.test or null;
    in assert versionCheck; {
      sources = json.sources or [];
      targets = builtins.mapAttrs (name: target:
        let
          n = native target;
          t = test target;
        in {
          inherit (target) platform scalaVersion;
          # Each builtins.fetchurl is its own FOD — per-artifact granularity
          compiler = fetchAll target.compiler;
          libraryDependencies = fetchAll target.libraryDependencies;
          nativeCompilerPlugins = if n != null then fetchAll n.compilerPlugins else [];
          nativeRuntimeDependencies = if n != null then fetchAll n.runtimeDependencies else [];
          nativeToolingDependencies = if n != null then fetchAll n.toolingDependencies else [];
          test =
            if t != null
            then {
              sources = t.sources;
              libraryDependencies = fetchAll t.libraryDependencies;
            }
            else null;
        }
      ) json.targets;
    };

  # Build a Coursier-compatible cache directory from fetched deps
  mkCacheDir = name: deps:
    let
      # Generate symlink commands for each dep
      linkCommands = builtins.map (entry:
        let
          # Strip "https://" and percent-encode "+" to match Coursier cache layout
          relative = builtins.replaceStrings [ "https://" "+" ] [ "https/" "%2B" ] entry.dep.url;
        in ''
          mkdir -p $out/$(dirname "${relative}")
          ln -sf ${entry.path} $out/${relative}
        ''
      ) deps;
    in runCommand name {} (builtins.concatStringsSep "\n" linkCommands);

  # Filter `src` down to the listed source files, returning both the filtered
  # source tree and a space-separated string of source paths to feed scala-cli.
  prepareSources = sources: src:
    let
      filteredSrc =
        if sources != []
        then lib.cleanSourceWith {
          src = src;
          filter = path: type:
            let rel = lib.removePrefix (toString src + "/") (toString path);
            in if type == "directory"
              then builtins.any (s: lib.hasPrefix (rel + "/") s) sources
              else builtins.elem rel sources;
        }
        else src;
      sourceArgs =
        if sources != []
        then builtins.concatStringsSep " " (builtins.map (s: "${filteredSrc}/${s}") sources)
        else "${filteredSrc}";
    in { inherit filteredSrc sourceArgs; };

  # Map lockfile platform name to --platform flag value
  platformFlag = platform:
    if platform == "Native" then "scala-native" else "jvm";

  # Common scala-cli sandbox env setup, shared by JVM/Native build and test phases
  scalaCliEnvSetup = ''
    export HOME=$TMPDIR/home
    export COURSIER_ARCHIVE_CACHE=$TMPDIR/coursier-arc
    export SCALA_CLI_HOME=$TMPDIR/scala-cli-home
    mkdir -p $HOME $COURSIER_ARCHIVE_CACHE $SCALA_CLI_HOME
  '';

  buildJvmTest = { pname, version, src, sources, fetched, attrOverrides }:
    let
      mainAndTestSources = sources ++ fetched.test.sources;
      inherit (prepareSources mainAndTestSources src) sourceArgs;
      # Test classpath was resolved combining main + test deps in a single
      # Coursier resolution; it is sufficient on its own.
      allDeps = fetched.compiler ++ fetched.test.libraryDependencies;
      depsCache = mkCacheDir "scala-cli-test-deps-${pname}" allDeps;
    in stdenv.mkDerivation (attrOverrides ({
      pname = "${pname}-test";
      inherit version;
      dontUnpack = true;
      buildInputs = [ scala-cli openjdk ];
      COURSIER_CACHE = depsCache;
      buildPhase = scalaCliEnvSetup + ''
        scala-cli --power test ${sourceArgs} --server=false --offline \
          --platform ${platformFlag fetched.platform} \
          --scala-version ${fetched.scalaVersion}
      '';
      installPhase = ''
        mkdir -p $out
        touch $out/passed
      '';
    }) "JVM");

  buildNativeTest = { pname, version, src, sources, fetched, attrOverrides }:
    let
      mainAndTestSources = sources ++ fetched.test.sources;
      inherit (prepareSources mainAndTestSources src) sourceArgs;
      allDeps = fetched.compiler ++ fetched.test.libraryDependencies
        ++ fetched.nativeCompilerPlugins ++ fetched.nativeRuntimeDependencies ++ fetched.nativeToolingDependencies;
      depsCache = mkCacheDir "scala-cli-test-deps-${pname}" allDeps;
    in stdenv.mkDerivation (attrOverrides ({
      pname = "${pname}-test";
      inherit version;
      dontUnpack = true;
      buildInputs = [ scala-cli openjdk clang which ];
      COURSIER_CACHE = depsCache;
      buildPhase = scalaCliEnvSetup + ''
        scala-cli --power test ${sourceArgs} --server=false --offline \
          --platform ${platformFlag fetched.platform} \
          --scala-version ${fetched.scalaVersion}
      '';
      installPhase = ''
        mkdir -p $out
        touch $out/passed
      '';
    }) "Native");

  # Build the test derivation attrset for passthru.tests, or {} if no tests.
  mkTests = { pname, version, src, sources, fetched, attrOverrides }:
    if fetched.test == null then {}
    else if fetched.platform == "Native"
    then { test = buildNativeTest { inherit pname version src sources fetched attrOverrides; }; }
    else { test = buildJvmTest { inherit pname version src sources fetched attrOverrides; }; };

  buildJvmApp = { pname, version, src, sources, fetched, mainClass, attrOverrides }:
    let
      inherit (prepareSources sources src) sourceArgs;
      allDeps = fetched.compiler ++ fetched.libraryDependencies;
      depsCache = mkCacheDir "scala-cli-deps-${pname}" allDeps;

      # Only JARs on the classpath, not POMs
      libraryJars = builtins.filter (e: builtins.match ".*\\.jar" e.dep.url != null) fetched.libraryDependencies;
      libraryClasspath = builtins.concatStringsSep ":" (builtins.map (e: e.path) libraryJars);

      defaultCompileAttrs = {
        pname = "${pname}-compiled";
        inherit version;
        dontUnpack = true;
        buildInputs = [ scala-cli openjdk ];

        COURSIER_CACHE = depsCache;

        buildPhase = scalaCliEnvSetup + ''
          scala-cli --power package ${sourceArgs} --server=false --offline --library \
            --platform ${platformFlag fetched.platform} \
            --scala-version ${fetched.scalaVersion} \
            -o $out/share/${pname}.jar
        '' + lib.optionalString (mainClass == null) ''
          MAIN_CLASSES=$(scala-cli --power run --main-class-list ${sourceArgs} --server=false --offline \
            --platform ${platformFlag fetched.platform} \
            --scala-version ${fetched.scalaVersion})
          MAIN_CLASS_COUNT=$(echo "$MAIN_CLASSES" | wc -l)
          if [ "$MAIN_CLASS_COUNT" -ne 1 ]; then
            echo "error: found $MAIN_CLASS_COUNT main classes, expected exactly 1:"
            echo "$MAIN_CLASSES"
            echo "Pass mainClass to buildScalaCliApp to disambiguate."
            exit 1
          fi
          echo -n "$MAIN_CLASSES" > $out/share/main-class
        '';
        installPhase = "true";
      };

      compiledJar = stdenv.mkDerivation (attrOverrides defaultCompileAttrs "JVM");

      resolvedMainClass =
        if mainClass != null
        then mainClass
        else builtins.readFile "${compiledJar}/share/main-class";

      tests = mkTests { inherit pname version src sources fetched attrOverrides; };

    in stdenv.mkDerivation (attrOverrides ({
      inherit pname version;
      dontUnpack = true;
      buildInputs = [ openjdk makeWrapper ];
      passthru = { inherit tests; };
      installPhase = ''
        mkdir -p $out/bin
        makeWrapper ${openjdk}/bin/java $out/bin/${pname} \
          --add-flags "-cp ${libraryClasspath}:${compiledJar}/share/${pname}.jar ${resolvedMainClass}"
      '';
    }) "JVM");

  buildNativeApp = { pname, version, src, sources, fetched, attrOverrides }:
    let
      inherit (prepareSources sources src) sourceArgs;
      allDeps = fetched.compiler ++ fetched.libraryDependencies
        ++ fetched.nativeCompilerPlugins ++ fetched.nativeRuntimeDependencies ++ fetched.nativeToolingDependencies;
      depsCache = mkCacheDir "scala-cli-deps-${pname}" allDeps;

      tests = mkTests { inherit pname version src sources fetched attrOverrides; };
    in stdenv.mkDerivation (attrOverrides ({
      inherit pname version;
      dontUnpack = true;
      buildInputs = [ scala-cli openjdk clang which ];
      passthru = { inherit tests; };

      COURSIER_CACHE = depsCache;

      buildPhase = scalaCliEnvSetup + ''
        mkdir -p $out/bin
        scala-cli --power package ${sourceArgs} --server=false --offline \
          --platform ${platformFlag fetched.platform} \
          --scala-version ${fetched.scalaVersion} \
          -o $out/bin/${pname}
      '';
      installPhase = "true";
    }) "Native");

  buildTarget = { pname, version, src, sources, targetFetched, mainClass ? null, attrOverrides }:
    if targetFetched.platform == "Native"
    then buildNativeApp { inherit pname version src sources attrOverrides; fetched = targetFetched; }
    else buildJvmApp { inherit pname version src sources mainClass attrOverrides; fetched = targetFetched; };

  # Normalize dots to underscores for Nix attribute name ergonomics
  nixKey = key: builtins.replaceStrings [ "." ] [ "_" ] key;

in {
  # Build all targets from a lockfile, returning an attrset keyed by target name
  # e.g. { jvm = <drv>; native = <drv>; } or { jvm-3_6_4 = <drv>; native-3_6_4 = <drv>; }
  buildScalaCliApps = { pname, version, src, lockFile, mainClass ? null, attrOverrides ? (attrs: _platform: attrs) }:
    let
      fetched = fetchDeps lockFile;
    in builtins.listToAttrs (
      builtins.map (key:
        { name = nixKey key;
          value = buildTarget {
            inherit pname version src mainClass attrOverrides;
            sources = fetched.sources;
            targetFetched = fetched.targets.${key};
          };
        }
      ) (builtins.attrNames fetched.targets)
    );

  # Build a single target from a lockfile.
  # If the lockfile has exactly one target, builds it.
  # If it has multiple targets, `target` must be specified (e.g. target = "jvm").
  buildScalaCliApp = { pname, version, src, lockFile, mainClass ? null, target ? null, attrOverrides ? (attrs: _platform: attrs) }:
    let
      fetched = fetchDeps lockFile;
      targetNames = builtins.attrNames fetched.targets;
      resolvedTarget =
        if target != null
        then target
        else if builtins.length targetNames == 1
        then builtins.head targetNames
        else builtins.throw "scala-cli-nix: lockfile has multiple targets (${builtins.concatStringsSep ", " targetNames}). Pass `target` to buildScalaCliApp or use buildScalaCliApps.";
    in buildTarget {
      inherit pname version src mainClass attrOverrides;
      sources = fetched.sources;
      targetFetched = fetched.targets.${resolvedTarget};
    };
}
