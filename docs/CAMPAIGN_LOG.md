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
- py1_L1d at hour 6.8 (07:08 UTC): 222 pack crafts, 166 due, 144% of plan over the last 3.8 h, every recipe at 90% or more, none at nothing. The self-exit keeps the moss loop alive past the starters (every earlier L1 or L0 run was dead by hour 3).
- 07:15 UTC: py-4's solve continued (nice 10) beside production's stitch, which had not converged in 1.5 h with priority (1,900 to 4,000 conflicts per attempt); the waiter for it removed.
- **Production s2 (`local_sinks` 0.1) stitch given up** (07:50 UTC) after 2 h 40 min of stitching: 1,600 to 4,400
  conflicts per attempt at about 1000 x 1050, no trend down, with priority and nothing else stitching. 1,020 machines
  in 24 blocks, 606 belts between blocks, and nearly every fluid's pipe tree reaching the factory edge for its 2%
  surplus ("edge takes 2.2/s"). **New attempt s3: `local_sinks` 1000** (every by-product item into a chest and every
  surplus fluid into Py's void beside its block; with the void fix this now validates), 3 threads, 6 GB heap. Same
  for py-4 (its m2 solve, with no local sinks at all, stopped with 23 of 26 blocks laid out: its stitch would have
  had the same edge links), then utility, space: queue `solves/queue_s3.log`.
- **Logistic science L5 (fed)**: PASS, 99.8% of plan over 3 measured hours (418 machines in game, 1.2x real time).
- **Py science 1 L1 (built) PASS over 24 game hours** (py1_L1d, 08:30 UTC): 24 to 25 pack crafts every hour from
  hour 6 (24.5 due), the port at 100% of plan, nothing handed in after the warm-up. Built parts that did it: the
  pull deciders, one self-exit (coarse: the moss farm's exit to the port belt works only while it holds 30 coarse),
  the overflow exits (18,470 items in the last 4 h, 1.28/s: sodium hydroxide 10,500, ash 7,000; a real chest needs a
  sink there) and Py's fluid voids. No reserve gate was placed (all 609 candidates were belt ends, which queue by
  themselves). **Py science 1 conquered at L1.**
- TODO (deliverable): a player's blueprint with the L1 parts. `--export-built` already blueprints the whole test surface after set-up (pull deciders, self-exit wires, overflow exits, voids), but as the test copy (infinity chests at the ports); `tools/factory-flow/tag.py --built` merges only exits and voids into the run's own blueprint so far.
- **Player's blueprint with the L1 parts** for py-1: a short check with the L1 flags and `--export-built`, then
  `tag.py --built` (now merging every wire the check built, also between entities the blueprint already has, and
  the circuit settings of every wired entity: self-exit and pull conditions, read modes). Py-1: 156 deciders, 6
  overflow exits (fast inserter, steel chest), 32 valves with 19 exhaust vents and 13 sinkholes, 442 wires. In
  `solves/py-science-pack-1_m2/player_L1_blueprint.txt`; the builds page has a second copy button for it.
- **Py science 2 L5 (fed)**: PASS, 99.8% of plan over 3 measured hours; 310 items fed.
- **Queue strategy changed** (09:15 UTC): the big factories run at about real time in game (logistic 1.2x, chemical
  1.0x, py-3 0.9x with three games at once), so a 24-hour level costs a day of a slot. With about 58 hours left: L5
  as the sanity check (does the layout run at all), then **L1 straight away** (the goal: real parts only), with a
  12-hour window (2 h warm-up, 10 h measured), then L4, L3, L2 only to find where a failing L1 breaks. Military
  (13 machines, about 20x) keeps the full ladder.
- **Military science L4 (ideal)**: PASS, 100.0% of plan over 6 measured hours.
- **Production s3 (`local_sinks` 1000) stitch: no better** after 1 h 25 min (2,000 to 5,000 conflicts, three
  floorplans in turn, no trend down). Local sinks did take the surplus out of the corridors (559 small by-products
  end beside their blocks, 26 links run to the edge), so the 600 belts between blocks are what does not fit. Resumed
  with `blocks.corridor` 40 instead of 20 (09:28 UTC; the blocks are kept, the floorplan is new), at priority. The
  s3 queue went on to py-4.
- **Military science L3 (rules)**: PASS over 24 game hours, 100.0% of plan.
- **Production s3 with 40-tile corridors: no convergence either** (2 h): 906, 1,125, 2,556, 2,510, 2,593 conflicts at
  about 1,050 to 1,240 tiles a side. Resumed again (11:30 UTC) with `blocks.stitch` "global" (corridors sized on a
  coarse grid from every link's demand before any fine routing; docs/STITCH_REWRITE.md), corridors 40.
- **Py-4 s3 (blocks of up to 30 by weight, `local_sinks` 1000): stopped** at 2 h 20 min with 6 of 26 blocks never laid
  out: block 20 (47 machines, 8,320 tiles of machine area) and 23 found no route at all in 34 and 38 tries at a box
  that no longer grew (345 x 230), block 4 came within 7 conflicts, 24 to 26 never got a turn. Re-solving as **s4**
  with `max_machines` 16 and `fluid_weight` 1.5 (as py-2 needed blocks of 12), local sinks 1000, 2 threads (12:10
  UTC). The s3 queue (utility, space after py-4) is stopped; they wait.
- 12:20 UTC: utility then space started as s4 (blocks of 16, local sinks 1000, 2 threads, nice 8), queue_s4b.log.
- **Military science L2 (mod)**: PASS over 24 game hours, 100.0% of plan.
- **Chemical science L5 (fed)**: PASS, 99.8% of plan over 3 measured hours; fed by the check: 361 items.
- 13:50 UTC: py-4 s4 (blocks of 16): all 51 blocks laid out in 1 h 15 min; stitching (nice 5, behind production). Production's global routing: floorplan 1 down to 14 corridor sides over capacity (from 5,234).
- **Military science L1 (built)**: PASS over 24 game hours, 100.0% of plan. **Military science conquered at L1** (L0 running).
- **Py science 3 L5 (fed)**: PASS, 99.8% of plan over 3 measured hours; fed by the check: 447 items. Every laid-out science now passes L5.
- 14:50 UTC: **py-4 s4 stitch stopped** (default router: 7,175 and 12,475 conflicts at about 1,200 x 1,340) and to
  free memory (13 GB of 16 in use with three solvers and three games). Its 51 blocks are kept; it resumes with
  `blocks.stitch` "global" and corridors 40 (`solves/jobs/py-science-pack-4_s4_g.json`) after production's stitch.
  Utility s4: 170 blocks of up to 16, 18 laid out after 1 h of blocks.
- Production's global routing: one floorplan fitted at 14:05 (1257 x 1069, 934 links, 15 rounds) and is in fine
  routing; another is down to 20 sides over capacity.
- **Military science L0 (plain)**: PASS over 24 game hours, 100.0% of plan. **Military science conquered at L0** (the plain blueprint).
- **Logistic, py-2 and chemical at L1: stopped** (15:35 UTC) at game hour 0.5 to 1.8, each with 2 pack crafts (the
  warm start) and none after. Logistic traced: the calf breeder (mukmoux-calf-3) lacks water barrels and nothing in
  the factory makes them; the mukmoux render (full-render-mukmoux-music) stands with its output full of programmable
  speakers, a catalyst it takes back and nothing returns to it (the desktop's script rule `--relief-own-jam` was for
  this); dozens of machines at 0 crafts, switched off by the pull deciders. Built L1 needs more parts for these
  factories (a carrier filler loop, a catalyst return), and each 12-hour attempt costs about 12 real hours.
  **Changed order**: L4 (ideal) for the four medium sciences now, then L3 (12 h windows), L1 again later.
- **Utility and space stopped** (15:55 UTC). Utility plans to 3,187 machines (3,629 once blocks round up) in 170
  blocks of up to 16; after 2 h of block search 18 blocks had layouts, and the stitch of 170 blocks on belts would
  come after. Production (1,020 machines, 24 blocks) has not stitched in 8 hours of attempts on this machine, and
  space is bigger than utility. With 4 cores and about 51 hours left, the CPU goes to production's and py-4's
  stitches and to the medium sciences' runs. Utility and space stay unsolved on belts here; see the report.
- **User (16:00 UTC): make sure the sciences at chemical and below work before continuing.** Production's stitch paused (SIGSTOP), py-3 runs moved to the end of the queue, 4 games at once: logistic, py-2 and chemical get the machine (L4 running, then L3 with 12 h windows, L2; L1 again once it has the parts these factories need).
- Logistic at L1 traced further (probe on logi_L4): the two mukmoux renders hand their programmable speaker (a
  catalyst, 1 in, 1 out) to each other directly; the stall at L1 was upstream, caged mukmoux short because the calf
  breeder lacked water barrels and the barrel filler did not run under the built pull. That is the barrel loop the
  desktop nights of 11-17 to 11-23 fought with script rules (L3) and the mod (L2). Building all those rules from
  combinators is a large project, so for logistic, py-2 and chemical the plan is L4, L3, L2 (the mod, a player can
  install it), and L1 only with time left. `probes/machine.lua` now searches around the machine's bounding box.
- **L4 (ideal transport) on logistic, py-2, chemical: stopped as inconclusive** (16:30 UTC) at game hour 1.4 to 1.9:
  3 to 5 pack crafts (the warm start) and none after; most machines switched off by the meters, several lacking raw
  ores ("makers none here": coal, lead ore, phosphate rock) while muddy sludge was made at up to 25 times its plan
  with its virtual stock at 0. The virtual-robot and fluid-stock helpers do not carry these factories. L4 was the
  optional rung for them; L3 (rules) and L2 (mod), where the desktop held logistic, get the slots.
- **logi_L3 traced at hour 1.9** (2 pack crafts, none in hour 2): the cage ring never started. The mukmoux render
  (makes cages) stood full of programmable speakers (its catalyst: both renders hand theirs to each other and both
  inputs held enough), and lacked caged mukmoux; the caging machine lacked cages. The carrier kit hands a machine its
  carrier only "while no maker of it stands full": the render stood full (of speakers), so no cage was ever handed
  in. Two check fixes (L3 rules, script): a maker counts as full of a carrier only while its output holds that
  carrier; `--relief-own-jam` also lets out a product it takes while its input holds a craft of it (surplus at the
  output limit). The running L3/L2 runs keep the old check; new runs take these.
- 17:00 UTC: logi_L3, py2_L3, chem_L3 stopped and restarted as *_L3b with the fixed check.
- 17:10 UTC: logi_L2 (mod) stopped at hour 1.1 (no cage handed in: the same carrier-kit fault) and restarted as logi_L2b.
- **logi_L2b (mod) stopped at hour 1.8** (2 pack crafts): the renders stood full of programmable speakers (8 in one
  output), the caging machine full of caged mukmoux it could not deliver. The mod has no relief-own-jam. Root: the
  warm start gave every machine two crafts' worth of each ingredient, a catalyst too, which overfills a ring where
  two machines hand their catalyst to each other. **Check fix (fair kit):** a catalyst (an item the recipe gives
  back) is primed with one craft's worth, as a player puts one in. Restarted as logi_L2c (18:15 UTC).
- **py2_L3b at hour 2.3**: 8 pack crafts, then 1 and 0 an hour after the starters. Chain: the pack lacks casein,
  casein the casein pulps, the casein mixture lacks milk. The milk barrel emptier (a furnace-type barrel machine,
  recipe nil while idle, planned empty-milk-barrel) stands switched off by the check's script (1,691 samples
  disabled_by_script) with 3 milk barrels in its input; the korlex milker holds all its ingredients and is switched
  off too. Which rule holds them is not traced yet (not flagged short, not capped). The desktop's py-2 never held
  under the rules either. Left running.
- **logi_L2c stopped at hour 1.3**: the renders jammed on speakers again (one craft's worth each at the start, but
  the two hand theirs to each other and any imbalance, plus the speaker maker's 1%, piles up in one output; the
  caging machine then stood full too). The check already has `--exit-catalyst` (a built overflow exit takes a
  catalyst once the input holds its share and the output stands at its limit; written for py-2's mukmoux pasture):
  **now part of the fair set** (L2, L1). Restarted as logi_L2d (19:00 UTC).
