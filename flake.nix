{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs";
  };

  outputs = { self, nixpkgs, ... }:
    let
      forAllSystems = nixpkgs.lib.genAttrs [ "x86_64-linux" "aarch64-darwin" "x86_64-darwin" ];
    in {
      lib = ./lib.nix;

      overlays.default = final: prev: {
        scala-cli-nix = final.callPackage self.lib { scala-cli = prev.scala-cli; };

        # The real scala-cli, accessible as real-scala-cli.
        # TEMPORARY: fetched from a kubukoz/scala-cli fork release instead of nixpkgs.
        # Revert to `prev.scala-cli` (or the `local-scala-cli` checked-in binary) once
        # the upstream release catches up.
        real-scala-cli =
          let
            assets = {
              "aarch64-darwin" = {
                asset = "scala-cli-aarch64-apple-darwin.gz";
                sha256 = "037ns1lna1cwpz7cd0dhrf070y3m305md5p2slcdx4c5hhbnj7ik";
              };
              "x86_64-linux" = {
                asset = "scala-cli-x86_64-pc-linux.gz";
                sha256 = "15i2xafdqj5lplg9kknyjsfblxzndxvzapvjcqi867irkn0rqip8";
              };
            };
            asset = assets.${final.stdenv.hostPlatform.system}
              or (throw "scala-cli fork release has no asset for ${final.stdenv.hostPlatform.system}");
            src = final.fetchurl {
              url = "https://github.com/kubukoz/scala-cli/releases/download/fork-a172823/${asset.asset}";
              inherit (asset) sha256;
            };
            forked = (prev.scala-cli.override { jre = prev.jdk; }).overrideAttrs (old: {
              version = "fork-a172823";
              inherit src;
            });
          in final.runCommand "real-scala-cli" {} ''
            mkdir -p $out/bin
            ln -s ${forked}/bin/scala-cli $out/bin/real-scala-cli
            ln -s ${forked}/bin/scala-cli $out/bin/scala-cli
          '';

        # scala-cli-nix CLI tool (init/lock), built by its own buildScalaCliApp
        scala-cli-nix-cli = let
          base = final.callPackage ./cli/derivation.nix { };
        in final.symlinkJoin {
          name = "scala-cli-nix";
          paths = [ base ];
          nativeBuildInputs = [ final.makeWrapper ];
          postBuild = ''
            wrapProgram $out/bin/scala-cli-nix \
              --prefix PATH : ${final.lib.makeBinPath [ final.real-scala-cli ]}
          '';
        };

        # Wrapped scala-cli that auto-locks before forwarding
        scala-cli = final.writeShellApplication {
          name = "scala-cli";
          runtimeInputs = [ final.real-scala-cli final.scala-cli-nix-cli ];
          text = builtins.readFile ./scala-cli-wrapper.sh;
        };
      };

      packages = forAllSystems (system:
        let
          pkgs = import nixpkgs {
            inherit system;
            overlays = [ self.overlays.default ];
          };
        in {
          default = pkgs.scala-cli-nix-cli;
        }
      );

      checks = forAllSystems (system:
        let
          pkgs = import nixpkgs {
            inherit system;
            overlays = [ self.overlays.default ];
          };
          example = pkgs.callPackage ./examples/scala3/derivation.nix { };
          example-scala2 = pkgs.callPackage ./examples/scala2/derivation.nix { };
          example-scala-native = pkgs.callPackage ./examples/scala-native/derivation.nix { };
          example-scala-native-ce = pkgs.callPackage ./examples/scala-native-ce/derivation.nix { };
          example-scala-native-ce-cross = pkgs.callPackage ./examples/scala-native-ce-cross/derivation.nix { };
        in {
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
        }
      );
    };
}
