#!/usr/bin/env python3
"""Why a machine does not craft in game: what it waits for, then why each maker of that does not make it, and so on
down to the roots (a maker that works but is too slow, one standing with full output and which of its products is
stuck, an item nothing in the factory makes).

    python3 tools/factorio-verify/why.py <out> <recipe> --recipes data/pyanodon.json [--depth 8]
"""
import argparse, json, os
from collections import defaultdict

ap = argparse.ArgumentParser()
ap.add_argument("out"); ap.add_argument("recipe"); ap.add_argument("--recipes", required=True); ap.add_argument("--depth", type=int, default=8)
a = ap.parse_args()
r = json.load(open(os.path.join(a.out, "verify_report.json")))
R = json.load(open(a.recipes))["recipes"]
fluids_of = {}
for q in r.get("dump_pipes", []):
    if "status" in q and q.get("get_fluid"):
        fluids_of[(q["x"], q["y"])] = q["get_fluid"]
def rec(m): return R.get((m.get("recipe") or "").split("#")[0])
makers = defaultdict(list); users = defaultdict(list)
for m in r["machines"]:
    rc = rec(m)
    if not rc: continue
    for p in rc["products"]: makers[p["name"]].append(m)
    for i in rc["ingredients"]: users[i["name"]].append(m)
def missing(m):
    rc = rec(m); out = []
    for i in rc["ingredients"]:
        if i["type"] == "item" and m["input"].get(i["name"], 0) < i["amount"]: out.append(i["name"])
        if i["type"] == "fluid":
            g = fluids_of.get((m["x"], m["y"]), "")
            box = [b for b in g.split() if b.endswith("/" + i["name"])]
            if box and box[0].startswith("-") or (not box and "fluid" in m["status"]): out.append("[" + i["name"] + "]")
    return out
seen = set()
def show(m, depth, ind):
    key = (m["x"], m["y"])
    tag = f"{m['recipe']} @({m['x']},{m['y']}) {m['status']} crafts {m['crafts']}"
    if key in seen: print(ind + tag + " (above)"); return
    seen.add(key); print(ind + tag)
    if depth <= 0: return
    if "full_output" in m["status"]:
        rc = rec(m)
        for p in rc["products"]:
            n = p["name"]; us = [u for u in users[n] if u is not m]
            busy = [u for u in us if u["crafts"] > 0]
            print(ind + f"  product {n}: {len(us)} users, {len(busy)} crafting; held {m['output'].get(n, '')}")
        return
    for it in missing(m):
        name = it.strip("[]"); mk = makers.get(name, [])
        made = sum(x["crafts"] for x in mk)
        print(ind + f"  waits {it}: {len(mk)} makers, made {made}")
        for x in sorted(mk, key=lambda x: -x["crafts"])[:2]:
            if x["crafts"] > 0: print(ind + f"    {x['recipe']} @({x['x']},{x['y']}) crafts {x['crafts']} {x['status']} (made some: share or route)")
            else: show(x, depth - 1, ind + "    ")
for m in r["machines"]:
    if (m.get("recipe") or "").split("#")[0] == a.recipe: show(m, a.depth, "")
