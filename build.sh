#!/bin/bash
set -e
cd "$(dirname "$0")"
export JAVA_HOME="$PWD/tools/jdk"
export PATH="$JAVA_HOME/bin:$PATH"
BT="$PWD/tools/sdk/build-tools/34.0.0"
JAR="$PWD/tools/sdk/platforms/android-34/android.jar"
SRC="app/src/main"
OUT="build"
rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/obj" "$OUT/dex"

echo "== compile resources =="
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/app-unsigned.apk" -I "$JAR" \
  --manifest "$SRC/AndroidManifest.xml" "$OUT/res.zip" \
  --java "$OUT/gen" --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 24 --version-name 24.0

echo "== compile java =="
find "$SRC/java" "$OUT/gen" -name "*.java" > "$OUT/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -cp "$JAR" -d "$OUT/obj" @"$OUT/sources.txt"

echo "== dex =="
"$BT/d8" --lib "$JAR" --min-api 26 --output "$OUT/dex" $(find "$OUT/obj" -name "*.class")

echo "== package =="
cp "$OUT/app-unsigned.apk" "$OUT/app-dex.apk"
cd "$OUT/dex" && zip -q "../app-dex.apk" classes.dex && cd ../..
"$BT/zipalign" -f -p 4 "$OUT/app-dex.apk" "$OUT/app-aligned.apk"

echo "== sign =="
if [ ! -f keystore.jks ]; then
  keytool -genkeypair -keystore keystore.jks -storepass motonetwork -keypass motonetwork \
    -alias moto -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Moto Network Switch, O=Personal Tool, C=IN" 2>/dev/null
fi
"$BT/apksigner" sign --ks keystore.jks --ks-pass pass:motonetwork --key-pass pass:motonetwork \
  --out "Moto-5G-4G-Switch.apk" "$OUT/app-aligned.apk"

echo "== verify =="
"$BT/apksigner" verify --print-certs "Moto-5G-4G-Switch.apk" | head -4
"$BT/aapt2" dump badging "Moto-5G-4G-Switch.apk" | head -6
ls -lh "Moto-5G-4G-Switch.apk"
