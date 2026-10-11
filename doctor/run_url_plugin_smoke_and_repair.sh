#!/usr/bin/env bash
set -euo pipefail

cp doctor-results/providers.json doctor/providers.json
mkdir -p doctor-results/android
rm -f doctor-results/android/playback-smoke.json

check_playback() {
  set +e
  bash doctor/run_android_smoke.sh
  local script_rc=$?
  python3 - <<'PY'
import json, pathlib, sys
p = pathlib.Path("doctor-results/android/playback-smoke.json")
try:
    d = json.loads(p.read_text(encoding="utf-8")) if p.exists() else {}
    ok = (
        d.get("passed", 0) > 0
        and d.get("failed", 0) == 0
        and any(x.get("status") == "ok" for x in d.get("results", []))
    )
except Exception as exc:
    print(f"Playback report could not be validated: {exc}", file=sys.stderr)
    ok = False
sys.exit(0 if ok else 1)
PY
  local report_rc=$?
  set -e
  if [[ "$script_rc" -ne 0 ]]; then
    echo "CloudStream smoke script exited with code $script_rc." >&2
    return "$script_rc"
  fi
  return "$report_rc"
}

if check_playback; then
  echo "Android playback smoke passed."
  exit 0
fi

if [[ ! -s doctor-results/android/playback-smoke.json ]]; then
  echo "No playback report was produced. This is an emulator/app/test setup failure; provider source will not be changed." >&2
  exit 1
fi

# A search that cannot be verified is not a confirmed provider failure. Do not
# mutate generated Kotlin based only on missing search results or onboarding UI.
if python3 - <<'PY'
import json, pathlib, sys
p = pathlib.Path("doctor-results/android/playback-smoke.json")
try:
    d = json.loads(p.read_text(encoding="utf-8"))
except Exception:
    sys.exit(1)
if d.get("failed", 0) == 0 and d.get("unverified", 0) > 0 and d.get("passed", 0) == 0:
    print("CloudStream arama sonucu doğrulanamadı. Eklenti kodu otomatik değiştirilmedi; playback testi kesin sonuç vermedi.")
    for item in d.get("results", []):
        if item.get("status") == "unverified":
            print(f"- {item.get('provider', 'unknown')}: {item.get('detail', '')}")
    sys.exit(0)
sys.exit(1)
PY
then
  exit 1
fi

# ADB/ActivityManager timeouts are test-environment failures, not evidence of a
# broken provider. Never let a flaky emulator launch trigger arbitrary source edits.
if python3 - <<'PY'
import json, pathlib, sys
p = pathlib.Path("doctor-results/android/playback-smoke.json")
try:
    d = json.loads(p.read_text(encoding="utf-8"))
except Exception:
    sys.exit(1)
infra = [
    x for x in d.get("results", [])
    if x.get("category") == "infrastructure"
    or "ADB/command timeout (test infrastructure" in str(x.get("detail", ""))
]
if infra:
    print("Playback smoke failed in the Android/ADB test layer; provider repair skipped.")
    for item in infra:
        print(f"- {item.get('provider', 'unknown')}: {item.get('detail', '')}")
    sys.exit(0)
sys.exit(1)
PY
then
  exit 1
fi

echo "First playback check failed; attempting provider repair from the actual playback report."
python3 doctor/site_to_plugin.py --mode repair --repo-root . \
  --report doctor-results/site-analysis.json \
  --metadata doctor-results/generated-provider.json \
  --playback-report doctor-results/android/playback-smoke.json

if ! ./gradlew clean make makePluginsJson --no-daemon --stacktrace > doctor-results/build-after-playback-repair.log 2>&1; then
  echo "Build failed after playback repair. Last build diagnostics:" >&2
  tail -n 120 doctor-results/build-after-playback-repair.log >&2 || true
  exit 1
fi

MODULE_NAME="$(python3 -c 'import json; print(json.load(open("doctor-results/generated-provider.json"))["module_name"])')"
CS3="$(find "$MODULE_NAME/build" -type f -name '*.cs3' -print -quit)"
if [[ -z "$CS3" ]]; then
  echo "No .cs3 artifact was generated after playback repair." >&2
  exit 1
fi
cp "$CS3" "doctor-results/cs3/$MODULE_NAME.cs3"
python3 doctor/cs3_health.py --directory doctor-results/cs3 --output doctor-results/cs3-health-after-repair.json

cp doctor-results/providers.json doctor/providers.json
rm -f doctor-results/android/playback-smoke.json
if check_playback; then
  echo "Playback passed after provider repair."
else
  echo "Playback still fails after provider repair." >&2
  exit 1
fi
