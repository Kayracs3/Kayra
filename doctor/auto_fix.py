#!/usr/bin/env python3
"""Apply only local, deterministic fixes to known CloudStream API patterns."""
import argparse
import json
import re
import sys
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

    # Search both the shortened diagnostic tail and the full Gradle log.
    # Compiler file references can be earlier than the last 1,400 lines when a
    # module produces a large cascade of errors (for example, missing coroutine APIs).
    log_parts = [log] if log.strip() else []
    build_log_path = Path(args.build_log)
    if build_log_path.exists():
        full_build_log = build_log_path.read_text(encoding="utf-8", errors="ignore")
        if full_build_log.strip() and full_build_log not in log:
            log_parts.append(full_build_log)
    log = "\\n".join(log_parts)

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
        print("DETERMINISTIC_FIX_SKIPPED: Could not identify an existing provider Kotlin source file in the full build log.")
        return

    parts = Path(relative).parts
    if len(parts) < 5 or parts[1:4] != ("src", "main", "kotlin"):
        raise RuntimeError("Unsafe target path: " + relative)
    if not (root / parts[0] / "build.gradle.kts").is_file():
        raise RuntimeError("Target is not a provider module: " + relative)

    source = target.read_text(encoding="utf-8", errors="ignore")
    # Reuse the same local, deterministic CloudStream API normalizer as the URL builder.
    # This path never sends source code or logs to an external AI service.
    sys.path.insert(0, str(root / "doctor"))
    try:
        from site_to_plugin import _normalize_cloudstream_api
        updated = _normalize_cloudstream_api(source)
    except Exception as exc:
        print("DETERMINISTIC_FIX_UNAVAILABLE:", str(exc))
        return

    if updated == source:
        print("DETERMINISTIC_FIX_SKIPPED: No known safe Kotlin API normalization applies to", relative)
        return

    if "package com.Kayracs3" not in updated or "MainAPI" not in updated:
        print("DETERMINISTIC_FIX_SKIPPED: Normalized source failed basic safety checks:", relative)
        return

    target.write_text(updated.rstrip() + "\n", encoding="utf-8")
    print("DETERMINISTIC_FIX: Applied known CloudStream API compatibility rules.")
    print("Updated:", relative)

if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print("AUTO_FIX_ERROR:", exc, file=sys.stderr)
        sys.exit(1)
