#!/usr/bin/env python3

import argparse
import json
import sys
import zipfile
from pathlib import Path


def inspect(path: Path):
    result = {
        "file": path.name,
        "bytes": path.stat().st_size,
        "valid_zip": False,
        "manifest": False,
        "dex": False,
        "status": "fail",
        "detail": "",
    }

    try:
        with zipfile.ZipFile(path) as zf:
            names = set(zf.namelist())
            result["valid_zip"] = zf.testzip() is None
            result["manifest"] = "manifest.json" in names
            result["dex"] = any(name.endswith(".dex") for name in names)

            if result["valid_zip"] and result["manifest"] and result["dex"]:
                result["status"] = "ok"
                result["detail"] = "valid CloudStream plugin container"
            else:
                result["detail"] = "missing manifest or dex, or invalid zip"

    except (OSError, zipfile.BadZipFile) as exc:
        result["detail"] = str(exc)

    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    directory = Path(args.directory)
    files = sorted(directory.glob("*.cs3"))
    results = [inspect(path) for path in files]

    payload = {
        "files": len(results),
        "ok": len([x for x in results if x["status"] == "ok"]),
        "failed": len([x for x in results if x["status"] != "ok"]),
        "results": results,
    }

    Path(args.output).write_text(
        json.dumps(payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 1 if payload["failed"] else 0


if __name__ == "__main__":
    sys.exit(main())
