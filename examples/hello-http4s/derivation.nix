{ scala-cli-nix, s2n-tls }:

scala-cli-nix.buildScalaCliApps {
  pname = "hello-http4s";
  version = "0.1.0";
  src = ./.;
  lockFile = ./scala.lock.json;
  attrOverrides = attrs: platform:
    if platform == "Native" then attrs // {
      buildInputs = (attrs.buildInputs or [ ]) ++ [ s2n-tls ];
    } else attrs;
}
