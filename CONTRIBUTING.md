# Contributing to scala-cli-nix

## Architecture

scala-cli-nix has two phases: **lock** (runs outside Nix, has network) and **build** (runs inside Nix sandbox, no network). This split exists because Nix builds are sandboxed — JVM dependencies must be pre-fetched with known hashes.

### Phase 1: Locking (`scala-cli-nix lock`)

Implemented in `cli/scala-cli-nix.scala` (Scala 3). The CLI is itself built by `buildScalaCliApp` (self-hosting). At runtime, the CLI needs a `scala-cli` binary to shell out to: it reads the absolute path from `SCALA_CLI_NIX_SCALA_CLI` if set, otherwise falls back to whatever `scala-cli` is on PATH. The Nix-built `scala-cli-nix-cli` derivation sets that env var to a bundled fork release (see "Overlay" below).

1. `scala-cli --power list-targets <inputs>` returns the build matrix as JSON — one `{platform, scalaVersion}` entry per declared target. The CLI handles `//> using platform[s]` / `//> using scala` directives, so we don't parse them ourselves.
2. For each target in the matrix, `scala-cli export --json --platform <p> --scala-version <v> <inputs>` discovers the Scala version, source files, and direct+transitive dependencies.
3. `coursierapi.Fetch` (from `io.get-coursier:interface`) downloads all transitive JARs for both the compiler and library dependencies. No `cs` CLI needed — resolution happens in-process.
4. For each JAR, the adjacent POM is found in the Coursier cache. Parent POMs are discovered by walking the `<parent>` chain, parsed with `scala-xml`. SHA-256 hashes are computed in-process via `java.security.MessageDigest` — no `nix hash file` needed. Hashes are cached at `$XDG_CACHE_HOME/scala-cli-nix/hashes.json` (default `~/.cache/scala-cli-nix/hashes.json`), keyed by absolute path with size+mtime as the freshness stamp; the cache is loaded once per `lock`/`init` invocation and persisted on the way out (including on failure). Threaded through the `sha256Base64` call sites as a `using HashCache` parameter — see `class HashCache` and `withHashCache` in `cli/scala-cli-nix.scala`.
5. The output is `scala.lock.json` with one section per target.

#### Lockfile format (`scala.lock.json`, version 8)

The lockfile uses a multi-target format. Each target (a platform/Scala version combination) has its own section under the `targets` key.

**Cross-platform example** (1 Scala version, 2 platforms):
```json
{
  "version": 8,
  "sources": ["hello.scala"],
  "resourceDirs": ["resources"],
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
        "compilerPlugins": [],
        "runtimeDependencies": [],
        "toolingDependencies": [...]
      }
    }
  }
}
```

**Single-target example** (standard JVM project):
```json
{
  "version": 8,
  "sources": ["foo.scala"],
  "resourceDirs": [],
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
    "resourceDirs": ["test-resources"],
    "libraryDependencies": [...]
  }
}
```
`test.libraryDependencies` is the full transitive resolution of main+test deps as a single Coursier resolution (matching scala-cli's own `Scope.Test` resolution model). For JVM tests, the `org.virtuslab.scala-cli:test-runner_<scalaBinary>` module appears directly in the test scope's `dependencies` returned by `scala-cli export --json` — the fork's `ScopedBuildInfo.forScope` injects it for the Test scope on JVM, with legacy version fallbacks for older Scala/Java already applied upstream. For Native tests, `test-interface` is pulled in transitively by the test framework (e.g. munit-native), but the framework's pinned version (e.g. `0.5.6`) often differs from scala-cli's bundled Scala Native runtime version (e.g. `0.5.10`). At test time `scala-cli test --offline` needs `test-interface` at scala-cli's bundled version, so the lock command also injects `org.scala-native:test-interface_native<snBinary>_<scalaBinary>:<scalaNativeVersion>` as a *direct* dep in the test-scope Coursier resolution. Being direct makes it the winner, ensuring the offline cache carries the version `scala-cli` will look up.

##### Target key naming

Target keys use only the dimensions that vary:

| Platforms | Scala versions | Key format | Example |
|---|---|---|---|
| 1 | 1 | `<platform>` | `jvm` |
| many | 1 | `<platform>` | `jvm`, `native` |
| 1 | many | `<version>` | `3.6.4`, `3.5.0` |
| many | many | `<platform>-<version>` | `jvm-3.6.4`, `native-3.5.0` |

##### Field reference

- `version` — schema version (8). Checked at build time; mismatch causes a build error directing the user to re-lock.
- `sources` — top-level, shared across targets. Lists source files relative to the project root.
- `resourceDirs` — top-level, shared across targets. Resource directories declared via `//> using resourceDir` (or equivalent CLI options), as paths relative to the project root. The build pulls each directory into the filtered source tree as a whole subtree so `scala-cli package` embeds its contents into the JAR (JVM) or the linked binary (Native).
- `targets.<key>.exportHash` — SHA-1 hex digest of the canonicalized (sorted keys, no spaces) `scala-cli export --json` output for this target, followed by a newline. Used for per-target staleness detection.
- `targets.<key>.platform` — `"JVM"` or `"Native"`. Determines the build strategy.
- `targets.<key>.compiler` / `libraryDependencies` — JARs, their POMs, and parent POMs. Parent POMs are needed because Coursier resolves version inheritance from parent POMs during offline resolution. The lock command walks each resolved POM's declared deps and materializes their POMs too (so scala-cli's offline resolver can see the full dep graph), but it does **not** materialize a JAR for any `(group, artifact)` already covered by the main resolution winner — an extra JAR at a different version on the runtime classpath would shadow the winner's classes (NoSuchMethodError at runtime).
- `targets.<key>.native` — present for Scala Native targets. Only `toolingDependencies` (the linker, scala-native-cli, etc.) is populated; `compilerPlugins` and `runtimeDependencies` are kept empty. Tooling is resolved on its own because it targets Scala 2.12 while everything else uses the project's Scala version. Scala Native's own runtime deps (`nscplugin`, `scala3lib_native`, `javalib_native`) are folded into `libraryDependencies` and resolved jointly with the user's deps — see the next bullet.
- **Combined resolution for Native targets.** scala-cli, at build time, resolves user deps and its injected native runtime deps together in one Coursier pass. The lock command must do the same: if user libs are resolved separately, a different version can "win" for a transitively-shared module than the version scala-cli picks at build time, and `scala-cli package --offline` fails to find the JAR for its winner. Concretely: `portable-scala-reflect_native0.5_2.13:1.1.3` (transitively pulled by e.g. `scala-java-time`) declares `scalalib_native0.5_2.13:2.13.8+0.5.2` directly. In a user-libs-only resolution, an older transitive `scala3lib_native` pulls a higher `scalalib_native0.5_2.13` and wins. With scala-cli's latest `scala3lib_native` added as a direct dep, it excludes `scalalib_native0.5_2.13` from its chain — the only remaining path is portable-scala-reflect's pinned 2.13.8+0.5.2, which becomes the winner. The lock-time CLI mirrors scala-cli's combined resolution to keep winners consistent. Regression test: `examples/scala3-native-evicted-2.13`.
- `targets.<key>.test` — optional. Present when the project has test sources or test-only deps. Contains `sources` (test source files), `resourceDirs` (test-scope resource directories, merged with the top-level `resourceDirs` when running tests), and `libraryDependencies` (full main+test classpath; reuses the target's `compiler` and `native` blocks).

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

`lib.nix` exposes three functions:

- `buildScalaCliApp { pname, version, src, lockFile, mainClass?, target?, nativeImage?, packaging? }` — builds a single target, returning one derivation. If the lockfile has multiple targets, `target` must be specified (e.g. `target = "jvm"`). If the lockfile has exactly one target, it is selected automatically. `nativeImage = true` switches a JVM target to GraalVM native-image output (see below); it is only valid for JVM targets. `packaging` selects the JVM output shape — `"app"` (default, classpath wrapper + thin user JAR) or `"assembly"` (fat JAR + wrapper); see below. `packaging = "assembly"` is JVM-only and mutually exclusive with `nativeImage = true`.
- `buildScalaCliApps { pname, version, src, lockFile, mainClass?, nativeImage?, packaging? }` — builds all targets, returning an attrset of derivations keyed by target name (dots normalized to underscores, e.g. `{ jvm = <drv>; native = <drv>; }`). `nativeImage` and `packaging` apply to every JVM target in the set.
- `collectChecks packages` — flattens an attrset of packages into a checks-shaped attrset by reading each package's `passthru.tests`. Each `<pkgName>` contributes one entry per test, named `<pkgName>-<testName>`. Packages without `passthru.tests` contribute nothing. Used as `checks.<system> = pkgs.scala-cli-nix.collectChecks self.packages.<system>;`.

Each derivation returned by `buildScalaCliApp(s)` carries `passthru.tests` — an attrset (currently `{ test = <drv>; }`) of test-runner derivations. The test derivation runs `scala-cli test --offline --server=false` against the project's test sources using a deps cache built from the lockfile's `test.libraryDependencies`. Tests are skipped (the attrset is empty) when the lockfile has no `test` section for that target. The `init` command's generated flake wires every package's `passthru.tests` into `checks` via `collectChecks` so `nix flake check` runs them; users with an existing `flake.nix` get the same one-liner in the printed instructions.

The `mainClass` parameter (JVM only) is only needed when the project has multiple main classes — otherwise it is discovered automatically at build time.

Both functions pass `--platform` and `--scala-version` flags to `scala-cli package` so that multi-platform sources are compiled for the correct target.

#### JVM builds

1. **Per-artifact FODs**: Each `{url, sha256}` entry becomes a `pkgs.fetchurl` call. Each is its own Fixed-Output Derivation in the Nix store — updating one dependency only re-downloads that one JAR. `pkgs.fetchurl` (not `builtins.fetchurl`) is used so Nix schedules downloads in parallel; `builtins.fetchurl` would block the single-threaded evaluator on each artifact sequentially.
2. **Source filtering**: The `src` is filtered using `lib.cleanSourceWith` to only include files listed in the lockfile's `sources` array, plus everything under each `resourceDirs` entry (so `using resourceDir` keeps working). This means changes to unrelated files (e.g. `README.md`, `flake.nix`) don't trigger a rebuild.
3. **Deps cache**: All fetched artifacts are symlinked into a Coursier-compatible cache layout (`mkCacheDir`). This is set as `COURSIER_CACHE` so `scala-cli --offline` can resolve dependencies.
4. **Compilation**: `scala-cli --power package <sources> --server=false --offline --library --platform jvm --scala-version <v>` compiles user code into a small JAR (~4KB) containing only the compiled classes, no bundled dependencies.
5. **Main class discovery**: Unless `mainClass` is explicitly passed, `scala-cli --power run --main-class-list <sources> --server=false --offline` is run inside the sandbox to find the main class. If there isn't exactly one, the build fails with an error asking the user to pass `mainClass` explicitly.
6. **Wrapper**: `makeWrapper` creates an executable that runs `java -cp <all library JARs>:<compiled JAR> <mainClass>`. The classpath references individual Nix store paths — no duplication, each dep independently cacheable.

#### JVM assembly builds (`packaging = "assembly"`)

When `packaging = "assembly"` is passed to `buildScalaCliApp(s)`, a JVM target is built as a single fat JAR bundling user code and all transitive deps:

1. **Deps cache**: Same as the regular JVM build — compiler + library JARs/POMs are symlinked into a Coursier cache.
2. **Compilation**: `scala-cli --power package <sources> --server=false --offline --assembly --platform jvm --scala-version <v> [--main-class <mc>] -o $out/share/<pname>.jar` produces an assembly JAR with `Main-Class` embedded in the manifest. No separate main-class discovery step is needed — scala-cli writes it for us. `--main-class` is forwarded only when the user passed `mainClass` explicitly; otherwise scala-cli infers it.
3. **Wrapper**: scala-cli's `--assembly` output already includes a `#!/usr/bin/env bash` preamble that locates `java` on PATH and execs it on the JAR. `makeWrapper` wraps that executable JAR at `$out/bin/<pname>`, prepending `openjdk/bin` to PATH so the preamble finds our pinned JDK.
4. **Tradeoff**: Per-artifact Nix-store granularity is lost for the *output* — every dep change rebuilds the fat JAR. Inputs (the fetchurl FODs) are still cached per-artifact. Useful for distribution where a single JAR is preferable to a directory of store paths.

`packaging = "assembly"` is rejected on Scala Native targets and is mutually exclusive with `nativeImage = true`.

#### GraalVM native-image builds (JVM targets)

When `nativeImage = true` is passed to `buildScalaCliApp(s)`, a JVM target is built as a GraalVM native image instead of a JAR + JVM wrapper:

1. **Deps cache**: Same as the JVM build path — compiler + library JARs/POMs are symlinked into a Coursier cache.
2. **GraalVM**: nixpkgs' `graalvmPackages.graalvm-ce` is provided as a build input. Its path is passed to scala-cli via `--java-home` so scala-cli does **not** try to coursier-fetch a GraalVM distribution (which would fail in the sandbox).
3. **Compilation + linking**: `scala-cli --power package <sources> --server=false --offline --native-image --java-home <graalvm> --platform jvm --scala-version <v>` compiles user code, resolves deps from the offline cache, and invokes `native-image` to produce a single binary. scala-cli's bundled reflection/resource configs (e.g. for the Scala 3 reflection machinery) are applied automatically — there's no need to maintain a `reflect-config.json` for stdlib usage.
4. **No wrapper**: The output is a native binary placed directly at `$out/bin/<pname>`. No JVM at runtime.
5. **Caveats**: `native-image` is slow and memory-hungry, so JVM-target builds with `nativeImage = true` are noticeably slower than the regular JVM build. App-specific reflection (e.g. Jackson, custom proxies) still needs user-supplied configs — pass them via scala-cli's `using` directives (`//> using packaging.graalvmArgs ...`) so they end up in the build.

`nativeImage = true` is rejected on Scala Native targets — Scala Native already produces a native binary via LLVM.

#### Scala Native builds

For Scala Native (`platform: "Native"`), the build is simpler but the dependency set is larger:

1. **Deps cache**: Compiler and library JARs/POMs are symlinked into the Coursier cache, along with native tooling (linker, scala-native-cli). In newly generated lockfiles, Scala Native's own runtime artifacts (`nscplugin`, `scala3lib_native`, `javalib_native`) live in `libraryDependencies` — see "Combined resolution for Native targets" in the lockfile reference above. `lib.nix` still folds in `native.compilerPlugins` and `native.runtimeDependencies` so older lockfiles keep building. The `+` character in artifact versions (e.g., `3.6.4+0.5.10`) is percent-encoded to `%2B` to match Coursier's cache layout.
2. **Compilation + linking**: `scala-cli --power package <sources> --server=false --offline --platform scala-native --scala-version <v>` compiles and links everything into a single native executable. No `--library` flag — the entire app is linked into one binary.
3. **No wrapper**: The output is a native binary, copied directly to `$out/bin`. No JVM or classpath needed at runtime.
4. **Extra build inputs**: `clang` and `which` are needed for the native linking step.

#### Common key flags

- `--library` (JVM, default `packaging = "app"`): produces a tiny JAR with only user code. `--standalone` would bundle all deps into one fat JAR, defeating per-artifact store granularity. The opt-in `packaging = "assembly"` mode uses `--assembly` instead, producing a fat JAR for distribution (tradeoff documented above).
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
| `scala-cli-nix-cli-native-image` | Same CLI built with `nativeImage = true`; no JVM at runtime, fast startup, slower to build |

`pkgs.scala-cli` itself is **not** overridden — users get whatever upstream nixpkgs ships. Likewise, the sandboxed Nix build (`lib.nix`) uses `prev.scala-cli`.

The CLI does need a specific scala-cli build at lock time (the `kubukoz/scala-cli` fork has fixes the lock workflow depends on), so `scala-cli-nix-cli` is wrapped with `makeWrapper` to set `SCALA_CLI_NIX_SCALA_CLI` to the bundled fork binary's absolute path. This is internal — the fork is never on the user's PATH and never used inside the sandbox.

#### Shell completions

The CLI uses case-app's `CommandsEntryPoint` with `enableCompleteCommand` and `enableCompletionsCommand`, which provides `scala-cli-nix complete <shell> ...` (used by the completion script) and `scala-cli-nix completions install/uninstall` (interactive setup writing to user rc files).

For Nix-installed users the interactive flow isn't needed: `cli/_scala-cli-nix` is a static zsh completion script (`#compdef scala-cli-nix scn`) that the overlay installs to `$out/share/zsh/site-functions/`. nixpkgs adds that path to `fpath` automatically, so completions work out of the box for both `scala-cli-nix` and the `scn` alias.

### `scala-cli-nix init`

Scaffolds a new project:
- `derivation.nix` — callPackage-shaped, calls `buildScalaCliApp` for single-target projects or `buildScalaCliApps` for cross-platform/cross-version projects (detected by counting entries returned by `scala-cli --power list-targets`)
- `flake.nix` — full flake with overlay, packages, and devShell (or prints instructions if flake.nix already exists)
- `scala.lock.json` — generated via `lock`

The generated flake uses the overlay pattern so consumers just do `pkgs.callPackage ./derivation.nix {}`.

#### `--ref`

`init --ref <value>` pins the generated `scala-cli-nix.url`. The value is auto-classified: a 40-char lowercase hex string becomes `?rev=<value>`, anything else becomes `?ref=<value>`. Empty or omitted leaves the URL bare (`github:scala-nix/scala-cli-nix`, floating on default branch). Useful when scaffolding against a feature branch or a known-good rev.

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
  scala-resources/        # Cross JVM+Native example using //> using resourceDir
  scala3-native-image/    # JVM target built as a GraalVM native image (nativeImage = true)
  scala3-assembly/        # JVM target built as a fat assembly JAR (packaging = "assembly")
  scala3-shadowed-deps/   # Regression guard: builds against a real lockfile that includes an evicted-POM coordinate; the binary calls `Node.child` to verify the runtime classpath isn't shadowed by a duplicate JAR
  scala3-native-evicted-2.13/    # Regression guard for combined Native resolution: portable-scala-reflect pins scalalib_native0.5_2.13, which would resolve differently under a user-libs-only pass — see "Combined resolution for Native targets"
  scala3-subset/          # Regression guard for subset source locking: an `unrelated.scala` with invalid Scala lives in the project root and must NOT leak into the build (the lockfile scopes sources to `src/` only)
  scala3-cross-platform-version/  # Full matrix example: JVM+Native × two Scala 3 versions (3.3.4 and 3.6.4), exercising the `<platform>-<version>` target-key format
  scala-test-weaver/        # Cross JVM+Native, weaver-cats test framework
  scala-test-munit/         # Cross JVM+Native, munit test framework
  scala-test-utest/         # Cross JVM+Native, utest test framework
  scala-test-scalatest/     # Cross JVM+Native, scalatest test framework
  scala-test-ziotest/       # Cross JVM+Native, zio-test test framework
  scala-native-docker/            # Wraps the scala-native binary into a dockerTools.buildLayeredImage (Linux-only)
  scala3-jvm-docker/              # Wraps the scala3 JVM app (wrapper + per-artifact JARs end up in the image)
  scala3-native-image-docker/     # Wraps the GraalVM native-image binary
```

Each `*-docker/` example is a thin `dockerTools.buildLayeredImage` derivation that takes the upstream app as a `callPackage` argument; no Scala source or lockfile of its own. They're wired into the root flake under `lib.optionalAttrs pkgs.stdenv.isLinux` (because `dockerTools` doesn't build on Darwin) so they at least build under `nix flake check` on aarch64-linux. The `docker-images` VM test is further gated to `x86_64-linux` because `pkgs.testers.runNixOSTest` needs KVM of the matching arch, and that's the only Linux arch we count on for CI builders. The VM test runs a NixOS guest with dockerd that loads each image and asserts the container's stdout — covering the full pattern from the README's Docker section.

### Running checks

```bash
nix flake check --print-build-logs
```

This builds all example apps (Scala 2, Scala 3, Scala Native, Native+CE, the cross JVM/Native example, and on Linux the docker images via a NixOS VM test) and verifies their output.

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
