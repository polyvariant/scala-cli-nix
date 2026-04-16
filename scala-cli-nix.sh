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

lock() {
  local inputs="."

  step "Exporting project info..."
  export_json=$(scala-cli export --json "$inputs" 2>/dev/null)

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
  main_class=$(scala-cli run --main-class-list "$inputs" 2>/dev/null | head -1)
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

  step "Writing lockfile..."
  jq -n \
    --arg scalaVersion "$scala_version" \
    --arg mainClass "$main_class" \
    --argjson sources "$sources_json" \
    --argjson compiler "[$compiler_entries]" \
    --argjson libraryDependencies "[$lib_entries]" \
    '{scalaVersion: $scalaVersion, mainClass: $mainClass, sources: $sources, compiler: $compiler, libraryDependencies: $libraryDependencies}' \
    > scala.lock.json
  success "Wrote ${bold}scala.lock.json${reset}"
}

init() {
  if ! ls ./*.scala >/dev/null 2>&1 && ! ls ./**/*.scala >/dev/null 2>&1; then
    error "No .scala files found in current directory."
    exit 1
  fi

  pname=$(basename "$(pwd)")

  echo ""
  echo -e "🚀 ${bold}Initializing scala-cli-nix project: ${green}${pname}${reset}"
  echo ""

  # Generate derivation.nix
  if [ -f "derivation.nix" ]; then
    warn "derivation.nix already exists, skipping."
  else
    step "Writing derivation.nix..."
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
    echo -e "    ${dim}scala-cli-nix.url = \"github:polyvariant/scala-cli-nix\";${reset}"
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
    echo -e "  ${bold}4.${reset} Optionally, add to your devShell:"
    echo ""
    # shellcheck disable=SC2016
    echo -e "    ${dim}scala-cli-nix.packages.\${system}.default${reset}"
    echo ""
  else
    step "Writing flake.nix..."
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
    success "Wrote ${bold}flake.nix${reset}"
  fi

  echo ""
  lock
  echo ""
  echo -e "🎉 ${bold}Done!${reset} Run ${green}nix build${reset} to build your project."
}

case "${1:-}" in
  init) init ;;
  lock) lock ;;
  *) usage ;;
esac
