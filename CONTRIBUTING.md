# Contributing to scala-cli-nix

## Architecture

scala-cli-nix has two phases: **lock** (runs outside Nix, has network) and **build** (runs inside Nix sandbox, no network). This split exists because Nix builds are sandboxed — JVM dependencies must be pre-fetched with known hashes.

### Phase 1: Locking (`scala-cli-nix lock`)

Implemented in `cli/scala-cli-nix.scala` (Scala 3). The CLI is itself built by `buildScalaCliApp` (self-hosting). At runtime, the CLI needs a `scala-cli` binary to shell out to: it reads the absolute path from `SCALA_CLI_NIX_SCALA_CLI` if set, otherwise falls back to whatever `scala-cli` is on PATH. The Nix-built `scala-cli-nix-cli` derivation sets that env var to a bundled fork release (see "Overlay" below).

1. `scala-cli --power list-targets <inputs>` returns the build matrix as JSON — one `{platform, scalaVersion}` entry per declared target. The CLI handles `//> using platform[s]` / `//> using scala` directives, so we don't parse them ourselves.
2. For each target in the matrix, `scala-cli export --json --platform <p> --scala-version <v> <inputs>` discovers the Scala version, source files, and direct+transitive dependencies.
3. `coursierapi.Fetch` (from `io.get-coursier:interface`) downloads all transitive JARs for both the compiler and library dependencies. No `cs` CLI needed — resolution happens in-process.
4. For each JAR, the adjacent POM is found in the Coursier cache. Parent POMs are discovered by walking the `<parent>` chain using regex. SHA-256 hashes are computed in-process via `java.security.MessageDigest` — no `nix hash file` needed.
5. The output is `scala.lock.json` with one section per target.

#### Lockfile format (`scala.lock.json`, version 7)

The lockfile uses a multi-target format. Each target (a platform/Scala version combination) has its own section under the `targets` key.

**Cross-platform example** (1 Scala version, 2 platforms):
```json
{
  "version": 7,
  "sources": ["hello.scala"],
  "targets": {
    "jvm": {
      "scalaVersion": "3.6.4",
      "platform": "JVM",
      "exportHash": "<sha1>",
      "compiler": [{"url": "...", "sha256": "base64..."}],
      "libraryDependencies": [{"url": "...", "sha256": "base64..."}]
    },
    "native": {
      "scalaVersion": "3.6.4",
      "platform": "Native",
      "exportHash": "<sha1>",
      "compiler": [...],
      "libraryDependencies": [...],
      "native": {
        "scalaNativeVersion": "0.5.10",
        "compilerPlugins": [...],
        "runtimeDependencies": [...],
        "toolingDependencies": [...]
      }
    }
  }
}
```

**Single-target example** (standard JVM project):
```json
{
  "version": 7,
  "sources": ["foo.scala"],
  "targets": {
    "jvm": {
      "scalaVersion": "3.8.3",
      "platform": "JVM",
      "exportHash": "<sha1>",
      "compiler": [...],
      "libraryDependencies": [...]
    }
  }
}
```

**Test scope** is captured per target as an optional `test` block, omitted when there are no test sources or test-only deps:
```json
"jvm": {
  ...,
  "test": {
    "sources": ["foo.test.scala"],
    "libraryDependencies": [...]
  }
}
```
`test.libraryDependencies` is the full transitive resolution of main+test deps as a single Coursier resolution (matching scala-cli's own `Scope.Test` resolution model). For JVM tests we additionally pin scala-cli's `org.virtuslab.scala-cli:test-runner_<scalaBinary>` because scala-cli adds it at test time but does not list it in `export --json`. The runner version comes from the export's `scalaCliVersion`; users on a SNAPSHOT/NIGHTLY scala-cli can pin a stable runner via `SCALA_CLI_NIX_RUNNER_VERSION`. For Native tests, `test-interface` is pulled in transitively by the test framework (e.g. munit-native), so no explicit runner dep is added.

##### Target key naming

Target keys use only the dimensions that vary:

| Platforms | Scala versions | Key format | Example |
|---|---|---|---|
| 1 | 1 | `<platform>` | `jvm` |
| many | 1 | `<platform>` | `jvm`, `native` |
| 1 | many | `<version>` | `3.6.4`, `3.5.0` |
| many | many | `<platform>-<version>` | `jvm-3.6.4`, `native-3.5.0` |

##### Field reference

- `version` — schema version (7). Checked at build time; mismatch causes a build error directing the user to re-lock.
- `sources` — top-level, shared across targets. Lists source files relative to the project root.
- `targets.<key>.exportHash` — SHA-1 hex digest of the canonicalized (sorted keys, no spaces) `scala-cli export --json` output for this target, followed by a newline. Used for per-target staleness detection.
- `targets.<key>.platform` — `"JVM"` or `"Native"`. Determines the build strategy.
- `targets.<key>.compiler` / `libraryDependencies` — JARs, their POMs, and parent POMs. Parent POMs are needed because Coursier resolves version inheritance from parent POMs during offline resolution.
- `targets.<key>.native` — present for Scala Native targets. Its three sub-fields (`compilerPlugins`, `runtimeDependencies`, `toolingDependencies`) are resolved independently because tooling dependencies target Scala 2.12, while the others use the project's Scala version.
- `targets.<key>.test` — optional. Present when the project has test sources or test-only deps. Contains `sources` (test source files) and `libraryDependencies` (full main+test classpath; reuses the target's `compiler` and `native` blocks).

#### Coursier cache path structure

Coursier stores artifacts at `<cache-root>/<protocol>/<host>/<path>`, which mirrors the URL structure. For example:

```
https://repo1.maven.org/maven2/org/typelevel/cats-core_3/2.13.0/cats-core_3-2.13.0.jar
```

becomes:

```
<cache>/https/repo1.maven.org/maven2/org/typelevel/cats-core_3/2.13.0/cats-core_3-2.13.0.jar
```

Coursier also percent-encodes special characters like `+` → `%2B` in directory and file names. The lock script accounts for this: it percent-encodes `+` when looking up POMs in the cache, and `mkCacheDir` does the same when creating the symlink layout.

The lock script reconstructs URLs by stripping the cache prefix and re-adding `://`.

### Phase 2: Building (`lib.nix`)

`lib.nix` exposes two functions:

- `buildScalaCliApp { pname, version, src, lockFile, mainClass?, target? }` — builds a single target, returning one derivation. If the lockfile has multiple targets, `target` must be specified (e.g. `target = "jvm"`). If the lockfile has exactly one target, it is selected automatically.
- `buildScalaCliApps { pname, version, src, lockFile, mainClass? }` — builds all targets, returning an attrset of derivations keyed by target name (dots normalized to underscores, e.g. `{ jvm = <drv>; native = <drv>; }`).

Each returned derivation carries `passthru.tests` — an attrset (currently `{ test = <drv>; }`) of test-runner derivations. The test derivation runs `scala-cli test --offline --server=false` against the project's test sources using a deps cache built from the lockfile's `test.libraryDependencies`. Tests are skipped (the attrset is empty) when the lockfile has no `test` section for that target. The `init` command's generated flake wires every package's `passthru.tests` into `checks` so `nix flake check` runs them.

The `mainClass` parameter (JVM only) is only needed when the project has multiple main classes — otherwise it is discovered automatically at build time.

Both functions pass `--platform` and `--scala-version` flags to `scala-cli package` so that multi-platform sources are compiled for the correct target.

#### JVM builds

1. **Per-artifact FODs**: Each `{url, sha256}` entry becomes a `builtins.fetchurl` call. Each is its own Fixed-Output Derivation in the Nix store — updating one dependency only re-downloads that one JAR.
2. **Source filtering**: The `src` is filtered using `lib.cleanSourceWith` to only include files listed in the lockfile's `sources` array. This means changes to unrelated files (e.g. `README.md`, `flake.nix`) don't trigger a rebuild.
3. **Deps cache**: All fetched artifacts are symlinked into a Coursier-compatible cache layout (`mkCacheDir`). This is set as `COURSIER_CACHE` so `scala-cli --offline` can resolve dependencies.
4. **Compilation**: `scala-cli --power package <sources> --server=false --offline --library --platform jvm --scala-version <v>` compiles user code into a small JAR (~4KB) containing only the compiled classes, no bundled dependencies.
5. **Main class discovery**: Unless `mainClass` is explicitly passed, `scala-cli --power run --main-class-list <sources> --server=false --offline` is run inside the sandbox to find the main class. If there isn't exactly one, the build fails with an error asking the user to pass `mainClass` explicitly.
6. **Wrapper**: `makeWrapper` creates an executable that runs `java -cp <all library JARs>:<compiled JAR> <mainClass>`. The classpath references individual Nix store paths — no duplication, each dep independently cacheable.

#### Scala Native builds

For Scala Native (`platform: "Native"`), the build is simpler but the dependency set is larger:

1. **Deps cache**: Compiler, library, and all three native dependency groups (compiler plugins, runtime, tooling) are symlinked into the Coursier cache. The `+` character in artifact versions (e.g., `3.6.4+0.5.10`) is percent-encoded to `%2B` to match Coursier's cache layout.
2. **Compilation + linking**: `scala-cli --power package <sources> --server=false --offline --platform scala-native --scala-version <v>` compiles and links everything into a single native executable. No `--library` flag — the entire app is linked into one binary.
3. **No wrapper**: The output is a native binary, copied directly to `$out/bin`. No JVM or classpath needed at runtime.
4. **Extra build inputs**: `clang` and `which` are needed for the native linking step.

#### Common key flags

- `--library` (JVM only, not `--standalone` or `--assembly`): produces a tiny JAR with only user code. `--standalone` would bundle all deps into one fat JAR, defeating per-artifact store granularity.
- `--server=false`: disables Bloop compilation server (can't run in sandbox).
- `--offline`: prevents any network access attempts.
- `--power`: required to use `--library`.
- `--platform jvm|scala-native`: selects the target platform. Always passed, even for single-target projects.
- `--scala-version <v>`: pins the Scala version. Always passed.

Sandbox environment variables set in the derivation:
- `COURSIER_CACHE` → the symlinked cache dir
- `COURSIER_ARCHIVE_CACHE` → `/tmp/coursier-arc`
- `SCALA_CLI_HOME` → `/tmp/scala-cli-home`
- `HOME` → `$TMPDIR/home` (Nix sets HOME to `/var/empty` which causes `FileSystemException`)

The classpath filters out POM files (`builtins.match ".*\\.jar"`) since only JARs belong on the runtime classpath.

### Overlay (`flake.nix`)

The overlay provides two packages:

| Package | Description |
|---|---|
| `scala-cli-nix` | The build library (`buildScalaCliApp` and `buildScalaCliApps`) |
| `scala-cli-nix-cli` | The CLI (init/lock commands), built by `buildScalaCliApp` itself (self-hosting); exposes both `scala-cli-nix` and the shorter `scn` alias |

`pkgs.scala-cli` itself is **not** overridden — users get whatever upstream nixpkgs ships. Likewise, the sandboxed Nix build (`lib.nix`) uses `prev.scala-cli`.

The CLI does need a specific scala-cli build at lock time (the `kubukoz/scala-cli` fork has fixes the lock workflow depends on), so `scala-cli-nix-cli` is wrapped with `makeWrapper` to set `SCALA_CLI_NIX_SCALA_CLI` to the bundled fork binary's absolute path. This is internal — the fork is never on the user's PATH and never used inside the sandbox.

### `scala-cli-nix init`

Scaffolds a new project:
- `derivation.nix` — callPackage-shaped, calls `buildScalaCliApp` for single-target projects or `buildScalaCliApps` for cross-platform/cross-version projects (detected by counting entries returned by `scala-cli --power list-targets`)
- `flake.nix` — full flake with overlay, packages, and devShell (or prints instructions if flake.nix already exists)
- `scala.lock.json` — generated via `lock`

The generated flake uses the overlay pattern so consumers just do `pkgs.callPackage ./derivation.nix {}`.

#### `--pin-self`

`init --pin-self` pins the generated `scala-cli-nix.url` to the exact revision the running CLI was built from (`github:scala-nix/scala-cli-nix?rev=<rev>` instead of the bare `github:scala-nix/scala-cli-nix`). Useful when scaffolding from a fork or feature branch so consumers don't float to whatever main looks like later.

The rev comes from `SCALA_CLI_NIX_SELF_REV`, set by the `scala-cli-nix-cli` wrapper (`flake.nix`) to `self.sourceInfo.rev`. On dirty/local checkouts `sourceInfo.rev` is absent — the env var ends up empty and `--pin-self` errors out with a message asking to drop the flag or build from a clean rev.

## Development

### Project structure

```
flake.nix              # Flake: overlay, packages, checks
lib.nix                # buildScalaCliApp / buildScalaCliApps Nix functions
cli/
  scala-cli-nix.scala  # CLI tool (init/lock), built by buildScalaCliApp
  derivation.nix       # Self-hosting derivation
  scala.lock.json      # CLI's own lockfile
examples/
  scala3/              # Scala 3 example (cats-effect hello world)
  scala2/              # Scala 2 example (os-lib hello world)
  scala-native/        # Scala Native example (hello world)
  scala-native-ce/     # Scala Native + cats-effect example
  scala-native-ce-cross/  # Cross JVM+Native example (cats-effect)
```

### Running checks

```bash
nix flake check --print-build-logs
```

This builds all example apps (Scala 2, Scala 3, Scala Native, Native+CE, and the cross JVM/Native example) and verifies their output.

### CLI tool

The CLI tool (`cli/scala-cli-nix.scala`) is written in Scala 3 and built by `buildScalaCliApp`. It uses `coursierapi` for dependency resolution, `fs2` for process execution and file I/O, and `circe` for JSON. To update the CLI's own lockfile after changing its dependencies, run:

```bash
cd cli
nix run ..# -- lock .
```

### Regenerating an example lockfile

```bash
cd examples/scala3
nix run ../..# -- lock
# or, from devShell:
scn lock
```
