#!/usr/bin/env python3
import argparse
import json
import os
import re
import sys
import urllib.request
from pathlib import Path

FILE_RE = re.compile(r"file://(/[^:\n]+?\.kt):(\d+)")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", required=True)
    parser.add_argument("--build-log", required=True)
    parser.add_argument("--build-error", required=True)
    parser.add_argument("--health-log", required=True)
    parser.add_argument("--attempt", required=True)
    args = parser.parse_args()
    root = Path(args.repo_root).resolve()
    build_error_path = Path(args.build_error)
    if build_error_path.exists():
        log = build_error_path.read_text(encoding="utf-8", errors="ignore")
    else:
        log = ""
    if not log.strip():
        build_log_path = Path(args.build_log)
        if build_log_path.exists():
            log = build_log_path.read_text(encoding="utf-8", errors="ignore")

    relative = None
    target = None

    # Kotlin compiler file URLs are absolute. The old expression stripped the
    # module's own "/src/" segment, leaving e.g. "main/kotlin/Provider.kt",
    # which is not a valid path from the repository root.
    for match in FILE_RE.finditer(log):
        try:
            candidate = Path(match.group(1)).resolve()
            candidate_relative = candidate.relative_to(root).as_posix()
        except (ValueError, OSError):
            continue

        parts = Path(candidate_relative).parts
        if len(parts) < 5 or parts[1:4] != ("src", "main", "kotlin"):
            continue
        if not candidate.is_file():
            continue
        if not (root / parts[0] / "build.gradle.kts").is_file():
            continue

        relative = candidate_relative
        target = candidate
        break

    if not relative:
        # Older/custom build logs may only expose a repository-relative path.
        for match in FILE_RE.finditer(log):
            raw_path = match.group(1).replace("\\\\", "/")
            for marker in ("/src/",):
                if marker in raw_path:
                    candidate_relative = raw_path.split(marker, 1)[1]
                    # This fallback is intentionally not accepted unless it
                    # resolves to a real provider Kotlin file below.
                    candidate = root / candidate_relative
                    if candidate.is_file():
                        relative = candidate_relative
                        target = candidate
                        break
            if relative:
                break

    if not relative:
        health_path = Path(args.health_log)
        if health_path.exists():
            try:
                health_data = json.loads(health_path.read_text(encoding="utf-8", errors="ignore"))
            except Exception:
                health_data = {}
            for item in health_data.get("results", []):
                source = str(item.get("source", "")).replace("\\\\", "/")
                candidate = root / source
                parts = Path(source).parts
                if (
                    item.get("status") == "fail"
                    and source
                    and candidate.is_file()
                    and len(parts) >= 5
                    and parts[1:4] == ("src", "main", "kotlin")
                    and (root / parts[0] / "build.gradle.kts").is_file()
                ):
                    relative = source
                    target = candidate
                    break

    if not relative or target is None:
        raise RuntimeError("Could not identify an existing provider Kotlin source file")

    parts = Path(relative).parts
    if len(parts) < 5 or parts[1:4] != ("src", "main", "kotlin"):
        raise RuntimeError("Unsafe target path: " + relative)
    if not (root / parts[0] / "build.gradle.kts").is_file():
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
