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
