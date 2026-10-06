#!/usr/bin/env python3
import argparse
import json
import os
import re
import sys
import urllib.request
from pathlib import Path

FILE_RE = re.compile(r"file:///.*?/src/([^:\\n]+\\.kt):(\\d+)")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", required=True)
    parser.add_argument("--build-log", required=True)
    parser.add_argument("--build-error", required=True)
    parser.add_argument("--health-log", required=True)
    parser.add_argument("--attempt", required=True)
    args = parser.parse_args()
    root = Path(args.repo_root).resolve()
