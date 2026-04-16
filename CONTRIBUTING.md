# Contributing to scala-cli-nix

## Architecture

scala-cli-nix has two phases: **lock** (runs outside Nix, has network) and **build** (runs inside Nix sandbox, no network). This split exists because Nix builds are sandboxed — JVM dependencies must be pre-fetched with known hashes.

### Phase 1: Locking (`scala-cli-nix lock`)

Implemented in `scala-cli-nix.sh`. Requires `scala-cli`, `cs` (Coursier), `jq`, and `nix` on PATH.

1. `scala-cli export --json <inputs>` discovers the Scala version, source files, and direct+transitive dependencies.
2. `scala-cli run --main-class-list <inputs>` discovers the main class.
3. `cs fetch` downloads all transitive JARs for both the compiler (`scala3-compiler_3`) and library dependencies.
4. For each JAR **and its corresponding POM**, `nix hash file --base64` computes a hash compatible with `builtins.fetchurl`. Parent POMs are discovered by walking the `<parent>` chain in each POM and included in the lockfile.
5. The output is `scala.lock.json`.

#### Lockfile format (`scala.lock.json`)

```json
{
  "version": 1,
  "scalaVersion": "3.8.3",
  "mainClass": "Main",
  "depsHash": "<sha1 of sorted dep coordinates, for cheap staleness checks>",
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

- `version` is checked at build time — `lib.nix` rejects lockfiles that don't match the supported version. This prevents confusing errors when the lockfile format changes.
- `compiler` and `libraryDependencies` contain JARs, their POMs, and any parent POMs referenced by those POMs. Parent POMs are needed because Coursier resolves version inheritance from parent POMs during offline resolution (e.g., `jline-reader` inherits version numbers from `jline-parent`).
- `sources` lists the source files relative to the project root, recorded so the wrapper can detect when they change.
- `depsHash` is a SHA1 of the sorted `groupId:artifactId:version` coordinates — a cheap way to detect dependency changes without re-fetching.

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
2. **Deps cache**: All fetched artifacts are symlinked into a Coursier-compatible cache layout (`mkCacheDir`). This is set as `COURSIER_CACHE` so `scala-cli --offline` can resolve dependencies.
3. **Compilation**: `scala-cli --power package <sources> --server=false --offline --library` compiles user code into a small JAR (~4KB) containing only the compiled classes, no bundled dependencies.
4. **Wrapper**: `makeWrapper` creates an executable that runs `java -cp <all library JARs>:<compiled JAR> <mainClass>`. The classpath references individual Nix store paths — no duplication, each dep independently cacheable.

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
| `scala-cli-nix-cli` | The `scala-cli-nix` CLI (init/lock commands) |
| `scala-cli` | Wrapped scala-cli that auto-locks before forwarding |

**Critical**: When calling `lib.nix` from the overlay, `scala-cli` must be passed as `prev.scala-cli` (the unwrapped upstream version). Otherwise the build would use the auto-locking wrapper, which tries to run `scala-cli export` inside the sandbox (no network) and fails.

### Auto-locking wrapper (`scala-cli-wrapper.sh`)

The wrapped `scala-cli` intercepts every call and checks if the lockfile is stale before forwarding to the real scala-cli. The `needs_lock` function checks:

1. Lockfile existence
2. Source file list matches
3. Scala version matches
4. Dependency coordinates hash matches

All comparisons use data from `scala-cli export --json` (via `real-scala-cli` to avoid recursion) compared against the existing `scala.lock.json`. If anything changed, it runs `scala-cli-nix lock` before proceeding.

The wrapper forwards all CLI arguments (`"$@"`) to both the staleness check and the real scala-cli — it does not hardcode `.` as the input.

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
scala-cli-nix.sh       # CLI tool (init/lock), used via writeShellApplication
scala-cli-wrapper.sh   # Auto-locking wrapper, used via writeShellApplication
example/
  foo.scala            # Example app (cats-effect hello world)
  derivation.nix       # Example derivation
  scala.lock.json      # Example lockfile (committed)
```

### Running checks

```bash
nix flake check --print-build-logs
```

This builds the example app and verifies its output is `"hello world!"`.

### Shell scripts and shellcheck

Both `.sh` files are packaged with `writeShellApplication`, which automatically runs shellcheck. Common issues:

- Don't declare unused variables (SC2034) — the wrapper only defines the color variables it uses.
- Nix expressions like `${system}` in heredocs need `# shellcheck disable=SC2016` or single-quoting.
- Prefer bash parameter expansion over `sed` in subshells (SC2001).

### Testing the wrapper locally

```bash
# Enter devShell with wrapped scala-cli
nix develop

# In a project with .scala files:
scala-cli run .
# The wrapper will auto-generate scala.lock.json if missing/stale
```

### Regenerating the example lockfile

```bash
cd example
nix run ..# -- lock
# or, from devShell:
scala-cli-nix lock
```
