{ scala-cli-nix }:

scala-cli-nix.buildScalaCliApp {
  pname = "example-scala3-shadowed-deps";
  version = "0.1.0";
  src = ./.;
  lockFile = ./scala.lock.json;
}
