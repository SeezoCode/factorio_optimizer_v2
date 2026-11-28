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
- **Chemical and below first** (user, 2026-11-27 16:00): make sure automation, py-1, logistic, military, py-2 and
  chemical work before continuing with py-3, production, py-4 (utility and space were stopped earlier).
  Production's stitch is paused (SIGSTOP, pid in `pgrep -f resume=solves/production`), py-3 runs at the queue's end.
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
| L3s | rules + starters | fair set without `--seed-stop`, + rule set + `--seed-through` (loop starters all along) | 2 h / 10 h |
| L2s | mod + starters | the same with the factory-flow mod (`--flow-mod`) in place of `--flow` | 2 h / 10 h |
| L1s | built + starters | L1 + `--seed-through` (starters all along) | 2 h / 10 h |
| L3 | rules | fair set + the rule set (script) | 2 h / 22 h (big factories 10 h) |
| L2 | mod | fair set + `--flow-mod` + the rule set less `--flow` | 2 h / 22 h (big factories 10 h) |
| L1 | built | fair set + `--pull-built --self-exit-built --reserve-built --reserve-self` (real parts only; before 04:35 on 11-27 it had `--flow --no-pull --pull-built`: those runs are level L1f) | 2 h / 22 h (big factories 10 h) |
| L0 | plain | `--prime-all` only | 2 h / 22 h |

Ranking for "fairest pass" (launch.py, builds_page.py): L5, L4, L3s, L2s, L1s, L3, L2, L1, L0 (a level with starters
all along counts as more help than any level without). Extras a run adds (`--loop-deep 7`, `--carrier-kit run`,
`--rate-cap 1.5`, ...) are listed per run in solves/<run>/cmd.txt; check fixes (place chests, ground pickup, relief
limits, heated fluids, the kit's bootstrap) apply to every run started after them.

Fair set: `--prime-all --carrier-kit --seed-stop 7200 --real-voids --relief --relief-exits --exit-catalyst` (a warm start, empty
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

(Rewrite this section as the campaign goes; history is in CAMPAIGN_LOG.md.)

As of 2026-11-27 23:30 UTC:

- **Laid out** (solves/): automation_m2 (13 machines), py-science-pack-1_m2 (50), military_m2 (13), logistic_m2
  (386), py-science-pack-2_m2b (411), chemical_m2 (487), py-science-pack-3_m2 (569).
- **Solving, paused for the user's priority** ("make sure the sciences at chemical and below work before
  continuing"): production_s3's stitch (JVM pid 22654 under SIGSTOP; `kill -CONT` to resume; global routing
  converged, fine routing 1,857-2,283 conflicts); py-science-pack-4_s4 (51 blocks done; a waiter starts its stitch
  after production's JVM exits). Utility and space stopped (beyond this machine on belts).
- **Results**: automation and military conquered at L0, py-1 at L1. Logistic, py-2, chemical, py-3 passed L5.
  L4 broke on the big ones; L3's governors (hot-air quota, rate cap) hold logistic's tin makers. L2 (the mod) runs
  best; logi_L2d's mukmoux bones loop drained by hour 3.5 (stock stranded past its users), logi_L2s (L2 + starters
  all along) holds past it (34 packs of 23 due at hour 3.7). py2_L2 ahead of plan at hour 3.5. Chemical behind at
  L2 (rubber / saps / fertilizer; chem_L2 stopped), chem_L2s started.
- **Check fixes today**: `--loop-deep D` (verify.py and the mod's tag.py; long Py loops become loop items, users at
  the nearest distance only, at least half the use, no target maker outside); `--pull-built` counts a heated fluid
  product even where the fluid is also an ingredient (outlet-gas ring); `--carrier-kit` turns on `bootstrap` without
  `--flow` or the mod (the kit did nothing at L1). New level L2s.
- **Check fixes of the 28th**: machines that place results on their own tile (`vector_to_place_result`: Py's wood
  processing units, sand/limestone extractors, classifiers, casting units) get a steel chest there, emptied into
  their output every ten ticks (they had been held to about one item a second); relief_limit is one craft for them
  and "waiting for space in destination" counts as full. `--loop-valves` breaks the mod's fluid pull (not for L2).
  Built reserve gates under the mod gridlock logistic's brick ring at hour 2 (3 of 3); not used at L2.
- **Running now**: logi_L1f (L1 + loop-deep, all fixes), logi_L2s, py2_L2, chem_L2s. Queue: logi_L2e (L2 +
  loop-deep + built reserve gates), chem_L1b, py2_L1b (both with loop-deep), logi_L0, logi_L3d, chem_L3d, py2_L0,
  chem_L0, then py-3's ladder.
- **Probes worth knowing**: `probes/sum.py <run>`, `probes/chainlive.py <run> <pack>`, `probes/lua.py <run> <file>`
  (Lua 5.2: no `//`; in Factorio 2.1 an entity has no `fluidbox` key, use `fluids_count`, `get_fluid(i)`,
  `get_fluid_box_prototype(i)`, `has_fluid_segment(i)`), `probes/samp_bash.sh` with `ls_logi.lua`.
- **Production's stitch stopped for good 2026-11-28 12:05 UTC** (the container restarted; it had not stitched in about
  24 hours of fine routing). Blocks kept in solves/production-science-pack_s3/resume; resume with
  `resume.sh production-science-pack_s3 --config=solves/jobs/production-science-pack_s3_c40g.json` (global routing,
  corridors 40). Four game slots now (`sched --max 4`). Background tasks do not survive a container restart: after
  one, restart the scheduler and the runs (verify runs cannot resume; start them under new names).
- **Check fixes of the 28th**: machines that place results on their own tile (`vector_to_place_result`: Py's wood
  processing units, sand/limestone extractors, classifiers, casting units) get a steel chest there, emptied into
  their output every ten ticks (they had been held to about one item a second); relief_limit is one craft for them
  and "waiting for space in destination" counts as full. `--loop-valves` breaks the mod's fluid pull (not for L2).
  Built reserve gates under the mod gridlock logistic's brick ring at hour 2 (3 of 3); not used at L2.
- **Running now**: logi_L1f (L1 + loop-deep, all fixes), logi_L2s, py2_L2, chem_L2s. Queue: logi_L2e (L2 +
  loop-deep + built reserve gates), chem_L1b, py2_L1b (both with loop-deep), logi_L0, logi_L3d, chem_L3d, py2_L0,
  chem_L0, then py-3's ladder.
- **Probes worth knowing**: `probes/sum.py <run>`, `probes/chainlive.py <run> <pack>`, `probes/lua.py <run> <file>`
  (Lua 5.2: no `//`; in Factorio 2.1 an entity has no `fluidbox` key, use `fluids_count`, `get_fluid(i)`,
  `get_fluid_box_prototype(i)`, `has_fluid_segment(i)`), `probes/samp_bash.sh` with `ls_logi.lua`.
- **Production's stitch resumed 2026-11-28 06:00 UTC** (pid 22654, nice -5; `kill -STOP` to pause again) with three
  game slots (`launch.py sched --max 3`). When it ends (solves/queue_resume.log), its L5 check goes into the queue,
  and the py-4 waiter starts py-4's stitch. Logistic passed L2s (03:45).
- **Scheduler**: `launch.py sched --max 4` (one copy only: `pgrep -af launch.py`). It writes the queue back each time it starts a run: stop it (`kill`) before editing `solves/campaign_queue.json`, restart it after (`nohup python3 tools/py-campaign/launch.py sched --max 4 >> solves/sched.log 2>&1 &`); restart it too after editing launch.py (LEVELS are read once), games at nice 10 (go.sh); renice a stitching solve to -5.
- **Check additions this campaign**: `--reserve-built`, `--reserve-self`, `--self-exit-built`. L1 =
  fair set + `--pull-built --self-exit-built --reserve-built --reserve-self`.
- **Planner fixes**: multi-item inserter whitelists; fluid voids drain any temperature variant. Snapshot
  /opt/fo/1127-0210 (`current`).
- **One stitch at a time, with the cores**: a factory's stitch works to wall-clock deadlines; under contention it does
  not converge. Pause other solves (`kill -STOP <pid>`, later `kill -CONT`) and run few games while one stitches.
  py-3's solve (pid in `pgrep -f out=solves/py-science-pack-3_m2`) was paused at 03:30 for chemical's stitch.
- **Waiters** (`bash -c 'while pgrep -f ...; do sleep 60; done; next'`): killing the waiter kills what it started.
  Check `solves/queue_resume.log` and `pgrep -fa resume.sh` before starting a resume by hand.
- **Memory**: the container is held to 16 GB, no swap (an OOM kill ends a solve silently). At most two solver JVMs
  at once (about 4 GB each; `JAVA_OPTS=-Xmx6g` for a big stitch), and no Gradle daemon left running (build with
  `--no-daemon`, or `./gradlew --stop` after).
- **Known hard cases**: big fluid-heavy blocks (pressured air, water unrouted at 0 conflicts): smaller blocks help;
  the uf6 cascade block of production; utility and space planned to 2,206 and 4,269 machines on the desktop
  (2026-11-09: "beyond what the stitcher manages").
