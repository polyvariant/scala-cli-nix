# scala-cli-nix

Build [scala-cli](https://scala-cli.virtuslab.org/) applications as Nix derivations with per-artifact granularity.

Each JVM dependency is fetched as its own Fixed-Output Derivation via `builtins.fetchurl`, so updating one dependency only re-downloads that one JAR.

## Quick start

In a directory with `.scala` files:

```bash
nix run github:scala-nix/scala-cli-nix -- init
```

This generates:

- `flake.nix` — Nix flake with your app as the default package and a devShell
- `derivation.nix` — calls `buildScalaCliApp` from the library
- `scala.lock.json` — locked dependency hashes

Then build and run:

```bash
nix build
./result/bin/<your-app-name>
```

## Development workflow

The generated flake includes a devShell with a wrapped `scala-cli` that **automatically regenerates the lockfile** when sources, Scala version, or dependencies change:

```bash
nix develop
scala-cli run .   # lockfile is checked/regenerated before every run
```

The wrapper detects staleness by comparing the current `scala-cli export --json` output against `scala.lock.json` — if anything differs, it runs `scala-cli-nix lock` before forwarding to the real scala-cli.

## Updating dependencies manually

After changing `//> using dep` directives in your `.scala` files, you can also regenerate the lockfile explicitly:

```bash
# From the devShell (nix develop), or directly:
nix run github:scala-nix/scala-cli-nix -- lock
```

Then rebuild with `nix build`.

## How it works

1. **`scala-cli-nix lock`** runs outside Nix (with network access):
   - `scala-cli export --json .` discovers dependencies and Scala version
   - `cs fetch` downloads all transitive JARs (compiler + library deps)
   - `nix hash file --base64` computes the hash of each JAR and its POM
   - Writes `scala.lock.json` with `{url, sha256}` entries

2. **`nix build`** runs inside Nix (sandboxed, no network except FODs):
   - Each `{url, sha256}` becomes a `builtins.fetchurl` call (per-artifact FOD)
   - JARs are symlinked into a Coursier-compatible cache layout
   - `scala-cli --power package --library --offline --server=false` compiles user code
   - `makeWrapper` creates an executable referencing individual Nix store JARs on the classpath

## License

Licensed under the Apache License 2.0. See [LICENSE](LICENSE) for details.
