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


def built_plan_path(target):
    """The plan the factory was built from: the blocks' rebalanced plan when the run wrote one (a block that got more
    machines of a recipe runs each of them slower: copper plate 0.30 crafts/s a machine where plan.json has 0.37, so
    quotas and caps counted from plan.json were that much too loose), else plan.json. None for a bare blueprint."""
    if not os.path.isdir(target):
        return None
    for name in ("plan_rebalanced.json", "plan.json"):
        path = os.path.join(target, name)
        if os.path.exists(path):
            return path
    return os.path.join(target, "plan.json")


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


FLOW_MOD = os.path.join(REPO, "tools", "factory-flow")


def prepare(work, enabled, have, flow_mod=False):
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
    if flow_mod:
        fi = mod_info(FLOW_MOD)
        shutil.copytree(FLOW_MOD, os.path.join(mods_dir, f"{fi['name']}_{fi['version']}"),
                        ignore=shutil.ignore_patterns("*.py", "__pycache__", "*.md"))
        mod_list.append({"name": fi["name"], "enabled": True})
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
    base, user, settings = prepare(work, enabled, have, flow_mod=getattr(args, "flow_mod", False))
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
        if params.get("export_built"):
            built = os.path.join(user, "script-output", "factorio-optimizer-built.txt")
            if os.path.exists(built):
                shutil.copy(built, params["export_built"])
                print(f"built copy written to {params['export_built']}", flush=True)
            else:
                print("the built copy was not written: " + "; ".join(started.get("warnings") or []), flush=True)
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
                extra = ""
                if st.get("machines"):
                    extra = f"; {st.get('working', 0)} of {st['machines']} machines working, target crafts {st.get('target_crafts', 0)}"
                    for k, label in (("off", "switched off"), ("held", "inserters held"), ("stopped", "held on fluids"), ("gates", "gates closed"), ("capped", "capped for side products"), ("metered", "held by the meter"), ("belt_held", "output inserters held"), ("fgates_open", "fluid overflows open")):
                        if st.get(k) is not None:
                            extra += f", {st[k]} {label}"
                print(f"  {st.get('phase')}: {st.get('game_s', 0):.0f} of {st.get('total_s', 0):.0f} game seconds ({rate:.1f}x real time){extra}", flush=True)
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
    path = built_plan_path(args.target)
    if not path or not os.path.exists(path):
        return {}
    tot, n = defaultdict(float), defaultdict(int)
    for m in json.load(open(path)).get("machines", []):
        r = m["recipe"].split("#")[0].split("@")[0]
        tot[r] += m.get("crafts", 0.0); n[r] += 1
    return {r: tot[r] / n[r] for r in tot}


def planned_target_crafts(args, targets):
    """Recipe -> planned crafts per second of all its machines, for the recipes making one of the job's targets."""
    path = built_plan_path(args.target)
    if not path or not os.path.exists(path) or not targets:
        return {}
    tot = defaultdict(float)
    for m in json.load(open(path)).get("machines", []):
        if any(k.split("#")[0].split("@")[0] in targets for k in m.get("products", {})):
            tot[m["recipe"].split("#")[0].split("@")[0]] += m.get("crafts", 0.0)
    return dict(tot)


def planned_use(args):
    """Item or fluid -> planned use per second (plan.json demands), by the game's name."""
    path = built_plan_path(args.target)
    if not path or not os.path.exists(path):
        return {}
    use = defaultdict(float)
    for m in json.load(open(path)).get("machines", []):
        for k, v in {**m.get("demand", {}), **m.get("fluidDemand", {})}.items():
            use[k.split("#")[0]] += v
    return use


VOID_MACHINES = {"py-gas-vent", "py-sinkhole"}


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

    # Fluid voids (Py's exhaust pipe and sinkhole, built by --real-voids or from the player's blueprint) are machines
    # to the game, with no recipe and nothing to craft: not part of the factory's verdict.
    machines = [m for m in report["machines"] if m["name"] not in VOID_MACHINES]
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

    if report.get("bots"):
        b = report["bots"]
        print(f"\nRobots: {b['robots']} {b.get('robot') or ''} in {b['networks']} network(s) ({b['robots_idle']} idle); {b['requesters_empty']} of "
                     f"{b['requesters']} requester chests empty, {b['items_in_providers']} items waiting in {b['providers']} providers"
                     + (f"; {b['chests_outside_networks']} chests outside any network" if b['chests_outside_networks'] else ""))
        pi = sorted((b.get("provider_items") or {}).items(), key=lambda kv: -kv[1])
        if pi:
            print("  most held in providers: " + ", ".join(f"{k} {v}" for k, v in pi[:15]))
    if report.get("bootstrapped"):
        bm = report.get("bootstrapped_measure") or {}
        print("\nLoop carriers put in by the check (--flow, a loop starved for two minutes): " +
              ", ".join(f"{k} {fmt(v)}" + (f" ({fmt(bm[k])} while measured)" if bm.get(k) else "")
                        for k, v in sorted(report["bootstrapped"].items(), key=lambda kv: -kv[1])) +
              ("" if bm else "; none while measured"))
    if report.get("pumped") is not None:
        pm = report.get("pumped_measure") or {}
        print("\nMoved by virtual pumps while measured, per second: " +
              (", ".join(f"{k} {fmt(v / secs)}" for k, v in sorted(pm.items(), key=lambda kv: -kv[1])[:25]) or "nothing"))
    if report.get("orphan_s"):
        raw = {i["item"].split("#")[0] for i in (ports or {}).get("inputs", [])} if isinstance(ports, dict) else set()
        rows = [(k, v) for k, v in report["orphan_s"].items() if k.split(" <- ")[0] not in raw]
        rows.sort(key=lambda kv: -kv[1])
        print(f"\nDemand no maker hears (a machine short of an item on belts no maker of it drops onto), seconds over the run:")
        for k, v in rows[:25]:
            print(f"  {k:70} {v:>8}")
        if not rows:
            print("  none")
    if report.get("voided") is not None:
        vb = report.get("voided_by") or {}
        print(f"\nDeleted by the check while measured (--void-full: surplus of full machines), per second:")
        rows = sorted(vb.items(), key=lambda kv: -kv[1])
        for k, n in rows[:40]:
            print(f"  {k:70} {fmt(n / secs):>10}")
        if not rows:
            print("  nothing")
        notes.append(f"virtual sinks: {len(rows)} recipe/item pairs had surplus deleted (see above)")
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

    # The port's rate alone can pass a factory that stood: a maker running at twice its plan fills the belt to the
    # port, and the port takes from that belt for hours after the last craft (logistic science stood at hour 11 of
    # 20 and its port still read 100%). So the target's makers must also have kept on over the last quarter of the
    # measured time (two hours at least): 80% of their planned crafts there.
    tl = [e for e in report.get("timeline") or [] if e.get("phase") == "measure"]
    if tl:
        end = tl[-1]
        span = max(2.0, (secs / 3600) / 4)
        before = [e for e in report["timeline"] if e["h"] <= end["h"] - span + 1e-6]
        if before:
            start = before[-1]
            hours = end["h"] - start["h"]
            for r, want in sorted(planned_target_crafts(args, getattr(args, "targets", None) or set()).items()):
                got = end["pf"].get(r, 0) - start["pf"].get(r, 0)
                exp = want * hours * 3600
                print(f"\nTarget makers over the last {hours:.1f} hours: {r} {got} crafts, planned {exp:.1f}")
                if exp >= 2 and got < 0.8 * exp:
                    fails.append(f"{r} kept on to the end only in part: {got} crafts in the last {hours:.1f} hours, planned {exp:.1f}")

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
    # Waiting for room (a backed-up belt, or direct insertion into a machine that has enough) is by design, and so is
    # an inserter switched off by the flow rules or a circuit with an item in its hand: it was held, not stuck.
    held = [s for s in stuck if not s.get("waiting") and s.get("status") in ("disabled_by_script", "disabled_by_control_behavior")]
    real = [s for s in stuck if not s.get("waiting") and s not in held]
    if len(stuck) - len(held) - len(real):
        print(f"  ({len(stuck) - len(held) - len(real)} of them wait for room where they drop: surplus backing up, not a fault)")
    if held:
        print(f"  ({len(held)} of them are switched off by the flow rules or a circuit: held, not a fault)")
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
    ap.add_argument("--input-cap", type=float, default=None, metavar="F",
                    help="each raw input belt is given at most F times its planned rate (ports.json), with at most a "
                         "minute's worth saved up, as a mine or train stop of the planned size would deliver (default: "
                         "unlimited; fluid inputs stay unlimited)")
    ap.add_argument("--uncapped", action="store_true",
                    help="empty output chests without limit (default: take each output item at its planned rate, so a "
                         "factory cannot look good by over-making one pack while starving another)")
    ap.add_argument("--dump", action="store_true", help="also write every belt's lanes, inserter and pipe to the report")
    ap.add_argument("--prime-all", action="store_true", help="warm start: every machine starts with two crafts' worth of its ingredients")
    ap.add_argument("--prime-full", action="store_true",
                    help="--prime-all with every fluid input box filled to the top, as in a factory that has run for a while")
    ap.add_argument("--feed-short", nargs="?", const="plan", choices=["plan", "full"], default=None,
                    help="every machine standing short of an ingredient gets it, counted per item: 'plan' (default) up to its "
                         "planned use, so what was fed is where the factory's own supply falls short; 'full' as much as it takes, "
                         "so what still stands is the layout's own fault")
    ap.add_argument("--feed-items", default=None, metavar="A,B,...",
                    help="with --feed-short plan: feed only these items and fluids")
    ap.add_argument("--feed-stop", type=float, default=None, metavar="S",
                    help="with --feed-short: stop feeding S game seconds in (a bootstrap); measured after, the factory shows "
                         "whether it keeps itself going")
    ap.add_argument("--flow", action="store_true",
                    help="flow control: --pull, plus quotas (a machine ahead of 1.5 times its planned intake of an item has the "
                         "inserters bringing it stopped while another machine on the same belt waits for it), and overflows keep "
                         "an item while a machine on their belt waits for it")
    ap.add_argument("--quota-factor", type=float, default=None, metavar="F",
                    help="with --flow: quotas let a machine take F times its planned intake (default 1.5)")
    ap.add_argument("--quota-window", type=float, default=None, metavar="S",
                    help="with --flow: quotas count what a machine took in the last S game seconds, not since the start")
    ap.add_argument("--no-quotas", action="store_true",
                    help="with --flow: everything but quotas (pull, stock, push, gates, carriers)")
    ap.add_argument("--no-pull", action="store_true",
                    help="with --flow: everything but pull (quotas, push, gates, carriers); machines run whenever they can")
    ap.add_argument("--pull-ahead", type=float, default=None, metavar="N",
                    help="with --pull or --flow: a machine working with less than N crafts' worth of an item also counts as "
                         "wanting it, so its makers run ahead of it (default 1: only once it stands short)")
    ap.add_argument("--pull-stock", type=float, default=None, metavar="N",
                    help="with --pull or --flow: every machine, switched off or not, keeps N crafts' worth of each ingredient, "
                         "so its makers run until it holds that much (default 1; 0: a machine switched off waits for nothing, "
                         "as before 2026-11-11)")
    ap.add_argument("--side-cap", type=float, nargs="?", const=1.5, default=None, metavar="FACTOR",
                    help="with --pull or --flow: a machine wanted only for a product it was not planned for (not its main "
                         "product, or not one the plan uses when the main one leaves as a by-product) runs no faster than "
                         "FACTOR (default 1.5) times its plan over the last ten minutes")
    ap.add_argument("--overflow-keep", type=float, default=None, metavar="F",
                    help="fluid overflows (infinity pipes at the 0/s fluid outputs) keep their pipes at most F full "
                         "(0 to 1) instead of the test copy's 0.5")
    ap.add_argument("--fluid-gates", action="store_true",
                    help="with --flow: fluid overflows keep their pipes at most 90%% full, and 40%% while a maker of that "
                         "fluid on the same pipes stands full and no user there waits")
    ap.add_argument("--provider-slots", type=int, default=None, metavar="N",
                    help="robot links: each provider chest holds at most N stacks (its inventory bar)")
    ap.add_argument("--virtual-pumps", action="store_true",
                    help="a helper: a machine short of a fluid ingredient gets up to two crafts' worth moved from the pipes it "
                         "connects to each second, as a pump in front of it would (its box otherwise fills only as far as "
                         "the pipes are full)")
    ap.add_argument("--requester-demand", action="store_true",
                    help="with --flow or --pull: a robot link's requester chest holding less than its request counts as a "
                         "machine waiting for the item, so its makers keep it stocked")
    ap.add_argument("--rate-cap", type=float, default=None, metavar="FACTOR",
                    help="with --pull or --flow: no machine runs faster than FACTOR times its plan over the last ten minutes "
                         "(plus three crafts), whatever wants it")
    ap.add_argument("--cap-slow", action="store_true",
                    help="with --rate-cap: a machine planned for fewer than three crafts in ten minutes is capped over "
                         "the time three planned crafts take (at most four hours) plus one craft, not over ten minutes "
                         "plus three crafts, which lets a machine planned for two crafts an hour make twenty-one; and "
                         "with --quota-window a user planned for fewer than three crafts in the window is counted "
                         "over the time three planned crafts take, with one craft over, not two")
    ap.add_argument("--cap-fill", action="store_true",
                    help="with --rate-cap: a machine at its cap keeps running for a user that is itself behind its "
                         "plan (the belt between them has to fill once); a user ahead of its plan gets no such leave")
    ap.add_argument("--meter", type=float, default=None, metavar="FACTOR",
                    help="every machine is held to FACTOR times its planned rate, as a meter would (a token bucket of one "
                         "craft; unlike --rate-cap it holds the slow machines too, and needs neither --pull nor --flow)")
    ap.add_argument("--meter-file", default=None, metavar="FILE",
                    help="meters at the rates of a plan with margins (tools/plan-sim/margin.py's caps file: each machine's "
                         "rate against its plan, and the carriers that only go round, which --void-full then leaves alone)")
    ap.add_argument("--virtual-bots", action="store_true",
                    help="a helper, with --flow: items travel as through one robot network (a machine below a craft's "
                         "worth of an item gets it out of other machines' outputs, nothing made up) and, with --belt-pull, "
                         "the machines' output inserters stand; belts carry raw inputs only, pipes the fluids")
    ap.add_argument("--belt-pull", action="store_true",
                    help="with --flow: an inserter putting a machine's products onto a belt works only while a user on that "
                         "belt holds less than its stock of one of them, so a metered machine's output goes to the belts "
                         "that need it instead of a third to each")
    ap.add_argument("--meter-start-factor", type=float, default=1.0, metavar="F",
                    help="with --meter-start: F crafts' worth for each user instead of one")
    ap.add_argument("--vb-buffer", type=float, default=None, metavar="S",
                    help="with --virtual-bots and --meter-file: what a machine stands full of goes to a storage holding up to "
                         "S seconds of each item's use, from which machines in need are served first; only what is past "
                         "that is deleted")
    ap.add_argument("--vb-stock", type=float, default=None, metavar="N",
                    help="with --virtual-bots: a machine in need is given up to N crafts' worth (default 2)")
    ap.add_argument("--request-need", action="store_true",
                    help="robot links: a requester chest asks only while a machine it feeds holds less than a craft's worth "
                         "of its item, for one craft's worth (need first; as built it asks for ten minutes' worth, at least 10)")
    ap.add_argument("--request-trip", type=float, default=None, metavar="N",
                    help="with --request-need: a requester chest asks at least N robot trips' worth of its link's flow "
                         "(from the nearest provider chest of its item), so that a far maker is not a wait for every craft")
    ap.add_argument("--vb-share", choices=["small"], default=None,
                    help="with --virtual-bots: of the machines waiting for an item the one planned to use least of it is "
                         "served first (else first come first served, in the machines' fixed order)")
    ap.add_argument("--vb-fluids", action="store_true",
                    help="fluids move from makers to machines in need (as --virtual-bots moves items), with the --vb-buffer stock "
                         "(as storage tanks on every fluid link would)")
    ap.add_argument("--meter-fill", default=None, metavar="K:H",
                    help="with --meter: for the first H game hours every meter runs K times faster (a fast start)")
    ap.add_argument("--meter-bucket", type=float, default=None, metavar="S",
                    help="with --meter: a machine that waits saves up to S seconds of its planned rate and catches up later (60 unless given)")
    ap.add_argument("--meter-start-depth", type=int, default=1, metavar="D",
                    help="with --meter-start: count the users' own allowances too, D levels down (1: only the users' first batch)")
    ap.add_argument("--meter-start-max", type=float, default=1e9, metavar="N",
                    help="with --meter-start: no meter starts with more than N crafts in hand")
    ap.add_argument("--meter-start", action="store_true",
                    help="with --meter-file: each meter starts with enough crafts in hand to give every user of its "
                         "machine's products one craft's worth (a start-up allowance)")
    ap.add_argument("--robot", default=None, metavar="NAME",
                    help="the logistic robot put into roboports (default: the slowest one a player can build)")
    ap.add_argument("--provider-level", type=float, default=None, metavar="STACKS",
                    help="a machine's exit to a robot provider chest works only while the network holds at most so many stacks "
                         "of the item, where the machine also feeds a user directly or by belt (as built the two exits share half and half)")
    ap.add_argument("--overflow-last", action="store_true",
                    help="a machine's output inserter toward an overflow chest works only once the machine has stood full "
                         "for a minute (as an overflow inserter wired to the stock would): its users are served first")
    ap.add_argument("--seed-through", action="store_true",
                    help="loop starters (plan seeds) are topped up while measuring too, not only in the warm-up; what "
                         "is handed out is in the report by item and by hour (a loop that closed stops drawing)")
    ap.add_argument("--void-wait", type=int, default=None, metavar="S",
                    help="with --void-full: a machine must stand full this many seconds before anything is deleted (30)")
    ap.add_argument("--relief", action="store_true",
                    help="overflow at the producer, by what a circuit at the machine could read: a product that blocks a "
                         "machine (a craft's worth in its output, a fluid box nine tenths full) is taken out while "
                         "another product of that machine, one some machine uses, is still leaving; a machine with "
                         "every used product lying in its output stands")
    ap.add_argument("--relief-gate", action="store_true",
                    help="the overflow ports of items the factory uses are gates on a wire from the makers on their "
                         "belts: open while one of them is blocked by a product on that belt and another used product "
                         "of its still leaves (the --relief condition), shut otherwise; nothing is taken at machines")
    ap.add_argument("--relief-built", action="store_true",
                    help="--relief-gate from real parts: a decider combinator at each such maker on a wire from the "
                         "machine, a red wire over every pole, and the last belt before each overflow port enabled by "
                         "its signal; the check itself gates nothing")
    ap.add_argument("--relief-demand", action="store_true",
                    help="with --relief or --relief-gate: another product counts as still wanted only while a user "
                         "on a belt or pipe network the machine puts it on stands short of it (less than a craft's "
                         "worth), not merely while it has room in the output")
    ap.add_argument("--relief-main", action="store_true",
                    help="with --relief or --relief-gate: only a product the machine was planned for (the plan's main "
                         "product of its recipe) counts as still wanted, never a by-product")
    ap.add_argument("--carrier-kit", nargs="?", const="warmup", choices=["warmup", "run"],
                    help="loop carriers as a player's kit: the check hands in only the empty form (barrels, cages, plain "
                         "bricks: a carrier some recipe makes from no other carrier), never a filled one (a caged animal "
                         "came with the animal), and no overflow takes a carrier out of a machine. 'warmup' (default): "
                         "handed in during the warm-up only, so a loop that leaks shows while measured; 'run': through "
                         "the whole run, counted, so the count while measured is the leak")
    ap.add_argument("--belt-hold", nargs="?", type=float, const=2.0, default=None, metavar="K",
                    help="built in the game: each inserter from a machine onto a belt is wired to that belt (read whole "
                         "belt) and works only while the belt holds less than K crafts' worth (default 2) of the item, "
                         "so a maker with two belts stops sharing out by turns once one of them has enough")
    ap.add_argument("--out-share", nargs="?", type=float, const=1.2, default=None, metavar="F",
                    help="a maker with one item product and inserters onto several belt networks gives each network "
                         "its planned share: the inserters of a network ahead of F times its share (default 1.2) of "
                         "what all of them carried are held, unless every network behind its share is blocked")
    ap.add_argument("--out-share-mode", choices=["demand", "both", "strict"], default="both",
                    help="with --out-share: 'both' (default) is demand first and the plan's shares when nobody asks: a "
                         "network with a user waiting for the item takes it, one nobody waits on is held meanwhile, "
                         "and when nobody waits a network ahead of its share is held while one behind it is free. "
                         "'strict': the same, and a network nobody waits on that is ahead of its share stays held even "
                         "when every other belt is blocked. 'demand': the first form, which lets go whenever nobody "
                         "waits")
    ap.add_argument("--loop-first", nargs="?", type=float, const=1800.0, default=None, metavar="S",
                    help="with --flow: a production loop feeds itself first. A user of an item whose own products come "
                         "back to a maker of that item within three recipes (0: it makes the item itself) is in the "
                         "item's loop; any other user has its inserters bringing the item held while a user in the "
                         "loop stands short of it and took less than its plan over the last S game seconds (default "
                         "1800). A breeding loop planned with no margin otherwise dies out once anything is lost")
    ap.add_argument("--reserve", nargs="?", type=int, const=4, default=None, metavar="N",
                    help="with --loop-first: a standing queue at each of a loop's own users. The belt tile just past "
                         "the pickup tile of an inserter bringing a loop's item to one of the loop's users stays shut "
                         "until the tile N back up the belt (default 4) has a lane full, so N tiles of queue stand at "
                         "the user before anything goes on")
    ap.add_argument("--reserve-items", action="store_true",
                    help="with --reserve: a gate past the last user of every item on each belt, not only of a loop's "
                         "items (what parks past the last user is lost once the inputs come at the plan's rate). "
                         "Worse on Py science 1 at capped inputs: a gate of another item took the seaweed gate's tile "
                         "on their shared belt and the seaweed loop died at hour 17")
    ap.add_argument("--reserve-all", action="store_true",
                    help="with --reserve: a gate past every user's pickup tile, for every item, not only past the last "
                         "of a loop's own users")
    ap.add_argument("--loop-quota", action="store_true",
                    help="with --loop-first: a loop's own user is held to its quota of the loop's item only for another "
                         "of the loop's users, never for a user outside the loop")
    ap.add_argument("--loop-stock", type=float, default=None, metavar="K",
                    help="with --loop-first: users outside a loop also wait while a loop user that is switched on "
                         "holds less than K crafts' worth of the item")
    ap.add_argument("--loop-share", action="store_true",
                    help="with --loop-first and --out-share: a maker's belt on which one of the item's loop users waits "
                         "comes before every other belt, whatever the plan's shares")
    ap.add_argument("--loop-fluids", action="store_true",
                    help="with --loop-first: fluid loops too. A user outside a loop of a fluid is switched off while one "
                         "of the loop's own users stands short of the fluid and behind its plan")
    ap.add_argument("--loop-keep", type=float, default=None, metavar="K",
                    help="with --loop-first: a loop's own user waits for the loop's item while it holds less than K "
                         "crafts' worth, switched off by pull or not, so pull and the share rule keep it stocked")
    ap.add_argument("--loop-port-catalyst", action="store_true",
                    help="with --loop-first: the overflow port of a belt a loop's item is put onto stays shut only for a "
                         "catalyst (an item its maker also takes) or a carrier, not for every loop item")
    ap.add_argument("--loop-valves", action="store_true",
                    help="with --loop-fluids: a user outside a fluid's production loop takes it through Py's overflow "
                         "valve (only what lies above 80%% full in the pipe), built in place of the pipe at its input")
    ap.add_argument("--loop-keep-time", type=float, default=None, metavar="S",
                    help="with --loop-first: as --loop-keep, counted in time: a loop's own user waits for the loop's item "
                         "while it holds less than S seconds of its planned use (the larger of the two when both are given)")
    ap.add_argument("--loop-strict", action="store_true",
                    help="with --loop-first: users outside a loop wait whenever a user in the loop stands short of the "
                         "item, not only while that user is behind its plan")
    ap.add_argument("--real-voids", action="store_true",
                    help="fluid output ports end as in a game: Py's overflow valve (opens above 80%% full) and an exhaust "
                         "pipe or sinkhole behind it, in place of the test copy's infinity pipe; counted in the report "
                         "(real_voids)")
    ap.add_argument("--mod-defaults", action="store_true",
                    help="with --flow-mod: leave every setting of the mod as it ships, as a player's game has them")
    ap.add_argument("--with-built", default=None, metavar="FILE",
                    help="with --flow-mod: test the player's blueprint. The overflow exits and fluid voids of FILE (an "
                         "earlier run's --export-built) are merged into the test copy as blueprint parts, and the check "
                         "builds none itself; it only empties the exits' chests")
    ap.add_argument("--seed-budget", type=float, default=None, metavar="HOURS",
                    help="loop starters as a kit of known size: each machine is handed at most HOURS of its planned "
                         "use of a starter item or fluid in all, as it runs short (default: five times the plan's use "
                         "for as long as starters flow)")
    ap.add_argument("--seed-kit", type=float, default=None, metavar="N",
                    help="loop starters as one delivery: each machine taking a starter item starts with N crafts' "
                         "worth of it (a stack at most) and gets nothing afterwards; fluids start with a full box "
                         "(--prime-full). In place of topping up for hours whichever machine stands short")
    ap.add_argument("--export-built", default=None, metavar="FILE",
                    help="write the test copy as built in the game to FILE as a blueprint string, with the overflow exits "
                         "(--relief-exits) and fluid voids (--real-voids) the check made from real parts; "
                         "tools/factory-flow/tag.py --built FILE merges them into the run's blueprint")
    ap.add_argument("--seed-stop", type=float, default=None, metavar="S",
                    help="loop starters are handed out only in the first S game seconds (default: the whole warm-up)")
    ap.add_argument("--bootstrap", action="store_true",
                    help="a loop starved of its carrier for two minutes (barrels, cages) is given it, as --flow does and "
                         "as a player fills a new loop by hand; nothing else of --flow")
    ap.add_argument("--duty", type=float, default=None, metavar="FACTOR",
                    help="every machine is held to FACTOR times its planned rate by a duty cycle: it works a fixed share "
                         "of a clock's period (10 s, 10 min or 10 h, the shortest that gives it a second), as an enable "
                         "condition against a clock signal would; nothing is saved up")
    ap.add_argument("--relief-exits", action="store_true",
                    help="with --relief: the items are let out by real parts at the machine, a filter inserter into a "
                         "chest of its own with its filters set by deciders on a wire from the machine; fluids and "
                         "machines without room for them stay with the script")
    ap.add_argument("--pull-built", action="store_true",
                    help="pull from real parts: a decider at each machine per ingredient puts a demand signal on a wire "
                         "over the poles while the machine holds less than a craft's worth, and each maker has an "
                         "enable condition on a decider that reads the demand it can serve (use with --flow --no-pull)")
    ap.add_argument("--dead-end", action="store_true",
                    help="the overflow ports of items the factory uses stay shut: their belts are stock, and surplus "
                         "leaves at the producer only (--relief or --void-full)")
    ap.add_argument("--void-full", action="store_true",
                    help="a helper, 'virtual sinks': a machine standing with a full output (and not switched off by flow "
                         "control) has half its stuck products deleted every second, counted per recipe and item: does the "
                         "factory run once every by-product has somewhere to go, and where would real sinks be needed")
    ap.add_argument("--flow-mod", action="store_true",
                    help="the same flow control by the factory-flow mod (tools/factory-flow), as a player would run it: the test "
                         "copy is tagged by its tag.py, the mod is installed beside the check, and the factory is built raising "
                         "build events so the mod picks its machines up")
    ap.add_argument("--pull", action="store_true",
                    help="production follows demand: a machine runs only while a user of one of its products (on a belt it drops "
                         "onto, a machine it hands to, or for a fluid anywhere) stands short of it, or it makes a target")
    ap.add_argument("--governor", type=float, nargs="?", const=2.0, default=None, metavar="FACTOR",
                    help="steady state: a machine ahead of FACTOR (default 2) times its planned crafts (plus 10) waits while another "
                         "machine is short of one of its ingredients, as full buffers would make it; without it a consumer early on "
                         "a belt whose output fills hours of pipe takes everything first")
    ap.add_argument("--keep", action="store_true", help="keep the temporary game folder (log, save)")
    ap.add_argument("--robots", type=int, default=20, help="logistic robots put into each roboport (robot links; default 20)")
    ap.add_argument("--debug-at", action="append", default=[], metavar="X,Y",
                    help="log flow control's pull inputs for the machine at X,Y (as the report gives it) to the report's debug")
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
        plan_file = built_plan_path(args.target)
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
    if args.input_cap and ports:
        params["input_ports"] = [[i["x"] + 0.5, i["y"] + 0.5, i["item"].split("#")[0], i["rate"] * args.input_cap]
                                 for i in ports.get("inputs", []) if not i.get("fluid") and i["rate"] > 0]
        print(f"input-cap: {len(params['input_ports'])} raw input belts given at most {args.input_cap:g} times their planned rate")
    if args.dump:
        params["dump"] = True
    if args.prime_all:
        params["prime_all"] = True
    if args.prime_full:
        params["prime_all"] = True
        params["prime_full"] = True
    if args.provider_level is not None:
        params["provider_level"] = args.provider_level
        print(f"provider-level: a maker's exit to its provider chest closes above {args.provider_level:g} stacks in the network, where it also feeds a user itself")
    if args.overflow_last:
        params["overflow_last"] = True
        print("overflow-last: a machine's exit to an overflow chest works only once it has stood full for a minute")
    if args.seed_through:
        params["seed_through"] = True
        print("seed-through: loop starters are handed out while measuring too, counted by the hour")
    if args.void_wait is not None:
        params["void_wait"] = args.void_wait
    # Each recipe's planned intake per machine (items), fuel included: what a burner without fuel waits for, and what a
    # warm start puts in its fuel slot.
    dp = built_plan_path(args.target)
    if dp and os.path.exists(dp):
        demand_of = defaultdict(dict)
        for m in json.load(open(dp)).get("machines", []):
            rn = m["recipe"].split("#")[0].split("@")[0]
            for item, rate in m.get("demand", {}).items():
                g = item.split("#")[0]
                demand_of[rn][g] = max(demand_of[rn].get(g, 0.0), rate)
        params["demand_of"] = dict(demand_of)
    if args.flow:
        args.pull = not args.no_pull
        params["flow"] = True
        fp = built_plan_path(args.target)
        if fp and os.path.exists(fp):
            rates = defaultdict(dict)
            for m in json.load(open(fp)).get("machines", []):
                rn = m["recipe"].split("#")[0].split("@")[0]
                for item, rate in {**m.get("demand", {}), **m.get("fluidDemand", {})}.items():
                    g = item.split("#")[0]
                    rates[rn][g] = max(rates[rn].get(g, 0.0), rate)
            params["flow_rates"] = dict(rates)
        print("flow: quotas on what each machine takes, production by demand, overflows keep what users wait for")
    if args.pull_ahead:
        params["pull_ahead"] = args.pull_ahead
    if args.flow or args.pull or args.flow_mod:
        params["pull_stock"] = 1 if args.pull_stock is None else args.pull_stock
    if args.cap_slow:
        params["cap_slow"] = True
    if args.cap_fill:
        params["cap_fill"] = True
    if args.quota_window:
        params["quota_window"] = args.quota_window
    if args.quota_factor:
        params["quota_factor"] = args.quota_factor
    if args.pull:
        params["pull"] = True
        params["pull_wanted"] = sorted({t["name"] for t in (job or {}).get("targets", [])})
        print("pull: a machine runs only while a user of one of its products waits for it")
    params["targets"] = sorted({t["name"] for t in (job or {}).get("targets", [])})
    # Robot links (blocks.bot_links): their chests, so flow control treats each item's provider and requester belts as
    # one; the check puts logistic robots into the roboports.
    if ports and ports.get("bots"):
        params["bots"] = ports["bots"]
        params["robots_per_roboport"] = args.robots
    if args.debug_at:
        params["debug_at"] = args.debug_at
    if args.side_cap or args.rate_cap or args.meter or args.meter_file or args.relief_main or args.duty:
        fp = built_plan_path(args.target)
        if fp and os.path.exists(fp):
            purpose, crafts = defaultdict(set), defaultdict(float)
            for m in json.load(open(fp)).get("machines", []):
                rn = m["recipe"].split("#")[0].split("@")[0]
                used = {k.split("#")[0] for k in m.get("products", {}) if not k.endswith("#byproduct")}
                main = m.get("product", "").split("#")[0]
                purpose[rn] |= {main} if main in used else used
                crafts[rn] = max(crafts[rn], m.get("crafts", 0.0))
            if args.side_cap:
                params["side_cap"] = args.side_cap
                print(f"side-cap: a machine wanted only for a side product runs at most {args.side_cap} times its plan")
            if args.rate_cap:
                params["rate_cap"] = args.rate_cap
                print(f"rate-cap: no machine runs faster than {args.rate_cap} times its plan")
            # (A plan the planner solved with a margin, plan.json "margin", carries its own meter rates and carriers:
            # --meter 1 --meter-start then needs no rate file.)
            own = json.load(open(fp))
            if args.meter and not args.meter_file and (args.meter_start or args.vb_buffer or own.get("rings")):
                caps = {"ratio": [args.meter] * len(own.get("machines", [])), "exempt": own.get("rings", [])}
                args.meter = 1.0
            elif args.meter_file:
                caps = json.load(open(args.meter_file))
            else:
                caps = None
            if caps is not None:
                crafts = defaultdict(float)
                for m, ratio in zip(json.load(open(fp)).get("machines", []), caps["ratio"]):
                    rn = m["recipe"].split("#")[0].split("@")[0]
                    crafts[rn] = max(crafts[rn], m.get("crafts", 0.0) * ratio, 1e-9)
                args.meter = args.meter or 1.0
                params["void_except"] = {i.split("#")[0]: True for i in caps.get("exempt", [])}
                # A start-up allowance per recipe: the crafts that give every user of its products one craft's worth.
                pj = json.load(open(fp)); pm = pj.get("machines", [])
                per_out, per_in = defaultdict(dict), defaultdict(dict)
                for t in pj.get("terminals", []):
                    if "machine" in t and pm[t["machine"]].get("crafts", 0) > 0:
                        d = per_out if t["role"] == "PRODUCER" else per_in if t["role"] == "CONSUMER" else None
                        if d is not None:
                            d[t["machine"]][t["item"]] = d[t["machine"]].get(t["item"], 0.0) + t["rate"] / pm[t["machine"]]["crafts"]
                # (One craft's worth for each user machine, added up: on a belt the first users stock up before the
                # last sees anything.)
                # (--meter-start-depth D: the users' own allowances count too, D levels down. A user that starts with
                # crafts in hand takes that much more from its makers at the start, and a fluid user fills a tank of two
                # crafts besides: Py chemical science's fusion reactor took 66,000 of the first 69,000 pressured water
                # where its plan share was 57,000, the heat exchanger behind it stood at a fifth of its rate for ten
                # hours, and the steam chain behind that paced the science packs.)
                base_of = lambda mi: pm[mi]["recipe"].split("#")[0].split("@")[0]
                start = defaultdict(float)
                for _ in range(max(1, args.meter_start_depth)):
                    biggest = defaultdict(float)
                    for mi, ins in per_in.items():
                        if caps["ratio"][mi] > 0:
                            for item, a in ins.items():
                                biggest[item] += a * (args.meter_start_factor + (start.get(base_of(mi), 0.0) if args.meter_start_depth > 1 else 0.0))
                    nxt = defaultdict(float)
                    for mi, outs in per_out.items():
                        rn = base_of(mi)
                        need = max([args.meter_start_factor] + [biggest[item] / p for item, p in outs.items() if p > 0 and biggest[item] > 0])
                        nxt[rn] = max(nxt[rn], float(int(min(need, args.meter_start_max) + 0.999)))
                    start = nxt
                if args.vb_buffer:
                    # A stock of every item in the network's storage: that many seconds of its use at the metered rates
                    # (never less than four crafts of its biggest user).
                    use, single = defaultdict(float), defaultdict(float)
                    for mi, ins in per_in.items():
                        for item, amt in ins.items():
                            g = item.split("#")[0]
                            use[g] += amt * pm[mi]["crafts"] * caps["ratio"][mi]
                            single[g] = max(single[g], amt)
                    params["vb_caps"] = {g: max(use[g] * args.vb_buffer, 4 * single[g]) for g in use}
                    print(f"vb-buffer: the storage holds up to {args.vb_buffer:.0f} s of each item's use ({len(params['vb_caps'])} items)")
                if args.meter_start:
                    params["meter_start"] = dict(start)
                    print(f"meter-start: each meter starts with its users' first batch in hand (up to {max(start.values()):.0f} crafts)")
                print(f"meters at {'the plan' + chr(39) + 's own rates' if not args.meter_file else 'rates with a margin from ' + args.meter_file}; {len(params['void_except'])} carriers kept out of the virtual sinks")
            if args.meter:
                params["meter"] = args.meter
                if args.meter_fill:
                    k, h = (float(v) for v in args.meter_fill.split(":"))
                    params["meter_fill"] = [k, h]
                    print(f"meter-fill: meters run {k:g} times faster for the first {h:g} game hours")
                if args.meter_bucket:
                    params["meter_bucket"] = args.meter_bucket
                    print(f"meter-bucket: a waiting machine saves up to {args.meter_bucket:.0f} s of its planned rate")
                print(f"meter: every machine held to {args.meter} times its planned rate")
            if args.side_cap or args.rate_cap or args.relief_main:
                params["purpose"] = {r: {p: True for p in ps} for r, ps in purpose.items()}
            if args.relief_main:
                # What each recipe is planned for: its products the plan uses up (a planned surplus of at most a
                # twentieth of what is made of the item), or failing that the one with the least surplus. The plan's
                # own "main product" will not do: a slaughterhouse's is bones, and its brains are what the pack needs.
                pl = json.load(open(fp))
                made, tg = defaultdict(float), {t["name"] for t in (job or {}).get("targets", [])}
                for m in pl.get("machines", []):
                    for k, v in m.get("products", {}).items():
                        made[k] += v
                surplus = pl.get("outputs", {})
                cons = {t["item"] for t in pl.get("terminals", []) if t.get("role") == "CONSUMER"}
                tight = {}
                for m in pl.get("machines", []):
                    rn = m["recipe"].split("#")[0].split("@")[0]
                    fr = {}
                    for k in m.get("products", {}):
                        base = k.split("#")[0]
                        if base in tg:
                            fr[base] = 0.0
                        elif k in cons or base in cons:
                            fr[base] = min(fr.get(base, 9.0), (surplus.get(k, 0.0) / made[k]) if made[k] > 0 else 0.0)
                    if fr:
                        keep = {b for b, f in fr.items() if f <= 0.05} or {min(fr, key=fr.get)}
                        tight[rn] = tight.get(rn, set()) | keep
                params["purpose"] = {r: {p: True for p in ps} for r, ps in tight.items()}
                params["relief_main"] = True
                print(f"relief-main: {sum(len(v) for v in tight.values())} products of {len(tight)} recipes count as what their machines are wanted for")
            params["plan_crafts"] = dict(crafts)
            if args.duty:
                params["duty"] = args.duty
                print(f"duty: every machine works the share of a clock's period that gives {args.duty} times its planned rate")
    if args.robot:
        params["robot"] = args.robot
    if args.requester_demand:
        params["requester_demand"] = True
    if args.virtual_pumps:
        params["virtual_pumps"] = True
    if args.request_need:
        params["request_need"] = True
        print("request-need: requester chests ask only while their machine is short of a craft's worth")
    if args.request_trip:
        params["request_trip"] = args.request_trip
        print(f"request-trip: and at least {args.request_trip:g} robot trips' worth of their link's flow")
    if args.vb_share:
        params["vb_share"] = args.vb_share
        print("vb-share: the smallest planned user of an item is served first")
    if args.vb_fluids:
        params["vb_fluids"] = True
        print("vb-fluids: fluids move like the items, from makers' outputs to a stock and on to machines in need")
    if args.provider_slots:
        params["provider_slots"] = args.provider_slots
    if args.no_quotas:
        params["no_quotas"] = True
    if args.fluid_gates:
        params["fluid_gates"] = True
    if args.overflow_keep is not None:
        params["overflow_keep"] = args.overflow_keep
    if args.relief_exits:
        args.relief = True  # (fluids, and machines with no room for the parts, stay with the script's relief)
    if args.relief:
        params["relief"] = True
        print("relief: a product blocking a machine is taken out while another used product of it still leaves")
    if args.dead_end:
        params["dead_end"] = True
    if args.pull_built:
        params["pull_built"] = True
        print("pull-built: demand deciders and enable conditions built in the game")
    if args.relief_exits:
        params["relief_exits"] = True
        print("relief-exits: an inserter, a chest and deciders at each machine that needs them, built in the game")
    if args.relief_demand:
        params["relief_demand"] = True
    if args.dead_end or args.relief_gate or args.relief_built:
        tg = {t["name"] for t in (job or {}).get("targets", [])}
        params["target_at"] = [[o["x"] + 0.5, o["y"] + 0.5] for o in ports.get("outputs", []) if not o.get("fluid") and o["item"].split("#")[0] in tg]
    if args.relief_built:
        params["relief_built"] = True
        print("relief-built: deciders, wires and belt gates built in the game; the check gates no overflow port")
    if args.relief_gate:
        params["relief_gate"] = True
        print("relief-gate: overflow ports of used items open only for a maker blocked by that item and wanted for another")
    if args.void_full:
        params["void_full"] = True
    if args.virtual_bots:
        params["virtual_bots"] = True
        if args.vb_stock: params["vb_stock"] = args.vb_stock
        args.belt_pull = True
        print("virtual-bots: items are moved from makers' outputs to machines in need; output inserters stand")
    if args.belt_pull:
        params["belt_pull"] = True
        print("belt-pull: output inserters work only while a user on their belt is below its stock")
        print("void-full: full machines have their surplus deleted (virtual sinks), counted")
    if args.feed_short:
        params["feed_short"] = args.feed_short
        if args.feed_items:
            params["feed_items"] = {n.strip(): True for n in args.feed_items.split(",") if n.strip()}
        if args.feed_stop is not None:
            params["feed_stop"] = args.feed_stop
        fp = built_plan_path(args.target)
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
        gp = built_plan_path(args.target)
        if gp and os.path.exists(gp):
            per = defaultdict(float)
            for m in json.load(open(gp)).get("machines", []):
                rn = m["recipe"].split("#")[0].split("@")[0]
                per[rn] = max(per[rn], m.get("crafts", 0.0))
            params["governor"] = {k: v for k, v in per.items() if v > 0}
            params["governor_factor"] = args.governor
            print(f"governor: a machine ahead of {args.governor:g}x its planned crafts waits while another is short of its ingredients")
    # Production loops the planner found (plan.json "seeds"): their machines get a few crafts' worth to start with.
    plan_path = built_plan_path(args.target)
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
    if args.flow_mod:
        if args.flow or args.pull:
            sys.exit("--flow-mod runs the factory-flow mod instead of the check's own --flow/--pull: pass one or the other")
        if not out or not os.path.exists(os.path.join(out, "plan.json")):
            sys.exit("--flow-mod needs a run's out folder (plan.json, ports.json) to tag the blueprint from")
        sys.path.insert(0, FLOW_MOD)
        import tag as flow_tag
        data_file = find_file((job or {}).get("data") or "data/recipes.json")
        recipes = json.load(open(data_file))["recipes"] if data_file else {}
        bp_obj = flow_tag.decode(bp)
        tagged, ends = flow_tag.tag(bp_obj, json.load(open(built_plan_path(out))), ports or {},
                                    {t["name"] for t in (job or {}).get("targets", [])}, recipes)
        if args.with_built:
            # The player's blueprint: the overflow exits and fluid voids an earlier run built from real parts, merged
            # in (tag.py --built does the same for the blueprint itself). The test copy's infinity pipes where a real
            # void now stands go; the check only empties the exits' chests, as a player would.
            ents = bp_obj["blueprint"]["entities"]
            pipes = {e["name"] for e in ents if "pipe" in e["name"] and "infinity" not in e["name"]} | {"pipe"}
            before = {(round(e["position"]["x"], 2), round(e["position"]["y"], 2)) for e in ents}
            added = flow_tag.merge_built(bp_obj, flow_tag.decode(open(args.with_built).read()), pipes)
            ents = bp_obj["blueprint"]["entities"]
            new_at = {(round(e["position"]["x"], 2), round(e["position"]["y"], 2)) for e in ents if e["name"] != "infinity-pipe"}
            gone = [e for e in ents if e["name"] == "infinity-pipe" and (round(e["position"]["x"], 2), round(e["position"]["y"], 2)) in new_at]
            dead = {e["entity_number"] for e in gone}
            bp_obj["blueprint"]["entities"] = [e for e in ents if e["entity_number"] not in dead]
            bp_obj["blueprint"]["wires"] = [w for w in bp_obj["blueprint"].get("wires", []) if w[0] not in dead and w[2] not in dead]
            if args.relief_exits or args.real_voids:
                sys.exit("--with-built brings the exits and voids with the blueprint: leave out --relief-exits and --real-voids")
            params["bp_exits"] = True
            params["relief"] = True
            print(f"with-built: {', '.join(f'{v} {k}' for k, v in sorted(added.items()))} from {args.with_built}; "
                  f"{len(gone)} of the test copy's infinity pipes replaced by real voids")
        bp = flow_tag.encode(bp_obj)
        params["raise_built"] = True
        # The mod's settings as the check's options give them.
        ms = {}
        if args.pull_ahead:
            ms["factory-flow-pull-ahead"] = args.pull_ahead
        if args.pull_stock is not None:
            ms["factory-flow-stock"] = args.pull_stock
        if args.quota_window:
            ms["factory-flow-quota-window"] = int(args.quota_window // 60)
        if args.quota_factor:
            ms["factory-flow-quota-factor"] = args.quota_factor
        if args.side_cap:
            ms["factory-flow-side-cap"] = args.side_cap
        if args.rate_cap:
            ms["factory-flow-rate-cap"] = args.rate_cap
        # (The mod's own loop-first and share rules, in place of the check's.)
        ms["factory-flow-loop-first"] = bool(args.loop_first)
        ms["factory-flow-loop-strict"] = bool(args.loop_strict)
        ms["factory-flow-reserve"] = int(args.reserve or 0)
        ms["factory-flow-loop-stock"] = float(args.loop_stock or 0)
        ms["factory-flow-loop-keep"] = float(args.loop_keep or 0)
        ms["factory-flow-loop-keep-time"] = float(args.loop_keep_time or 0)
        ms["factory-flow-loop-share"] = bool(args.loop_share)
        ms["factory-flow-loop-fluids"] = bool(args.loop_fluids)
        ms["factory-flow-shares"] = bool(args.out_share)
        ms["factory-flow-slow-machines"] = bool(args.cap_slow)
        ms["factory-flow-cap-fill"] = bool(args.cap_fill)
        args.loop_first = None
        args.out_share = None
        if args.mod_defaults:
            ms = {}
        if ms:
            params["mod_settings"] = ms
        # (Loop carriers as --flow puts them in: a player fills a new loop by hand.)
        params["bootstrap"] = True
        print(f"flow mod: {tagged} machines and {ends} overflow belts tagged; the factory-flow mod controls them, "
              f"the check puts in loop carriers as a player would")
    if args.carrier_kit:
        params["carrier_kit"] = args.carrier_kit
        # (No overflow takes a carrier: --void-full, --relief and --relief-exits leave them in the machine.)
        params["void_except"] = {**{c: True for c in params.get("seed_carriers", [])}, **params.get("void_except", {})}
        print(f"carrier-kit: empty carriers only, {'during the warm-up' if args.carrier_kit == 'warmup' else 'counted through the run'}; "
              f"no overflow takes {', '.join(sorted(params['void_except'])) or 'any (the plan names no carriers)'}")
    if args.belt_hold:
        params["belt_hold"] = args.belt_hold
        print(f"belt-hold: an inserter onto a belt works only while that belt holds less than {args.belt_hold:g} crafts' worth of the item (wired in the game)")
    if args.out_share:
        params["out_share"] = args.out_share
        params["out_share_mode"] = args.out_share_mode
        print(f"out-share: a maker's inserters onto several belt networks carry each network's planned share (within {args.out_share:g} times), mode {args.out_share_mode}")
    if args.loop_first:
        lp = built_plan_path(args.target)
        if not (lp and os.path.exists(lp)):
            sys.exit("--loop-first needs the run's plan")
        def _b(n): return n.split("#")[0].split("@")[0]
        recs = {}
        for m in json.load(open(lp)).get("machines", []):
            r = recs.setdefault(_b(m["recipe"]), {"in": set(), "out": set()})
            r["in"] |= {_b(k) for k in m.get("demand", {})}
            r["out"] |= {_b(k) for k in m.get("products", {})}
        makers = defaultdict(set)
        for n, r in recs.items():
            for o in r["out"]:
                makers[o].add(n)
        adj = {n: set() for n in recs}
        for n, r in recs.items():
            for i in r["in"]:
                for mk in makers.get(i, ()):
                    adj[mk].add(n)
        # A user's way back to a maker of the item, in recipes: 0 when it makes the item itself (a catalyst's farm), 1
        # when what it makes goes straight into a maker, and so on; None when nothing it makes ever comes back.
        # Py logistic science is one loop of 53 recipes by way of ash, biomass and barrels, so "in the loop" says
        # nothing: the users within three steps are the item's own loop, the rest take what that leaves.
        from collections import deque
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
                        seen.add(w); todo.append((w, d + 1))
            return None
        inside, outside = defaultdict(dict), defaultdict(dict)
        for item, mks in makers.items():
            for n, r in recs.items():
                if item in r["in"]:
                    d = back(mks, n)
                    (inside if d is not None and d <= 3 else outside)[item][n] = True
        # (--loop-fluids: fluid loops too, found over the graph with fluid inputs counted as well; items keep the
        # classification above. The margin layout's syngas goes round by way of refined syngas and oleochemicals, and
        # the plastic maker and four other users outside took it from the shared pipes until the loop stood.)
        fluid_loops = []
        if args.loop_fluids:
            fin = defaultdict(set)
            for m in json.load(open(lp)).get("machines", []):
                fin[_b(m["recipe"])] |= {_b(k) for k in m.get("fluidDemand", {})}
            for n, fs in fin.items():
                for f in fs:
                    for mk in makers.get(f, ()):
                        adj[mk].add(n)
            # (Not a fluid that an input pipe brings: it does not run out.)
            raw_f = {i["item"].split("#")[0] for i in (ports or {}).get("inputs", []) if i.get("fluid")}
            for f in {f for fs in fin.values() for f in fs}:
                mks = makers.get(f, set())
                if not mks or f in raw_f:
                    continue
                ins_, outs_ = {}, {}
                for n, fs in fin.items():
                    if f in fs:
                        d = back(mks, n)
                        (ins_ if d is not None and d <= 3 else outs_)[n] = True
                if ins_ and outs_:
                    inside[f], outside[f] = ins_, outs_
                    fluid_loops.append(f)
        both = {i for i in inside if i in outside}
        params["loop_first"] = {"window": args.loop_first, "inside": {i: inside[i] for i in both}, "outside": {i: outside[i] for i in both}}
        if fluid_loops:
            params["loop_first"]["fluids"] = True
            print(f"loop-fluids: {len(fluid_loops)} fluids of production loops go to the loop's own users first ({', '.join(sorted(fluid_loops))})")
        if args.loop_strict:
            params["loop_first"]["strict"] = True
        if args.loop_quota:
            params["loop_first"]["quota"] = True
        if args.loop_stock:
            params["loop_first"]["stock"] = args.loop_stock
        if args.loop_keep:
            params["loop_first"]["keep"] = args.loop_keep
        if args.loop_keep_time:
            params["loop_first"]["keep_time"] = args.loop_keep_time
        if args.loop_valves:
            params["loop_valves"] = True
        if args.loop_port_catalyst:
            params["loop_port_catalyst"] = True
        if args.loop_share:
            params["loop_share"] = True
        if args.reserve:
            params["reserve"] = args.reserve
        if args.reserve_all:
            params["reserve_all"] = True
        if args.reserve_items:
            params["reserve_items"] = True
        print(f"loop-first: {len(both)} items of production loops go to the loop's own users first ({', '.join(sorted(both)[:12])}{', ...' if len(both) > 12 else ''})")
    if args.flow and built_plan_path(args.target) and os.path.exists(built_plan_path(args.target)):
        # Machines the plan has only to get rid of something (sinks.burn): every product goes to a burner or to other
        # such machines. They take their ingredients last.
        def _bn(n): return n.split("#")[0].split("@")[0]
        precs = {}
        for m in json.load(open(built_plan_path(args.target))).get("machines", []):
            r = precs.setdefault(_bn(m["recipe"]), {"in": set(), "out": set(), "void": False})
            r["in"] |= {_bn(k) for k in {**m.get("demand", {}), **m.get("fluidDemand", {})}}
            r["out"] |= {_bn(k) for k in m.get("products", {})}
            r["void"] = r["void"] or "-pyvoid" in m["recipe"] or m.get("machine") == "py-burner"
        if any(r["void"] for r in precs.values()):
            users = defaultdict(set)
            for n, r in precs.items():
                for i in r["in"]:
                    users[i].add(n)
            tg = {t["name"] for t in (job or {}).get("targets", [])}
            done = {n for n, r in precs.items() if r["void"]}
            disposal = set()
            while True:
                more = {n for n, r in precs.items() if n not in done and r["out"] and not (r["out"] & tg)
                        and all((users[o] - {n}) <= done for o in r["out"])}
                if not more:
                    break
                disposal |= more
                done |= more
            if disposal:
                params["disposal"] = {n: True for n in disposal}
                print(f"disposal last: {len(disposal)} recipes that only feed burners take their ingredients last ({', '.join(sorted(disposal))})")
    if args.real_voids:
        params["real_voids"] = True
        print("real-voids: fluid ports end in an overflow valve and Py's exhaust pipe or sinkhole")
    if args.seed_budget:
        params["seed_budget"] = args.seed_budget
        print(f"seed-budget: each machine is handed at most {args.seed_budget:g} hours of its planned use of a starter")
    if args.seed_kit:
        params["seed_kit"] = args.seed_kit
        params["seed_stop"] = 30 if args.seed_stop is None else args.seed_stop
        print(f"seed-kit: starters once, {args.seed_kit:g} crafts' worth at each machine that takes one")
    if args.export_built:
        params["export_built"] = os.path.abspath(args.export_built)
    if args.seed_stop is not None and not args.seed_kit:
        params["seed_stop"] = args.seed_stop
        print(f"seed-stop: loop starters only in the first {args.seed_stop:g} game seconds")
    if args.bootstrap:
        params["bootstrap"] = True
        print("bootstrap: starved loops get their carriers from the check, as from a player")
    params = {k: v for k, v in params.items() if v is not None}
    report = run_game(args, bp, params, enabled, have)
    report["mods"] = enabled
    if out:
        with open(os.path.join(out, "verify_report.json"), "w") as f:
            json.dump(report, f, indent=1)
    args.launched = {}
    if out and os.path.exists(os.path.join(out, "plan.json")):
        with open(built_plan_path(out)) as f:
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
