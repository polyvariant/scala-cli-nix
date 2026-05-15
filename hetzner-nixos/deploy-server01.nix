{ lib, writeShellApplication, deploy-rs, openssh, coreutils, hostKey }:

# `nix run`-able wrapper that invokes deploy-rs against the server01 node
# defined in flake.nix. nix-ci sets DEPLOY_SSH_KEY to the private key path;
# locally:
#
#   DEPLOY_SSH_KEY=$PWD/.secrets/nix-ci-deploy \
#     nix run .#deploy-server01
#
# Optional first argument scopes the deploy to a single profile, e.g.
# `nix run .#deploy-server01 -- .#server01.system`.
# With no args, deploy-rs ships every profile in the node.
writeShellApplication {
  name = "deploy-server01";
  runtimeInputs = [ deploy-rs openssh coreutils ];
  text = ''
    : "''${DEPLOY_SSH_KEY:?set DEPLOY_SSH_KEY to the deploy key path}"

    known_hosts=$(mktemp)
    trap 'rm -f "$known_hosts"' EXIT
    printf '%s\n' ${lib.escapeShellArg hostKey} > "$known_hosts"

    export SSH_OPTS="-i $DEPLOY_SSH_KEY -o UserKnownHostsFile=$known_hosts -o StrictHostKeyChecking=yes"

    # --skip-checks: deploy-rs's preflight runs `nix flake check`, which in
    # this repo builds every example (~30 derivations) — orthogonal to what
    # we ship to the host. CI already runs `nix flake check`; skip it here.
    target=''${1:-".#server01"}
    exec deploy "$target" --ssh-opts "$SSH_OPTS" --skip-checks
  '';
}
