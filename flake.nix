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
        real-scala-cli = final.writeShellScriptBin "real-scala-cli" ''
          exec ${prev.scala-cli}/bin/scala-cli "$@"
        '';

        # scala-cli-nix CLI tool (init/lock)
        scala-cli-nix-cli = final.writeShellApplication {
          name = "scala-cli-nix";
          runtimeInputs = [ final.real-scala-cli final.coursier final.jq final.nix ];
          text = builtins.readFile ./scala-cli-nix.sh;
        };

        # Wrapped scala-cli that auto-locks before forwarding
        scala-cli = final.writeShellApplication {
          name = "scala-cli";
          runtimeInputs = [ final.real-scala-cli final.scala-cli-nix-cli final.jq ];
          text = builtins.readFile ./scala-cli-wrapper.sh;
        };
      };

      packages = forAllSystems (system:
        let
          pkgs = nixpkgs.legacyPackages.${system};
        in {
          default = pkgs.writeShellApplication {
            name = "scala-cli-nix";
            runtimeInputs = with pkgs; [ scala-cli coursier jq nix ];
            text = builtins.readFile ./scala-cli-nix.sh;
          };
        }
      );

      checks = forAllSystems (system:
        let
          pkgs = import nixpkgs {
            inherit system;
            overlays = [ self.overlays.default ];
          };
          example = pkgs.callPackage ./example/derivation.nix { };
          example-scala2 = pkgs.callPackage ./example-scala2/derivation.nix { };
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
        }
      );
    };
}
