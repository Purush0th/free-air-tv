#!/usr/bin/env bash
# Builds FreeAir TV on Linux (GitHub Actions) without Gradle, using only Android SDK build tools.
# The same steps as build.ps1.
#
#   VERSION=1.2.0 KEYSTORE_FILE=release.keystore KEYSTORE_PASSWORD=... ./build.sh
#
# Output: build/FreeAirTV-$VERSION.apk and build/FreeAirTV-$VERSION.apk.sha256
set -euo pipefail

VERSION="${VERSION:-0.0.0-dev}"
SDK="${ANDROID_HOME:?ANDROID_HOME is not set}"
BT="$SDK/build-tools/${BUILD_TOOLS:-35.0.0}"
PLATFORM="$SDK/platforms/android-35/android.jar"
ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/app/src/main"
OUT="$ROOT/build"
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
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/gen-res/raw" "$OUT/gen-res/values"
echo "FreeAir TV $VERSION (code $VERSION_CODE), updates from github.com/$REPO"

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
"$BT/aapt2" link -I "$PLATFORM" --manifest "$SRC/AndroidManifest.xml" \
  --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" --version-name "$VERSION" \
  --java "$OUT/gen" -o "$OUT/unaligned.apk" "$OUT/res.zip" "$OUT/gen-res.zip"

echo "3/6 Compiling Java"
find "$SRC/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 \
  -bootclasspath "$PLATFORM" -classpath "$BT/core-lambda-stubs.jar" \
  -d "$OUT/classes" @"$OUT/sources.txt"

echo "4/6 Converting to DEX"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --release --min-api "$MIN_SDK" --lib "$PLATFORM" --output "$OUT/dex" @"$OUT/classes.txt"
(cd "$OUT/dex" && zip -q -u "$OUT/unaligned.apk" classes.dex)

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
