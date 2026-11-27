# Py science campaign: what was tried

Newest last. Hours are game hours unless said otherwise. Each entry: what, why, result, verdict (worked / did not /
open). Context and the help levels: [CAMPAIGN_CONTEXT.md](CAMPAIGN_CONTEXT.md).

## 2026-11-26

- **Set-up in the cloud container** (Factorio 2.1.24 headless with Py, Java 25, `FO_NO_IMAGES`). Worked; see
  [CLOUD_SETUP.md](CLOUD_SETUP.md). 2.1.25 does not load Py (pipe sprite variation mismatch).
- **Solve queue m2**: all 11 sciences with a 2% planner margin (the margin layouts are the ones that held logistic
  and chemical science on the desktop). Running.
- **Automation science, L3, 10 + 10 minutes**: PASS (13 machines). A smoke test of the launcher and the rule flags.
- **Solve queue r2 (robot layouts)**, started 23:25 UTC beside m2 and **stopped at once: the user wants belts only**
  (sinks allowed). Its plan was: `blocks.max_machines` 2 by machines,
  `bot_links` 1.0 (every item link between blocks by logistic robots), `overflow_after_stacks` 2,
  `provider_until_stacks` 2, 2% margin, 2 threads. Why: the go/no-go nights (2026-11-13 to 11-15) found belts do not
  share out a metered supply while robots do; the robot-built logistic factory made 96 of 97 packs due from a warm
  start. Order: logistic, chemical, py-2, production, py-3, utility, py-4, space, military, py-1.
- Builds page published: https://claude.ai/artifact/7rqXsL9UZFaMF8m7fdcyEk (drawn from each layout's blueprint).
- **Solve queue s2 (belts with local sinks)**, started 23:26 UTC: `blocks.local_sinks` 1000 (every by-product ends in
  a chest or Py's fluid void beside its block). Why: fewer corridor links (Py production science: 312 of 332 item
  outputs local in an earlier run), so big factories stitch sooner; the user allows sinks. Big sciences first.
- **Automation science L5 (fed)**: PASS, pack 99.8% of plan over 3 measured hours (1 h warm-up). Fed 12 items; 9 recipe/item pairs had surplus deleted.
- **Automation science L4 (ideal)**: PASS, 100.0% of plan over 6 measured hours (2 h warm-up).
- **Logistic science m2 solved** 00:11 UTC: 386 machines, 515 x 548, 44 min (planner ~10 min). Ladder queued.
- **Military science m2 solved** 00:13 UTC: one block, 78 x 78, 39 s. Ladder queued before logistic's.
- **Production science s2 with `local_sinks` 1000: did not lay out.** 1040 machines in 25 blocks; after the 1800 s
  budget and 47 minutes in all (2 threads), 10 blocks had no first layout: a fluid unrouted at 0 conflicts every try
  (helium, light oil, tar, gasoline, nitrogen, water, pressured air), boxes grown to 233 x 464. Likely cause: 1000
  sends every by-product fluid to a valve and void beside its block, more pipe work where fluids already fail. The
  desktop's production layout (932 x 835) used 0.1. Restarted the s2 queue with `local_sinks` 0.1 (00:31 UTC).
- **Automation science L3 (rules)**: PASS over 24 game hours (2 h warm-up, 22 measured): 100.0% of plan at the port.
- **Automation science L2 (mod)**: PASS over 24 game hours: 100.0% of plan at the port.
- **Automation science L1 (built)**: PASS over 24 game hours: 100.0% of plan. Pull from deciders on wires, overflow exits and voids built from real parts, no script flow rules.
- **Py science 2 m2 (blocks of up to 20 by weight): did not lay out.** Every block but block 9 had a layout within
  25 minutes; block 9 (29 machines, about 25 fluids) never did: dowfroth-250 (into a flotation cell and a centrifugal
  pan), pressured air or water unrouted at 0 conflicts, boxes grown to 400 x 600 in 55 minutes. As on the desktop's
  Py science 3 (2026-11-09), a connection that finds no path at any size: the block is too fluid-heavy. Stopped;
  re-solving as **m2b** with `blocks.max_machines` 12 and `fluid_weight` 1.5 (01:08 UTC).
- **Production science s2 (`local_sinks` 0.1)**, 40 minutes in: 12 of 25 blocks without a layout; block 5 fails the
  validator with FLUID_MIX (uf6 and uf6#20, the same fluid at two temperatures, on one pipe net). Left running.
- Games cut from 3 to 2 at a time while three solves run (4 cores): the solves gate everything after them.
- **Py science 1 L5 (fed)**: PASS, 99.8% of plan over 3 measured hours.
- **Py science 1 L4 (ideal)**: PASS, 100.0% of plan over 6 measured hours.
- **Py science 2 m2b (blocks of up to 12): did not lay out either**, 37 minutes: 26 of 27 blocks laid out, block 7
  (three slaughterhouses among 16 machines) never: water unrouted on some tries, and on most the validator's
  "long-handed-inserter has 6 filters but 3 filter slots". A machine with many products puts the ones that go onto
  a mixed by-product belt there through one inserter with a blacklist of its other products; Py's simik render has
  eight, six going elsewhere, and no long-handed inserter has six slots.
- **Planner fix: whitelists of several items.** Where the blacklist does not fit any allowed inserter of the reach
  and the products going onto the mixed belt are fewer, the inserter gets those as a whitelist instead
  (`PlacedEntity.whitelist`; blueprint writer and reader, validator, overflow rule and resume files know it).
  Built into a snapshot `/opt/fo/current` (solves now run from there: a rebuild of `build/install` under a running
  solve can crash it; one was stopped seconds before its copy step). `resume.sh` continues m2b with it (01:53 UTC).
- **Automation science L0 (plain)**: PASS over 24 game hours, 100% of plan at the port; 72 to 74 pack crafts every hour after hour 2, nothing handed in after the warm-up, no flow control. **Conquered at every level, L5 to L0.**
- **Validator fix: a fluid void takes its fluid at any temperature.** Production science s2 spent 100 minutes on
  blocks 5 (the uf6 enrichment cascade: uf6#10 to uf6#397) and 20 (outlet gas at 250 to 1000), rejected again and
  again as FLUID_MIX ("uf6 and uf6#20 share one pipe network") or FLUID_MISSING at an `*-pyvoid-*` building. A local
  sink's void building has its recipe's fluid under the game's plain name (uf6-pyvoid-fluid takes "uf6") while the
  pipe from the maker carries the program's temperature variant: the validator counted two fluids on one net (and
  the void as unfed). In the game the void takes uf6 at any temperature. Now a void's connection drains whatever
  variant of its fluid the net carries. Snapshot `/opt/fo/1127-0210`.
- Both solve queues stopped (their loops had the old binary path baked in); chemical m2 left to finish (a waiter
  writes its end line); production s2 resumed with the fix (02:10 UTC), keeping its 18 laid-out blocks.
- Tests: BlueprintTest (with a new whitelist round trip), OverflowLastTest, SpoilageTest pass.
- **Py science 2 m2b solved** (resumed with the whitelist fix) 02:21 UTC: 411 machines in 27 blocks, 487 x 549. Ladder queued; the queue is now breadth-first (every science's L5, then L4, ...).
- **The void fix works**: production s2's resume (02:10) laid out blocks 5 and 20 to 24 within 16 minutes, where 100 minutes before it had none; all 24 blocks laid out.
- **Production s2 resume killed by the memory limit** (03:15 UTC, in its stitch: 606 conflicts at 1159 x 966). The
  container's cgroup holds 16 GB and has no swap; three solver JVMs (about 4 GB each), two games and an idle Gradle
  daemon (1.5 GB) were too much. Gradle daemons stopped; production resumes when chemical's stitch ends (a waiter),
  with `-Xmx6g`. Note: the desktop's valid production (993 x 880) and py-4 (1092 x 1145) stitches had robot links
  for items under 0.1/s; on belts alone (the user's rule) every such link is a corridor belt, so these stitches
  are harder than any done before.
- **Stitches starve under contention** (03:30 UTC): chemical m2's stitch made no progress in an hour (900 to 2,400
  conflicts every round at about 500 x 500), with the desktop's settings and a smaller plan (487 machines against the
  desktop's 598, which stitched on 5 threads). The stitch's stages run to wall-clock deadlines, so with 4 cores
  shared by three solves and two games each attempt does a fraction of the work. Py-3's solve paused (SIGSTOP) and
  games cut to one so chemical's stitch has the cores; py-3 continues after (kill -CONT).
- **Py science 1 L3 (rules)**: PASS over 24 game hours, 100.0% of plan.
- **Py science 1 L2 (mod)**: PASS over 24 game hours, 100.0% of plan.
- **Military science L5 (fed)**: PASS, 99.8% of plan over 3 measured hours.
- **Chemical science m2 solved** 04:25 UTC: 487 machines in 19 blocks, 561 x 486, after 1 h 55 min of stitching (converged once it had the cores). Ladder queued. Py-3 continued; production resumes after py-3.
- **L1 redefined** (04:35 UTC): it was the fair set plus `--flow --no-pull --pull-built`, but `--flow` brings the
  check's script quotas and belt gates, so it was not "real parts only". Now L1 = the fair set plus `--pull-built`
  (pull from deciders and enable conditions built in the game), no script flow rules. The two runs of the old kind
  are kept as level **L1f**: automation passed it (24 h, 100%); py-1 under it made 4 pack crafts in 2.9 h with no
  machine working (stopped). Both rerun under the new L1.
- **Py science 3 m2 solved** 04:43 UTC: 569 machines, 746 x 726 (2 h 20 min, paused an hour for chemical's stitch). Ladder queued; production's resume started after it.
- Production's resume: the waiter did start it at 04:44, but I misread the log, killed the waiter (and with it that
  resume) and started it again by hand at 04:50 (6 GB heap). One resume runs, beside py-4's solve (planning).
- **Py science 1 at L1 (built) and L0 (plain): fail.** Both made their packs through the warm-up (66 and 52 pack
  crafts by hour 2.4) and none after the starters ended; stopped at hours 10 and 7. Traced at L1: the pack lacks
  fawogae substrate, which lacks moss; the moss farm (Moss-4-without-sludge: takes 15 coarse a craft, gives coarse
  back, 0.036/s made against 0.031/s used with the 2% margin) lacks coarse. The surplus coarse leaves through an
  output port at the end of the moss farm's belt, which takes its planned rate whether the farm is short or not, so
  once the farm fell behind its loop drained for good. The rules (L3) and the mod (L2) shut the port of a loop's own
  item and hold a reserve (`--reserve`): both pass 24 h. In-game equivalent not built yet: a **reserve gate from real
  parts** (the belt tile past a loop's last user, enabled only while the tile N back holds items, read by wire).
  That is the next step for L1/L0 once every science has its upper levels.
- **New in the check: `--reserve-built [T]`, reserve gates from real parts** (05:30 UTC). At the `--reserve` gate
  tiles (with `--reserve-items`: past the last user of every item on each belt), the sensor tile N=4 back up the belt
  reads its contents (hold) onto a green wire to the gate tile, whose enable condition is "anything >= T" (4); no
  script opens or shuts anything. Without `--flow` the gate places come from the layout (the networks the item's
  makers drop onto). Where the belt ends at the last user (a port right after it) the pickup tile itself is the
  gate. Py science 1: 32 gates, 12 of them at pickup tiles, coarse included (without `--reserve-items` only seaweed
  counts as a loop item, and all its candidates were belt ends: no gate). **L1 is now** the fair set +
  `--pull-built --reserve-built --reserve-items`. py1_L1b (24 h) queued to test it.
- 05:25 UTC: production s2 in its stitch; py-4's solve (blocks, 14 of 26 laid out) paused with SIGSTOP (pid 30458) so the stitch has the cores. Continue it with kill -CONT when production's stitch ends.
- **py1_L1b (L1 with gates on every item, `--reserve-items`): fail**, stopped at hour 6.4 with 2 pack crafts and 45
  of 47 recipes at nothing: a gate opens only once 4 items stand at its sensor, and with one past every item's last
  user the slow items' belts barely move. New `--reserve-self` instead: gates for a loop's items and for items a
  recipe gives back (catalysts, own by-products). Py-1: 3 gates (seaweed 2, coarse 1). L1 is now
  `--pull-built --reserve-built --reserve-self` on the fair set; py1_L1c started.
- **Automation science L1 (built, with gates on every item)**: PASS over 24 game hours, 100% of plan (auto_L1, started under the `--reserve-items` version of L1).
- **py1_L1c (L1 with gates for loop and given-back items): stopped at hour 3.5**, the pack at 61 crafts. Traced live:
  the moss farm stood short of coarse with 38 coarse in its own output. Its coarse goes onto two belts: a two-tile
  return that ends at its own pickup tile, and a belt through long undergrounds to the port (256 coarse queued in
  them). The new pickup-tile gate shut the return's last tile, a shut tile takes nothing in, so the inserter never
  got coarse: a deadlock of my making. Pickup-tile gates removed. Without gates (py1_L1) the farm lost half its
  coarse to the port belt until that belt backed up, more than its 17% surplus.
- **New in the check: `--self-exit-built`.** For a machine whose recipe gives back an item it takes, an inserter
  carrying that item onto a belt the machine does not take it back from gets a green wire from the machine (read
  contents, craft in hand excluded) and works only while the machine holds two crafts' worth (coarse >= 30). Found by
  positions (inserter targets are not set right after the build). Py-1: one such exit (coarse). **L1 is now** the
  fair set + `--pull-built --self-exit-built --reserve-built --reserve-self`; py1_L1d started 06:34 UTC.
