#!/usr/bin/env python3
import argparse
import json
import os
import re
import sys
import urllib.request
from pathlib import Path

FILE_RE = re.compile(r"file:///.*?/src/([^:\\n]+\\.kt):(\\d+)")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", required=True)
    parser.add_argument("--build-log", required=True)
    parser.add_argument("--build-error", required=True)
    parser.add_argument("--health-log", required=True)
    parser.add_argument("--attempt", required=True)
    args = parser.parse_args()
    root = Path(args.repo_root).resolve()
    log = Path(args.build_error).read_text(encoding="utf-8", errors="ignore")
    if not log.strip():
        log = Path(args.build_log).read_text(encoding="utf-8", errors="ignore")

    match = FILE_RE.search(log)
    if not match:
        raise RuntimeError("Could not identify Kotlin source file")

    relative = match.group(1).replace("\\", "/")
    target = root / relative
    parts = Path(relative).parts
    if not target.exists():
        raise RuntimeError("Target source does not exist: " + relative)
    if len(parts) < 5 or parts[1:4] != ("src", "main", "kotlin"):
        raise RuntimeError("Unsafe target path: " + relative)
    if not (root / parts[0] / "build.gradle.kts").exists():
        raise RuntimeError("Target is not a provider module: " + relative)

    source = target.read_text(encoding="utf-8", errors="ignore")
    health_path = Path(args.health_log)
    health = health_path.read_text(encoding="utf-8", errors="ignore") if health_path.exists() else "{}"

    prompt = (
        "You are Kayra Doctor, a conservative Kotlin CloudStream provider repair agent.\n\n"
        "Modify exactly one Kotlin provider source file. Do not add dependencies or edit Gradle/workflow files.\n"
        "Return only JSON with path, content, reason. Content must be the complete file.\n\n"
        "Attempt: " + args.attempt + "\n"
        "Path: " + relative + "\n\n"
        "Build error:\n" + log[-30000:] + "\n\n"
        "Health report:\n" + health[-8000:] + "\n\n"
        "Source:\n" + source[-90000:]
    )
