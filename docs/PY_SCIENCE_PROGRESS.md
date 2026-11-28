# Py science campaign: progress by science

The 11 Pyanodon sciences, each at 0.02 packs/s from `jobs/py_sciences/`, laid out by the optimizer (2% planner
margin unless said) and run in Factorio 2.1.24 headless by `tools/factorio-verify/verify.py`. Levels from the most
help to none are described in [CAMPAIGN_CONTEXT.md](CAMPAIGN_CONTEXT.md#help-levels-most-help-first). The table is
written by `tools/py-campaign/progress.py`; the sections below it are written by hand as runs finish.

<!-- campaign:start -->
| Science | Machines | Fairest pass | L5 fed | L4 ideal | L3s rules+starters | L2s mod+starters | L1s built+starters | L3 rules | L2 mod | L1 built | L0 plain |
|---|---:|---|---|---|---|---|---|---|---|---|---|
| Automation science pack | 13 | **L0 plain** | **pass** 3.6 h | **pass** 7.2 h | · | · | · | **pass** 21.6 h | **pass** 21.6 h | **pass** 21.6 h | **pass** 21.6 h |
| pY science pack 1 | 50 | **L1 built** | **pass** 3.6 h | **pass** 7.2 h | · | · | · | **pass** 21.6 h | **pass** 21.6 h | **pass** 21.6 h | stopped |
| Logistic science pack | 386 | **L2s mod+starters** | **pass** 3.6 h | stopped | · | **pass** 10.8 h | stopped | stopped | running, 1.2 h | stopped | running, 0 h |
| Military science pack | 13 | **L0 plain** | **pass** 3.6 h | **pass** 7.2 h | · | · | · | **pass** 21.6 h | **pass** 21.6 h | **pass** 21.6 h | **pass** 21.6 h |
| pY science pack 2 | 411 | **L5 fed** | **pass** 3.6 h | stopped | stopped | running, 1.2 h | · | stopped | stopped | stopped | · |
| Chemical science pack | 487 | **L5 fed** | **pass** 3.6 h | stopped | running, 0 h | stopped | · | stopped | stopped | stopped | · |
| pY science pack 3 | 569 | **L5 fed** | **pass** 3.6 h | · | · | · | · | · | · | · | · |
| Production science pack | stitch paused (chemical and below first) | | · | · | · | · | · | · | · | · | · |
| pY science pack 4 | stitch waiting (chemical and below first) | | · | · | · | · | · | · | · | · | · |
| Utility science pack | stopped: too big for this machine on belts | | · | · | · | · | · | · | · | · | · |
| Space science pack | stopped: too big for this machine on belts | | · | · | · | · | · | · | · | · | · |

Updated 2026-11-28 03:41 UTC. Hours are game hours (warm-up included); a pass is the in-game check's verdict over the level's whole run. Builds drawn tile for tile: [the builds page](https://claude.ai/artifact/7rqXsL9UZFaMF8m7fdcyEk) (private link).
<!-- campaign:end -->

## Conquered

Sciences that passed at some level, newest last, with the fairest level they passed.

- **Automation science: L0, the plain blueprint** (2026-11-27 02:00 UTC). Passed at every level from L5 to L0; at
  L0 with a warm start and nothing else: 72 to 74 pack crafts every hour from hour 3 to 24 against 72 due, the
  port at 100% of plan. Layout `automation-science-pack_m2`: 13 machines in one 76 x 76 block.
- **Py science 1: L1, real parts only** (2026-11-27 08:30 UTC). Passed L5 to L2 and the built L1 (pull from deciders,
  a self-exit for the moss farm's coarse, overflow exits, Py's fluid voids, no script): 24 h, 24 to 25 pack crafts
  every hour from hour 6, the port at 100%. The plain blueprint (L0) fails on the coarse loop; see below.
- **Military science: L0, the plain blueprint** (2026-11-27 15:05 UTC). Passed every level from L5 to L0, each over
  its full window; at L1 and L0 36 to 38 pack crafts every hour (36.7 due).
- **Logistic science: L2s, the factory-flow mod with loop starters all along** (2026-11-28 03:45 UTC). 12 game
  hours (2 h warm-up, 10 h measured): 124 pack crafts against 73 due, 8 to 13 every hour from the first to the
  last, the port at 100% of plan. Help counted: loop starters while measuring (plan seeds topped up when a loop runs
  dry; 16,000 guar seeds, 7,700 seaweed, 7,000 biomass, 5,100 ash, and fluids, most of it tailings, sulfuric acid,
  oxygen and slacked lime), 100 barrels and 16 empty honeycombs as the player's carrier kit in the warm-up. Without
  the starters (L2, logi_L2d) the mukmoux bones loop drained at hour 3.5; L1 (built parts only) stands on the tin
  cycle and the water-barrel distribution. See below.

## By science

### Automation science

13 machines in one block, 76 x 76 (2% margin). Every level passes, each over its full window:

| Level | Run | Result |
|---|---|---|
| L5 fed | auto_L5 | pass, 99.8% over 3 measured hours; the check fed 12 items, deleted surplus of 9 recipe/item pairs |
| L4 ideal | auto_L4 | pass, 100% over 6 measured hours; nothing deleted |
| L3 rules | auto_L3 | pass, 24 h, 100%; 72 to 75 crafts an hour from hour 5 |
| L2 mod | auto_L2 | pass, 24 h, 100% |
| L1f (old L1: built pull plus the check's quotas and gates) | auto_L1f | pass, 24 h, 100%; the built overflow exits took 4,000 ash in the last 4 h (a real chest would need emptying or a burner) |
| L1 built | auto_L1 | queued (rerun with real parts only) |
| L0 plain | auto_L0 | pass, 24 h, 100%; no flow control, nothing handed in after the warm-up |

Ahead of plan in the first hours at every level (the warm start's stocks), then exactly the port's rate.

### Py science 1

50 machines, 129 x 138 (2% margin).

| Level | Run | Result |
|---|---|---|
| L5 fed | py1_L5 | pass, 99.8% over 3 measured hours; 42 items fed |
| L4 ideal | py1_L4 | pass, 100% over 6 measured hours |
| L3 rules | py1_L3 | pass, 24 h, 100%: 24 to 25 pack crafts every hour from hour 3 (24.5 due) |
| L2 mod | py1_L2 | pass, 24 h, 100%, hour for hour as L3 |
| L1f | py1_L1f | stopped at hour 2.9: 4 pack crafts, no machine working (the old L1's script quotas with built pull) |
| L1 built (first form: pull only) | py1_L1 | **fail**: 66 pack crafts by hour 2.4, none after; stopped at hour 10 |
| L1 built (gates on every item) | py1_L1b | **fail**: 2 pack crafts by hour 6.4, the gates kept the slow items' belts still |
| L1 built (gates for loop items, pickup-tile gates) | py1_L1c | **fail**: the pickup-tile gate shut the moss farm's own coarse return (a deadlock of the check's making) |
| **L1 built** (pull, self-exit, gates for loop items) | py1_L1d | **pass, 24 h, 100%**: 24 to 25 every hour from hour 6 |
| L0 plain | py1_L0 | **fail**: 52 by hour 2.4, 2 after; stopped at hour 7 |

Why L1 and L0 fail: the moss farm's coarse loop. The farm takes 15 coarse a craft and gives coarse back (0.036/s made,
0.031/s used); the 2% surplus leaves by an output port at the end of the farm's belt, which takes its share whether
the farm is short or not. Once the farm fell behind, the port kept draining the loop and it never restarted; moss,
fawogae substrate and the pack stopped behind it. The rules and the mod shut that port for a loop's own item and
keep a reserve in front of the farm. The fair-level fix (L1): a **self-exit** from real parts. The farm's inserter
onto the port belt gets a green wire from the farm (reading its contents) and the condition coarse >= 30, so the
farm's own two-tile return fills first and only what it cannot hold leaves. For L0 the planner would have to put that
wire and condition into the blueprint itself.

### Military science

13 machines in one block, 78 x 78 (2% margin).

| Level | Run | Result |
|---|---|---|
| L5 fed | mil_L5 | pass, 99.8% over 3 measured hours; 14 items fed |
| L4 ideal | mil_L4 | pass, 100% over 6 measured hours |
| L3 rules | mil_L3 | pass, 24 h, 100% |
| L2 mod | mil_L2 | pass, 24 h, 100% |
| L1 built | mil_L1 | pass, 24 h, 100%: 36 to 38 pack crafts every hour; no self-exit needed, nothing overflowed |
| L0 plain | mil_L0 | pass, 24 h, 100% |

### Logistic, Py science 2, chemical, Py science 3

Laid out (386, 411, 487 and 569 machines). Each passed L5 (fed: 99.8% of plan over 3 measured hours, with 292, 310,
361 and 447 items fed). They run at 1.2 to 1.8 times real time, so a 12-hour window costs 7 to 10 hours of a slot.

What stands in their way below L5, as traced (details and dates in CAMPAIGN_LOG.md):

- **L4 (ideal transport)**: the virtual-robot helpers do not carry them (raw ores never arrive); dropped.
- **L3 (the check's flow rules)**: its governors hold the planned makers: logistic's two hot-air tin casters stood
  stopped by the hot-air quota, the plain tin furnace by the rate cap; no solder, no speakers, no bones.
- **L2 (the factory-flow mod)**: the best rung. Logistic ran ahead of plan until its starters stopped at hour 2,
  then the mukmoux bones loop drained by hour 3.5: what passed the loop's own users while they stood full (250 saps,
  148 bones, 46 mukmoux) lay backed up before switched-off outside users at dead ends. The loop rules had not seen
  it as a loop (its users are four to six recipe steps round; the rule looked three deep): `--loop-deep` fixes the
  classification. With starters all along (L2s) logistic passes. Py-2 at L2 ran at plan until hour 6, when its
  bacteria fluid loop (10 in, 11 out a craft) drained. Chemical at L2: its packs wait in turn on silicon, rubber and
  plastic; plastic goes to users far above their plan (fish food 269%, guar gum 577%), and the mod shares no
  ingredient among its users.
- **L1 (built parts only)**: three faults fixed (the outlet-gas heat ring had no pull, the carrier kit never acted,
  machines that place results on their own tile spilled onto the ground and stopped); what remains on logistic is
  one cycle of some 15 recipes through tin, coke, filtration media, xyhiphoe and blood back to the mukmoux, and the
  water barrels, which the fillers spread over four belts by turns where the mod would share them by plan.

### Production, Py science 4, utility, space

The big four. On belts only (no robot links, the user's rule) their stitch is the hard part: production has 1,020
machines in 24 blocks with about 600 belts between blocks. The default corridor router did not converge in 7 hours
of attempts (with sinks at 0.1 or 1000, corridors of 20 or 40); global routing (corridors sized on a coarse grid
first) did: one floorplan fitted at 1257 x 1069 after 15 rounds (2026-11-27 14:05) and is in fine routing. Py
science 4 needed blocks of at most 16 (51 blocks) to lay out every block; its stitch waits for global routing after
production. Utility: 170 blocks of up to 16, laying out. Space after utility.
