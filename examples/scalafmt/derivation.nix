{ scala-cli-nix }:

scala-cli-nix.buildCoursierApp {
  pname = "scalafmt";
  version = "3.11.1";
  lockFile = ./scala.lock.json;
}
