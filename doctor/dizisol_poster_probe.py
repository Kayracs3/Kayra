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
            body = response.read(1_500_000).decode("utf-8", "replace")
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
            if not raw or not IMAGE_ATTR_RE.search(key):
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
    all_image_nodes = [
        n for n in all_nodes
        if n.tag in {"img", "source"} or any(
            IMAGE_ATTR_RE.search(key) for key in n.attrs
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
        "html_start": body[:1400],
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    pages = []
    for path in PAGES:
        pages.append(analyze_page(fetch(urljoin(BASE, path))))

    payload = {
        "diagnostic_only": True,
        "base_url": BASE,
        "pages": pages,
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
        "error": p["error"],
    } for p in pages]
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
