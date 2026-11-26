#!/usr/bin/env python3
"""Writes the campaign's builds page: tools/py-campaign/builds_page.html plus one data file per science.

    python3 tools/py-campaign/builds_page.py <out dir>

For each of the 11 sciences: the best solved layout (solves/<science>_<suffix>, newest suffix with a layout wins
unless BUILDS_SUFFIX is set), drawn from its blueprint (entities as compact rows), its blueprint string, and the
campaign's runs on it by help level (verdict, hours, pack crafts). Publish <out dir> as an artifact
(index.html + data/*.json + status.json).
"""
import json, os, re, shutil, sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SCI = ["automation-science-pack", "py-science-pack-1", "logistic-science-pack", "military-science-pack",
       "py-science-pack-2", "chemical-science-pack", "py-science-pack-3", "production-science-pack",
       "py-science-pack-4", "utility-science-pack", "space-science-pack"]
LEVELS = ["L5", "L4", "L3", "L2", "L1", "L0"]


def category(name, ent):
    if "splitter" in name: return 3
    if "underground-belt" in name: return 2
    if "transport-belt" in name or name.endswith("-belt") or "loader" in name: return 1
    if "inserter" in name: return 4
    if "pipe" in name or name in ("pump", "offshore-pump") or "valve" in name or "tank" in name: return 5
    if "pole" in name or name == "substation" or "substation" in name: return 6
    if "combinator" in name or "chest" in name or "roboport" in name or "lamp" in name: return 7
    if ent and (ent.get("crafting_categories") or ent.get("type") in ("furnace", "assembling-machine", "rocket-silo", "mining-drill", "lab")):
        return 8
    return 9


def layout_dir(s):
    want = os.environ.get("BUILDS_SUFFIX")
    cands = [d for d in os.listdir(os.path.join(ROOT, "solves")) if d.startswith(s + "_")
             and os.path.exists(os.path.join(ROOT, "solves", d, "best_blueprint.json"))]
    if want:
        cands = [d for d in cands if d == f"{s}_{want}"]
    cands.sort(key=lambda d: os.path.getmtime(os.path.join(ROOT, "solves", d, "best_blueprint.json")))
    return cands[-1] if cands else None


def runs_for(layout):
    out = {}
    for d in sorted(os.listdir(os.path.join(ROOT, "solves"))):
        p = os.path.join(ROOT, "solves", d)
        if not os.path.exists(os.path.join(p, "cmd.txt")) or not os.path.exists(os.path.join(p, "level.txt")):
            continue
        if open(os.path.join(p, "cmd.txt")).read().split()[0] != layout:
            continue
        lvl = open(os.path.join(p, "level.txt")).read().strip()
        txt = open(os.path.join(p, "verify.out")).read() if os.path.exists(os.path.join(p, "verify.out")) else ""
        verdict = "pass" if re.search(r"^PASS", txt, re.M) else "fail" if re.search(r"^FAIL", txt, re.M) else "running"
        prog = re.findall(r"(warmup|measure): (\d+) of (\d+) game seconds", txt)
        hours = 0.0
        if prog:
            hours = int(prog[-1][1]) / 3600  # game seconds since the start, warm-up included
        rate = re.search(r"total " + re.escape(layout.rsplit("_", 1)[0]) + r"\s+([\d.]+)\s+planned\s+([\d.]+)\s+([\d.]+)%", txt)
        r = {"run": d, "verdict": verdict, "hours": round(hours, 1), "pct": float(rate.group(3)) if rate else None}
        prev = out.get(lvl)
        # a level's shown run: a pass beats a running run beats a fail; within those, the longest
        rank = {"pass": 2, "running": 1, "fail": 0}
        if prev is None or (rank[r["verdict"]], r["hours"]) > (rank[prev["verdict"]], prev["hours"]):
            out[lvl] = r
    return out


def main():
    dst = sys.argv[1]
    os.makedirs(os.path.join(dst, "data"), exist_ok=True)
    data = json.load(open(os.path.join(ROOT, "data", "pyanodon.json")))
    ents = data["entities"]
    status = []
    for s in SCI:
        d = layout_dir(s)
        row = {"science": s, "name": data["items"].get(s, {}).get("translated_name", s), "layout": d, "runs": {}}
        if d:
            p = os.path.join(ROOT, "solves", d)
            bp = json.load(open(os.path.join(p, "best_blueprint.json")))["blueprint"]
            names, recipes, rows = [], [], []
            for e in bp["entities"]:
                n = e["name"]
                ent = ents.get(n, {})
                w, h = ent.get("width", 1), ent.get("height", 1)
                dr = e.get("direction", 0)
                if dr in (4, 12):
                    w, h = h, w
                if n not in names: names.append(n)
                rc = e.get("recipe", "")
                if rc and rc not in recipes: recipes.append(rc)
                x, y = e["position"]["x"] - w / 2, e["position"]["y"] - h / 2
                rows.append([category(n, ent), round(x, 1), round(y, 1), w, h, dr // 4, names.index(n), recipes.index(rc) if rc else -1])
            plan = json.load(open(os.path.join(p, "plan.json")))
            bstr = open(os.path.join(p, "best_output_string.txt")).read().strip()
            xs = [r[1] for r in rows] + [r[1] + r[3] for r in rows]
            ys = [r[2] for r in rows] + [r[2] + r[4] for r in rows]
            json.dump({"names": names, "recipes": recipes, "e": rows, "bp": bstr}, open(os.path.join(dst, "data", s + ".json"), "w"), separators=(",", ":"))
            row.update(machines=len(plan["machines"]), entities=len(rows), w=round(max(xs) - min(xs)), h=round(max(ys) - min(ys)),
                       margin=plan.get("margin"), runs=runs_for(d))
        status.append(row)
    json.dump({"levels": LEVELS, "sciences": status}, open(os.path.join(dst, "status.json"), "w"), indent=1)
    shutil.copy(os.path.join(os.path.dirname(__file__), "builds_page.html"), os.path.join(dst, "index.html"))
    print("wrote", dst, [r["science"] for r in status if r["layout"]])


if __name__ == "__main__":
    main()
