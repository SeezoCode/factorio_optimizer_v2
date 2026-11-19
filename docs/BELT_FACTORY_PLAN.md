# The belt factory: what is known and what comes next

Written 2026-11-18, after the nights of 2026-11-16 to 2026-11-18. The goal: a belt-built Py factory that runs in the
game on help a player could give, its flow rules carried by a Lua mod. The evidence is in
[NIGHT_LOG_2026-11-16.md](NIGHT_LOG_2026-11-16.md), [NIGHT_LOG_2026-11-17.md](NIGHT_LOG_2026-11-17.md) and
[NIGHT_LOG_2026-11-18.md](NIGHT_LOG_2026-11-18.md); this page is the summary and the plan.

"Starters" are the loop items the check hands in at the start; "carriers" are barrels, cages and bricks that only
go round. Hours are game hours.

## Where it stands

| Factory | Best result on fair help | |
|---|---|---|
| Py science 1 (56 machines) | 24 hours at exactly the planned rate | once under the check, once under the mod, and four shorter runs alike |
| Logistic science, margin layout (337) | 20 hours to the end at the planned rate; 14 hours under the mod | the same rules hold about one run in three: 3 of 9 repeats alive at hour 16 to 17 on 2026-11-19 |
| Logistic science, burner layout (352) | stops one to four hours after its starters | under every rule set |
| Logistic science, old layout of 2026-11-09 (325) | stops when the last barrel has been handed in | the layout has a fault of its own |
| Py science 2 (406) | stops after its starters | not traced |

So: the small factory is solved as far as the rules go, the big one has held once, and nothing is proven by a single
run (see "Timing" below).

## What I found out

1. **Lean belts do not queue, and that is why runs stop.** A belt serves a user only as items pass it, or once the
   queue from the belt's end stands at its inserter. These layouts have almost no splitters: a belt runs past its
   users one after the other. Pull and the caps keep belts lean, so what a user does not need at the moment it
   passes goes on and parks at the far end. Seen on four layouts: 273 water barrels, 254 filtration media, 146
   seaweed lying past the user that stood without. An ordinary maker makes another batch; a loop's maker depends on
   that user, and the loop is dead for good.
2. **The flows are trickles on express belts.** A belt network holds a few thousand items and the planned flow of
   many items is under a hundred an hour, so "let the belts fill up" takes tens of hours an item. Lean running is
   forced, and the remedy has to be a small queue where it matters, not full belts.
3. **A maker with several belts shares out by turns**, whatever the plan gave each belt (69 such cases on the old
   layout; half the water barrels went to a belt planned 6%).
4. **Slow machines run at many times their plan.** The cap and the quotas allow a few crafts over; for a machine
   planned for a craft or two an hour that is the whole allowance. Such machines take what large users were planned
   for (powdered quartz at 18 times its plan took half the glass maker's quartz).
5. **Loops are planned with no margin and the other users drain them.** The scrondrix give 11% more meat than
   breeding takes, and the plan spends exactly that. While the starters flow a loop runs rich; when they end, the
   users outside the loop take what is left.
6. **The first hours of every run are the starters, not the factory.** 19,600 to 27,800 items and 64,000 to 110,000
   of fluids in four hours on logistic science (11,500 items and about 50,000 of fluids in two). What counts is what
   a run does after they end.
7. **Timing decides when a fragile loop dies.** The same factory under the same rules stopped at hour 4, 5, 6 and
   10 in different runs, and "held 16 hours" once. A single long run proves little either way.
8. **The check itself was wrong several times, each time in the factory's favour.** Filled barrels and caged animals
   were handed in (44% of the water barrels); Py's barrel fillers were invisible to it while empty; the mod's
   settings were never applied in any mod-driven run before 2026-11-18; the overflow exit at the pack machine took
   every second pack.
9. **The old layout cannot be judged on.** One return belt puts empty barrels where they cannot get back to the
   water-barrel filler: 3% of the loop's barrels an hour, for good. The layouts of 2026-11-17 do not have that.
10. **What leaves the factory is small but has to go somewhere.** 5,500 to 6,500 items an hour on logistic science
    (one Py burner takes 12,000). The worst overflow chest is full in three hours.

## What worked

| Rule or part | What it does | Evidence |
|---|---|---|
| Share rule (`--out-share`) | where a machine feeds several belts: the belt of a waiting user first, the plan's shares when nobody waits | 89.2% of the seaweed to a belt planned 89.3% over 24 hours |
| Reserve gate (`--reserve`) | a belt gate past the last of a loop's own users, shut until a few tiles in front of it are full | Py science 1: 24 hours with it, a stop at hour 4 to 10 without, in six runs |
| Slow machines held, with the leave passed up the chain (`--cap-slow --cap-fill`) | no machine far over its plan, but a capped machine and its makers run on for a user that is behind plan | margin layout: 6, 1, 4 pack crafts after the starters without the leave reaching upstream, 8 to 13 an hour with it |
| Loop users kept full first (`--loop-stock 2`) | users outside a loop wait until the loop's own users hold two crafts' worth | margin layout: the seaweed loop empty at hour 5 without; at hour 11 in one run with it, not at all in the other two |
| Cap of twice the plan, quotas over half an hour, loop-first, shut ports for loop items and carriers | the base since 2026-11-17 | overflow from 17 to 20 items a second down to about 1 |
| Carriers as empties only, during the warm-up | no free animals or filled barrels | none lost in the runs counted |
| Fluid voids from real parts | Py's valve with an exhaust pipe or sinkhole at every fluid port | runs with them match runs without |
| Overflow exits from real parts | inserter, chest and deciders at each machine a by-product could stop | 89 to 93 of them a layout, all wired |
| The mod (`tools/factory-flow` 0.2.0) | carries every rule above | hour for hour the check's numbers on Py science 1; 14 hours on logistic science |
| The player's blueprint (`tag.py --built`) | the layout with exits, fluid voids and tags in one string | builds complete in the game, 24,780 entities |
| Py science 1 as a test bench | twelve hours in a quarter of an hour | told six rule sets apart in one night |

## What did not work

| Tried | Result |
|---|---|
| Slow machines held to their plan, alone | starves: nothing passes the users any more |
| Loop priority made strict (others wait whenever a loop user is short) | holds the biggest user off its own stock once the loop's stock is parked |
| No pull rule, every overflow port shut (back-pressure) | 5 pack crafts where pull makes 57; fluids cannot be shut and were vented by the hundred thousand |
| Every overflow port shut, with pull | dead in the second hour on Py science 1 |
| A gate past every user, for every item | cost half the packs on the margin layout; not understood |
| Starters as one delivery at the start | a stack is far too little for a breeding loop |
| The inserter-to-belt wire (hold while the belt has enough) | what rests past the last user counts as enough |
| A 2% margin in the plan, alone | did not stop the stalls |
| Burners for surplus items as planned today (`sinks.burn`) | the burners work; the plan makes things only to burn them, and those chains take the science's inputs |
| Pricing vented fluids low in the planner | 593 machines, no layout; reverted |

## What the script still does for the factory

| Help | State |
|---|---|
| Flow rules | script, or the mod |
| Machines filled at the start | script |
| Starters | script, for the first two hours; not yet a counted kit |
| Carriers | script, as empties, during the warm-up |
| Chests at the overflow exits | emptied by script: 3,700 items an hour at 38 exits in the run that held |
| Item ports (by-product belt ends) | deleted at the port: 1,480 items an hour at 24 ports |
| Raw inputs | unlimited at every input belt |

## What I want to work on, in order

**Update 2026-11-19** ([NIGHT_LOG_2026-11-19.md](NIGHT_LOG_2026-11-19.md)): step 1 is answered (the rules hold about
one run in three) and every stop was traced. They come in four kinds: fish parked past the last loop user, a loop
user switched off by pull and so not counted as waiting, a maker sharing out by plan while a loop user waits, and
fluid loops. The check has a rule for each of the last three (`--loop-keep`, `--loop-share`, `--loop-fluids`), and
`--loop-keep` alone already looks better (none of four runs lost to an item loop by hour 12). Step 3 is done for Py
science 1 (150 seaweed, 18 coarse, 200 methanol, 80 phytoplankton) and step 6 started (Py science 1 runs at plan on
inputs capped at the plan, after a slump). Next: the three new rules together, eight runs of 24 hours, with the loop
fluids narrowed. Then the parked fish, which needs the layout (step 5). Then the kit and capped inputs on logistic
science.

1. **Find out whether it holds, by repeats.** The rule set that held on the margin layout, five runs with the gates
   and five without, 24 hours each, and three under the mod. Done when every run reaches its end or every stop is
   traced. About one night. Until then "it held once" is all that can be said.
2. **The loops the gate does not reach.** On the margin layout only fish and fish eggs have a gate; the seaweed loop
   that died in the third run has none, and the burner layout's scrondrix breeders sit at their belt's end and still
   empty. Find where a queue can stand for those (or why the queue at a belt's end is not enough), and trace the
   scrondrix stop to its start, not its end state.
3. **Starters as a kit a player can hand in.** `--seed-budget` exists and made no difference on Py science 1; run it
   on logistic science and shrink it until the factory fails. Done when there is a list by item with amounts.
4. **Real voids for items.** Lead the by-product belt ends and the worst exits to a burner. This is planner work:
   exits and a trash belt as planned parts, and `sinks.burn` without chains made only to be burned. Done when a run
   holds with nothing deleted by script.
5. **Into the planner what the check now builds after the fact**: overflow exits, fluid voids and reserve gates as
   blueprint parts; a loop's own users at the head of the loop's belt; a check at layout time that no return belt
   leaves its loop (the old layout would fail it). Then lay the old factory out again.
6. **Inputs at the planned rate.** Every run so far had unlimited raw inputs, and the factory runs at up to twice its
   plan when it can. Cap the input belts at the plan and see whether the rules still hold.
7. **The other factories.** Py science 2 and the burner layout under the rule set that held, each traced once.
8. **Housekeeping in the check.** Its verdict line fails a run for one inserter holding an item and counts exhaust
   pipes as machines without a recipe; neither says anything about the factory.

Steps 1 to 3 need no planner change and decide whether the rules are finished. Steps 4 and 5 are what makes the
result a factory someone can build without the check.
