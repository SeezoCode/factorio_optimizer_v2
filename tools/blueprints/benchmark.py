#!/usr/bin/env python3
"""Community designs against the solver's layouts for the same jobs, as a markdown table.

    python3 tools/blueprints/benchmark.py docs/community_benchmark/designs.json --solves <dir> [--verify <dir>] [--json out.json]

designs.json lists each design: its factorio.school key and --pick path, and the solver job that matches it
(same products and rates, machine and belt tier, modules and beacons). For each one this fetches the blueprint
(fetch.py, cached), measures it (analyze.py), measures the solver's best layout in <solves>/<job name>/
best_output_string.txt, and, when <verify>/<id>.verify.log exists (verify.py's output on testcopy.py's test copy),
adds what the game made. Run the jobs first, e.g.

    for j in docs/community_benchmark/jobs/*.json; do ./optimize --config=$j --out=solves/$(basename $j .json) --plain; done
"""
import argparse
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import analyze as A  # noqa: E402
import fetch  # noqa: E402

REPO = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))


def measure_community(d, data):
    path, rec = fetch.get(d["key"])
    obj = A.decode(rec["blueprintString"])
    want = tuple(int(x) for x in d["pick"].split("/")) if d.get("pick") else ()
    bp = next(b for p, b in A.walk(obj) if p == want)
    m = A.analyze(bp, data, d.get("product"), d.get("furnace_recipe"))
    m["favorites"] = rec.get("numberOfFavorites", 0)
    return m


def measure_solver(job_path, solves, data):
    name = os.path.splitext(os.path.basename(job_path))[0]
    f = os.path.join(solves, name, "best_output_string.txt")
    if not os.path.exists(f):
        return None
    with open(f) as fh:
        obj = A.decode(fh.read())
    with open(os.path.join(REPO, job_path) if not os.path.isabs(job_path) else job_path) as fh:
        job = json.load(fh)
    product = ",".join(t["name"] for t in job["targets"])
    bp = next(b for _, b in A.walk(obj))
    return A.analyze(bp, data, product)


def read_verify(path):
    """(PASS/FAIL, {item: per s out}, short reason) from a verify.py log."""
    if not os.path.exists(path):
        return None
    with open(path, errors="replace") as f:
        text = f.read()
    verdict = "PASS" if re.search(r"^PASS\s*$", text, re.M) else ("FAIL" if re.search(r"^FAIL\s*$", text, re.M) else "?")
    outs = {}
    sec = re.search(r"^Outputs \(per second\):\n(.*?)\n\n", text, re.S | re.M)
    if sec:
        for line in sec.group(1).splitlines():
            port = re.match(r"^\s+([a-z0-9-]+)\s+at \([^)]*\)\s+(?:[a-z]+\s+)?([0-9.]+)\s+planned", line)
            if port:  # a planned port: "item at (x,y) edge  got  planned  want  pct"
                outs[port.group(1)] = outs.get(port.group(1), 0) + float(port.group(2))
            elif "(output)" in line or "(not a planned port)" in line:  # "(output) at (x,y) item v, item v"
                for item, v in re.findall(r"([a-z0-9-]+) ([0-9.]+)", line.split(") ", 1)[-1]):
                    outs[item] = outs.get(item, 0) + float(v)
    reasons = re.findall(r"^  - (.*)$", text, re.M)
    return verdict, outs, "; ".join(reasons)


def row_metrics(m):
    if m is None:
        return None
    rate = sum(v for k, v in m["products_supported"].items())
    return {"box": "%d×%d" % tuple(m["box"]), "area": m["area"], "machines": m["n_machines"], "beacons": m["beacons"],
            "belts": m["belt_tiles"], "inserters": m["n_inserters"], "poles": m["n_poles"], "rate": rate,
            "area_per": m["area"] / rate if rate else None,
            "di": sum(v for k, v in m["inserter_links"].items() if k == "machine->machine")}


def short(item):
    return item.replace("-science-pack", "").replace("electronic-circuit", "circuit").replace("-bar", "")


def verify_cell(v, products):
    if not v:
        return "–"
    got = [(k, x) for k, x in v[1].items() if k in products]
    rate = ", ".join(f"{short(k)} {x:.2f}" for k, x in got) or "nothing"
    return rate if v[0] == "PASS" else f"{rate} ✗"


def pattern_count(m, key):
    for p in m["patterns"]:
        if p.startswith(key):
            return int(re.search(r": (\d+)", p).group(1))
    return 0


PATTERN_COLUMNS = [
    lambda m: f"{m['n_inserters'] / max(1, m['n_machines']):.1f}",
    lambda m: f"{m['belt_tiles'] / max(1, m['n_machines']):.1f}",
    lambda m: sum(m["undergrounds"].values()),
    lambda m: sum(m["splitters"].values()),
    lambda m: pattern_count(m, "direct insertion"),
    lambda m: pattern_count(m, "long-handed"),
    lambda m: pattern_count(m, "belts carrying"),
    lambda m: pattern_count(m, "belts tapped"),
]


def pair(cm, sm, f):
    """'community / solver' for one column."""
    return f"{f(cm)} / {f(sm)}" if sm else f"{f(cm)} / –"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("designs")
    ap.add_argument("--solves", required=True, help="folder with one solver out folder per job name (and <job>.verify.log)")
    ap.add_argument("--verify", action="append", default=[],
                    help="LABEL=DIR: a folder of <id>.verify.log for the community test copies (repeatable)")
    ap.add_argument("--data", default=A.DEFAULT_DATA)
    ap.add_argument("--json", help="also write all metrics here")
    a = ap.parse_args(argv)
    data = A.Data(a.data)
    with open(a.designs) as f:
        designs = json.load(f)
    vdirs = [x.split("=", 1) for x in a.verify]
    t1 = ["| design | made /s | community box = area | machines | beacons | belt tiles | inserters (DI) | poles "
          "| solver box = area | machines | beacons | belt tiles | inserters (DI) | poles | solver ÷ community, area per item/s |",
          "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    t2 = ["| design | community in game: " + " | ".join(f"research {l}" for l, _ in vdirs) + " | solver in game |",
          "|---|" + "---|" * (len(vdirs) + 1)]
    t3 = ["| design | inserters per machine (C / S) | belt tiles per machine | undergrounds | splitters | direct insertion "
          "| long-handed over a belt | belts with 2+ items | belts served from both sides |",
          "|---|---|---|---|---|---|---|---|---|"]

    dump = []
    for d in designs:
        cm = measure_community(d, data)
        sm = measure_solver(d["job"], a.solves, data)
        c, s = row_metrics(cm), row_metrics(sm)
        products = set(cm["products_supported"])
        made = ", ".join(f"{short(k)} {v:.3g}" for k, v in cm["products_supported"].items())
        ratio = f"{s['area_per'] / c['area_per']:.2f}" if s and c["area_per"] and s["area_per"] else "–"
        sp = (f"{s['box']} = {s['area']} | {s['machines']} | {s['beacons']} | {s['belts']} | {s['inserters']} ({s['di']}) | {s['poles']}"
              if s else "– | | | | |")
        name = f"[{d['id']}]({d['url']}) {d['title']}"
        t1.append(f"| {name} | {made} | {c['box']} = {c['area']} | {c['machines']} | {c['beacons']} | {c['belts']} "
                  f"| {c['inserters']} ({c['di']}) | {c['poles']} | {sp} | {ratio} |")
        vs = [read_verify(os.path.join(vd, d["id"] + ".verify.log")) for _, vd in vdirs]
        job = os.path.splitext(os.path.basename(d["job"]))[0]
        sv = read_verify(os.path.join(a.solves, job + ".verify.log"))
        t2.append(f"| {d['id']} | " + " | ".join(verify_cell(v, products) for v in vs) + f" | {verify_cell(sv, products)} |")
        cells = [pair(cm, sm, f) for f in PATTERN_COLUMNS]
        t3.append(f"| {d['id']} | " + " | ".join(cells) + " |")
        dump.append({"design": d, "community": cm, "solver": sm, "verify": vs, "solver_verify": sv})
    print("\n".join(t1))
    print()
    print("\n".join(t2))
    print()
    print("\n".join(t3))
    if a.json:
        with open(a.json, "w") as f:
            json.dump(dump, f, indent=1)


if __name__ == "__main__":
    main()
