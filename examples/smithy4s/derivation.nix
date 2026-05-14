{ scala-cli-nix }:

scala-cli-nix.buildCoursierApp {
  pname = "smithy4s";
  version = "0.19.4";
  lockFile = ./scala.lock.json;
}
