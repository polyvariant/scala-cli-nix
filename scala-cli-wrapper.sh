#!/usr/bin/env bash
set -euo pipefail

# Known scala-cli subcommands — strip these from args before passing to lock
SUBCOMMANDS="clean|compile|dependency-update|doc|fix|fmt|format|scalafmt|new|repl|console|package|publish|run|test|version|config|export|help|install|setup-ide|shebang|uninstall|update"

# Extract input args by stripping the subcommand (if any) from $@
input_args=("$@")
if [[ ${#input_args[@]} -gt 0 && "${input_args[0]}" =~ ^($SUBCOMMANDS)$ ]]; then
  input_args=("${input_args[@]:1}")
fi

# Lock command handles staleness check internally — if up to date, it exits quickly
scala-cli-nix lock "${input_args[@]}"

# Forward to real scala-cli
exec real-scala-cli "$@"
