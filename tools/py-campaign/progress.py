#!/usr/bin/env python3
"""Writes the campaign's results table into readme.md and docs/PY_SCIENCE_PROGRESS.md (between the markers
<!-- campaign:start --> and <!-- campaign:end -->), from the runs in solves/ (as builds_page.py reads them).

    python3 tools/py-campaign/progress.py
"""
import importlib.util, json, os, re, time

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("bp", os.path.join(HERE, "builds_page.py"))
bp = importlib.util.module_from_spec(spec); spec.loader.exec_module(bp)
ROOT = bp.ROOT
NAMES = {"L5": "fed", "L4": "ideal", "L3s": "rules+starters", "L2s": "mod+starters", "L1s": "built+starters", "L3": "rules", "L2": "mod", "L1": "built", "L0": "plain"}
# Sciences without a layout yet: why (the campaign's state, CAMPAIGN_CONTEXT.md).
NOT_LAID = {"production-science-pack": "24 blocks laid out; stitch did not converge (about 24 h of fine routing)",
            "py-science-pack-4": "51 blocks laid out; stitch not run (after production)",
            "utility-science-pack": "stopped: too big for this machine on belts",
            "space-science-pack": "stopped: too big for this machine on belts"}
PAGE = "https://claude.ai/artifact/7rqXsL9UZFaMF8m7fdcyEk"


def cell(r):
    if not r:
        return "·"
    if r["verdict"] == "pass":
        return f"**pass** {r['hours']:g} h"
    if r["verdict"] == "running":
        return f"running, {r['hours']:g} h"
    return f"stopped{', ' + str(round(r['pct'])) + '%' if r['pct'] is not None else ''}"


def best(runs):
    """The fairest level a science passed at (L0 fairest), or None."""
    for l in reversed(bp.LEVELS):
        if runs.get(l, {}).get("verdict") == "pass":
            return l
    return None


def table():
    data = json.load(open(os.path.join(ROOT, "data", "pyanodon.json")))
    rows = ["| Science | Machines | Fairest pass | " + " | ".join(f"{l} {NAMES[l]}" for l in bp.LEVELS) + " |",
            "|---|---:|---|" + "---|" * len(bp.LEVELS)]
    rank = {"pass": 2, "running": 1, "fail": 0}
    for s in bp.SCI:
        ds = bp.layout_dirs(s)
        name = data["items"].get(s, {}).get("translated_name", s)
        if not ds:
            rows.append(f"| {name} | {NOT_LAID.get(s, 'solving')} | | " + " | ".join("·" for _ in bp.LEVELS) + " |")
            continue
        runs = {}
        for d in ds:
            for l, r in bp.runs_for(d).items():
                if l not in runs or (rank[r["verdict"]], r["hours"]) > (rank[runs[l]["verdict"]], runs[l]["hours"]):
                    runs[l] = r
        m = " / ".join(str(len(json.load(open(os.path.join(ROOT, "solves", d, "plan.json")))["machines"])) for d in ds)
        b = best(runs)
        rows.append(f"| {name} | {m} | {('**' + b + ' ' + NAMES[b] + '**') if b else '—'} | " + " | ".join(cell(runs.get(l)) for l in bp.LEVELS) + " |")
    stamp = time.strftime("%Y-%m-%d %H:%M UTC", time.gmtime())
    return "\n".join(rows) + f"\n\nUpdated {stamp}. Hours are game hours (warm-up included); a pass is the in-game check's verdict over the level's whole run. Builds drawn tile for tile: [the builds page]({PAGE}) (private link)."


def put(path, text):
    s = open(path).read()
    new = re.sub(r"<!-- campaign:start -->.*?<!-- campaign:end -->", "<!-- campaign:start -->\n" + text + "\n<!-- campaign:end -->", s, flags=re.S)
    if new != s:
        open(path, "w").write(new)


if __name__ == "__main__":
    t = table()
    put(os.path.join(ROOT, "readme.md"), t)
    put(os.path.join(ROOT, "docs", "PY_SCIENCE_PROGRESS.md"), t)
    print(t)
