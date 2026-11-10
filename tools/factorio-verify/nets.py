#!/usr/bin/env python3
"""Where an item goes in an in-game check: each belt network that carries it or that its makers drop onto or its users
take from, with those makers and users (status, crafts while measured, what they hold of it), the items on its belts,
and the edge sinks and sources on it.

    python3 tools/factorio-verify/nets.py <out> <item> [--recipes data/pyanodon.json]

Needs a report written with --dump by a mod that records belt networks (verify_report.json: "feeds"/"drops" per machine,
"net" per belt and edge port).
"""
import argparse
import json
import os
from collections import defaultdict

ap = argparse.ArgumentParser()
ap.add_argument("out")
ap.add_argument("item")
ap.add_argument("--recipes", default="data/pyanodon.json")
a = ap.parse_args()
r = json.load(open(os.path.join(a.out, "verify_report.json")))
data = json.load(open(a.recipes))
recipes = data["recipes"]
item = a.item


def amount(rec, side):
    # (A range, as Py's 1 to 2 fiber from wood, counts at its middle.)
    def one(x):
        a = x.get("amount")
        if a is None:
            a = ((x.get("amount_min") or 0) + (x.get("amount_max") or 0)) / 2
        return a * (x.get("probability") or 1)
    return sum(one(x) for x in rec.get(side, []) if x["name"] == item)


on_belts, belts = defaultdict(int), defaultdict(int)
for b in r.get("dump_belts", []):
    n = sum(l.get(item, 0) for l in b["lanes"])
    if n:
        on_belts[b.get("net")] += n
        belts[b.get("net")] += 1
makers, users = defaultdict(list), defaultdict(list)
for m in r["machines"]:
    rec = recipes.get((m.get("recipe") or "").split("#")[0], {})
    if amount(rec, "products") > 0:
        for n in m.get("drops") or [None]:
            makers[n].append(m)
    if amount(rec, "ingredients") > 0:
        for n in m.get("feeds") or [None]:
            users[n].append(m)
sinks = defaultdict(list)
for o in r["outputs"]:
    if item in o.get("per_s", {}) or o.get("net") in on_belts:
        sinks[o.get("net")].append(o)
sources = defaultdict(list)
for i in r["inputs"]:
    if item in i.get("per_s", {}):
        sources[i.get("net")].append(i)
nets = set(on_belts) | set(makers) | set(users) | set(sources)
secs = r["measured_s"]
for n in sorted(nets, key=lambda n: (-(on_belts.get(n, 0)), str(n))):
    print(f"net {n}: {on_belts.get(n, 0)} on {belts.get(n, 0)} belts")
    for s in sources.get(n, []):
        print(f"  source ({s['x']},{s['y']}) {s['per_s'].get(item, 0):.3f}/s")
    for m in makers.get(n, []):
        rec = recipes.get(m["recipe"].split("#")[0], {})
        print(f"  maker  {m['recipe'][:30]:30} ({m['x']},{m['y']}) {m['status'][:24]:24} {m['crafts']:5} crafts "
              f"({m['crafts'] * amount(rec, 'products') / secs:.3f}/s)  holds {(m.get('output') or {}).get(item, 0)}")
    for m in users.get(n, []):
        rec = recipes.get(m["recipe"].split("#")[0], {})
        need = amount(rec, "ingredients")
        print(f"  user   {m['recipe'][:30]:30} ({m['x']},{m['y']}) {m['status'][:24]:24} {m['crafts']:5} crafts "
              f"({m['crafts'] * need / secs:.3f}/s)  holds {(m.get('input') or {}).get(item, 0)} of {need:g} a craft")
    for s in sinks.get(n, []):
        kind = "overflow" if s.get("overflow") else "mixed" if s.get("mixed") else "sink"
        print(f"  {kind:8} ({s['x']},{s['y']}) " + ", ".join(f"{k} {v:.3f}/s" for k, v in s["per_s"].items() if v))
