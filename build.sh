#!/usr/bin/env bash
# Compila os módulos sem Gradle: aapt2 + javac + d8 + apksigner.
# Uso: ./build.sh [home|phone]     (padrão: home)
#   home  -> build/hyhome.apk    launcher do projetor
#   phone -> build/hyremote.apk  controle para o celular (fala ADB com o projetor)
set -euo pipefail
cd "$(dirname "$0")"

MODULE="${1:-home}"
case "$MODULE" in
    home)  DIR=.;     NAME=hyhome ;;
    phone) DIR=phone; NAME=hyremote ;;
    *) echo "módulo desconhecido: $MODULE (use home ou phone)" >&2; exit 2 ;;
esac

SDK="${ANDROID_SDK:-$HOME/AndroidSdk}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-33/android.jar"
KS="${HYHOME_KEYSTORE:-$HOME/.hyhome-debug.jks}"
W="build/$NAME"

rm -rf "$W" && mkdir -p "$W/gen" "$W/classes" "$W/dex"

"$BT/aapt2.exe" compile --dir "$DIR/res" -o "$W/res.zip"
"$BT/aapt2.exe" link -I "$JAR" --manifest "$DIR/AndroidManifest.xml" --java "$W/gen" \
    --min-sdk-version "$([ "$MODULE" = phone ] && echo 26 || echo 28)" --target-sdk-version 33 \
    -o "$W/base.apk" "$W/res.zip"

javac -encoding UTF-8 --release 8 -Xlint:-options -cp "$JAR" -d "$W/classes" \
    $(find "$DIR/src" "$W/gen" -name '*.java')

"$BT/d8.bat" --lib "$JAR" --min-api "$([ "$MODULE" = phone ] && echo 26 || echo 28)" --output "$W/dex" \
    $(find "$W/classes" -name '*.class')

cp "$W/base.apk" "$W/unsigned.apk"
(cd "$W/dex" && jar uf ../unsigned.apk classes.dex)
"$BT/zipalign.exe" -f 4 "$W/unsigned.apk" "$W/aligned.apk"

if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storepass hyhome123 -keypass hyhome123         -alias hyhome -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=HY Home"
fi
"$BT/apksigner.bat" sign --ks "$KS" --ks-pass pass:hyhome123 --key-pass pass:hyhome123     --out "build/$NAME.apk" "$W/aligned.apk"
"$BT/apksigner.bat" verify "build/$NAME.apk"
ls -l "build/$NAME.apk"
