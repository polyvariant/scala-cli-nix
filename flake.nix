{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
  };

  outputs = { self, nixpkgs, ... }:
    let
      forAllSystems = nixpkgs.lib.genAttrs [ "x86_64-linux" "aarch64-darwin" "x86_64-darwin" ];
    in {
      lib = ./lib.nix;

      overlays.default = final: prev: {
        scala-cli-nix = final.callPackage self.lib { scala-cli = prev.scala-cli; };

        # scala-cli-nix CLI tool (init/lock), built by its own buildScalaCliApp.
        # Exposes both `scala-cli-nix` and the shorter `scn` alias.
        #
        # The CLI shells out to `scala-cli` during `lock` / `init` (for
        # `list-targets` and `export --json`). We pass the path to a
        # kubukoz/scala-cli fork release via SCALA_CLI_NIX_SCALA_CLI because the
        # fork has fixes the lock workflow depends on. This is internal —
        # neither the sandboxed build (`lib.nix`) nor the user's PATH sees the
        # fork.
        #
        # `scala-cli-nix-cli-native-image` is the same CLI built as a GraalVM
        # native image (no JVM at runtime). Slower to build, much faster to
        # start.
        inherit (
          let
            forkScalaCli =
              let
                assets = {
                  "aarch64-darwin" = {
                    asset = "scala-cli-aarch64-apple-darwin.gz";
                    sha256 = "1h2ghqp0jan7hxzqfnfyyvhyn9dpyjfak1cd73sm1k2qbhvcm1pg";
                  };
                  "x86_64-linux" = {
                    asset = "scala-cli-x86_64-pc-linux.gz";
                    sha256 = "03788lp7mycvm1p6ji7000vywhaw2f97xg8mxypj10gwy0n9hc8b";
                  };
                };
                asset = assets.${final.stdenv.hostPlatform.system}
                  or (throw "scala-cli fork release has no asset for ${final.stdenv.hostPlatform.system}");
                src = final.fetchurl {
                  url = "https://github.com/kubukoz/scala-cli/releases/download/fork-c043db1/${asset.asset}";
                  inherit (asset) sha256;
                };
              in (prev.scala-cli.override { jre = prev.jdk; }).overrideAttrs (old: {
                version = "fork-c043db1";
                inherit src;
              });
            wrapCli = name: base: final.symlinkJoin {
              inherit name;
              paths = [ base ];
              nativeBuildInputs = [ final.makeWrapper ];
              postBuild = ''
                wrapProgram $out/bin/scala-cli-nix \
                  --set-default SCALA_CLI_NIX_SCALA_CLI ${forkScalaCli}/bin/scala-cli
                ln -s scala-cli-nix $out/bin/scn

                # zsh completion: nixpkgs auto-loads files under share/zsh/site-functions
                # via the standard fpath. Covers both scala-cli-nix and the scn alias.
                install -Dm644 ${./cli/_scala-cli-nix} $out/share/zsh/site-functions/_scala-cli-nix
              '';
              # symlinkJoin drops passthru by default; forward the unwrapped
              # derivation's `passthru.tests` so consumers (and our own checks)
              # can run the CLI's munit suite via `nix flake check`.
              passthru = base.passthru or { };
              meta.mainProgram = "scala-cli-nix";
            };
          in {
            scala-cli-nix-cli = wrapCli "scala-cli-nix"
              (final.callPackage ./cli/derivation.nix { });
            scala-cli-nix-cli-native-image = wrapCli "scala-cli-nix-native-image"
              (final.callPackage ./cli/derivation.nix { nativeImage = true; });
          }
        ) scala-cli-nix-cli scala-cli-nix-cli-native-image;
      };

      packages = forAllSystems (system:
        let
          pkgs = import nixpkgs {
            inherit system;
            overlays = [ self.overlays.default ];
          };
        in {
          default = pkgs.scala-cli-nix-cli;
          inherit (pkgs) scala-cli-nix-cli-native-image;
        }
      );

      checks = forAllSystems (system:
        let
          pkgs = import nixpkgs {
            inherit system;
            overlays = [ self.overlays.default ];
          };
          # Pull `passthru.tests` from every package in `self.packages.<system>`
          # into checks via the library helper — same code path as a generated
          # user flake, so the CLI's munit suite runs under `nix flake check`.
          packageTests = pkgs.scala-cli-nix.collectChecks self.packages.${system};
          example = pkgs.callPackage ./examples/scala3/derivation.nix { };
          example-scala3-subset = pkgs.callPackage ./examples/scala3-subset/derivation.nix { };
          example-scala2 = pkgs.callPackage ./examples/scala2/derivation.nix { };
          example-scala-native = pkgs.callPackage ./examples/scala-native/derivation.nix { };
          example-scala-native-ce = pkgs.callPackage ./examples/scala-native-ce/derivation.nix { };
          example-scala-native-ce-cross = pkgs.callPackage ./examples/scala-native-ce-cross/derivation.nix { };
          example-scala-resources = pkgs.callPackage ./examples/scala-resources/derivation.nix { };
          example-scala3-native-image = pkgs.callPackage ./examples/scala3-native-image/derivation.nix { };
          example-scala3-assembly = pkgs.callPackage ./examples/scala3-assembly/derivation.nix { };
          example-scala3-shadowed-deps = pkgs.callPackage ./examples/scala3-shadowed-deps/derivation.nix { };
          example-scala3-native-evicted-2_13 = pkgs.callPackage ./examples/scala3-native-evicted-2.13/derivation.nix { };
          example-scala3-cross-platform-version = pkgs.callPackage ./examples/scala3-cross-platform-version/derivation.nix { };

          # Build a runCommand that runs `<pkg>/bin/<binName>` and asserts its
          # stdout equals `expected`. `binName` defaults to the check key,
          # which holds for every example whose pname matches its check name;
          # cross-target checks (e.g. `example-foo-jvm`) pass `binName`
          # explicitly because the underlying binary keeps the unsuffixed
          # pname.
          mkOutputCheck = { name, pkg, expected, binName ? name }:
            pkgs.runCommand "check-${name}" { } ''
              output=$(${pkg}/bin/${binName})
              expected=${nixpkgs.lib.escapeShellArg expected}
              if [ "$output" = "$expected" ]; then
                echo "OK: ${name} output matches"
                touch $out
              else
                echo "FAIL: expected '$expected', got '$output'"
                exit 1
              fi
            '';
        in packageTests // {
          example = mkOutputCheck { name = "example"; pkg = example; expected = "hello world!"; };
          example-scala3-subset = mkOutputCheck { name = "example-scala3-subset"; pkg = example-scala3-subset; expected = "hello from subset!"; };
          example-scala2 = mkOutputCheck { name = "example-scala2"; pkg = example-scala2; expected = "hello from scala 2!"; };
          example-scala-native = mkOutputCheck { name = "example-scala-native"; pkg = example-scala-native; expected = "hello from scala native!"; };
          example-scala-native-test = example-scala-native.passthru.tests.test;
          example-scala-native-ce = mkOutputCheck { name = "example-scala-native-ce"; pkg = example-scala-native-ce; expected = "hello from scala native with cats-effect!"; };
          example-scala-native-ce-cross-jvm = mkOutputCheck { name = "example-scala-native-ce-cross-jvm"; pkg = example-scala-native-ce-cross.jvm; binName = "example-scala-native-ce-cross"; expected = "hello from scala jvm/native with cats-effect!"; };
          example-scala-native-ce-cross-native = mkOutputCheck { name = "example-scala-native-ce-cross-native"; pkg = example-scala-native-ce-cross.native; binName = "example-scala-native-ce-cross"; expected = "hello from scala jvm/native with cats-effect!"; };
          example-scala-resources-jvm = mkOutputCheck { name = "example-scala-resources-jvm"; pkg = example-scala-resources.jvm; binName = "example-scala-resources"; expected = "hello from embedded resource!"; };
          example-scala-resources-native = mkOutputCheck { name = "example-scala-resources-native"; pkg = example-scala-resources.native; binName = "example-scala-resources"; expected = "hello from embedded resource!"; };
          example-scala3-native-image = mkOutputCheck { name = "example-scala3-native-image"; pkg = example-scala3-native-image; expected = "hello from graalvm native image!"; };
          example-scala3-assembly = mkOutputCheck { name = "example-scala3-assembly"; pkg = example-scala3-assembly; expected = "hello from assembly!"; };
          # Regression: scala-java-time transitively pulls
          # portable-scala-reflect_native0.5_2.13, which pins scalalib_native0.5_2.13
          # to 2.13.8+0.5.2. scala-cli's combined resolution at build time picks
          # that pinned version (other paths to scalalib_2.13 are excluded by
          # the newer scala3lib). If the lock generator resolves user libs
          # separately from scala-cli's native runtime deps, it lands on a
          # different scalalib_2.13 winner and `scala-cli package --offline`
          # can't find the JAR. Running the binary verifies the lock matches
          # scala-cli's build-time resolution.
          example-scala3-native-evicted-2_13 = mkOutputCheck { name = "example-scala3-native-evicted-2_13"; pkg = example-scala3-native-evicted-2_13; expected = "hello from evicted-2.13!"; };
          # Regression: a transitive POM (scalatest 3.2.9) declares scala-xml_3:2.0.0;
          # if that JAR ends up on the runtime classpath alongside our 2.4.0
          # winner, the binary throws NoSuchMethodError on `Node.child()` (the
          # signature changed between 2.x). Running the binary verifies the
          # classpath only carries the resolved winner.
          example-scala3-shadowed-deps = mkOutputCheck { name = "example-scala3-shadowed-deps"; pkg = example-scala3-shadowed-deps; expected = "hello from shadowed-deps! grandchildren=2"; };
        } // nixpkgs.lib.listToAttrs (builtins.map
          # 4-target matrix (JVM/Native × Scala 3.3.4/3.6.4). Each target
          # produces its own derivation (keyed `<platform>-<version>` per
          # lib.nix's nixKey), so building all four exercises the
          # platform×version key-naming path. The binaries print a single
          # shared greeting — distinguishability comes from the derivation
          # keys, not the output.
          (key: {
            name = "example-scala3-cross-platform-version-${key}";
            value = mkOutputCheck {
              name = "example-scala3-cross-platform-version-${key}";
              pkg = example-scala3-cross-platform-version."${key}";
              binName = "example-scala3-cross-platform-version";
              expected = "hello from cross-platform-version!";
            };
          })
          [ "jvm-3_3_4" "jvm-3_6_4" "native-3_3_4" "native-3_6_4" ])
      );
    };
}
