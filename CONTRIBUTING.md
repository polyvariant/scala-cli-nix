# Contributing to scala-cli-nix

## Architecture

scala-cli-nix has two phases: **lock** (runs outside Nix, has network) and **build** (runs inside Nix sandbox, no network). This split exists because Nix builds are sandboxed — JVM dependencies must be pre-fetched with known hashes.

### Phase 1: Locking (`scala-cli-nix lock`)

Implemented in `cli/scala-cli-nix.scala` (Scala 3). The CLI is itself built by `buildScalaCliApp` (self-hosting). At runtime, only `real-scala-cli` needs to be on PATH.

1. `scala-cli export --json <inputs>` discovers the Scala version, source files, and direct+transitive dependencies.
2. `scala-cli run --main-class-list <inputs>` discovers the main class.
3. `coursierapi.Fetch` (from `io.get-coursier:interface`) downloads all transitive JARs for both the compiler and library dependencies. No `cs` CLI needed — resolution happens in-process.
4. For each JAR, the adjacent POM is found in the Coursier cache. Parent POMs are discovered by walking the `<parent>` chain using regex. SHA-256 hashes are computed in-process via `java.security.MessageDigest` — no `nix hash file` needed.
5. The output is `scala.lock.json`.

#### Lockfile format (`scala.lock.json`)

```json
{
  "version": 3,
  "scalaVersion": "3.8.3",
  "mainClass": "Main",
  "exportHash": "<sha1 of sorted scala-cli export JSON>",
  "sources": ["foo.scala"],
  "compiler": [
    {"url": "https://repo1.maven.org/...", "sha256": "base64..."},
    ...
  ],
  "libraryDependencies": [
    {"url": "https://repo1.maven.org/...", "sha256": "base64..."},
    ...
  ]
}
```

- `version` is checked at build time — `lib.nix` rejects lockfiles that don't match version 3. This prevents confusing errors when the lockfile format changes.
- `compiler` and `libraryDependencies` contain JARs, their POMs, and any parent POMs referenced by those POMs. Parent POMs are needed because Coursier resolves version inheritance from parent POMs during offline resolution (e.g., `jline-reader` inherits version numbers from `jline-parent`).
- `sources` lists the source files relative to the project root.
- `exportHash` is a SHA-1 hex digest of the canonicalized (sorted keys, 2-space indent) `scala-cli export --json` output followed by a newline. The lock command uses this to detect staleness — if the hash matches, it skips re-locking.

#### Coursier cache path structure

Coursier stores artifacts at `<cache-root>/<protocol>/<host>/<path>`, which mirrors the URL structure. For example:

```
https://repo1.maven.org/maven2/org/typelevel/cats-core_3/2.13.0/cats-core_3-2.13.0.jar
```

becomes:

```
<cache>/https/repo1.maven.org/maven2/org/typelevel/cats-core_3/2.13.0/cats-core_3-2.13.0.jar
```

The lock script reconstructs URLs by stripping the cache prefix and re-adding `://`.

### Phase 2: Building (`lib.nix`)

`lib.nix` exposes `buildScalaCliApp { pname, version, src, lockFile, mainClass? }`.

1. **Per-artifact FODs**: Each `{url, sha256}` entry becomes a `builtins.fetchurl` call. Each is its own Fixed-Output Derivation in the Nix store — updating one dependency only re-downloads that one JAR.
2. **Source filtering**: The `src` is filtered using `lib.cleanSourceWith` to only include files listed in the lockfile's `sources` array. This means changes to unrelated files (e.g. `README.md`, `flake.nix`) don't trigger a rebuild.
3. **Deps cache**: All fetched artifacts are symlinked into a Coursier-compatible cache layout (`mkCacheDir`). This is set as `COURSIER_CACHE` so `scala-cli --offline` can resolve dependencies.
4. **Compilation**: `scala-cli --power package <sources> --server=false --offline --library` compiles user code into a small JAR (~4KB) containing only the compiled classes, no bundled dependencies.
5. **Wrapper**: `makeWrapper` creates an executable that runs `java -cp <all library JARs>:<compiled JAR> <mainClass>`. The classpath references individual Nix store paths — no duplication, each dep independently cacheable.

Key flags:
- `--library` (not `--standalone` or `--assembly`): produces a tiny JAR with only user code. `--standalone` would bundle all deps into one fat JAR, defeating per-artifact store granularity.
- `--server=false`: disables Bloop compilation server (can't run in sandbox).
- `--offline`: prevents any network access attempts.
- `--power`: required to use `--library`.

Sandbox environment variables set in the derivation:
- `COURSIER_CACHE` → the symlinked cache dir
- `COURSIER_ARCHIVE_CACHE` → `/tmp/coursier-arc`
- `SCALA_CLI_HOME` → `/tmp/scala-cli-home`
- `HOME` → `$TMPDIR/home` (Nix sets HOME to `/var/empty` which causes `FileSystemException`)

The classpath filters out POM files (`builtins.match ".*\\.jar"`) since only JARs belong on the runtime classpath.

### Overlay (`flake.nix`)

The overlay provides four packages:

| Package | Description |
|---|---|
| `scala-cli-nix` | The build library (`buildScalaCliApp`) |
| `real-scala-cli` | Thin wrapper around the upstream `scala-cli`, used to avoid recursion |
| `scala-cli-nix-cli` | The `scala-cli-nix` CLI (init/lock commands), built by `buildScalaCliApp` itself (self-hosting) |
| `scala-cli` | Wrapped scala-cli that auto-locks before forwarding |

**Critical**: When calling `lib.nix` from the overlay, `scala-cli` must be passed as `prev.scala-cli` (the unwrapped upstream version). Otherwise the build would use the auto-locking wrapper, which tries to run `scala-cli export` inside the sandbox (no network) and fails.

The `scala-cli-nix-cli` package is wrapped with `makeWrapper` to put `real-scala-cli` on PATH at runtime (needed for `export --json` and `run --main-class-list`).

### Auto-locking wrapper (`scala-cli-wrapper.sh`)

The wrapped `scala-cli` intercepts every call and runs `scala-cli-nix lock` before forwarding to the real scala-cli. The lock command handles the staleness check internally — if the lockfile is up to date, it exits quickly with a message.

The wrapper strips the scala-cli subcommand (e.g. `run`, `test`) from the arguments before passing them to `lock`, then forwards the original arguments to `real-scala-cli`.

### `scala-cli-nix init`

Scaffolds a new project:
- `derivation.nix` — callPackage-shaped, calls `buildScalaCliApp`
- `flake.nix` — full flake with overlay, packages, and devShell (or prints instructions if flake.nix already exists)
- `scala.lock.json` — generated via `lock`

The generated flake uses the overlay pattern so consumers just do `pkgs.callPackage ./derivation.nix {}`.

## Development

### Project structure

```
flake.nix              # Flake: overlay, packages, checks
lib.nix                # buildScalaCliApp Nix function
scala-cli-wrapper.sh   # Auto-locking wrapper, used via writeShellApplication
cli/
  scala-cli-nix.scala  # CLI tool (init/lock), built by buildScalaCliApp
  derivation.nix       # Self-hosting derivation
  scala.lock.json      # CLI's own lockfile
examples/
  scala3/              # Scala 3 example (cats-effect hello world)
  scala2/              # Scala 2 example (os-lib hello world)
```

### Running checks

```bash
nix flake check --print-build-logs
```

This builds both example apps (Scala 2 and 3) and verifies their output.

### Shell scripts and shellcheck

The wrapper (`scala-cli-wrapper.sh`) is packaged with `writeShellApplication`, which automatically runs shellcheck.

The CLI tool itself (`cli/scala-cli-nix.scala`) is written in Scala 3 and built by `buildScalaCliApp`. It uses `coursierapi` for dependency resolution, `os-lib` for subprocess execution, and `upickle` for JSON. To update the CLI's own lockfile after changing its dependencies, run:

```bash
cd cli
nix run ..# -- lock .
```

### Testing the wrapper locally

```bash
# Enter devShell with wrapped scala-cli
nix develop

# In a project with .scala files:
scala-cli run .
# The wrapper will auto-generate scala.lock.json if missing/stale
```

### Regenerating an example lockfile

```bash
cd examples/scala3
nix run ../..# -- lock
# or, from devShell:
scala-cli-nix lock
```
