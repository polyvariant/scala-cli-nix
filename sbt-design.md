# sbt support: design notes

Status: working POC on the `sbt-poc` branch. Builds an sbt-managed project
inside the Nix sandbox using real sbt (the project's own version), with
per-artifact FODs and a project-independent toolchain that's content-addressed
and cacheable across projects.

## What works

- `scn lock-sbt` in an sbt project directory produces a v9 lockfile with an
  `sbt` block.
- `scala-cli-nix.buildSbtApp { pname; version; src; lockFile; }` builds the
  app in a no-network sandbox; output is a `makeWrapper`'d binary that runs
  `java -cp <user runtime classpath>:<packaged jar> <mainClass>`.
- Example: `examples/sbt/`, a Scala 3.3.4 / sbt 1.10.7 / cats-core hello
  world. Passes `nix flake check` on aarch64-darwin and x86_64-linux.

## What does NOT work (out of scope for this POC)

- sbt plugins beyond what sbt itself ships. `project/plugins.sbt` deps are
  not locked. Will fail at build time if used.
- Multi-module sbt builds (only the root project's `package` is taken).
- Non-managed (unmanaged) jars.
- Tests. `mainClass` must be uniquely determined by `Compile / mainClass`.
- Source generators that depend on plugins or external tools.
- Scala 2 user code: probably works (lock-time code path exists) but
  untested.

## Architecture

Two phases mirror the existing scala-cli flow: **lock** (outside Nix, has
network) and **build** (inside Nix sandbox, no network).

### Phase 1: `scn lock-sbt`

1. Write a temporary `scn-manifest.sbt` into the project root with a
   `scnManifest` task that prints a JSON manifest to stdout between sentinel
   lines. Run `sbt scnManifest`, capture stdout, delete the file.
2. The manifest reports: `scalaVersion`, `scalaBootVersion` (sbt's own
   Scala — read via `scala.util.Properties.versionNumberString` from inside
   the task), `sbtVersion`, optional `mainClass`, source paths, declared
   `libraryDependencies` (with cross-suffix applied, filtered to runtime
   configs, scala-stdlib jars excluded — the build path resolves those
   itself).
3. The CLI then Coursier-resolves five artifact groupings and hashes them
   via the existing `collectEntries` walker (jar + POM + parent POMs + BOM
   imports + declared-but-evicted POMs whose versions come from
   `<dependencyManagement>`):
   - `launcherJar`: `org.scala-sbt:sbt-launch:<sbtVersion>` — just the jar,
     no transitives.
   - `bootJars`: resolved-winner JARs (no POMs, no evicted versions) of
     `org.scala-sbt:sbt:<sbtVersion>`. ~83 jars for sbt 1.10.7.
   - `bootCoursierCache`: full transitive set of the same resolution
     (~295 jars + ~335 POMs). Includes parent POMs, BOMs, evicted
     versions.
   - `scalaInstance`: `scala3-compiler_3:<v>` + `scaladoc_3:<v>` for
     Scala 3, `scala-compiler:<v>` for Scala 2.
   - `compilerBridge`: `scala3-sbt-bridge:<v>` for Scala 3, or
     `compiler-bridge_<binVersion>:<sbtVersion>` for Scala 2.
4. User runtime deps go into the existing `targets.jvm.libraryDependencies`.
5. Lockfile schema is v9 (was v8); the new top-level `sbt` block is the
   only addition. Other targets/sources/resourceDirs unchanged.

### Phase 2: `buildSbtApp` in `lib.nix`

Splits into a **project-independent toolchain** and a **per-app build**.

#### Toolchain (`mkSbtToolchain`, internal)

Two read-only derivations, keyed only on `(sbtVersion, scalaBootVersion)`
plus the locked sbt-side artifact hashes:

- `sbt-boot-<sbtVersion>-<scalaBootVersion>`: symlink farm laying out
  `<scalaBootVersion>/lib/{scala-library,scala-compiler,scala-reflect,
  scala-xml_2.12-<v>}.jar` (with the *unversioned* launcher-expected names
  — these are derived from the versioned URLs in `bootJars` by filename
  mapping) plus `<scalaBootVersion>/org.scala-sbt/sbt/<sbtVersion>/<flat
  jar list>`. Only the resolved-winner jars (no POMs / evicted versions)
  end up here — sbt-launch fails with #4955 otherwise.
- `sbt-coursier-<sbtVersion>-<scalaBootVersion>`: standard Coursier cache
  layout (`https/repo1.maven.org/maven2/...`) from `bootCoursierCache` ∪
  `scalaInstance` ∪ `compilerBridge`.

Two projects on the same sbt version + scalaBootVersion + same scalaInstance
+ same locked artifact set share both these derivations in the Nix store.
Changing only the user's source code does not invalidate them.

#### Per-app build

- `sbt-userdeps-<pname>`: a third symlink farm with the user's runtime
  `libraryDependencies` in Coursier-cache layout. Keyed on `pname` + the
  user's locked deps; rebuilt per project but never per source change.
- The main `<pname>-<version>` derivation:
  1. **configurePhase**: copy (`cp -rs --no-preserve=mode`) the toolchain's
     `bootDir` and `coursierCache` into writable tmpdirs, then overlay
     `sbt-userdeps-<pname>` on top of `COURSIER_CACHE`. Sets
     `HOME=$TMPDIR/home` so sbt doesn't try `/var/empty/.sbt/...`.
  2. **buildPhase**: `java <opts> -jar <locked sbt-launch jar> package
     < /dev/null`. Critical flags:
     - `-Dsbt.boot.directory`, `-Dsbt.global.base`, `-Dsbt.ivy.home`,
       `COURSIER_CACHE` redirected to the writable tmpdirs.
     - `-Dsbt.offline=true` — blocks network in sbt's lm-coursier.
     - `-Dsbt.color=false`, `-Dsbt.supershell=false` — equivalents of
       `-no-colors -batch` (the launcher jar doesn't accept those flags
       directly, only the `sbt` shell script does).
     - `< /dev/null` for batch mode (also a shell-script-only flag).
     - We exec the locked `sbt-launch.jar` directly rather than going
       through `pkgs.sbt` because `pkgs.sbt`'s launcher is bound to its
       own embedded Scala bootstrap version and fails offline when asked
       to load a different sbt version.
  3. **installPhase**: glob `target/scala-*/*.jar` (asserts exactly one;
     single-module only), copy to `$out/share/<pname>.jar`,
     `makeWrapper` with classpath built from `libraryDependencies`'s JAR
     entries plus the packaged jar.

## Hard-won pitfalls

These cost real time and are worth preserving:

- **sbt#4955** (`unable to detect the Scala version for org.scala-sbt:sbt`):
  fires when the launcher's flat boot dir contains *any* evicted-version or
  duplicate-artifactId jar. Solution: split lockfile into `bootJars`
  (resolved winners only, what goes in the boot dir layout) and
  `bootCoursierCache` (full transitive set, for sbt's internal Coursier
  resolution).
- **Scala stdlib in `lib/`**: sbt-launch expects `scala-library.jar`,
  `scala-compiler.jar`, `scala-reflect.jar`, and the versioned
  `scala-xml_2.12-<v>.jar` in `<scalaBootVersion>/lib/`. The first three
  must have *unversioned* filenames — sbt-launch renames them when
  self-extracting from its own jar. We materialize them by filename-mapping
  the versioned Coursier URLs from `bootJars`.
- **`pkgs.sbt` is the wrong launcher**: it's bundled with one specific
  Scala bootstrap version (e.g. sbt 1.12.4 + scala-2.12.21) and will try
  to fetch a different one over the network if the project's
  `sbt.version` points elsewhere. We lock and exec the matching
  `sbt-launch-<sbtVersion>.jar` directly.
- **`-Dsbt.offline=true` matters**: without it, lm-coursier still hits the
  network at update time and chokes on the HTML error page with the
  baffling `SAXParseException: DOCTYPE is disallowed` message. The actual
  underlying error is `UnknownHostException`.
- **Inherited dependency versions**: many POMs declare `<dependency>` blocks
  without `<version>` — the version is inherited from the parent POM's
  `<dependencyManagement>`. The pre-sbt lockfile path dropped those. Our
  fix: `collectAccumulatedDependencyManagement` walks the parent chain
  building a `(g,a) → v` map, and `collectDeclaredPoms` looks up missing
  versions there. This was needed to pick up
  `jackson-annotations:2.12.1.pom` for sbt's lm-coursier on Linux.
- **BOMs via `<scope>import</scope>`**: a POM's `<dependencyManagement>` can
  import another POM (with `<type>pom</type><scope>import</scope>`). lm-coursier
  follows these recursively. Our `collectImportedBoms` walks one level
  per POM; nested imports are handled by the recursion. This caught
  `junit-bom:5.9.2` (imported from jackson-core/jackson-databind 2.15.1).
- **`scalaInstance` needs `collectEntries`**, not a per-jar collector. A
  per-jar collector grabs each jar + its POM + parent POMs but misses BOM
  imports and declared-but-evicted POMs. scalaInstance for Scala 3 pulls
  ~50 jars including jackson, which transitively forces those walks.
- **`mtime` matters for the hash cache**: pre-existing bug
  (`computeSha256` used `IO#as(Base64.encode(digest.digest()))` which
  evaluates the digest *eagerly* before the stream finalizes, returning
  the empty-file SHA for every fresh hash). Fixed in commit `3b0d63b`
  with `*> IO(...)`.

## Caching characteristics

- **Within a project**: source changes rebuild only `example-sbt-0.1.0`,
  not the toolchain pieces. Verified by changing
  `src/main/scala/Main.scala`, rebuilding, and confirming
  `sbt-boot-1.10.7-2.12.20.drv` and `sbt-coursier-1.10.7-2.12.20.drv`
  store paths are identical before/after.
- **Across projects**: identical content-addressed inputs → identical
  store paths. Two projects on sbt 1.10.7 + Scala 3.3.4 + same locked
  artifact set share both toolchain derivations.
- **Via binary cache (nix-ci.com etc.)**: only works if some build has
  pushed those paths. Currently no CI job builds the toolchain pieces
  in isolation; they're only built as inputs of `example-sbt`. To
  guarantee cross-project cache hits via a public substituter, the
  toolchain pieces would need to be reachable as a flake output (a
  separate `package` or `check`). Deferred until a second sbt example
  exists.

## File layout

- `cli/scala-cli-nix.scala`:
  - `SbtManifestSbt` — the injected sbt task source.
  - `SbtManifest` — case class for the manifest JSON.
  - `SbtLock` — top-level lockfile section (v9 addition).
  - `scalaInstanceDeps`, `compilerBridgeDep` — coord builders.
  - `computeSbtLock`, `lockSbt` — the `lock-sbt` command body.
  - `LockSbtCommand` in `ScalaCliNix` — caseapp wiring.
  - `parseDeclaredDeps` (returns empty-string version for
    no-`<version>` deps).
  - `parseDependencyManagement`,
    `collectAccumulatedDependencyManagement` — the parent-chain
    `<dependencyManagement>` walker.
- `lib.nix`:
  - `mkSbtBootDir` — boot dir symlink farm builder.
  - `mkSbtToolchain` — internal, returns `{ launcherJar; bootDir;
    coursierCache; sbtVersion; scalaBootVersion; mainClass; }` from a
    lockfile's `sbt` block.
  - `buildSbtAppImpl` — internal, builds the per-project derivation.
  - `buildSbtApp` — public, takes `{ pname; version; src; lockFile;
    attrOverrides?; }`.
- `examples/sbt/`: build.sbt + project/build.properties + a single
  `Main.scala` + `derivation.nix` calling `buildSbtApp` + lockfile.

## Open questions / next steps

- Lock the version of the toolchain artifacts independently from
  the user's deps so re-locking a user dep doesn't change the
  toolchain path. (Currently identical inputs → identical paths,
  so this is already true by construction — but if e.g. a transitive
  user dep happens to pull in a new BOM that's also in the boot tree,
  the toolchain hash could shift. Worth verifying.)
- Expose the toolchain pieces as flake-level packages so a CI job
  can build and cache them once, separately from any one app.
- Support multi-module sbt projects: pick a sub-project by name,
  or build them all in one shot.
- Support `project/plugins.sbt` locking: extend the manifest task to
  resolve the meta-build's `update` report.
- Wire `lock-sbt` into `init` (currently you have to write
  `derivation.nix` and `flake.nix` by hand for sbt projects).
- Test scope: derive `Test / sources`, `Test /
  libraryDependencies`, run sbt `test` in `passthru.tests`.
