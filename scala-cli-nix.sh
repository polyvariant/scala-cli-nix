#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: scala-cli-nix <command>" >&2
  echo "" >&2
  echo "Commands:" >&2
  echo "  init    Scaffold flake.nix, derivation.nix, and generate lockfile" >&2
  echo "  lock    Regenerate the lockfile from scala-cli sources in ." >&2
  exit 1
}

lock() {
  local inputs="."

  # 1. Export project info
  export_json=$(scala-cli export --json "$inputs" 2>/dev/null)

  scala_version=$(echo "$export_json" | jq -r '.scalaVersion')

  # Build dep coordinates as groupId:fullName:version
  deps=$(echo "$export_json" | jq -r '.scopes.main.dependencies[] | "\(.groupId):\(.artifactId.fullName):\(.version)"')

  # 2. Discover main class
  main_class=$(scala-cli run --main-class-list "$inputs" 2>/dev/null | head -1)

  # 3. Detect Coursier cache dir
  cache_dir=$(cs about 2>&1 | grep "Cache location:" | sed 's/.*Cache location: *//')

  # 4. Fetch compiler deps
  compiler_paths=$(cs fetch "org.scala-lang:scala3-compiler_3:${scala_version}" 2>/dev/null)

  # 5. Fetch library deps (include scala3-library explicitly)
  lib_args=("org.scala-lang:scala3-library_3:${scala_version}")
  while IFS= read -r dep; do
    [ -n "$dep" ] && lib_args+=("$dep")
  done <<< "$deps"

  lib_paths=$(cs fetch "${lib_args[@]}" 2>/dev/null)

  # 6. Convert paths to {url, sha256} entries, including POMs
  path_to_entries() {
    local path="$1"
    local relative="${path#"$cache_dir"/}"
    local url
    local proto="${relative%%/*}"
    local rest="${relative#*/}"
    url="${proto}://${rest}"
    local sha256
    sha256=$(nix hash file --base64 "$path")
    printf '{"url":"%s","sha256":"%s"}' "$url" "$sha256"

    # Also include the POM file
    local pom_path="${path%.jar}.pom"
    if [ -f "$pom_path" ]; then
      local pom_url="${url%.jar}.pom"
      local pom_sha256
      pom_sha256=$(nix hash file --base64 "$pom_path")
      printf ',{"url":"%s","sha256":"%s"}' "$pom_url" "$pom_sha256"
    fi
  }

  compiler_entries=""
  while IFS= read -r path; do
    [ -z "$path" ] && continue
    entries=$(path_to_entries "$path")
    if [ -z "$compiler_entries" ]; then
      compiler_entries="$entries"
    else
      compiler_entries="$compiler_entries,$entries"
    fi
  done <<< "$compiler_paths"

  lib_entries=""
  while IFS= read -r path; do
    [ -z "$path" ] && continue
    entries=$(path_to_entries "$path")
    if [ -z "$lib_entries" ]; then
      lib_entries="$entries"
    else
      lib_entries="$lib_entries,$entries"
    fi
  done <<< "$lib_paths"

  # 7. Write lock file
  echo "{\"scalaVersion\":\"$scala_version\",\"mainClass\":\"$main_class\",\"compiler\":[$compiler_entries],\"libraryDependencies\":[$lib_entries]}" | jq . > scala-cli-nix-lock.json
  echo "Wrote scala-cli-nix-lock.json"
}

init() {
  if [ -f "flake.nix" ]; then
    echo "flake.nix already exists, aborting." >&2
    exit 1
  fi

  # Check there are scala files
  if ! ls ./*.scala >/dev/null 2>&1 && ! ls ./**/*.scala >/dev/null 2>&1; then
    echo "No .scala files found in current directory." >&2
    exit 1
  fi

  # Detect project name from directory
  pname=$(basename "$(pwd)")

  echo "Initializing scala-cli-nix project: $pname"

  # Generate derivation.nix
  cat > derivation.nix << 'DERIVATION_EOF'
{ scala-cli-nix }:

scala-cli-nix.buildScalaCliApp {
  pname = "@PNAME@";
  version = "0.1.0";
  src = ./.;
  lockFile = ./scala-cli-nix-lock.json;
}
DERIVATION_EOF
  sed -i '' "s/@PNAME@/$pname/" derivation.nix
  echo "Wrote derivation.nix"

  # Generate flake.nix
  cat > flake.nix << 'FLAKE_EOF'
{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs";
    scala-cli-nix.url = "github:polyvariant/scala-cli-nix";
  };

  outputs = { nixpkgs, scala-cli-nix, ... }:
    let
      forAllSystems = nixpkgs.lib.genAttrs [ "x86_64-linux" "aarch64-darwin" "x86_64-darwin" ];
    in {
      packages = forAllSystems (system:
        let
          pkgs = nixpkgs.legacyPackages.${system};
          scn = pkgs.callPackage scala-cli-nix.lib { };
        in {
          default = pkgs.callPackage ./derivation.nix {
            scala-cli-nix = scn;
          };
        }
      );

      devShells = forAllSystems (system:
        let
          pkgs = nixpkgs.legacyPackages.${system};
        in {
          default = pkgs.mkShell {
            buildInputs = [
              scala-cli-nix.packages.${system}.default
              pkgs.scala-cli
            ];
          };
        }
      );
    };
}
FLAKE_EOF
  echo "Wrote flake.nix"

  # Generate lockfile
  lock
}

case "${1:-}" in
  init) init ;;
  lock) lock ;;
  *) usage ;;
esac
