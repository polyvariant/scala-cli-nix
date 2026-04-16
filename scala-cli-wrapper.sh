#!/usr/bin/env bash
set -euo pipefail

bold='\033[1m'
yellow='\033[0;33m'
reset='\033[0m'

LOCKFILE="scala.lock.json"

# Known scala-cli subcommands — strip these from args before passing to export/lock
SUBCOMMANDS="clean|compile|dependency-update|doc|fix|fmt|format|scalafmt|new|repl|console|package|publish|run|test|version|config|export|help|install|setup-ide|shebang|uninstall|update"

# Extract input args by stripping the subcommand (if any) from $@
input_args=("$@")
if [[ ${#input_args[@]} -gt 0 && "${input_args[0]}" =~ ^($SUBCOMMANDS)$ ]]; then
  input_args=("${input_args[@]:1}")
fi

needs_lock() {
  # No lockfile at all
  if [ ! -f "$LOCKFILE" ]; then
    echo -e "${yellow}🔒${reset} No ${bold}${LOCKFILE}${reset} found, generating..." >&2
    return 0
  fi

  # Get current state from scala-cli export
  local export_json
  export_json=$(real-scala-cli export --json "${input_args[@]}" 2>/dev/null) || return 1

  # Hash the entire export JSON — any change in sources, scala version,
  # dependencies, repos, etc. will trigger a re-lock
  local current_hash
  current_hash=$(echo "$export_json" | jq -S '.' | shasum | cut -d' ' -f1)
  local locked_hash
  locked_hash=$(jq -r '.exportHash // ""' "$LOCKFILE")

  if [ "$current_hash" != "$locked_hash" ]; then
    echo -e "${yellow}🔒${reset} Project configuration changed, regenerating ${bold}${LOCKFILE}${reset}..." >&2
    return 0
  fi

  return 1
}

# Check and regenerate lockfile if needed
if needs_lock; then
  scala-cli-nix lock "${input_args[@]}"
  echo "" >&2
fi

# Forward to real scala-cli
exec real-scala-cli "$@"
