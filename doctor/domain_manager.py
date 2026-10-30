#!/usr/bin/env python3
"""Safely discover and update changed provider domains for Kayra.

Automatic switches are intentionally limited to candidates proven by an
HTTP redirect, a canonical/og:url link from the old site, or an explicitly
configured trusted candidate/discovery URL. A candidate must also match the
provider's saved identity profile or explicit identity markers.
"""
from __future__ import annotations

import argparse
import html
import json
import re
import sys
import unicodedata
from concurrent.futures import ThreadPoolExecutor, as_completed
from html.parser import HTMLParser
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote_plus, urljoin, urlparse, urlunparse
from urllib.request import Request, urlopen

MAIN_URL_RE = re.compile(
    r'''(?m)^\s*(?:override\s+)?(?:var|val)\s+mainUrl\s*=\s*["']([^"']+)["']'''
)
MAIN_URL_ASSIGN_RE = re.compile(
    r'''(?m)^([ \t]*(?:override\s+)?(?:var|val)\s+mainUrl\s*=\s*["'])([^"']+)(["'])'''
)
VERSION_RE = re.compile(r'(?m)^(\s*version\s*=\s*)(\d+)(\s*(?:#.*)?$)')
USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "Chrome/130.0.0.0 Safari/537.36 KayraDomainManager/1.0"
)
MIN_BODY_BYTES = 500
MAX_BODY_BYTES = 750_000
BLOCK_PAGE_MARKERS = (
    "just a moment", "attention required", "checking your browser",
    "verify you are human", "access denied", "security check",
    "captcha", "bot verification", "domain for sale", "buy this domain",
    "parked domain", "site not configured", "under construction",
)


def normalize_text(value: str) -> str:
    value = html.unescape(value or "")
    value = unicodedata.normalize("NFKD", value)
    value = "".join(ch for ch in value if not unicodedata.combining(ch))
    return re.sub(r"\s+", " ", re.sub(r"[^a-zA-Z0-9]+", " ", value.lower())).strip()


def host_of(url: str) -> str:
    return (urlparse(url).hostname or "").lower().removeprefix("www.")


def origin_of(url: str) -> str:
    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise ValueError(f"Invalid HTTP(S) URL: {url}")
    host = parsed.hostname.lower()
    port = parsed.port
    netloc = host if not port or (parsed.scheme == "https" and port == 443) or (parsed.scheme == "http" and port == 80) else f"{host}:{port}"
    return urlunparse((parsed.scheme, netloc, "", "", "", ""))


class PageParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.title_parts: list[str] = []
        self.h1_parts: list[str] = []
        self._capture: str | None = None
        self.meta: dict[str, str] = {}
        self.canonical: list[str] = []
        self.links: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attrs_d = {key.lower(): (value or "") for key, value in attrs}
        if tag == "title":
            self._capture = "title"
        elif tag == "h1":
            self._capture = "h1"
        elif tag == "meta":
            key = (attrs_d.get("property") or attrs_d.get("name") or "").lower()
            value = attrs_d.get("content", "").strip()
            if key and value:
                self.meta[key] = value
        elif tag == "link":
            rel = attrs_d.get("rel", "").lower().split()
            href = attrs_d.get("href", "").strip()
            if href and "canonical" in rel:
                self.canonical.append(href)
            elif href and "alternate" in rel:
                self.links.append(href)
        elif tag == "a":
            href = attrs_d.get("href", "").strip()
            if href:
                self.links.append(href)

    def handle_endtag(self, tag: str) -> None:
        if tag == "title" and self._capture == "title":
            self._capture = None
        elif tag == "h1" and self._capture == "h1":
            self._capture = None

    def handle_data(self, data: str) -> None:
        if not data.strip():
            return
        if self._capture == "title":
            self.title_parts.append(data.strip())
        elif self._capture == "h1":
            self.h1_parts.append(data.strip())


def parse_page(body: bytes, base_url: str) -> dict[str, Any]:
    text = body.decode("utf-8", errors="replace")
    parser = PageParser()
    try:
        parser.feed(text)
    except Exception:
        pass
    title = " ".join(parser.title_parts).strip() or parser.meta.get("og:title", "")
    h1 = " ".join(parser.h1_parts).strip()
    site_name = parser.meta.get("og:site_name", "")
    description = parser.meta.get("og:description", "") or parser.meta.get("description", "")
    fields = [title, h1, site_name, description]
    tokens = sorted({token for field in fields for token in normalize_text(field).split() if len(token) >= 3})
    canonical_urls = [urljoin(base_url, value) for value in parser.canonical]
    canonical_urls += [urljoin(base_url, parser.meta.get("og:url", ""))] if parser.meta.get("og:url") else []
    discovered_links = [urljoin(base_url, value) for value in parser.links]
    return {
        "title": title[:180],
        "h1": h1[:180],
        "siteName": site_name[:120],
        "description": description[:240],
        "tokens": tokens[:40],
        "canonicalUrls": canonical_urls[:8],
        "links": discovered_links[:300],
    }


def identity_match(profile: dict[str, Any] | None, candidate: dict[str, Any], markers: list[str] | None = None) -> tuple[bool, str]:
    markers = [str(item).strip() for item in (markers or []) if str(item).strip()]
    identity_fields = " ".join([
        candidate.get("title", ""), candidate.get("h1", ""),
        candidate.get("siteName", ""), candidate.get("description", ""),
    ])
    candidate_text = normalize_text(identity_fields + " " + " ".join(candidate.get("tokens", [])))
    identity_header = normalize_text(" ".join([
        candidate.get("title", ""), candidate.get("h1", ""), candidate.get("siteName", "")
    ]))
    if any(marker in identity_header for marker in BLOCK_PAGE_MARKERS):
        return False, "candidate looks like a protection, parked-domain, or placeholder page"
    if markers:
        missing = [marker for marker in markers if normalize_text(marker) not in candidate_text]
        return (not missing, "explicit markers matched" if not missing else "missing identity markers: " + ", ".join(missing))

    baseline = set((profile or {}).get("tokens", []))
    current = set(candidate.get("tokens", []))
    common = baseline & current
    if len(baseline) < 3:
        return False, "not enough saved identity tokens; add identityMarkers to doctor/domains.json for this provider"
    ratio = len(common) / max(1, len(baseline))
    required = max(3, min(5, (len(baseline) + 1) // 2))
    if len(common) >= required and ratio >= 0.35:
        return True, f"identity matched ({len(common)}/{len(baseline)} tokens, ratio={ratio:.2f})"
    return False, f"identity mismatch ({len(common)}/{len(baseline)} tokens, ratio={ratio:.2f})"


def fetch_page(url: str, timeout: int = 12) -> dict[str, Any]:
    request = Request(url, headers={
        "User-Agent": USER_AGENT,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "tr-TR,tr;q=0.9,en;q=0.7",
    })
    try:
        with urlopen(request, timeout=timeout) as response:
            body = response.read(MAX_BODY_BYTES)
            final_url = response.geturl()
            status = getattr(response, "status", 200)
            content_type = response.headers.get("Content-Type", "")
            return {"status": status, "finalUrl": final_url, "contentType": content_type, "body": body, "error": ""}
    except HTTPError as exc:
        try:
            body = exc.read(MAX_BODY_BYTES)
        except Exception:
            body = b""
        return {
            "status": exc.code, "finalUrl": exc.geturl(),
            "contentType": exc.headers.get("Content-Type", "") if exc.headers else "",
            "body": body, "error": str(exc),
        }
    except (URLError, TimeoutError, OSError) as exc:
        return {"status": None, "finalUrl": url, "contentType": "", "body": b"", "error": str(exc)}


def discover_modules(root: Path) -> list[dict[str, str]]:
    providers: list[dict[str, str]] = []
    for build_file in sorted(root.glob("*/build.gradle.kts")):
        module = build_file.parent
        candidates: list[tuple[Path, str]] = []
        for kt in sorted((module / "src/main/kotlin").rglob("*.kt")):
            try:
                source = kt.read_text(encoding="utf-8", errors="ignore")
            except OSError:
                continue
            match = MAIN_URL_RE.search(source)
            if match and re.search(r":\s*MainAPI\s*\(", source):
                candidates.append((kt, match.group(1).strip()))
        if candidates:
            source_file, main_url = candidates[0]
            providers.append({
                "name": module.name,
                "url": main_url.rstrip("/"),
                "source": source_file.relative_to(root).as_posix(),
                "module": module.name,
            })
    return providers


def normalize_candidate(raw: str, old_url: str) -> str | None:
    raw = str(raw or "").strip()
    if not raw:
        return None
    if "://" not in raw:
        raw = "https://" + raw.lstrip("/")
    try:
        candidate = origin_of(urljoin(old_url, raw))
        if host_of(candidate) == host_of(old_url):
            return None
        return candidate
    except Exception:
        return None


def configured_candidates(config: dict[str, Any], old_url: str) -> list[tuple[str, str]]:
    result: list[tuple[str, str]] = []
    for raw in config.get("candidateDomains", []) if isinstance(config.get("candidateDomains", []), list) else []:
        candidate = normalize_candidate(str(raw), old_url)
        if candidate:
            result.append((candidate, "configured candidate domain"))
    for discovery_url in config.get("discoveryUrls", []) if isinstance(config.get("discoveryUrls", []), list) else []:
        discovery = fetch_page(str(discovery_url))
        if not (discovery["status"] and 200 <= discovery["status"] < 300):
            continue
        page = parse_page(discovery["body"], discovery["finalUrl"])
        for link in page["links"]:
            candidate = normalize_candidate(link, old_url)
            if candidate:
                result.append((candidate, f"link found on trusted discovery URL {discovery_url}"))
    deduped: dict[str, str] = {}
    for candidate, reason in result:
        deduped.setdefault(candidate, reason)
    return list(deduped.items())


def search_candidates(provider: dict[str, str], profile: dict[str, Any] | None, old_url: str) -> list[tuple[str, str]]:
    """Use web search only to nominate possible hosts; identity checks still decide trust.

    This is a last-resort discovery layer for cases where the old hostname no longer
    redirects and has no useful canonical metadata. Search results are never applied
    directly, and all candidates must pass identity_match() in inspect_provider().
    """
    profile = profile or {}
    brand = str(profile.get("siteName") or profile.get("h1") or "").strip()
    title = str(profile.get("title") or "").strip()
    queries = []
    if brand:
        queries.append(f'"{brand}" official website')
        if title and normalize_text(title) != normalize_text(brand):
            queries.append(f'"{brand}" "{title}"')
    # The module name is a weaker fallback, but still only creates a candidate.
    module_name = provider.get("name", "").strip()
    if module_name:
        queries.append(f'"{module_name}" official site')

    found: dict[str, str] = {}
    for query in queries[:3]:
        search_url = "https://www.bing.com/search?format=rss&q=" + quote_plus(query)
        response = fetch_page(search_url, timeout=8)
        if not (response.get("status") and 200 <= response["status"] < 300):
            continue
        body = response.get("body", b"")
        try:
            from xml.etree import ElementTree
            root = ElementTree.fromstring(body)
            links = [node.text.strip() for node in root.findall(".//item/link") if node.text]
        except Exception:
            links = []
        for link in links[:12]:
            candidate = normalize_candidate(link, old_url)
            if candidate and candidate not in found:
                found[candidate] = f'web search nomination for {query!r}'
        if len(found) >= 8:
            break
    return list(found.items())[:8]


def update_module_domain(root: Path, provider: dict[str, str], old_url: str, new_url: str) -> list[str]:
    module = root / provider["module"]
    old_host = urlparse(old_url).hostname or ""
    new_host = urlparse(new_url).hostname or ""
    changed: list[str] = []
    source_files = list((module / "src/main/kotlin").rglob("*.kt"))
    source_files += [module / "build.gradle.kts"]
    for path in source_files:
        if not path.is_file():
            continue
        try:
            original = path.read_text(encoding="utf-8")
        except OSError:
            continue
        updated = original
        if path.suffix == ".kt":
            # Set the canonical mainUrl exactly (including scheme/port), then
            # update other first-party literals that still point to the old host.
            updated = MAIN_URL_ASSIGN_RE.sub(
                lambda match: (
                    match.group(1)
                    + re.sub(r"(?i)^https?://[^/]+", new_url.rstrip("/"), match.group(2), count=1)
                    + match.group(3)
                ),
                updated,
                count=1,
            )
        updated = updated.replace(old_host, new_host)
        if updated != original:
            path.write_text(updated, encoding="utf-8")
            changed.append(path.relative_to(root).as_posix())

    gradle_file = module / "build.gradle.kts"
    if gradle_file.exists():
        gradle_text = gradle_file.read_text(encoding="utf-8")
        bumped, count = VERSION_RE.subn(
            lambda match: f"{match.group(1)}{int(match.group(2)) + 1}{match.group(3)}",
            gradle_text,
            count=1,
        )
        if count and bumped != gradle_text:
            gradle_file.write_text(bumped, encoding="utf-8")
            rel = gradle_file.relative_to(root).as_posix()
            if rel not in changed:
                changed.append(rel)
    return changed


def inspect_provider(root: Path, provider: dict[str, str], registry_entry: dict[str, Any], apply: bool) -> dict[str, Any]:
    old_url = provider["url"]
    old_host = host_of(old_url)
    result: dict[str, Any] = {
        "provider": provider["name"], "currentUrl": old_url,
        "status": "unknown", "candidate": None, "reason": "",
        "changedFiles": [],
    }
    current = fetch_page(old_url)
    status = current["status"]
    final_url = current["finalUrl"]
    redirected_host = host_of(final_url) if final_url else old_host
    page = parse_page(current["body"], final_url or old_url) if current["body"] else None

    profile = registry_entry.get("identityProfile")
    if not profile and status and 200 <= status < 300 and page and redirected_host == old_host:
        identity_header = normalize_text(" ".join([page.get("title", ""), page.get("h1", ""), page.get("siteName", "")]))
        is_block_page = any(marker in identity_header for marker in BLOCK_PAGE_MARKERS)
        if len(page.get("tokens", [])) >= 3 and not is_block_page:
            registry_entry["identityProfile"] = {
                key: page[key] for key in ("title", "h1", "siteName", "description", "tokens")
            }
            profile = registry_entry["identityProfile"]
            result["identityProfileSaved"] = True

    candidates: list[tuple[str, str]] = []
    if redirected_host and redirected_host != old_host:
        candidate = normalize_candidate(final_url, old_url)
        if candidate:
            candidates.append((candidate, f"HTTP redirect from primary domain to {final_url}"))

    if page:
        for canonical in page.get("canonicalUrls", []):
            candidate = normalize_candidate(canonical, old_url)
            if candidate:
                candidates.append((candidate, f"cross-domain canonical/og:url discovered on {old_url}"))

    # If the old endpoint still serves a healthy page on the original hostname,
    # do not switch merely because an unrelated candidate happens to respond.
    current_is_healthy = bool(
        status and 200 <= status < 300 and len(current["body"]) >= MIN_BODY_BYTES
        and "html" in (current["contentType"] or "text/html").lower()
        and redirected_host == old_host
    )

    if current_is_healthy:
        result["status"] = "ok"
        result["reason"] = "primary domain is healthy; no switch needed"
        return result

    candidates.extend(configured_candidates(registry_entry, old_url))
    # Last-resort discovery: the search engine only nominates hosts. The same
    # saved identity profile / explicit markers below must still validate them.
    if not candidates:
        candidates.extend(search_candidates(provider, profile, old_url))
    unique: dict[str, str] = {}
    for candidate, reason in candidates:
        unique.setdefault(candidate, reason)

    markers = registry_entry.get("identityMarkers", [])
    for candidate_url, discovery_reason in unique.items():
        probe = fetch_page(candidate_url)
        probe_status = probe["status"]
        if not (probe_status and 200 <= probe_status < 300):
            result.setdefault("rejectedCandidates", []).append({
                "url": candidate_url, "reason": f"candidate HTTP status {probe_status}; {probe.get('error', '')}".strip()
            })
            continue
        if len(probe["body"]) < MIN_BODY_BYTES or "html" not in (probe["contentType"] or "text/html").lower():
            result.setdefault("rejectedCandidates", []).append({"url": candidate_url, "reason": "candidate is not a substantial HTML page"})
            continue
        candidate_host = host_of(probe["finalUrl"])
        if not candidate_host or candidate_host == old_host:
            result.setdefault("rejectedCandidates", []).append({"url": candidate_url, "reason": "candidate redirects back to old domain"})
            continue
        candidate_page = parse_page(probe["body"], probe["finalUrl"])
        matched, identity_reason = identity_match(profile, candidate_page, markers)
        # On the first scan a provider may already be offline, so no live
        # identity profile could have been learned. In that case only accept
        # a candidate whose page title/H1/site-name contains the exact module
        # brand (compact comparison handles camel case and punctuation).
        if not matched and not profile and not markers:
            brand = normalize_text(provider.get("name", "")).replace(" ", "")
            header = normalize_text(" ".join([
                candidate_page.get("title", ""),
                candidate_page.get("h1", ""),
                candidate_page.get("siteName", ""),
            ])).replace(" ", "")
            if len(brand) >= 6 and brand in header:
                matched = True
                identity_reason = "provider module brand matched candidate page header"
        if not matched:
            result.setdefault("rejectedCandidates", []).append({
                "url": candidate_url, "reason": f"{identity_reason}; discovered by {discovery_reason}"
            })
            continue

        new_url = origin_of(probe["finalUrl"])
        result.update({
            "status": "verified-candidate", "candidate": new_url,
            "reason": f"{discovery_reason}; {identity_reason}",
        })
        if apply and registry_entry.get("automaticSwitch", True):
            changed = update_module_domain(root, provider, old_url, new_url)
            result["changedFiles"] = changed
            registry_entry.setdefault("history", []).append({
                "from": old_url, "to": new_url,
                "reason": result["reason"],
            })
            registry_entry["history"] = registry_entry["history"][-10:]
            registry_entry["identityProfile"] = {
                key: candidate_page[key] for key in ("title", "h1", "siteName", "description", "tokens")
            }
            registry_entry["lastSwitch"] = {"from": old_url, "to": new_url, "reason": result["reason"]}
            result["status"] = "switched" if changed else "verified-no-source-change"
        elif not registry_entry.get("automaticSwitch", True):
            result["status"] = "verified-awaiting-manual-switch"
        return result

    if current["status"] is None:
        result["status"] = "inconclusive"
    elif current["status"] in {401, 403, 429, 451, 503}:
        result["status"] = "protected"
    else:
        result["status"] = "unavailable"
    result["reason"] = current.get("error") or f"primary domain returned HTTP {status}; no identity-verified candidate found"
    if not profile and not markers:
        result["reason"] += "; identity baseline unavailable, so automatic switching is disabled for safety"
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description="Discover and safely update Kayra provider domains")
    parser.add_argument("--root", default=".")
    parser.add_argument("--report", default="domain-results/domain-report.json")
    parser.add_argument("--apply", action="store_true", help="write verified domain changes to provider sources")
    parser.add_argument("--workers", type=int, default=6)
    args = parser.parse_args()

    root = Path(args.root).resolve()
    registry_path = root / "doctor" / "domains.json"
    try:
        registry = json.loads(registry_path.read_text(encoding="utf-8"))
    except Exception as exc:
        print(f"ERROR: cannot read {registry_path}: {exc}", file=sys.stderr)
        return 2

    if registry.get("version", 0) < 2:
        registry["version"] = 2
    policy = registry.setdefault("policy", {})
    policy["automaticSwitching"] = True
    policy["switchRule"] = (
        "HTTP redirect, cross-domain canonical/og:url, configured candidateDomains/discoveryUrls, and last-resort "
        "search results may nominate a host. Search results are never trusted by themselves: the candidate must "
        "return a substantial HTML page and match saved identityProfile or all configured identityMarkers."
    )
    policy["limits"] = [
        "If the old domain is fully offline and no saved profile or provider-brand marker can verify a search nominee, report it for curation instead of guessing.",
        "403/429/Cloudflare protection is not treated as proof that a domain changed.",
        "A successful HTTP response alone never authorizes a domain switch.",
    ]
    providers_cfg = registry.setdefault("providers", {})
    providers = discover_modules(root)
    results: list[dict[str, Any]] = []

    # Every provider gets a registry entry so identity can be learned while its
    # currently configured domain is still healthy.
    with ThreadPoolExecutor(max_workers=max(1, min(args.workers, 12))) as pool:
        future_map = {}
        for provider in providers:
            entry = providers_cfg.setdefault(provider["name"], {})
            future_map[pool.submit(inspect_provider, root, provider, entry, args.apply)] = provider["name"]
        for future in as_completed(future_map):
            try:
                results.append(future.result())
            except Exception as exc:
                results.append({"provider": future_map[future], "status": "error", "reason": repr(exc), "changedFiles": []})

    results.sort(key=lambda item: item.get("provider", "").lower())
    registry_path.write_text(json.dumps(registry, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    report_path = (root / args.report).resolve()
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report = {
        "version": 1,
        "mode": "apply" if args.apply else "dry-run",
        "providers": len(providers),
        "switched": sum(item.get("status") == "switched" for item in results),
        "verifiedCandidates": sum(item.get("candidate") is not None for item in results),
        "unavailable": sum(item.get("status") in {"unavailable", "protected", "inconclusive"} for item in results),
        "results": results,
    }
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
