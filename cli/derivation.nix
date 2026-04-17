{ scala-cli-nix }:

scala-cli-nix.buildScalaCliApp {
  pname = "scala-cli-nix";
  version = "0.1.0";
  src = ./.;
  lockFile = ./scala.lock.json;
}
