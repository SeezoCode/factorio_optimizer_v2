#!/usr/bin/env python3
"""Belt networks of one item in game: which producers can reach which consumers, and how each network did.

    python3 tools/factorio-verify/verify.py out --dump          # writes out/verify_report.json with belt/inserter dumps
    python3 tools/factorio-verify/chains.py out iron-plate       # every network that carries iron plates
    python3 tools/factorio-verify/chains.py out iron-plate --all # also the networks that kept up

Producers and consumers of the item are joined when the producer's output belt reaches a belt the consumer's
inserter takes from (downstream through belts, undergrounds and splitters). Each network is listed with its
producers (made in game and at full speed), its consumers (what they took in game against the plan's per-machine
share of their recipe), and the blocks it runs through (blocks.json). A network whose producers run flat out while
a consumer of it starves is short as built; one whose consumers take more than planned is over-drawn in game.
"""
import argparse
import json
import os
import sys
from collections import defaultdict, Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from starve import Factory, F, REPO, load_recipes  # noqa: E402


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("out", help="a run's out folder with verify_report.json (made with verify.py --dump)")
    ap.add_argument("item")
    ap.add_argument("--all", action="store_true", help="also networks whose consumers all kept up")
    ap.add_argument("--recipes", default=os.path.join(REPO, "src/main/resources/recipes.json"))
    args = ap.parse_args()
    report = json.load(open(os.path.join(args.out, "verify_report.json")))
    if "dump_belts" not in report:
        sys.exit("the report has no belt dump: run verify.py with --dump")
    data = json.load(open(args.recipes))
    load_recipes(args.recipes)   # also the underground belts' reach (mods change it)
    rec = data["recipes"]
    sizes = {n: (e.get("tile_width") or e.get("width") or 3) for n, e in data.get("entities", {}).items() if isinstance(e, dict)}
    f = Factory(report, sizes)
    T = report["measured_s"]
    item = args.item

    def amount(r, key):
        return sum(x["amount"] for x in rec.get(r, {}).get(key, []) if x["name"] == item)

    # The plan's crafts per machine of each recipe (an average over the recipe's machines).
    plan = json.load(open(os.path.join(args.out, "plan.json")))
    per = defaultdict(list)
    for m in plan["machines"]:
        per[m["recipe"]].append(m["crafts"])
    planned = {r: sum(v) / len(v) for r, v in per.items()}

    blocks = []
    bf = os.path.join(args.out, "blocks.json")
    if os.path.exists(bf):
        blocks = json.load(open(bf))
    # Blueprint coordinates of the report are the factory's: blocks.json is in the same frame.
    def block_of(x, y):
        for b in blocks:
            if b["x"] <= x < b["x"] + b["w"] and b["y"] <= y < b["y"] + b["h"]:
                return b["block"]
        return None

    # Producer machine -> belts its item lands on; consumer machine -> belts it takes the item from.
    prod_belts, cons_belts = defaultdict(set), defaultdict(set)
    machines = {}
    for i in report["dump_inserters"]:
        src = f.machine_at((i["px"], i["py"]))
        dst = f.machine_at((i["dx"], i["dy"]))
        pb = f.cell.get((F(i["px"]), F(i["py"])))
        db = f.cell.get((F(i["dx"]), F(i["dy"])))
        if src is not None and db is not None and amount(src["recipe"], "products") > 0:
            prod_belts[id(src)].add(id(db)); machines[id(src)] = src
        if dst is not None and pb is not None and amount(dst["recipe"], "ingredients") > 0:
            cons_belts[id(dst)].add(id(pb)); machines[id(dst)] = dst
    byid = {id(b): b for b in report["dump_belts"]}
    # Union-find over producers and consumers: a producer joins every consumer its belts reach.
    parent = {}
    def find(a):
        parent.setdefault(a, a)
        while parent[a] != a:
            parent[a] = parent[parent[a]]; a = parent[a]
        return a
    def union(a, b):
        parent[find(a)] = find(b)
    pickers = defaultdict(set)
    for c, bs in cons_belts.items():
        for b in bs:
            pickers[b].add(c)
    reaches = {}
    for p, bs in prod_belts.items():
        find(p)
        seen = set()
        for b in bs:
            for d in f.reach(byid[b], up=False):
                seen.add(id(d))
        reaches[p] = seen
        for b in seen:
            for c in pickers.get(b, ()):
                union(p, c)
    for c in cons_belts:
        find(c)
    nets = defaultdict(lambda: {"p": [], "c": []})
    for p in prod_belts:
        nets[find(p)]["p"].append(machines[p])
    for c in cons_belts:
        nets[find(c)]["c"].append(machines[c])

    def made(m):
        return m["crafts"] / T * amount(m["recipe"], "products")
    def full(m):
        e = rec[m["recipe"]].get("energy", 0.5)
        return m["speed"] / e * amount(m["recipe"], "products")
    def took(m):
        return m["crafts"] / T * amount(m["recipe"], "ingredients")
    def want(m):
        return planned.get(m["recipe"], 0) * amount(m["recipe"], "ingredients")

    shown = 0
    for k, n in sorted(nets.items(), key=lambda kv: -sum(made(m) for m in kv[1]["p"])):
        ps, cs = n["p"], n["c"]
        short = [c for c in cs if c["usual_status"] == "item_ingredient_shortage" and (c.get("input") or {}).get(item, 0) < amount(c["recipe"], "ingredients")]
        if not args.all and not short:
            continue
        shown += 1
        bl = Counter(block_of(m["x"], m["y"]) for m in ps + cs)
        print(f"\nnetwork of {len(ps)} producers, {len(cs)} consumers; blocks {dict(bl)}")
        print(f"  made {sum(made(m) for m in ps):.2f}/s of {sum(full(m) for m in ps):.2f}/s at full speed; "
              f"taken {sum(took(c) for c in cs):.2f}/s, planned {sum(want(c) for c in cs):.2f}/s")
        byb = defaultdict(list)
        for m in ps:
            byb[(m["recipe"], block_of(m["x"], m["y"]))].append(m)
        for (r, b), ms in sorted(byb.items(), key=lambda kv: str(kv[0][1])):
            st = Counter(m["usual_status"] for m in ms)
            print(f"  made by {r:20} block {str(b):4} x{len(ms):<3} {sum(made(m) for m in ms):7.2f}/s of {sum(full(m) for m in ms):7.2f}/s  "
                  + ", ".join(f"{k} {v}" for k, v in st.items()))
        byr = defaultdict(list)
        for c in cs:
            byr[(c["recipe"], block_of(c["x"], c["y"]))].append(c)
        for (r, b), ms in sorted(byr.items(), key=lambda kv: -sum(took(c) for c in kv[1])):
            t, w = sum(took(c) for c in ms), sum(want(c) for c in ms)
            s = sum(1 for c in ms if c in short)
            print(f"  {r:28} block {str(b):4} x{len(ms):<3} took {t:7.2f}/s  planned {w:7.2f}/s  {100 * t / w if w else 0:5.0f}%"
                  + (f"  {s} starving" if s else ""))
    if not shown:
        print(f"no network of {item} has a consumer starving for it" + ("" if args.all else " (--all lists them all)"))


if __name__ == "__main__":
    main()
