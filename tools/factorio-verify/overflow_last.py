"""overflow_last.py <run folder> <new folder> [--stacks N]

An experiment on a robot-built factory's test blueprint: every machine's output inserter that leads only to a block's
overflow chest gets a logistic condition, "more than N stacks of the item in the network" (default 2), so the surplus
exit takes nothing until the stock its users are served from is well filled.

As built, a maker has two output inserters, one toward its provider chest and one toward the overflow chest, and they
share what is made: Py logistic science's reduced tin, planned to overflow one item in 60 hours, lost nine of fourteen
to the three belts before its overflow chest while the molten tin machine waited.

Writes <new folder>/test_output_string.txt and links the rest of the run's files. Prints what it changed.
"""
import base64
import json
import os
import sys
import zlib

DIRS = {0: (0, -1), 4: (1, 0), 8: (0, 1), 12: (-1, 0)}


def main():
    src, dst = sys.argv[1], sys.argv[2]
    stacks = int(sys.argv[sys.argv.index("--stacks") + 1]) if "--stacks" in sys.argv else 2
    data = json.load(open("data/pyanodon.json"))
    items = data["items"]
    raw = open(os.path.join(src, "test_output_string.txt")).read().strip()
    bp = json.loads(zlib.decompress(base64.b64decode(raw[1:])))
    es = bp["blueprint"]["entities"]
    ports = json.load(open(os.path.join(src, "ports.json")))
    provided = {p["item"] for p in ports["bots"]["providers"]}

    def tile(e):
        return (int(e["position"]["x"] // 1), int(e["position"]["y"] // 1))

    belts, chests, inserters, other = {}, {}, [], {}
    for e in es:
        n = e["name"]
        if n.endswith("transport-belt") or n.endswith("underground-belt") or n.endswith("splitter") or n.endswith("loader"):
            belts[tile(e)] = e
        elif n.endswith("chest"):
            chests[tile(e)] = e
        elif n.endswith("inserter"):
            inserters.append(e)
    def reach(e):
        return 2 if e["name"].startswith("long-handed") else 1
    def pick(e):
        dx, dy = DIRS[e.get("direction", 0)]
        x, y = tile(e)
        return (x + dx * reach(e), y + dy * reach(e))
    def drop(e):
        dx, dy = DIRS[e.get("direction", 0)]
        x, y = tile(e)
        return (x - dx * reach(e), y - dy * reach(e))

    picks_from, drops_onto = {}, {}
    for i in inserters:
        picks_from.setdefault(pick(i), []).append(i)
        drops_onto.setdefault(drop(i), []).append(i)

    def upstream(t):
        """The belts that run onto the belt at [t]."""
        out = []
        b = belts[t]
        if b["name"].endswith("underground-belt") and b.get("type") == "output":
            dx, dy = DIRS[b.get("direction", 0)]
            for k in range(1, 12):
                u = (t[0] - dx * k, t[1] - dy * k)
                ub = belts.get(u)
                if ub and ub["name"] == b["name"] and ub.get("direction", 0) == b.get("direction", 0) and ub.get("type") == "input":
                    out.append(u)
                    break
            return out
        for d, (dx, dy) in DIRS.items():
            u = (t[0] - dx, t[1] - dy)
            ub = belts.get(u)
            if ub and ub.get("direction", 0) == d and not (ub["name"].endswith("underground-belt") and ub.get("type") == "input"):
                out.append(u)
        return out

    port_item = {(o["x"], o["y"]): o["item"] for o in ports["outputs"] if o.get("local") and not o.get("fluid")}
    changed, skipped = [], []
    for ct, c in chests.items():
        if c["name"] != "infinity-chest":
            continue
        feeders = [i for i in drops_onto.get(ct, []) if pick(i) in belts]
        if len(feeders) != 1:
            continue
        gate = feeders[0]
        chain, queue = {pick(gate)}, [pick(gate)]
        while queue:
            for u in upstream(queue.pop()):
                if u not in chain:
                    chain.add(u)
                    queue.append(u)
        item = next((port_item[t] for t in chain if t in port_item), None)
        users = [i for t in chain for i in picks_from.get(t, []) if i is not gate]
        makers = [i for t in chain for i in drops_onto.get(t, []) if pick(i) not in belts and pick(i) not in chests]
        if item is None or users or not makers or item not in provided or len(chain) > 60:
            skipped.append((ct, item, len(users), len(makers), len(chain)))
            continue
        n = stacks * items[item]["stack_size"]
        for i in makers:
            if "control_behavior" in i:
                skipped.append((ct, item, "inserter already has a condition", 0, len(chain)))
                continue
            i["control_behavior"] = {"connect_to_logistic_network": True,
                                     "logistic_condition": {"first_signal": {"type": "item", "name": item}, "constant": n, "comparator": ">"}}
            changed.append((item, n, len(chain), tile(i)))

    os.makedirs(dst, exist_ok=True)
    for f in os.listdir(src):
        if f in ("plan.json", "plan_rebalanced.json", "ports.json", "resume"):
            if not os.path.lexists(os.path.join(dst, f)):
                os.symlink(os.path.relpath(os.path.realpath(os.path.join(src, f)), dst), os.path.join(dst, f))
    out = "0" + base64.b64encode(zlib.compress(json.dumps(bp).encode(), 9)).decode()
    open(os.path.join(dst, "test_output_string.txt"), "w").write(out)
    print(f"{len(changed)} output inserters now wait for more than {stacks} stacks in the network; {len(skipped)} overflow chests left as they were")
    belts_before = sum(c[2] for c in changed)
    print(f"belts before those overflow chests: {belts_before} tiles, room for about {belts_before * 8} items")
    for c in sorted(changed)[:400]:
        print("  %-34s > %-5d %2d belts  inserter at %s" % c)
    for s in skipped:
        print("  left:", s)


main()
