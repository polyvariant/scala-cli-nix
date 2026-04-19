{ scala-cli-nix }:

scala-cli-nix.buildScalaCliApps {
  pname = "example-scala-native-ce-cross";
  version = "0.1.0";
  src = ./.;
  lockFile = ./scala.lock.json;
}
