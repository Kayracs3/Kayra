#!/usr/bin/env python3
"""Diagnose DiziSol poster markup on GitHub Actions runners.

This script is diagnostic only: it never edits provider code and never fails the
whole workflow just because DiziSol blocks automated requests.
"""
import argparse
import json
import re
import sys
from html.parser import HTMLParser
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin, urlparse
from urllib.request import Request, urlopen

BASE = "https://dizisol.com"
PAGES = [
    "/",
    "/diziler",
    "/filmler",
    "/netflix-dizileri",
    "/?s=Breaking%20Bad",
    "/?s=Avatar",
]
API_PROBE_PATHS = [
    "/api/library/home-feed",
    "/api/library/browse?type=movie&page=1",
    "/api/library/browse?type=tv&page=1",
    "/api/movies/search?q=Breaking%20Bad",
    "/api/library/tmdb-ids",
    "/api/tmdb/search/multi?query=Breaking%20Bad&page=1",
    "/api/tmdb/search/movie?query=Avatar&page=1",
    "/api/tmdb/search/tv?query=Breaking%20Bad&page=1",
    "/api/tmdb/movie/popular",
    "/api/tmdb/trending/all/week",
    "/api/tmdb/movie/550",
    "/api/tmdb/tv/1399",
]
UA = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"
)
TMDB_RE = re.compile(
    r"""(?i)(?:https?:)?//image\.tmdb\.org/t/p/[A-Za-z0-9_/-]+"""
    r"""[A-Za-z0-9_%.-]+\.(?:jpe?g|png|webp)(?:\?[^\s"'<>)]*)?"""
)
POSTER_PATH_RE = re.compile(
    r"""(?i)["']poster_path["']\s*:\s*["']([^"']+)["']"""
)
CONTENT_PATH_RE = re.compile(r"""(?i)/(?:film|dizi)/[^"'?#\s<>]+""")
SEASON_EP_RE = re.compile(r"""(?i)\d+-sezon-\d+-bolum""")
IMAGE_ATTR_RE = re.compile(
    r"""(?i)(?:src|srcset|poster|image|thumb|background|poster_path|tmdb)"""
)


class Node:
    def __init__(self, tag, attrs, parent=None):
        self.tag = tag.lower()
        self.attrs = dict(attrs)
        self.parent = parent
        self.children = []
        self.text_parts = []

    @property
    def text(self):
        values = list(self.text_parts)
        for child in self.children:
            values.append(child.text)
        return re.sub(r"\s+", " ", " ".join(values)).strip()

    def walk(self):
        yield self
        for child in self.children:
            yield from child.walk()

    def outer_summary(self):
        attrs = {}
        for key, val in self.attrs.items():
            if key in {"class", "id", "href", "src", "srcset", "data-src",
                       "data-lazy-src", "data-original", "data-poster",
                       "data-poster-path", "poster_path", "style"}:
                attrs[key] = (val or "")[:600]
            elif IMAGE_ATTR_RE.search(key):
                attrs[key] = (val or "")[:600]
        return {
            "tag": self.tag,
            "attrs": attrs,
            "text": self.text[:200],
        }


class TreeParser(HTMLParser):
    VOID = {
        "area", "base", "br", "col", "embed", "hr", "img", "input", "link",
        "meta", "param", "source", "track", "wbr",
    }

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.root = Node("document", {})
        self.stack = [self.root]

    def handle_starttag(self, tag, attrs):
        node = Node(tag, attrs, self.stack[-1])
        self.stack[-1].children.append(node)
        if tag.lower() not in self.VOID:
            self.stack.append(node)

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)
        if tag.lower() not in self.VOID and len(self.stack) > 1:
            self.stack.pop()

    def handle_endtag(self, tag):
        tag = tag.lower()
        for i in range(len(self.stack) - 1, 0, -1):
            if self.stack[i].tag == tag:
                del self.stack[i:]
                break

    def handle_data(self, data):
        self.stack[-1].text_parts.append(data)


def fetch(url):
    request = Request(url, headers={
        "User-Agent": UA,
        "Accept": "text/html,application/xhtml+xml,application/json,*/*;q=0.8",
        "Accept-Language": "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
    })
    try:
        with urlopen(request, timeout=18) as response:
            body = response.read(8_000_000).decode("utf-8", "replace")
            return {
                "requested_url": url,
                "final_url": response.geturl(),
                "status": response.status,
                "content_type": response.headers.get("Content-Type", ""),
                "body": body,
                "error": None,
            }
    except HTTPError as exc:
        data = ""
        try:
            data = exc.read(200_000).decode("utf-8", "replace")
        except Exception:
            pass
        return {
            "requested_url": url,
            "final_url": exc.geturl(),
            "status": exc.code,
            "content_type": exc.headers.get("Content-Type", "") if exc.headers else "",
            "body": data,
            "error": str(exc),
        }
    except (URLError, TimeoutError, OSError) as exc:
        return {
            "requested_url": url,
            "final_url": url,
            "status": None,
            "content_type": "",
            "body": "",
            "error": str(exc),
        }


def image_candidates(node, base_url):
    output = []
    for item in node.walk():
        attrs = item.attrs
        for key, raw in attrs.items():
            if not raw or not (
                IMAGE_ATTR_RE.search(key)
                or key.lower() == "style"
                or key.lower().startswith("data-bg")
            ):
                continue
            for candidate in re.split(r"\s*,\s*", raw):
                candidate = candidate.strip().split(" ")[0].strip("\"'")
                match = re.search(r"""(?i)url\(\s*['"]?([^'")]+)""", candidate)
                if match:
                    candidate = match.group(1)
                if not candidate or candidate.startswith("data:"):
                    continue
                if key.lower().endswith("srcset") or key.lower() == "srcset":
                    candidate = candidate.split(" ")[0].strip()
                absolute = urljoin(base_url, candidate)
                if urlparse(absolute).scheme not in {"http", "https"}:
                    continue
                if any(x in absolute.lower() for x in (
                    "logo", "avatar", "placeholder", "no-image", "blank."
                )):
                    continue
                record = {
                    "url": absolute[:1000],
                    "from_attr": key,
                    "element": item.outer_summary(),
                }
                if record["url"] not in {x["url"] for x in output}:
                    output.append(record)
    return output


def analyze_page(page):
    body = page["body"]
    parser = TreeParser()
    try:
        parser.feed(body)
    except Exception:
        pass
    all_nodes = list(parser.root.walk())
    anchors = []
    for node in all_nodes:
        if node.tag != "a":
            continue
        href = node.attrs.get("href", "").strip()
        if not href:
            continue
        absolute = urljoin(page["final_url"], href)
        path = urlparse(absolute).path
        if not (CONTENT_PATH_RE.search(path) or SEASON_EP_RE.search(path)):
            continue

        related = []
        ancestor = node.parent
        depth = 0
        while ancestor is not None and depth < 6:
            candidates = image_candidates(ancestor, page["final_url"])
            content_links = []
            for a in ancestor.walk():
                if a.tag != "a":
                    continue
                h = a.attrs.get("href", "").strip()
                if not h:
                    continue
                target = urljoin(page["final_url"], h)
                target_path = urlparse(target).path
                if CONTENT_PATH_RE.search(target_path) or SEASON_EP_RE.search(target_path):
                    content_links.append(target)
            related.append({
                "depth": depth + 1,
                "container": {
                    "tag": ancestor.tag,
                    "class": ancestor.attrs.get("class", "")[:250],
                    "id": ancestor.attrs.get("id", "")[:120],
                },
                "content_link_count": len(set(content_links)),
                "image_candidate_count": len(candidates),
                "tmdb_candidates": [
                    x for x in candidates if "image.tmdb.org/t/p/" in x["url"].lower()
                ][:12],
                "image_candidates": candidates[:12],
            })
            ancestor = ancestor.parent
            depth += 1
        anchors.append({
            "href": absolute,
            "text": node.text[:250],
            "anchor": node.outer_summary(),
            "ancestors": related,
        })

    direct_tmdb = list(dict.fromkeys(TMDB_RE.findall(body)))
    poster_paths = list(dict.fromkeys(POSTER_PATH_RE.findall(body)))
    script_urls = list(dict.fromkeys(
        urljoin(page["final_url"], node.attrs.get("src", "").strip())
        for node in all_nodes
        if node.tag == "script" and node.attrs.get("src", "").strip()
    ))
    all_image_nodes = [
        n for n in all_nodes
        if n.tag in {"img", "source"} or any(
            IMAGE_ATTR_RE.search(key) or key.lower() == "style" or key.lower().startswith("data-bg")
            for key in n.attrs
        )
    ]
    return {
        "requested_url": page["requested_url"],
        "final_url": page["final_url"],
        "http_status": page["status"],
        "content_type": page["content_type"],
        "error": page["error"],
        "html_bytes": len(body.encode("utf-8")),
        "tmdb_url_count": len(direct_tmdb),
        "tmdb_urls": direct_tmdb[:100],
        "poster_path_count": len(poster_paths),
        "poster_paths": poster_paths[:100],
        "image_element_count": sum(1 for n in all_nodes if n.tag in {"img", "source"}),
        "image_attribute_node_count": len(all_image_nodes),
        "content_anchor_count": len(anchors),
        "content_anchors": anchors[:30],
        "script_urls": script_urls[:40],
        "script_count": len(script_urls),
        "html_start": body[:1800],
    }


def summarize_api_endpoint(path):
    url = urljoin(BASE, path)
    response = fetch(url)
    body = response["body"]
    result = {
        "path": path,
        "url": response["final_url"],
        "status": response["status"],
        "content_type": response["content_type"],
        "bytes": len(body.encode("utf-8")),
        "error": response["error"],
        "json_valid": False,
        "json_type": None,
        "top_level_keys": [],
        "result_count": None,
        "sample_results": [],
        "body_start": body[:1800],
    }
    try:
        data = json.loads(body)
    except Exception:
        return result

    result["json_valid"] = True
    result["json_type"] = type(data).__name__
    rows = []
    if isinstance(data, list):
        rows = data
        result["top_level_keys"] = []
    elif isinstance(data, dict):
        result["top_level_keys"] = list(data.keys())[:80]
        for key in ("results", "data", "items", "movies", "series", "list", "content"):
            if isinstance(data.get(key), list):
                rows = data[key]
                result["result_array_key"] = key
                break
        if not rows:
            rows = [data]
    result["result_count"] = len(rows)
    selected = []
    allowed = {
        "id", "tmdbId", "tmdb_id", "media_type", "type", "title", "name",
        "slug", "url", "href", "path", "route", "poster_path", "posterPath",
        "poster", "posterUrl", "image", "imageUrl", "backdrop_path",
        "release_date", "first_air_date", "vote_average", "overview",
        "season_number", "episode_number", "episodes", "seasons",
    }
    for row in rows[:5]:
        if isinstance(row, dict):
            selected.append({k: row[k] for k in row.keys() if k in allowed})
        else:
            selected.append(row)
    result["sample_results"] = selected
    return result


def inspect_js_bundle(url):
    page = fetch(url)
    body = page["body"]
    patterns = [
        ("tmdb_image_base", re.compile(r"(?i)image\.tmdb\.org/t/p")),
        ("poster_path", re.compile(r"(?i)poster[_-]?path")),
        ("poster_field", re.compile(r"(?i)poster(?:Url|URL|Image|Path)?")),
        ("api_route", re.compile(r"""(?i)["']/(?:api|v1|v2|graphql|trpc|search|movies|movie|series|dizi|film|episodes|episode|season)(?:/|["'?])""")),
        ("fetch_call", re.compile(r"(?i)\bfetch\s*\(")),
        ("axios", re.compile(r"(?i)\baxios\b")),
        ("base_url", re.compile(r"(?i)\b(?:baseURL|BASE_URL|VITE_[A-Z0-9_]+|API_URL|API_BASE_URL)\b")),
        ("tmdb", re.compile(r"(?i)\btmdb\b")),
        ("graphql", re.compile(r"(?i)\bgraphql\b")),
        ("supabase", re.compile(r"(?i)\bsupabase\b")),
        ("firebase", re.compile(r"(?i)\bfirebase\b")),
        ("search", re.compile(r"(?i)\bsearch(?:Movies|Series|Films|Dizi)?\b")),
        ("episode_data", re.compile(r"(?i)\b(?:episodes|episodeList|seasonEpisodes|season_number|episode_number)\b")),
        ("movie_data", re.compile(r"(?i)\b(?:movies|movieList|seriesList|posterUrl|poster_path|backdrop_path)\b")),
    ]
    indicators = []
    for label, pattern in patterns:
        taken = 0
        seen_context = set()
        for match in pattern.finditer(body):
            start = max(0, match.start() - 160)
            end = min(len(body), match.end() + 260)
            context = re.sub(r"\s+", " ", body[start:end]).strip()
            if context in seen_context:
                continue
            seen_context.add(context)
            indicators.append({"type": label, "match": match.group(0)[:120], "context": context[:520]})
            taken += 1
            if taken >= 8:
                break
    endpoint_strings = list(dict.fromkeys(
        re.findall(r"""(?i)(?:https?:)?//[A-Za-z0-9._-]+(?:/[A-Za-z0-9._~%/?#=&+-]*)?""", body)
        + re.findall(r"""["'](\/(?:api|v1|v2|graphql|trpc|search|movies|movie|series|dizi|film|episodes|episode|season)[A-Za-z0-9._~%/?#=&+-]*)["']""", body)
    ))
    source_contexts = []
    for needle in (
        "/api/tmdb", "/api/img", "/api/movies/search", "/api/library/tmdb-ids",
        "/search/multi", "/search/movie", "/search/tv", "/film/", "/dizi/",
        "const ZK=", "poster_path", "function Fo", "Fo("
    ):
        count = 0
        seen = set()
        for match in re.finditer(re.escape(needle), body, re.IGNORECASE):
            start = max(0, match.start() - 320)
            end = min(len(body), match.end() + 480)
            snippet = re.sub(r"\s+", " ", body[start:end]).strip()
            if snippet in seen:
                continue
            seen.add(snippet)
            source_contexts.append({"needle": needle, "context": snippet[:900]})
            count += 1
            if count >= 4:
                break
    return {
        "url": url,
        "status": page["status"],
        "content_type": page["content_type"],
        "bytes": len(body.encode("utf-8")),
        "error": page["error"],
        "tmdb_urls": list(dict.fromkeys(TMDB_RE.findall(body)))[:100],
        "poster_paths": list(dict.fromkeys(POSTER_PATH_RE.findall(body)))[:100],
        "endpoint_strings": endpoint_strings[:100],
        "indicators": indicators[:100],
        "source_contexts": source_contexts[:80],
        "first_1000_chars": body[:1000],
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    pages = [analyze_page(fetch(urljoin(BASE, path))) for path in PAGES]
    api_reports = [summarize_api_endpoint(path) for path in API_PROBE_PATHS]
    script_urls = list(dict.fromkeys(
        script_url
        for page in pages
        for script_url in page.get("script_urls", [])
        if urlparse(script_url).netloc.lower() == "dizisol.com"
        and "/assets/" in urlparse(script_url).path
    ))
    js_reports = []
    for script_url in script_urls[:8]:
        js_reports.append(inspect_js_bundle(script_url))

    payload = {
        "diagnostic_only": True,
        "base_url": BASE,
        "pages": pages,
        "api_endpoint_probes": api_reports,
        "javascript_asset_count": len(script_urls),
        "javascript_assets_inspected": js_reports,
    }
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

    summary = [{
        "url": p["requested_url"],
        "status": p["http_status"],
        "bytes": p["html_bytes"],
        "tmdb_urls": p["tmdb_url_count"],
        "poster_paths": p["poster_path_count"],
        "img_elements": p["image_element_count"],
        "content_anchors": p["content_anchor_count"],
        "scripts": len(p.get("script_urls", [])),
        "error": p["error"],
    } for p in pages]
    print(json.dumps({
        "pages": summary,
        "api_endpoint_probes": [
            {
                "path": item["path"], "status": item["status"], "bytes": item["bytes"],
                "json_valid": item["json_valid"], "json_type": item["json_type"],
                "top_level_keys": item["top_level_keys"][:20],
                "result_count": item["result_count"],
                "result_array_key": item.get("result_array_key"),
            }
            for item in api_reports
        ],
        "javascript_asset_count": len(script_urls),
        "javascript_assets": [
            {
                "url": item["url"],
                "status": item["status"],
                "bytes": item["bytes"],
                "tmdb_urls": len(item["tmdb_urls"]),
                "poster_paths": len(item["poster_paths"]),
                "endpoints": item["endpoint_strings"][:20],
            }
            for item in js_reports
        ],
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
