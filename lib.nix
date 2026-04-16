{ scala-cli, openjdk, makeWrapper, runCommand, stdenv }:
let
  fetchDeps = lockFile:
    let
      json = builtins.fromJSON (builtins.readFile lockFile);
    in {
      inherit json;
      # Each builtins.fetchurl is its own FOD — per-artifact granularity
      compiler = builtins.map (dep: { inherit dep; path = builtins.fetchurl dep; }) json.compiler;
      libraryDependencies = builtins.map (dep: { inherit dep; path = builtins.fetchurl dep; }) json.libraryDependencies;
    };

  # Build a Coursier-compatible cache directory from fetched deps
  mkCacheDir = name: deps:
    let
      # Generate symlink commands for each dep
      linkCommands = builtins.map (entry:
        let
          # Strip "https://" from URL to get cache-relative path
          relative = builtins.replaceStrings [ "https://" ] [ "https/" ] entry.dep.url;
        in ''
          mkdir -p $out/$(dirname "${relative}")
          ln -sf ${entry.path} $out/${relative}
        ''
      ) deps;
    in runCommand name {} (builtins.concatStringsSep "\n" linkCommands);

in {
  buildScalaCliApp = { pname, version, src, lockFile, mainClass ? null }:
    let
      fetched = fetchDeps lockFile;
      resolvedMainClass = if mainClass != null then mainClass else fetched.json.mainClass;
      sources = fetched.json.sources or null;

      # Build the source arguments for scala-cli
      sourceArgs =
        if sources != null
        then builtins.concatStringsSep " " (builtins.map (s: "${src}/${s}") sources)
        else "${src}";

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
        '';
        installPhase = "true";
      };

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
}
