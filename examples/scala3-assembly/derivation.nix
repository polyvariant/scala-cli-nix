{ scala-cli-nix }:

scala-cli-nix.buildScalaCliApp {
  pname = "example-scala3-assembly";
  version = "0.1.0";
  src = ./.;
  lockFile = ./scala.lock.json;
  packaging = "assembly";
}
