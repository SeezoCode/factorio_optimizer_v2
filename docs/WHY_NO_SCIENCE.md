# Why the big factories make nothing, and what to change in the algorithm

2026-11-12. Written after two weeks in which every fix to a Py factory in game ended with "no science yet". The
question: why do we keep hitting bottlenecks, and what should change in how the program plans, not in one layout.

## Short answer

**No layout could make these plans work as they are built, and the layouts have the same gap one level down.** Each
plan is one exact operating point: every machine at exactly its planned rate. Nothing we build holds a factory
there: not the rates, not who goes first on a belt, not the stock a loop needs to start again.

Shown with a small model that runs a plan as a factory with *perfect transport*: one shared pool per item that every
maker fills and every user draws from, no belts, pipes or robots at all (`tools/plan-sim/sim.py`, ten seconds a run).
Py production science (869 machines, target 0.02/s):

| Rules in the model | Science | Machines at 90% of plan or more | Machines under 10% |
|---|---:|---:|---:|
| as built: machines run whenever they can | **0%** | 151 | 685 |
| no machine faster than 3 times its plan | 0% | 153 | 648 |
| 1.5 times | 0% | 171 | 625 |
| 1.1 times | 1% | 192 | 296 |
| 1.02 times | 87% | 644 | 0 |
| **exactly its plan** | **100%** | 869 | 0 |
| its plan, each limit off by up to 2% either way | 0% | 495 | 230 |
| production by demand (pull) alone | 0% | 84 | 711 |
| its plan, then 5% of machines stand for an hour | 0% for good | | |
| the same, with a small standing stock of each loop's starter items | 100% again within the hour | 869 | 0 |

(Outlets as the in-game check has them, loops primed through the warm-up, eight hours, measured over the last two.)

So with transport that cannot fail, the plan as built makes no science, and it makes all of it once every machine is
held to its planned rate. The model's failed state looks like the game's: in the game this morning (no feeding) raw
items came in at 125% of plan, the factory made 118% of its planned crafts, 403 of 869 machines made nothing, and
science was nil.

In the game, meters on every machine (new today) cut what leaves the factory from four times the plan to the plan,
and after a running start the science chain's waits shrank from 296 machines at 21 roots to 20 machines at 2. Still
no science: what is left is priorities and reserves, below.

## Why the plan does not hold itself

Four properties of the plans, measured on production science (`tools/plan-sim/structure.py`):

1. **Machines can run far faster than planned.** A recipe planned at 0.003 crafts/s still gets a whole machine. The
   median recipe can run at 37 times its plan; 577 of 744 at ten times or more. Only 26 machines are within 10% of
   their limit, and they make the basics everything needs (glass, zinc plate, coke, raw fibre, hydrogen).
2. **Every item is balanced to the last unit.** 269 items are made only as one of several products of some machine
   and used up exactly; 118 items have several makers and 394 several users.
3. **Most of the factory is one loop.** 548 of the 744 recipes (616 machines) each depend on every other, through
   by-products that are reused, barrels, breeding and recycling.
4. **Whoever comes first on a belt takes what it wants.** Nothing shares an item out by plan.

Together: a machine that can run ahead does, because its other products leave through overflows (330 of 832 items
have one, so that a by-product never blocks its machine; 305 machines have an overflow on every product and nothing
ever stops them). It takes more than its share of an exactly balanced item. The last user in line then gets nothing
rather than a little less, and its chain stops. Because nearly everything is in one loop, the shortage comes round to
the machines that caused it. A loop whose stock is used up never starts again by itself.

The game shows each step (run without feeding, `tools/plan-sim/balance.py` and `waits.py` on its report):

- **Material**: 35.8 items/s left the factory against 8.9 planned; 28.6/s of the excess were items the factory itself
  uses (iron oxide, pu-239 at 20 times its planned make, graded ores, coke, sodium hydroxide). Uranium and chromium
  ore came in at 9 times plan, titanium ore at 8; nickel and niobium ore at under a tenth.
- **Waits**: following what each machine waits for from the science machine, 296 machines wait on 21 roots: 11
  makers of basics working flat out and still short (glass, copper plate, raw fibre), 2 loops of machines waiting
  for one another (ammonia and cyanic acid, the very loop the model stops at), 2 machines standing full of a
  by-product no one takes, 3 switched off, and so on.

## In game: meters stop the waste, and are not enough alone

`verify.py --meter F` (new): every machine is held to F times its planned rate by a token bucket of one craft.
Production science, 90 minutes' warm-up, 10 measured, nothing fed unless said:

| Run | Crafts, of plan | Recipes within 10% of plan (of 744) | Recipes over 1.5 times plan | Machines that made nothing | Raw items in, of plan | Items leaving (plan 8.9/s) | Science |
|---|---:|---:|---:|---:|---:|---:|---|
| no meters (this morning's best settings) | 118% | 22 | 166 | 403 | 125% | 35.8/s | none |
| meters at plan | 70% | 177 | 19 | 340 | 83% | 8.5/s | none |
| meters at plan, with pull | 65% | 146 | 16 | 333 | 80% | 8.6/s | none |
| meters at plan, loop starters fed where short | 79% | 201 | 12 | 341 | 89% | 8.6/s | none |
| meters at 1.1 times plan, with pull | 70% | 102 | 31 | 331 | 85% | 10.0/s | none |
| meters at plan, fed where short for the first hour only, measured 1.5 hours after | 78% | 234 | 15 | **206** | 87% | 11.2/s | none (8 crafts of 12 in the fed hour, none after) |
| for comparison: fed where short throughout, no meters (this morning) | 139% | 76 | 330 | 81 | 131% | 34.9/s | at plan |

The meters did what the model said they would: the over-running stopped, and what leaves the factory fell from four
times the plan to the plan. They did not bring science: about 340 machines still made nothing. What the science
chain waits on then (`waits.py`), which the one-pool model cannot show:

- **A by-product with nowhere to go.** 186 machines wait behind one pulp mill (ag-biomass-extraction: lead dust
  and biomass) that stands full 87% of the time. Its biomass belt has one taker nearby, a bakelite machine holding 96
  biomass and waiting for phenol, and no overflow (the block's biomass overflows are on another biomass belt).
  Phenol waits, through filtration media and silver, for the mill's lead dust: a circle of ten machines, a root in
  every run traced today. Of the 14 machines with several products that usually stood full under meters, 11 drop
  onto a belt with no way out at all.
- **Makers at plan, users still short.** Iron plate was made at its planned 1.30/s and only 0.54/s was used: the
  rest was still filling belts and chests after 90 minutes, and 9 of its 14 users stood for want of something else.
  A factory metered at exactly its plan has nothing spare to fill its own belts with or to catch up after a stall.
- **Loops that never came up**, where a second maker held to its plan can no longer cover for them (phenol: the
  cumene still at plan, the coal tar route dead behind the silver loop).
- **After a running start the chain is nearly whole.** Fed where short for an hour and then left alone under meters,
  the factory needed far less help than without them (19 items fed at half their planned use or more, against 53),
  and 1.5 hours after feeding stopped the science machine's waits ended at just two roots: a breeding loop (xeno
  eggs, guts, caged xenos) dead behind a renderer standing full of mukmoux fat no one takes, and one barrel filler
  held at its plan while its users wanted more.

So there are two layers. The plan cannot hold itself (the model: no layout could save it). And the layout has the
same kind of gap one level down: where a by-product can go, and who goes first on a belt, were never decided.

## Why two weeks of fixes all read "no science"

- **Several causes each stop science alone.** Removing one leaves the others, so every run looked the same. The
  number of machines at plan is the better gauge.
- **Our limits were never tight.** `--rate-cap 3` allows three times plan plus three crafts per ten minutes; quotas
  1.1 times plus two crafts. For a machine planned at a craft every five minutes that is two to four times its plan,
  and most Py machines are that slow. The model says anything past about 1.02 fails.
- **Pull does not help by itself.** In one loop every machine keeps another wanted: under pull with a limit of 1.2,
  713 of 869 machines ran at the limit. Loops spin (uranium enrichment and nexelit grading at 20 to 30 times plan in
  game).
- **It is not specific to this factory.** Every Py science plan but the 13-machine one makes nothing as built in the
  model, and all but one make 100% when held to plan (table below).
- **Small factories work by luck of position.** Py science 1 (56 machines) in the model: of 12 random orders of who
  draws first, one made 100%, one 16%, ten nothing. In game it passes, after a night of nursing. With hundreds of
  shared items no order is lucky enough, which is the size wall we met at about 400 machines.

## What to change in the algorithm

What we build today encodes connections and capacities: every link exists and can carry its planned flow. A plan also
assumes three things that nothing we build carries: **rates** (each machine at its planned speed), **priorities**
(who takes, and who supplies, first) and **reserves** (stock that lets a loop restart and a belt fill). Each fix of
the last two weeks patched one case of one of them, in the check's rules. They belong in the plan and the layout.

1. **Test whether a design settles, before laying it out and after.** The validator proves a static thing. Whether
   the factory arrives at its flows is found out by a half-hour game run after hours of layout. Port the model into
   the planner as a test every plan and every control idea must pass: reaches its target from a cold start, still does
   with every rate 5% off, and comes back after an outage. Then a second level on the routed layout: a pool per belt
   or pipe network, makers and users in their order along it, links between blocks as flows. That one would name
   the pulp mill above in seconds. *Evidence: the model reproduces production science's failed state in game (crafts and raw
   input over plan, most machines dead, no science) and stops at the same ammonia loop.*
2. **Rates: meter every machine, and plan with a margin.** Meters first, because they are cheap and remove the
   waste: in the mod and the check a token bucket per machine (done in the check today); in a plain blueprint one clock
   per block on a circuit wire and an enable condition on each machine (2.x machines take one; the crackers already
   use it) or on its input inserter, so it runs its planned share of the time. Meters on only the machines that look
   at fault are not enough (in the model, metering the 285 to 350 worst left science at nil), and links between
   blocks need them too (a requester chest asks for ten minutes' worth and pours it out as fast as it can).
   Then the margin, because exact meters do not exist and an exactly metered factory cannot fill its belts or catch
   up: the rate program should make every item at, say, 1.05 times what is used at the metered rates, the surplus
   leaving through the overflows, and size the 26 tight machines to match. Then a meter a few percent off takes from
   surplus, not from a neighbour. It costs raw material, compounding with depth. *Evidence: model, exact meters 100%,
   2% off 0%; a plan scaled up evenly works at 110%. Not shown: a plan with true margins; my quick try outside the
   planner did not converge, it needs the real rate program.*
3. **Priorities: decide who goes first on every belt, and where every by-product can go, in the planner; have the
   router build it and the validator check it.** Every belt that carries a by-product ends in its own way out
   (the pulp mill's block has its biomass overflows on another biomass belt), and that overflow is last in line. Among users, a loop's
   own return comes first. Among suppliers, machines that make the item as a by-product come first; imports, robot
   links and makers dedicated to the item only top up, putting onto the belt only while it holds less than a set
   amount (the rule catalysts already get: an inserter that works only while the return belt is empty). Position
   along the belt does all of it without circuits where the router is free to order the chain. *Evidence: the pulp
   mill; in the model, Py science 1 works for 1 order of who draws first in 12, and with shortfalls shared out evenly
   rates 5% off still give 81 to 88% instead of nothing. The supplier order is the least tested part.*
4. **Reserves: every loop keeps what it needs to restart.** The planner already names the items that start each
   loop (83 here). The factory should hold a small standing stock of each and let outside users take only what is
   above it (the way a uranium enrichment loop keeps its 40), not only be primed once. *Evidence: model, an hour's
   outage of 5% of machines is fatal without it and an hour's dip with it.*
5. **Prefer plans that hang together less.** The rate program minimises machines and raw input, so it reuses every
   by-product and closes every loop: 269 knife-edge items, one loop of 548 recipes. Give sharing a price (per item
   with several makers or users, per recipe that closes a loop), let a raw-fed chain replace a reused scrap where it
   is cheap, void surplus on the spot (Py's void recipes are skipped today), and judge candidates with the model.
   Fewer shared items is also fewer links between blocks, which is what the stitch struggles with. *Evidence:
   indirect only (every root above is a shared item or a loop). Not tried.*

In the order I would do them: 1 (it makes every later step cheap to judge), then 2's meters with 4 in the mod and
the check, then 3 in the router, then 2's margins and 5 in the rate program.

## Smaller things found on the way

- The check and the mod's tagger took each machine's planned rate from `plan.json`, the plan before the blocks were
  rebalanced. Where a block got more machines of a recipe, each runs slower than `plan.json` says (copper plate
  0.30 crafts/s a machine against 0.37, aluminium plate half), so quotas and caps were that much looser again for
  the basic plates. Both now read `plan_rebalanced.json` when the run wrote one.
- Py science 3's plan does not make its target in the model even held to plan with a fixed order of who draws first
  (71% with shortfalls shared out). Not traced.
- The model has no fuel slots, no batches and no latency: it is kinder than the game in those and has none of the
  check's flow control, so it is harsher on the two mid-size factories that pass under it.

## Every plan, in the model and in game

| Science (0.02/s) | Machines | Recipes in the biggest loop | Knife-edge items | Model, as built | Model, held to plan | In game so far |
|---|---:|---:|---:|---:|---:|---|
| automation | 13 | none | none | 100% | 100% | passes |
| Py science 1 | 56 | 9 of 56 | 24 | 0% (1 order in 12: 100%) | 100% | passes |
| logistic | 325 | 213 of 315 | 118 | 0% | 100% | passes under flow control, 4 h warm-up |
| Py science 2 | 406 | 266 of 378 | 134 | 0% | 100% | about 30% |
| chemical | 512 | 295 of 481 | 177 | 0% | 100% | 17% |
| Py science 3 | 681 | 484 of 622 | 227 | 0% | 0% (71% with items shared fairly; not traced) | a few crafts |
| production | 869 | 548 of 744 | 269 | 0% | 100% | none |
| Py science 4 | 1,102 | 810 of 931 | 296 | 0% | 100% | none |
| utility | 2,524 | 1,123 of 1,270 | 435 | 0% | 100% | not run |

A knife-edge item is made only as one of several products of its makers and used up exactly. "As built": outlets as
the in-game check has them, the target taken at its planned rate. The model is harsher than the game on the two
factories that pass with flow control, which the check's rules (pull, quotas, gates) carry; it has none of them.

