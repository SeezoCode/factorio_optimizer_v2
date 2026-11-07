#!/usr/bin/env python3
"""Test copy of a community blueprint: feeds on the belts that enter it, sinks on the belts that leave it.

    python3 tools/blueprints/testcopy.py <blueprint string | file> [--pick PATH] [--out test.txt] [--product NAME]

Like the solver's test_output_string.txt, so tools/factorio-verify/verify.py can build and run it in the game:
- every belt line that starts open (nothing feeds it) and carries items that no machine inside makes gets a loader
  from an infinity chest of that item. When the same belt also carries items made inside (gears dropped next to
  copper), or two outside items, each item is side-loaded onto its own lane (an empty belt behind keeps the joint
  straight), on the lane the inside drops leave free;
- every belt line carrying a product (made and not used inside) that ends open gets a loader into a void chest.
What each belt carries is worked out from the recipes of the machines that take from it and drop onto it. Fluids are
not fed (designs with pipes in or out cannot be run this way yet). Prints what it fed and what it could not.
"""
import argparse
import base64
import json
import os
import sys
import zlib
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import analyze as A  # noqa: E402

LOADER = {"transport-belt": "loader", "fast-transport-belt": "fast-loader", "express-transport-belt": "express-loader",
          "turbo-transport-belt": "turbo-loader"}


def encode(obj):
    return "0" + base64.b64encode(zlib.compress(json.dumps(obj, separators=(",", ":")).encode(), 9)).decode()


def left(d):
    return (3 + d) % 4  # direction index to the left of d (N E S W)


DECOR = {"small-lamp", "constant-combinator", "display-panel", "decider-combinator", "arithmetic-combinator",
         "selector-combinator", "power-switch", "programmable-speaker"}


def build(bp, data, product=None, keep_decor=False):
    """(test blueprint object, report). Lamps and combinators are left out unless keep_decor (they mostly label
    a design and sit where feeds go); wires to them go with them."""
    dropped_decor = 0
    if not keep_decor:
        kept = [e for e in bp.get("entities", []) if e["name"] not in DECOR]
        dropped_decor = len(bp.get("entities", [])) - len(kept)
        ids = {e.get("entity_number") for e in kept}
        bp = dict(bp, entities=kept)
        if "wires" in bp:
            bp["wires"] = [w for w in bp["wires"] if w[0] in ids and w[2] in ids]
    major = bp.get("version", 0) >> 48
    dir_div = 4 if major >= 2 else 2
    ents = [A.Ent(i, e, data, dir_div) for i, e in enumerate(bp.get("entities", []))]
    at = {t: e for e in ents for t in e.tiles}
    m = A.analyze(bp, data, product)
    targets = set(m["products_supported"])
    beltish = [e for e in ents if A.kind_of(e) == "belt"]
    comp = A.belt_components(beltish, at, data)
    machines = {e.i: e for e in ents if e.kind in A.MACHINE_TYPES}

    def recipe(e):
        r = e.raw.get("recipe")
        return data.recipes.get(r) if r else None

    # Who takes from / drops onto each belt line.
    takes = defaultdict(set)      # machine -> components it takes from
    dropped = defaultdict(set)    # component -> items dropped onto it
    drop_lanes = defaultdict(lambda: {"L": 0, "R": 0})  # component -> inside drops per lane
    di = defaultdict(set)         # machine -> items it gets by direct insertion
    for ins in (e for e in ents if e.kind == "inserter"):
        reach = 2 if "long" in ins.name else 1
        dx, dy = A.DIRS[ins.dir]
        src, dst = at.get((ins.x + dx * reach, ins.y + dy * reach)), at.get((ins.x - dx * reach, ins.y - dy * reach))
        if A.kind_of(src) == "belt" and dst is not None and dst.i in machines:
            takes[dst.i].add(comp[src.i])
        elif src is not None and src.i in machines and recipe(src):
            items = {p["name"] for p in recipe(src)["products"] if p["name"] not in data.fluids}
            if A.kind_of(dst) == "belt":
                c = comp[dst.i]
                dropped[c] |= items
                # The inserter stands on one side of the belt; it drops onto the far lane.
                sx, sy = ins.x - dst.x, ins.y - dst.y
                side = ((sx > 0) - (sx < 0), (sy > 0) - (sy < 0))  # long inserters stand 2 tiles off
                lx, ly = A.DIRS[left(dst.dir)]
                if side == (lx, ly):
                    drop_lanes[c]["R"] += 1
                elif side == (-lx, -ly):
                    drop_lanes[c]["L"] += 1
            elif dst is not None and dst.i in machines:
                di[dst.i] |= items

    # Outside items each line must carry: the belt-fed ingredients of its takers that nothing drops onto it.
    need = defaultdict(set)  # machine -> outside items it takes from belts
    for mi, cs in takes.items():
        r = recipe(machines[mi])
        if not r:
            continue
        ing = {g["name"] for g in r["ingredients"] if g["name"] not in data.fluids} - di[mi]
        need[mi] = ing - set().union(*(dropped[c] for c in cs))
    # A machine taking from one line: that line carries all of it. Taking from several: what none of them is known
    # to carry goes on the one that carries nothing known yet (else on all of them).
    cand = defaultdict(set)
    for mi, cs in takes.items():
        if len(cs) == 1:
            cand[next(iter(cs))] |= need[mi]
    for _ in range(5):
        for mi, cs in takes.items():
            if len(cs) < 2:
                continue
            rest = need[mi] - set().union(*(cand[c] for c in cs))
            if rest:
                empty = [c for c in cs if not cand[c]]
                for c in (empty[:1] if len(empty) == 1 else (empty or cs)):
                    cand[c] |= rest

    # Underground exits that an entrance feeds.
    paired_out = set()
    for e in beltish:
        if e.kind == "underground-belt" and e.raw.get("type") == "input":
            dx, dy = A.DIRS[e.dir]
            for k in range(1, A.UG_REACH.get(e.name, 5) + 1):
                o = at.get((e.x + dx * k, e.y + dy * k))
                if o is not None and o.name == e.name and o.raw.get("type") == "output" and o.dir == e.dir:
                    paired_out.add(o.i)
                    break

    # Belt-family tiles that something pushes into.
    fed = defaultdict(list)  # tile -> directions items arrive from (as the pusher's travel dir)
    for e in beltish:
        if e.kind == "underground-belt" and e.raw.get("type") == "input":
            continue
        dx, dy = A.DIRS[e.dir]
        for (tx, ty) in e.tiles:
            fed[(tx + dx, ty + dy)].append(e.dir)
    occupied = set(at)
    added = []
    notes = []

    def free(*tiles):
        return all(t not in occupied for t in tiles)

    def put(name, tiles, d=None, extra=None):
        xs = [t[0] for t in tiles]
        ys = [t[1] for t in tiles]
        ent = {"name": name, "position": {"x": (min(xs) + max(xs) + 1) / 2, "y": (min(ys) + max(ys) + 1) / 2}}
        if d is not None and d != 0:
            ent["direction"] = d * dir_div
        ent.update(extra or {})
        added.append(ent)
        occupied.update(tiles)

    def chest(tile, item=None):
        s = {"remove_unfiltered_items": True}
        if item:
            s["filters"] = [{"index": 1, "name": item, "count": 50, "mode": "exactly"}]
        put("infinity-chest", [tile], extra={"infinity_settings": s})

    def step(t, d, k=1):
        dx, dy = A.DIRS[d]
        return (t[0] + dx * k, t[1] + dy * k)

    def loader_name(e):
        tier = e.name.replace("underground-belt", "transport-belt").replace("splitter", "transport-belt")
        return LOADER.get(tier, "express-loader")

    def feed_straight(tile, d, item, lname, belt_name):
        """Loader (and chest) behind the entry, further back along a straight belt when the near tiles are taken;
        else from either side (the entry becomes a curve, which keeps both lanes full)."""
        for k in range(0, 9):
            for away in ((d + 2) % 4, left(d), (left(d) + 2) % 4):
                into = (away + 2) % 4
                if away != (d + 2) % 4 and k == 0:
                    continue  # from the side, the loader needs a belt tile to curve into the entry
                ext = [step(tile, away, j) for j in range(1, k + 1)]
                a, b, c = step(tile, away, k + 1), step(tile, away, k + 2), step(tile, away, k + 3)
                if free(*ext, a, b, c):
                    for t in ext:
                        put(belt_name, [t], into)
                    put(lname, [a, b], into, {"type": "output"})
                    chest(c, item)
                    return True
        return False

    def feed_lanes(tile, d, lanes, belt_name, lname):
        """lanes: {"L": item, "R": item}. A straight belt back from the entry; k tiles back each item is side-loaded
        onto its lane by a loader beside it (a belt behind that tile keeps it straight, not a curve)."""
        back = (d + 2) % 4
        for k in range(1, 10):
            ext = [step(tile, back, j) for j in range(1, k + 2)]  # ext[k-1] is the joint, ext[k] keeps it straight
            joint = ext[k - 1]
            plan = []
            for lane, item in lanes.items():
                side = left(d) if lane == "L" else (left(d) + 2) % 4
                s0 = step(joint, side)
                plan.append((side, item, [s0, step(s0, side), step(s0, side, 2), step(s0, side, 3)]))
            if not free(*ext) or not all(free(*p[2]) for p in plan):
                continue
            for t in ext:
                put(belt_name, [t], d)
            for side, item, tiles in plan:
                into = (side + 2) % 4
                put(belt_name, [tiles[0]], into)
                put(lname, [tiles[1], tiles[2]], into, {"type": "output"})
                chest(tiles[3], item)
            return True
        return False

    def sink(tile, d, lname, belt_name):
        for k in range(0, 9):
            ext = [step(tile, d, j) for j in range(1, k + 1)]
            a, b, c = step(tile, d, k + 1), step(tile, d, k + 2), step(tile, d, k + 3)
            if free(*ext, a, b, c):
                for t in ext:
                    put(belt_name, [t], d)
                put(lname, [a, b], d, {"type": "input"})
                chest(c)
                return True
        return False

    report = {"feeds": [], "sinks": [], "skipped": [], "decor_removed": dropped_decor}
    seen_entry = set()
    for e in beltish:
        c = comp[e.i]
        items = sorted(cand.get(c, set()))
        dx, dy = A.DIRS[e.dir]
        for tile in e.tiles:
            # Entries: a line start nothing feeds. An underground exit is fed through the ground, unless no entrance
            # pairs with it (a tileable design's neighbour feeds it): then it takes a feed from behind like a belt.
            if e.kind == "underground-belt" and e.raw.get("type") == "output" and e.i in paired_out:
                continue
            arrivals = fed.get(tile, [])
            if e.kind == "splitter":
                arrivals = [a for t in e.tiles for a in fed.get(t, []) if a == e.dir]
            if arrivals or not items or (c, tile) in seen_entry:
                continue
            seen_entry.add((c, tile))
            lname = loader_name(e)
            belt = e.name.replace("underground-belt", "transport-belt").replace("splitter", "transport-belt")
            if e.kind == "underground-belt" and e.raw.get("type") == "output":
                # An exit takes items only through the ground: an entrance right behind it, the feed behind that.
                back = step(tile, (e.dir + 2) % 4)
                ok = free(back)
                if ok:
                    put(e.name, [back], e.dir, {"type": "input"})
                    if len(items) == 1 and not dropped[c]:
                        ok = feed_straight(back, e.dir, items[0], lname, belt)
                    else:
                        lanes = {"L": items[0], "R": items[1]} if len(items) == 2 else \
                            {"L" if drop_lanes[c]["L"] <= drop_lanes[c]["R"] else "R": items[0]} if len(items) == 1 else None
                        ok = bool(lanes) and feed_lanes(back, e.dir, lanes, belt, lname)
                how = "underground entrance + loader"
            elif len(items) == 1 and not dropped[c]:
                ok = feed_straight(tile, e.dir, items[0], lname, belt)
                how = "loader"
            else:
                inside = drop_lanes[c]
                if len(items) == 1:
                    lanes = {"L" if inside["L"] <= inside["R"] else "R": items[0]}
                elif len(items) == 2:
                    lanes = {"L": items[0], "R": items[1]}
                else:
                    lanes = None
                ok = bool(lanes) and feed_lanes(tile, e.dir, lanes, belt, lname)
                how = f"lanes {lanes}"
            (report["feeds"] if ok else report["skipped"]).append(
                {"at": list(tile), "items": items, "how": how if ok else f"no room/plan ({how})"})
        # Exits: a line carrying a product whose end pushes into nothing that takes belts.
        if not (dropped[c] & targets):
            continue
        if e.kind == "underground-belt" and e.raw.get("type") == "input":
            continue
        for tile in e.tiles:
            nxt = (tile[0] + dx, tile[1] + dy)
            o = at.get(nxt)
            if o is not None and A.kind_of(o) == "belt" and o.dir != (e.dir + 2) % 4:
                continue
            if o is not None:
                continue  # pushes into a machine or chest side: not an open end
            belt = e.name.replace("underground-belt", "transport-belt").replace("splitter", "transport-belt")
            if sink(tile, e.dir, loader_name(e), belt):
                report["sinks"].append({"at": list(tile), "items": sorted(dropped[c] & targets)})
            else:
                report["skipped"].append({"at": list(tile), "items": sorted(dropped[c]), "how": "no room to sink"})

    out = dict(bp)
    ents_out = [dict(e) for e in bp["entities"]]
    n = max((e.get("entity_number", 0) for e in ents_out), default=0)
    for k, e in enumerate(added):
        ents_out.append(dict(e, entity_number=n + k + 1))
    out["entities"] = ents_out
    out["label"] = (bp.get("label") or "blueprint") + " (test feeds)"
    report["planned"] = m["products_supported"]
    report["inputs_per_s"] = m["inputs_per_s"]
    return {"blueprint": out}, report


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("blueprint")
    ap.add_argument("--pick", help="blueprint in a book, as analyze.py --list shows")
    ap.add_argument("--product", help="product(s), comma separated (default: made and not used inside)")
    ap.add_argument("--data", default=A.DEFAULT_DATA)
    ap.add_argument("--out", help="write the test blueprint string here (default: print it)")
    ap.add_argument("--keep-decor", action="store_true", help="keep lamps and combinators (default: left out)")
    a = ap.parse_args(argv)
    obj = A.decode(A.read_input(a.blueprint))
    data = A.Data(a.data)
    bps = list(A.walk(obj))
    if a.pick:
        want = tuple(int(x) for x in a.pick.split("/")) if all(x.isdigit() for x in a.pick.split("/")) else None
        bps = [(p, b) for p, b in bps if (p == want if want else a.pick.lower() in b.get("label", "").lower())][:1]
    if len(bps) != 1:
        sys.exit("pick one blueprint (--pick, see analyze.py --list)")
    bp, report = build(bps[0][1], data, a.product, a.keep_decor)
    s = encode(bp)
    if a.out:
        with open(a.out, "w") as f:
            f.write(s)
        with open(os.path.splitext(a.out)[0] + "_feeds.json", "w") as f:
            json.dump(report, f, indent=1)
    else:
        print(s)
    for f in report["feeds"]:
        print(f"feed {f['items']} at {f['at']}: {f['how']}", file=sys.stderr)
    for f in report["sinks"]:
        print(f"sink {f['items']} at {f['at']}", file=sys.stderr)
    for f in report["skipped"]:
        print(f"SKIPPED {f['items']} at {f['at']}: {f['how']}", file=sys.stderr)
    print(f"planned products/s: {report['planned']}", file=sys.stderr)


if __name__ == "__main__":
    main()
