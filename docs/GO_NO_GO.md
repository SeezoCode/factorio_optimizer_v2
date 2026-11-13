# Can a Py factory hold itself? The overnight test

2026-11-13, the night after [WHY_NO_SCIENCE.md](WHY_NO_SCIENCE.md). The question put to the model: is there a plan and
a set of rules under which a Py factory keeps making its science when every rate is a few percent off and after an
outage? If none exists, no layout work can help and big Py should stop. If one exists, what does it cost and what has
to be built?

## Verdict

**The plan half is solved; the belt half is not.**

- **Yes, a design exists under which every Py science plan holds itself in the model**: rates with a margin of 1 to
  2%, a meter on every machine, an overflow on everything but carriers, a stock of each loop's starter items and a
  start-up allowance. All eight plans (56 to 2,524 machines) make their first science within 1 to 9 hours from empty
  and hold 100% after priming ends, with whole crafts, late deliveries and every rate up to 2% off (with continuous
  flows also 5% off and an hour's outage, smallest user first).
  It costs 14 to 41% more raw input at 1% (32 to 129% at 2%) and almost no machines (utility: 13% more).
- **No, that design did not make science in game on the layouts we have**, in up to 14 game-hours, with the check
  standing in for the unbuilt parts. What stops it is no longer over-running or waste: it is that belts do not share
  out a metered supply. The two rules I added for that in the check more than halved what was left and did not
  finish it.
- **So it can be fixed, but it is a second phase of the project, not a patch**, and its last step is unproven. The
  rate program, the meters, the overflows and the starter stocks are known work. How items reach every user of a
  metered supply is the open part, and the evidence points one way: low-rate items should travel through a shared
  stock (robots and chests, as pipes already are for fluids), with belts kept for the bulk. 676 of production
  science's 744 recipes run under 30% of one machine; that tail is where belts fail and where robots are enough.

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

**No run made science**, where the model, given the same rates, whole crafts and first-come sharing, has chemical
science starting between hours 4 and 9 and production within 5. How far each got, by the machines that never
crafted once:

| Chemical science (512 machines), from empty | Game hours | Never crafted |
|---|---:|---:|
| margin meters | 1.7 | 209 |
| + start-up allowance for one user | 6 | 127 |
| the same | 14 | 73 |
| + output inserters work only for a belt in need | 10 | 84 |
| start-up allowance for every user | 4.5 | 114 |
| start-up allowance for every user + output inserters work only for a belt in need | 4.5 | **51** |
| the same | 14 | FINAL_CHEM |

| Production science (869 machines) at half rates, from empty | Game hours | Never crafted |
|---|---:|---:|
| margin meters at full rates (cannot be reached) | 1.7 | 156 |
| + start-up allowance for one user | 5 | 144 |
| the same | 10 | 130 |
| + output inserters work only for a belt in need | 7 | 126 |
| start-up allowance for every user + output inserters work only for a belt in need | 10 | FINAL_PROD |

What the reports show:

- **Every root is a maker at its metered rate whose users go unserved** (13 of 13 in production science, 13 of 14
  in chemical, and the same after 10 and 14 hours). Titanium plate in chemical science: made at its metered
  0.041/s, 0.027/s used, three of its seven users without a single plate.
- **Belts do not share out a metered supply.** A machine that drops onto several belts hands each a share by turns,
  not by need (158 of chemical's 512 machines and 286 of production's 869 drop onto two belt networks or more).
  Unmetered it makes more until every belt is full and the split stops mattering. Metered it makes only what the
  plan needs, and the belt whose users are stocked silts up (100 tiles hold 800 plates, hours of a slow maker's
  output) before it backs up to the machine. Along one belt the first users stock up before the last sees anything.
  The check's new `--belt-pull` (an output inserter works only while a user on its belt holds less than its stock)
  and an allowance counted for every user more than halve what is left, but did not finish it in the time I had.
- **Carriers lock a machine that makes other things too**: a renderer standing full of 3 cages nobody is ready to
  take, its brain and skin not made.
- These runs are slow by nature: half of chemical's machines are planned to craft less than once in ten minutes, and
  a first craft that waits on a fuel rod waits on a chain 13 machines deep.

The pool model has none of this: in it every user of an item draws from one stock. That is what a pipe network is,
and what a robot network is, and it is not what a belt is. With robot-like transport in the model (everything five
minutes late, first come first served, whole crafts) the design still holds at 100% for production, chemical and
Py science 4.

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

Before any of it, the cheapest proof: one mid-size factory (logistic science, 325 machines) laid out with step 5's
transport, run under meters at margin rates. If that makes science at plan from empty, the rest is scale. If it
does not, big Py should stop there.

