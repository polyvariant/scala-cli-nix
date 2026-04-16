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
        scala-cli-nix = final.callPackage self.lib { };
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
        }
      );
    };
}
