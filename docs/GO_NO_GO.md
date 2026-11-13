# Can a Py factory hold itself? The overnight test

2026-11-13, the night after [WHY_NO_SCIENCE.md](WHY_NO_SCIENCE.md). The question put to the model: is there a plan and
a set of rules under which a Py factory keeps making its science when every rate is a few percent off and after an
outage? If none exists, no layout work can help and big Py should stop. If one exists, what does it cost and what has
to be built?

VERDICT_PLACEHOLDER

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
| utility | 2,524 | 10 | 0 | UTIL2 | UTIL10 |

The plain plan under the same test (meters at its own rates, smallest first, the same stock): none of 6 runs for
production science.

### What the margin costs

Machines needed, and all crafts against the plain plan's, for three tolerances:

| Science | Plain plan | 0.5% | 1% | 2% |
|---|---:|---:|---:|---:|
| Py science 1 | 56 | 51 (1.05) | 51 (1.12) | 51 (1.28) |
| logistic | 325 | 308 (0.91) | 308 (1.24) | 309 (2.13) |
| Py science 2 | 406 | 386 (0.71) | 387 (0.78) | 387 (0.98) |
| chemical | 512 | 501 (0.86) | 501 (0.94) | 501 (1.14) |
| Py science 3 | 681 | 662 (1.06) | 668 (1.15) | 670 (1.36) |
| production | 869 | 857 (1.09) | 864 (1.24) | 882 (1.62) |
| Py science 4 | 1,102 | 1,067 (0.88) | 1,069 (0.98) | 1,078 (1.25) |
| utility | 2,524 | 2,602 (1.10) | 2,771 (1.28) | 3,252 (1.72) |

(Under 1.00: the second program also drops what the planner's whole-machine rounding had left in.) The margin
compounds round the loops: 2% costs production science 62% more crafts and raw input, 3% more than double, and 5%
four times, with control units left without any margin. So the tolerance has to be small, and the meters as exact as the plan
assumes. A clock on a circuit wire and an enable condition on each machine is exact to the tick, and Py's machines
take wires (606 of 626 machine types and all 18 furnaces have a circuit connector).

### With whole crafts

The model above lets a machine make a hundredth of a craft. With whole crafts only, a bucket of crafts as the meter
and buffers of ten crafts:

- **The steady state holds.** Production science and Py science 3 run at 100 to 102% for a day and more, also after
  priming stops; chemical for the 35 hours it was followed. With exact meters a margin of 0.5% is enough (production:
  100% in 3 of 3, at 1.1 times the raw input). The plain plan with exact meters gives 68 to 82% and falls; with meters
  1% off, about a tenth.
- **Deliveries that arrive late need the starter stock.** With everything made arriving two minutes later,
  production science runs at 100% while primed and dies within two hours of priming ending when its pools hold a
  minute's flow; with two hours' stock of each loop's starter items it carries on (followed for 8 hours).
- **Errors past the tolerance are not forgiven**: a 1% margin with rates 2% off passes 1 run in 3.
- **Starting is slow, and that is Py, not the meters.** At 0.02 science a second many machines craft once in
  hours (ground samples in Py science 4: once in 28 hours). From empty, primed: production science and Py science 3
  make science within 2 to 4 hours, chemical after 29, Py science 2 after 83, Py science 4 and utility not within 4
  days. Our 90-minute checks from empty could never have shown these working.

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

IN_GAME_PLACEHOLDER
