#!/usr/bin/env bash
# Builds FreeAir TV on Linux (GitHub Actions) without Gradle, using only Android SDK build tools.
# The same steps as build.ps1.
#
#   VERSION=1.2.0 KEYSTORE_FILE=release.keystore KEYSTORE_PASSWORD=... ./build.sh
#
# Output: build/FreeAirTV-$VERSION.apk and build/FreeAirTV-$VERSION.apk.sha256
# Libraries are listed with their checksums in deps.lock, downloaded into .deps/ and verified.
set -euo pipefail

VERSION="${VERSION:-0.0.0-dev}"
SDK="${ANDROID_HOME:?ANDROID_HOME is not set}"
BT="$SDK/build-tools/${BUILD_TOOLS:-35.0.0}"
PLATFORM="$SDK/platforms/android-35/android.jar"
ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/app/src/main"
OUT="$ROOT/build"
DEPS="$ROOT/.deps"
MIN_SDK=22
TARGET_SDK=35
KEY_ALIAS="${KEY_ALIAS:-freeairtv}"
: "${KEYSTORE_FILE:?KEYSTORE_FILE is not set}"
: "${KEYSTORE_PASSWORD:?KEYSTORE_PASSWORD is not set}"

# versionCode from the version: 1.2.3 -> 10203.
IFS=. read -r MAJ MIN PAT <<<"$(echo "$VERSION" | sed -E 's/[^0-9.].*$//')"
VERSION_CODE=$(( ${MAJ:-0} * 10000 + ${MIN:-0} * 100 + ${PAT:-0} ))
[ "$VERSION_CODE" -ge 1 ] || VERSION_CODE=1

# The repository the app updates from: GitHub Actions sets GITHUB_REPOSITORY.
REPO="${GITHUB_REPOSITORY:-${FREEAIRTV_REPO:-OWNER/free-air-tv}}"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/libres" "$OUT/gen-res/raw" "$OUT/gen-res/values" "$DEPS"
echo "FreeAir TV $VERSION (code $VERSION_CODE), updates from github.com/$REPO"

echo "0/6 Libraries"
JARS=()
LIB_RES=()
LIB_PKGS=()
while read -r url sha; do
  case "$url" in ''|'#'*) continue;; esac
  file="$DEPS/$(basename "$url")"
  if [ ! -f "$file" ] || [ "$(sha256sum "$file" | cut -d' ' -f1)" != "$sha" ]; then
    curl -sSfL --retry 3 -o "$file" "$url"
  fi
  if [ "$(sha256sum "$file" | cut -d' ' -f1)" != "$sha" ]; then
    rm -f "$file"; echo "Checksum mismatch for $url" >&2; exit 1
  fi
  case "$file" in
    *.jar) JARS+=("$file"); continue;;
  esac
  # An .aar: its code is classes.jar; its resources are compiled and linked under its own package.
  dir="${file%.aar}"
  [ -d "$dir" ] || unzip -q -o "$file" -d "$dir"
  [ -f "$dir/classes.jar" ] && JARS+=("$dir/classes.jar")
  for j in "$dir"/libs/*.jar; do [ -f "$j" ] && JARS+=("$j"); done
  pkg=$(grep -o 'package="[^"]*"' "$dir/AndroidManifest.xml" | head -1 | cut -d'"' -f2)
  if [ -d "$dir/res" ] && [ -n "$(find "$dir/res" -type f | head -1)" ]; then
    zipf="$OUT/libres/$(basename "$dir").zip"
    "$BT/aapt2" compile --dir "$dir/res" -o "$zipf"
    LIB_RES+=("$zipf")
    LIB_PKGS+=("$pkg")
  fi
done < "$ROOT/deps.lock"

echo "1/6 Compiling resources"
cp "$ROOT/channels.json" "$OUT/gen-res/raw/channels.json"
cat > "$OUT/gen-res/values/config.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="github_repo" translatable="false">$REPO</string>
    <string name="github_branch" translatable="false">main</string>
</resources>
EOF
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/res.zip"
"$BT/aapt2" compile --dir "$OUT/gen-res" -o "$OUT/gen-res.zip"

echo "2/6 Linking manifest and resources"
LINK=(link -I "$PLATFORM" --manifest "$SRC/AndroidManifest.xml"
  --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK"
  --version-code "$VERSION_CODE" --version-name "$VERSION"
  --auto-add-overlay --java "$OUT/gen" -o "$OUT/unaligned.apk")
if [ ${#LIB_PKGS[@]} -gt 0 ]; then LINK+=(--extra-packages "$(IFS=:; echo "${LIB_PKGS[*]}")"); fi
# Library resources first, so the app's own resources override them.
for z in "${LIB_RES[@]}"; do LINK+=(-R "$z"); done
LINK+=("$OUT/res.zip" "$OUT/gen-res.zip")
"$BT/aapt2" "${LINK[@]}"

echo "3/6 Compiling Java"
find "$SRC/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
CP="$BT/core-lambda-stubs.jar$(printf ':%s' "${JARS[@]}")"
javac -source 8 -target 8 -Xlint:-options -nowarn -encoding UTF-8 \
  -bootclasspath "$PLATFORM" -classpath "$CP" \
  -d "$OUT/classes" @"$OUT/sources.txt"

echo "4/6 Converting to DEX"
{ find "$OUT/classes" -name '*.class'; printf '%s\n' "${JARS[@]}"; } > "$OUT/d8-inputs.txt"
"$BT/d8" --release --min-api "$MIN_SDK" --lib "$PLATFORM" --output "$OUT/dex" @"$OUT/d8-inputs.txt"
(cd "$OUT/dex" && zip -q -u "$OUT/unaligned.apk" classes*.dex)

echo "5/6 Aligning"
"$BT/zipalign" -p -f 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"

echo "6/6 Signing"
APK="$OUT/FreeAirTV-$VERSION.apk"
"$BT/apksigner" sign --ks "$KEYSTORE_FILE" --ks-pass env:KEYSTORE_PASSWORD \
  --ks-key-alias "$KEY_ALIAS" --out "$APK" "$OUT/aligned.apk"
"$BT/apksigner" verify "$APK"
(cd "$OUT" && sha256sum "FreeAirTV-$VERSION.apk" > "FreeAirTV-$VERSION.apk.sha256")
echo "Built $APK"
cat "$APK.sha256"
