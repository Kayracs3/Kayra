#!/usr/bin/env python3
"""Compile the deterministic CloudStream provider template without contacting any website or AI API."""
import argparse
import shutil
import subprocess
from pathlib import Path

from site_to_plugin import _provider_template, check


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", default=".")
    args = parser.parse_args()
    root = Path(args.repo_root).resolve()
    module_name = "_GeneratedTemplateSmoke"
    class_name = "GeneratedTemplateSmoke"
    origin = "https://example.com"
    module = root / module_name
    if module.exists():
        raise RuntimeError("Temporary template module already exists: " + str(module))

    source = check(_provider_template(class_name, "Generated Template Smoke", origin), class_name, origin)
    source_dir = module / "src/main/kotlin/com/Kayracs3"
    source_dir.mkdir(parents=True)
    (source_dir / (class_name + ".kt")).write_text(source.rstrip() + "\n", encoding="utf-8")
    plugin_source = (
        "package com.Kayracs3\n\n"
        "import com.lagradost.cloudstream3.plugins.CloudstreamPlugin\n"
        "import com.lagradost.cloudstream3.plugins.Plugin\n"
        "import android.content.Context\n\n"
        "@CloudstreamPlugin\n"
        "class " + class_name + "Plugin : Plugin() {\n"
        "    override fun load(context: Context) {\n"
        "        registerMainAPI(" + class_name + "())\n"
        "    }\n"
        "}\n"
    )
    (source_dir / (class_name + "Plugin.kt")).write_text(plugin_source, encoding="utf-8")
    (module / "build.gradle.kts").write_text(
        'version = 1\n\ncloudstream {\n'
        '    description = "Deterministic template compile check"\n'
        '    authors = listOf("Kayracs3")\n'
        '    status = 1\n'
        '    tvTypes = listOf("Movie", "TvSeries")\n'
        '    language = "tr"\n'
        '    iconUrl = "https://example.com/favicon.ico"\n'
        '}\n\nandroid {\n    buildFeatures {\n        buildConfig = true\n    }\n}\n',
        encoding="utf-8",
    )

    try:
        result = subprocess.run(
            ["bash", str(root / "gradlew"), ":" + module_name + ":compileDebugKotlin", "--no-daemon", "--stacktrace"],
            cwd=root,
            check=False,
        )
        if result.returncode:
            raise SystemExit(result.returncode)
    finally:
        shutil.rmtree(module, ignore_errors=True)

    print("Deterministic generated provider template compiled successfully; no AI or website request was used.")


if __name__ == "__main__":
    main()
