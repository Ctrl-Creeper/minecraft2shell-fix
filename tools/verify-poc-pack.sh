#!/usr/bin/env bash
# Downloads the published minecraft2shell proof-of-concept resource pack and runs
# the scanner over every font it ships. This is the "does it catch the real
# thing" check; it never executes font code, it only reads bytes.
#
#   tools/verify-poc-pack.sh
#
# Expects a built core jar:  mvn -q -pl core -am package
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
core_jar="$root/core/target/m2sfix-core-1.0.0.jar"
pack_url="https://raw.githubusercontent.com/bikini/minecraft2shell/main/resource-pack.zip"
work="${TMPDIR:-/tmp}/m2sfix-poc-pack"

if [[ ! -f "$core_jar" ]]; then
  echo "build first: mvn -q -pl core -am package" >&2
  exit 1
fi

rm -rf "$work"
mkdir -p "$work"
echo "downloading $pack_url"
curl -sfL -o "$work/resource-pack.zip" "$pack_url"
echo "sha1: $(sha1sum "$work/resource-pack.zip" | cut -d' ' -f1)"
unzip -q "$work/resource-pack.zip" -d "$work/pack"

java -cp "$core_jar" dev.duckducktw.m2sfix.core.cli.ScanFonts "$work/pack"
