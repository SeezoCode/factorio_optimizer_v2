#!/usr/bin/env python3
"""Each block of a factory in game on its own, looking for machines that lock up.

A factory's in-game check (verify.py) only shows the first thing that stops it: everything after waits on that. Each
block's test copy (block_N/test_output_string.txt) gets every input from an infinity chest and every output taken off
(without limit unless --capped), so a machine standing with a full output there is stuck by its own wiring, not by
what other blocks do: most often a
machine that gives back what it takes (Py's catalysts) whose returns cannot get back to it. This runs verify.py on
every block (a few at a time) and lists, per block:

- machines standing with a full output for most of the measured time, and among them those holding one of their own
  ingredients in their output (a returned catalyst that cannot go back: a deadlock),
- machines that crafted nothing while measured though their plan has them craft.

    python3 tools/factorio-verify/blocks.py solves/<run> --job jobs/<job>.json [--parallel 3] [--flow]

Writes each block's check to block_N/verify.txt (and its report to block_N/verify_report.json), the summary to
<run>/blocks_check.txt. Exit code 1 when some block has a deadlocked machine.
"""
import argparse
import concurrent.futures
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def blocks_of(run):
    out = []
    for name in os.listdir(run):
        m = re.fullmatch(r"block_(\d+)", name)
        if m and os.path.exists(os.path.join(run, name, "test_output_string.txt")):
            out.append(int(m.group(1)))
    return sorted(out)


def check_block(run, b, args):
    d = os.path.join(run, f"block_{b}")
    cmd = [sys.executable, os.path.join(HERE, "verify.py"), d, "--warmup", str(args.warmup), "--measure", str(args.measure),
           "--machines"]
    if args.job:
        cmd += ["--job", args.job]
    if args.flow:
        cmd.append("--flow")
    if not args.capped:
        cmd.append("--uncapped")
    with open(os.path.join(d, "verify.txt"), "w") as f:
        p = subprocess.run(cmd, stdout=f, stderr=subprocess.STDOUT, timeout=args.timeout)
    return b, p.returncode


def judge_block(run, b):
    """(machines, full, deadlocked [(recipe, x, y, items)], idle [(recipe, x, y)]) or None when no report."""
    d = os.path.join(run, f"block_{b}")
    rp = os.path.join(d, "verify_report.json")
    if not os.path.exists(rp):
        return None
    report = json.load(open(rp))
    planned = {}
    try:
        plan = json.load(open(os.path.join(d, "plan.json")))
        for m in plan.get("machines", []):
            planned[m["recipe"]] = planned.get(m["recipe"], 0) + 1
    except (OSError, ValueError):
        pass
    measured = report.get("measured_s") or 1
    full, deadlocked, idle = [], [], []
    for m in report["machines"]:
        statuses = m.get("statuses") or {}
        n = sum(statuses.values()) or 1
        if statuses.get("full_output", 0) / n >= 0.5:
            full.append(m)
            own = sorted(set((m.get("output") or {}).keys()) & set((m.get("input") or {}).keys()))
            if own:
                deadlocked.append((m["recipe"], m["x"], m["y"], own))
        if m.get("crafts", 0) == 0 and m["recipe"] in planned and m.get("status") != "working":
            idle.append((m["recipe"], m["x"], m["y"], m.get("status")))
    return len(report["machines"]), full, deadlocked, idle


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("run", help="a factory run's out folder (with block_N folders)")
    ap.add_argument("--job", help="job file (default <run>/resume/job.json)")
    ap.add_argument("--parallel", type=int, default=3, help="blocks checked at once (default 3)")
    ap.add_argument("--warmup", type=float, default=1800, help="game seconds before measuring (default 1800)")
    ap.add_argument("--measure", type=float, default=600, help="game seconds measured (default 600)")
    ap.add_argument("--flow", action="store_true", help="the check's flow control (verify.py --flow)")
    ap.add_argument("--capped", action="store_true",
                    help="outputs taken at their planned rate (default: without limit, so a machine standing full is stuck "
                         "by its own block, not by making more than the plan takes off)")
    ap.add_argument("--only", help="comma-separated block numbers")
    ap.add_argument("--timeout", type=int, default=3600, help="seconds one block may take (default 3600)")
    ap.add_argument("--report-only", action="store_true", help="only summarize the block reports already there")
    args = ap.parse_args()
    if not args.job and os.path.exists(os.path.join(args.run, "resume", "job.json")):
        args.job = os.path.join(args.run, "resume", "job.json")
    bs = blocks_of(args.run)
    if args.only:
        want = {int(x) for x in args.only.split(",")}
        bs = [b for b in bs if b in want]
    if not args.report_only:
        with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.parallel)) as pool:
            for fut in concurrent.futures.as_completed([pool.submit(check_block, args.run, b, args) for b in bs]):
                try:
                    b, code = fut.result()
                    print(f"block {b}: checked (exit {code})", flush=True)
                except Exception as e:  # noqa: BLE001 (a block that fails to run is reported, the others go on)
                    print(f"a block check failed: {e}", flush=True)
    lines, any_dead = [], False
    for b in bs:
        r = judge_block(args.run, b)
        if r is None:
            lines.append(f"block {b}: no report")
            continue
        n, full, dead, idle = r
        any_dead |= bool(dead)
        lines.append(f"block {b}: {n} machines, {len(full)} standing full, {len(dead)} deadlocked, {len(idle)} crafted nothing while measured")
        for recipe, x, y, items in dead:
            lines.append(f"  DEADLOCK {recipe} at ({x},{y}): its output holds its own ingredient {', '.join(items)}")
        for m in full:
            if not any(m["recipe"] == d[0] and m["x"] == d[1] and m["y"] == d[2] for d in dead):
                lines.append(f"  full {m['recipe']} at ({m['x']},{m['y']}): output {m.get('output')}")
        for recipe, x, y, st in idle[:10]:
            lines.append(f"  idle {recipe} at ({x},{y}): {st}")
        if len(idle) > 10:
            lines.append(f"  ... {len(idle) - 10} more idle")
    text = "\n".join(lines)
    print(text)
    with open(os.path.join(args.run, "blocks_check.txt"), "w") as f:
        f.write(text + "\n")
    sys.exit(1 if any_dead else 0)


if __name__ == "__main__":
    main()
