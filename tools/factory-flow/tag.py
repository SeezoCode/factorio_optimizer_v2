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
from collections import defaultdict, deque

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


def purposes(plan):
    """Recipe -> (the products its machines were planned for, planned crafts per second of one machine): the main
    product, or every product the plan uses when the main one leaves as a by-product (plan.json's '#byproduct')."""
    purpose, crafts = defaultdict(set), defaultdict(float)
    for m in plan.get("machines", []):
        r = base(m["recipe"])
        used = {base(k) for k in m.get("products", {}) if not k.endswith("#byproduct")}
        main = base(m.get("product", ""))
        purpose[r] |= {main} if main in used else used
        crafts[r] = max(crafts[r], m.get("crafts", 0.0))
    return purpose, crafts


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


def loops(plan, ports=None, deep=None, targets=()):
    """(inside, outside): item -> the recipes using it that are in the item's own production loop, and those that are
    not, for every item with users of both kinds. A user is in the loop when what it makes comes back to a maker of
    the item within three recipes (0: it makes the item itself, a catalyst's farm). Py logistic science is one loop of
    53 recipes by way of ash, biomass and barrels, so "in a loop at all" says nothing: the users within three steps
    are the item's own loop, the rest take what that leaves (the mod's loop-first rule)."""
    recs = {}
    for m in plan.get("machines", []):
        r = recs.setdefault(base(m["recipe"]), {"in": set(), "out": set()})
        r["in"] |= {base(k) for k in m.get("demand", {})}
        r["out"] |= {base(k) for k in m.get("products", {})}
    makers = defaultdict(set)
    for n, r in recs.items():
        for o in r["out"]:
            makers[o].add(n)
    adj = {n: set() for n in recs}
    for n, r in recs.items():
        for i in r["in"]:
            for mk in makers.get(i, ()):
                adj[mk].add(n)

    def back(mks, start):
        if start in mks:
            return 0
        seen, todo = {start}, deque([(start, 0)])
        while todo:
            v, d = todo.popleft()
            for w in adj[v]:
                if w in mks:
                    return d + 1
                if w not in seen:
                    seen.add(w)
                    todo.append((w, d + 1))
        return None

    use = defaultdict(lambda: defaultdict(float))
    for m in plan.get("machines", []):
        for k, v in m.get("demand", {}).items():
            use[base(k)][base(m["recipe"])] += v
    inside, outside = defaultdict(set), defaultdict(set)
    for item, mks in makers.items():
        ds = {n: back(mks, n) for n, r in recs.items() if item in r["in"]}
        # (deep D, as the check's --loop-deep: an item none of whose users is within three steps, but one is within D,
        # has as its loop the users at the nearest distance, where those take at least half of the plan's use of
        # it and no maker of a target is among the others. Logistic science's mukmoux loop (bones, bonemeal, sap
        # trees, saps, mukmoux food, calves, mukmoux, the renderer) is four to six steps round.)
        lim = 3
        if deep and not any(d is not None and d <= 3 for d in ds.values()):
            dd = min((d for d in ds.values() if d is not None and d <= deep), default=None)
            if dd is not None:
                ins_ = [n for n, d in ds.items() if d is not None and d <= dd]
                tot = sum(use[item].values()) or 1
                if sum(use[item][n] for n in ins_) / tot >= 0.5 and not any(recs[n]["out"] & set(targets) for n in ds if n not in ins_):
                    lim = dd
        for n, d in ds.items():
            (inside if d is not None and d <= lim else outside)[item].add(n)
    # (Fluid loops too, found over the graph with fluid inputs counted as well; items keep the classification above.
    # The mod uses them under its loop-fluids setting. Not a fluid that an input pipe brings: it does not run out.
    # The margin layout's syngas goes round by way of refined syngas and oleochemicals, and the plastic maker and four
    # other users outside took it from the shared pipes until the loop stood.)
    fin = defaultdict(set)
    for m in plan.get("machines", []):
        fin[base(m["recipe"])] |= {base(k) for k in m.get("fluidDemand", {})}
    for n, fs in fin.items():
        for f in fs:
            for mk in makers.get(f, ()):
                adj[mk].add(n)
    raw_f = {base(i["item"]) for i in (ports or {}).get("inputs", []) if i.get("fluid")}
    for f in {f for fs in fin.values() for f in fs}:
        mks = makers.get(f, set())
        if not mks or f in raw_f:
            continue
        ins_, outs_ = set(), set()
        for n, fs in fin.items():
            if f in fs:
                d = back(mks, n)
                (ins_ if d is not None and d <= 3 else outs_).add(n)
        if ins_ and outs_:
            inside[f], outside[f] = ins_, outs_
    both = {i for i in inside if i in outside}
    return {i: inside[i] for i in both}, {i: outside[i] for i in both}


def overflow_ports(plan, ports, targets):
    """Item outputs that only take what is left over: 0/s by-products, and items the factory itself also uses."""
    used = {base(t["item"]) for t in plan.get("terminals", []) if t.get("role") == "CONSUMER"}
    return [o for o in ports.get("outputs", []) if not o.get("fluid") and base(o["item"]) not in targets
            and (o["rate"] <= 1e-12 or base(o["item"]) in used)]


def tag(bp_obj, plan, ports, targets, recipes=None, deep=None):
    """Adds the tags in place; returns (machines tagged, overflow belts tagged)."""
    bp = bp_obj["blueprint"]
    intake = planned_intake(plan)
    purpose, crafts = purposes(plan)
    loop = carriers(plan)
    makes = {}
    for r in recipes or {}:
        makes[r] = {p["name"] for p in recipes[r].get("products", [])}
    by_tile = {}
    for e in bp["entities"]:
        by_tile[(int(e["position"]["x"] // 1), int(e["position"]["y"] // 1))] = e
    inside, outside = loops(plan, ports, deep, targets)
    # (The recipes the plan has for each kind of machine: a furnace whose blueprint names none is one of them.)
    of_machine = defaultdict(set)
    for m in plan.get("machines", []):
        of_machine[m.get("machine")].add(base(m["recipe"]))

    def flow_of(r):
        flow = {"take": {k: round(v, 9) for k, v in intake[r].items()}}
        # (What it was planned for, and how fast: the side-product cap.)
        if purpose.get(r):
            flow["purpose"] = sorted(purpose[r])
            flow["crafts"] = round(crafts[r], 9)
        if makes.get(r, set()) & targets:
            flow["target"] = True
        # (The loop carriers it takes: the mod says when a loop starves for them.)
        held = sorted(k for k in intake[r] if k in loop)
        if held:
            flow["carriers"] = held
        # (Loop first: the items it takes as one of their loop's own users, and those it takes from outside.)
        lin = sorted(i for i in inside if r in inside[i])
        lout = sorted(i for i in outside if r in outside[i])
        if lin:
            flow["loop_in"] = lin
        if lout:
            flow["loop_out"] = lout
        return flow

    machines = 0
    for e in bp["entities"]:
        r = e.get("recipe") or (e.get("tags") or {}).get("recipe")
        if not r and of_machine.get(e["name"]):
            # A furnace with no recipe in the blueprint (an older layout's barrel fillers): the mod picks among the
            # plan's recipes for that machine once it sees what the furnace holds or crafts.
            e.setdefault("tags", {})["flow"] = {"candidates": {c: flow_of(c) for c in sorted(of_machine[e["name"]]) if c in intake}}
            machines += 1
            continue
        if not r or r not in intake:
            continue
        e.setdefault("tags", {})["flow"] = flow_of(r)
        machines += 1
    ends = 0
    for o in overflow_ports(plan, ports, targets):
        e = by_tile.get((o["x"], o["y"]))
        if e and any(e["name"].endswith(b) for b in BELTS):
            e.setdefault("tags", {})["flow"] = {"overflow": base(o["item"])}
            ends += 1
    # By-product chests beside blocks (blocks.local_sinks, a 0/s output inside the factory): the inserter beside the
    # port tile that drops into the chest two tiles out carries the item, so the mod gates it.
    for o in ports.get("outputs", []):
        if not o.get("local") or o.get("fluid") or o.get("rate", 0) > 1e-12:
            continue
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            ins = by_tile.get((o["x"] + dx, o["y"] + dy))
            chest = by_tile.get((o["x"] + 2 * dx, o["y"] + 2 * dy))
            if ins and chest and "inserter" in ins["name"] and "chest" in chest["name"]:
                ins.setdefault("tags", {})["flow"] = {"sink": base(o["item"])}
                break
    # Robot links (blocks.bot_links): their provider and requester chests carry the item, so the mod treats the belts
    # into and out of them as one (pull reaches the makers behind a provider).
    for role in ("providers", "requesters"):
        for b in (ports.get("bots") or {}).get(role, []):
            e = by_tile.get((b["x"], b["y"]))
            if e:
                e.setdefault("tags", {})["flow"] = {"bot": base(b["item"])}
    return machines, ends


# What the in-game check builds from real parts and a player needs too: an overflow exit at each machine that could
# stop on a product nobody takes (an inserter set by circuit, its chest, a decider per product), and at each fluid
# output Py's overflow valve with an exhaust pipe or sinkhole behind it (and the pipes between).
EXTRAS = {"fast-inserter", "steel-chest", "decider-combinator", "py-overflow-valve", "py-gas-vent", "py-sinkhole"}


def merge_built(bp_obj, built_obj, pipes=()):
    """Adds to the blueprint what the in-game check built into its test copy (verify.py --relief-exits --real-voids
    --export-built): the entities of the kinds in EXTRAS and pipes that the blueprint does not have, with their
    circuit wires, and the circuit settings of the machines they are wired to. Returns a count by name."""
    bp, built = bp_obj["blueprint"], built_obj["blueprint"]

    def key(e, dx=0.0, dy=0.0):
        return e["name"], round(e["position"]["x"] - dx, 2), round(e["position"]["y"] - dy, 2)

    orig = {key(e): e for e in bp["entities"]}
    # The game centres a blueprint where it likes: the shift under which most entities fall onto the blueprint's own.
    anchor = next(e for e in bp["entities"] if e.get("recipe"))
    sample = built["entities"][::29]
    best = None
    for e in built["entities"]:
        if e["name"] == anchor["name"] and e.get("recipe") == anchor["recipe"]:
            dx, dy = e["position"]["x"] - anchor["position"]["x"], e["position"]["y"] - anchor["position"]["y"]
            n = sum(1 for x in sample if key(x, dx, dy) in orig)
            if best is None or n > best[0]:
                best = (n, dx, dy)
    if best is None or best[0] < 0.8 * len(sample):
        raise SystemExit("the built copy does not match this blueprint")
    _, dx, dy = best
    last = max(e["entity_number"] for e in bp["entities"])
    nxt, num, extras, counts = last + 1, {}, [], defaultdict(int)
    matched = {}
    for e in built["entities"]:
        k = key(e, dx, dy)
        if k in orig:
            num[e["entity_number"]] = orig[k]["entity_number"]
            matched[orig[k]["entity_number"]] = e
        elif e["name"] in EXTRAS or e["name"] in pipes:
            c = dict(e)
            c["position"] = {"x": e["position"]["x"] - dx, "y": e["position"]["y"] - dy}
            c["entity_number"] = nxt
            num[e["entity_number"]] = nxt
            nxt += 1
            extras.append(c)
            counts[e["name"]] += 1
    # Every circuit wire the check built (also between entities the blueprint has: an exit or self-exit inserter
    # wired to its machine, the pull network's wire hopping over the poles), each once.
    have = {tuple(w) for w in bp.get("wires", [])} | {(w[2], w[3], w[0], w[1]) for w in bp.get("wires", [])}
    wires, wired = [], set()
    for a, ca, b, cb in built.get("wires", []):
        if a in num and b in num and (num[a], ca, num[b], cb) not in have:
            wires.append([num[a], ca, num[b], cb])
            have.add((num[a], ca, num[b], cb)); have.add((num[b], cb, num[a], ca))
            wired.update((num[a], num[b]))
    # (The circuit settings of wired entities the blueprint has: a machine's "read contents", a pull or self-exit
    # inserter's enable condition, a machine's enable condition, a gate belt's condition and a sensor's read mode.)
    for e in bp["entities"]:
        m = matched.get(e["entity_number"])
        if e["entity_number"] in wired and m is not None and m.get("control_behavior") and "control_behavior" not in e:
            e["control_behavior"] = m["control_behavior"]
    bp["entities"] += extras
    bp["wires"] = bp.get("wires", []) + wires
    counts["wires"] = len(wires)
    return dict(counts)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--job", required=True, help="the job file, for its targets")
    ap.add_argument("--blueprint", default="best_output_string.txt", help="the blueprint string file in <out>")
    ap.add_argument("--recipes", help="the game data (recipes) the job used, to find what makes the targets; "
                                      "defaults to the job's own data file")
    ap.add_argument("--built", help="the test copy as the in-game check built it (verify.py --relief-exits --real-voids "
                                    "--export-built FILE): its overflow exits and fluid voids are added to the blueprint")
    ap.add_argument("--loop-deep", type=int, default=None, metavar="D",
                    help="an item with no user within three recipe steps of a maker, but one within D, is a loop item too")
    ap.add_argument("-o", "--output", help="where to write the tagged string (default <out>/flow_blueprint.txt)")
    a = ap.parse_args()
    job = json.load(open(a.job))
    targets = {t["name"] for t in job.get("targets", [])}
    data = a.recipes or job.get("data") or "data/recipes.json"
    recipes = json.load(open(data))["recipes"] if os.path.exists(data) else {}
    # (The blocks' rebalanced plan when there is one: the rates the factory was built for, per machine.)
    rebalanced = os.path.join(a.out, "plan_rebalanced.json")
    plan = json.load(open(rebalanced if os.path.exists(rebalanced) else os.path.join(a.out, "plan.json")))
    ports = json.load(open(os.path.join(a.out, "ports.json")))
    bp = decode(open(os.path.join(a.out, a.blueprint)).read())
    machines, ends = tag(bp, plan, ports, targets, recipes, a.loop_deep)
    added = {}
    if a.built:
        # (Pipes: the kinds the blueprint itself uses; the check extends a port with the neighbouring pipe's own kind.)
        pipes = {e["name"] for e in bp["blueprint"]["entities"] if "pipe" in e["name"] and "infinity" not in e["name"]} | {"pipe"}
        added = merge_built(bp, decode(open(a.built).read()), pipes)
    path = a.output or os.path.join(a.out, "flow_blueprint.txt")
    open(path, "w").write(encode(bp))
    print(f"{machines} machines and {ends} overflow belts tagged -> {path}")
    if added:
        print("added from the built copy: " + ", ".join(f"{v} {k}" for k, v in sorted(added.items())))


if __name__ == "__main__":
    main()
