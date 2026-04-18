{ scala-cli, openjdk, makeWrapper, runCommand, stdenv, lib, clang, which }:
let
  supportedVersion = 5;

  fetchDeps = lockFile:
    let
      json = builtins.fromJSON (builtins.readFile lockFile);
      lockVersion = json.version or null;
      versionCheck =
        if lockVersion != supportedVersion
        then builtins.throw "scala-cli-nix: unsupported lockfile version ${builtins.toString lockVersion} (expected ${builtins.toString supportedVersion}). Re-run 'scala-cli-nix lock' to regenerate."
        else true;
      fetchAll = deps: builtins.map (dep: { inherit dep; path = builtins.fetchurl dep; }) deps;
      native = json.native or null;
    in assert versionCheck; {
      inherit json;
      platform = json.platform or "JVM";
      # Each builtins.fetchurl is its own FOD — per-artifact granularity
      compiler = fetchAll json.compiler;
      libraryDependencies = fetchAll json.libraryDependencies;
      nativeCompilerPlugins = if native != null then fetchAll native.compilerPlugins else [];
      nativeRuntimeDependencies = if native != null then fetchAll native.runtimeDependencies else [];
      nativeToolingDependencies = if native != null then fetchAll native.toolingDependencies else [];
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

  # Common source filtering and args, shared by JVM and Native builders
  prepareSources = fetched: src:
    let
      sources = fetched.json.sources or null;
      filteredSrc =
        if sources != null
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
        if sources != null
        then builtins.concatStringsSep " " (builtins.map (s: "${filteredSrc}/${s}") sources)
        else "${filteredSrc}";
    in { inherit filteredSrc sourceArgs; };

  buildJvmApp = { pname, version, src, fetched, mainClass }:
    let
      inherit (prepareSources fetched src) sourceArgs;
      allDeps = fetched.compiler ++ fetched.libraryDependencies;
      depsCache = mkCacheDir "scala-cli-deps-${pname}" allDeps;

      # Only JARs on the classpath, not POMs
      libraryJars = builtins.filter (e: builtins.match ".*\\.jar" e.dep.url != null) fetched.libraryDependencies;
      libraryClasspath = builtins.concatStringsSep ":" (builtins.map (e: e.path) libraryJars);

      compiledJar = stdenv.mkDerivation {
        pname = "${pname}-compiled";
        inherit version;
        dontUnpack = true;
        buildInputs = [ scala-cli openjdk ];

        COURSIER_CACHE = depsCache;
        COURSIER_ARCHIVE_CACHE = "/tmp/coursier-arc";
        SCALA_CLI_HOME = "/tmp/scala-cli-home";

        buildPhase = ''
          export HOME=$TMPDIR/home
          mkdir -p $HOME $COURSIER_ARCHIVE_CACHE $SCALA_CLI_HOME

          scala-cli --power package ${sourceArgs} --server=false --offline --library -o $out/share/${pname}.jar
        '' + lib.optionalString (mainClass == null) ''
          MAIN_CLASSES=$(scala-cli --power run --main-class-list ${sourceArgs} --server=false --offline)
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

      resolvedMainClass =
        if mainClass != null
        then mainClass
        else builtins.readFile "${compiledJar}/share/main-class";

    in stdenv.mkDerivation {
      inherit pname version;
      dontUnpack = true;
      buildInputs = [ openjdk makeWrapper ];
      installPhase = ''
        mkdir -p $out/bin
        makeWrapper ${openjdk}/bin/java $out/bin/${pname} \
          --add-flags "-cp ${libraryClasspath}:${compiledJar}/share/${pname}.jar ${resolvedMainClass}"
      '';
    };

  buildNativeApp = { pname, version, src, fetched }:
    let
      inherit (prepareSources fetched src) sourceArgs;
      allDeps = fetched.compiler ++ fetched.libraryDependencies
        ++ fetched.nativeCompilerPlugins ++ fetched.nativeRuntimeDependencies ++ fetched.nativeToolingDependencies;
      depsCache = mkCacheDir "scala-cli-deps-${pname}" allDeps;
    in stdenv.mkDerivation {
      inherit pname version;
      dontUnpack = true;
      buildInputs = [ scala-cli openjdk clang which ];

      COURSIER_CACHE = depsCache;
      COURSIER_ARCHIVE_CACHE = "/tmp/coursier-arc";
      SCALA_CLI_HOME = "/tmp/scala-cli-home";

      buildPhase = ''
        export HOME=$TMPDIR/home
        mkdir -p $HOME $COURSIER_ARCHIVE_CACHE $SCALA_CLI_HOME $out/bin

        scala-cli --power package ${sourceArgs} --server=false --offline -o $out/bin/${pname}
      '';
      installPhase = "true";
    };

in {
  buildScalaCliApp = { pname, version, src, lockFile, mainClass ? null }:
    let
      fetched = fetchDeps lockFile;
    in if fetched.platform == "Native"
      then buildNativeApp { inherit pname version src fetched; }
      else buildJvmApp { inherit pname version src fetched mainClass; };
}
