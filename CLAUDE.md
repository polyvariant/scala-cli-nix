# CLAUDE.md

## Project

scala-cli-nix: Nix packaging for scala-cli applications with per-artifact FOD granularity.

## Key rules

- Read `CONTRIBUTING.md` for architecture details before making changes. **Keep it up to date** when you change how the lock format, build process, overlay structure, or wrapper logic works.
- The overlay must pass `scala-cli = prev.scala-cli` to `lib.nix` — using `final.scala-cli` would pass the auto-locking wrapper, which breaks inside the Nix sandbox.
- Shell scripts are checked by shellcheck via `writeShellApplication`. Don't declare unused variables, and use bash parameter expansion instead of sed.
- `--library` (not `--standalone`) is intentional — it produces a tiny JAR with only user code. Dependencies stay as individual Nix store paths on the classpath.
- Both JARs and POMs must be in the lockfile. POMs are needed for offline Coursier resolution but filtered out of the runtime classpath.

## Commands

```bash
nix flake check --print-build-logs  # Build + test example app
nix develop                          # Enter devShell with wrapped scala-cli
cd example && nix run ..# -- lock   # Regenerate example lockfile
```
