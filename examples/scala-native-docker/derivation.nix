{ dockerTools, cacert, lib, example-scala-native }:

dockerTools.buildLayeredImage {
  name = "example-scala-native-docker";
  tag = example-scala-native.version;
  contents = [ cacert ];
  config = {
    Cmd = [ (lib.getExe example-scala-native) ];
  };
}
