# Py science campaign: progress by science

The 11 Pyanodon sciences, each at 0.02 packs/s from `jobs/py_sciences/`, laid out by the optimizer (2% planner
margin unless said) and run in Factorio 2.1.24 headless by `tools/factorio-verify/verify.py`. Levels from the most
help to none are described in [CAMPAIGN_CONTEXT.md](CAMPAIGN_CONTEXT.md#help-levels-most-help-first). The table is
written by `tools/py-campaign/progress.py`; the sections below it are written by hand as runs finish.

<!-- campaign:start -->
| Science | Machines | Fairest pass | L5 fed | L4 ideal | L3 rules | L2 mod | L1 built | L0 plain |
|---|---:|---|---|---|---|---|---|---|
| Automation science pack | 13 | **L0 plain** | **pass** 3.6 h | **pass** 7.2 h | **pass** 21.6 h | **pass** 21.6 h | **pass** 21.6 h | **pass** 21.6 h |
| pY science pack 1 | 50 | **L2 mod** | **pass** 3.6 h | **pass** 7.2 h | **pass** 21.6 h | **pass** 21.6 h | running, 0 h | · |
| Logistic science pack | 386 | — | · | · | · | · | · | · |
| Military science pack | 13 | **L5 fed** | **pass** 3.6 h | · | · | · | · | · |
| pY science pack 2 | 411 | — | · | · | · | · | · | · |
| Chemical science pack | 487 | — | · | · | · | · | · | · |
| pY science pack 3 | 569 | — | · | · | · | · | · | · |
| Production science pack | solving | | · | · | · | · | · | · |
| pY science pack 4 | solving | | · | · | · | · | · | · |
| Utility science pack | solving | | · | · | · | · | · | · |
| Space science pack | solving | | · | · | · | · | · | · |

Updated 2026-11-27 04:11 UTC. Hours are game hours (warm-up included); a pass is the in-game check's verdict over the level's whole run. Builds drawn tile for tile: [the builds page](https://claude.ai/artifact/7rqXsL9UZFaMF8m7fdcyEk) (private link).
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
| L1 built | auto_L1 | pass, 24 h, 100%; the built overflow exits took 4,000 ash in the last 4 h (a real chest would need emptying or a burner) |
| L0 plain | auto_L0 | pass, 24 h, 100%; no flow control, nothing handed in after the warm-up |

Ahead of plan in the first hours at every level (the warm start's stocks), then exactly the port's rate.
