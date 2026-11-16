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
- **The second night (2026-11-14) brought both, in part.** The first science packs made in game without feeding:
  chemical science with its items carried robot-like and a stock, 24 to 30 crafts in 14 hours and 41 to 49% of the
  plan over the last four, once fluids had a stock too. And logistic science laid out for robots by the planner
  itself (531 x 544, stitched at the first try). That built factory has not made a pack in up to 21 game-hours: it
  fills at the pace of its plan, and the model, taught to hoard as the game's machines do, puts its first pack at
  hour 36. Three rules join the design: a stock for fluids, room for every carrier of a ring, and need first.
- **The long runs of that day (48 and 60 game-hours) split the two proofs.** The cheap one holds as far as it went:
  chemical science, items and fluids through a stock, made packs **at the planned rate for the last two of 48
  hours**, after 40 hours of spurts while one fluid hoard filled. The built one is open, neither way: the robot-built
  logistic factory made **no pack in 60 game-hours**, as built or with requests by need. Nothing in those runs
  points at the layout or the robots (1,740 of 1,800 idle). The pack waits for one loop of about 25 recipes, through
  tin, fish and circuits, to go round once from empty at a craft in hours; and the check itself stopped the starter
  feed at hour 48 and deleted tin plate the circuit was waiting for. **A factory like this cannot be judged, or
  started, from empty.** Still not shown: a built factory making packs, and packs at the planned rate for more than
  two hours.
- **The third night (2026-11-15): the built factory holds the planned rate, warm, with the check standing in for
  three rules.** Started warm (every machine with two crafts of its inputs and full fluid boxes), the robot-built
  logistic factory made **96 packs in 16 hours where 97 are due, 51 in the measured eight where 48.5 are due**,
  nothing handed to it, 324 of its 336 recipes at 90% of their rate or more. As built it had made 4 packs in ten
  hours; with all but the last fault of the check repaired, 68% (85% with loop starters fed). What changed is two rules for a maker's exits (10 and 11 below), and seven faults of the check itself: half
  of what had looked like deadlocks on the first two nights was the check deleting, misrouting or withholding
  things. **No deadlock was found in a warm factory; every shortfall traced ended in one named, repairable
  cause.** Not shown: the planner's own blueprint with the two rules holding that rate (its first try stood within
  three hours on roboport reach; repaired, stitched again as `solves/n9_built`, not run), need first and a fluid
  stock as something built, and a start.

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
input box holds four crafts (37,715 seen in the 48-hour run, not the 20,000 first written here), eight hours of
all that is made; the pipe gives to whoever is first.

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
had made 28 in 14 hours. But the 14-hour run with it made **no pack at all**: 345 of 481 recipes at their rate
(419 before), 91 machines that never crafted (none before). Taking fluid back out of bigger users' boxes once a
second is not the model's smallest-first sharing, and it wrecked the start; why is not traced. So rule 7 below
stands in game at this: packs every hour at 41 to 49% of the plan with a stock that misses some fluids, and no
working emulation of a complete one yet.

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

The first check found the factory standing still, and not for a reason of plan or transport: turning a block during
the stitch had dropped its requester chests' requests (260 of 380 asked for nothing). Fixed and restitched. On real
robots, with meters at the plan's own rates:

| Built logistic factory, from empty | Game hours | Science crafts | Recipes at their rate | Recipes under a tenth | Machines that never crafted |
|---|---:|---:|---:|---:|---:|
| requests missing in turned blocks | 8 | 0 | 64 of 336 | 113 | 227 of 337 |
| fixed | 12 | 0 | 180 | 32 | 65 |
| fixed, meters 3 times faster for the first 16 hours | 21 | 0 | 231 | 17 | 17 |
| the same, fluids through the emulated stock | 21 | 0 | 242 | 17 | 17 |
| fixed, requests by need, meters 3 times faster for the first 10 hours | 15 | 0 | 220 | 37 | 41 |
| the same, fluids through the emulated stock | 15 | 0 | 218 | 31 | 41 |

**No pack yet, and the model now says why a day is not enough**: with every machine hoarding two crafts' worth this
plan makes its first pack at hour 36 from empty. In the 21-hour runs the science machine holds all but two of its
ingredients, and both wait on one chain: tin concentrate to molten tin to an equipment chassis, eight machines
planned at a craft every 20 minutes to three hours each, then lab instruments and plasmids. All eight worked in the
last three hours, at half to all of their rate. The factory is filling at the pace of its plan. The model had put
the first pack of the fast-start runs at hour 13 and, with requests by need, at hour 9: the game did not keep to
that, so the model's start times are too early by a factor these runs do not give, and they are too short to tell
the start-up rules apart. (The fluid emulation in these runs was the first one, which catches only fluids that
linger in output boxes.)

### The long runs: 48 and 60 game-hours

Read the same afternoon; hour by hour in `docs/NIGHT_LOG_2026-11-14.md`.

**Chemical science, emulated robots and a stock for items and fluids, 48 hours** (`solves/n2_chem_long`): 71 packs,
the last six hours 2, 3, 5, 4, 6 and 6 where 6.1 are due. Over the measured 12 hours 445 of 481 recipes ran at 90%
of their rate or more and none had never crafted. The 40 hours of spurts before that were one thing: the fusion
reactor filling its box of pressured water (four crafts, 37,715) ahead of a heat exchanger that about thirty recipes
hang on. At hour 41 the box was full and the chains ran at 90 to 100% from hour 43. Two hours at plan are not a
steady state shown, but it is what the model said: hoarding costs the start, not the rate.

**The built logistic factory, real robots, 60 hours**, as built (`solves/n2_logi_long_a`) and with requests by need
and a fluid stock (`solves/n2_logi_long_b`):

| | As built | Requests by need |
|---|---:|---:|
| science packs | 0 | 0 |
| recipes at 90% of their rate or more, hours 37 to 48 (of 336) | 261 | 276 |
| the same, hours 52 to 60 | 225 | 238 |
| never crafted | 17 | 5 |
| first reduced tin | hour 58 | hour 41 |
| first tin plate | none | hour 47 |

- Everything that never crafted waits for tin. Tin needs acetone, acetone comes from animals fed on fish, fish are
  bred with small lamps, lamps take a circuit, the circuit takes tin plate: about 25 recipes round, half of them
  planned under a craft an hour, each stocking two crafts of every input first. From empty the loop did not close in
  60 hours. With requests by need it came within one solder of the first circuit.
- The check stops handing out loop starters when measuring begins. 26 to 33 recipes that had run at their rate
  stood within three hours of hour 48, for want of sulfuric acid, chromium, fish, bones and the like.
- The check's sink for surplus (`--void-full`) deleted 85 of the 126 tin plates made in the second run while their
  four users held none. And nine of fourteen reduced tin are not accounted for in its report.
- The model had put the first pack at hour 36: too early by more than two times.

What follows: start-up time goes with the hoards counted in crafts over the craft rate, so it grows as the target
rate falls, and game hours are hours at the player's desk. A start from empty tells little about such a factory and
is not how one would be started. The built factory's steady rate has not been seen.

Also seen: the blocks of a robot-built factory are 197 separate power networks (no belt joins them); the stitch
leaves that wiring to the player.

## The third night: the built factory, warm

Details in [NIGHT_LOG_2026-11-15.md](NIGHT_LOG_2026-11-15.md). The plan was: repair the check's three known faults,
start the built logistic factory warm, and decide on its rate. About 90% of the plan: go on. Decay with starters fed
and nothing deleted: one trace, then stop.

| Built logistic factory, warm start, real robots | Game hours | Packs in each four hours (24 due) | Measured half | Recipes at 90% of rate (of 336) |
|---|---:|---|---:|---:|
| as built (stopped) | 10 | 4 in ten hours | | |
| overflow exits last (rule 10), starters all through; check as it was | 24 | 11, 11, 4, 17, 7, 9 | 45% | 291 |
| the same, starters for 12 hours | 24 | 11, 11, 4, 10, 1, 2 | 18% | 240 |
| check repaired (faults 4 to 6), rule 10 | 16 | 20, 15, 19, 14 | 68% | 312 |
| rules 10 and 11 | 16 | 18, 17, 19, 14 | 68% | 319 |
| rules 10 and 11, starters all through | 16 | 18, 13, 23, 18 | 85% | 320 |
| **rules 10 and 11, requests repaired (fault 7), no starters** | 16 | 20, 25, 27, 24 | **105%** | 324 |
| stitched afresh with rules 10 and 11 and chest limits in the blueprint (stopped) | 3.2 | 6, then none | | |

Two rules for a maker's exits join the design. Both are logistic conditions on an inserter, both are in the planner:

- **10. An overflow exit takes only once the stock is filled, and only where the item has another way out.** A maker
  built for robots has one inserter toward its provider chest and one toward its block's overflow chest, and they
  took turns from the first item: 196 such exits, 775 belt tiles before the chests, room for 6,200 items that the
  slow makers filled first (`blocks.overflow_after_stacks`).
- **11. A provider exit stops at the stock where the maker also feeds a user itself.** Otherwise the two share half
  and half and the neighbour starves beside a filling chest (`blocks.provider_until_stacks`). The provider chest's
  slot limit does the same once the chest is full; the built factory had lost it (below).

What the check had been doing wrong, each found by tracing one starved recipe to its root:

| | Fault | What it cost |
|---|---|---|
| 1 | starters handed out only in the warm-up | recipes stopping within hours of measuring |
| 2 | the sink for full machines deleted output still on its way to a user | 85 of 126 tin plates |
| 3 | no account of where items lie | nine of fourteen reduced tin unaccounted |
| 4 | fluid sharing gave hot steam to users of cool steam | 164,000 steam burned one for one that should have been cooled into 1.8 times as much |
| 5 | a warm start left big fluid boxes empty | a wax box of 1,000 on a craft in three hours held molds, casting and five chains behind it for eight hours |
| 6 | a test copy's fluid overflow deletes any fluid at another temperature than its filter's | half of all boric acid, in every run of all three nights: the science pack's own chain capped at 55% |
| 7 | requests by need counted what machines held against a fast link's two minutes | the coke gasifier at 90% with 4,800 coke in store; a tenth too little ammonia, a fifth too few packs |

Faults 6 and 7 are the planner's too: a sink at the fluid's default temperature (repaired: `MADE_TEMPERATURES`), and
rule 9 as it would have been built.

And three of the stitch, found on the way to a blueprint that carries rules 10 and 11 itself:

- **A block layout saved and loaded again lost its provider chests' slot limit** (`blocks.bot_provider_slots`), so
  the built factory's 239 provider chests took everything (4,800 coke in one). With the limit in place a level of
  two stacks for rule 10 could never have been passed. The limit is saved, put back where it is missing, and the
  levels of rules 10 and 11 stay half a stack under it.
- **Roboports covered the chests but not the inserters with a logistic condition**: 17 of 224 stood outside the
  network, where the condition never holds. They are covered now.
- **Turning a block left a local fluid sink's valve where it was**; some stitches then came out valid and could not
  be written, without a word. The valve turns, and a failed write is reported.

What the night does not show: a start from empty (nothing was started cold), the rules as the planner writes them
into a blueprint (every rate above is from the check's stand-in on the factory built before them; the one factory stitched with them stood within three hours, and the repaired one, `solves/n9_built`, has not run), and anything
past 16 to 24 hours.

### The day after: the planner's own blueprint

The run that was missing, and what stood in its way (details in the night log, "The day after").

| Stitched with rules 10 and 11 in the blueprint, warm start, no stand-in for them | Size | Packs in each four hours (24 due) | Measured half | Recipes at 90% of rate (of 336) |
|---|---|---|---:|---:|
| first stitch (`n9_built`): six overflow exits behind long undergrounds not found | 544 x 536 | 22 in eleven hours | a third | |
| that repaired, and by my own doing a factory twice the area (`n10_built`) | 798 x 779 | 20, 27, 17, 9 | 54% | 309 |
| **stitched at the first size again, with rule 10b (`n13_built`)** | 544 x 525 | 22, 23, 27, 20 | **97%** | 322 |
| the same, every chest asking two robot trips' worth (`n13_trip`) | 544 x 525 | 16, 20, 15, 0 | 31% | 308 |
| the big one, every chest asking two robot trips' worth (`n10_trip`) | 798 x 779 | 15, 19, 31, 25 | 115% | 303 |

- **The planner's blueprint holds the plan**: 47 packs of 48.5 in the measured half, 92 of 97 over 16 hours, with the
  check standing in for rule 9 and the fluid stock only. One run.
- **Size is robot time.** A requester chest that asks a craft's worth gets one craft per robot trip. At 623 tiles
  from its provider (260 in the smaller factory) the coke quencher made 24 crafts an hour of 31 from the first hour;
  nine hours of stock hid it from the pack, then the pack halved. In a warm run the signal is a maker under plan in
  hour 1, never the pack.
- **10b. An overflow exit for an item no provider holds waits for a craft's worth in the machine its maker hands it
  to** (a circuit condition and a red wire; in the planner). It covers 11 exits. Thirty more are open with nothing to
  guard them, and those whose user is fed over a belt of the block cost: brains are made 2% over their need, a
  fifth went out or was deleted, and animal samples made 83% in the measured half (the last four hours of the run:
  20 packs of 24).
- **Rule 9 needs the robot's trip in it**, as the link's planned flow times the trip. The check's try at it
  (`--request-trip`) takes the flow from the built request, which is the same least amount on 326 of 380 chests: it
  cured the coke link in the big factory and starved the smallest users of shared makers in the small one.

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

### After the second night

- Step 1 is in the planner (`planner.margin`; the block split keeps the surplus as by-products). Step 5 exists
  between blocks: a factory of two-machine blocks with every item link by robots lays out and stitches.
- **New, 7: fluid links need a stock**, and how to build one is open. A tank on a pipe is a stock only if machines
  can still draw from a pipe that is nearly empty, and a big-batch user must not fill up first: either the small
  users get the fluid first (the big user fed through a pump that runs only while the line's tank is above a level),
  or fluids travel in barrels by robot like the items. The first task there is a small in-game test of what a tank
  on a shared pipe does.
- **New, 8: a ring's storage holds all its carriers**, or their count is fixed when the ring is primed.
- **New, 9: need first.** One requester chest per machine with its requests set from the machine's missing
  ingredients over a wire (2.0 machines can send them) would be need first by construction, and one chest instead of
  one per item link.
- **Power**: a robot-built factory's blocks are separate power networks (197 here); the stitch should join them.
- **Checks**: a factory of this kind needs two days of game time from empty before its rate means anything, and
  the built logistic one more than 60 hours. The model, with `tank=2`, gives a lower bound; twelve hours say nothing.
- The two proofs after the long runs: the cheap one reached the planned rate (the last two of 48 hours,
  `solves/n2_chem_long`). The built one made no pack in 60 hours and is not decided
  (`solves/n2_logi_long_a`, `n2_logi_long_b`).
- **Rule 4, sized anew: a starter stock lasts until its loop has gone round once**, not an hour of use. For the
  built factory that is more than 60 hours of the starters of a 25-recipe loop; or every machine is handed its first
  two crafts when the factory is built.
- **The check, before the next long run**: feed starters until each loop makes its own and report when; let
  `--void-full` leave what a user still lacks; find the missing reduced tin.
- **The next proof is a warm start of the built factory**: every machine with two crafts of its inputs
  (`--prime-all`), starters fed through, 12 to 24 hours. It shows the steady rate without the start, which is the
  half no run has shown. Not started.

### After the third night

- **Rules 10 and 11** are in the planner and tested; the first is on by default, the second off until a factory
  built with it has run. Roboports now reach every inserter that carries such a condition.
- **Rule 9, need first, with stock for the fast**: a requester chest asks for what its machines lack; one whose link
  moves an item every few seconds keeps two minutes of it in the chest and on its belt. Still a stand-in of the
  check: it needs a wire from the machines to the chest.
- **Rule 2, meters, as a budget**: a machine that could not craft keeps its crafts and catches up (the check: a
  day's worth). A bucket of a minute throws away every wait. And **no start-up allowance in a warm factory**: it let
  small recipes take 8 to 88 times their plan from shared makers in the first hours.
- **First fills are part of the start**: fluid boxes (up to 1,000 on a trickle recipe), the belt and input of a user
  beside a maker, the first machine on a shared requester belt. A warm start of two crafts does not cover them; a
  slow item pays for each once, in hours. Either they are handed over with the starters or trickle recipes with big
  boxes are kept out of the plan.
- **Nothing shares a shortage out.** A maker a tenth short leaves its smallest or last user with all of the
  shortfall, and three levels down that was a fifth of the packs. The margin of rule 1 is what stands against it; a
  check that reads 90% on a maker should be read as a fault somewhere, not as nearly there.
- **The check has to be audited like the layout.** Four of its seven faults deleted or misdirected goods and read as
  deadlocks. Before the next verdict on a factory: where does every item made go (`where`, `sink_temperatures`,
  what the sink deleted, what starters handed out).
- **Shown the day after**: the factory stitched with rules 10, 10b and 11, the slot limits and the roboport repair
  (`solves/n13_built`), warm for 16 hours with no stand-in for those rules: 97% in the measured half.
- **Next, none of it started**: the exits of rule 10b whose user is fed over a belt (brains); the trip in rule 9 from
  planned flows; then need first and a fluid stock built instead of stood in for; then the start, with first fills
  handed over. A start from empty stays the wrong test.

## The fourth night: belts with by-products

Details in [NIGHT_LOG_2026-11-16.md](NIGHT_LOG_2026-11-16.md). The question: can the belt-built logistic factory
(325 machines, 319 x 342, laid out 2026-11-09) pass the warm 16-hour test, with overflow control at the producer and
circuit logic, and how much of the check's help does it need?

| Belt-built logistic factory, warm start, the check's help | Packs by hour 8 (48.5 due) | Measured half |
|---|---:|---|
| none (the layout's own overflow ports) | 4 by hour 3.6 | stopped |
| flow rules: pull, quotas, gates, loop carriers | 12 by hour 6.5 | stopped |
| **flow rules and an overflow at every producer** | 47 | **82%**, with a third of the slaughterhouse's animals handed in |
| flow rules without quotas, or without pull, with the overflow | 6 and 4 by hour 3 | stopped |
| overflow at the producer alone, as a circuit can decide it | 3 by hour 3.9 | stopped |
| flow rules, the overflow from real parts (90 inserters, 156 deciders) | 49 | 103% by packs, from a rubber chain at twice its plan that loses half to a second belt (night log); 259 of 313 recipes at their rate in the last four hours, nearly half of the animals handed in |
| robot-built, for comparison (third night) | | 97% |

- **12. Relief at the machine.** A product at the game's own limit in a machine's output (two to five crafts' worth
  by the recipe's speed, or the next craft not fitting its stack) is taken out there while another product of that
  machine is still wanted. Decided by decider combinators on a wire from the machine, built in the game from real
  parts, and what keeps a multi-product machine from standing. It has to take from the machine: a gate on a port
  that other makers share moves their goods too.
- **Relief is not demand.** On its own it lets every machine run for its neighbour's appetite (1.5 million steam let
  out in four hours, 3 packs). Which machine is wanted is pull; who gets a shared belt's goods is quotas. Belts need
  both from outside; a robot network has both in it (requests and one stock).
- **Pull can be built** (a demand decider per machine and ingredient, an enable condition per maker: 1,092 deciders
  here, all with room). Its one run stood still for a reason of the check; not decided.
- **Quotas have no part**, and rate caps did not stand in for them (24 packs and a halt, at 2, 3 and 5 times plan).
- **13. No splitting by turns.** A maker with two belts gives each half whatever each needs: the lab instruments,
  planned 95% of the rubber, got half of it for 13 hours while 353 lay on the other belt. The stitch calls this
  harmless until belts back up; for a slow item that is most of a day. An inserter toward a belt should work only
  while that belt holds little (a wire to the belt). Not written; the check's `--belt-pull`, which looks at the user
  instead, did not stop it.
- **No belt run is steady.** By packs the measured halves read 82 to 105%; by recipes at their rate the ones not fed starters
  to the end run down, about 305 to 246 to 259 of 313 over the 16 hours, with a third to a half of the slaughterhouse's animals handed in
  by the check. The last hours' packs come out of stock.
- **Verdict on belts:** possible, not close: a factory that runs down under four helps (one of them now a real
  part) against one that held 97% with one. Go on with robots for the links; take rules 12 and 13 into the planner
  for whatever carries the items.

