#!/usr/bin/env python3
"""Solves jobs and checks each in the real game: does a change make layouts smaller, and do they still make their rates?

    python3 tools/factorio-verify/bench.py docs/community_benchmark/jobs/red_1p5_am2.json docs/community_benchmark/jobs/green_1_am1.json
    python3 tools/factorio-verify/bench.py --label duos --tuning '{"place": {"duos": true}}' docs/community_benchmark/jobs/circuit_*.json
    python3 tools/factorio-verify/bench.py --budget 120 --threads 4 --out bench_runs jobs/*.json

Each job is copied with the tuning overrides merged into its "tuning" section (and --budget/--threads into "search"),
solved with ./optimize into <out>/<job>_<label>, and its test copy built in the game with verify.py (outputs taken at
their planned rate). One line per job goes to <out>/results.tsv and the screen: box, belt tiles, undergrounds,
inserters (and how many hand straight from machine to machine), what came out in game, and PASS or why not.
"""
import argparse
import glob
import json
import os
import subprocess
import sys
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))


def merge(a, b):
    for k, v in b.items():
        if isinstance(v, dict) and isinstance(a.get(k), dict):
            merge(a[k], v)
        else:
            a[k] = v
    return a


def metrics(out):
    """Box and part counts of a run's best layout."""
    path = os.path.join(out, "best_blueprint.json")
    if not os.path.exists(path):
        return {}
    ents = json.load(open(path))["blueprint"]["entities"]
    names = Counter(e["name"] for e in ents)
    xs = [e["position"]["x"] for e in ents]
    ys = [e["position"]["y"] for e in ents]
    belts = sum(c for n, c in names.items() if n.endswith("transport-belt"))
    ugs = sum(c for n, c in names.items() if n.endswith("underground-belt"))
    inserters = sum(c for n, c in names.items() if n.endswith("inserter"))
    machines = sum(1 for e in ents if e.get("recipe"))
    return {"box": f"{round(max(xs) - min(xs) + 1)}x{round(max(ys) - min(ys) + 1)}", "machines": machines,
            "belts": belts, "undergrounds": ugs, "inserters": inserters}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("jobs", nargs="+", help="job files (globs are expanded)")
    ap.add_argument("--label", default="run", help="suffix of each run's folder (default run)")
    ap.add_argument("--tuning", default="{}", help="tuning overrides as JSON, merged into each job's tuning section")
    ap.add_argument("--budget", type=float, help="search.time_budget_s for every job")
    ap.add_argument("--threads", type=int, help="search.threads for every job")
    ap.add_argument("--out", default=os.path.join(REPO, "solves", "bench"), help="where runs and results.tsv go")
    ap.add_argument("--warmup", type=float, default=600)
    ap.add_argument("--measure", type=float, default=600)
    ap.add_argument("--no-game", action="store_true", help="solve only")
    args = ap.parse_args()
    tuning = json.loads(args.tuning)
    os.makedirs(args.out, exist_ok=True)
    jobs = [j for pattern in args.jobs for j in (sorted(glob.glob(pattern)) or [pattern])]
    results = open(os.path.join(args.out, "results.tsv"), "a")
    for path in jobs:
        job = json.load(open(path))
        merge(job.setdefault("tuning", {}), tuning)
        if args.budget:
            job.setdefault("search", {})["time_budget_s"] = args.budget
        if args.threads:
            job.setdefault("search", {})["threads"] = args.threads
        name = os.path.splitext(os.path.basename(path))[0] + "_" + args.label
        out = os.path.join(args.out, name)
        cfg = os.path.join(args.out, name + ".json")
        json.dump(job, open(cfg, "w"), indent=1)
        print(f"{name}: solving", flush=True)
        with open(out + ".log", "w") as log:
            subprocess.run([os.path.join(REPO, "optimize"), f"--config={cfg}", f"--out={out}", "--plain"], cwd=REPO, stdout=log, stderr=subprocess.STDOUT)
        m = metrics(out)
        game, verdict = "", "not checked"
        if not args.no_game and m:
            print(f"{name}: {m['box']}; checking in game", flush=True)
            r = subprocess.run([sys.executable, os.path.join(HERE, "verify.py"), out, "--job", cfg, "--warmup", str(args.warmup), "--measure", str(args.measure), "--dump"],
                               cwd=REPO, capture_output=True, text=True)
            with open(out + ".verify", "w") as f:
                f.write(r.stdout + r.stderr)
            lines = [l for l in r.stdout.splitlines() if l.strip()]
            verdict = "PASS" if lines and lines[-1].strip() == "PASS" else "; ".join(l.strip(" -") for l in lines[-3:] if l.startswith("  - ")) or "FAIL"
            rep = os.path.join(out, "verify_report.json")
            if os.path.exists(rep):
                per = Counter()
                for o in json.load(open(rep)).get("outputs", []):
                    for k, v in o.get("per_s", {}).items():
                        per[k] += v
                game = " ".join(f"{k}={v:.3f}" for k, v in per.items())
        row = [name, m.get("box", "no layout"), str(m.get("machines", "")), str(m.get("belts", "")), str(m.get("undergrounds", "")),
               str(m.get("inserters", "")), game, verdict]
        results.write("\t".join(row) + "\n")
        results.flush()
        print("\t".join(row), flush=True)


if __name__ == "__main__":
    main()
