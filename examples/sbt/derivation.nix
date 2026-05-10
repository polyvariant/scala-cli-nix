{ scala-cli-nix }:

scala-cli-nix.buildScalaCliApp {
  pname = "example-sbt";
  version = "0.1.0";
  src = ./.;
  lockFile = ./scala.lock.json;
}
