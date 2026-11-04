#!/bin/bash
# Direct-toolchain build (no Gradle daemon) for this VM.
# Steps: kotlinc -> d8 -> aapt2 compile/link -> add dex -> zipalign -> apksigner.
# Produces osnm-z-multijob-debug.apk next to this script's parent dir.
# Note (user request 2026-11-03): when sending an update, just rename the
# APK file (e.g. …-0.1.1.apk) so the download does not overwrite the old
# file on the phone — do not bump the in-app version for a rename alone.
# Requirements: /opt/jdk17, /opt/kotlinc, ~/Android/Sdk (platform 34, build-tools 34.0.0),
# and manual-build/libs/*.jar (downloaded once from Maven Central).
set -e
export PATH=/opt/jdk17/bin:$PATH
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="$HOME/Android/Sdk"
BT="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
MB="$ROOT/manual-build"
LIBS="$MB/libs"

echo "== kotlinc =="
rm -rf "$MB/classes" "$MB/dex" "$MB/compiled"
mkdir -p "$MB/classes" "$MB/dex" "$MB/compiled"
# shellcheck disable=SC2046
/opt/kotlinc/bin/kotlinc -jvm-target 1.8 -no-stdlib \
  -cp "$ANDROID_JAR:$LIBS/kotlin-stdlib.jar:$LIBS/bcprov.jar:$LIBS/coroutines-core.jar:$LIBS/coroutines-android.jar:$LIBS/annotations.jar" \
  -d "$MB/classes" $(find "$ROOT/app/src/main/java" -name "*.kt") 2>&1 | grep -v "warning:" || true

echo "== d8 =="
/opt/jdk17/bin/jar cf "$MB/app-classes.jar" -C "$MB/classes" .
"$BT/d8" --min-api 26 --output "$MB/dex" \
  "$MB/app-classes.jar" "$LIBS/kotlin-stdlib.jar" "$LIBS/bcprov.jar" \
  "$LIBS/coroutines-core.jar" "$LIBS/coroutines-android.jar" "$LIBS/annotations.jar" 2>&1 | grep -v "^Info" || true

echo "== aapt2 =="
# aapt2 standalone needs a package attribute; Gradle injects it from
# namespace, so inject it only into the manual-build copy of the manifest.
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android">|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.osnmz.android">|' \
  "$ROOT/app/src/main/AndroidManifest.xml" > "$MB/AndroidManifest.xml"
"$BT/aapt2" compile "$ROOT/app/src/main/res/xml/backup_rules.xml" "$ROOT/app/src/main/res/xml/data_extraction_rules.xml" -o "$MB/compiled/"
"$BT/aapt2" link -o "$MB/app-unaligned.apk" --manifest "$MB/AndroidManifest.xml" \
  --version-code 1 --version-name 0.1.0 --min-sdk-version 26 --target-sdk-version 34 \
  -I "$ANDROID_JAR" "$MB/compiled/"*.flat --auto-add-overlay

echo "== add dex, align, sign =="
cp "$MB/app-unaligned.apk" "$MB/app-with-dex.apk"
(cd "$MB/dex" && zip -q "$MB/app-with-dex.apk" classes.dex)
"$BT/zipalign" -f 4 "$MB/app-with-dex.apk" "$MB/app-aligned.apk"
# Debug keystore for local testing only (generated here, never committed,
# never used for a release build).
if [ ! -f "$MB/debug.keystore" ]; then
  /opt/jdk17/bin/keytool -genkeypair -keystore "$MB/debug.keystore" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" -noprompt
fi
"$BT/apksigner" sign --ks "$MB/debug.keystore" --ks-pass pass:android --key-pass pass:android \
  --out "$ROOT/osnm-z-multijob-debug.apk" "$MB/app-aligned.apk"
"$BT/apksigner" verify "$ROOT/osnm-z-multijob-debug.apk"
echo "APK: $ROOT/osnm-z-multijob-debug.apk"
ls -lh "$ROOT/osnm-z-multijob-debug.apk"
