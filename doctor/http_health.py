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

MAIN_URL_RE = re.compile(r'(?m)\bmainUrl\b[^=\n]*=\s*"([^"]+)"')


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

            # A 200 response with a tiny body may be an SPA shell, redirect
            # placeholder, or transient edge response. Report it as inconclusive
            # instead of declaring the whole provider broken on byte count alone.
            return {
                **item,
                "status": "inconclusive" if 200 <= status < 400 else "fail",
                "http_status": status,
                "bytes": len(body),
                "content_type": content_type,
                "detail": "HTTP response was too small to verify site health" if 200 <= status < 400 else "unexpected HTTP response",
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
        detail = str(exc)
        # Site-edge and hosted-runner timeouts are intermittent and do not
        # prove a provider is broken. Keep them visible but non-blocking.
        timed_out = "timed out" in detail.lower() or "timeout" in detail.lower()
        return {
            **item,
            "status": "inconclusive" if timed_out else "fail",
            "http_status": None,
            "detail": detail,
        }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    root = Path(args.root).resolve()
    providers = find_providers(root)

    # Domain registry tracks reviewed aliases, but never changes a provider's
    # mainUrl automatically. The Kotlin source remains the primary-domain source of truth.
    domain_registry_path = root / "doctor" / "domains.json"
    domain_registry = {}
    if domain_registry_path.exists():
        try:
            domain_registry = json.loads(
                domain_registry_path.read_text(encoding="utf-8")
            )
        except Exception as exc:
            print(f"WARNING: invalid doctor/domains.json: {exc}", file=sys.stderr)
    domain_configured = domain_registry.get("providers", {})

    health_config_path = root / "doctor" / "providers.json"
    health_config = {}
    if health_config_path.exists():
        try:
            health_config = json.loads(
                health_config_path.read_text(encoding="utf-8")
            )
        except Exception as exc:
            print(f"WARNING: invalid doctor/providers.json: {exc}", file=sys.stderr)
    configured = health_config.get("providers", {})

    expanded_providers = []
    for provider in providers:
        expanded_providers.append({**provider, "domain_role": "primary"})

        domain_extra = domain_configured.get(provider["name"], {})
        aliases = domain_extra.get("approvedAliases", [])
        if not isinstance(aliases, list):
            aliases = []
        for index, alias in enumerate(aliases, start=1):
            if isinstance(alias, dict):
                alias_url = str(alias.get("url", "")).strip()
                alias_note = str(alias.get("note", "")).strip()
            else:
                alias_url = str(alias).strip()
                alias_note = ""
            if not alias_url:
                continue
            expanded_providers.append({
                **provider,
                "name": f"{provider['name']}::alias-{index}",
                "provider_name": provider["name"],
                "url": alias_url.rstrip("/"),
                "domain_role": "approved-alias",
                "domain_note": alias_note,
            })

        extra = configured.get(provider["name"], {})
        test_url = extra.get("testUrl")
        if test_url:
            expanded_providers.append({
                **provider,
                "name": provider["name"] + "::deep",
                "url": str(test_url).rstrip("/"),
                "domain_role": "deep-test",
            })

    providers = expanded_providers

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
        "providers": len([x for x in results if x.get("domain_role") == "primary"]),
        "targets": len(results),
        "primary_domains": len([x for x in results if x.get("domain_role") == "primary"]),
        "approved_aliases": len([x for x in results if x.get("domain_role") == "approved-alias"]),
        "deep_tests": len([x for x in results if x.get("domain_role") == "deep-test"]),
        "ok": len([x for x in results if x["status"] == "ok"]),
        "protected": len(protected),
        "inconclusive": len([x for x in results if x["status"] == "inconclusive"]),
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
