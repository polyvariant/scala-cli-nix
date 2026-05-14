{ dockerTools, cacert, lib, example }:

dockerTools.buildLayeredImage {
  name = "example-scala3-jvm-docker";
  tag = example.version;
  contents = [ cacert ];
  config = {
    Cmd = [ (lib.getExe example) ];
  };
}
