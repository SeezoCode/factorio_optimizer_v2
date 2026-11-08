#!/usr/bin/env python3
"""Where a factory stalls in game: per item the idle machines are short of, how many wait for it and whether
anything made it while measured (makers' status). Items no maker made, waited on by many, are the roots.

    python3 tools/factorio-verify/stall.py <out> --recipes data/pyanodon.json
"""
import argparse, json, os
from collections import defaultdict, Counter

ap = argparse.ArgumentParser()
ap.add_argument("out"); ap.add_argument("--recipes", required=True); ap.add_argument("--top", type=int, default=25)
a = ap.parse_args()
r = json.load(open(os.path.join(a.out, "verify_report.json")))
R = json.load(open(a.recipes))["recipes"]
def rec(name): return R.get((name or "").split("#")[0]) or R.get(name or "")
waiting = defaultdict(list); makers = defaultdict(list)
for m in r["machines"]:
    rc = rec(m.get("recipe"))
    if not rc: continue
    for p in rc["products"]: makers[p["name"]].append(m)
    if m["crafts"] > 0: continue
    st = m["status"]
    if "shortage" in st or "no_ingredients" in st:
        for i in rc["ingredients"]:
            if i["type"] == "item" and m["input"].get(i["name"], 0) < i["amount"]: waiting[i["name"]].append(m)
            elif i["type"] == "fluid" and "fluid" in st: waiting["[" + i["name"] + "]"].append(m)
print(f"{sum(m['crafts'] > 0 for m in r['machines'])} of {len(r['machines'])} machines crafted while measured")
for item, ws in sorted(waiting.items(), key=lambda kv: -len(kv[1]))[:a.top]:
    mk = makers.get(item.strip("[]"), [])
    made = sum(m["crafts"] for m in mk)
    sts = Counter(m["status"] for m in mk)
    print(f"  {item:32} {len(ws):3} waiting ({', '.join(sorted({w['recipe'] for w in ws})[:3])}) | makers {len(mk)} made {made} {dict(sts)}")
