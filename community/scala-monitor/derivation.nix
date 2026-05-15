{ scala-cli-nix, fetchFromGitHub, lib, stdenv, runCommand, curl, libidn2 }:

let
  rev = "c410ca7595bff9c0e9d7a6ede5a6c66c073e9c38";

  rawSrc = fetchFromGitHub {
    owner = "polyvariant";
    repo = "scala-monitor";
    inherit rev;
    hash = "sha256-Apgsry/hGV87Wmkzvcbdkq5pA/MuLdVMwlWWFEkJ7E4=";
  };

  # `//> using computeVersion git:dynver` needs a real .git directory; the
  # GitHub tarball doesn't ship one. Strip that directive so scala-cli's
  # BuildInfo falls back to the default (projectVersion = None ⇒ "dev").
  src = runCommand "scala-monitor-src-${builtins.substring 0 7 rev}" { } ''
    cp -r ${rawSrc} $out
    chmod -R u+w $out
    sed -i '/^\/\/> using computeVersion git:dynver$/d' $out/project.scala
  '';
in
scala-cli-nix.buildScalaCliApp {
  pname = "scala-monitor";
  version = "0-unstable-${builtins.substring 0 7 rev}";
  inherit src;
  lockFile = ./scala.lock.json;

  # Version.scala uses sttp's CurlBackend → links against libcurl. On Linux,
  # libidn2 is a transitive runtime dep of OpenSSL-curl builds (CI installs
  # `libidn2-dev` explicitly).
  attrOverrides = attrs: _platform: attrs // {
    buildInputs = (attrs.buildInputs or [])
      ++ [ curl ]
      ++ lib.optional stdenv.isLinux libidn2;
  };
}
