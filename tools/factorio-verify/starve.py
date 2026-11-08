#!/usr/bin/env python3
"""Why machines starve in game: for each machine short of an ingredient, the belts that bring it, traced upstream.

    python3 tools/factorio-verify/verify.py out --dump        # writes out/verify_report.json with belt/inserter dumps
    python3 tools/factorio-verify/starve.py out                # every starving machine
    python3 tools/factorio-verify/starve.py out --item rail    # only machines short of rail

For each machine whose usual status is an ingredient shortage (with --slow also each that crafted under --share of
the busiest machine of its recipe), it finds the ingredients it holds less than one craft of, then for each such item the belts its
inserters take it from and everything upstream of them (belts, undergrounds, splitters). It lists who puts the item
there (producer machines and their status: a producer standing with full output while a consumer downstream starves
means its output does not reach that consumer, or the belt is blocked between them) and who else takes it on the way
(consumers upstream draw first). It also says, for each producer of the item standing with full output anywhere in
the factory, whether its output can reach the starving machine at all.
"""
import argparse
import json
import math
import os
import sys
from collections import defaultdict

F = math.floor
D = {0: (0, -1), 4: (1, 0), 8: (0, 1), 12: (-1, 0)}
REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def load_recipes(path):
    d = json.load(open(path))
    out, makes = {}, {}
    for name, r in d["recipes"].items():
        out[name] = {i["name"]: i["amount"] for i in r.get("ingredients", []) if i.get("type", "item") == "item"}
        makes[name] = {p["name"] for p in r.get("products", []) if p.get("type", "item") == "item"}
    sizes = {n: (e.get("tile_width") or e.get("width") or 3) for n, e in d.get("entities", {}).items() if isinstance(e, dict)}
    # Underground belts' reach from the game data (mods change it: Pyanodon's express ones span 33 tiles).
    UG_REACH.update({u["name"]: u["max_distance"] for u in (d.get("logistics") or {}).get("undergrounds", []) if u.get("max_distance")})
    return out, makes, sizes


# Underground belt name -> tiles it spans at most (else the base game's: 10 express or turbo, 8 fast, 6 basic).
UG_REACH = {}


class Factory:
    def __init__(self, report, sizes):
        self.r = report
        self.cell = {}
        for b in report["dump_belts"]:
            if b["n"].endswith("splitter"):
                if b["d"] in (0, 8):
                    ts = [(F(b["x"] - .5), F(b["y"])), (F(b["x"] + .5), F(b["y"]))]
                else:
                    ts = [(F(b["x"]), F(b["y"] - .5)), (F(b["x"]), F(b["y"] + .5))]
            else:
                ts = [(F(b["x"]), F(b["y"]))]
            b["tiles"] = ts
            for t in ts:
                self.cell[t] = b
        self.mach = {}
        for m in report["machines"]:
            s = sizes.get(m["name"], 3)
            m["size"] = s
            for dx in range(s):
                for dy in range(s):
                    self.mach[(F(m["x"] - s / 2 + dx + .5), F(m["y"] - s / 2 + dy + .5))] = m
        self.pick = defaultdict(list)
        self.drop = defaultdict(list)
        for i in report["dump_inserters"]:
            self.pick[(F(i["px"]), F(i["py"]))].append(i)
            self.drop[(F(i["dx"]), F(i["dy"]))].append(i)
        self.prev = defaultdict(list)
        for b in report["dump_belts"]:
            for c in self.nxt(b):
                if b not in self.prev[id(c)]:
                    self.prev[id(c)].append(b)

    def nxt(self, b):
        d = D[b["d"]]
        if b["n"].endswith("underground-belt") and b.get("t") == "input":
            rng = UG_REACH.get(b["n"]) or (10 if "express" in b["n"] or "turbo" in b["n"] else 8 if "fast" in b["n"] else 6)
            x, y = b["tiles"][0]
            for k in range(1, rng + 2):
                c = self.cell.get((x + d[0] * k, y + d[1] * k))
                if c and c["n"] == b["n"] and c["d"] == b["d"]:
                    return [c] if c.get("t") == "output" else []
            return []
        out = []
        for (x, y) in b["tiles"]:
            c = self.cell.get((x + d[0], y + d[1]))
            if not c or c is b:
                continue
            if c["n"].endswith("underground-belt") and c.get("t") == "output" and c["d"] == b["d"]:
                continue  # its back is closed
            if c["n"].endswith("underground-belt") and c.get("t") == "input" and c["d"] != b["d"] and abs(c["d"] - b["d"]) != 8:
                continue
            if (c["d"] - b["d"]) % 16 == 8:
                continue  # head-on
            if c not in out:
                out.append(c)
        return out

    def reach(self, start, up):
        seen = {}
        stack = [start]
        while stack:
            b = stack.pop()
            if id(b) in seen:
                continue
            seen[id(b)] = b
            stack += self.prev[id(b)] if up else self.nxt(b)
        return list(seen.values())

    def machine_at(self, t):
        return self.mach.get((F(t[0]), F(t[1])))


def where(m):
    return f"{m['recipe']}@({m['x']},{m['y']})"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("out", help="a run's out folder with verify_report.json (made with verify.py --dump), or the report")
    ap.add_argument("--item", help="only machines short of this item")
    ap.add_argument("--slow", action="store_true", help="also machines that crafted under --share of the busiest of their recipe (planned rates differ, so these need not be short)")
    ap.add_argument("--share", type=float, default=0.9, help="with --slow: the share (default 0.9)")
    ap.add_argument("--recipes", default=os.path.join(REPO, "src/main/resources/recipes.json"), help="game data (recipes.json)")
    args = ap.parse_args()
    path = args.out if args.out.endswith(".json") else os.path.join(args.out, "verify_report.json")
    report = json.load(open(path))
    if "dump_belts" not in report:
        sys.exit("the report has no belt dump: run verify.py with --dump")
    recipes, makes, sizes = load_recipes(args.recipes)
    f = Factory(report, sizes)
    best = defaultdict(float)
    for m in report["machines"]:
        best[m["recipe"]] = max(best[m["recipe"]], m["crafts"])
    starving = [m for m in report["machines"]
                if m["usual_status"] == "item_ingredient_shortage"
                or (args.slow and best[m["recipe"]] > 0 and m["crafts"] < args.share * best[m["recipe"]])]
    by_item = defaultdict(list)
    for m in report["machines"]:
        for it in makes.get(m["recipe"], ()):
            by_item[it].append(m)
    found = 0
    for m in starving:
        need = recipes.get(m["recipe"], {})
        have = m.get("input") or {}
        short = [it for it, a in need.items() if have.get(it, 0) < a]
        if args.item:
            short = [it for it in short if it == args.item]
        if not short:
            continue
        found += 1
        print(f"\n{where(m)}: crafted {m['crafts']} (busiest {m['recipe']}: {best[m['recipe']]:g}), {m['usual_status']}, "
              f"holds {have}; short of {', '.join(short)}")
        tiles = {(F(m['x'] - m['size'] / 2 + dx + .5), F(m['y'] - m['size'] / 2 + dy + .5)) for dx in range(m['size']) for dy in range(m['size'])}
        for it in short:
            feeders = [i for t in tiles for i in f.drop[t]]
            belts = [f.cell[(F(i['px']), F(i['py']))] for i in feeders if (F(i['px']), F(i['py'])) in f.cell]
            direct = [f.machine_at((i['px'], i['py'])) for i in feeders if f.machine_at((i['px'], i['py']))]
            direct = [p for p in direct if it in makes.get(p['recipe'], ())]
            for p in direct:
                print(f"  {it}: direct from {where(p)} ({p['usual_status']}, crafted {p['crafts']})")
            up = {}
            for b in belts:
                for u in f.reach(b, up=True):
                    up[id(u)] = u
            if not up:
                if not direct:
                    print(f"  {it}: no inserter brings it")
                continue
            tiles_up = {t for u in up.values() for t in u["tiles"]}
            on = sum(l.get(it, 0) for u in up.values() for l in u["lanes"])
            producers, rivals, sources = [], [], []
            for t in tiles_up:
                for i in f.drop[t]:
                    p = f.machine_at((i['px'], i['py']))
                    if p is None:
                        if i.get("h") in (None, it):
                            sources.append(i)
                    elif it in makes.get(p['recipe'], ()):
                        producers.append((p, i))
                for i in f.pick[t]:
                    c = f.machine_at((i['dx'], i['dy']))
                    if c is not None and c is not m and it in recipes.get(c['recipe'], {}):
                        rivals.append((c, i))
            print(f"  {it}: {len(up)} belt pieces upstream of its {len(belts)} feed belt(s), {on} {it} on them")
            seen = set()
            for p, i in producers:
                if id(p) in seen:
                    continue
                seen.add(id(p))
                print(f"    made by {where(p)}: {p['usual_status']}, crafted {p['crafts']}, output {p.get('output')}, inserter {i.get('s')}")
            if sources:
                print(f"    plus {len(sources)} inserter(s) from chests/ports")
            seen = set()
            for c, i in rivals:
                if id(c) in seen:
                    continue
                seen.add(id(c))
                print(f"    also taken by {where(c)}: {c['usual_status']}, crafted {c['crafts']}, holds {(c.get('input') or {}).get(it, 0)}")
            # Producers of the item standing full anywhere: can their output reach this machine's feed belts?
            feed_ids = {id(b) for b in belts}
            for p in by_item.get(it, []):
                if p['usual_status'] != 'full_output' or id(p) in seen:
                    continue
                ptiles = {(F(p['x'] - p['size'] / 2 + dx + .5), F(p['y'] - p['size'] / 2 + dy + .5)) for dx in range(p['size']) for dy in range(p['size'])}
                outs = [f.cell[(F(i['dx']), F(i['dy']))] for t in ptiles for i in f.pick[t] if (F(i['dx']), F(i['dy'])) in f.cell]
                reaches = any(id(b) in feed_ids for o in outs for b in f.reach(o, up=False))
                print(f"    {where(p)} stands with full output; its output {'reaches' if reaches else 'never reaches'} this machine")
    if not found:
        print("no machine is short of an ingredient")


if __name__ == "__main__":
    main()
