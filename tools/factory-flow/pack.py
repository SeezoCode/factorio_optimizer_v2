#!/usr/bin/env python3
"""Packs the factory-flow mod for the game: build/factory-flow_<version>.zip, to put in Factorio's mods folder.

    python3 tools/factory-flow/pack.py
"""
import json
import os
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
info = json.load(open(os.path.join(HERE, "info.json")))
top = f"{info['name']}_{info['version']}"
os.makedirs(os.path.join(REPO, "build"), exist_ok=True)
path = os.path.join(REPO, "build", top + ".zip")
with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk(HERE):
        for f in sorted(files):
            if f.endswith((".py", ".pyc", ".md")) or "__pycache__" in root:
                continue
            full = os.path.join(root, f)
            z.write(full, os.path.join(top, os.path.relpath(full, HERE)))
print(path)
