{ lib, path, writeShellApplication, nixos-rebuild, openssh, coreutils, hostKey, targetHost, systemPath }:

# `nix run`-able wrapper that drives nixos-rebuild against server01.
#
# The target system closure (`systemPath`) is built by Nix as a regular input
# to this derivation, so the deploy script gets a pre-built store path and
# runs `nixos-rebuild switch --store-path` — no flake evaluation at runtime,
# no checkout needed. nix-ci can `nix run` this from any working directory.
#
# nix-ci sets DEPLOY_SSH_KEY to a path holding the private key
# (see ssh-keys config in nix-ci.nix). Locally you can run it the same way:
#
#   DEPLOY_SSH_KEY=$PWD/.secrets/nix-ci-deploy \
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

    # nixos-rebuild's BuildAttr resolution probes `<nixos-system>` /
    # `<nixpkgs/nixos>` via `nix-instantiate --find-file` even with
    # --store-path, so without NIX_PATH it errors before getting to the
    # store-path short-circuit. Pin nixpkgs to the same one the system
    # closure was built from; the lookup result isn't actually used.
    export NIX_PATH="nixpkgs=${path}"

    exec nixos-rebuild switch \
      --store-path ${systemPath} \
      --target-host root@${targetHost}
  '';
}
