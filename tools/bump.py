#!/usr/bin/env python3
"""Bump the app version in version.js.  Usage: python tools/bump.py [patch|minor|major|X.Y.Z]

Changing version.js changes the service worker's bytes and cache name, so installed apps
fetch the new files, drop the old cache and reload.  (CI also stamps APP_BUILD per deploy.)"""
import re
import sys
from pathlib import Path

f = Path(__file__).resolve().parent.parent / "version.js"
src = f.read_text()
cur = re.search(r'APP_VERSION = "(\d+)\.(\d+)\.(\d+)"', src)
major, minor, patch = map(int, cur.groups())
arg = sys.argv[1] if len(sys.argv) > 1 else "patch"
if re.fullmatch(r"\d+\.\d+\.\d+", arg):
    new = arg
else:
    new = {"major": f"{major + 1}.0.0", "minor": f"{major}.{minor + 1}.0", "patch": f"{major}.{minor}.{patch + 1}"}[arg]
f.write_text(re.sub(r'APP_VERSION = "[^"]+"', f'APP_VERSION = "{new}"', src))
print(f"{major}.{minor}.{patch} -> {new}")
