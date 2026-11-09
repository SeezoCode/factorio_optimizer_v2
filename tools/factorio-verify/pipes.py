#!/usr/bin/env python3
"""Pipe audit of an in-game check: every machine fluid box (as the game connects it: verify_report.json's
"fluids" -> "conns") whose pipe holds another fluid, and boxes the recipe uses that no pipe reaches.

    python3 tools/factorio-verify/pipes.py <out>

A mismatch means the optimizer's idea of where a machine's connections are differs from the game's (Py science 1:
a soil extractor's second water connection under a sulfuric acid pipe).
"""
import argparse
import json
import os

ap = argparse.ArgumentParser()
ap.add_argument("out")
a = ap.parse_args()
r = json.load(open(os.path.join(a.out, "verify_report.json")))
pipe_at = {(q["x"], q["y"]): q for q in r.get("dump_pipes", [])}
wrong, unreached, n = [], [], 0
for m in r["machines"]:
    for b in m.get("fluids") or []:
        conns = [c for c in b.get("conns") or [] if "x" in c]
        if not b.get("name") or not conns:
            continue
        n += 1
        linked = [c for c in conns if c.get("linked")]
        for c in linked:
            q = pipe_at.get((c["x"], c["y"]))
            if q and q.get("f") and q["f"] != b["name"]:
                wrong.append(f"{m['recipe']} @({m['x']},{m['y']}) {b['io']} {b['name']} joins a pipe of {q['f']} at ({c['x']},{c['y']})")
        if not linked and b.get("amount", 0) == 0 and b["io"] == "input":
            unreached.append(f"{m['recipe']} @({m['x']},{m['y']}) input {b['name']}: no pipe on any of {[(c['x'], c['y']) for c in conns]}")
print(f"{n} fluid boxes checked")
print(f"{len(wrong)} joined to a pipe of another fluid" + (":" if wrong else ""))
for w in wrong:
    print("  " + w)
print(f"{len(unreached)} empty inputs with no pipe" + (":" if unreached else ""))
for u in unreached[:30]:
    print("  " + u)
