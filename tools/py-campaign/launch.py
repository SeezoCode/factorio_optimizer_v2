#!/usr/bin/env python3
"""The Py science campaign's runs: help levels, a queue, and a scheduler that keeps the CPU full.

    python3 tools/py-campaign/launch.py add <run> <layout> <level> [extra verify flags...]   # queue a run
    python3 tools/py-campaign/launch.py sched [--max N]     # start queued runs while fewer than N games run (loops)
    python3 tools/py-campaign/launch.py status              # every run: level, alive, game hour, verdict
    python3 tools/py-campaign/launch.py stop <run...>        # stop runs with their game servers

<layout> is a folder in solves/ with a solved layout (solves/<science>_<suffix> from solve_queue.sh); <run> becomes
solves/<run>. Levels go from the most help to none (see docs/CAMPAIGN_CONTEXT.md):

    L5 fed      every lack fed, every jam deleted: can the layout run at all
    L4 ideal    ideal transport (virtual robots, fluids too), every machine metered to its plan, surplus deleted
    L3 rules    belts as built; the check's flow rules by script (the rule set that passes logistic science)
    L2 mod      the same rules run by the factory-flow mod, as a player would install it
    L1 built    real parts only: overflow exits and voids built in game, pull from deciders on wires (no script rules)
    L0 plain    the blueprint as built, a warm start and loop starters during the warm-up, nothing else
"""
import json, os, re, subprocess, sys, time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
QUEUE = os.path.join(ROOT, "solves", "campaign_queue.json")

FAIR = "--prime-all --carrier-kit --seed-stop 7200 --real-voids --relief --relief-exits".split()
RULES = ("--flow --quota-window 1800 --loop-first --out-share --rate-cap 2 --cap-slow --cap-fill --loop-stock 2 "
         "--reserve 4 --loop-keep 2 --loop-share --loop-fluids --loop-keep-time 3600 --quota-catalyst --loop-short-on "
         "--loop-short-reach --loop-dead-end --seed-carrier-cut --share-wait-on --seed-own-wait --relief-own-jam "
         "--splitter-priority").split()
LEVELS = {
    "L5": ("fed", "--prime-full --feed-short full --void-full".split(), 3600, 3 * 3600),
    "L4": ("ideal", "--prime-full --flow --virtual-bots --vb-fluids --meter 1.02 --meter-start --void-full".split(),
           7200, 6 * 3600),
    "L3": ("rules", FAIR + RULES, 7200, 22 * 3600),
    "L2": ("mod", FAIR + ["--flow-mod"] + RULES[1:], 7200, 22 * 3600),
    # Real parts only: overflow exits, voids and pull built from deciders and enable conditions; no script flow rules.
    # (Until 2026-11-27 04:30 L1 was FAIR + --flow --no-pull --pull-built, whose quotas and gates are the check's script:
    # those runs are kept as level L1f.)
    # From 2026-11-27 05:50 also reserve gates from real parts past the last user of a loop's items and of items a
    # machine gives back; gates on every item (--reserve-items) locked py-1 from the start (py1_L1b). From 06:34 also
    # --self-exit-built: an exit carrying an item a machine gives back works only while it holds two crafts' worth
    # (py-1's moss farm lost half its coarse to the port belt until that backed up).
    "L1": ("built", FAIR + ["--pull-built", "--self-exit-built", "--reserve-built", "--reserve-self"], 7200, 22 * 3600),
    "L0": ("plain", ["--prime-all"], 7200, 22 * 3600),
}


def load():
    return json.load(open(QUEUE)) if os.path.exists(QUEUE) else []


def save(q):
    json.dump(q, open(QUEUE + ".tmp", "w"), indent=1)
    os.replace(QUEUE + ".tmp", QUEUE)


def games():
    """Factorio processes running now (one per live check)."""
    out = subprocess.run(["ps", "-eo", "command"], capture_output=True, text=True).stdout
    return sum(1 for l in out.splitlines() if "/bin/x64/factorio" in l and "--start-server" in l)


def solved(layout):
    """A solve queue logged the layout's end (solves/queue_*.log), or it was copied in by hand (solves/<layout>/SOLVED)."""
    d = os.path.join(ROOT, "solves")
    if os.path.exists(os.path.join(d, layout, "SOLVED")):
        return True
    for f in os.listdir(d):
        if f.startswith("queue_") and f.endswith(".log") and f"end solves/{layout} " in open(os.path.join(d, f)).read():
            return os.path.exists(os.path.join(d, layout, "test_output_string.txt"))
    return False


def flags_for(level, extra):
    name, flags, warm, meas = LEVELS[level]
    f = list(flags)
    if "--warmup" not in extra:
        f += ["--warmup", str(warm)]
    if "--measure" not in extra:
        f += ["--measure", str(meas)]
    return f + list(extra)


def start(item):
    flags = flags_for(item["level"], item["extra"])
    r = subprocess.run([os.path.join(ROOT, "tools/py-campaign/go.sh"), item["run"], item["layout"]] + flags,
                       capture_output=True, text=True)
    item["started"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    item["flags"] = flags
    with open(os.path.join(ROOT, "solves", item["run"], "level.txt"), "w") as f:
        f.write(item["level"] + "\n")
    print(r.stdout.strip(), r.stderr.strip())


def status():
    runs = sorted(d for d in os.listdir(os.path.join(ROOT, "solves")) if os.path.exists(os.path.join(ROOT, "solves", d, "verify.out")))
    live = subprocess.run(["ps", "-eo", "command"], capture_output=True, text=True).stdout
    for d in runs:
        p = os.path.join(ROOT, "solves", d)
        lvl = open(os.path.join(p, "level.txt")).read().strip() if os.path.exists(os.path.join(p, "level.txt")) else "?"
        out = open(os.path.join(p, "verify.out")).read()
        alive = ("solves/" + d + " ") in live
        verdict = "PASS" if re.search(r"^PASS", out, re.M) else ("FAIL" if re.search(r"^FAIL", out, re.M) else "")
        prog = [l for l in out.splitlines() if re.match(r"\s+(warmup|measuring|measure)", l)]
        print(f"{d:40s} {lvl:3s} {'live' if alive else 'done':4s} {verdict:4s} {prog[-1].strip()[:110] if prog else out.strip().splitlines()[-1][:110] if out.strip() else ''}")


def main():
    sys.stdout.reconfigure(line_buffering=True)
    cmd = sys.argv[1]
    if cmd == "add":
        run, layout, level, *extra = sys.argv[2:]
        assert level in LEVELS, level
        q = load()
        assert all(i["run"] != run for i in q) and not os.path.exists(os.path.join(ROOT, "solves", run)), "exists: " + run
        q.append({"run": run, "layout": layout, "level": level, "extra": extra, "started": None})
        save(q)
        print("queued", run)
    elif cmd == "sched":
        mx = int(sys.argv[sys.argv.index("--max") + 1]) if "--max" in sys.argv else os.cpu_count()
        while True:
            q = load()
            for item in q:
                if item["started"] is None:
                    if games() >= mx:
                        break  # keep the queue's order: wait for a free slot rather than start a later run
                    if not solved(item["layout"]):
                        continue  # layout not solved yet (a factory run writes previews before its final layout)
                    start(item)
                    q2 = load()  # runs may have been added meanwhile
                    for i in q2:
                        if i["run"] == item["run"]:
                            i.update(item)
                    save(q2)
                    q = q2
                    time.sleep(90)  # let the game come up before counting again
            time.sleep(30)
    elif cmd == "status":
        status()
    elif cmd == "stop":
        # Stop a run and its game server (killing verify.py alone leaves the server running and holding a slot).
        for run in sys.argv[2:]:
            pids = subprocess.run(["pgrep", "-f", f"verify.py solves/{run} "], capture_output=True, text=True).stdout.split()
            for pid in pids:
                subprocess.run(["pkill", "-P", pid])
                subprocess.run(["kill", pid])
            print(run, "stopped" if pids else "not running")


if __name__ == "__main__":
    main()
