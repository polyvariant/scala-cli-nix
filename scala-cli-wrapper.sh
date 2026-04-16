#!/usr/bin/env bash
set -euo pipefail

bold='\033[1m'
yellow='\033[0;33m'
dim='\033[2m'
reset='\033[0m'

LOCKFILE="scala.lock.json"

needs_lock() {
  # No lockfile at all
  if [ ! -f "$LOCKFILE" ]; then
    echo -e "${yellow}🔒${reset} No ${bold}${LOCKFILE}${reset} found, generating..." >&2
    return 0
  fi

  # Get current state from scala-cli export
  local export_json
  export_json=$(real-scala-cli export --json "$@" 2>/dev/null) || return 1

  local pwd_dir
  pwd_dir=$(pwd)

  # Compare sources
  local current_sources
  current_sources=$(echo "$export_json" | jq -S --arg pwd "$pwd_dir" '[.scopes.main.sources[] | ltrimstr($pwd + "/") | ltrimstr("/private" + $pwd + "/")]')
  local locked_sources
  locked_sources=$(jq -S '.sources // []' "$LOCKFILE")

  if [ "$current_sources" != "$locked_sources" ]; then
    echo -e "${yellow}🔒${reset} Sources changed, regenerating ${bold}${LOCKFILE}${reset}..." >&2
    return 0
  fi

  # Compare scala version
  local current_scala_version
  current_scala_version=$(echo "$export_json" | jq -r '.scalaVersion')
  local locked_scala_version
  locked_scala_version=$(jq -r '.scalaVersion' "$LOCKFILE")

  if [ "$current_scala_version" != "$locked_scala_version" ]; then
    echo -e "${yellow}🔒${reset} Scala version changed (${dim}${locked_scala_version}${reset} → ${bold}${current_scala_version}${reset}), regenerating..." >&2
    return 0
  fi

  # Compare dep coordinates hash
  local current_deps_hash
  current_deps_hash=$(echo "$export_json" | jq -r '[.scopes.main.dependencies[] | "\(.groupId):\(.artifactId.fullName):\(.version)"] | sort | .[]' | shasum | cut -d' ' -f1)
  local locked_deps_hash
  locked_deps_hash=$(jq -r '.depsHash // ""' "$LOCKFILE")

  if [ "$current_deps_hash" != "$locked_deps_hash" ]; then
    echo -e "${yellow}🔒${reset} Dependencies changed, regenerating ${bold}${LOCKFILE}${reset}..." >&2
    return 0
  fi

  return 1
}

# Check and regenerate lockfile if needed
if needs_lock "$@"; then
  scala-cli-nix lock "$@"
  echo "" >&2
fi

# Forward to real scala-cli
exec real-scala-cli "$@"
