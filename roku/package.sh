#!/usr/bin/env bash
# Zips the Roku channel for sideloading: build/FreeAirTV-roku-<version>.zip
# Usage: ./roku/package.sh   (needs zip, Node and Chrome)
# Same contents as package.ps1: the channel files plus the repository's channels.json,
# after redrawing the Tamil channel names to match it.
#   VERSION=0.1.6     package as this version (the manifest in the zip is changed to match)
#   SKIP_LABELS=1     use the committed labels as they are (the release workflow does this)
set -euo pipefail
roku="$(cd "$(dirname "$0")" && pwd)"
repo="$(dirname "$roku")"
field() { sed -n "s/^$1=//p" "$roku/manifest" | tr -d '\r'; }
[ "${SKIP_LABELS:-}" = 1 ] || node "$roku/art/labels.js"
version="${VERSION:-$(field major_version).$(field minor_version).$(field build_version)}"
IFS=. read -r major minor build <<< "$version"

mkdir -p "$repo/build"
zip="$repo/build/FreeAirTV-roku-$version.zip"
rm -f "$zip"
stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
cp -R "$roku/manifest" "$roku/source" "$roku/components" "$roku/images" "$stage/"
cp "$repo/channels.json" "$stage/"
sed -i -e "s/^major_version=.*/major_version=$major/" -e "s/^minor_version=.*/minor_version=$minor/" \
    -e "s/^build_version=.*/build_version=$build/" "$stage/manifest"
(cd "$stage" && zip -qr -X "$zip" manifest channels.json source components images)
echo "Packaged: $zip"
