# CLAUDE.md

## Project

scala-cli-nix: Nix packaging for scala-cli applications with per-artifact FOD granularity.

## Key rules

- If you're EVER about to `rm -rf` anything, STOP processing immediately - just tell me what you intended to do and stop doing any work. This also applies to writing such commands into files.
- Read `CONTRIBUTING.md` for architecture details before making changes. **Keep it up to date** when you change how the lock format, build process, overlay structure, or wrapper logic works.
- The overlay must pass `scala-cli = prev.scala-cli` to `lib.nix` — using `final.scala-cli` would pass the auto-locking wrapper, which breaks inside the Nix sandbox.
- Shell scripts are checked by shellcheck via `writeShellApplication`. Don't declare unused variables, and use bash parameter expansion instead of sed.
- `--library` (not `--standalone`) is intentional for JVM builds — it produces a tiny JAR with only user code. Dependencies stay as individual Nix store paths on the classpath.
- Both JARs and POMs must be in the lockfile. POMs are needed for offline Coursier resolution but filtered out of the runtime classpath.
- Lockfile version is 6. It uses a multi-target `targets` map (even for single-target projects). `lib.nix` exposes both `buildScalaCliApp` (single derivation) and `buildScalaCliApps` (attrset of derivations for cross projects).
- `--platform` and `--scala-version` are always passed to `scala-cli package` and `scala-cli export --json` to select the correct target from multi-platform sources.

## Commands

```bash
nix flake check --print-build-logs  # Build + test example app
nix develop                          # Enter devShell with wrapped scala-cli
cd examples/scala3 && nix run ../..# -- lock  # Regenerate example lockfile
```

# Scala coding guide

- Prefer purely functional programming. Unmanaged side effects (outside an effect system) must be avoided at all cost. Local mutation, vars and loops should be replaced with tail recursion or something akin to a State monad.
- When working with binary data, prefer the Scodec library, using as much of its combinator syntax as possible.
- Use Cellar skill to find latest dependency versions, dependency modules, available functions, sources etc.
- For concurrency, use Cats Effect IO - parTraverse, parTraverseN, Ref/Deferred, cats.effect.std etc
- Use braces (no whitespace-sensitive syntax)
- No milestone, snapshot, or otherwise unstable dependency versions unless explicitly asked for
- Ignore unused imports warnings until it's time to commit (fix them then)
- Run scala-cli fmt on the modified files before commit
