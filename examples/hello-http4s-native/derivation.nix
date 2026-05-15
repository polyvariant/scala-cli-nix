{ scala-cli-nix, s2n-tls }:

scala-cli-nix.buildScalaCliApp {
  pname = "hello-http4s-native";
  version = "0.1.0";
  src = ./.;
  lockFile = ./scala.lock.json;
  attrOverrides = attrs: _platform: attrs // {
    buildInputs = (attrs.buildInputs or [ ]) ++ [ s2n-tls ];
  };
}
