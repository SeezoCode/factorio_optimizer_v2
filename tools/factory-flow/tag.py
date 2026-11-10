#!/usr/bin/env python3
"""Tags a factorio_optimizer blueprint for the factory-flow mod: each machine gets its planned intake per second (items,
fuel and fluids) and whether it makes a target, and each overflow port's belt the item it lets out.

    python3 tools/factory-flow/tag.py <out> --job <job.json> [--blueprint best_output_string.txt] [-o flow_blueprint.txt]

<out> is a solve directory (plan.json, ports.json and the blueprint string). The tagged string goes to
<out>/flow_blueprint.txt unless -o says otherwise; build it with the mod installed, and the mod keeps the factory near its
plan (see tools/factory-flow/README.md).
"""
import argparse
import base64
import json
import os
import zlib
from collections import defaultdict

BELTS = ("transport-belt", "underground-belt", "splitter")


def decode(text):
    return json.loads(zlib.decompress(base64.b64decode(text.strip()[1:])))


def encode(obj):
    return "0" + base64.b64encode(zlib.compress(json.dumps(obj, separators=(",", ":")).encode(), 9)).decode()


def base(name):
    return name.split("#")[0].split("@")[0]


def planned_intake(plan):
    """Recipe -> item or fluid -> planned intake per second of one machine (the mean over the plan's machines)."""
    tot, n = defaultdict(lambda: defaultdict(float)), defaultdict(int)
    for m in plan.get("machines", []):
        r = base(m["recipe"])
        n[r] += 1
        for k, v in {**m.get("demand", {}), **m.get("fluidDemand", {})}.items():
            tot[r][base(k)] += v
    return {r: {k: v / n[r] for k, v in tot[r].items()} for r in n}


def carriers(plan):
    """Items a production loop passes round one for one (plan.json seedCarriers, and every item of their rings): what a
    new loop needs put in by hand once (barrels, cages, bricks)."""
    ms = plan.get("machines", [])

    def same(a, b):
        return abs(a - b) <= 1e-6 * max(a, b) + 1e-12

    def reach(item):
        seen, todo = {item}, [item]
        while todo:
            x = todo.pop()
            for m in ms:
                rx = m.get("demand", {}).get(x, m.get("fluidDemand", {}).get(x))
                if rx is None:
                    continue
                for y, ry in m.get("products", {}).items():
                    if y not in seen and same(rx, ry):
                        seen.add(y)
                        todo.append(y)
        return seen

    names = {y for c in plan.get("seedCarriers", []) for y in reach(c) if c in reach(y)}
    return {base(n) for n in names}


def overflow_ports(plan, ports, targets):
    """Item outputs that only take what is left over: 0/s by-products, and items the factory itself also uses."""
    used = {base(t["item"]) for t in plan.get("terminals", []) if t.get("role") == "CONSUMER"}
    return [o for o in ports.get("outputs", []) if not o.get("fluid") and base(o["item"]) not in targets
            and (o["rate"] <= 1e-12 or base(o["item"]) in used)]


def tag(bp_obj, plan, ports, targets, recipes=None):
    """Adds the tags in place; returns (machines tagged, overflow belts tagged)."""
    bp = bp_obj["blueprint"]
    intake = planned_intake(plan)
    loop = carriers(plan)
    makes = {}
    for r in recipes or {}:
        makes[r] = {p["name"] for p in recipes[r].get("products", [])}
    by_tile = {}
    for e in bp["entities"]:
        by_tile[(int(e["position"]["x"] // 1), int(e["position"]["y"] // 1))] = e
    machines = 0
    for e in bp["entities"]:
        r = e.get("recipe") or (e.get("tags") or {}).get("recipe")
        if not r or r not in intake:
            continue
        flow = {"take": {k: round(v, 9) for k, v in intake[r].items()}}
        if makes.get(r, set()) & targets:
            flow["target"] = True
        # (The loop carriers it takes: the mod says when a loop starves for them.)
        held = sorted(k for k in intake[r] if k in loop)
        if held:
            flow["carriers"] = held
        e.setdefault("tags", {})["flow"] = flow
        machines += 1
    ends = 0
    for o in overflow_ports(plan, ports, targets):
        e = by_tile.get((o["x"], o["y"]))
        if e and any(e["name"].endswith(b) for b in BELTS):
            e.setdefault("tags", {})["flow"] = {"overflow": base(o["item"])}
            ends += 1
    return machines, ends


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--job", required=True, help="the job file, for its targets")
    ap.add_argument("--blueprint", default="best_output_string.txt", help="the blueprint string file in <out>")
    ap.add_argument("--recipes", help="the game data (recipes) the job used, to find what makes the targets; "
                                      "defaults to the job's own data file")
    ap.add_argument("-o", "--output", help="where to write the tagged string (default <out>/flow_blueprint.txt)")
    a = ap.parse_args()
    job = json.load(open(a.job))
    targets = {t["name"] for t in job.get("targets", [])}
    data = a.recipes or job.get("data") or "data/recipes.json"
    recipes = json.load(open(data))["recipes"] if os.path.exists(data) else {}
    plan = json.load(open(os.path.join(a.out, "plan.json")))
    ports = json.load(open(os.path.join(a.out, "ports.json")))
    bp = decode(open(os.path.join(a.out, a.blueprint)).read())
    machines, ends = tag(bp, plan, ports, targets, recipes)
    path = a.output or os.path.join(a.out, "flow_blueprint.txt")
    open(path, "w").write(encode(bp))
    print(f"{machines} machines and {ends} overflow belts tagged -> {path}")


if __name__ == "__main__":
    main()
