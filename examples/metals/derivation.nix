{ scala-cli-nix }:

scala-cli-nix.buildCoursierApp {
  pname = "metals";
  version = "1.5.3";
  lockFile = ./scala.lock.json;
}
