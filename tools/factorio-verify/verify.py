#!/usr/bin/env python3
"""Builds a solver run's test blueprint in the real game, runs it and prints a pass/fail report.

    python3 tools/factorio-verify/verify.py out                    # a run's out folder (test_output_string.txt)
    python3 tools/factorio-verify/verify.py out --warmup 600 --measure 300
    python3 tools/factorio-verify/verify.py some_blueprint.txt --mods Krastorio2 --no-space-age

The blueprint is the run's "test copy" (test_output_string.txt): the factory as built plus a loader from an infinity
chest on every input port and a loader into an infinity chest on every output port. Output chests are emptied at each item's planned rate (--uncapped: without limit), so
a full output belt backs up as it would at a real consumer. The verify mod in this folder
builds it on a lab-tile surface of a fresh game (every ghost revived, modules inserted), gives each electric network an
energy interface, lets it warm up, then measures: per machine its usual status, crafts and crafting progress, the items
per second leaving at each output and taken at each input, fluids drained at fluid outputs, and inserters and belts
that never moved. It passes when everything was built, every machine crafted (a spare one may idle when every output
keeps its rate), each output reached its planned rate
(ports.json, within --tolerance) and no inserter stayed stuck holding an item (waiting for room on a backed-up belt
or in a machine that has enough does not count: surplus backs up). Belts that never moved are listed but do
not fail it: a belt's end past its last inserter stands still in a working factory too.

The game runs as a headless server (no window) with a data folder and mod folder of its own, like export.py next door,
and is driven over RCON. Mods: the job's "data" file (resume/job.json in the out folder) names them; --mods and
--no-space-age override. The job's research (inserter capacity, recipe productivity) and belt_stack_size are applied.
The full report (every machine) is written to <out>/verify_report.json.
"""
import argparse
import json
import os
import random
import shutil
import signal
import socket
import string
import struct
import subprocess
import sys
import tempfile
import time
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "factorio-exporter"))
try:
    from export import BINARY, BUILT_IN, USER_DIR, installed_mods, mod_info, required  # noqa: E402
except ImportError:
    sys.exit("verify.py uses tools/factorio-exporter/export.py (where the game is, which mods are installed)")

REPORT = "factorio-optimizer-verify.json"
CHUNK = 3000  # blueprint characters per RCON command


class Rcon:
    """Minimal Source RCON client (what Factorio speaks)."""

    def __init__(self, port, password, timeout=120):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=timeout)
        self.next_id = 1
        if self._request(3, password)[0] == -1:
            raise RuntimeError("RCON authentication failed")

    def _send(self, kind, body):
        rid = self.next_id
        self.next_id += 1
        data = body.encode("utf-8")
        self.sock.sendall(struct.pack("<iii", len(data) + 10, rid, kind) + data + b"\0\0")
        return rid

    def _read(self):
        size = struct.unpack("<i", self._exact(4))[0]
        rid, kind = struct.unpack("<ii", self._exact(8))
        body = self._exact(size - 8)[:-2]
        return rid, kind, body.decode("utf-8", "replace")

    def _exact(self, n):
        buf = b""
        while len(buf) < n:
            part = self.sock.recv(n - len(buf))
            if not part:
                raise ConnectionError("RCON connection closed")
            buf += part
        return buf

    def _request(self, kind, body):
        rid = self._send(kind, body)
        while True:
            got, k, text = self._read()
            if got == rid or got == -1:
                return got, text

    def command(self, text):
        return self._request(2, text)[1]

    def json(self, text):
        reply = self.command(text).strip()
        try:
            return json.loads(reply.splitlines()[-1]) if reply else {}
        except json.JSONDecodeError:
            raise RuntimeError(f"unexpected reply to {text[:40]}: {reply[:500]}")

    def close(self):
        self.sock.close()


def free_port(kind=socket.SOCK_STREAM):
    with socket.socket(socket.AF_INET, kind) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def find_file(path):
    for base in ("", os.getcwd(), REPO):
        p = os.path.join(base, path) if base else path
        if os.path.exists(p):
            return p
    return None


def load_inputs(args):
    """(blueprint string, job dict or None, ports dict or None, out folder or None)"""
    if os.path.isdir(args.target):
        out = args.target
        bp_file = os.path.join(out, "test_output_string.txt")
        if not os.path.exists(bp_file):
            sys.exit(f"{bp_file} is missing: verify needs the test copy a run writes (not written in test mode)")
    else:
        out, bp_file = None, args.target
    with open(bp_file) as f:
        bp = f.read().strip()
    job = None
    job_file = args.job or (out and os.path.join(out, "resume", "job.json"))
    if job_file and os.path.exists(job_file):
        with open(job_file) as f:
            job = json.load(f)
    ports = None
    ports_file = args.ports or (out and os.path.join(out, "ports.json"))
    if ports_file and os.path.exists(ports_file):
        with open(ports_file) as f:
            ports = json.load(f)
    return bp, job, ports, out


def mods_for(args, job):
    """The mods to enable (names), from --mods/--no-space-age or the job's data file."""
    wanted, no_space_age = [m for m in args.mods.split(",") if m], args.no_space_age
    if not wanted and job and job.get("data"):
        data_file = find_file(job["data"])
        if not data_file:
            sys.exit(f"the job's data file {job['data']} is missing; pass --mods instead")
        with open(data_file) as f:
            data_mods = json.load(f).get("mods", {})
        wanted = [m for m in data_mods if m not in ("core", "base") and not m.startswith("factorio-optimizer-")]
        if not args.mods and "space-age" not in data_mods:
            no_space_age = True
    have = installed_mods()
    enabled = ["base"] if no_space_age else list(BUILT_IN)
    todo = list(wanted)
    while todo:
        m = todo.pop()
        if m in enabled or m == "core":
            continue
        if m in BUILT_IN:
            enabled.append(m)
            continue
        if m not in have:
            sys.exit(f"mod '{m}' is not in {USER_DIR}/mods: install it in game first (Mods -> Install)")
        enabled.append(m)
        todo += [d for d in (required(x) for x in have[m][2].get("dependencies", [])) if d]
    return enabled, have


def prepare(work, enabled, have):
    mods_dir = os.path.join(work, "mods")
    os.makedirs(mods_dir)
    for m in enabled:
        if m not in BUILT_IN:
            src = have[m][1]
            if os.path.isdir(src):
                shutil.copytree(src, os.path.join(mods_dir, os.path.basename(src)))
            else:
                shutil.copy(src, mods_dir)
    info = mod_info(HERE)
    shutil.copytree(HERE, os.path.join(mods_dir, f"{info['name']}_{info['version']}"),
                    ignore=shutil.ignore_patterns("*.py", "__pycache__"))
    mod_list = [{"name": m, "enabled": m in enabled} for m in BUILT_IN] + \
               [{"name": m, "enabled": True} for m in enabled if m not in BUILT_IN] + \
               [{"name": info["name"], "enabled": True}]
    with open(os.path.join(mods_dir, "mod-list.json"), "w") as f:
        json.dump({"mods": mod_list}, f, indent=1)
    user = os.path.join(work, "user")
    os.makedirs(user)
    ini = os.path.join(work, "config.ini")
    with open(ini, "w") as f:
        f.write(f"[path]\nread-data=__PATH__system-read-data__\nwrite-data={user}\n")
    settings = os.path.join(work, "server-settings.json")
    with open(settings, "w") as f:
        json.dump({"name": "factorio-optimizer verify", "description": "", "visibility": {"public": False, "lan": False},
                   "require_user_verification": False, "auto_pause": False, "autosave_interval": 100000,
                   "autosave_slots": 1, "only_admins_can_pause_the_game": True}, f)
    return [BINARY, "--config", ini, "--mod-directory", mods_dir], user, settings


def log_tail(log, n=3000):
    with open(log, errors="replace") as f:
        return f.read()[-n:]


def run_game(args, bp, params, enabled, have):
    work = tempfile.mkdtemp(prefix="factorio-verify-")
    base, user, settings = prepare(work, enabled, have)
    save = os.path.join(work, "verify.zip")
    log = os.path.join(work, "factorio.log")
    created = subprocess.run(base + ["--create", save], capture_output=True, text=True)
    if created.returncode != 0 or not os.path.exists(save):
        sys.exit("creating the game failed:\n" + created.stdout[-3000:])
    port, password = free_port(), "".join(random.choice(string.ascii_letters) for _ in range(20))
    env = dict(os.environ, SteamAppId="427520", SteamGameId="427520")
    cmd = base + ["--start-server", save, "--server-settings", settings, "--port", str(free_port(socket.SOCK_DGRAM)),
                  "--rcon-bind", f"127.0.0.1:{port}", "--rcon-password", password]
    with open(log, "w") as lf:
        game = subprocess.Popen(cmd, stdout=lf, stderr=subprocess.STDOUT, env=env)
    rcon = None
    try:
        deadline = time.time() + args.timeout
        while rcon is None:
            if game.poll() is not None or time.time() > deadline:
                sys.exit(f"the server did not start (log in {log}):\n{log_tail(log)}")
            if "Starting RCON interface" in log_tail(log, 10**7) or "RCON interface" in log_tail(log, 10**7):
                try:
                    rcon = Rcon(port, password)
                except OSError:
                    time.sleep(1)
            else:
                time.sleep(1)
        print(f"server up ({', '.join(enabled)}); sending the blueprint ({len(bp):,} characters)", flush=True)
        rcon.json("/verify_clear")
        for i in range(0, len(bp), CHUNK):
            rcon.json("/verify_append " + bp[i:i + CHUNK])
        started = rcon.json("/verify_start " + json.dumps(params))
        if "error" in started:
            sys.exit(f"verify failed to start: {started['error']} (log in {log})")
        b = started["build"]
        print(f"built {b['revived']} of {b['ghosts']} entities, {started['machines']} machines, "
              f"{b['power_networks']} power networks; running {params['warmup_s']} s warm-up + {params['measure_s']} s measuring", flush=True)
        t0, last = time.time(), -1
        while True:
            if game.poll() is not None:
                sys.exit(f"the game stopped (log in {log}):\n{log_tail(log)}")
            st = rcon.json("/verify_status")
            if st.get("phase") == "done":
                break
            if time.time() - t0 > args.timeout + (params["warmup_s"] + params["measure_s"]) * 2:
                sys.exit(f"timed out at {st} (log in {log})")
            pct = int(100 * st.get("game_s", 0) / max(1, st.get("total_s", 1)))
            if pct // 10 != last // 10:
                rate = st.get("game_s", 0) / max(1e-9, time.time() - t0)
                print(f"  {st.get('phase')}: {st.get('game_s', 0):.0f} of {st.get('total_s', 0):.0f} game seconds ({rate:.1f}x real time)", flush=True)
                last = pct
            time.sleep(2)
        report_file = os.path.join(user, "script-output", REPORT)
        with open(report_file) as f:
            report = json.load(f)
        # Lua writes an empty list as {}.
        for k in ("machines", "outputs", "inputs", "fluid_outputs", "stuck_inserters", "stuck_belts", "warnings"):
            if isinstance(report.get(k), dict):
                report[k] = list(report[k].values())
        report["started"] = started
        return report
    finally:
        if rcon is not None:
            try:
                rcon.command("/quit")
            except Exception:
                pass
            rcon.close()
        try:
            game.wait(20)
        except subprocess.TimeoutExpired:
            game.send_signal(signal.SIGTERM)
            try:
                game.wait(20)
            except subprocess.TimeoutExpired:
                game.kill()
        if args.keep:
            print(f"work folder kept: {work}")
        else:
            shutil.rmtree(work, ignore_errors=True)


def fmt(v):
    return f"{v:.3f}".rstrip("0").rstrip(".") if v else "0"


def planned_crafts(args):
    """Recipe -> planned crafts per second of one of its machines (the mean over the plan's machines), from plan.json."""
    path = os.path.join(args.target, "plan.json") if os.path.isdir(args.target) else None
    if not path or not os.path.exists(path):
        return {}
    tot, n = defaultdict(float), defaultdict(int)
    for m in json.load(open(path)).get("machines", []):
        r = m["recipe"].split("#")[0].split("@")[0]
        tot[r] += m.get("crafts", 0.0); n[r] += 1
    return {r: tot[r] / n[r] for r in tot}


def planned_use(args):
    """Item or fluid -> planned use per second (plan.json demands), by the game's name."""
    path = os.path.join(args.target, "plan.json") if os.path.isdir(args.target) else None
    if not path or not os.path.exists(path):
        return {}
    use = defaultdict(float)
    for m in json.load(open(path)).get("machines", []):
        for k, v in {**m.get("demand", {}), **m.get("fluidDemand", {})}.items():
            use[k.split("#")[0]] += v
    return use


def judge(report, ports, args):
    """Prints the report; returns the list of failures."""
    fails, notes = [], []
    b = report["build"]
    missing = sum(b["failed"].values())
    print(f"\nBuild: {b['revived']} of {b['ghosts']} entities built ({b['blueprint_entities']} in the blueprint, {b.get('tiles', 0)} tiles), "
          f"{b['modules_inserted']} modules inserted, {b['power_networks']} electric networks powered")
    if missing:
        fails.append(f"{missing} entities could not be built: " + ", ".join(f"{n} x{c}" for n, c in b["failed"].items()))
    if b.get("power_networks_unwired"):
        fails.append(f"{b['power_networks_unwired']} electric networks could not be wired to power")
    rs = report.get("research", {})
    print(f"Research: inserter stack size +{rs.get('inserter_stack_size_bonus', 0)}, bulk inserter capacity "
          f"+{rs.get('bulk_inserter_capacity_bonus', 0)}, belt stack size +{rs.get('belt_stack_size_bonus', 0)}")
    for w in report.get("warnings", []):
        notes.append(w)

    machines = report["machines"]
    secs = report["measured_s"]
    print(f"\nMachines ({len(machines)}), over {secs:.0f} s after a {report['warmup_s']:.0f} s warm-up:")
    groups = defaultdict(list)
    for m in machines:
        groups[(m.get("recipe") or "(no recipe)", m["name"])].append(m)
    print(f"  {'recipe':34} {'machine':28} {'n':>4} {'crafts/min':>11} {'idle':>4}  usual status")
    for (recipe, name), ms in sorted(groups.items()):
        idle = sum(1 for m in ms if m["crafts"] == 0)
        usual = Counter(m["usual_status"] for m in ms).most_common(2)
        print(f"  {recipe:34} {name:28} {len(ms):>4} {sum(m['crafts_per_min'] for m in ms):>11.1f} {idle:>4}  "
              + ", ".join(f"{s} x{c}" for s, c in usual))
    show_all = args.machines or len(machines) <= 40
    listed = machines if show_all else [m for m in machines if m["crafts"] == 0]
    if listed:
        print(f"\n  {'at':>14}  {'machine':28} {'recipe':34} {'crafts':>6} {'/min':>7} {'progress':>8}  status (usual)")
        for m in sorted(listed, key=lambda m: (m["y"], m["x"])):
            print(f"  {'(%g,%g)' % (m['x'], m['y']):>14}  {m['name']:28} {(m.get('recipe') or '-'):34} {m['crafts']:>6} "
                  f"{m['crafts_per_min']:>7.2f} {m['progress']:>8.2f}  {m['status']} ({m['usual_status']})")
    # Against the plan (plan.json: crafts per second of each machine, by recipe): a Py machine at 0.0001 crafts/s makes
    # nothing in 20 minutes as planned, so "finished nothing" counts only machines planned to craft at least once.
    planned = planned_crafts(args)
    def expected(m):
        r = (m.get("recipe") or "").split("#")[0].split("@")[0]
        return planned[r] * secs if r in planned else None
    if planned:
        dead = [m for m in machines if m["crafts"] == 0 and (expected(m) is None or expected(m) >= 1)]
        slow = [m for m in machines if expected(m) is not None and expected(m) >= 2 and 0 < m["crafts"] < 0.5 * expected(m)]
        quiet = sum(1 for m in machines if m["crafts"] == 0) - len(dead)
        dead_msg = (f"{len(dead)} machines finished nothing while measured, of those planned to craft at least once "
                    f"({quiet} more were planned to craft less than once)") if dead else None
        if slow:
            notes.append(f"{len(slow)} machines made under half their planned crafts: " +
                         ", ".join(f"{m.get('recipe')} {m['crafts']}/{expected(m):.0f}" for m in sorted(slow, key=lambda m: m['crafts'] / expected(m))[:12]))
    else:
        dead = [m for m in machines if m["crafts"] == 0]
        dead_msg = f"{len(dead)} machines finished nothing while measured" if dead else None
    no_recipe = [m for m in machines if not m.get("recipe")]
    if no_recipe:
        fails.append(f"{len(no_recipe)} machines have no recipe")

    fed = report.get("fed_measure") or {}
    if report.get("fed") is not None:
        use = planned_use(args)
        print(f"\nFed by the check while measured (the factory's own supply fell short), per second:")
        rows = sorted(fed.items(), key=lambda kv: -kv[1])
        for k, n in rows[:40]:
            u = use.get(k.strip("[]").split("#")[0], 0.0)
            print(f"  {k:40} {fmt(n / secs):>10}  planned use {fmt(u):>8}" + (f"  ({100 * n / secs / u:.0f}%)" if u > 1e-12 else ""))
        if not rows:
            print("  nothing")
        notes.append(f"fed by the check: {len(rows)} items (the layout ran with every machine supplied; see above)")

    outs = [dict(o, fluid=False) for o in report["outputs"]] + \
           [{"x": d["x"], "y": d["y"], "per_s": {d["fluid"]: d["per_s"]}, "fluid": True} for d in report["fluid_outputs"]]
    ins = [dict(i, fluid=False) for i in report["inputs"]]
    for title, side, feeds, check in (("Outputs", "outputs", outs, True), ("Inputs taken", "inputs", ins, False)):
        print(f"\n{title} (per second):")
        planned = (ports or {}).get(side, [])
        used, totals = set(), defaultdict(lambda: [0.0, 0.0])
        for p in planned:
            if p.get("fluid") and side == "inputs":
                continue  # fluid inputs are infinity pipes, not measured
            cx, cy = p["x"] + 0.5, p["y"] + 0.5
            near = [(abs(f["x"] - cx) + abs(f["y"] - cy), i) for i, f in enumerate(feeds)
                    if f["fluid"] == bool(p.get("fluid")) and i not in used]
            near = [n for n in near if n[0] <= 3.5]
            got = 0.0
            if near:
                i = min(near)[1]
                used.add(i)
                per_s = feeds[i]["per_s"]
                game = p["item"].split("#")[0]   # the game's name (temperatures and by-products are named "x#...")
                got = sum(per_s.values()) if p["item"].startswith("mixed") else per_s.get(game, 0.0)
                extra = {k: v for k, v in per_s.items() if k != game and not p["item"].startswith("mixed") and v}
            else:
                extra = {}
            totals[p["item"]][0] += got
            totals[p["item"]][1] += p["rate"]
            what = ", ".join(f"{k} {fmt(v)}" for k, v in sorted(feeds[min(near)[1]]["per_s"].items())) if near and p["item"].startswith("mixed") else ""
            print(f"  {p['item']:34} at ({p['x']},{p['y']}) {p.get('edge', ''):5} {fmt(got):>9}  planned {fmt(p['rate']):>8}  "
                  f"{100 * got / p['rate'] if p['rate'] else 0:6.1f}%" + (f"  [{what}]" if what else "")
                  + (f"  also {extra}" if extra else "") + ("" if near else "  (no feed found at this port)"))
        for i, f in enumerate(feeds):
            if i not in used and any(f["per_s"].values()):
                print(f"  {'(not a planned port)' if ports else '(output)':34} at ({f['x']:g},{f['y']:g}) " + ", ".join(f"{k} {fmt(v)}" for k, v in f["per_s"].items()))
        if len(planned) > 1:
            for item, (got, want) in sorted(totals.items()):
                print(f"  {'total ' + item:34} {'':14} {fmt(got):>9}  planned {fmt(want):>8}  {100 * got / want if want else 0:6.1f}%")
        if check:
            # The job's targets must come out at their rate; a by-product leaving below its plan is a note (its users
            # inside take it first, and overflows leave only what is left over).
            targets = getattr(args, "targets", None) or set()
            for item, (got, want) in totals.items():
                if want and got < want * (1 - args.tolerance):
                    msg = f"{item}: {fmt(got)}/s out, planned {fmt(want)}/s"
                    if targets and item not in targets:
                        notes.append("by-product " + msg)
                    else:
                        fails.append(msg)
    if not ports:
        notes.append("no ports.json: rates not compared with the plan")

    # Rocket silos: parts built (each finished rocket is launched), against the plan's launched rate.
    launched = getattr(args, "launched", {}) or {}
    silos = report.get("silos") or []
    if launched or silos:
        print("\nLaunched (per second):")
        secs = report["measured_s"]
        for item, want in sorted(launched.items()):
            # A silo's crafts are finished rockets; its parts are counted apart.
            got = sum(s.get("parts", 0) for s in silos) / secs if item == "rocket-part" else \
                sum(m["crafts"] for m in machines if m.get("recipe") == item) / secs
            print(f"  {item:34} {'':14} {fmt(got):>9}  planned {fmt(want):>8}  {100 * got / want if want else 0:6.1f}%")
            if want and got < want * (1 - args.tolerance):
                fails.append(f"{item}: {fmt(got)}/s built, planned {fmt(want)}/s")
        for s in silos:
            print(f"  rocket silo at ({s['x']:g},{s['y']:g}): {s.get('parts', 0)} parts built, {s['launches']} rockets launched while measured"
                  + (f", {s['failed_launches']} launches refused" if s.get("failed_launches") else ""))

    # A machine that crafted nothing is a fault, unless every output kept its rate: with outputs taken at the plan and
    # machine counts rounded up (headroom), a spare machine at the end of a belt can wait all the time.
    if dead_msg:
        if any(": " in f and "/s out, planned" in f for f in fails) or args.uncapped or not ports:
            fails.append(dead_msg)
        else:
            print(f"\n{dead_msg}: spare (every output kept its planned rate)")
    stuck = report["stuck_inserters"]
    print(f"\nInserters: {report['inserters']}, stuck holding an item: {len(stuck)}, never moved with an empty hand: {report['idle_inserters']}")
    for s in stuck[:30]:
        print(f"  ({s['x']:g},{s['y']:g}) {s['name']} holding {s.get('holding')} for {s.get('drop_target')} ({s.get('status')})")
    # Waiting for room (a backed-up belt, or direct insertion into a machine that has enough) is by design.
    real = [s for s in stuck if not s.get("waiting")]
    if len(real) < len(stuck):
        print(f"  ({len(stuck) - len(real)} of them wait for room where they drop: surplus backing up, not a fault)")
    if real:
        fails.append(f"{len(real)} inserters stuck holding an item")
    belts = report["stuck_belts"]
    print(f"Belts: {report['belts']}, never moved while holding items: {len(belts)}")
    for s in belts[:args.belts]:
        print(f"  ({s['x']:g},{s['y']:g}) {s['name']}: " + ", ".join(f"{k} x{v}" for k, v in s["items"].items()))
    if len(belts) > args.belts:
        print(f"  ... {len(belts) - args.belts} more in the report")

    for n in notes:
        print(f"note: {n}")
    return fails


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("target", help="a run's out folder, or a file with a blueprint string")
    ap.add_argument("--warmup", type=float, default=300, help="game seconds to run before measuring (default 300)")
    ap.add_argument("--measure", type=float, default=300, help="game seconds to measure (default 300)")
    ap.add_argument("--speed", type=float, default=1000, help="game.speed while running (default 1000: as fast as it goes)")
    ap.add_argument("--tolerance", type=float, default=0.05, help="how far below plan an output may be (default 0.05)")
    ap.add_argument("--mods", default="", help="comma-separated mods to enable (default: the job's data file's mods)")
    ap.add_argument("--no-space-age", action="store_true", help="base game only")
    ap.add_argument("--job", help="job file (default <out>/resume/job.json)")
    ap.add_argument("--ports", help="ports.json with the planned rates (default <out>/ports.json)")
    ap.add_argument("--machines", action="store_true", help="list every machine (default: all when 40 or fewer, else the idle ones)")
    ap.add_argument("--belts", type=int, default=10, help="stuck belts to list (default 10)")
    ap.add_argument("--timeout", type=int, default=600, help="seconds to wait for the server, on top of the run")
    ap.add_argument("--uncapped", action="store_true",
                    help="empty output chests without limit (default: take each output item at its planned rate, so a "
                         "factory cannot look good by over-making one pack while starving another)")
    ap.add_argument("--dump", action="store_true", help="also write every belt's lanes, inserter and pipe to the report")
    ap.add_argument("--prime-all", action="store_true", help="warm start: every machine starts with two crafts' worth of its ingredients")
    ap.add_argument("--feed-short", nargs="?", const="plan", choices=["plan", "full"], default=None,
                    help="every machine standing short of an ingredient gets it, counted per item: 'plan' (default) up to its "
                         "planned use, so what was fed is where the factory's own supply falls short; 'full' as much as it takes, "
                         "so what still stands is the layout's own fault")
    ap.add_argument("--feed-stop", type=float, default=None, metavar="S",
                    help="with --feed-short: stop feeding S game seconds in (a bootstrap); measured after, the factory shows "
                         "whether it keeps itself going")
    ap.add_argument("--governor", type=float, nargs="?", const=2.0, default=None, metavar="FACTOR",
                    help="steady state: a machine ahead of FACTOR (default 2) times its planned crafts (plus 10) waits while another "
                         "machine is short of one of its ingredients, as full buffers would make it; without it a consumer early on "
                         "a belt whose output fills hours of pipe takes everything first")
    ap.add_argument("--keep", action="store_true", help="keep the temporary game folder (log, save)")
    args = ap.parse_args()

    bp, job, ports, out = load_inputs(args)
    enabled, have = mods_for(args, job)
    research = (job or {}).get("research", {})
    params = {"warmup_s": args.warmup, "measure_s": args.measure, "speed": args.speed,
              "inserter_capacity": research.get("inserter_capacity", 0),
              "productivity": research.get("productivity") or None,
              "belt_stack_size": (job or {}).get("belt_stack_size", 1)}
    if not args.uncapped and ports:
        # Every item output at its planned rate: the belt backs up, so the block's own users of a by-product take
        # first (Py's moss gets its coarse back; uncapped, the moving belt took 2 of its 15 out of the factory) and a
        # machine with several products runs at its planned rate. Not the overflows (0/s, plan.overflowItems): they
        # take only what is left over.
        # Items the factory also uses (plan.json consumers), and the 0/s overflows, leave only what their users leave:
        # the mod opens their sinks only while the belt into them is backed up, and does not hold them to a rate.
        used = set()
        plan_file = os.path.join(args.target, "plan.json") if os.path.isdir(args.target) else None
        if plan_file and os.path.exists(plan_file):
            used = {t["item"] for t in json.load(open(plan_file)).get("terminals", []) if t.get("role") == "CONSUMER"}
        targets = {t["name"] for t in (job or {}).get("targets", [])}
        overflow = {o["item"].split("#")[0] for o in ports.get("outputs", [])
                    if not o.get("fluid") and o["item"] not in targets and (o["rate"] <= 1e-12 or o["item"] in used)}
        if overflow:
            params["overflow"] = sorted(overflow)
            # Where they are, so their sinks start closed (by item alone, the first ones through would leave).
            params["overflow_at"] = [[o["x"] + 0.5, o["y"] + 0.5] for o in ports.get("outputs", [])
                                     if not o.get("fluid") and o["item"].split("#")[0] in overflow]
        # Mixed belts (leftovers, plan.mixed) always leave: their sinks are never held back as overflows, whatever they carry.
        mixed_at = [[o["x"] + 0.5, o["y"] + 0.5] for o in ports.get("outputs", []) if o["item"].startswith("mixed-")]
        if mixed_at:
            params["mixed_at"] = mixed_at
        rates = defaultdict(float)
        for o in ports.get("outputs", []):
            if not o.get("fluid") and o["rate"] > 1e-12 and o["item"].split("#")[0] not in overflow:
                rates[o["item"].split("#")[0]] += o["rate"]
        params["sink_rates"] = dict(rates)
    if args.dump:
        params["dump"] = True
    if args.prime_all:
        params["prime_all"] = True
    if args.feed_short:
        params["feed_short"] = args.feed_short
        if args.feed_stop is not None:
            params["feed_stop"] = args.feed_stop
        fp = os.path.join(args.target, "plan.json") if os.path.isdir(args.target) else None
        if fp and os.path.exists(fp):
            rates = defaultdict(dict)
            for m in json.load(open(fp)).get("machines", []):
                rn = m["recipe"].split("#")[0].split("@")[0]
                for item, rate in {**m.get("demand", {}), **m.get("fluidDemand", {})}.items():
                    g = item.split("#")[0]
                    rates[rn][g] = max(rates[rn].get(g, 0.0), rate)
            params["feed_rates"] = dict(rates)
            crafts = defaultdict(float)
            for m in json.load(open(fp)).get("machines", []):
                rn = m["recipe"].split("#")[0].split("@")[0]
                crafts[rn] = max(crafts[rn], m.get("crafts", 0.0))
            params["feed_crafts"] = dict(crafts)
    if args.governor is not None:
        gp = os.path.join(args.target, "plan.json") if os.path.isdir(args.target) else None
        if gp and os.path.exists(gp):
            per = defaultdict(float)
            for m in json.load(open(gp)).get("machines", []):
                rn = m["recipe"].split("#")[0].split("@")[0]
                per[rn] = max(per[rn], m.get("crafts", 0.0))
            params["governor"] = {k: v for k, v in per.items() if v > 0}
            params["governor_factor"] = args.governor
            print(f"governor: a machine ahead of {args.governor:g}x its planned crafts waits while another is short of its ingredients")
    # Production loops the planner found (plan.json "seeds"): their machines get a few crafts' worth to start with.
    plan_path = os.path.join(args.target, "plan.json") if os.path.isdir(args.target) else None
    if plan_path and os.path.exists(plan_path):
        raw = json.load(open(plan_path)).get("seeds", [])
        seeds = sorted({s.split("#")[0] for s in raw})
        if seeds:
            params["seeds"] = seeds
            # Fluids seeded at a temperature (name#t: Py's outlet gas ring at 1000) are poured in at that temperature.
            temps = defaultdict(list)
            for s in raw:
                name, _, t = s.partition("#")
                if t.replace(".", "", 1).isdigit():
                    temps[name].append(float(t))
            if temps:
                params["seed_temps"] = dict(temps)
            plan_json = json.load(open(plan_path))
            if "seedCarriers" in plan_json:
                carrier_names = plan_json["seedCarriers"]
            else:
                # An older plan: a seed that comes back round to itself through one-for-one steps (plan.loopCarriers).
                ms = plan_json.get("machines", [])
                def same(a, b): return abs(a - b) <= 1e-6 * max(a, b) + 1e-12
                def one_for_one(item):
                    seen, todo = {item}, [item]
                    while todo:
                        x = todo.pop()
                        for m in ms:
                            rx = m.get("demand", {}).get(x, m.get("fluidDemand", {}).get(x))
                            if rx is None:
                                continue
                            for y, ry in m.get("products", {}).items():
                                if y != x and same(rx, ry):
                                    if y == item:
                                        return True
                                    if y not in seen:
                                        seen.add(y); todo.append(y)
                    return False
                carrier_names = [x for x in raw if one_for_one(x)]
            # And every item on their rings (one-for-one steps from them): Py's heat bricks are seeded as the warm ones,
            # and the stone bricks the same ring passes on got a warm start's two crafts' worth on top (Py science 2: 74
            # bricks in a two-machine ring, both outputs full, each machine waiting for the other).
            ms_all = plan_json.get("machines", [])
            def reach(item):
                def same(a, b): return abs(a - b) <= 1e-6 * max(a, b) + 1e-12
                seen, todo = {item}, [item]
                while todo:
                    x = todo.pop()
                    for m in ms_all:
                        rx = m.get("demand", {}).get(x, m.get("fluidDemand", {}).get(x))
                        if rx is None:
                            continue
                        for y, ry in m.get("products", {}).items():
                            if y not in seen and same(rx, ry):
                                seen.add(y); todo.append(y)
                return seen
            # (Only items that lead back round to it: rates that merely happen to match took in brains and cages.)
            def ring(item):
                return {y for y in reach(item) if item in reach(y)}
            carrier_names = sorted({y for c in carrier_names for y in ring(c)})
            carriers = sorted({s.split("#")[0] for s in carrier_names})
            # Each recipe's planned use of each seed per machine: top-ups keep within it (with room to start up).
            # (Every ingredient: catalysts are topped up too, within the same cap.)
            seed_rates = defaultdict(dict)
            for m in plan_json.get("machines", []):
                rn = m["recipe"].split("#")[0].split("@")[0]
                for item, rate in {**m.get("demand", {}), **m.get("fluidDemand", {})}.items():
                    g = item.split("#")[0]
                    seed_rates[rn][g] = max(seed_rates[rn].get(g, 0.0), rate)
            if seed_rates:
                params["seed_rates"] = {k: v for k, v in seed_rates.items()}
            if carriers:
                params["seed_carriers"] = carriers
            print(f"seeding production loops with {', '.join(raw)}")
    params = {k: v for k, v in params.items() if v is not None}
    report = run_game(args, bp, params, enabled, have)
    report["mods"] = enabled
    if out:
        with open(os.path.join(out, "verify_report.json"), "w") as f:
            json.dump(report, f, indent=1)
    args.launched = {}
    if out and os.path.exists(os.path.join(out, "plan.json")):
        with open(os.path.join(out, "plan.json")) as f:
            args.launched = json.load(f).get("launched") or {}
    args.targets = {t["name"] for t in (job or {}).get("targets", [])}
    fails = judge(report, ports, args)
    if out:
        print(f"\nfull report: {os.path.join(out, 'verify_report.json')}")
    if fails:
        print("\nFAIL")
        for f in fails:
            print(f"  - {f}")
        sys.exit(1)
    print("\nPASS")


if __name__ == "__main__":
    main()
