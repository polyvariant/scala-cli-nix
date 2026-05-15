#!/usr/bin/env bash
# Convenience SSH into server01 using the deploy key. Extra args are
# forwarded (e.g. `./ssh.sh journalctl -u sshd -n 50`).
#
# Reads the server IP from `tofu output` (preferred — survives
# refresh-driven IP changes), falling back to parsing terraform.tfstate
# directly so this works without a network round-trip.
set -euo pipefail

here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)

ip=$(tofu -chdir="$here" output -raw ipv4 2>/dev/null || true)
if [[ -z "$ip" ]]; then
  ip=$(jq -r '.resources[] | select(.type=="hcloud_server" and .name=="server01") | .instances[0].attributes.ipv4_address' "$here/terraform.tfstate")
fi
if [[ -z "$ip" || "$ip" == "null" ]]; then
  echo "ssh.sh: could not determine server01 IP — is the box provisioned?" >&2
  exit 1
fi

exec ssh \
  -i "$here/.secrets/nix-ci-deploy" \
  -o IdentitiesOnly=yes \
  "root@$ip" \
  "$@"
