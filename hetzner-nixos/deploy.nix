{ lib, writeShellApplication, nixos-rebuild, openssh, coreutils, hostKey, targetHost }:

# `nix run`-able wrapper that drives nixos-rebuild against server01.
#
# nix-ci sets DEPLOY_SSH_KEY to a path holding the private key
# (see ssh-keys config in nix-ci.nix). Locally you can run it the same way:
#
#   DEPLOY_SSH_KEY=$PWD/hetzner-nixos/.secrets/nix-ci-deploy \
#     nix run .#deploy-server01
writeShellApplication {
  name = "deploy-server01";
  runtimeInputs = [ nixos-rebuild openssh coreutils ];
  text = ''
    : "''${DEPLOY_SSH_KEY:?set DEPLOY_SSH_KEY to the deploy key path}"

    known_hosts=$(mktemp)
    trap 'rm -f "$known_hosts"' EXIT
    printf '%s\n' ${lib.escapeShellArg hostKey} > "$known_hosts"

    export NIX_SSHOPTS="-i $DEPLOY_SSH_KEY -o UserKnownHostsFile=$known_hosts -o StrictHostKeyChecking=yes"

    # No --build-host: build locally in-process. (--build-host localhost
    # would still ssh to itself and our pinned known_hosts only covers
    # server01.)
    exec nixos-rebuild switch \
      --flake "''${FLAKE:-.}#server01" \
      --target-host root@${targetHost}
  '';
}
