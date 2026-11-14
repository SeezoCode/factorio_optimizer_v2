# Can a Py factory hold itself? The overnight test

2026-11-13, the night after [WHY_NO_SCIENCE.md](WHY_NO_SCIENCE.md). The question put to the model: is there a plan and
a set of rules under which a Py factory keeps making its science when every rate is a few percent off and after an
outage? If none exists, no layout work can help and big Py should stop. If one exists, what does it cost and what has
to be built?

## Verdict

**The plan half is solved. Belts are the half that fails; a shared stock for low-rate items is the way through.**

- **Yes, a design exists under which every Py science plan holds itself in the model**: rates with a margin of 1 to
  2%, a meter on every machine, an overflow on everything but carriers, a stock of each loop's starter items and a
  start-up allowance. All eight plans (56 to 2,524 machines) make their first science within 1 to 9 hours from empty
  and hold 100% after priming ends, with whole crafts, late deliveries and every rate up to 2% off (with continuous
  flows also 5% off and an hour's outage, smallest user first).
  It costs 14 to 41% more raw input at 1% (32 to 129% at 2%) and almost no machines (utility: 13% more).
- **In game, on the layouts we have, no run made a science pack** in up to 14 game-hours, with the check standing
  in for the unbuilt parts. On belts about half the recipes never reach their rate: belts do not share out a
  metered supply. With the same factories' items carried as a robot network would carry them, most recipes run at
  their rate within 12 hours (chemical science: 374 of 481, against 215 to 262 on belts; under a tenth of their rate:
  14, against 62 to 135) and the science machine waits for its last one or two ingredients.
- **So it can be fixed, and the night says how: the plan as tested, and low-rate items through a shared stock
  instead of belts.** That is a second phase of the project, not a patch. The rate program, the meters, the
  overflows and the starter stocks are known work. Robots and chests for the low-rate items, belts for the bulk:
  676 of production science's 744 recipes run under 30% of one machine, and that tail is where belts fail and where
  robots are enough. (Fluids need a stock as well: pipes hold too little against Py's batches. See the second
  night.) Not shown that night: a science pack in game, and a layout built this way.
- **The second night (2026-11-14) brought both**: VERDICT2

## The design that holds in the model

1. **Rates with a margin.** For the plan's own recipes, a second rate program (`tools/plan-sim/margin.py`, on the
   planner's solver) finds the rates at which every item the factory uses is still in surplus when every maker runs
   slow and every user fast by a tolerance. The surplus leaves through overflows. Carriers that only go round
   (barrels, cages, heat bricks; 2 to 14 items a plan) cannot be in surplus: they are balanced exactly and never
   overflow.
2. **A meter on every machine**, set to those rates.
3. **An overflow on every pool that is not a carrier.**
4. **A stock of each loop's starter items**: an hour or two of use, and primed until the loops run.
5. **Smallest user first** where an item runs short, if the meters can be more than about 2% off. With meters within
   the tolerance, the order on the belt does not matter.
6. **No recipe below its planned rate** and **a start-up allowance** on each meter (both found with whole crafts,
   below).

Optional: production by demand on top (a machine stands while the pools it fills are full). It changes nothing in
what is made and saves raw input (production science: 1.35 instead of 1.62 times the plain plan's).

### Results: every plan, ten random cases each

Pools with continuous flows; margin for a tolerance of 2%; every meter off by a random amount (a different draw
each run); "outage": 5% of machines stand for an hour. A run passes at 95% of the target or more.

| Science | Machines | Rates 5% off, outage, smallest first | Rates 5% off, order on the belt | Rates 2% off, order on the belt | Rates 10% off, smallest first |
|---|---:|---:|---:|---:|---:|
| Py science 1 | 56 | 10 of 10 | 7 | 10 | 9 (worst run 94%) |
| logistic | 325 | 10 | 0 | 10 | 8 (95%) |
| Py science 2 | 406 | 10 | 1 | 10 | 3 (86%) |
| chemical | 512 | 10 | 1 | 10 | 1 (23%) |
| Py science 3 | 681 | 10 | 0 | 10 | 0 (43%) |
| production | 869 | 10 | 0 | 10 | 2 (0%) |
| Py science 4 | 1,102 | 8 | 0 | 10 | 1 (0%) |
| utility | 2,524 | 10 | 0 | 10 | 0 (0%) |

The plain plan under the same test (meters at its own rates, smallest first, the same stock): none of 6 runs for
production science.

### What the margin costs

The margin compounds round the loops. For production science a tolerance of 1% costs about a quarter more raw
input, 2% 62% more, 3% more than double, and 5% four times, with control units left without any margin. (The table
in the next section has every plan.) So the tolerance has to be small, and the meters as exact as the plan assumes.
A clock on a circuit wire and an enable condition on each machine is exact to the tick.

### With whole crafts, late deliveries and a cold start

The model above lets a machine make a hundredth of a craft. The harder version: whole crafts only, a bucket of
crafts as the meter, buffers of ten crafts, everything made arriving two minutes late, two hours' stock of each
loop's starter items, started from empty and primed for 48 hours, then left alone for 16. Two more rules turned out
to be needed:

- **No recipe below its planned rate.** Left free, the second rate program cuts some recipes to a trickle (a craft
  in days), and one of them then holds the whole start (logistic science never started).
- **A start-up allowance.** Each meter starts with enough crafts in hand to give every user of its machine's products
  one craft's worth. Without it a machine planned at a craft every three hours keeps its users waiting for its
  second craft: chemical science took 29 hours to start, Py science 2 83, Py science 4 75. A flat allowance (5 or 20
  crafts for all) brings the over-running back; this one does not.

| Science | Machines, plain plan | Margin 1%: machines, raw input | Margin 2%: machines, raw input | First science, hour | Hours 49 to 64, left alone |
|---|---:|---|---|---:|---|
| Py science 1 | 56 | 56, 1.14 times | 56, 1.32 | 1 to 2 | 101 to 102%, no hour under 100% |
| logistic | 325 | 325, 1.41 | 326, 2.29 | 3 | 101 to 102% |
| Py science 2 | 406 | 406, 1.17 | 406, 1.43 | 6 to 8 | 100 to 102% |
| chemical | 512 | 512, 1.18 | 512, 1.42 | 2 | 100 to 102% |
| Py science 3 | 681 | 689, 1.22 | 715, 1.89 | 2 | 101 to 104% |
| production | 869 | 878, 1.26 | 895, 1.63 | 1 to 2 | 101 to 102% |
| Py science 4 | 1,102 | 1,114, 1.24 | 1,134, 1.56 | 7 | 101 to 102% |
| utility | 2,524 | 2,848, 1.35 | 3,517, 1.90 | 6 to 9 | 100 to 102%, worst hour 97% |

Each plan was run three ways: 1% margin with exact meters, 2% with exact meters, 2% with every meter up to 2% off.
All three hold for every plan. What does not hold:

- **The plain plan**: with exact meters 68 to 82% and falling (production science); with meters 1% off, about a tenth.
- **Errors past the tolerance**: a 1% margin with rates 2% off passes 1 run in 3. Smallest-first sharing does not
  rescue that here as it does with continuous flows.
- **Small pools with late deliveries**: with a minute's stock instead of two hours', production science runs at 100%
  while primed and dies within two hours of priming ending.

### What did not work

- **Meters on only some machines** (those with an overflow on some product, the rest stopped by full outputs):
  collapses. An unmetered machine takes what it can reach while its buffers fill.
- **A start-up allowance** (every meter starting with 5 or 20 crafts in hand) and **a boost while stock is low**:
  both bring the over-running back. **Every meter 20 times faster for the first two hours**: no faster start; the wait
  is in recipes that take hours.
- **A less entangled plan from cheaper surplus** (by-products leaving at 3% of today's price): the big loop stays (515
  of 690 recipes) and raw input rises 43%. The entanglement is Py's.
- **Reserve by rule** (outside users only above a level) in the plain plan: starves them, since its pools sit at
  nothing. With a margin the pools sit full and a plain bigger buffer does the job.

## In game

The design can be tried on the layouts we have, with the check standing in for what is not built: meters at the
margin rates (`--meter-file`), each starting with its users' first batch in hand (`--meter-start`), an overflow
everywhere (`--void-full`, which now leaves carriers alone), loops primed through the warm-up. Two limits: the
layouts were built for the plain plan (in production science 60 machines would have to run past their top speed, so
it runs at half rates and could make half its science at best), and nothing orders who draws first.

**No run made a science pack.** But how far each got differs by a factor of ten, and it differs by transport.
Recipes "at their rate" ran at 90% of their metered rate or more over the last hours of the run; "under a tenth"
counts recipes due at least two crafts in that time.

| Chemical science (512 machines, 481 recipes), from empty | Game hours | Machines that never crafted | Recipes at their rate | Recipes under a tenth |
|---|---:|---:|---:|---:|
| belts, margin meters | 1.7 | 209 | | |
| belts, start-up allowance for one user | 14 | 73 | 215 | 135 |
| belts, allowance for every user, output inserters work only for a belt in need | 14 | 2 | 262 | 62 |
| **robot-like transport** | 12 | 3 | **374** | **14** |
| robot-like transport, one craft's worth a delivery, double allowance | 4.5 | 11 | 280 | 17 |
| the same | 10 | 5 | 323 | 13 |

| Production science (869 machines, 742 recipes) at half rates, from empty | Game hours | Machines that never crafted | Recipes under a tenth |
|---|---:|---:|---:|
| belts, start-up allowance for one user | 10 | 130 | 142 |
| belts, output inserters work only for a belt in need | 7 | 126 | 149 |
| belts, allowance for every user, output inserters work only for a belt in need | 10 | 114 | 176 |
| **robot-like transport** | 9 | **14** | **33** |
| robot-like transport, one craft's worth a delivery, double allowance | 7 | 145 | 241 |

"Robot-like transport" is `--virtual-bots`: once a second a machine holding less than a craft's worth of an item is
given it out of other machines' outputs, first come first served, and the machines' output inserters stand. Nothing
is made up, only moved; belts still bring the raw inputs and pipes carry the fluids as built. It is what a robot
network with a chest at every machine would do, without the flight time.

What the reports show:

- **On belts every root is a maker at its metered rate whose users go unserved** (13 of 13 in production science,
  13 of 14 in chemical). Titanium plate in chemical science: made at its metered 0.041/s, 0.027/s used, three of
  its seven users without a single plate.
- **Belts do not share out a metered supply.** A machine that drops onto several belts hands each a share by turns,
  not by need (158 of chemical's 512 machines and 286 of production's 869 drop onto two belt networks or more).
  Unmetered it makes more until every belt is full and the split stops mattering. Metered it makes only what the
  plan needs, and the belt whose users are stocked silts up (100 tiles hold 800 plates, hours of a slow maker's
  output) before it backs up to the machine. Along one belt the first users stock up before the last sees anything.
  Two rules in the check for exactly that (`--belt-pull`, and an allowance counted for every user) halve what is
  left in chemical science and change little in production.
- **With robot-like transport both factories come alive almost everywhere.** After 12 hours chemical science's
  machine holds 8 of its 10 ingredients in full and waits for the two with the deepest chains (mechanical parts and
  advanced circuits); production's holds 6 of 9 after 9 hours. What is left waits at metered pace: a dozen levels
  of machines at a craft every 10 to 40 minutes each.
- **The start-up allowance is the touchy part.** Doubling it brought chemical science to within 13 recipes of its
  first pack in 10 hours (its science machine short of mechanical parts and advanced circuits only), and set
  production science back (145 machines never crafted after 7 hours, against 14 after 9): with more in hand, machines
  run ahead of each other again, as the model warned for flat allowances.
- **Starting is slow whatever carries the items.** Half of chemical's machines are planned to craft less than once
  in ten minutes. None of the 90-minute checks of the last weeks could have shown these factories working.

The pool model has one stock per item that every user draws from. That is what a pipe network is, and what a robot
network is, and it is not what a belt is. The game agrees with the model where the transport is a shared stock and
disagrees where it is a belt.

## The second night: first packs, a factory built for robots, and what the model had left out

Details in [NIGHT_LOG_2026-11-14.md](NIGHT_LOG_2026-11-14.md).

### First packs in game, and why they stopped

Chemical science on the old layout, items carried robot-like with a storage stock (`--vb-buffer`): **the first
science packs made in game without feeding**, 12 crafts by hour 10, half the planned rate over the last two hours.
They did not last: the same set-up run on stood at 12 crafts from hour 14 to hour 17. Traced down eleven levels from
the science machine: a heat exchanger at a fifth of its rate for want of pressured water, of which the fusion
reactor next to the maker had taken 66,000 of 69,000 made (57,000 by plan). The reactor takes 10,000 a craft and its
input box holds 20,000, four hours of all that is made; the pipe gives to whoever is first.

With a stock for fluids as well (`--vb-fluids`), ten hours from empty and four measured:

| Chemical science, robot-like transport with a stock | Science crafts by hour 14 | Crafts by hour, hours 7 to 14 (6.1 due) | Recipes at their rate in the last hours | Machines that never crafted |
|---|---:|---|---:|---:|
| items only | 12 | 10 by hour 8.4, then 2 more | 423 of 481 (hours 9 and 10) | 1 |
| fluids too, first come first served | 24 | 2 4 6 0 5 1 3 3 | 419 of 481 | 0 |
| fluids too, smallest user first | 30 | 7 4 4 4 2 5 3 0 | 415 of 481 | 0 |

**Packs now come hour after hour, at 41 to 49% of the plan over the last four hours**, where they had stopped. Not at
plan yet, and the reports say why: the stock never saw the pressured water. A maker's output box empties into its
pipe at once, the reactor beside it still had the water first, and the heat exchanger made 6 of its 29 crafts in
both runs, exactly as before. The check now also lets a small user short of a craft draw from what bigger users of
the fluid hold (`--vb-share small` with `--vb-fluids`); in a half-hour test the heat exchanger made 14 crafts where it
had made 28 in 14 hours. CHEM_K

### What the model had left out

The model served every user from one pool and took only what a craft needs. The game's machines each keep two
crafts' worth of every input (fluid boxes up to four), a belt, a pipe or a robot gives to whoever comes first, and a
pipe holds almost nothing. With that in the model (`sim2.py tank=2 fluidk ringroom`), margins of 1%, cold start,
48 hours primed and 16 left alone, the target over those 16 hours in two random cases:

| Science | Machines | Everything in store, room for carriers | Fluids in pipes only | Fluids: one batch in store | Carriers without room |
|---|---:|---:|---:|---:|---:|
| Py science 1 | 56 | 101% | 101% | 101% | 101% |
| logistic | 325 | 101% | 101% | 101% | 101% |
| Py science 2 | 406 | 102% | 52% and 84% | 102% | 102% |
| chemical | 512 | 96% and 101% | 0% and 62% | 96% and 101% | 0% both |
| Py science 3 | 681 | 101% | 96% and 101% | 101% | 101% |
| production | 869 | 101% | 101% | 101% | 101% |
| Py science 4 | 1,102 | 101% | 101% | 101% | 101% |
| utility | 2,524 | 101% | 101% | 101% | 101% |

- **The design survives hoarding and first come first served**, given a stock of everything and room for the
  carriers. Three rules join the six above:
- **7. A stock for fluids too**: one batch of the fluid's biggest user is enough. Without it chemical science and Py
  science 2 lose most of their target (serving the smallest user first would also do; a deeper meter bucket does
  not).
- **8. Room for every carrier of a ring**, or a fixed count of them. Chemical science otherwise stops dead on cages.
- **9. Need first.** Hoarding does not cost the steady state, it costs the start: the first pack comes at hour 12 to
  49 from empty instead of hour 2 to 9. Meters run three times faster for the first hours help three plans and set
  three back. Filling machines that hold less than one craft's worth before anybody stocks a second brings every
  plan back to hour 2 to 22 and all hold 101 to 102% afterwards. In a blueprint that is a wire from each machine
  to its requester chest.

### A factory built for robots

The planner now solves rates with a margin itself (`planner.margin`), and a factory comes out built for robots from
configuration alone: blocks of at most two machines, every item link between blocks by robots, one shared chest per
item at each end of a block. Logistic science: 337 machines in 169 blocks, 412 robot links, every block laid out
within seconds, stitched at the first try at 531 x 544 (the belt factory was 319 x 342).

LOGI_GAME

## What would have to be built

In the order that keeps every step testable in the model first:

1. **Margins in the planner.** The second rate program as a planner stage: a tolerance of 1%, no recipe below a
   floor, carriers found and balanced exactly, each machine's meter rate and start-up allowance written to the
   plan. The split into blocks and the block rebalancing must keep the surplus (today they balance exactly).
2. **The model as the planner's test** (a port of `sim2.py`): whole crafts, late deliveries, rate errors, an outage.
   A plan that fails it is not laid out.
3. **Meters**: in the mod a bucket of crafts per machine with the start-up allowance (the check has both now); in a
   plain blueprint a clock on a circuit wire and an enable condition on each machine. Py's machines take wires (606
   of 626 machine types and all 18 furnaces have a circuit connector).
4. **An overflow that really empties** for every item that is not a carrier. Today a block has one chest per
   by-product, which fills. Py has void recipes (skipped today); robots hauling to one void is the other way.
5. **Low-rate items through a shared stock instead of belts**: inside a block, a requester and a provider chest at
   each machine for items under a set rate (robot links between blocks already work this way), belts for the bulk,
   pipes as now. The stock is also where the starter items and the buffers of ten crafts live. This replaces most of
   the belt routing that the big blocks struggle with.
6. **Checks that can see it**: from empty these factories need hours, so the in-game check needs long warm-ups and
   the allowance, never 90 minutes.

Not needed: smallest-first ordering (only matters when meters are further off than the tolerance), production by
demand (saves raw input, changes nothing else), more flow rules in the check.

Two proofs come before the rest. The cheap one: let the robot-like run of chemical science go on for a day or two
of game time until packs come out at a steady rate (a few hours of machine time, nothing to build). The built one:
one mid-size factory (logistic science, 325 machines) laid out with steps 1, 3 and 5 and run under meters. If that
makes science at plan from empty, the rest is scale. If it does not, big Py should stop there.

