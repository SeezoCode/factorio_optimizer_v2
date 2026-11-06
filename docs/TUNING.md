# Tuning notes

Defaults live in `src/main/kotlin/config/Tuning.kt`; `src/main/resources/tuning.json`, when present, overrides
them (it is written by `--calibrate`). Numbers below are trial success rates: one trial = anneal, route with up
to three feedback rounds, power, validate, at a fixed square box, 6–8 seeds.

## Placer weights

The plan's starting values (beta 0.5, gamma 4, delta 0.5) packed machines flush against each other and against
the box edge. Routing then failed on T4 in almost every trial even at 34 x 34.

| beta (RUDY) | gamma (pins) | delta (DI) | T3 at 20 x 20 | T4 at 26 x 26 |
|------------:|-------------:|-----------:|--------------:|--------------:|
| 0.5 | 4 | 1 | 6/8 | 0/8 |
| 5 | 12 | 2 | 8/8 | 1/8 |
| 20 | 12 | 6 | 8/8 | 3/8 |

`delta` is now per belt inserter a fitting direct-insertion pair saves (`min(producer, consumer rate) / belt
pickup rate`), not per pair, and pairs a tile or two away from fitting get a fading share of it so the anneal
can find the exact gap.

## Router changes found by these runs

- Nets with more than `max_chain_visits` (12) inserter visits, or more flow than 0.9 of a belt, are split into
  several chains after direct insertion (capacity-balanced k-means on consumers, nearest producers supply each
  chain). This is the plan's b > 1 split, done per routing run so that it always matches that run's DI.
- A producer the chains split (a share of its output on each of two chains) goes whole to its larger chain when
  the other chain's producers have the spare speed to cover its share (machine counts are rounded up). In the
  game a machine cannot steer its output: each of its inserters takes whenever its belt has room, and one ahead
  of its chain's other producers fills their gaps first. Measured on the 2/s six-science factory, a gear
  assembler planned to send nearly all its gears to its block's output sent about half each way and the
  factory fell to 1.6 chemical and 1.15 utility science per second. Where no spare speed covers the share, the
  split stays, and its shares drop after their chains' other producers (the shares that are a producer's
  largest first); a chain that cannot route so drops that order. The validator models what the game does with
  every split (`SPLIT_OUTPUT`).
- Chains may interleave producers and consumers whenever the supply upstream covers the consumer's share (the
  plan's v2 prefix rule). Strict producers-first chains drove past consumer slots they could not use yet.
- A* states are (tile, heading, kind), so a found path can cross itself; such paths are repaired lazily by
  forbidding the offending state and searching again (`repair_restarts`).
- When a leg finds no path, the previous leg is undone and its end state banned (`max_backtracks`).
- The present-congestion factor is capped at `p_fac_max` = 1000, as in VPR.
- Direct-insertion flows are settled the way the validator models them (a consumer draws evenly through all its
  DI inserters); the greedy assignment alone overstated what one producer delivers.

## v2 changes for larger blocks

Measured on chemical science at 0.5/s from ore and crude oil (43 machines, 20 nets; seeds that route out of
those tried, single trials as in the search):

| Change | 62 x 62 | 54 x 54 | 48 x 48 |
|--------|--------:|--------:|--------:|
| Before | 0/3 | — | — |
| Endgame (rip up and reroute around the last conflicts, undo rounds that end worse) | 1/3 | — | — |
| Passing claims skip inserter tiles another net holds | 4/4 | 2/4 | 0/4 |
| Pipe paths that cross themselves are repaired; free stub tile beyond every fluid connection | 4/4 | 4/4 | 3/4 |
| Final: also best-iteration restore, endgame time cap, no shared stubs | — | — | 2/4 |

- Endgame knobs: `endgame_rounds` (30), `endgame_radius` (3, +2 every 4 rounds), `endgame_iterations` (4 per
  round), and it starts only when negotiation ends within `endgame_max_overuse` (150) conflicts and
  `endgame_max_unrouted` (4) unrouted nets, and stops starting rounds after `endgame_time_factor` (2) times the
  negotiation's own time. Negotiation first returns to its best iteration.
- Search: until the first success the box grows by `grow_factor` (1.25) after `grow_rounds` (2) seed rounds per
  size, instead of doubling. With doubling, the user's run first succeeded at 248 x 248; now the full search
  reaches 46 x 46 in 7 s and 43 x 43 in 129 s.

Second round, with three benchmark cases (two seeds each, success / tried). "54" is red + green + chemical
science at 1/s with plates as inputs, "110" the same from ore.

| Change (cumulative) | chem 42 | chem 44 | 54 @ 70 |
|--------|--------:|--------:|--------:|
| Before this round | 0/2 | 1/4 (search) | 0/2 |
| Chain ends by direction, placer feedback from the best iteration | 1/2 | 2/2 | 0/2 (1 conflict left in one round) |
| + underground weaving | 2/2 | 2/2 | 1/2 |
| + near-miss retries (best round kept) | 2/2 | 2/2 | 1/2 (1 conflict left) |
| + placer belt-access demand, weight 1 | 1/2 (1 router bug, since fixed) | 2/2 | 2/2 in 3 s |
| + placer belt-access demand, weight 2 | 2/2 | 2/2 | 2/2 in 2 s |

Harder sizes with the access demand:

| Weight | chem 38 | chem 40 | 54 @ 60 | 110 @ 90 |
|-------:|--------:|--------:|--------:|---------:|
| 1 | 1/2 | 2/2 | 2/2 | 2/2 (8 s each) |
| 2 | 0/2 | 2/2 | 2/2 | 2/2 |

Weight 1 is the default (`access_demand`; `access_belt_tiles` 3 belt tiles per terminal, `access_range` 2).

| | chem 38 | 54 @ 56 | 110 @ 80 |
|--|--------:|--------:|---------:|
| `lane_sharing` off | 1/2 | 2/2 (22 chains) | 2/2 (32–33 chains) |
| `lane_sharing` on | 0/2 | 2/2 (19 chains) | 2/2 (29–30 chains) |
| `max_chain_visits` 40 instead of 12 (at 42 / 70 sizes) | 1/2 at chem 42 | 0/2 at 70 | — |

- Weaving: `weave_tier` "auto" uses express undergrounds under fast belts (fast under express), only on chains
  whose flow fits that tier; `weave_penalty` 1.0 per pair.
- Near misses: `near_miss_max` (4 conflicts), `near_miss_attempts` (4), `near_miss_endgame_factor` (3).
- Placer feedback: history at the best iteration plus `feedback_overuse_weight` (2) per conflict left.

## Night sweeps (2026-10-24)

`SweepTest` (`SWEEP=<spec>`; parallel single trials at fixed sizes, 8–12 seeds per cell) on chemical science
0.5/s at 36–38, the 54-machine job (plates as inputs) at 50–52 and the 110-machine job from ore at 64–70, sizes
at the edge of what routes. Full tables in [NIGHT_LOG.md](NIGHT_LOG.md).

- **Router present-cost growth** was the one strong lever: successes out of 60 for `p_mult` 1.5 / 1.3 / 1.2 /
  1.1 were 7 / 9 / 13 / 19. Defaults are now `p_mult` 1.1, `max_iterations` 120, `stagnation_stop` 40
  (1.05 routes slightly more per trial but takes ~55% longer, so not per minute of search).
- **Placer feedback weight** `beta2` 1.0 instead of 0.2: 43 vs 37 successes over three sweeps at no cost; now
  the default.
- Flat (within noise): access demand 0.5–1.5, belt tiles 2–4, access range 3, weave penalty 0.3 (3 is worse),
  near-miss threshold 10, lane sharing, anneal effort ×4, beta 10 (40 is worse), RUDY bin 3 (6 is worse),
  gamma 24, delta 12 (3 is worse), h_fac 0.25 / 1, 100 iterations at p_mult 1.5, ug_penalty 1 / 4,
  pin_reservation 0 / 3, p_fac_first 1 (worse), 6 feedback rounds (more per trial, slower).
- **Spread retries** (move machines away from conflict clusters and reroute): no gain, ~45% slower; off by
  default (`spread_max` 0).
- Failed trials at the edge sizes end with a median of 15–35 conflicts: placement-limited; the remaining
  headroom is in placement structure, not in knobs.

## Column placement and path cost (2026-10-25)

Two placer options inspired by a hand-built utility science block (33 x 44 against the solver's 42 x 42):
`place.initial` = "columns" (machines of a recipe stacked in columns with item-sized corridors, annealed from
there) and `place.belt_cost` = "path" (bounding box plus the gaps of a greedy path between the machines'
inserter rings, instead of bounding box x q(k)). Sweeps on 8 threads, job 9 with its own tuning but 3 feedback
rounds; "closest" = conflicts + 10 x unrouted chains of the trial's closest routing run (lower is better).

| Variant | job 9 at 42: mean closest (seeds) | job 9 at 40 | chemical science 0.5/s at 38: built |
|---------|---------------------------------:|------------:|------------------------------------:|
| random start, bounding box (default) | 25.8 (14) | 45.8 (6) | 9 of 16 |
| path cost, weight 1 | 30.8 (6) | 59.5 (6) | |
| path cost, weight 0.3 | 29.3 (6) | | |
| columns, flush, column moves only | 53.2 (6) | 73.7 (6) | |
| columns, 1 lane between machines, column moves only | 29.4 (14) | | |
| ... with swaps only inside a column | 23.0 (8) | 55.6 (8) | 3 of 16 |
| ... with free moves after the start (column defaults) | 25.4 (14) | 47.1 (8) | 10 of 16 |
| random start, `route.max_chain_visits` 24 | 21.8 (8; same seeds at 12: 24.3) | | |

- Flush columns are walls: every flow across one must tunnel under it. One free lane between machines halves
  the conflicts. Swaps between columns mix their recipes; keeping swaps inside a column helps job 9 at 42 but
  hurts fluid-heavy jobs, where chemical plants need room to turn.
- Neither option beats the default on average; columns with free moves match it and give the search a
  different family of layouts (`"initial": "mixed"` alternates them by seed). The path cost was slightly worse
  everywhere: with q(k) gone, many-terminal nets weigh less against congestion.
- The hand-built block's density comes as much from routing as from placement: one belt per input running down
  a whole column (manifold), two items per input belt, and an input bus. Next steps there, not in these knobs.

## Open

- Underground jumps are cheaper per tile than belts with `ug_penalty` 2.0 + 0.1 x span, so routes use many
  undergrounds; 5.0 changed little in success rate. Worth revisiting once the score weights are settled.
- The remaining failures at the smallest sizes end with 10–15 conflicts around furnace columns; weaving and
  the access demand help, the rest is placement.

## Calibration

`--calibrate` over T1–T4 (60 s each) collected 17,013 routed chains: the median of belt tiles / HPWL is 1.0 up
to 4 terminals and 1.26 at 5, so q(k) was flattened for small k. The smallest box over machine area was 2.78
(T1), 3.0 (T2), 2.5 (T3) and 4.9 (T4); the area factor for s_lo is now 0.95 x 2.5 = 2.375 instead of 1.8.

## Benchmarks

Default config (fast belts, assembling machine 3, no inserter research), `tuning.json` as committed, 4 threads,
3 seeds per size, 120 s budget. Score = area + 0.3 x belts + 1.0 x underground ends + 0.2 x inserters.

| Job | Machines | Best box | Found after | Belts | Underground ends | Inserters | Score |
|-----|---------:|---------:|------------:|------:|-----------------:|----------:|------:|
| T1 gears 1/s | 1 | 5 x 5 | < 1 s | 3 | 0 | 3 | 26.5 |
| T2 circuits 2/s | 3 | 9 x 9 | < 1 s | 12 | 2 | 10 | 88.6 |
| T3 red + green 0.5/s | 10 | 15 x 15 | 7.7 s | 40 | 14 | 30 | 257.0 |
| T4 processing units 0.2/s | 10 | 20 x 20 | 23 s | 89 | 28 | 41 | 462.9 |
| Six science 1/s each | 161 | none | — | | | | |

T1 and T2 numbers are from the calibration runs. For six science, see STATUS.md: routing does not converge.
