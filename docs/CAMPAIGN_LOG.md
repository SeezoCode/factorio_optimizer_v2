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
- **logi_L3b at hour 3.8**: 27 pack crafts by hour 3, none after. The chain is one long loop: the pack's animal
  samples lack bones; bones come from the renders; the mukmoux render lacks caged mukmoux, back through mukmoux,
  calves, mukmoux food, saps, sap trees, bonemeal, to bones. The fish render (bones too) stands switched off by the
  script (likely loop-first holding it off the fish loop). Queued **logi_L3c**: the same rules with six hours of
  starters and a six-hour warm-up, then 10 h measured, to see whether the loop only needs longer to fill.
- py2_L3b: 8 pack crafts by hour 2, none in hours 3 and 4 (the milk barrels, above). chem_L3b: 12 by hour 2.8 (17
  due), slowing.
- py2_L3b stopped at hour 3.4 (no packs in two hours) to free its slot for logi_L3c.
- **New rung L3s** (rules + starters): the L3 rule set with the loop starters topped up all along (`--seed-through`,
  no `--seed-stop`), 2 h warm-up and 10 h measured. A pass there and a fail at L3 says the only gap under the rules is
  starting the loops. Queued for chemical and py-2; logistic runs logi_L3c (six hours of starters) meanwhile.
- logi_L3b stopped at about hour 4 (27 pack crafts by hour 3, none after): logistic fails L3 with two hours of starters on this layout; logi_L3c tests six.
- chem_L3b stopped at hour 4.1: 14 pack crafts (3, 8, 2, 1, 0 by hour), faded after the starters; chemical fails L3 with two hours of starters. chem_L3s (starters all along) running.
- **logi_L3c (six hours of starters) stalled at hour 3 as well**, so not a start-up gap: the chain now ends at tin
  plate. Its maker stands switched off by the script with 4 tin plates in its output while solder and mukmoux food
  lack tin plate; the fish render (bones) is switched off too. The rate cap (2x plan over ten minutes, plus three
  crafts; its leave only for users behind their own plan) is the likely brake: users running ahead of their plan
  hold a slow maker at its cap. chem_L3s (starters all along) 1 pack craft in hour 2. Queued **logi_L3d and
  chem_L3d: L3 with `--rate-cap 4`**. logi_L2d (the mod) meanwhile leads: 28 pack crafts by hour 3.3 (20 due).
- **logi_L3c traced to two of L3's governors (live debug of the tin makers, hour 3.1):** the planned tin source,
  both hot-air tin plate casters, stands *stopped by the quota* on hot air (`quota:hot-air`: over their share
  while another hot-air user is behind, on whatever pipe); the plain tin plate furnace, which had carried the load
  (89 crafts against a plan of 1.1 in three hours), stands held by the rate cap and by the quota on tin ore. Solder,
  mukmoux food and so the speakers and caged mukmoux starve: no bones, no animal samples, no packs. L2 (the mod)
  runs without the flow quotas and leads (28 crafts by hour 3.3, 20 due), so **L3 tuning is worth less than the
  fairer levels**: stopped logi_L3c and chem_L3s (5 crafts of 12 due), started **py2_L2 and chem_L2** (FAIR now
  with the exit-catalyst), queued **logi_L1b, chem_L1b, py2_L1b** (built parts with the self-exit and reserve
  fixes that passed py-1), then logi_L0, and L3d variants (`--rate-cap 4 --quota-fluid-net`) after them.
- Stopped py2_L3s too (7 crafts in hour 1, none in hour 2; the L3 governors are the known brake) for logi_L1b.
- **logi_L2d (the mod) stalled at hour 3.5 (28 crafts, then none): its mukmoux bones loop drained.** Not the loop's
  gain (a sap tree, 20 bones, gives about 85 saps, 17 mukmoux food crafts): the stock lay where the loop cannot reach
  it. Live trace: 250 saps backed up on one lane through two undergrounds (30 and 32 tiles) past the mukmoux food
  maker's pickup, toward a dead end at the arqad comb that the mod had switched off; 148 bones the same way past the
  bonemeal maker toward switched-off fungal substrate and cottongut food; 46 mukmoux past caged-mukmoux toward the
  arqad honeycomb. Each lacking machine took from the very network holding the stock.
- **Why no reserve gate held them:** the loop rules count a user as inside an item's loop only if it gets back to
  one of the item's makers within three recipe steps (Py logistic is one 53-recipe loop by way of ash, biomass and
  barrels). The bonemeal maker is four steps back, the food makers six: none of bones, saps or mukmoux was a loop
  item, so no reserve gate (and under the mod, whose flow the check does not record, no script reserve at all) stood
  past their users. logi_L1b (built parts) had 5 reserve gates, none for them.
- **New `--loop-deep D` (verify.py):** an item with no user within three steps but one within D is a loop item, its
  loop the users within a step of the nearest; only where those take at least half of the plan's use of the item and
  no target maker is among the others (at eight steps coke, sand, lime and iron ore turned into loop items of one small
  user each, and the logistic pack an outside user of its own batteries). With D = 7: logistic gains bones, saps,
  mukmoux, meat, moss, starch, gravel, worm (27 loop items, was 19); chemical 5, py-2 4 more.
- **New level L2s (mod + starters all along)**, ranked with L3s on the help side: logi_L2s started. **logi_L1b
  restarted as logi_L1c with `--loop-deep 7`**; chem_L1b and py2_L1b queued with it.
- The mod's own loop classification (tools/factory-flow/tag.py `loops`) had the same three-step rule: given the same
  deep rule (`deep`, from verify's `--loop-deep`; tag.py `--loop-deep` for a player's blueprint). Logistic: 47 loop
  items, 55 with D = 7 (bones, gravel, meat, moss, mukmoux, saps, starch, worm). Under the mod the check's script
  reserve never acted (it learns the networks from --flow's records); the built reserve gates work from the layout.
  Queued **logi_L2e: L2 + `--loop-deep 7 --reserve-built`**.
- logi_L1c (`--loop-deep 7`, users within a step of the nearest) built 8 reserve gates, now mukmoux and meat too, but
  none for bones or saps ("belt ends"): the extra step took the cottongut food and fungal substrate (bones) and the
  arqad comb (saps) into the loop, the last loop user stood at the belt's dead end, and a belt ending at its last user
  gets no gate. **The deep rule now takes the users at the nearest distance only** (bones: the bonemeal maker; saps:
  the two mukmoux food makers), in verify.py and tag.py alike. logi_L1c stopped at hour 0.8 for **logi_L1d**.
- logi_L1d (nearest-distance rule): 10 reserve gates from real parts, now **bones** (gate on the tile past the
  bonemeal maker's pickup, sensor three tiles back), **saps** (past the mukmoux food maker's pickup) and **mukmoux**
  (past caged-mukmoux), all shut at the start as meant: what passes the loop's own users now queues at them first.
- **logi_L1d (built parts) stood at hour 1.6 with 2 packs of 10 due, 109 recipes at nothing.** Two faults of the
  built level, both now fixed:
  1. *The outlet-gas ring had no pull.* Py's outlet gas goes round reheat (to 1000), warmer stone brick (500) and warm
     stone brick (250). `--pull-built` skipped a product that is also an ingredient (a catalyst), so the reheater's
     gas was no product and its decider listened only for molten salt; the reheater and the warm-brick maker stood
     switched off by their deciders, the hot air, glass, lamps, fish breeders, fish, meat and packs behind them.
     Now a fluid product at a temperature of its own counts for pull even where the fluid is also an ingredient.
  2. *The carrier kit never handed in anything at L1* (carriers handed in: none; logi_L2d had 82 barrels by hour 3):
     the kit works from the shortage records of `--flow` or `--bootstrap`, and L1 has neither. Both water-barrel
     fillers stood with water and no empty barrel; the calves without water barrels gave none back. `--carrier-kit`
     without `--flow` or the mod now turns on `bootstrap` (the kit's records and hand-ins only, empty carriers in
     the warm-up, as before).
  logi_L1d stopped for **logi_L1e** (both fixes, `--loop-deep 7`).
- Hour 2 (22:20 UTC): logi_L2s (mod + starters) 21 packs of 14 due, 182% in the last stretch; py2_L2 12 of 9; chem_L2
  6 of 12. Chemical is no deadlock but throughput: its nuclear sample waits on pu-238, behind it the salt solution,
  sb-silicate, silicate solution, teos, and silicon, which waits for coal dust (20 a craft), a by-product of tailings
  separation and grade-4 zinc, both working; 190 recipes run over 150% of plan, and the plan's margin is 2%.
- logi_L1e, hour 0.4: the kit handed in 172 empty barrels (none in logi_L1d), the water-barrel fillers made 168 and
  202 water barrels (18 and 71 by hour 1.6 before), the outlet-gas reheaters craft on demand (27 and 9); 2 packs of
  3 due.
- logi_L1e stood again by hour 1.3 (2 packs of 8 due), many makers "disabled by control behavior". Checked and ruled
  out: the demand wire is one green network (the 123 "power networks" the check reports are counted before its power
  hubs, four tiles apart, join each other; 527 poles, one electric and one green network). The fish breeders' biomass
  comes by plan from one starch maker alone (its belt network is the only biomass net that feeds them), and that
  maker does hear the breeders' demand; it stood short of fiberboard, behind it treated wood, creosote, naphthalene oil,
  high distillate and the oil refinery short of steam (from the geothermal exchanger on the same pipe segment).
  Restarted as **logi_L1f** (the same fixes) to see whether that recurs; no code change from this.
- 23:10 UTC: **logi_L2s (mod + starters) past hour 3.5, where logi_L2d's loop died: 34 packs of 23 due**; py2_L2
  20 of 14 (266% in the last stretch). chem_L2 stopped at hour 3.0, 8 of 18 (34% in hour 3): its packs now wait on
  rubber, behind it latex, creamy latex, saps and the mk04 sap trees, whose maker lacks fertilizer while the mulcher
  stands waiting for space with 15 fertilizer in its output, and the mod holds fertilizer-3 off. Started
  **chem_L2s** (mod + starters, `--loop-deep 7`).
- 23:55 UTC: **logi_L1f (L1, every fix) stood from hour 1: 2 packs by hour 2.4 of 15 due.** The kit and the gas ring
  work now; what stands is one cycle of some 15 recipes: the mukmoux food takes tin only from the hot-air casters
  (the plain tin furnace feeds other belts and its decider stays off), their molten tin waits on sintered tin,
  lime, coke, filtration media, nanofibrils, xyhiphoe cubs, blood meal and the blood of the mukmoux render, and the
  render on the mukmoux the food makes. Two hours of starters never closed it; under the mod with starters all
  along (logi_L2s) it runs (49 packs of 32 due at hour 5.2). **New level L1s: built parts + starters all along**
  (ranked with L3s and L2s on the help side). logi_L1f stopped; **logi_L1s** started (`--loop-deep 7`).
- 00:45 UTC: **logi_L1s (built + starters all along) stood like L1f: 2 packs by hour 1.6.** The demand wiring works
  (clay's steam decider fires, the electric boiler hears it and refills it: snapshots catch the cycle's off phase).
  What stands is distribution: the two water-barrel fillers drop onto four belt networks; three of them (Scrondrix,
  cottongut, one more) held 183, 151 and 108 water barrels, the mukmoux calves' 13, and the fillers stood without
  empty barrels (531 handed in by the kit). The mod's share rule spreads a maker's output by the plan's shares and
  by who waits; L1 has no built part for that. Stopped for **logi_L2e** (L2 + `--loop-deep 7 --reserve-built`, no
  starters): logistic's fairer rung if it holds.
- chem_L2s, hour 2.2: rubber, latex, creamy latex, saps; the mod holds the mk04 sap mulchers (one stands full of
  fertilizer, 15, its fertilizer belt backed up and not the tree maker's; its relief exit shut below the limit), the
  tree maker (9 of 10 bonemeal, 3 of 4 fertilizer) and the bonemeal maker. Watching to hour 3.
- 01:20 UTC: **chem_L2s stood from hour 2 (5 packs of 17 due by hour 2.8).** Traced to Py machines that place
  their results on a tile of their own (`vector_to_place_result`: wood processing units, sand extractors and
  classifiers, casting units). The check builds nothing on those tiles; with a product it cannot hand on (the mk04
  sap mulcher's fertilizer belt backed up, 14 fertilizer in its output, 9 a craft) such a machine spills a result
  onto the ground and stands "waiting for space in destination", starting no craft. Its relief exit opened at two
  crafts' worth (18) and the relief rule knew only "full output", so it never opened: no saps, no creamy latex, latex,
  rubber, packs. Items lay on such tiles in all three big runs (chemical: 26; py-2 and logistic: a mulcher each).
  Two check fixes: **relief_limit is one craft for such machines, and "waiting for space in destination" counts as
  full**; and **what lies on their tile goes back into the machine's output once a second** (report `ground_back`,
  `ground_lost`), as an inserter a player sets there would. chem_L2s stopped for **chem_L2sb**; logi_L2e (hour 1)
  for **logi_L2f** (logistic's mk03 mulcher, in the mukmoux loop, stood waiting for space too).
- 01:35 UTC: **logi_L2s at hour 7.9: 79 packs of 49 due, 193% in the last hour.** **py2_L2 stood from hour 6**
  (29 of 29 due by hour 7.1, none since): its packs wait on solidified sarcorus, behind it the cottongut seeds on
  oleochemicals, and oleochemicals-from-skin on bacteria-1, its own fluid loop (10 in, 11 out a craft) that outside
  users drain once the starters stop at hour 2. Stopped for **py2_L2s** (mod + starters all along, `--loop-deep 7
  --reserve-built --loop-valves`: with --reserve-built the check's loop classification comes back under the mod, so
  the valves before the loop's outside users get built).
- 02:00 UTC: the ground pickup moves a lot: chem_L2sb by hour 1.3 put back 3,063 coarse, 2,386 limestone, 1,474 raw
  fiber, 545 chromium, 467 gravel, 402 fertilizer, 393 sb dust, 279 aluminium plate, 273 nickel plate, 227 iron oxide,
  173 saps (none lost); logi_L2f by hour 1.7 858 raw fiber, 818 soil, 180 bedding, 172 yotoi leaves, 155 saps (32 soil
  lost). Every machine placing results on its own tile spills continually; without the pickup each stood after its
  first spill. (Once a second, one spilled item each time: a cap on such machines above one item a second; watch.)
  logi_L2f (L2, no starters) 15 packs of 10 due at hour 1.7; logi_L2s 92 of 55 at hour 9.0; chem_L2sb 5 of 8.
- 02:30 UTC, chem_L2sb hour 1.9: 7 packs of 11 due (3 in hour 2; chem_L2s had 1). No stop now: the nuclear-sample
  chain (teos, silicate solution, sb silicate, salt solution) all working, at 55-63% of plan since the start; behind
  it the ethanol from arqad honey, the honeycomb ring and the arqad hive, which now works without pause at its full
  speed (6, its 40 arqad modules in; plan 0.051 crafts/s of 0.06): a slow start, catching up, or not. Hour 3 decides.
- 02:50 UTC: three runs of the new code stood: chem_L2sb (8 of 14 by hour 2.3, none in the last 0.3 h), logi_L2f
  (15 of 18 by hour 3, none in hour 3), py2_L2s (5 of 9, none since hour 1). logi_L2s (older code) at hour 10.3:
  105 of 63.
  - **py2_L2s: `--loop-valves` under the mod breaks fluid pull.** The bio-sample maker stood without pressured water
    behind a loop valve (its input on segment 2107, the maker's output on 379); the mod hears who waits on a fluid by
    segment, never heard it, and held the pressured-water maker off. (The check's own pull counts the pipe behind a
    valve as in front of it; the mod does not.) Not for the mod's levels.
  - **logi_L2f: the stone-brick ring gridlocked at hour 2**: warm brick (62 in its output), warmer brick (40) and the
    hot-air maker (59 stone bricks) each stood full of its product and without its input, their output inserters
    waiting on backed-up belts; bricks are carriers no overflow takes, so nothing makes room. No reserve gate stands
    on those belts. Cause not settled (too many bricks in the ring once the mod held the hot-air maker off for a
    while?).
  Stopped both; **py2_L2sb** (L2s + `--loop-deep 7`, no valves, no built reserve) and **logi_L2g** (L2 +
  `--loop-deep 7`, no built reserve) next.
- chem_L2sb stopped at hour 2.4 (9 packs of 14.4 due, 62%): its packs wait on optical fiber (74% of plan), short of
  plastic, while both plastic makers run at 149% and 161% of plan: plastic goes to users far above their plan
  (fish food 01 269%, guar gum 577%, dhilmos food 471%, sb oxide 148%). The mod has a rate cap but no fair share of
  an ingredient among its users; L3's rules have the quota (a user over its share is held while one behind waits).
  Queued **chem_L3sb** (L3s + `--loop-deep 7`, with every fix of today).
- chem_L1b and py2_L1b started before a queue reorder took (the scheduler writes the queue back as it starts a run
  and overwrote the reorder) and were stopped at once for chem_L3sb (no results). Edit the queue only with the
  scheduler stopped (now in CAMPAIGN_CONTEXT). Running: logi_L2s, py2_L2sb, logi_L2g, chem_L3sb.
- **03:45 UTC: Logistic science PASSES at L2s** (logi_L2s: the mod + loop starters all along): 124 pack crafts of
  73 due over 12 game hours, 8 to 13 every hour, the port at 100%. Starters while measuring: 16,031 guar seeds, 7,679
  seaweed, 6,961 biomass, 5,098 ash and fluids (tailings 166k, sulfuric acid 30k, oxygen 30k, slacked lime 16k,
  ammonia 10k); the kit 100 barrels and 16 empty honeycombs in the warm-up. Logistic's fairest pass so far.
- Queue rewritten (scheduler stopped): L0 for the big factories dropped (certain to stand early; logi_L0 stopped at
  start), py3_L4 dropped (L4 helpers broken there). Now: **py3_L2s** (started), logi_L3d and chem_L3d (L3 with rate
  cap 4, quota by pipe network, loop-deep: L3 ranks fairer than L2s), py3_L2, py3_L3s.
- Builds page republished (version 7, the same link): the L2s and L1s columns, logistic's L2s pass.
- 04:10 UTC: chem_L3sb (rules + starters) 3 packs of 11 due by hour 1.8, none in hour 2, short on four branches at
  once: optical fiber (plastic, and nbfe coating behind a cladding chain), nuclear samples (automation packs short of
  native flora; pu-238), advanced circuits (vacuum tubes, graphite, coke: the hot-residual coke maker stood full of
  coke, then switched off by the script with some of its exits held by the share rule, one graphite maker off and
  two short) and stainless steel (meat for phosphate). py2_L2sb 13 of 10 by hour 2.5; logi_L2g, py3_L2s running.
- 04:35 UTC: **logi_L2g (L2 + `--loop-deep 7`, no starters after hour 2) holds past hour 3.5: 31 packs of 21 due**
  (logi_L2d without loop-deep stood there). py2_L2sb at plan (13 of 13 by hour 3.1) and slowing: honey, honeycomb,
  the arqad hive, ulric and its water barrels; the one water-barrel filler stood without empty barrels while 174 lay
  on belts toward the factory's six other barrel fillers (honey, btx, hydrofluoric acid, molten iron, naphtha, wax).
  Restarted as **py2_L2sc** with `--carrier-kit run` (the kit's empty carriers through the run, at L2s like the
  starters; logistic's L2s pass had the kit in the warm-up only). chem_L3sb 6 of 15 by hour 2.4, the last stretch
  at 86%; py3_L2s 6 of 24 in its first hour.
- 05:15 UTC: py3_L2s stopped at hour 2.0 (8 packs of 49 due): its packs wait on moss; the moss farms lack limestone
  while the limestone extractors, far off, stand with their belts backed up (not traced further: py-3 comes after
  chemical in the user's order). logi_L2g 46 of 31 by hour 5.0; chem_L3sb 11 of 22 (58% in the last stretch);
  py2_L2sc 8 of 6 by hour 1.4. Queue: **chem_L3d** next; logi_L3d last (an L2 pass, if logi_L2g holds, ranks above
  any L3 pass).
- 05:55 UTC: **logi_L2g (L2 + loop-deep, no starters) stood from hour 5** (47 packs of 37 due by hour 6.1, 1 in hour
  6): the mukmoux loop again, now at fertilizer: the sap-tree maker 1 short while 747 fertilizer lay on four belt
  networks (322, 193, 161, 71) out of its reach, and the overflow took 1,533 saps in four hours. Loop-deep moved the
  stand from hour 3.5 to hour 5; the stock still strands past the users. The built reserve gates are the fix for
  that, and logi_L2f (with them) gridlocked its brick ring at hour 2, cause unsettled. Stopped logi_L2g; **logi_L2h**
  = logi_L2f's settings again, to see whether the gridlock recurs.
- 06:00 UTC: chem_L3sb stopped (11 packs of 27 due by hour 4.4, none in hour 5). **Production's stitch resumed
  early** (`kill -CONT 22654`, nice -5; it had stood paused since 2026-11-27 12:00 in global routing, best preview
  49 corridor sides over capacity) with three game slots (sched --max 3): chem_L3d, py2_L2sc, logi_L2h.
- 06:45 UTC: chem_L3d (L3, rate cap 4, quota by pipe network) stood like the others: 5 packs of 12 due by hour 2.0.
  py2_L2sc 21 of 16 by hour 3.8 (116% lately); logi_L2h 17 of 10 by hour 1.6, no brick gridlock yet. Production's
  stitch still widening (factory floorplan 3/3: 2,722 corridor sides over capacity; preview 2/3: 2,163 conflicts).
  One more chemical try, **chem_L2sc**: L2s + loop-deep + the kit through the run + `--rate-cap 1.5` (the mod's cap,
  2 in the rules; plastic went to fish food at 269% of plan).
- 07:10 UTC: **logi_L2h gridlocked its brick ring at hour 2 exactly as logi_L2f did** (59, 40, 62 bricks in the three
  machines' outputs): deterministic, and only with `--reserve-built`. No gate stands on a brick belt; what
  --reserve-built also did under the mod was set the check's loop membership (params.loop_first), which wakes the
  check's own loop rules (splitter priority, loop keep, dead ends) beside the mod's. Now under the mod the membership
  goes to the reserve gates alone (params.reserve_lf). **logi_L2i**: L2 + loop-deep + reserve-built, isolated.
- 08:20 UTC: **logi_L2i gridlocked the brick ring at hour 2 again** (61, 40, 62), with the loop membership given to
  the gates alone. So the built reserve gates themselves bring it on: three runs of three with them, none of five
  without. No gate stands on a brick belt (the ring at 250,88; the ten gates on barrel, bones, fertilizer, guar
  seeds, guts, iron plate, meat, mukmoux, saps elsewhere; the barrel gate shut at the time). The likely path is
  indirect: a shut gate starves something, the hot-air users stop a while, the ring fills past what its belts and
  outputs hold, and bricks (carriers) no overflow takes. A ring-jam rule would be needed; logistic passes L2s,
  so the reserve-gate L2 path is dropped. logi_L2i stopped; py3_L2 takes the slot.
- 08:55 UTC: **the place-result machines were throttled to about one item a second all along.** py2_L2sc stood from
  hour 6 (35 packs of 30 due by 7.4): flasks, stoppers, rubber, latex, creamy latex, saps; all three mk03 sap mulchers
  "waiting for space" with an item on their drop tile and one or two biomass in their outputs. These machines place
  every result on that tile, main products too (the ground pickup's counts: 3,063 coarse, 2,386 limestone, 1,474 raw
  fiber in chemical's first 1.3 hours), and the pickup freed one item a second. Live test: a steel chest set on each
  mulcher's drop tile, and in 20 seconds each was working with about five crafts in its chest (438 saps, 45
  fertilizer, 22 biomass...). **Check fix: a chest on every such drop tile, emptied into the machine's output every
  ten ticks** (a full output leaves it in the chest; a full chest stops the machine); the ground pickup stays as the
  fallback where no chest fits. Restarted all three games on it: **py2_L2sd** (L2s + loop-deep + kit through the
  run), **chem_L2sd** (the same + rate cap 1.5), **logi_L2j** (L2 + loop-deep: logistic's mulchers were throttled the
  same way); py3_L2sb queued (py-3's limestone extractors too).
- 11:25 UTC, the place-chest runs: py2_L2sd 20 packs of 14 due by hour 3.4; chem_L2sd 13 of 18 by hour 2.9 (6 and 4
  in hours 2 and 3: chemical's best yet, 72%); logi_L2j (L2 + loop-deep, no starters) 24 of 23 by hour 3.8, its
  mukmoux loop short at hour 3 again, this time through moss: the moss farms lack coarse, the soil separator soil.
  Every L2 logistic run without starters loses that long loop at a different link. Production's stitch: best
  floorplan 798 conflicts (fine routing), still widening.
- 11:55 UTC: **chem_L2sd catching up: 7 packs in hour 4 (126% of plan)**, 20 of 24 by hour 3.9 (mod + starters,
  kit through the run, rate cap 1.5, place chests). logi_L2j recovered: 7 in hour 5, 35 of 32 by hour 5.2. py2_L2sd
  24 of 19 by hour 4.6, slowing (4, 2). Production: floorplan 2/3 at 830 conflicts.
- **12:05 UTC: the container restarted** (uptime 0): every game, the scheduler and production's stitch were gone;
  the files (/opt, the repo, solves/) were kept. Lost mid-run: chem_L2sd (20 of 24 by hour 3.9, catching up),
  logi_L2j (35 of 32 by hour 5.2), py2_L2sd (24 of 19 by hour 4.6). Restarted the same settings as **chem_L2se,
  logi_L2k, py2_L2se**.
- **Production's stitch not restarted**: since 2026-11-27 11:30 (with a day's pause) it had global routing fit within
  half an hour and then about 24 hours of solver time in fine routing without a stitched factory (preview "did not
  stitch" at 87,600 s; conflicts swinging between about 800 and 2,500). Its 24 blocks are laid out and kept
  (solves/production-science-pack_s3/resume). The core goes to a fourth game slot (sched --max 4): py3_L2sb next.
- 13:45 UTC (restarted runs): **chem_L2se at plan: 11 packs of 11 due by hour 1.9** (6, 5; 94% in the last hour; L2s
  + loop-deep + kit through the run + rate cap 1.5 + place chests). logi_L2k (L2) 20 of 15 by 2.5; py2_L2se 13 of 9 by
  2.2; py3_L2sb 14 of 35 by 1.4 (56% lately): acrylic, acetone; the acetone maker lacks copper plate while the mod holds
  both copper-plate makers off.
- 14:10 UTC: chem_L2se slowed in hour 3 (12 of 14): the nuclear-sample chain all working but slow (teos waits on
  ethanol, the honey-to-ethanol reactor on manure bacteria), and small parts 02 behind bolts behind iron sticks (the
  stick maker holds 23 in its output). py2_L2se slowed in hour 3 (13 of 11): arqad honey, honeycomb, ulric, water
  barrels; the filler stands without empty barrels (3,223 filled so far); the kit handed in only 39 (it gives only
  while no barrel maker, here the animals, stands full). logi_L2k 26 of 18 by hour 3.0; py3_L2sb 20 of 43 by hour
  1.8 (12 in hour 2).
- 15:30 UTC: **logi_L2k (L2, no starters) past hour 5 with 12 packs in it: 47 of 31 due** (8 8 9 10 12): the runs
  that stood at hour 3.5 (L2d) and hour 5 (L2g) had neither the place chests nor (L2d) loop-deep. py2_L2se 24 of 18
  by 4.4; chem_L2se 18 of 23 by 3.8 (62% lately); py3_L2sb 37 of 73 by 3.0 (58%).
- 16:30 UTC queue for the night (after the four running): logi_L1t (L1s + loop-deep), py2_L2f (L2 + loop-deep), chem_L3sc (L3s + loop-deep + kit through the run), py3_L3s (+ loop-deep); logi_L3d dropped.
- 18:10 UTC: **logi_L2k stood from hour 7.8** at 74 packs (55 due by hour 9; about 58 of 61 due in the measured
  window so far, right at the 95% line): batteries now, behind them nas batteries, pbsb alloy, simik lead, bio ore,
  z3 reagent short of methanol, and the filtration-media / xyhiphoe / fish-egg / fish-food loop. py2_L2se 47 of 32
  by 7.9 (steady); chem_L2se 35 of 41 by 6.8; py3_L2sb stood from hour 5 (61 of 132).
