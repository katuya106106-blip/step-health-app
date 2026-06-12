#!/bin/sh
set -eu

if [ ! -f "local.properties" ]; then
  echo "local.properties がありません。local.properties.example をコピーして sdk.dir を設定してください。" >&2
  exit 1
fi

chmod +x ./gradlew
./gradlew --version
./gradlew clean assembleDebug

echo "Android Debug APK build completed."
