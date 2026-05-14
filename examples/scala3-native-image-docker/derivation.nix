{ dockerTools, cacert, lib, example-scala3-native-image }:

dockerTools.buildLayeredImage {
  name = "example-scala3-native-image-docker";
  tag = example-scala3-native-image.version;
  contents = [ cacert ];
  config = {
    Cmd = [ (lib.getExe example-scala3-native-image) ];
  };
}
