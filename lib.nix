{ scala-cli, openjdk, makeWrapper, runCommand, stdenv, lib, clang, which }:
let
  supportedVersion = 9;

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
      sbtJson = json.sbt or null;
    in assert versionCheck; {
      sources = json.sources or [];
      resourceDirs = json.resourceDirs or [];
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
              resourceDirs = t.resourceDirs or [];
              libraryDependencies = fetchAll t.libraryDependencies;
            }
            else null;
        }
      ) json.targets;
      sbt =
        if sbtJson != null
        then {
          inherit (sbtJson) sbtVersion scalaBootVersion;
          mainClass = sbtJson.mainClass or null;
          launcherJar = { dep = sbtJson.launcherJar; path = builtins.fetchurl sbtJson.launcherJar; };
          bootJars = fetchAll sbtJson.bootJars;
          bootCoursierCache = fetchAll sbtJson.bootCoursierCache;
          scalaInstance = fetchAll sbtJson.scalaInstance;
          compilerBridge = fetchAll sbtJson.compilerBridge;
        }
        else null;
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

  # Filter `src` down to the listed source files (and resource directories),
  # returning both the filtered source tree and a space-separated string of
  # source paths to feed scala-cli. Resource directories are included as
  # whole subtrees so `using resourceDir` can find them at compile/link time.
  prepareSources = sources: resourceDirs: src:
    let
      # Is `rel` inside (or equal to) any resourceDir? Then keep it.
      insideResourceDir = rel:
        builtins.any (r: rel == r || lib.hasPrefix (r + "/") rel) resourceDirs;
      # Is `rel` an ancestor of any resourceDir? Then we must descend into it.
      ancestorOfResourceDir = rel:
        builtins.any (r: lib.hasPrefix (rel + "/") r) resourceDirs;
      filteredSrc =
        if sources != [] || resourceDirs != []
        then lib.cleanSourceWith {
          src = src;
          filter = path: type:
            let rel = lib.removePrefix (toString src + "/") (toString path);
            in if type == "directory"
              then builtins.any (s: lib.hasPrefix (rel + "/") s) sources
                || insideResourceDir rel
                || ancestorOfResourceDir rel
              else builtins.elem rel sources || insideResourceDir rel;
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

  buildJvmTest = { pname, version, src, sources, resourceDirs, fetched, attrOverrides }:
    let
      mainAndTestSources = sources ++ fetched.test.sources;
      mainAndTestResourceDirs = resourceDirs ++ fetched.test.resourceDirs;
      inherit (prepareSources mainAndTestSources mainAndTestResourceDirs src) sourceArgs;
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

  buildNativeTest = { pname, version, src, sources, resourceDirs, fetched, attrOverrides }:
    let
      mainAndTestSources = sources ++ fetched.test.sources;
      mainAndTestResourceDirs = resourceDirs ++ fetched.test.resourceDirs;
      inherit (prepareSources mainAndTestSources mainAndTestResourceDirs src) sourceArgs;
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
  mkTests = { pname, version, src, sources, resourceDirs, fetched, attrOverrides }:
    if fetched.test == null then {}
    else if fetched.platform == "Native"
    then { test = buildNativeTest { inherit pname version src sources resourceDirs fetched attrOverrides; }; }
    else { test = buildJvmTest { inherit pname version src sources resourceDirs fetched attrOverrides; }; };

  buildJvmApp = { pname, version, src, sources, resourceDirs, fetched, mainClass, attrOverrides }:
    let
      inherit (prepareSources sources resourceDirs src) sourceArgs;
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

      tests = mkTests { inherit pname version src sources resourceDirs fetched attrOverrides; };

    in stdenv.mkDerivation (attrOverrides ({
      inherit pname version;
      dontUnpack = true;
      buildInputs = [ openjdk makeWrapper ];
      passthru = { inherit tests; };
      meta.mainProgram = pname;
      installPhase = ''
        mkdir -p $out/bin
        makeWrapper ${openjdk}/bin/java $out/bin/${pname} \
          --add-flags "-cp ${libraryClasspath}:${compiledJar}/share/${pname}.jar ${resolvedMainClass}"
      '';
    }) "JVM");

  buildNativeApp = { pname, version, src, sources, resourceDirs, fetched, attrOverrides }:
    let
      inherit (prepareSources sources resourceDirs src) sourceArgs;
      allDeps = fetched.compiler ++ fetched.libraryDependencies
        ++ fetched.nativeCompilerPlugins ++ fetched.nativeRuntimeDependencies ++ fetched.nativeToolingDependencies;
      depsCache = mkCacheDir "scala-cli-deps-${pname}" allDeps;

      tests = mkTests { inherit pname version src sources resourceDirs fetched attrOverrides; };
    in stdenv.mkDerivation (attrOverrides ({
      inherit pname version;
      dontUnpack = true;
      buildInputs = [ scala-cli openjdk clang which ];
      passthru = { inherit tests; };
      meta.mainProgram = pname;

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

  buildTarget = { pname, version, src, sources, resourceDirs, targetFetched, mainClass ? null, attrOverrides }:
    if targetFetched.platform == "Native"
    then buildNativeApp { inherit pname version src sources resourceDirs attrOverrides; fetched = targetFetched; }
    else buildJvmApp { inherit pname version src sources resourceDirs mainClass attrOverrides; fetched = targetFetched; };

  # Build sbt's boot dir layout. Two sublayouts:
  #   - `<bootDir>/<scalaBootVersion>/lib/{scala-library,scala-compiler,scala-reflect,scala-xml_<bin>-<ver>}.jar`
  #     The launcher expects the Scala stdlib jars here with their
  #     *unversioned* sbt-flavored filenames (`scala-library.jar` etc.).
  #   - `<bootDir>/<scalaBootVersion>/org.scala-sbt/sbt/<sbtVersion>/<filename>.jar`
  #     All other sbt-application jars, named as published on Maven.
  # `bootJars` should be ONLY the resolved-winner jars (no evicted versions,
  # no POMs); otherwise sbt-launch refuses to start with #4955.
  mkSbtBootDir = name: sbtVersion: scalaBootVersion: bootJars:
    let
      # Map a versioned Scala stdlib URL to the unversioned filename sbt-launch wants.
      libRename = url:
        let
          isLib = grp: nm:
            lib.hasInfix "/${grp}/${nm}/${scalaBootVersion}/${nm}-${scalaBootVersion}.jar" url;
        in
          if isLib "org/scala-lang" "scala-library" then "scala-library.jar"
          else if isLib "org/scala-lang" "scala-compiler" then "scala-compiler.jar"
          else if isLib "org/scala-lang" "scala-reflect" then "scala-reflect.jar"
          else if lib.hasInfix "/org/scala-lang/modules/scala-xml_" url
            then baseNameOf url
          else null;
      libLinkCommands = builtins.concatLists (builtins.map (entry:
        let mapped = libRename entry.dep.url;
        in if mapped == null then [] else [ ''
          ln -sf ${entry.path} $out/${scalaBootVersion}/lib/${mapped}
        '' ]
      ) bootJars);
      appLinkCommands = builtins.map (entry: ''
        ln -sf ${entry.path} $out/${scalaBootVersion}/org.scala-sbt/sbt/${sbtVersion}/${baseNameOf entry.dep.url}
      '') bootJars;
    in runCommand name {} (''
      mkdir -p $out/${scalaBootVersion}/lib $out/${scalaBootVersion}/org.scala-sbt/sbt/${sbtVersion}
    '' + builtins.concatStringsSep "\n" (libLinkCommands ++ appLinkCommands));

  # Project-independent sbt environment: launcher + boot dir + a Coursier
  # cache prepopulated with everything sbt needs to bootstrap itself
  # (boot transitive set incl. evicted POMs, scalaInstance, compiler bridge).
  # The user's runtime libraryDependencies are layered on top at build time.
  #
  # Two projects on the same sbt version + scalaBootVersion + same
  # scalaInstance/bridge share this exact toolchain via Nix's content-addressed
  # store. The cache key intentionally excludes anything project-specific
  # (pname, sources, user runtime deps).
  mkSbtToolchain = sbtBlock:
    let
      key = "${sbtBlock.sbtVersion}-${sbtBlock.scalaBootVersion}";
      bootDeps =
        sbtBlock.bootCoursierCache ++
        sbtBlock.scalaInstance ++
        sbtBlock.compilerBridge;
    in {
      launcherJar = sbtBlock.launcherJar.path;
      inherit (sbtBlock) sbtVersion scalaBootVersion mainClass;
      bootDir = mkSbtBootDir "sbt-boot-${key}"
        sbtBlock.sbtVersion sbtBlock.scalaBootVersion sbtBlock.bootJars;
      coursierCache = mkCacheDir "sbt-coursier-${key}" bootDeps;
    };

  buildSbtAppImpl = { pname, version, src, sources, fetched, attrOverrides }:
    let
      sbtBlock =
        if fetched.sbt == null
        then builtins.throw "buildSbtApp: lockfile has no `sbt` section. Run `scn lock-sbt` first."
        else fetched.sbt;

      toolchain = mkSbtToolchain sbtBlock;

      # User runtime deps are layered on top of the toolchain's Coursier cache
      # at build time. Keeping them separate (rather than merged into the
      # toolchain) preserves the toolchain's shareability across projects.
      userDepsCache = mkCacheDir "sbt-userdeps-${pname}"
        fetched.targets.jvm.libraryDependencies;

      # Filter src down to what's listed in `sources` + the build.sbt-related
      # files sbt needs. The filtered tree must include build.sbt, project/,
      # and the source files; nothing else.
      filteredSrc =
        if sources != []
        then lib.cleanSourceWith {
          src = src;
          filter = path: type:
            let rel = lib.removePrefix (toString src + "/") (toString path);
            in if type == "directory"
              then rel == "project"
                || lib.hasPrefix "project/" rel
                || builtins.any (s: lib.hasPrefix (rel + "/") s) sources
              else rel == "build.sbt"
                || lib.hasPrefix "project/" rel
                || builtins.elem rel sources;
        }
        else src;

      # Runtime classpath for the wrapper: JAR-only entries from libraryDependencies.
      libraryJars = builtins.filter
        (e: builtins.match ".*\\.jar" e.dep.url != null)
        fetched.targets.jvm.libraryDependencies;
      libraryClasspath = builtins.concatStringsSep ":" (builtins.map (e: e.path) libraryJars);

      mainClass =
        if toolchain.mainClass != null
        then toolchain.mainClass
        else builtins.throw "buildSbtApp: lockfile has no mainClass and explicit override not yet supported; set `Compile / mainClass := Some(\"...\")` in build.sbt and re-lock.";
    in stdenv.mkDerivation (attrOverrides ({
      inherit pname version;
      src = filteredSrc;
      buildInputs = [ openjdk makeWrapper ];
      meta.mainProgram = pname;

      configurePhase = ''
        runHook preConfigure

        # Materialize writable boot dir + Coursier cache. sbt writes into
        # both (compiled compiler bridge, internal lockfiles), so we can't
        # use the read-only FOD paths directly. `cp -rs` populates with
        # symlinks pointing at the FODs.
        export SBT_HOME=$TMPDIR/sbt-home
        export SBT_BOOT=$SBT_HOME/boot
        export SBT_SBTBOOT=$SBT_HOME/sbtboot
        export SBT_IVY=$SBT_HOME/ivy
        export COURSIER_CACHE=$SBT_HOME/coursier
        mkdir -p $SBT_BOOT $SBT_SBTBOOT $SBT_IVY $COURSIER_CACHE

        # Project-independent toolchain (shared via Nix store across projects).
        cp -rs --no-preserve=mode ${toolchain.bootDir}/. $SBT_BOOT/
        cp -rs --no-preserve=mode ${toolchain.coursierCache}/. $COURSIER_CACHE/
        # Layer the user's runtime deps on top of the toolchain's Coursier cache.
        cp -rs --no-preserve=mode ${userDepsCache}/. $COURSIER_CACHE/

        export HOME=$TMPDIR/home
        mkdir -p $HOME

        runHook postConfigure
      '';

      # The sbt launcher's JVM-level flags. -Dsbt.boot.directory etc. tell it
      # to use our redirected dirs; -Dsbt.offline=true blocks net access in
      # all internal Coursier calls.
      SBT_LAUNCHER_OPTS = lib.concatStringsSep " " [
        "-Xmx2G"
        "-Dsbt.global.base=$SBT_SBTBOOT"
        "-Dsbt.boot.directory=$SBT_BOOT"
        "-Dsbt.ivy.home=$SBT_IVY"
        "-Dsbt.offline=true"
        "-Dsbt.supershell=false"
        "-Dsbt.color=false"
      ];

      buildPhase = ''
        runHook preBuild
        # `-Dsbt.color=false` already disables colors; `-Dsbt.supershell=false`
        # disables the interactive bar. Closing stdin (`< /dev/null`) puts sbt
        # in batch mode without needing the `-batch` flag (which only the
        # `sbt` shell script understands, not the launcher jar directly).
        java $SBT_LAUNCHER_OPTS \
          -jar ${toolchain.launcherJar} \
          package < /dev/null
        runHook postBuild
      '';

      installPhase = ''
        runHook preInstall
        mkdir -p $out/share $out/bin
        # `sbt package` writes to target/scala-<bin>/<name>_<bin>-<version>.jar
        # — pick whichever JAR landed there (single-module).
        JARS=( target/scala-*/*.jar )
        if [ ''${#JARS[@]} -ne 1 ]; then
          echo "expected exactly one packaged JAR, found: ''${JARS[@]}"
          exit 1
        fi
        cp "''${JARS[0]}" $out/share/${pname}.jar
        makeWrapper ${openjdk}/bin/java $out/bin/${pname} \
          --add-flags "-cp ${libraryClasspath}:$out/share/${pname}.jar ${mainClass}"
        runHook postInstall
      '';
    }) "JVM");

  # Normalize dots to underscores for Nix attribute name ergonomics
  nixKey = key: builtins.replaceStrings [ "." ] [ "_" ] key;

in {
  # Collect `passthru.tests` from every package in `packages` into a flat
  # checks-shaped attrset, mapping `<pkgName>-<testName>` to each test
  # derivation. Packages without a `passthru.tests` attrset contribute
  # nothing. Intended for use as `checks.<system> = scala-cli-nix.collectChecks
  # self.packages.<system>;` in flakes that consume `buildScalaCliApp(s)`.
  collectChecks = packages:
    builtins.foldl'
      (acc: pkgName:
        let tests = packages.${pkgName}.passthru.tests or {};
        in acc // lib.mapAttrs'
          (testName: drv: { name = "${pkgName}-${testName}"; value = drv; })
          tests)
      {}
      (builtins.attrNames packages);

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
            resourceDirs = fetched.resourceDirs;
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
      resourceDirs = fetched.resourceDirs;
      targetFetched = fetched.targets.${resolvedTarget};
    };

  # Build an sbt-managed app using sbt itself inside the Nix sandbox.
  # Requires the lockfile to carry an `sbt` section (run `scn lock-sbt`).
  buildSbtApp = { pname, version, src, lockFile, attrOverrides ? (attrs: _platform: attrs) }:
    let fetched = fetchDeps lockFile;
    in buildSbtAppImpl {
      inherit pname version src attrOverrides;
      sources = fetched.sources;
      fetched = fetched;
    };
}
