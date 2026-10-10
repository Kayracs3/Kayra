#!/usr/bin/env python3
"""Generate the Kayra plugin catalog/badges and append commit-based update notes."""

import argparse
import re
import subprocess
from pathlib import Path
from urllib.parse import quote

MAIN_URL_RE = re.compile(r'\bmainUrl\b[^=\n]*=\s*["\']([^"\']+)["\']')
CAPABILITIES = [
    ("Search", re.compile(r'\boverride\s+(?:suspend\s+)?fun\s+(?:search|quickSearch)\s*\(')),
    ("Catalog", re.compile(r'hasMainPage\s*=\s*true|\boverride\s+suspend\s+fun\s+getMainPage\s*\(')),
    ("Poster", re.compile(r'\bposterUrl\s*=')),
    ("IMDb", re.compile(r'Score\.from10|response\.score|\bscore\s*=')),
    ("Actors", re.compile(r'ActorData|response\.actors|\bactors\s*=')),
    ("Trailer", re.compile(r'TrailerData|response\.trailers|trailers\s*\+=')),
    ("Episodes", re.compile(r'newEpisode|newTvSeriesLoadResponse|TvType\.TvSeries')),
    ("Subtitles", re.compile(r'newSubtitleFile|SubtitleFile')),
    ("Playback", re.compile(r'\boverride\s+suspend\s+fun\s+loadLinks\s*\(|newExtractorLink')),
]


def git(*args):
    return subprocess.run(
        ["git", *args], check=False, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, text=True, encoding="utf-8", errors="replace"
    )


def discover_modules(root: Path):
    discovered = []
    for build_file in sorted(root.glob("*/build.gradle.kts")):
        module = build_file.parent
        kotlin_files = sorted((module / "src/main/kotlin").rglob("*.kt"))
        candidates = []
        for path in kotlin_files:
            try:
                source = path.read_text(encoding="utf-8", errors="ignore")
            except OSError:
                continue
            main_url = MAIN_URL_RE.search(source)
            if main_url:
                is_provider = bool(re.search(r':\s*MainAPI\s*\(', source))
                candidates.append((not is_provider, path, source, main_url.group(1).strip()))
        if not candidates:
            continue
        _, source_path, source, main_url = sorted(
            candidates, key=lambda item: (item[0], str(item[1]))
        )[0]
        capabilities = [label for label, pattern in CAPABILITIES if pattern.search(source)]
        discovered.append({
            "name": module.name,
            "path": module.name,
            "source": source_path.relative_to(root).as_posix(),
            "url": main_url.rstrip("/"),
            "capabilities": capabilities,
        })
    return discovered


def badge(label: str) -> str:
    slug = quote(label, safe="")
    return f'![{label}](https://img.shields.io/badge/{slug}-coded-1f6feb?style=flat-square)'


def render_readme(root: Path) -> str:
    modules = discover_modules(root)
    build_badge = (
        "![Build](https://img.shields.io/github/actions/workflow/status/"
        "Kayracs3/Kayra/Derleyici.yml?label=Build&style=flat-square)"
    )
    doctor_badge = (
        "![Kayra Doctor](https://img.shields.io/github/actions/workflow/status/"
        "Kayracs3/Kayra/KayraDoctor.yml?label=Kayra%20Doctor&style=flat-square)"
    )
    domain_badge = "![Domain check](https://img.shields.io/badge/domain-monitoring-enabled-1f6feb?style=flat-square)"
    lines = [
        "# Kayra — CloudStream Eklentileri",
        "",
        f"{build_badge} {doctor_badge} {domain_badge}",
        "",
        "Kayra, CloudStream için film/dizi ve medya sağlayıcı eklentilerini içerir.",
        "",
        "## Eklenti listesi ve yetenek rozetleri",
        "",
        "Rozetler ilgili sağlayıcının ana Kotlin dosyasında özellik kodu algılandığını gösterir. "
        "Tek başına canlı sitede çalıştığını veya video oynatmanın doğrulandığını garanti etmez. "
        "Canlı durum için Build, Kayra Doctor ve Domain Sağlık Kontrolü Actions sonuçlarına bakın.",
        "",
        "| Eklenti | Ana domain | Kodda algılanan yetenekler |",
        "|---|---|---|",
    ]
    for item in modules:
        cap_badges = " ".join(badge(label) for label in item["capabilities"])
        domain = item["url"].replace("|", "%7C")
        lines.append(
            f'| [{item["name"]}]({item["path"]}) | [{domain}]({item["url"]}) | '
            f'{cap_badges or "—"} |'
        )
    lines.extend([
        "",
        "## Sistemler",
        "",
        "- Sağlık kontrolü: Kontrol.yml ve KayraDoctor.yml; build/CS3 konteyneri, site erişimi ve Android plugin yükleme kontrolleri.",
        "- Domain yönetimi: doctor/domains.json. Kotlin mainUrl birincil kaynaktır; yalnızca approvedAliases listesinde gözden geçirilmiş domainler kontrol edilir. Otomatik domain değişimi yapılmaz.",
        "- Güncelleme notları: CHANGELOG.md, ana dala gelen commitlerden otomatik üretilir.",
        "- CI: [Derleyici](https://github.com/Kayracs3/Kayra/actions/workflows/Derleyici.yml) · [Kayra Doctor](https://github.com/Kayracs3/Kayra/actions/workflows/KayraDoctor.yml) · [Domain Sağlık Kontrolü](https://github.com/Kayracs3/Kayra/actions/workflows/Kontrol.yml)",
        "",
        "## Domain ekleme / değiştirme",
        "",
        "Yeni domain doğrulanınca doctor/domains.json içindeki sağlayıcıya approvedAliases listesi ve açıklama ekleyin. Rapor eski birincil domain ile onaylı alternatifi ayrı kontrol eder. Eklentinin gerçekten yeni domaini kullanması için Kotlin mainUrl, yönlendirmeler ve site seçicileri ayrıca incelenmelidir.",
        "",
        "<!-- This file is generated by doctor/repo_catalog.py. Manual edits are overwritten. -->",
        "",
    ])
    return "\n".join(lines)


def changed_paths(commit_sha: str):
    result = git("diff-tree", "--root", "--no-commit-id", "--name-only", "-r", commit_sha)
    return [line.strip() for line in result.stdout.splitlines() if line.strip()]


def changed_areas(paths, root: Path):
    areas = set()
    for path in paths:
        first = path.split("/", 1)[0]
        if first not in {".github", "doctor"} and (root / first / "build.gradle.kts").exists():
            areas.add(first)
        elif path.endswith((".kt", ".kts")) and first not in {".github", "doctor"}:
            areas.add(first)
        elif first in {".github", "doctor"} or path in {"build.gradle.kts", "settings.gradle.kts", "gradle.properties"}:
            areas.add("Repo / CI")
    return sorted(areas, key=str.lower) or ["Repo / CI"]


def parse_log_lines(output: str):
    commits = []
    for line in output.splitlines():
        parts = line.split("\x1f", 2)
        if len(parts) == 3:
            commits.append((parts[0], parts[1].strip(), parts[2][:10]))
    return commits


def get_commits(before: str, after: str, known: set[str]):
    # Workflow runs can cancel one another when a burst of commits arrives.
    # Re-scan from the earliest changelog marker so cancelled runs never lose notes.
    if known:
        history = git("log", "--reverse", "--format=%H%x1f%s%x1f%aI")
        all_commits = parse_log_lines(history.stdout)
        first_known = next(
            (index for index, item in enumerate(all_commits) if item[0] in known),
            None,
        )
        if first_known is not None:
            return all_commits[first_known + 1:]

    if after and before and re.fullmatch(r"[0-9a-fA-F]{40}", before) and not re.fullmatch(r"0+", before):
        result = git("log", "--reverse", "--format=%H%x1f%s%x1f%aI", f"{before}..{after}")
    elif after:
        result = git("log", "-20", "--reverse", "--format=%H%x1f%s%x1f%aI", after)
    else:
        result = git("log", "-20", "--reverse", "--format=%H%x1f%s%x1f%aI")
    return parse_log_lines(result.stdout)


def update_changelog(root: Path, path: Path, before: str, after: str):
    original = path.read_text(encoding="utf-8") if path.exists() else (
        "# Kayra Güncelleme Notları\n\n"
        "> Bu dosya ana dala gelen commit mesajlarından otomatik oluşturulur.\n\n"
    )
    known = set(re.findall(r'<!-- kayra-commit:([0-9a-f]{40}) -->', original))
    fresh = []
    for sha, subject, date in get_commits(before, after, known):
        if sha in known:
            continue
        paths = changed_paths(sha)
        if paths and all(item in {"README.md", "CHANGELOG.md"} for item in paths):
            continue
        areas = changed_areas(paths, root)
        clean_subject = subject.replace("|", "¦").strip() or "Güncelleme"
        fresh.append(
            f"## {date}\n\n"
            f"<!-- kayra-commit:{sha} -->\n"
            f"### {clean_subject}\n\n"
            f"- Etkilenen alanlar: {', '.join(areas)}\n"
            f"- Commit: [{sha[:7]}](https://github.com/Kayracs3/Kayra/commit/{sha})\n"
        )
    if not fresh:
        return original
    header_end = original.find("\n\n")
    if header_end < 0:
        header = "# Kayra Güncelleme Notları\n\n> Bu dosya otomatik oluşturulur.\n\n"
        rest = ""
    else:
        header = original[:header_end + 2]
        rest = original[header_end + 2:].lstrip()
    new_body = "\n".join(reversed(fresh))
    return header + new_body + ("\n\n" + rest if rest else "\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=".")
    parser.add_argument("--readme", default="README.md")
    parser.add_argument("--changelog", default="CHANGELOG.md")
    parser.add_argument("--before", default="")
    parser.add_argument("--after", default="")
    args = parser.parse_args()
    root = Path(args.root).resolve()
    (root / args.readme).write_text(render_readme(root), encoding="utf-8")
    changelog_path = root / args.changelog
    changelog_path.write_text(
        update_changelog(root, changelog_path, args.before, args.after),
        encoding="utf-8",
    )
    print(f"Plugin catalog: {len(discover_modules(root))} providers written to {root / args.readme}")
    print(f"Update notes written to {changelog_path}")


if __name__ == "__main__":
    main()
