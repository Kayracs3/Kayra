#!/usr/bin/env python3

import argparse
import json
import re
import subprocess
import sys
import time
import urllib.parse
import xml.etree.ElementTree as ET
from pathlib import Path


SOURCE_RE = re.compile(r"Loaded ExtractorLink:", re.I)
FATAL_RE = re.compile(
    r"(ExoPlaybackException|PlaybackException|M3u8 must contains TS files|"
    r"HttpDataSourceException|FileDataSourceException|BehindLiveWindowException|"
    r"Playback error)",
    re.I,
)


def run(cmd, check=True, timeout=30):
    return subprocess.run(
        cmd,
        check=check,
        timeout=timeout,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    ).stdout


def adb(*args, check=True, timeout=30):
    return run(["adb", *args], check=check, timeout=timeout)


def logcat():
    return adb("logcat", "-d", "-v", "threadtime", check=False, timeout=20)


def dump_ui():
    adb(
        "shell", "uiautomator", "dump", "/sdcard/window.xml",
        check=False, timeout=15
    )
    return adb(
        "exec-out", "cat", "/sdcard/window.xml",
        check=False, timeout=15
    )


def parse_ui(xml_text):
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        return []
    return list(root.iter("node"))


def node_center(node):
    bounds = node.attrib.get("bounds", "")
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap_node(node):
    point = node_center(node)
    if not point:
        return False
    x, y = point
    adb("shell", "input", "tap", str(x), str(y), check=False, timeout=10)
    return True


def find_text_node(xml_text, text):
    wanted = re.sub(r"\\s+", " ", text.casefold().strip())
    exact = []
    partial = []
    for node in parse_ui(xml_text):
        value = re.sub(r"\\s+", " ", node.attrib.get("text", "").casefold().strip())
        desc = re.sub(r"\\s+", " ", node.attrib.get("content-desc", "").casefold().strip())
        if not wanted:
            continue
        if value == wanted or desc == wanted:
            exact.append(node)
        elif wanted in value or wanted in desc:
            partial.append(node)
    return exact[0] if exact else (partial[0] if partial else None)


def find_resource_node(xml_text, suffix):
    for node in parse_ui(xml_text):
        rid = node.attrib.get("resource-id", "")
        if rid.endswith("/" + suffix) or rid == suffix:
            return node
    return None


def stop_app(package):
    adb("shell", "am", "force-stop", package, check=False, timeout=15)


def open_search(package, query):
    uri = "cloudstreamsearch://" + urllib.parse.quote(query, safe="")
    return run(
        [
            "adb", "shell", "am", "start", "-W",
            "-a", "android.intent.action.VIEW",
            "-d", uri, "-p", package,
        ],
        check=False,
        timeout=30,
    )


def push_only_plugin(package, plugin_path):
    remote_dir = "/sdcard/Cloudstream3/plugins"
    adb("shell", "mkdir", "-p", remote_dir, check=False, timeout=15)
    adb("shell", "rm", "-f", remote_dir + "/*.cs3", check=False, timeout=15)
    adb("shell", "rm", "-f", remote_dir + "/*.zip", check=False, timeout=15)
    adb("push", str(plugin_path), remote_dir + "/", check=True, timeout=60)
    stop_app(package)


def launch_account_activity(package):
    candidates = [
        package + "/.ui.account.AccountSelectActivity",
        package + "/com.lagradost.cloudstream3.ui.account.AccountSelectActivity",
    ]
    for activity in candidates:
        out = run(
            ["adb", "shell", "am", "start", "-n", activity],
            check=False,
            timeout=20,
        )
        if "Error type 3" not in out and "unable to resolve" not in out.lower():
            return out
    return ""


def player_surface_visible(xml_text):
    for node in parse_ui(xml_text):
        blob = " ".join(
            [
                node.attrib.get("class", ""),
                node.attrib.get("resource-id", ""),
                node.attrib.get("content-desc", ""),
            ]
        )
        if re.search(
            r"(SurfaceView|TextureView|StyledPlayerView|PlayerView)",
            blob,
            re.I,
        ):
            return True
    return False


def wait_for_node(text=None, resource=None, timeout=30):
    deadline = time.time() + timeout
    last = ""
    while time.time() < deadline:
        last = dump_ui()
        node = (
            find_resource_node(last, resource)
            if resource
            else find_text_node(last, text)
        )
        if node:
            return node, last
        time.sleep(2)
    return None, last


def test_case(package, plugins_dir, item, timeout_seconds):
    provider = str(item.get("name", "unknown"))
    query = str(item.get("searchQuery", "")).strip()
    expected_title = str(item.get("expectedTitle", "")).strip()
    episode_text = str(item.get("episodeText", "")).strip()
    plugin_file = plugins_dir / (provider + ".cs3")

    result = {
        "provider": provider,
        "status": "fail",
        "searchQuery": query,
        "expectedTitle": expected_title or None,
        "episodeText": episode_text or None,
        "plugin": str(plugin_file),
        "sourceFound": False,
        "playerSurface": False,
        "fatalMediaError": None,
        "detail": "",
    }

    if not plugin_file.exists():
        result["status"] = "not-configured"
        result["detail"] = "Matching .cs3 file was not found"
        return result

    if not query:
        result["status"] = "not-configured"
        result["detail"] = "searchQuery is missing"
        return result

    push_only_plugin(package, plugin_file)
    launch_account_activity(package)
    time.sleep(3)

    adb("logcat", "-c", check=False, timeout=15)
    start_output = open_search(package, query)
    result["amStart"] = start_output[-2000:]

    title = expected_title or query
    title_node, last_ui = wait_for_node(text=title, timeout=timeout_seconds)
    if not title_node:
        result["detail"] = "CloudStream search result was not found"
        result["uiTail"] = last_ui[-5000:]
        result["logTail"] = logcat()[-8000:]
        stop_app(package)
        return result

    if not tap_node(title_node):
        result["detail"] = "Could not tap the search result"
        stop_app(package)
        return result

    time.sleep(3)

    if episode_text:
        episode_node, last_ui = wait_for_node(text=episode_text, timeout=timeout_seconds)
        if not episode_node:
            result["detail"] = "Expected episode was not found on the result page"
            result["uiTail"] = last_ui[-5000:]
            result["logTail"] = logcat()[-8000:]
            stop_app(package)
            return result
        tap_node(episode_node)
    else:
        play_node, last_ui = wait_for_node(
            resource="result_play_movie",
            timeout=timeout_seconds,
        )
        if not play_node:
            result["detail"] = "Movie play button was not found"
            result["uiTail"] = last_ui[-5000:]
            result["logTail"] = logcat()[-8000:]
            stop_app(package)
            return result
        tap_node(play_node)

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

        if result["sourceFound"] and result["playerSurface"]:
            if stable_since is None:
                stable_since = time.time()
            elif time.time() - stable_since >= 5:
                result["status"] = "ok"
                result["detail"] = (
                    "Provider search opened a result, CloudStream received an "
                    "ExtractorLink, and the player surface remained visible for "
                    "at least 5 seconds."
                )
                break
        else:
            stable_since = None

        if result["fatalMediaError"] and result["sourceFound"]:
            result["detail"] = (
                "CloudStream found a media source but reported a fatal "
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

    result["logTail"] = last_log[-10000:]
    result["uiTail"] = last_ui[-5000:]
    stop_app(package)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--package", required=True)
    parser.add_argument("--plugins-dir", required=True)
    parser.add_argument("--config", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--timeout", type=int, default=45)
    args = parser.parse_args()

    config = json.loads(Path(args.config).read_text(encoding="utf-8"))
    plugins_dir = Path(args.plugins_dir)
    cases = []

    for name, data in config.get("providers", {}).items():
        if isinstance(data, dict) and data.get("searchQuery"):
            item = dict(data)
            item["name"] = name
            cases.append(item)

    results = [test_case(args.package, plugins_dir, item, args.timeout) for item in cases]
    failures = [x for x in results if x["status"] == "fail"]
    not_configured = [x for x in results if x["status"] == "not-configured"]

    payload = {
        "package": args.package,
        "configured": len(cases),
        "passed": len([x for x in results if x["status"] == "ok"]),
        "failed": len(failures),
        "notConfigured": len(not_configured),
        "results": results,
    }

    Path(args.output).write_text(
        json.dumps(payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
