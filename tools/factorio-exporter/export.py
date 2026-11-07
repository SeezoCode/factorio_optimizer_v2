#!/usr/bin/env python3
"""Exports game data for factorio_optimizer from a local Factorio install, with any mods.

    python3 tools/factorio-exporter/export.py data/krastorio2.json --mods Krastorio2
    python3 tools/factorio-exporter/export.py src/main/resources/recipes.json          # base game + Space Age

The mods are taken from your Factorio mods folder (install them in game first; they may stay disabled there). They
and their required dependencies are enabled in a mod folder of their own next to this script's output, together with
the exporter mod in this folder, so your own mod list is never changed. A new game is created with them and loaded;
the exporter writes its file a moment later and the game is closed. It has a data folder of its own, so it runs
beside a game you have open and leaves your saves, logs and settings alone.

The game binary is started directly (with Steam's app id set) rather than through Steam, which would ask to confirm a
launch with arguments. Set FACTORIO to the binary and FACTORIO_USER_DIR to the user data folder if they are not where
Steam on macOS puts them.
"""
import argparse
import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
BINARY = os.environ.get("FACTORIO", os.path.expanduser(
    "~/Library/Application Support/Steam/steamapps/common/Factorio/factorio.app/Contents/MacOS/factorio"))
USER_DIR = os.environ.get("FACTORIO_USER_DIR", os.path.expanduser("~/Library/Application Support/factorio"))
BUILT_IN = ["base", "elevated-rails", "quality", "recycler", "space-age"]
OUTPUT = "factorio-optimizer-data.json"


def mod_info(path):
    """info.json of a mod zip or folder."""
    if os.path.isdir(path):
        with open(os.path.join(path, "info.json")) as f:
            return json.load(f)
    with zipfile.ZipFile(path) as z:
        name = next(n for n in z.namelist() if n.count("/") == 1 and n.endswith("/info.json"))
        return json.loads(z.read(name))


def installed_mods():
    """Name -> (version tuple, path) of the newest copy of each mod in the user's mods folder."""
    found = {}
    folder = os.path.join(USER_DIR, "mods")
    for entry in os.listdir(folder):
        path = os.path.join(folder, entry)
        if not (entry.endswith(".zip") or os.path.isdir(path)):
            continue
        try:
            info = mod_info(path)
        except Exception:
            continue
        version = tuple(int(x) for x in info["version"].split("."))
        if info["name"] not in found or found[info["name"]][0] < version:
            found[info["name"]] = (version, path, info)
    return found


def required(dep):
    """The mod name of a required dependency, or None: "? x", "(?) x" are optional, "! x" incompatible; "~ x" and
    "+ x" (2.1) are required."""
    dep = dep.strip()
    if dep[:1] in "?!(":
        return None
    if dep[:1] in "~+":
        dep = dep[1:].strip()
    return dep.split()[0]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("out", help="where to write the data (a .json file)")
    ap.add_argument("--mods", default="", help="comma-separated mod names to enable (with their dependencies)")
    ap.add_argument("--no-space-age", action="store_true", help="base game only (no Space Age, quality, recycler, elevated rails)")
    ap.add_argument("--timeout", type=int, default=300, help="seconds to wait for the export")
    args = ap.parse_args()

    have = installed_mods()
    wanted = [m for m in args.mods.split(",") if m]
    enabled = ["base"] if args.no_space_age else list(BUILT_IN)
    todo = list(wanted)
    while todo:
        m = todo.pop()
        if m in enabled or m == "core":
            continue
        if m in BUILT_IN:
            enabled.append(m)
            continue
        if m not in have:
            sys.exit(f"mod '{m}' is not in {USER_DIR}/mods: install it in game first (Mods -> Install)")
        enabled.append(m)
        todo += [d for d in (required(x) for x in have[m][2].get("dependencies", [])) if d]

    work = tempfile.mkdtemp(prefix="factorio-export-")
    mods_dir = os.path.join(work, "mods")
    os.makedirs(mods_dir)
    for m in enabled:
        if m not in BUILT_IN:
            src = have[m][1]
            shutil.copytree(src, os.path.join(mods_dir, os.path.basename(src))) if os.path.isdir(src) else shutil.copy(src, mods_dir)
    exporter_info = mod_info(HERE)
    shutil.copytree(HERE, os.path.join(mods_dir, f"{exporter_info['name']}_{exporter_info['version']}"),
                    ignore=shutil.ignore_patterns("*.py", "__pycache__"))
    mod_list = [{"name": m, "enabled": m in enabled} for m in BUILT_IN] + \
               [{"name": m, "enabled": True} for m in enabled if m not in BUILT_IN] + \
               [{"name": exporter_info["name"], "enabled": True}]
    with open(os.path.join(mods_dir, "mod-list.json"), "w") as f:
        json.dump({"mods": mod_list}, f, indent=1)
    print("mods:", ", ".join(m for m in enabled))

    # A data folder of its own (config.ini's write-data): runs beside an open game, and leaves its logs and saves alone.
    user = os.path.join(work, "user")
    os.makedirs(user)
    ini = os.path.join(work, "config.ini")
    with open(ini, "w") as f:
        f.write(f"[path]\nread-data=__PATH__system-read-data__\nwrite-data={user}\n")
    base = [BINARY, "--config", ini, "--mod-directory", mods_dir]
    save = os.path.join(work, "export.zip")
    log = os.path.join(work, "factorio.log")
    created = subprocess.run(base + ["--create", save], capture_output=True, text=True)
    if created.returncode != 0 or not os.path.exists(save):
        sys.exit("creating the game failed:\n" + created.stdout[-3000:])

    result = os.path.join(user, "script-output", OUTPUT)
    env = dict(os.environ, SteamAppId="427520", SteamGameId="427520")
    with open(log, "w") as lf:
        game = subprocess.Popen(base + ["--load-game", save], stdout=lf, stderr=subprocess.STDOUT, env=env)
    try:
        deadline = time.time() + args.timeout
        while time.time() < deadline:
            if os.path.exists(result) and os.path.getsize(result) > 0:
                time.sleep(1)
                break
            if game.poll() is not None:
                break
            with open(log) as lf:
                text = lf.read()
            if "Exception at tick" in text or "non-recoverable error" in text:
                break
            time.sleep(2)
    finally:
        if game.poll() is None:
            game.send_signal(signal.SIGTERM)
            try:
                game.wait(20)
            except subprocess.TimeoutExpired:
                game.kill()
    if not (os.path.exists(result) and os.path.getsize(result) > 0):
        with open(log) as lf:
            tail = lf.read()[-3000:]
        sys.exit(f"no export written (log in {log}):\n{tail}")
    os.makedirs(os.path.dirname(os.path.abspath(args.out)) or ".", exist_ok=True)
    with open(result) as f:
        d = json.load(f)
    # Indented, two spaces, as recipes.json has always been: diffs between game versions stay readable.
    with open(args.out, "w") as f:
        json.dump(d, f, indent=2, ensure_ascii=False)
        f.write("\n")
    machines = sum(1 for e in d["entities"].values() if e.get("crafting_categories"))
    print(f"wrote {args.out}: {len(d['recipes'])} recipes, {len(d['items'])} items, {machines} crafting machines, "
          f"{len(d['logistics']['belts'])} belts, {len(d['logistics']['inserters'])} inserters (mods: {', '.join(sorted(d['mods']))})")
    shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
