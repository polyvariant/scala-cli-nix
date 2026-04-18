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

        # The real scala-cli, accessible as real-scala-cli
        real-scala-cli =
          let
            bin = ./local-scala-cli;
          in final.runCommand "real-scala-cli" {} ''
            mkdir -p $out/bin
            cp ${bin} $out/bin/real-scala-cli
            chmod +x $out/bin/real-scala-cli
            cp ${bin} $out/bin/scala-cli
            chmod +x $out/bin/scala-cli
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
        }
      );
    };
}
