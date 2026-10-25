#!/bin/zsh
# Builds Anime Home without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Targets SDK 28 on purpose: the Wi-Fi dropdown joins networks with WifiManager calls that newer targets lose.
set -euo pipefail
cd "${0:A:h}"

export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}
export PATH=$JAVA_HOME/bin:$PATH
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
BT=$SDK/build-tools/34.0.0
JAR=$SDK/platforms/android-34/android.jar
OUT=build

rm -rf $OUT && mkdir -p $OUT/classes $OUT/dex

if [[ ! -f res/drawable-xhdpi/banner.png ]]; then
  swift tools/art.swift res/drawable-xhdpi
fi

$BT/aapt2 compile --dir res -o $OUT/res.zip
$BT/aapt2 link -o $OUT/unsigned.apk -I $JAR --manifest AndroidManifest.xml -A assets \
  --min-sdk-version 28 --target-sdk-version 28 --version-code ${VERSION_CODE:-1} --version-name 1.0 \
  -0 jpg $OUT/res.zip

javac -source 8 -target 8 -bootclasspath $JAR:$BT/core-lambda-stubs.jar -Xlint:-options -encoding UTF-8 \
  -d $OUT/classes $(find src -name '*.java')
$BT/d8 --release --min-api 28 --lib $JAR --output $OUT/dex $(find $OUT/classes -name '*.class')
(cd $OUT/dex && zip -q -j ../unsigned.apk classes.dex)

$BT/zipalign -f -p 4 $OUT/unsigned.apk $OUT/aligned.apk

KS=${ANIME_HOME_KEYSTORE:-$HOME/.android/animehome.keystore}
KS_PASS=${ANIME_HOME_KS_PASS:-animehome}
if [[ ! -f $KS ]]; then
  mkdir -p $HOME/.android
  keytool -genkeypair -keystore $KS -storepass $KS_PASS -keypass $KS_PASS -alias animehome \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Anime Home" >/dev/null 2>&1
fi
$BT/apksigner sign --ks $KS --ks-pass pass:$KS_PASS --ks-key-alias animehome \
  --out $OUT/anime-home.apk $OUT/aligned.apk

ls -lh $OUT/anime-home.apk
