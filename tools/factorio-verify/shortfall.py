#!/usr/bin/env python3
"""Why a factory fell short in an in-game check: from the machines making the target, what each stood short of at the
end, who makes that, and what those stood short of in turn, as a tree (each machine once), with each machine's crafts
against its plan and the statuses it spent its time in. (why.py starts from a recipe and digs for the roots.)

    python3 tools/factorio-verify/shortfall.py <out> [item | --job <job.json>] [--recipes data/pyanodon.json] [--depth 8]

<out> holds verify_report.json and plan.json. Without an item it starts from the job's targets. A machine line
shows its recipe, place, status at the end, crafts while measured against its plan, and in brackets the statuses it
spent most seconds in; "short:" lists the ingredients it held less than one craft of (fluids from its fluid boxes).
"""
import argparse
import json
import os
from collections import defaultdict

ap = argparse.ArgumentParser()
ap.add_argument("out")
ap.add_argument("item", nargs="?")
ap.add_argument("--recipes", default="data/pyanodon.json")
ap.add_argument("--depth", type=int, default=8)
ap.add_argument("--job", help="the job file, for its targets when no item is named")
a = ap.parse_args()
rep = json.load(open(os.path.join(a.out, "verify_report.json")))
plan = json.load(open(os.path.join(a.out, "plan.json"))) if os.path.exists(os.path.join(a.out, "plan.json")) else {}
recipes = json.load(open(a.recipes))["recipes"]
secs = rep["measured_s"]


def base(name):
    return (name or "").split("#")[0].split("@")[0]


planned = defaultdict(float)
count = defaultdict(int)
for m in plan.get("machines", []):
    planned[base(m["recipe"])] += m.get("crafts", 0.0)
    count[base(m["recipe"])] += 1

makers = defaultdict(list)
for m in rep["machines"]:
    rec = recipes.get(base(m.get("recipe")), {})
    for p in rec.get("products", []):
        makers[p["name"]].append(m)


def short_of(m):
    rec = recipes.get(base(m.get("recipe")), {})
    held = dict(m.get("input") or {})
    for f in m.get("fluids") or []:
        if f.get("io") == "input" and f.get("name"):
            held[f["name"]] = held.get(f["name"], 0) + f.get("amount", 0)
    return [(i["name"], held.get(i["name"], 0), i["amount"]) for i in rec.get("ingredients", [])
            if held.get(i["name"], 0) < i["amount"]]


def line(m):
    r = base(m.get("recipe"))
    plan_crafts = planned[r] / max(1, count[r]) * secs
    top = sorted((m.get("statuses") or {}).items(), key=lambda kv: -kv[1])[:2]
    tot = sum((m.get("statuses") or {}).values()) or 1
    st = ", ".join(f"{k} {100 * v // tot}%" for k, v in top)
    return f"{r[:34]:34} ({m['x']},{m['y']}) {m['status'][:26]:26} {m['crafts']:4} of {plan_crafts:6.1f} planned  [{st}]"


seen = set()


def walk(item, depth, pad):
    ms = makers.get(item, [])
    if not ms:
        print(f"{pad}{item}: no machine makes it (an input)")
        return
    for m in ms:
        key = (m["x"], m["y"])
        if key in seen:
            print(f"{pad}{item} <- {base(m.get('recipe'))} ({m['x']},{m['y']}) (above)")
            continue
        seen.add(key)
        sh = short_of(m)
        print(f"{pad}{item} <- {line(m)}")
        if sh:
            print(f"{pad}    short: " + ", ".join(f"{n} {h:g}/{need:g}" for n, h, need in sh))
        if depth > 0 and m["status"] not in ("working", "full_output", "output_full"):
            for n, _, _ in sh:
                walk(n, depth - 1, pad + "    ")


targets = [a.item] if a.item else [t["name"] for t in json.load(open(a.job))["targets"]] if a.job else []
if not targets:
    ap.error("name the item, or the job (--job) to take its targets")
for t in targets:
    walk(t, a.depth, "")
