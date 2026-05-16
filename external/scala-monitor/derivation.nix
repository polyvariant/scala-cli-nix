{ scala-cli-nix, fetchFromGitHub, runCommand, lib, stdenv, curl, libidn2 }:

scala-cli-nix.buildScalaCliApp {
  pname = "scala-monitor";
  version = "0.5.6";
  # The upstream `project.scala` declares `//> using computeVersion git:dynver`,
  # which scala-cli refuses to evaluate without a real .git directory. Strip
  # it so the build matches what `scn init` saw at lock time (BuildInfo falls
  # back to `projectVersion = None`).
  src =
    let
      raw = fetchFromGitHub {
        owner = "polyvariant";
        repo = "scala-monitor";
        rev = "c410ca7595bff9c0e9d7a6ede5a6c66c073e9c38";
        hash = "sha256-Apgsry/hGV87Wmkzvcbdkq5pA/MuLdVMwlWWFEkJ7E4=";
      };
    in runCommand "scala-monitor-src-c410ca7" { } ''
      cp -r ${raw} $out
      chmod -R u+w $out
      find $out -name '*.scala' -exec sed -i '/^\/\/> using computeVersion git:dynver$/d' {} +
    '';
  lockFile = ./scala.lock.json;

  # Version.scala uses sttp's CurlBackend to check for updates → links against
  # libcurl. On Linux, libidn2 is a transitive runtime dep of OpenSSL-curl
  # builds (upstream CI installs `libidn2-dev` explicitly).
  attrOverrides = attrs: _platform: attrs // {
    buildInputs = (attrs.buildInputs or [])
      ++ [ curl ]
      ++ lib.optional stdenv.isLinux libidn2;
  };
}
