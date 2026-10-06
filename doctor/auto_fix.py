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
    relative = match.group(1).replace("\\", "/") if match else None

    if not relative:
        health_path = Path(args.health_log)
        if health_path.exists():
            try:
                health_data = json.loads(health_path.read_text(encoding="utf-8", errors="ignore"))
            except Exception:
                health_data = {}
            for item in health_data.get("results", []):
                if item.get("status") == "fail" and item.get("source"):
                    relative = str(item["source"]).replace("\\", "/")
                    break

    if not relative:
        raise RuntimeError("Could not identify Kotlin source file")

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
    key = os.environ.get("OPENAI_API_KEY")
    if not key:
        raise RuntimeError("OPENAI_API_KEY is missing")
    model = os.environ.get("OPENAI_MODEL") or "gpt-6-luna"
    base = os.environ.get("OPENAI_BASE_URL") or "https://api.openai.com/v1"

    body = json.dumps({"model": model, "input": prompt}).encode()
    request = urllib.request.Request(
        base.rstrip("/") + "/responses",
        data=body,
        headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"},
        method="POST",
    )

    with urllib.request.urlopen(request, timeout=180) as response:
        data = json.loads(response.read().decode())

    text = data.get("output_text")
    if not text:
        for item in data.get("output", []):
            for part in item.get("content", []):
                if isinstance(part, dict) and isinstance(part.get("text"), str):
                    text = part["text"]
                    break
            if text:
                break

    if not text:
        raise RuntimeError("Model returned no text")
    text = text.strip()
    if text.startswith("```"):
        text = text.split("\n", 1)[1]
        if text.rstrip().endswith("```"):
            text = text.rstrip()[:-3]

    result = json.loads(text.strip())
    if result.get("path") != relative:
        raise RuntimeError("Model returned a different path")

    replacement = result.get("content")
    if not isinstance(replacement, str) or not replacement.strip():
        raise RuntimeError("Model returned empty source")

    target.write_text(replacement.rstrip() + "\n", encoding="utf-8")
    print("AI reason:", result.get("reason", "not supplied"))
    print("Updated:", relative)

if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print("AUTO_FIX_ERROR:", exc, file=sys.stderr)
        sys.exit(1)
