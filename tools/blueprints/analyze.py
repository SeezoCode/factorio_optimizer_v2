#!/usr/bin/env python3
"""Metrics and layout patterns of a Factorio blueprint, for comparing community designs with the solver's.

    python3 tools/blueprints/analyze.py <blueprint string | file> [--list] [--pick PATH] [--product NAME] [--json]

The input is a blueprint string ("0eNq..."), a file holding one (a solver run's best_output_string.txt), a
blueprint's JSON, or a factorio.school / factorioprints record (JSON with a "blueprintString" field). `-` reads
stdin. A book is listed with --list; --pick chooses one blueprint in it by its index path ("3" or "2/5", as --list
prints) or by a case-insensitive piece of its label.

What it measures (all assuming every machine can run flat out; nothing is simulated):
- footprint: bounding box of all entities (w x h) and the share of its tiles that entities cover;
- components: machines by recipe, beacons, belts, undergrounds, splitters, inserters, poles, pipes, other;
- throughput: each machine's crafts/s from its crafting speed, its modules and the beacons in reach (2.0 rules:
  speed x max(0.2, 1 + sum of effects), beacons x distribution_effectivity / sqrt(n)), turned into the full-speed
  rate of every product. "supported" scales the products down to what the machines upstream inside the design can
  feed, so a design with too few gear assemblers shows less than its science assemblers could make;
- per unit: area, machines, beacons, belts, inserters and poles per item/s of the products;
- patterns: direct insertion (machine to machine), long-handed reach over a belt, belts fed or tapped from both
  sides, belts that must carry two or more items (a machine with one input belt but several belt-fed ingredients),
  outputs dropped together on one belt, splitter-free designs, beacon sharing.

Recipe and machine data come from the solver's recipes.json (src/main/resources/recipes.json, or --data for a
modded export from tools/factorio-exporter). Furnaces have no recipe in a blueprint; pass --furnace-recipe if the
design smelts, otherwise they are counted but make nothing.
"""
import argparse
import base64
import json
import math
import os
import sys
import zlib
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_DATA = os.path.normpath(os.path.join(HERE, "..", "..", "src", "main", "resources", "recipes.json"))

# Footprints (w, h) of entities that recipes.json does not list, as placed facing north.
SIZES = {
    "splitter": (2, 1), "fast-splitter": (2, 1), "express-splitter": (2, 1), "turbo-splitter": (2, 1),
    "loader": (1, 2), "fast-loader": (1, 2), "express-loader": (1, 2), "turbo-loader": (1, 2),
    "medium-electric-pole": (1, 1), "small-electric-pole": (1, 1), "big-electric-pole": (2, 2), "substation": (2, 2),
    "storage-tank": (3, 3), "pump": (1, 2), "offshore-pump": (1, 2), "steam-engine": (3, 5), "steam-turbine": (3, 5),
    "lab": (3, 3), "biolab": (5, 5), "roboport": (4, 4), "radar": (3, 3), "rocket-silo": (9, 9),
    "electric-mining-drill": (3, 3), "big-mining-drill": (5, 5), "pumpjack": (3, 3), "accumulator": (2, 2),
    "solar-panel": (3, 3), "nuclear-reactor": (5, 5), "heat-exchanger": (3, 2), "agricultural-tower": (3, 3),
    "lightning-rod": (1, 1), "lightning-collector": (2, 2), "decider-combinator": (1, 2),
    "arithmetic-combinator": (1, 2), "selector-combinator": (1, 2), "display-panel": (1, 1),
    "infinity-chest": (1, 1), "linked-belt": (1, 1), "lane-splitter": (1, 1), "cargo-bay": (4, 4),
}
MACHINE_TYPES = {"assembling-machine", "furnace", "rocket-silo"}
BELT_TIERS = {"transport-belt": "yellow", "fast-transport-belt": "red", "express-transport-belt": "blue",
              "turbo-transport-belt": "green"}
UG_REACH = {"underground-belt": 5, "fast-underground-belt": 7, "express-underground-belt": 9,
            "turbo-underground-belt": 11}
POLES = {"small-electric-pole", "medium-electric-pole", "big-electric-pole", "substation"}
PIPES = {"pipe", "pipe-to-ground"}
CHESTS_SUFFIX = ("-chest",)
QUALITY_LEVEL = {"normal": 0, "uncommon": 1, "rare": 2, "epic": 3, "legendary": 5}
DIRS = [(0, -1), (1, 0), (0, 1), (-1, 0)]  # N E S W


# ---------------------------------------------------------------- decoding

def decode(text):
    """A blueprint string, blueprint JSON or factorio.school record -> the decoded JSON object."""
    text = text.strip()
    if text.startswith("{"):
        obj = json.loads(text)
        if "blueprintString" in obj:
            return decode(obj["blueprintString"])
        return obj
    raw = zlib.decompress(base64.b64decode(text[1:]))
    return json.loads(raw)


def read_input(arg):
    if arg == "-":
        return sys.stdin.read()
    if os.path.exists(arg):
        with open(arg, encoding="utf-8") as f:
            return f.read()
    return arg


def walk(obj, path=()):
    """Yields (path, blueprint) for every blueprint in a blueprint, book or list of them."""
    if "blueprint" in obj:
        yield path, obj["blueprint"]
    elif "blueprint_book" in obj:
        for i, child in enumerate(obj["blueprint_book"].get("blueprints", [])):
            idx = child.get("index", i)
            yield from walk(child, path + (idx,))


def book_label(obj):
    for k in ("blueprint", "blueprint_book", "upgrade_planner", "deconstruction_planner"):
        if k in obj:
            return obj[k].get("label", "")
    return ""


# ---------------------------------------------------------------- game data

class Data:
    def __init__(self, path):
        with open(path, encoding="utf-8") as f:
            d = json.load(f)
        self.recipes = d["recipes"]
        self.items = d["items"]
        self.entities = d["entities"]
        self.fluids = set(d.get("fluids", {}))

    def size(self, name):
        e = self.entities.get(name)
        if e and "width" in e:
            return e["width"], e["height"]
        return SIZES.get(name, (1, 1))

    def kind(self, name):
        e = self.entities.get(name)
        if e:
            return e.get("type", "")
        if name in BELT_TIERS:
            return "transport-belt"
        if name in UG_REACH:
            return "underground-belt"
        if name.endswith("splitter") and name != "lane-splitter":
            return "splitter"
        if name.endswith("loader"):
            return "loader"
        if name.endswith("inserter"):
            return "inserter"
        if name in POLES:
            return "electric-pole"
        if name in PIPES:
            return name
        if name.endswith(CHESTS_SUFFIX):
            return "container"
        return "other"

    def module_effects(self, name, quality):
        eff = dict(self.items.get(name, {}).get("module_effects", {}))
        mult = 1 + 0.3 * QUALITY_LEVEL.get(quality or "normal", 0)
        return {k: (v * mult if v > 0 else v) for k, v in eff.items()}


# ---------------------------------------------------------------- geometry

class Ent:
    __slots__ = ("i", "name", "kind", "x", "y", "w", "h", "dir", "raw", "tiles", "comp")

    def __init__(self, i, raw, data, dir_div):
        self.i = i
        self.raw = raw
        self.name = raw["name"]
        self.kind = data.kind(self.name)
        self.dir = (raw.get("direction", 0) // dir_div) % 4
        w, h = data.size(self.name)
        if self.dir in (1, 3):
            w, h = h, w
        self.w, self.h = w, h
        cx, cy = raw["position"]["x"], raw["position"]["y"]
        self.x = int(math.floor(cx - w / 2 + 1e-6))
        self.y = int(math.floor(cy - h / 2 + 1e-6))
        self.tiles = [(self.x + a, self.y + b) for a in range(w) for b in range(h)]
        self.comp = None

    @property
    def center(self):
        return self.x + self.w / 2, self.y + self.h / 2


def modules_of(raw):
    """[(module name, quality)] in an entity's own slots (2.0 and 1.1 formats)."""
    items = raw.get("items")
    out = []
    if isinstance(items, dict):  # 1.1: {"speed-module-3": 2}
        for n, c in items.items():
            out += [(n, "normal")] * int(c)
    elif isinstance(items, list):  # 2.0: [{"id": {...}, "items": {"in_inventory": [...]}}]
        for it in items:
            idd = it.get("id", {})
            slots = it.get("items", {}).get("in_inventory", [])
            n = sum(s.get("count", 1) for s in slots) or 1
            out += [(idd.get("name"), idd.get("quality", "normal"))] * n
    return [m for m in out if m[0] and "module" in m[0]]


# ---------------------------------------------------------------- analysis

def analyze(bp, data, product=None, furnace_recipe=None, research_prod=0.0):
    version = bp.get("version", 0)
    major = version >> 48
    dir_div = 4 if major >= 2 else 2
    ents = [Ent(i, e, data, dir_div) for i, e in enumerate(bp.get("entities", []))]
    at = {}
    for e in ents:
        for t in e.tiles:
            at[t] = e

    xs = [e.x for e in ents] + [e.x + e.w for e in ents]
    ys = [e.y for e in ents] + [e.y + e.h for e in ents]
    W, H = (max(xs) - min(xs), max(ys) - min(ys)) if ents else (0, 0)
    covered = len(at)

    machines = [e for e in ents if e.kind in MACHINE_TYPES]
    beacons = [e for e in ents if e.kind == "beacon"]
    inserters = [e for e in ents if e.kind == "inserter"]
    beltish = [e for e in ents if e.kind in ("transport-belt", "underground-belt", "splitter", "loader",
                                              "linked-belt", "lane-splitter")]
    for m in machines:
        if not m.raw.get("recipe") and m.kind == "furnace" and furnace_recipe:
            m.raw = dict(m.raw, recipe=furnace_recipe)

    # Beacons in reach of each machine.
    in_reach = defaultdict(list)
    served_by_beacon = Counter()
    for b in beacons:
        reach = data.entities.get(b.name, {}).get("supply_area_distance", {})
        r = reach.get(b.raw.get("quality", "normal"), reach.get("normal", 3)) if isinstance(reach, dict) else 3
        bx0, by0, bx1, by1 = b.x - r, b.y - r, b.x + b.w + r, b.y + b.h + r
        for m in machines:
            if m.x < bx1 and m.x + m.w > bx0 and m.y < by1 and m.y + m.h > by0:
                in_reach[m.i].append(b)
                served_by_beacon[b.i] += 1

    # Crafts per second of every machine.
    crafts = {}
    unknown_recipe = Counter()
    for m in machines:
        rname = m.raw.get("recipe")
        r = data.recipes.get(rname) if rname else None
        if not r:
            unknown_recipe[m.name] += 1
            continue
        me = data.entities.get(m.name, {})
        cs = me.get("crafting_speed", {})
        speed = cs.get(m.raw.get("quality", "normal"), cs.get("normal", 1)) if isinstance(cs, dict) else cs
        eff = Counter(me.get("effect_receiver", {}).get("base_effect", {}))
        for mod, q in modules_of(m.raw):
            eff.update(data.module_effects(mod, q))
        bs = in_reach.get(m.i, [])
        if bs:
            share = 1 / math.sqrt(len(bs))
            for b in bs:
                bq = QUALITY_LEVEL.get(b.raw.get("quality", "normal"), 0)
                be = data.entities.get(b.name, {})
                de = be.get("distribution_effectivity", 1.5) + be.get("distribution_effectivity_bonus_per_quality_level", 0.2) * bq
                for mod, q in modules_of(b.raw):
                    for k, v in data.module_effects(mod, q).items():
                        eff[k] += v * de * share
        allowed = r.get("allowed_effects", {})
        prod = max(0.0, eff.get("productivity", 0) + research_prod) if allowed.get("productivity", True) else 0.0
        prod = min(prod, r.get("maximum_productivity", 3))
        sp = max(0.2, 1 + eff.get("speed", 0))
        crafts[m.i] = (rname, speed * sp / r["energy"], prod)

    # Full-speed production and consumption per item.
    made = Counter()
    used = Counter()
    by_recipe = defaultdict(list)
    for mi, (rname, cps, prod) in crafts.items():
        r = data.recipes[rname]
        by_recipe[rname].append(mi)
        for p in r["products"]:
            amt = p.get("amount", (p.get("amount_min", 0) + p.get("amount_max", 0)) / 2) * p.get("probability", 1)
            made[p["name"]] += cps * amt * (1 + prod)
        for g in r["ingredients"]:
            used[g["name"]] += cps * g["amount"]

    # Products: given, or everything made and not consumed inside.
    if product:
        targets = [p.strip() for p in product.split(",")]
    else:
        targets = [i for i in made if made[i] > used.get(i, 0) + 1e-9 and used.get(i, 0) == 0]
    supported, bottleneck, inputs = supported_rates(data, targets, made, crafts, by_recipe)

    # ---- inserter links
    links = Counter()
    long_over_belt = 0
    di_pairs = 0
    pick_comp = defaultdict(set)   # machine i -> belt components it picks from
    drop_comp = defaultdict(set)   # component -> items dropped onto it
    di_items = defaultdict(set)    # machine i -> items it receives by direct insertion
    comp_of = belt_components(beltish, at, data)
    tap_sides = defaultdict(set)   # component -> axis-signed sides inserters stand on
    for ins in inserters:
        reach = 2 if "long" in ins.name else 1
        dx, dy = DIRS[ins.dir]
        px, py = ins.x + dx * reach, ins.y + dy * reach
        qx, qy = ins.x - dx * reach, ins.y - dy * reach
        src, dst = at.get((px, py)), at.get((qx, qy))
        sk, dk = kind_of(src), kind_of(dst)
        links[(sk, dk)] += 1
        if reach == 2 and kind_of(at.get((ins.x + dx, ins.y + dy))) == "belt" and sk == "belt":
            long_over_belt += 1
        if sk == "machine" and dk == "machine":
            di_pairs += 1
            if src.i in crafts:
                r = data.recipes[crafts[src.i][0]]
                di_items[dst.i].update(p["name"] for p in r["products"])
        if sk == "belt" and dk == "machine":
            c = comp_of.get(src.i)
            pick_comp[dst.i].add(c)
            tap_sides[c].add((dx, dy))
        if sk == "machine" and dk == "belt":
            c = comp_of.get(dst.i)
            tap_sides[c].add((-dx, -dy))
            if src.i in crafts:
                r = data.recipes[crafts[src.i][0]]
                drop_comp[c].update(p["name"] for p in r["products"] if p["name"] not in data.fluids)

    # Belts that carry two items: a machine fed by one belt component with 2+ belt-fed solid ingredients.
    multi_item_comps = {}
    for mi, comps in pick_comp.items():
        if mi not in crafts or len(comps) != 1:
            continue
        r = data.recipes[crafts[mi][0]]
        need = {g["name"] for g in r["ingredients"] if g.get("type") != "fluid" and g["name"] not in data.fluids}
        need -= di_items.get(mi, set())
        if len(need) >= 2:
            c = next(iter(comps))
            multi_item_comps[c] = multi_item_comps.get(c, set()) | need
    mixed_out = {c: s for c, s in drop_comp.items() if len(s) >= 2}
    two_sided = sum(1 for c, s in tap_sides.items() if c is not None and
                    any((-a, -b) in s for (a, b) in s))

    belts = Counter(e.name for e in ents if e.kind == "transport-belt")
    ugs = Counter(e.name for e in ents if e.kind == "underground-belt")
    splitters = Counter(e.name for e in ents if e.kind == "splitter")
    ins_c = Counter(e.name for e in inserters)
    poles = Counter(e.name for e in ents if e.kind == "electric-pole")
    pipes = Counter(e.name for e in ents if e.kind in PIPES)
    mach_c = Counter((e.name, e.raw.get("recipe") or "?") for e in machines)
    other = Counter(e.name for e in ents if e.kind not in MACHINE_TYPES and e.kind not in (
        "beacon", "inserter", "transport-belt", "underground-belt", "splitter", "electric-pole", "pipe",
        "pipe-to-ground"))

    total_out = sum(supported[t] for t in targets if t not in data.fluids)
    total_fluid = sum(supported[t] for t in targets if t in data.fluids)
    per = total_out or total_fluid or 0
    n_belt_tiles = sum(belts.values()) + sum(ugs.values()) + 2 * sum(splitters.values())

    m_with_beacons = [m for m in machines if in_reach.get(m.i)]
    patterns = []
    if di_pairs:
        patterns.append(f"direct insertion: {di_pairs} machine-to-machine inserters")
    if long_over_belt:
        patterns.append(f"long-handed over a belt: {long_over_belt} (two belts feed one row)")
    if two_sided:
        patterns.append(f"belts tapped from both sides: {two_sided} belt lines serve machines on both sides")
    if multi_item_comps:
        items = sorted({i for s in multi_item_comps.values() for i in s})
        patterns.append(f"belts carrying 2+ items: {len(multi_item_comps)} ({', '.join(items)})")
    if mixed_out:
        items = sorted({i for s in mixed_out.values() for i in s})
        patterns.append(f"outputs merged on one belt: {len(mixed_out)} ({', '.join(items)})")
    if not splitters and beltish:
        patterns.append("no splitters")
    if beacons:
        avg_b = sum(len(in_reach.get(m.i, [])) for m in machines) / max(1, len(machines))
        avg_m = sum(served_by_beacon.values()) / len(beacons)
        patterns.append(f"beacons: {avg_b:.1f} per machine, each reaches {avg_m:.1f} machines")
    if links.get(("chest", "machine")) or links.get(("machine", "chest")):
        patterns.append(f"chest buffers: {links.get(('chest', 'machine'), 0)} in, {links.get(('machine', 'chest'), 0)} out")

    return {
        "label": bp.get("label", ""),
        "version": f"{major}.{(version >> 32) & 0xffff}.{(version >> 16) & 0xffff}" if version else "?",
        "box": [W, H], "area": W * H, "covered_tiles": covered,
        "density": round(covered / (W * H), 3) if W * H else 0,
        "entities": len(ents),
        "machines": {f"{n} [{r}]": c for (n, r), c in sorted(mach_c.items())},
        "n_machines": len(machines),
        "beacons": len(beacons), "machines_in_beacon_reach": len(m_with_beacons),
        "belts": dict(belts), "undergrounds": dict(ugs), "splitters": dict(splitters),
        "belt_tiles": n_belt_tiles,
        "inserters": dict(ins_c), "n_inserters": len(inserters),
        "poles": dict(poles), "n_poles": sum(poles.values()),
        "pipes": dict(pipes), "other": dict(other),
        "unknown_recipe": dict(unknown_recipe),
        "products_full_speed": {t: round(made[t], 4) for t in targets},
        "products_supported": {t: round(supported[t], 4) for t in targets},
        "bottleneck": bottleneck,
        "inputs_per_s": {i: round(v, 3) for i, v in sorted(inputs.items())},
        "inserter_links": {f"{a}->{b}": c for (a, b), c in links.most_common()},
        "per_item_s": {
            "area": round(W * H / per, 2) if per else None,
            "machines": round(len(machines) / per, 3) if per else None,
            "beacons": round(len(beacons) / per, 3) if per else None,
            "belt_tiles": round(n_belt_tiles / per, 2) if per else None,
            "inserters": round(len(inserters) / per, 2) if per else None,
            "poles": round(sum(poles.values()) / per, 2) if per else None,
        },
        "area_per_machine": round(W * H / len(machines), 1) if machines else None,
        "patterns": patterns,
    }


def kind_of(e):
    if e is None:
        return "none"
    if e.kind in MACHINE_TYPES:
        return "machine"
    if e.kind in ("transport-belt", "underground-belt", "splitter", "loader", "linked-belt", "lane-splitter"):
        return "belt"
    if e.kind in ("container", "logistic-container", "infinity-container") or e.name.endswith("chest"):
        return "chest"
    return e.kind or "other"


def belt_components(beltish, at, data):
    """Belt entity index -> component id; belts join where one feeds the next (side-loading included)."""
    parent = {e.i: e.i for e in beltish}

    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a

    def union(a, b):
        parent[find(a)] = find(b)

    for e in beltish:
        dx, dy = DIRS[e.dir]
        ug_type = e.raw.get("type")
        if e.kind == "underground-belt" and ug_type == "input":
            reach = UG_REACH.get(e.name, 5)
            for k in range(1, reach + 1):
                o = at.get((e.x + dx * k, e.y + dy * k))
                if o and o.name == e.name and o.raw.get("type") == "output" and o.dir == e.dir:
                    union(e.i, o.i)
                    break
            continue
        for (tx, ty) in e.tiles:
            o = at.get((tx + dx, ty + dy))
            if o is None or o is e or o.i not in parent or o.dir == (e.dir + 2) % 4:
                continue  # nothing there, or head-on
            if o.kind == "underground-belt" and o.raw.get("type") == "output" and o.dir == e.dir:
                continue  # into the back of an underground exit: a dead end
            union(e.i, o.i)
    return {i: find(i) for i in parent}


def supported_rates(data, targets, made, crafts, by_recipe):
    """Products scaled by the largest factor (at most 1) that the machines inside can feed.

    A small linear program: crafts/s x_r of every recipe, at most what its machines make; for every item made
    inside, production minus consumption at least lambda times its full-speed rate if it is a product, else at
    least 0; maximise lambda. Items nothing inside makes are inputs and are not limited. By-products (refinery
    oils, cracking) are handled exactly, which a top-down demand walk gets wrong.
    Returns (products at the supported rate, the recipes that limit them, inputs/s at that rate).
    """
    recipes = sorted(by_recipe)
    cap = {r: sum(crafts[m][1] for m in by_recipe[r]) for r in recipes}
    prod_of = {r: crafts[by_recipe[r][0]][2] for r in recipes}
    net = defaultdict(lambda: defaultdict(float))  # item -> recipe -> items per craft
    for r in recipes:
        rec = data.recipes[r]
        for p in rec["products"]:
            amt = p.get("amount", (p.get("amount_min", 0) + p.get("amount_max", 0)) / 2) * p.get("probability", 1)
            net[p["name"]][r] += amt * (1 + prod_of[r])
        for g in rec["ingredients"]:
            net[g["name"]][r] -= g["amount"]
    inside = [i for i in net if made.get(i, 0) > 0]
    n = len(recipes) + 1  # x_r..., lambda
    rows, rhs = [], []
    for i in inside:  # -(net_i . x) + t_i * lambda <= 0
        rows.append([-net[i].get(r, 0.0) for r in recipes] + [made[i] if i in targets else 0.0])
        rhs.append(0.0)
    for k, r in enumerate(recipes):  # x_r <= cap_r
        rows.append([1.0 if j == k else 0.0 for j in range(n)])
        rhs.append(cap[r])
    rows.append([0.0] * len(recipes) + [1.0])  # lambda <= 1
    rhs.append(1.0)
    z = simplex_max([0.0] * len(recipes) + [1.0], rows, rhs)
    lam = z[-1]
    x = dict(zip(recipes, z[:-1]))
    limits = sorted(r for r in recipes if lam < 1 - 1e-6 and cap[r] > 0 and x[r] >= cap[r] * (1 - 1e-6)
                    and not any(p["name"] in targets for p in data.recipes[r]["products"]))
    inputs = {}
    for i, per in net.items():
        if made.get(i, 0) == 0:
            use = -sum(v * x[r] for r, v in per.items())
            if use > 1e-9:
                inputs[i] = use
    return {t: made.get(t, 0) * lam for t in targets}, ", ".join(limits) or None, inputs


def simplex_max(c, A, b):
    """max c.z subject to A z <= b, z >= 0, with every b >= 0 (so z = 0 is feasible). Bland's rule."""
    m, n = len(A), len(c)
    T = [list(A[i]) + [1.0 if j == i else 0.0 for j in range(m)] + [b[i]] for i in range(m)]
    obj = [-v for v in c] + [0.0] * m + [0.0]
    basis = [n + i for i in range(m)]
    eps = 1e-12
    for _ in range(5000):
        col = next((j for j in range(n + m) if obj[j] < -1e-10), None)
        if col is None:
            break
        best, row = None, None
        for i in range(m):
            if T[i][col] > eps:
                ratio = T[i][-1] / T[i][col]
                if best is None or ratio < best - 1e-12 or (abs(ratio - best) <= 1e-12 and basis[i] < basis[row]):
                    best, row = ratio, i
        if row is None:
            raise ValueError("unbounded")
        pv = T[row][col]
        T[row] = [v / pv for v in T[row]]
        for i in range(m):
            if i != row and abs(T[i][col]) > eps:
                f = T[i][col]
                T[i] = [a - f * bb for a, bb in zip(T[i], T[row])]
        f = obj[col]
        obj = [a - f * bb for a, bb in zip(obj, T[row])]
        basis[row] = col
    z = [0.0] * (n + m)
    for i, bv in enumerate(basis):
        z[bv] = T[i][-1]
    return z[:n]


# ---------------------------------------------------------------- output

def fmt(m):
    lines = []
    w, h = m["box"]
    lines.append(f"{m['label'] or '(no label)'}  (game {m['version']})")
    lines.append(f"  box {w} x {h} = {m['area']} tiles, {m['density']:.0%} covered, {m['entities']} entities")
    lines.append(f"  machines {m['n_machines']}: " + ", ".join(f"{c} {k}" for k, c in m["machines"].items()))
    if m["beacons"]:
        lines.append(f"  beacons {m['beacons']} ({m['machines_in_beacon_reach']} machines in reach)")
    lines.append(f"  belts {sum(m['belts'].values())} {fmt_c(m['belts'])}, undergrounds {sum(m['undergrounds'].values())}, "
                 f"splitters {sum(m['splitters'].values())} -> {m['belt_tiles']} belt tiles")
    lines.append(f"  inserters {m['n_inserters']} {fmt_c(m['inserters'])}; poles {m['n_poles']} {fmt_c(m['poles'])}")
    if m["pipes"]:
        lines.append(f"  pipes {fmt_c(m['pipes'])}")
    if m["other"]:
        lines.append(f"  other {fmt_c(m['other'])}")
    if m["unknown_recipe"]:
        lines.append(f"  no recipe set: {fmt_c(m['unknown_recipe'])} (furnaces: pass --furnace-recipe)")
    lines.append("  products/s full speed: " + ", ".join(f"{k} {v:g}" for k, v in m["products_full_speed"].items()))
    lines.append("  products/s supported:  " + ", ".join(f"{k} {v:g}" for k, v in m["products_supported"].items())
                 + (f"  (limited by {m['bottleneck']})" if m["bottleneck"] else ""))
    if m["inputs_per_s"]:
        lines.append("  inputs/s: " + ", ".join(f"{k} {v:g}" for k, v in m["inputs_per_s"].items()))
    p = m["per_item_s"]
    if p["area"] is not None:
        lines.append(f"  per item/s: {p['area']} tiles, {p['machines']} machines, {p['beacons']} beacons, "
                     f"{p['belt_tiles']} belt tiles, {p['inserters']} inserters, {p['poles']} poles")
    if m["area_per_machine"]:
        lines.append(f"  {m['area_per_machine']} tiles per machine")
    lines.append("  inserters: " + ", ".join(f"{k} {v}" for k, v in m["inserter_links"].items()))
    for pat in m["patterns"]:
        lines.append(f"  * {pat}")
    return "\n".join(lines)


def ascii_map(bp, data):
    """One character per tile: machines by recipe letter, beacons B, belts arrows, undergrounds u/U (in/out),
    splitters S, inserters i (long l, fast f, bulk/stack k) pointing nowhere, poles +, pipes =, chests C, other ?."""
    major = bp.get("version", 0) >> 48
    ents = [Ent(i, e, data, 4 if major >= 2 else 2) for i, e in enumerate(bp.get("entities", []))]
    if not ents:
        return ""
    x0, y0 = min(e.x for e in ents), min(e.y for e in ents)
    x1, y1 = max(e.x + e.w for e in ents), max(e.y + e.h for e in ents)
    grid = [[" "] * (x1 - x0) for _ in range(y1 - y0)]
    letters = {}
    legend = []
    for e in ents:
        k = kind_of(e)
        if k == "machine":
            r = e.raw.get("recipe") or e.name
            if r not in letters:
                letters[r] = "ABCDEFGHJKMNOPQRTVWXYZ"[len(letters) % 22]
                legend.append(f"{letters[r]}={r}")
            ch = letters[r]
        elif e.kind == "beacon":
            ch = "#"
        elif e.kind == "transport-belt":
            ch = "^>v<"[e.dir]
        elif e.kind == "underground-belt":
            ch = "u" if e.raw.get("type") == "input" else "U"
        elif e.kind == "splitter":
            ch = "S"
        elif e.kind == "inserter":
            ch = "l" if "long" in e.name else "f" if "fast" in e.name else "k" if ("bulk" in e.name or "stack" in e.name) else "i"
        elif e.kind == "electric-pole":
            ch = "+"
        elif e.kind in PIPES:
            ch = "="
        elif k == "chest":
            ch = "C"
        else:
            ch = "?"
        for (tx, ty) in e.tiles:
            grid[ty - y0][tx - x0] = ch
    head = f"origin ({x0},{y0}); " + ", ".join(legend) + "; # beacon, ^>v< belt, u/U underground in/out, S splitter, " \
        "i/l/f/k inserter (plain/long/fast/bulk), + pole, = pipe, C chest"
    return head + "\n" + "\n".join("".join(row).rstrip() for row in grid)


def fmt_c(c):
    return "(" + ", ".join(f"{v} {k}" for k, v in c.items()) + ")" if c else ""


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("blueprint", help="blueprint string, file, or - for stdin")
    ap.add_argument("--list", action="store_true", help="list the blueprints in a book")
    ap.add_argument("--pick", help="index path (e.g. 2/5) or a piece of the label of one blueprint in a book")
    ap.add_argument("--product", help="product(s) to measure, comma separated (default: made and not used inside)")
    ap.add_argument("--furnace-recipe", help="recipe for furnaces, which blueprints leave unset")
    ap.add_argument("--research-prod", type=float, default=0.0, help="research productivity added to every recipe")
    ap.add_argument("--data", default=DEFAULT_DATA, help="recipes.json (default: the solver's)")
    ap.add_argument("--json", action="store_true", help="print metrics as JSON")
    ap.add_argument("--map", action="store_true", help="also draw the blueprint as text, one character per tile")
    a = ap.parse_args(argv)

    obj = decode(read_input(a.blueprint))
    data = Data(a.data)
    bps = list(walk(obj))
    if a.list:
        print(f"{book_label(obj)}: {len(bps)} blueprints")
        for path, bp in bps:
            recipes = Counter(e.get("recipe") for e in bp.get("entities", []) if e.get("recipe"))
            top = ", ".join(f"{c} {r}" for r, c in recipes.most_common(4))
            v = bp.get("version", 0)
            print(f"  {'/'.join(map(str, path)) or '-':8} {v >> 48}.{(v >> 32) & 0xffff:<2} {len(bp.get('entities', [])):5d} ents  "
                  f"{bp.get('label', '')[:50]:50}  {top}")
        return
    if a.pick:
        want = tuple(int(x) for x in a.pick.split("/")) if all(x.isdigit() for x in a.pick.split("/")) else None
        sel = [(p, b) for p, b in bps if (p == want if want else a.pick.lower() in b.get("label", "").lower())]
        if not sel:
            sys.exit(f"nothing in the book matches {a.pick!r}")
        bps = sel[:1]
    elif len(bps) > 1:
        sys.exit(f"a book of {len(bps)} blueprints: use --list, then --pick")
    m = analyze(bps[0][1], data, a.product, a.furnace_recipe, a.research_prod)
    print(json.dumps(m, indent=1) if a.json else fmt(m))
    if a.map:
        print(ascii_map(bps[0][1], data))


if __name__ == "__main__":
    main()
