{ scala-cli-nix }:

scala-cli-nix.buildScalaCliApp {
  pname = "example-scala-native-ce";
  version = "0.1.0";
  src = ./.;
  lockFile = ./scala.lock.json;
}
