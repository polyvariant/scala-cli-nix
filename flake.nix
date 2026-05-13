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
          example-scala3-shadowed-deps = pkgs.callPackage ./examples/scala3-shadowed-deps/derivation.nix { };
          example-scala3-native-evicted-2_13 = pkgs.callPackage ./examples/scala3-native-evicted-2.13/derivation.nix { };
        in packageTests // {
          example = pkgs.runCommand "check-example" { } ''
            output=$(${example}/bin/example)
            if [ "$output" = "hello world!" ]; then
              echo "OK: example output matches"
              touch $out
            else
              echo "FAIL: expected 'hello world!', got '$output'"
              exit 1
            fi
          '';
          example-scala3-subset = pkgs.runCommand "check-example-scala3-subset" { } ''
            output=$(${example-scala3-subset}/bin/example-scala3-subset)
            if [ "$output" = "hello from subset!" ]; then
              echo "OK: example-scala3-subset output matches"
              touch $out
            else
              echo "FAIL: expected 'hello from subset!', got '$output'"
              exit 1
            fi
          '';
          example-scala2 = pkgs.runCommand "check-example-scala2" { } ''
            output=$(${example-scala2}/bin/example-scala2)
            if [ "$output" = "hello from scala 2!" ]; then
              echo "OK: example-scala2 output matches"
              touch $out
            else
              echo "FAIL: expected 'hello from scala 2!', got '$output'"
              exit 1
            fi
          '';
          example-scala-native = pkgs.runCommand "check-example-scala-native" { } ''
            output=$(${example-scala-native}/bin/example-scala-native)
            if [ "$output" = "hello from scala native!" ]; then
              echo "OK: example-scala-native output matches"
              touch $out
            else
              echo "FAIL: expected 'hello from scala native!', got '$output'"
              exit 1
            fi
          '';
          example-scala-native-test = example-scala-native.passthru.tests.test;
          example-scala-native-ce = pkgs.runCommand "check-example-scala-native-ce" { } ''
            output=$(${example-scala-native-ce}/bin/example-scala-native-ce)
            if [ "$output" = "hello from scala native with cats-effect!" ]; then
              echo "OK: example-scala-native-ce output matches"
              touch $out
            else
              echo "FAIL: expected 'hello from scala native with cats-effect!', got '$output'"
              exit 1
            fi
          '';
          example-scala-native-ce-cross-jvm = pkgs.runCommand "check-example-scala-native-ce-cross-jvm" { } ''
            output=$(${example-scala-native-ce-cross.jvm}/bin/example-scala-native-ce-cross)
            if [ "$output" = "hello from scala jvm/native with cats-effect!" ]; then
              echo "OK: example-scala-native-ce-cross-jvm output matches"
              touch $out
            else
              echo "FAIL: expected 'hello from scala jvm/native with cats-effect!', got '$output'"
              exit 1
            fi
          '';
          example-scala-native-ce-cross-native = pkgs.runCommand "check-example-scala-native-ce-cross-native" { } ''
            output=$(${example-scala-native-ce-cross.native}/bin/example-scala-native-ce-cross)
            if [ "$output" = "hello from scala jvm/native with cats-effect!" ]; then
              echo "OK: example-scala-native-ce-cross-native output matches"
              touch $out
            else
              echo "FAIL: expected 'hello from scala jvm/native with cats-effect!', got '$output'"
              exit 1
            fi
          '';
          example-scala-resources-jvm = pkgs.runCommand "check-example-scala-resources-jvm" { } ''
            output=$(${example-scala-resources.jvm}/bin/example-scala-resources)
            if [ "$output" = "hello from embedded resource!" ]; then
              echo "OK: example-scala-resources-jvm output matches"
              touch $out
            else
              echo "FAIL: expected 'hello from embedded resource!', got '$output'"
              exit 1
            fi
          '';
          example-scala-resources-native = pkgs.runCommand "check-example-scala-resources-native" { } ''
            output=$(${example-scala-resources.native}/bin/example-scala-resources)
            if [ "$output" = "hello from embedded resource!" ]; then
              echo "OK: example-scala-resources-native output matches"
              touch $out
            else
              echo "FAIL: expected 'hello from embedded resource!', got '$output'"
              exit 1
            fi
          '';
          example-scala3-native-image = pkgs.runCommand "check-example-scala3-native-image" { } ''
            output=$(${example-scala3-native-image}/bin/example-scala3-native-image)
            if [ "$output" = "hello from graalvm native image!" ]; then
              echo "OK: example-scala3-native-image output matches"
              touch $out
            else
              echo "FAIL: expected 'hello from graalvm native image!', got '$output'"
              exit 1
            fi
          '';
          # Regression: scala-java-time transitively pulls
          # portable-scala-reflect_native0.5_2.13, which pins scalalib_native0.5_2.13
          # to 2.13.8+0.5.2. scala-cli's combined resolution at build time picks
          # that pinned version (other paths to scalalib_2.13 are excluded by
          # the newer scala3lib). If the lock generator resolves user libs
          # separately from scala-cli's native runtime deps, it lands on a
          # different scalalib_2.13 winner and `scala-cli package --offline`
          # can't find the JAR. Running the binary verifies the lock matches
          # scala-cli's build-time resolution.
          example-scala3-native-evicted-2_13 = pkgs.runCommand "check-example-scala3-native-evicted-2_13" { } ''
            output=$(${example-scala3-native-evicted-2_13}/bin/example-scala3-native-evicted-2_13)
            if [ "$output" = "hello from evicted-2.13!" ]; then
              echo "OK: example-scala3-native-evicted-2_13 output matches"
              touch $out
            else
              echo "FAIL: expected 'hello from evicted-2.13!', got '$output'"
              exit 1
            fi
          '';
          # Regression: a transitive POM (scalatest 3.2.9) declares scala-xml_3:2.0.0;
          # if that JAR ends up on the runtime classpath alongside our 2.4.0
          # winner, the binary throws NoSuchMethodError on `Node.child()` (the
          # signature changed between 2.x). Running the binary verifies the
          # classpath only carries the resolved winner.
          example-scala3-shadowed-deps = pkgs.runCommand "check-example-scala3-shadowed-deps" { } ''
            output=$(${example-scala3-shadowed-deps}/bin/example-scala3-shadowed-deps)
            if [ "$output" = "hello from shadowed-deps! grandchildren=2" ]; then
              echo "OK: example-scala3-shadowed-deps output matches"
              touch $out
            else
              echo "FAIL: expected 'hello from shadowed-deps! grandchildren=2', got '$output'"
              exit 1
            fi
          '';
        }
      );
    };
}
