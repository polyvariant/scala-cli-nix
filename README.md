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

## Updating dependencies

After changing `//> using dep` directives in your `.scala` files, regenerate the lockfile:

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
