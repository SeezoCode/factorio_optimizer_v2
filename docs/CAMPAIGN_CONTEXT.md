# Py science campaign: persistent context

This file is the working memory of the campaign that started 2026-11-26 23:00 UTC in a Claude Code cloud
session. It is written so that work can resume from it alone (after a context reset or in a new container). Keep it
current; the log of what was tried is [CAMPAIGN_LOG.md](CAMPAIGN_LOG.md), the results table is in the
[readme](../readme.md#py-science-campaign) and the detail per science in [PY_SCIENCE_PROGRESS.md](PY_SCIENCE_PROGRESS.md).

## The task (from the user, 2026-11-26)

- Make **every Py science work**: the 11 jobs in `jobs/py_sciences/` (automation, py-1, logistic, py-2, military,
  chemical, py-3, production, py-4, utility, space), each at 0.02 packs/s.
- **Start with a lot of help from the check/mod and work down to fair in-game mechanics** where a science holds.
- Progress table in the readme plus a detailed explanation; report each science that is conquered.
- This file as persistent context; [CAMPAIGN_LOG.md](CAMPAIGN_LOG.md) for what was tried, what worked, what not.
- Save storage: no images (`FO_NO_IMAGES=1`, see below).
- Give the user a link to look at the builds: **https://claude.ai/artifact/7rqXsL9UZFaMF8m7fdcyEk** (private to
  the user). Rebuild with `python3 tools/py-campaign/builds_page.py <scratchpad>/builds` and republish that
  folder's `index.html` with `root` = the folder and `files` = `status.json` plus every `data/<science>.json`
  (same file path keeps the URL; from a new session pass the URL as `url`).
- Keep-alive: the user worries the container stops after 30 idle minutes. Three hourly scheduled check-ins fire
  into this session at :07, :27 and :47 (trig_01Ea9ZbET52dNvSMRFxCnHL7, trig_018cG15oJoz8fTM8NfK78W9W,
  trig_01Sx2onJBB9rXDpDoAhCujUu). Delete them at the end (2026-11-29 19:00 UTC report).
- Use all the CPU. Let runs take as long as they need.
- **Belts only, no robots** (user, 2026-11-26 23:26): layouts must be belt-built; **sinks are allowed**
  (`blocks.local_sinks`: by-products end in a chest or Py's fluid void beside their block).
- Push only to the branch `claude/practical-davinci-i6iqkj-6m4aie` (the user chose that; not main).
- **Do not stop** unless (a) stuck and progress stalls, (b) a very important decision, (c) **2026-11-29 20:00 CET
  (19:00 UTC)**: then report progress. The user is in exam season and will not intervene much.

## Environment (cloud container, 4 cores, 16 GB, ~60 GB disk allowance)

Set up as in [CLOUD_SETUP.md](CLOUD_SETUP.md); the container is lost when the session ends, so redo it there.

- Java 25: `/opt/jdk/jdk-25*` (the container's own JAVA_HOME is Java 21: always set JAVA_HOME explicitly).
- Build: `JAVA_HOME=/opt/jdk/jdk-25.0.5.1+2 ./gradlew -q jar -Porg.gradle.java.installations.paths=$JAVA_HOME`, then
  snapshot: `V=/opt/fo/$(date +%m%d-%H%M); cp -r build/install/factorio_optimizer_v2 $V; cp build/libs/*.jar $V/lib/;
  ln -sfn $V /opt/fo/current`. **Never run installDist while a solve runs** (it overwrites the jars under the JVM);
  solve.sh and resume.sh run `/opt/fo/current`. (The m2 queue's `solve_queue.sh` still runs build/install.)
- Factorio 2.1.24 headless `/opt/factorio`, mods `/opt/factorio-user/mods` (23 Py mods; graphics mods without
  images from branch `mods-upload`), `/usr/share/factorio -> /opt/factorio/data`.
- `FACTORIO=/opt/factorio/bin/x64/factorio FACTORIO_USER_DIR=/opt/factorio-user` for verify.py (go.sh sets them).
- The shell profile cannot be edited (blocked); set variables per command.
- `FO_NO_IMAGES=1`: the optimizer writes no PNGs (new switch in `viz/Render.kt`). Draw one later with
  `--render=<dir>` (without the variable).

## Tools of the campaign (`tools/py-campaign/`)

- `solve_queue.sh <suffix> <margin> <threads> <science...>`: solves `jobs/py_sciences/<science>.json` into
  `solves/<science>_<suffix>` one after another (job written to `solves/jobs/<science>_<suffix>.json`).
- `resume.sh <layout>`: continue a failed factory solve with the current snapshot (keeps finished blocks); logs to
  `solves/queue_resume.log`. Delete a failed run's old `end` line from its queue log first, or launch.py may take a
  preview for the finished layout.
- `go.sh <run> <layout> <verify flags>`: copies a layout into `solves/<run>` and starts verify.py in the background
  (`verify.out`, `cmd.txt`).
- `launch.py add <run> <layout> <level> [flags]`, `launch.py sched --max N` (scheduler loop, keeps N games
  running, queue in `solves/campaign_queue.json`, log `solves/sched.log`), `launch.py status`.
- `watch.sh`: the Monitor script (re-arm it every 30 min: Monitor command `tools/py-campaign/watch.sh`).
- Probes in `tools/factorio-verify/probes` (README there): `sum.py <run>` packs by hour for a live or finished run.

## Help levels (most help first)

| Level | Name | Flags (see launch.py) | Warm-up / measured |
|---|---|---|---|
| L5 | fed | `--prime-full --feed-short full --void-full` | 1 h / 3 h |
| L4 | ideal | `--prime-full --flow --virtual-bots --vb-fluids --meter 1.02 --meter-start --void-full` | 2 h / 6 h |
| L3 | rules | fair set + the rule set that passes logistic science (script) | 2 h / 22 h |
| L2 | mod | the same by the factory-flow mod (`--flow-mod`) | 2 h / 22 h |
| L1 | built | fair set + `--flow --no-pull --pull-built` (real parts) | 2 h / 22 h |
| L0 | plain | `--prime-all` only | 2 h / 22 h |

Fair set: `--prime-all --carrier-kit --seed-stop 7200 --real-voids --relief --relief-exits` (a warm start, empty
carriers and loop starters in the first two hours only, Py's own voids for fluids, overflow exits built from real
parts). Rule set (from the night logs of 2026-11-17 to 11-25): `--flow --quota-window 1800 --loop-first --out-share
--rate-cap 2 --cap-slow --cap-fill --loop-stock 2 --reserve 4 --loop-keep 2 --loop-share --loop-fluids
--loop-keep-time 3600 --quota-catalyst --loop-short-on --loop-short-reach --loop-dead-end --seed-carrier-cut
--share-wait-on --seed-own-wait --relief-own-jam --splitter-priority`. The exact command lines of the desktop runs
were lost with the desktop's scratchpad; this is reconstructed from the logs.

"Works" at a level: verify.py's verdict passes (every machine crafted, the pack at its planned rate within 5% at the
port over the measured window) over the level's full measured window.

## State before the campaign (from the night logs, desktop, 10 cores)

- Py science 1: 24 h at plan on fair help, under the check and the mod (2026-11-18).
- Logistic science (2% margin layout): 24 h passes, 4 of 6 check runs and 2 of 2 mod runs (2026-11-23/24).
- Py science 2: at plan 2 to 3 h after starters, then stops (long belts, loops with no margin).
- Chemical science (2% margin): at or above plan to hour 7 to 10 (fish loop), 2026-11-25.
- Automation science: passes alone and under the mod (2026-11-09).
- Military, py-3, production, py-4, utility, space: no record of a run that made packs. Production science made 0%
  in the ideal-transport model unless every machine is held to its plan (WHY_NO_SCIENCE.md).
- None of the desktop's layouts (`solves/`, not in git) came along: everything is solved again here.

## Current state

(Update this section as the campaign goes.)

- 2026-11-26 23:20 UTC: solve queue m2 (2% margin, 4 threads) running in order automation, py-1, logistic,
  military, py-2, chemical, py-3, production, py-4, utility, space. Automation solved in 3 s (13 machines, 76x76).
  Py's planner spends minutes per job in its fixed-charge MIP (600 s limit per call).
- Automation science L3 test run (10 + 10 game minutes): PASS. Full ladder (L5..L0) queued 23:20.
- 23:25 UTC: a robot-layout queue (r2) was started and stopped a minute later: the user wants belts only.
- 23:26 UTC: second solve queue **s2** = belts with local sinks (`tools/py-campaign/solve.sh`, JOB_PATCH
  `{"blocks": {"local_sinks": 0.1}}` (1000 failed on production: fluid voids at every block), 2% margin, 2 threads), big sciences first: production, utility, py-4, space,
  py-3, chemical, py-2. Log `solves/queue_s2.log`. The m2 queue (plain belts) works from the small end; when the
  two meet, stop the duplicates. (`solve_queue.sh` is the m2 queue's running copy: do not edit it while it runs.)
