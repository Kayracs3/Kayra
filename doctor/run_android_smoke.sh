#!/usr/bin/env bash
set -euo pipefail

mkdir -p doctor-results/android

APK_DIR="${RUNNER_TEMP}/cloudstream"
mkdir -p "$APK_DIR"

echo "CloudStream prerelease APK aranıyor..."
TAG="$(gh release list --repo recloudstream/cloudstream --limit 20 --json tagName,isPrerelease,publishedAt --jq '[.[] | select(.isPrerelease == true)] | sort_by(.publishedAt) | reverse | .[0].tagName')"
test -n "$TAG"
echo "CloudStream prerelease: $TAG"

gh release download "$TAG"   --repo recloudstream/cloudstream   --pattern "*.apk"   --dir "$APK_DIR"

echo "CloudStream APK varlıkları:"
find "$APK_DIR" -maxdepth 1 -type f -name "*.apk" -print | sort

APK="$(find "$APK_DIR" -maxdepth 1 -type f -iname "*x86_64*.apk" | head -n 1 || true)"
if [ -z "$APK" ]; then
  APK="$(find "$APK_DIR" -maxdepth 1 -type f -iname "*universal*.apk" | head -n 1 || true)"
fi
if [ -z "$APK" ]; then
  APK="$(find "$APK_DIR" -maxdepth 1 -type f -name "*.apk" | head -n 1 || true)"
fi
test -n "$APK"
echo "Seçilen APK: $APK"

adb start-server >/dev/null
adb devices -l

echo "CloudStream APK kuruluyor..."
adb install -r "$APK"

echo "Plugin klasörü hazırlanıyor..."
adb shell mkdir -p /sdcard/Cloudstream3/plugins
adb push doctor-results/cs3/. /sdcard/Cloudstream3/plugins/

echo "CloudStream başlatılıyor..."
adb shell am start -n com.lagradost.cloudstream3.prerelease/.ui.account.AccountSelectActivity ||   adb shell am start -n com.lagradost.cloudstream3/.ui.account.AccountSelectActivity

sleep 10

adb logcat -d -v time |   grep -E "PluginManager|Loading plugin|Failed to load plugin|ClassNotFoundException|VerifyError|NoClassDefFoundError|Kayracs3"   > doctor-results/android/plugin-load.log || true

cat doctor-results/android/plugin-load.log

if grep -Eq "Failed to load plugin|No manifest found|ClassNotFoundException|VerifyError|NoClassDefFoundError"   doctor-results/android/plugin-load.log; then
  echo "Plugin yükleme hatası bulundu."
  exit 1
fi

adb logcat -d -v threadtime > doctor-results/android/full-logcat.txt

CS_PACKAGE="$(adb shell pm list packages | tr -d '\\r' | sed 's/^package://' | grep -E '^com\\.lagradost\\.cloudstream3(\\.prerelease)?$' | head -n 1 || true)"
if [ -z "$CS_PACKAGE" ]; then
  echo "CloudStream package bulunamadı."
  adb shell pm list packages | grep -i cloudstream || true
  exit 1
fi

echo "CloudStream package: $CS_PACKAGE"

python3 doctor/playback_smoke.py   --package "$CS_PACKAGE"   --plugins-dir doctor-results/cs3   --config doctor/providers.json   --output doctor-results/android/playback-smoke.json   --timeout 45
