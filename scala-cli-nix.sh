#!/usr/bin/env bash
set -euo pipefail

# Colors
bold='\033[1m'
green='\033[0;32m'
blue='\033[0;34m'
yellow='\033[0;33m'
red='\033[0;31m'
dim='\033[2m'
reset='\033[0m'

info() { echo -e "${blue}ℹ${reset} $*"; }
success() { echo -e "${green}✅${reset} $*"; }
warn() { echo -e "${yellow}⚠️${reset} $*"; }
error() { echo -e "${red}❌${reset} $*" >&2; }
step() { echo -e "${bold}👉 $*${reset}"; }

usage() {
  echo -e "${bold}scala-cli-nix${reset} — Nix packaging for scala-cli apps"
  echo ""
  echo -e "  ${bold}init${reset}    Scaffold flake.nix, derivation.nix, and generate lockfile"
  echo -e "  ${bold}lock${reset}    Regenerate the lockfile from scala-cli sources in ."
  exit 1
}

# Use real-scala-cli if available (avoids recursion with wrapper)
if command -v real-scala-cli &>/dev/null; then
  SCALA_CLI=real-scala-cli
else
  SCALA_CLI=scala-cli
fi

lock() {
  local inputs=("${@:-.}")

  step "Exporting project info..."
  export_json=$($SCALA_CLI export --json "${inputs[@]}" 2>/dev/null)

  scala_version=$(echo "$export_json" | jq -r '.scalaVersion')
  info "Scala version: ${bold}${scala_version}${reset}"

  # Extract sources as relative paths
  pwd_dir=$(pwd)
  sources_json=$(echo "$export_json" | jq --arg pwd "$pwd_dir" '[.scopes.main.sources[] | ltrimstr($pwd + "/") | ltrimstr("/private" + $pwd + "/")]')
  source_count=$(echo "$sources_json" | jq 'length')
  info "Sources: ${bold}${source_count}${reset} files"

  deps=$(echo "$export_json" | jq -r '.scopes.main.dependencies[] | "\(.groupId):\(.artifactId.fullName):\(.version)"')
  dep_count=$(echo "$deps" | grep -c . || true)
  info "Found ${bold}${dep_count}${reset} dependencies"

  step "Discovering main class..."
  main_class=$($SCALA_CLI run --main-class-list "${inputs[@]}" 2>/dev/null | head -1)
  info "Main class: ${bold}${main_class}${reset}"

  step "Detecting Coursier cache..."
  cache_dir=$(cs about 2>&1 | grep "Cache location:" | sed 's/.*Cache location: *//')
  info "Cache: ${dim}${cache_dir}${reset}"

  step "Fetching compiler dependencies..."
  compiler_paths=$(cs fetch "org.scala-lang:scala3-compiler_3:${scala_version}" 2>/dev/null)
  compiler_count=$(echo "$compiler_paths" | grep -c . || true)
  info "Compiler: ${bold}${compiler_count}${reset} artifacts"

  step "Fetching library dependencies..."
  lib_args=("org.scala-lang:scala3-library_3:${scala_version}")
  while IFS= read -r dep; do
    [ -n "$dep" ] && lib_args+=("$dep")
  done <<< "$deps"

  lib_paths=$(cs fetch "${lib_args[@]}" 2>/dev/null)
  lib_count=$(echo "$lib_paths" | grep -c . || true)
  info "Libraries: ${bold}${lib_count}${reset} artifacts (transitive)"

  step "Hashing artifacts..."
  path_to_entry() {
    local path="$1"
    local relative="${path#"$cache_dir"/}"
    local proto="${relative%%/*}"
    local rest="${relative#*/}"
    local url="${proto}://${rest}"
    local sha256
    sha256=$(nix hash file --base64 "$path")
    printf '{"url":"%s","sha256":"%s"}' "$url" "$sha256"
  }

  path_to_entries() {
    local path="$1"
    path_to_entry "$path"

    local pom_path="${path%.jar}.pom"
    if [ -f "$pom_path" ]; then
      printf ','
      path_to_entry "$pom_path"
    fi
  }

  # Walk the parent POM chain for a given POM file, emitting JSON entries
  # for each parent POM found in the Coursier cache.
  collect_parent_poms() {
    local pom_path="$1"
    while [ -f "$pom_path" ]; do
      local parent_group parent_artifact parent_version
      parent_group=$(sed -n '/<parent>/,/<\/parent>/s/.*<groupId>\(.*\)<\/groupId>.*/\1/p' "$pom_path" | head -1)
      parent_artifact=$(sed -n '/<parent>/,/<\/parent>/s/.*<artifactId>\(.*\)<\/artifactId>.*/\1/p' "$pom_path" | head -1)
      parent_version=$(sed -n '/<parent>/,/<\/parent>/s/.*<version>\(.*\)<\/version>.*/\1/p' "$pom_path" | head -1)

      [ -z "$parent_group" ] && break

      local parent_dir="${parent_group//.//}"
      local parent_pom_path="$cache_dir/https/repo1.maven.org/maven2/${parent_dir}/${parent_artifact}/${parent_version}/${parent_artifact}-${parent_version}.pom"

      [ ! -f "$parent_pom_path" ] && break

      printf ','
      path_to_entry "$parent_pom_path"

      pom_path="$parent_pom_path"
    done
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
    # Collect parent POMs for the POM adjacent to this JAR
    local pom_path="${path%.jar}.pom"
    if [ -f "$pom_path" ]; then
      parent_entries=$(collect_parent_poms "$pom_path")
      compiler_entries="$compiler_entries$parent_entries"
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
    # Collect parent POMs for the POM adjacent to this JAR
    local pom_path="${path%.jar}.pom"
    if [ -f "$pom_path" ]; then
      parent_entries=$(collect_parent_poms "$pom_path")
      lib_entries="$lib_entries$parent_entries"
    fi
  done <<< "$lib_paths"

  # Compute a hash of the entire export JSON for staleness checks
  export_hash=$(echo "$export_json" | jq -S '.' | shasum | cut -d' ' -f1)

  step "Writing lockfile..."
  jq -n \
    --argjson version 2 \
    --arg scalaVersion "$scala_version" \
    --arg mainClass "$main_class" \
    --arg exportHash "$export_hash" \
    --argjson sources "$sources_json" \
    --argjson compiler "[$compiler_entries]" \
    --argjson libraryDependencies "[$lib_entries]" \
    '{version: $version, scalaVersion: $scalaVersion, mainClass: $mainClass, exportHash: $exportHash, sources: $sources, compiler: $compiler, libraryDependencies: $libraryDependencies}' \
    > scala.lock.json
  success "Wrote ${bold}scala.lock.json${reset}"
}

init() {
  if ! ls ./*.scala >/dev/null 2>&1 && ! ls ./**/*.scala >/dev/null 2>&1; then
    error "No .scala files found in current directory."
    exit 1
  fi

  pname=$(basename "$(pwd)")
  generated_files=()

  echo ""
  echo -e "🚀 ${bold}Initializing scala-cli-nix project: ${green}${pname}${reset}"
  echo ""

  # Generate derivation.nix
  if [ -f "derivation.nix" ]; then
    warn "derivation.nix already exists, skipping."
  else
    step "Writing derivation.nix..."
    generated_files+=("derivation.nix")
    cat > derivation.nix << DERIVATION_EOF
{ scala-cli-nix }:

scala-cli-nix.buildScalaCliApp {
  pname = "${pname}";
  version = "0.1.0";
  src = ./.;
  lockFile = ./scala.lock.json;
}
DERIVATION_EOF
    success "Wrote ${bold}derivation.nix${reset}"
  fi

  # Generate or advise on flake.nix
  if [ -f "flake.nix" ]; then
    echo ""
    warn "flake.nix already exists. Add the following to your flake:"
    echo ""
    echo -e "  ${bold}1.${reset} Add the input:"
    echo ""
    echo -e "    ${dim}scala-cli-nix.url = \"github:scala-nix/scala-cli-nix\";${reset}"
    echo -e "    ${dim}scala-cli-nix.inputs.nixpkgs.follows = \"nixpkgs\";${reset}"
    echo ""
    echo -e "  ${bold}2.${reset} Apply the overlay to nixpkgs:"
    echo ""
    echo -e "    ${dim}pkgs = import nixpkgs {${reset}"
    echo -e "    ${dim}  inherit system;${reset}"
    echo -e "    ${dim}  overlays = [ scala-cli-nix.overlays.default ];${reset}"
    echo -e "    ${dim}};${reset}"
    echo ""
    echo -e "  ${bold}3.${reset} Add the package:"
    echo ""
    echo -e "    ${dim}packages.default = pkgs.callPackage ./derivation.nix { };${reset}"
    echo ""
    echo -e "  ${bold}4.${reset} Add to your devShell (uses wrapped scala-cli with auto-locking):"
    echo ""
    echo -e "    ${dim}pkgs.scala-cli${reset}"
    echo -e "    ${dim}pkgs.scala-cli-nix-cli${reset}"
    echo ""
  else
    step "Writing flake.nix..."
    generated_files+=("flake.nix")
    cat > flake.nix << 'FLAKE_EOF'
{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs";
    scala-cli-nix.url = "github:scala-nix/scala-cli-nix";
    scala-cli-nix.inputs.nixpkgs.follows = "nixpkgs";
  };

  outputs = { nixpkgs, scala-cli-nix, ... }:
    let
      forAllSystems = nixpkgs.lib.genAttrs [ "x86_64-linux" "aarch64-darwin" "x86_64-darwin" ];
    in {
      packages = forAllSystems (system:
        let
          pkgs = import nixpkgs {
            inherit system;
            overlays = [ scala-cli-nix.overlays.default ];
          };
        in {
          default = pkgs.callPackage ./derivation.nix { };
        }
      );

      devShells = forAllSystems (system:
        let
          pkgs = import nixpkgs {
            inherit system;
            overlays = [ scala-cli-nix.overlays.default ];
          };
        in {
          default = pkgs.mkShell {
            buildInputs = [
              pkgs.scala-cli
              pkgs.scala-cli-nix-cli
            ];
          };
        }
      );
    };
}
FLAKE_EOF
    success "Wrote ${bold}flake.nix${reset}"
  fi

  echo ""
  lock
  echo ""

  generated_files+=("scala.lock.json")

  # Stage generated files so nix build can see them
  if git rev-parse --is-inside-work-tree &>/dev/null; then
    step "Staging generated files..."
    git add "${generated_files[@]}"
    success "Staged ${bold}${generated_files[*]}${reset}"
  fi

  echo -e "🎉 ${bold}Done!${reset} Run ${green}nix build${reset} to build your project."
}

case "${1:-}" in
  init) shift; init "$@" ;;
  lock) shift; lock "$@" ;;
  *) usage ;;
esac
