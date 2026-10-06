#!/usr/bin/env python3

import argparse
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path


PLAYER_CLASS_RE = re.compile(
    r"(PlayerView|StyledPlayerView|SurfaceView|TextureView|PlayerControlView|ExoPlayer)",
    re.I,
)
SOURCE_RE = re.compile(r"Loaded ExtractorLink:", re.I)
FATAL_RE = re.compile(
    r"(ExoPlaybackException|PlaybackException|M3u8 must contains TS files|"
    r"HttpDataSourceException|FileDataSourceException|BehindLiveWindowException|"
    r"No links found|no_links_found_toast|Playback error)",
    re.I,
)


def run(cmd, check=True, timeout=30, text=True):
    return subprocess.run(
        cmd,
        check=check,
        timeout=timeout,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=text,
    )


def adb(*args, check=True, timeout=30):
    return run(["adb", *args], check=check, timeout=timeout).stdout


def logcat():
    return adb("logcat", "-d", "-v", "threadtime", check=False, timeout=20)


def dump_ui():
    adb(
        "shell",
        "uiautomator",
        "dump",
        "/sdcard/window.xml",
        check=False,
        timeout=15,
    )
    raw = adb(
        "exec-out",
        "cat",
        "/sdcard/window.xml",
        check=False,
        timeout=15,
    )
    return raw


def player_surface_visible(xml_text):
    if not xml_text or "hierarchy" not in xml_text:
        return False

    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        return False

    for node in root.iter("node"):
        attrs = node.attrib
        blob = " ".join(
            [
                attrs.get("class", ""),
                attrs.get("resource-id", ""),
                attrs.get("content-desc", ""),
                attrs.get("text", ""),
            ]
        )
        if PLAYER_CLASS_RE.search(blob):
            return True

    return False


def stop_app(package):
    adb("shell", "am", "force-stop", package, check=False, timeout=15)


def open_url(package, url):
    result = run(
        [
            "adb",
            "shell",
            "am",
            "start",
            "-W",
            "-a",
            "android.intent.action.VIEW",
            "-d",
            url,
            "-p",
            package,
        ],
        check=False,
        timeout=30,
    )
    return result.stdout


def test_case(package, item, timeout_seconds):
    provider = str(item.get("name", "unknown"))
    url = str(item.get("playbackUrl", "")).strip()
    expected_title = str(item.get("expectedTitle", "")).strip()

    result = {
        "provider": provider,
        "url": url,
        "expectedTitle": expected_title or None,
        "status": "fail",
        "sourceFound": False,
        "playerSurface": False,
        "fatalMediaError": None,
        "detail": "",
    }

    if not url:
        result["status"] = "not-configured"
        result["detail"] = "playbackUrl is missing"
        return result

    adb("logcat", "-c", check=False, timeout=15)
    stop_app(package)
    time.sleep(1)

    start_output = open_url(package, url)
    result["amStart"] = start_output[-2000:]

    deadline = time.time() + timeout_seconds
    stable_since = None
    last_log = ""
    last_ui = ""

    while time.time() < deadline:
        last_log = logcat()
        last_ui = dump_ui()

        if SOURCE_RE.search(last_log):
            result["sourceFound"] = True

        fatal = FATAL_RE.search(last_log)
        if fatal:
            result["fatalMediaError"] = fatal.group(0)

        result["playerSurface"] = player_surface_visible(last_ui)

        title_ok = True
        if expected_title:
            title_ok = expected_title.lower() in last_ui.lower()

        if result["sourceFound"] and result["playerSurface"] and title_ok:
            if stable_since is None:
                stable_since = time.time()
            elif time.time() - stable_since >= 5:
                result["status"] = "ok"
                result["detail"] = (
                    "CloudStream resolved a media source and the player surface "
                    "remained present for at least 5 seconds."
                )
                break
        else:
            stable_since = None

        if result["fatalMediaError"] and result["sourceFound"]:
            result["detail"] = (
                "A media source was found, but the player reported a fatal "
                "playback error."
            )
            break

        time.sleep(2)

    if result["status"] != "ok" and not result["detail"]:
        result["detail"] = (
            f"sourceFound={result['sourceFound']}, "
            f"playerSurface={result['playerSurface']}, "
            f"fatalMediaError={result['fatalMediaError']!r}"
        )

    result["logTail"] = last_log[-8000:]
    result["uiTail"] = last_ui[-4000:]
    stop_app(package)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--package", required=True)
    parser.add_argument("--config", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--timeout", type=int, default=45)
    args = parser.parse_args()

    config = json.loads(Path(args.config).read_text(encoding="utf-8"))
    cases = []

    for name, data in config.get("providers", {}).items():
        if not isinstance(data, dict):
            continue
        item = dict(data)
        item["name"] = name
        if item.get("playbackUrl"):
            cases.append(item)

    results = [test_case(args.package, item, args.timeout) for item in cases]
    failures = [x for x in results if x["status"] == "fail"]
    unconfigured = [
        {"provider": name, "status": "not-configured"}
        for name, data in config.get("providers", {}).items()
        if isinstance(data, dict) and not data.get("playbackUrl")
    ]

    payload = {
        "package": args.package,
        "configured": len(cases),
        "passed": len([x for x in results if x["status"] == "ok"]),
        "failed": len(failures),
        "notConfigured": len(unconfigured),
        "results": results,
        "skippedProviders": unconfigured,
    }

    Path(args.output).write_text(
        json.dumps(payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(json.dumps(payload, ensure_ascii=False, indent=2))

    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
