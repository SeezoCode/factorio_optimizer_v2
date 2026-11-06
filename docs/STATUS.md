# Status against the plan

Milestones in build order, with where each acceptance test lives. "In game" items need a person with the game;
see [VERIFY.md](VERIFY.md). The v2 milestones (D0–D7, [PLAN_V2.md](PLAN_V2.md)) come first; the v1 record (M0–M7)
follows.

## D0 Facts — done in code, in-game check open

- Research levels set inserter hand sizes; base-game fluid connection table for assemblers, chemical plants and
  refineries (rotation and mirroring). Verify blueprints v10–v15 cover the new facts; none is ticked off yet.

## D1 Planner — done

- `PlanTest`: LP with recipe choice and by-products: advanced oil processing plus cracking to plastic, uranium
  processing keeps its by-product, sourced items, burner fuel, T1–T4 counts unchanged.

## D2 Mixed footprints — done

- `PlacerTest`: orientation codes with mirroring, used connection tiles inside the box and apart from other
  fluids; a free stub tile straight out from every used connection so a pipe can always reach it.

## D3 Pipes — done

- `ValidatorTest` fluid fixtures (mixed, stuck, leaking, broken, pipe-to-ground pairing); `TrialTest`: the plastic
  block and T4 with piped acid validate. Pipe paths that cross themselves are repaired by banning the state
  and searching again.

## D4 Belt trees — done

- Splitter branches (`RouterTest` fork fixture: 29 belts against 38 as one chain), lane-aware validator and
  router, items split over several belts (40 iron/s test).
- The validator assigns producer output to consumers by max flow over belt order: an inserter's items reach
  only what lies downstream of its drop.

## D5 Ports and manifest — done

- Products and surplus by-products leave through output ports (items on a belt, fluids in a pipe); `ports.json`
  lists every input and output with tile, edge and rate. Test mode empties outputs into void infinity chests and
  drains. `TrialTest`: gears leave through a port and the harness empties it; sulfuric acid leaves by pipe.

## D6 Big blocks — in progress

Measured with `DiagTest` (single trials at a fixed box, as the search runs them); details in
[TUNING.md](TUNING.md#v2-changes-for-larger-blocks).

| Job | Machines | Before D6 | Now |
|-----|---------:|-----------|-----|
| Chemical science 0.5/s from ore and crude oil | 43 | first success 248 × 248 (doubling search) | 40 × 40: 2/2 seeds, 38 × 38: 1/2 |
| Red + green + chemical science 1/s, plates as inputs | 54 | no success at 70 × 70 | 56 × 56: 2/2, a few seconds each |
| Red + green + chemical science 1/s from ore | 110 | no success at 100 × 100 (7–15 min per seed) | 80 × 80: 2/2, about 8 s each |

Full searches (2 threads, 3 seeds per size, low priority): chemical science 0.5/s reached 37 × 37 in 5 minutes
(`solves/1_long_big_throughput_v2`); red + green + chemical science 1/s from ore reached 68 × 68 in 8 minutes
(`solves/3_long_v2`).

What made the difference, in order of effect:

- **Placer: belt-access demand.** Each machine adds its inserters plus a few belt tiles per belt terminal to the
  congestion bins in a 2-tile band around it, so many-input machines keep room for the belts that pass them.
  Failing runs had all their conflicts in one tight cluster of chemical science and engine assemblers.
- **Router endgame.** When negotiation stalls, every net near the remaining conflicts is ripped up and rerouted
  together in a shuffled order; rounds that end worse are undone. Negotiation first returns to its best iteration.
- **Chain ends by direction.** A belt in front of a chain's end conflicts only if it would take the chain's items
  (not one facing it head-on).
- **Underground weaving (B5).** A second underground tier (express under fast belts) has its own channels, so
  it can pass under a row the main tier already uses. On by default (`weave_tier`); verify kit v16.
- **Placer feedback from the best iteration** instead of the final, detoured one.
- **Near misses.** A trial whose best round ended within 4 conflicts goes back to that placement and retries:
  each retry shifts one machine next to a conflict and routes again with a larger endgame.
- **Inserter claims.** Passing claims skip an inserter tile another net holds when a free one comes later.
- **Search.** The box grows by 25% with two seed rounds per size until the first success; `box.max_size` and
  `box.start_size` set the range.
- **Two items per belt (`lane_sharing`, off by default).** Chains of two items that mostly the same machines
  consume, each fitting a lane, share one belt (one item per lane; producers drop onto their item's lane; a shared
  input belt enters straight from the edge with its lanes listed in `ports.json`). It routes and validates and cuts
  the chain count by about 10%, but did not raise success rates at the sizes measured, and it depends on the
  inserter drop-lane facts (verify kit v2, v13), so it stays opt-in until those are checked in game.
- Same-item merging was reviewed: chains of one item never share belts; within a chain, splitter branches do.
  Allowing up to 40 visits per chain (fewer, longer, branching chains) routed worse than 12, so 12 stays.

Validator changes found by this work:

- Items are tracked per lane; contamination means two items on one lane. An inserter may only drop onto a lane
  that carries its item.
- Producer output is allocated by max flow over the real constraints: a producer's items reach only consumer
  inserters downstream of its drop, each consumer inserter moves at most its rate, and a machine's need along
  a chain may be met by any of its inserters there. The belt walk then locates starvation (upstream machines draw
  first) and checks lane and belt capacity; a splitter divides each lane by its item's demand on each side.
- A machine's input inserters are checked against its demand by max flow, so an inserter on a shared belt moves
  whichever item the machine is short of.
- Router bug fixed: a branch splitter could replace a curve fed from the side, because underground entrances did
  not record their heading; that branch then got nothing.

Night of 2026-10-24 (see [NIGHT_LOG.md](NIGHT_LOG.md)):

- Router: present-cost growth `p_mult` 1.1 (was 1.5) and placer feedback weight 1.0 (was 0.2) after ~1,700
  benchmark trials; every other knob was flat.
- Fixed: a pipe-to-ground end could sit on a machine's fluid connection and not join it (new fluid fuzz test).
- Endgame threshold scales with the number of chains (2 per chain) for big blocks.
- 110-machine job: 64 × 64 in a 2-hour search with the tuned defaults (68 × 68 before the night);
  chemical science 0.5/s stays at 37 × 37 (36 never routed in 675 trials).
- Six science from ore is 618 machines / 144 chains: 130 × 130 ends at 2,300+ conflicts, 200 × 200 at 168–303
  after 76 minutes per trial. Not reachable as one block yet; split it by hand.

## Factory of blocks (block split with stitching)

- `blocks` in the job: plan once, split into blocks of at most `max_machines` machines (pipe-joined machines stay
  together; `split_at` items are preferred cuts), order upstream first in one or two rows, turn every belt between
  blocks into a link with an exact rate (≤ 90% of a belt), generate each block with its link ports facing its
  partner, place the blocks with corridors, route the link belts with the same router, place poles over the whole
  factory and validate it end to end against the full plan.
- Router: nets with several input and output ports, or a port plus producers, get chains per port
  (`buildGeneralChains`); a link port is exactly one belt so both ends pair up; fixed-tile ports can carry a
  direction; undergrounds can be kept from passing under blocks.
- `FactoryTest`: the split keeps blocks within the limit and balances every item; T3 as two blocks stitches into
  one valid factory, also in test mode.
- Red + green + chemical science from ore (110 machines) as 3 blocks of ≤ 40: valid, 115 × 44 (5-minute budget).
- Six science from ore (618 machines) as 6 blocks of ≤ 120: valid, 261 × 181, in about 30 minutes on 10 threads
  (blocks 25 × 25 to 110 × 110). The first attempt failed in block 3 on a validator bug (a machine's draw was
  split per belt chain); fixed with one draw per machine and item.
- Block borders and reserved areas are marked with floor tiles (`marker_tile`); ports sit on the ring outside
  each box (`ports_on_ring`); `reserved` areas stay empty for building by hand.

- Not yet: Steiner/locality terms in the placer; fluids piped between blocks.


## Space Age machines — done in code, in-game check open

- Foundry, electromagnetic plant, cryogenic plant, biochamber, crusher, recycler: sizes from the game data, fluid
  connections from the 2.0 prototypes (all hidden unless the recipe uses fluids), verify blueprint v17.
- Planner: the machine's base productivity plus `research.productivity` per recipe, capped at the recipe's
  maximum, applied to the part of a product above the same item's ingredient amount; burner fuel per fuel
  category (nutrients for biochambers); `raw_resources` limits what enters without a listed source.
- Machines with several item products get one filtered output inserter per product; the validator checks
  filters and rates per product (verify blueprint v18).
- `SpaceAgeTest`: foundry productivity, research productivity and its cap, biochamber fuel, a platform plan with
  only asteroid chunks coming in; foundry, electromagnetic plant and crusher trials, and a platform trial.
- Router fix found on the way: a chain whose only producer needs several output inserters ranked its start
  options with the last attempt's tile ownership, so when it was the first chain routed it never started.
- Not modelled: spoilage, stack inserters' belt stacking, quality outputs, platform foundations.
- `JobFuzzTest` (random jobs: base game, Space Age in two machine orders, space platforms; up to 45 machines, at
  4x and 6x the machine area, three seeds each) found, on 2026-10-24:
  - direct insertion summed a machine's spare output over all its products, so an advanced crusher's copper and
    chunks counted as spare iron ore, a foundry was promised more ore than existed, and the other crusher's ore
    was never routed (also uranium processing); now per product;
  - a raw resource that is also a target (sulfuric acid on Vulcanus) was passed straight through; now it is made;
  - one fast machine could need more inserters than fit around it (an electromagnetic plant making 4 poles/s
    needed 17); the planner now adds machines until each one's inserters take at most `planner.access_share`
    of the tiles around it.
  After the first two fixes, 948 jobs in 33 minutes on 8 threads: no invalid layout, no exception, no blueprint
  round-trip difference. 893 built (45 of them on a second seed or the larger box), 38 platform jobs have no plan
  (rocket fuel, low density structures: no oil products from asteroids). 6 failed: 3 were the third case, and 3
  were Gleba rocket fuel from jelly (23–29 machines with 100+ inserter visits, every biochamber also fed
  nutrients), still 120–170 conflicts short at 6x the machine area. 5 express and turbo belt jobs ran out of time; the
  third fix builds them in under a second.
- User job 9 (utility science 1/3 s, 44 machines, every input pinned to one edge tile) failed every seed at
  any size, up to 120 × 120:
  - copper fed ~25 inserters, so its net was split into chains of at most 12 visits, and every chain had to
    start on the one port tile. Chains per port are now capped by the port's tiles; fewer, longer chains
    branch with splitters instead;
  - petroleum, heavy oil and water were pinned to touching tiles, and pipes on touching tiles join. A fluid port
    is now entered from just outside the box, so its tile can hold a pipe-to-ground open to the outside, and
    such a port stays on the edge (no ring pipe).
  Job 9 now builds at 48 × 48 on every seed in 2–6 s per trial.
- A Space Age factory of blocks, metallurgic and electromagnetic science at 1/s each (50 machines, blocks of
  ≤ 25), validates at 76 × 42.

## Factory compaction, repack and merge — done

- `blocks.compact` (default on): exact removal of rows and columns that only straight belts, straight pipes or
  underground spans cross, checked line by line (nothing may point into a closed gap, no pipe may come to touch
  another pipe or a machine, no inserter reaches across), then poles again and validation; T3 as three blocks:
  35 x 14 -> 32 x 13, the factory test 29 x 18 -> 25 x 11.
- Blocks are laid out in rows of their own widths (no shared column widths across rows); link ports are read
  from where each block put them, so blocks searched before a merge still stitch.
- `blocks.repack`: blocks searched again at fixed row heights (`box.fixed_height`), per-row combinations kept
  if smaller. `blocks.merge`: neighbouring blocks merged (`mergeBlocks`) and searched from their placements side
  by side (`Search(startFrom)`, refined cool), kept if smaller; T3 as three blocks 32 x 13 -> 28 x 13.

## Column placement and path cost — done, not default

- `place.initial` "columns" / "mixed": machines grouped by recipe in columns with corridors sized by their items
  and a lane between machines, falling back to tighter layouts when the box is small; column moves (shift or
  swap whole columns, slide within a column, swaps within a column). `place.belt_cost` "path": greedy path
  between inserter rings on top of the bounding box.
- `PlacerTest`: exact incremental cost with both, the path cost separating a column from a scattered net, a
  column start keeping its columns.
- Benchmarks in TUNING.md: columns match the default, the path cost is slightly worse; the default stays.

## M0 Refactor and planning — done

- Packages as in the plan (`data`, `config`, `plan`, `geom`, `place`, `route`, `power`, `search`, `validate`,
  `emit`, `viz`, `legacy.cpsat`). Machine counts use crafting speed; recipes are found by product through
  `RecipeIndex`, with `recipe_overrides`. Stage 1 writes `plan.json`.
- `PlanTest`: T2 gives 1 circuit and 2 cable machines, one cable net, iron and copper sources; T3 nets and flows
  match `src/test/resources/jobs/t3_expected.json`; strict config parsing.
- `LegacyCpSatTest`: `--engine=cpsat` still solves.
- Deviation: `pressing` is in the default categories, because `transport-belt` is a pressing recipe in the 2.0
  data and would otherwise become a raw input.

## M1 Geometry and blueprint 2.0 — done, in-game check open

- `BlueprintTest`: encode → decode → equal entity list for every entity type; 16-way directions, top-level
  copper `wires`, 2.0 version number, item signals without `type`.
- In game: `docs/verify/t1-hand.txt` and the verify kit are ready; nothing has been ticked off yet.

## M2 Validator and renderer — done

- `ValidatorTest`: one fixture per check (dead-end contamination, contamination that turns a straight into a
  curve, wrong underground pairing, inserter dropping onto another net, uncovered inserter, adjacent infinity
  pipes, missing pipe, overlap, starved and unreachable consumers). The hand-written T1 layout passes with
  rates and power.
- The validator splits a shared producer's output across belt chains by max flow, as belts back up in game.

## M3 Router on fixed placements — done

- `RouterTest`: T1, T2 (direct insertion and belt variants) and T3 hand placements route and validate; a
  crossing that plain A* overlaps is resolved by negotiation with an underground.
- `RouterFuzzTest`: 200 random instances (2–8 machines, 12–30 tiles); every success validates (188/200 route).
  One iteration on 100 x 100 with 60 nets: about 12 ms (target 200 ms).

## M4 Placer — done

- `PlacerTest`: incremental cost equals a full recompute after 10,000 moves (T3 and T4, with history);
  T3 anneals below half its random initial cost for 5 seeds; T2 places a cable machine for direct insertion
  into the circuit machine in at least 4 of 5 seeds; fluid tiles stay apart and inside the box.
- Routing feedback: up to 3 rounds of history → placer (`beta2`) → re-anneal at T0/20.

## M5 Power, fluids, harness — done, in-game check open

- `TrialTest`: T4 validates with an infinity pipe on every acid consumer, none adjacent; test mode feeds every
  source from infinity chests; removing a pole from a valid result fails validation.
- In game: T4 at 90% of target rate within 10 minutes — not run.

## M6 Outer loop — done

- `SearchTest`: box area never grows across improvements; the written blueprint decodes and re-validates;
  `flush` rewrites the best result. Checked by hand: SIGTERM to a running search rewrites the best result and
  logs `stopped: interrupted` (Ctrl-C does the same in a terminal).
- Calibration (`--calibrate`) refitted q(k) and the area factor from 17,013 routed chains over T1–T4;
  results are in `src/main/resources/tuning.json` (area factor 2.375, q(k) = 1.0 up to k = 4).

## M7 Benchmark — partly done

- T3 and T4: see the table in [TUNING.md](TUNING.md#benchmarks).
- Six-science (`src/main/resources/config.json`, 161 machines): no valid layout within the budget. Routing
  diverges at 118 x 118 (belt length doubles as history grows) and plateaus at 60–90 conflicts at 160 x 160,
  mostly belts forced through the same corridors beside machine access slots. It needs placement with
  deliberate belt corridors, more direct insertion, or v2 lane sharing / weaving.
- Comparison with community layouts: [COMMUNITY_BENCHMARK.md](COMMUNITY_BENCHMARK.md) measures 15 factorio.school
  designs against the solver on the same jobs (tools in `tools/blueprints/`).

## Beyond the plan

- Nets are split into several chains after direct insertion when they need more than 12 inserter visits or
  more than 0.9 of a belt (this is where the plan's b > 1 split happens).
- Chains interleave producers and consumers when upstream supply covers the consumer (the plan's v2 rule).
- Leg backtracking, lazy repair of self-crossing paths, capped present-congestion factor.
- Options from the v2/v3 catalog (`weave_tier`, `sinks.mode = output_ports`, tricks I10–I13, B5, B7–B13) are
  rejected by config validation instead of being silently ignored.
