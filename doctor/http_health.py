#!/usr/bin/env python3

import argparse
import json
import re
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from urllib.parse import urlparse

MAIN_URL_RE = re.compile(r'(?m)\bmainUrl\s*=\s*"([^"]+)"')


def find_providers(root: Path):
    providers = []
    for build_file in root.glob("*/build.gradle.kts"):
        module = build_file.parent
        kt_files = list(module.glob("src/main/kotlin/**/*.kt"))
        if not kt_files:
            continue

        main_url = None
        source_file = None

        for kt in kt_files:
            try:
                text = kt.read_text(encoding="utf-8", errors="ignore")
            except OSError:
                continue

            match = MAIN_URL_RE.search(text)
            if match:
                main_url = match.group(1).strip()
                source_file = str(kt)
                break

        if main_url:
            providers.append({
                "name": module.name,
                "url": main_url.rstrip("/"),
                "source": source_file,
            })

    return providers


def check_url(item):
    url = item["url"]
    parsed = urlparse(url)

    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        return {
            **item,
            "status": "invalid-url",
            "http_status": None,
            "detail": "mainUrl is not a valid HTTP(S) URL",
        }

    request = Request(
        url,
        headers={
            "User-Agent": (
                "Mozilla/5.0 (X11; Linux x86_64) "
                "AppleWebKit/537.36 Chrome/150 Safari/537.36 KayraDoctor/1.0"
            ),
            "Accept": "text/html,application/xhtml+xml,*/*;q=0.8",
        },
        method="GET",
    )

    try:
        with urlopen(request, timeout=20) as response:
            body = response.read(250_000)
            status = response.status
            content_type = response.headers.get("Content-Type", "")

            if 200 <= status < 400 and len(body) > 100:
                return {
                    **item,
                    "status": "ok",
                    "http_status": status,
                    "bytes": len(body),
                    "content_type": content_type,
                    "detail": "homepage reachable",
                }

            return {
                **item,
                "status": "fail",
                "http_status": status,
                "bytes": len(body),
                "content_type": content_type,
                "detail": "unexpected HTTP response",
            }

    except HTTPError as exc:
        if exc.code in {401, 403, 429, 451, 503}:
            return {
                **item,
                "status": "protected",
                "http_status": exc.code,
                "detail": "site responded but blocks automated requests or is unavailable to this runner for legal/policy reasons",
            }

        return {
            **item,
            "status": "fail",
            "http_status": exc.code,
            "detail": str(exc),
        }

    except (URLError, TimeoutError, OSError) as exc:
        return {
            **item,
            "status": "fail",
            "http_status": None,
            "detail": str(exc),
        }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    root = Path(args.root).resolve()
    providers = find_providers(root)

    config_path = root / "doctor" / "providers.json"
    if config_path.exists():
        try:
            config = json.loads(config_path.read_text(encoding="utf-8"))
        except Exception:
            config = {}
        configured = config.get("providers", {})
        for provider in list(providers):
            extra = configured.get(provider["name"], {})
            test_url = extra.get("testUrl")
            if test_url:
                providers.append({
                    "name": provider["name"] + "::deep",
                    "url": str(test_url).rstrip("/"),
                    "source": provider["source"],
                })

    results = []

    with ThreadPoolExecutor(max_workers=8) as pool:
        futures = {pool.submit(check_url, item): item for item in providers}
        for future in as_completed(futures):
            results.append(future.result())

    results.sort(key=lambda x: x["name"].lower())

    hard_failures = [
        x for x in results
        if x["status"] in {"fail", "invalid-url"}
    ]

    protected = [
        x for x in results
        if x["status"] == "protected"
    ]

    payload = {
        "providers": len(results),
        "ok": len([x for x in results if x["status"] == "ok"]),
        "protected": len(protected),
        "failed": len(hard_failures),
        "results": results,
    }

    Path(args.output).write_text(
        json.dumps(payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 1 if hard_failures else 0


if __name__ == "__main__":
    sys.exit(main())
