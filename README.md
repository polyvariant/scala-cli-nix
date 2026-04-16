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

## GitHub Actions

scala-cli-nix ships a reusable composite action that regenerates `scala.lock.json` on every pull request and commits the result if anything changed.

Create `.github/workflows/lock.yml` in your repo:

```yaml
name: Update lockfile

on:
  pull_request:

jobs:
  lock:
    runs-on: ubuntu-latest
    permissions:
      contents: write   # needed to push the updated lockfile
    steps:
      - uses: actions/checkout@v4
        with:
          ref: ${{ github.head_ref }}   # check out the PR branch, not the merge commit

      - uses: scala-nix/scala-cli-nix/.github/actions/lock@main
```

On every PR this will install Nix, run `scala-cli-nix lock` (which includes `scala-cli`, `coursier`, `jq`, and `nix` — nothing extra to install), and commit `scala.lock.json` back to the PR branch if it changed.

### Options

| Input | Default | Description |
|---|---|---|
| `working-directory` | `.` | Directory containing the Scala project |
| `commit-message` | `chore: regenerate scala.lock.json` | Commit message when the lockfile is updated |
| `token` | `github.token` | Token used to push — the default works for same-repo PRs |

Example with a non-root project:

```yaml
      - uses: scala-nix/scala-cli-nix/.github/actions/lock@main
        with:
          working-directory: my-app
```

> **Fork PRs:** the default `github.token` is read-only for PRs from forks, so the push step will be skipped. Contributors from forks need to regenerate the lockfile locally with `nix run github:scala-nix/scala-cli-nix -- lock` and push it themselves.

## License

Licensed under the Apache License 2.0. See [LICENSE](LICENSE) for details.
