# Py science campaign: progress by science

The 11 Pyanodon sciences, each at 0.02 packs/s from `jobs/py_sciences/`, laid out by the optimizer (2% planner
margin unless said) and run in Factorio 2.1.24 headless by `tools/factorio-verify/verify.py`. Levels from the most
help to none are described in [CAMPAIGN_CONTEXT.md](CAMPAIGN_CONTEXT.md#help-levels-most-help-first). The table is
written by `tools/py-campaign/progress.py`; the sections below it are written by hand as runs finish.

<!-- campaign:start -->
| Science | Machines | Fairest pass | L5 fed | L4 ideal | L3 rules | L2 mod | L1 built | L0 plain |
|---|---:|---|---|---|---|---|---|---|
| Automation science pack | 13 | **L0 plain** | **pass** 3.6 h | **pass** 7.2 h | **pass** 21.6 h | **pass** 21.6 h | **pass** 21.6 h | **pass** 21.6 h |
| pY science pack 1 | 50 | **L2 mod** | **pass** 3.6 h | **pass** 7.2 h | **pass** 21.6 h | **pass** 21.6 h | running, 21.6 h | stopped |
| Logistic science pack | 386 | **L5 fed** | **pass** 3.6 h | · | · | · | · | · |
| Military science pack | 13 | **L5 fed** | **pass** 3.6 h | · | · | · | · | · |
| pY science pack 2 | 411 | — | running, 3.2 h | · | · | · | · | · |
| Chemical science pack | 487 | — | running, 0 h | · | · | · | · | · |
| pY science pack 3 | 569 | — | · | · | · | · | · | · |
| Production science pack | solving | | · | · | · | · | · | · |
| pY science pack 4 | solving | | · | · | · | · | · | · |
| Utility science pack | solving | | · | · | · | · | · | · |
| Space science pack | solving | | · | · | · | · | · | · |

Updated 2026-11-27 08:27 UTC. Hours are game hours (warm-up included); a pass is the in-game check's verdict over the level's whole run. Builds drawn tile for tile: [the builds page](https://claude.ai/artifact/7rqXsL9UZFaMF8m7fdcyEk) (private link).
<!-- campaign:end -->

## Conquered

Sciences that passed at some level, newest last, with the fairest level they passed.

- **Automation science: L0, the plain blueprint** (2026-11-27 02:00 UTC). Passed at every level from L5 to L0; at
  L0 with a warm start and nothing else: 72 to 74 pack crafts every hour from hour 3 to 24 against 72 due, the
  port at 100% of plan. Layout `automation-science-pack_m2`: 13 machines in one 76 x 76 block.

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
| L1 built | py1_L1 | **fail**: 66 pack crafts by hour 2.4, none after; stopped at hour 10 |
| L0 plain | py1_L0 | **fail**: 52 by hour 2.4, 2 after; stopped at hour 7 |

Why L1 and L0 fail: the moss farm's coarse loop. The farm takes 15 coarse a craft and gives coarse back (0.036/s made,
0.031/s used); the 2% surplus leaves by an output port at the end of the farm's belt, which takes its share whether
the farm is short or not. Once the farm fell behind, the port kept draining the loop and it never restarted; moss,
fawogae substrate and the pack stopped behind it. The rules and the mod shut that port for a loop's own item and
keep a reserve in front of the farm. The fair-level fix still to build: a reserve gate from real parts.
