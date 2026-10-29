#!/usr/bin/env bash
# Build both apps on this computer, signed with your own key, into optimize/apk/ where optimize/install.sh takes them from.
#   android/build-apks.sh
# Needs: JDK 17, the Android SDK (ANDROID_HOME, platform 34), Gradle 8.9 on the PATH, and your key:
#   ANDROID_KEYSTORE=/path/to/urtv-release.p12 ANDROID_KEYSTORE_PASSWORD=… android/build-apks.sh
# Make the key once (alias urtv, as android/*/build.gradle signs with), and keep the file and its password safe: Android only
# installs an update signed with the same key, so a lost key means removing the apps (and their settings) to install again.
#   keytool -genkeypair -storetype PKCS12 -keystore ~/urtv-release.p12 -alias urtv -keyalg RSA -keysize 4096 -validity 36500 -dname CN=UrTV
# The versionCode is the number of commits (URTV_BUILD=N to choose it), so every new commit installs over the last build.
# The Thmanyah fonts are not in this repository (their licence): put Thmanyah-*.woff2 in assets/fonts/ and Thmanyah-*.otf in
# android/launcher/src/main/assets/fonts/ if you have them; without them the screens use the TV's own Arabic font.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -z "${ANDROID_KEYSTORE:-}" ] || [ -z "${ANDROID_KEYSTORE_PASSWORD:-}" ] || [ ! -f "${ANDROID_KEYSTORE:-}" ]; then
  echo "!! Set ANDROID_KEYSTORE (your .p12 file, alias urtv) and ANDROID_KEYSTORE_PASSWORD: without them Gradle signs with this"
  echo "   computer's debug key, and a build from another computer could never update the apps. See the top of this file."
  exit 1
fi
command -v gradle > /dev/null || { echo "!! No gradle on the PATH (Gradle 8.9)"; exit 1; }
[ -n "${ANDROID_HOME:-}" ] || { echo "!! Set ANDROID_HOME to the Android SDK (platform 34 installed)"; exit 1; }
ls assets/fonts/Thmanyah-*.woff2 > /dev/null 2>&1 || echo "Note: no Thmanyah fonts in assets/fonts/: the screens use the TV's own Arabic font."

export GITHUB_RUN_NUMBER="${URTV_BUILD:-$(git rev-list --count HEAD)}"   # android/*/build.gradle: versionCode N, versionName 3.N
python3 src/build.py
gradle -p android assembleRelease --no-daemon
mkdir -p optimize/apk
cp android/app/build/outputs/apk/release/app-release.apk optimize/apk/urtv-screensaver.apk
cp android/launcher/build/outputs/apk/release/launcher-release.apk optimize/apk/urtv-home.apk
echo "Built 3.$GITHUB_RUN_NUMBER: optimize/apk/urtv-screensaver.apk and optimize/apk/urtv-home.apk. Install: optimize/install.sh all"
