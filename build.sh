#!/usr/bin/env bash
# Compila o HY Home sem Gradle: aapt2 + javac + d8 + apksigner.
# Uso: ./build.sh   ->  build/hyhome.apk
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK:-$HOME/AndroidSdk}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-33/android.jar"
KS="${HYHOME_KEYSTORE:-$HOME/.hyhome-debug.jks}"

rm -rf build && mkdir -p build/gen build/classes build/dex

"$BT/aapt2.exe" compile --dir res -o build/res.zip
"$BT/aapt2.exe" link -I "$JAR" --manifest AndroidManifest.xml --java build/gen \
    --min-sdk-version 28 --target-sdk-version 33 -o build/base.apk build/res.zip

javac -encoding UTF-8 --release 8 -Xlint:-options -cp "$JAR" -d build/classes \
    $(find src build/gen -name '*.java')

"$BT/d8.bat" --lib "$JAR" --min-api 28 --output build/dex $(find build/classes -name '*.class')

cp build/base.apk build/unsigned.apk
(cd build/dex && jar uf ../unsigned.apk classes.dex)
"$BT/zipalign.exe" -f 4 build/unsigned.apk build/aligned.apk

if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storepass hyhome123 -keypass hyhome123 \
        -alias hyhome -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=HY Home"
fi
"$BT/apksigner.bat" sign --ks "$KS" --ks-pass pass:hyhome123 --key-pass pass:hyhome123 \
    --out build/hyhome.apk build/aligned.apk
"$BT/apksigner.bat" verify build/hyhome.apk
ls -l build/hyhome.apk
