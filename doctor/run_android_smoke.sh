#!/usr/bin/env bash
set -euo pipefail

mkdir -p doctor-results/android

APK_DIR="${RUNNER_TEMP}/cloudstream"
mkdir -p "$APK_DIR"

echo "CloudStream prerelease APK aranıyor..."
TAG="$(gh release list --repo recloudstream/cloudstream --limit 20 --json tagName,isPrerelease,publishedAt --jq '[.[] | select(.isPrerelease == true)] | sort_by(.publishedAt) | reverse | .[0].tagName')"
test -n "$TAG"
echo "CloudStream prerelease: $TAG"

gh release download "$TAG" \
  --repo recloudstream/cloudstream \
  --pattern "*.apk" \
  --dir "$APK_DIR"

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

echo "CloudStream paketi keşfediliyor..."
CS_PACKAGE="$(adb shell pm list packages | grep -E '^package:com\.lagradost\.cloudstream3(\.prerelease)?' | head -n 1 | cut -d: -f2 | tr -d '[:space:]' || true)"
if [ -z "$CS_PACKAGE" ]; then
  echo "CloudStream paketi bulunamadı."
  adb shell pm list packages | grep -i cloudstream || true
  exit 1
fi
echo "CloudStream package: $CS_PACKAGE"

adb shell am force-stop "$CS_PACKAGE" || true

echo "Playback smoke başlatılıyor..."
python3 -u doctor/playback_smoke.py \
  --package "$CS_PACKAGE" \
  --plugins-dir doctor-results/cs3 \
  --config doctor/providers.json \
  --output doctor-results/android/playback-smoke.json \
  --timeout 20
