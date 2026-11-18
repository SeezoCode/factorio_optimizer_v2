# Factory flow (mod)

A Factorio 2.1 mod that keeps a factory laid out by the optimizer near its plan. Deep factories (Py's above all) start
badly without it: early machines take everything, loops starve, and by-products pass their users on the way out. The
in-game check showed the same rules work as `verify.py --flow`. This mod is those rules for your own game.

Once a second, for the machines of a tagged blueprint:

- **Pull**: a machine runs while a machine it feeds (along its output belts, or by hand) waits for one of its products,
  or while it makes a target. Every machine, switched off or not, keeps three crafts' worth of each ingredient
  (*stock*, a setting): its makers run until it does (production science in the check: 44,536 crafts in ten minutes
  with three, 36,945 with one, 47,382 with six). Without that a machine switched off waited for nothing, so a long
  chain stayed off level by level behind the machine above it, and when demand came every level started from empty
  (Py production science in the in-game check: 528 crafts a minute and ten levels of uranium enrichment that never ran,
  against 3,386 a minute with stock). Machines whose outputs could not be traced always run. What a machine's fuel burns to
  (ash) counts as one of its products. A machine waits for what its input slots lack (a Py farm's animals in its
  module slots are not counted: a reproductive complex running on xeno never asked for the xeno its recipe takes).
- **Quotas**: a machine that has taken more of an item than its plan allows so far (planned intake × 1.5, plus two
  crafts) has the inserters bringing that item stopped while a machine still behind its own plan waits for it, anywhere
  in the factory. For a fluid, the machine itself stops (what sits in its fluid box counts up to two crafts' worth: a
  barreler that filled its box while it had no barrels is not over its share). A machine nobody behind plan waits
  behind takes what it likes.
  An inserter taking from a belt its own machine drops onto (a catalyst's own return loop) is never stopped: its
  returns could not come back.
- **Gates**: on each overflow port's belt, the belt just past the last machine taking from it stops while a machine on
  that belt waits, or while no machine putting onto it stands with a full output. Items queue in front of their users,
  and the overflow only takes what would otherwise stop a maker. When the belt past the last user is an underground or
  a splitter, the gate goes on the first plain belt on toward the port, and never in front of a splitter whose other
  side feeds more users. (A belt cannot be stopped by script, so the mod puts
  a hidden, empty constant combinator under each gate belt, wires it to the belt, and closes the gate by turning on
  the belt's enable condition `signal-A > 0`, which never holds there.)
- **By-product chests** (`blocks.local_sinks`): the inserter into each block's by-product chest works only while a
  machine putting onto its belt stands full and no machine there waits for the item. Like the gates, only a maker
  that makes something besides that item counts (another product, or its fuel's ash): one making nothing else just
  has no more users and should stop, not pour into the chest (the in-game check lost 25/s of items the factory uses
  that way).
- **Requester demand** (a setting, on by default): a robot link's requester chest holding less than its request
  counts as a machine waiting for the item, so its makers keep it stocked. Robots take minutes to cross a large
  factory; a maker that ran only once the machines past the requester stood short left them waiting a trip each time
  (production science in the check: 36,945 crafts in ten minutes with it, 30,102 without).
- **Robot links** (`blocks.bot_links`): the belt into a tagged provider chest and the belts out of requester chests of
  the same item count as one, as robots carry between them.
- **Push**: a machine standing with a full output (or a full burnt result slot: its ash) gets the users of what is
  stuck in it going, even if nothing they make is waited for (a ring stops for good otherwise).
- **Loop first** (a setting, on by default): a production loop feeds itself first. A user of an item whose own products
  come back to a maker of that item within three recipes is in the item's loop (the tags say which); any other user
  has its inserters for that item stopped while one in the loop stands short of it and took less than its plan in
  the last half hour. Py's breeding loops are planned with no margin (a scrondrix gives 8.3 meat by way of its bones
  and takes 7.5 to breed; the 11% over is exactly what the other users take), so whatever is lost is never grown back
  otherwise. The overflow belt of a loop's own item, or of a carrier, stays closed.
- **Reserve** (a setting, 4 tiles by default; 0: off): a standing queue at each of a loop's own users. A belt serves
  a user only as items pass it, or once the queue from the belt's end stands at its inserter; pull and the caps keep
  the belts lean, so what passes a user that is not asking at that moment parks at the far end, and a loop can die
  with its stock in sight (Py science 1's seaweed loop: its fertilizer maker 2 seaweed short, 146 lying past it on
  the loop's own belt). Per loop item and belt, the first plain belt tile past the last of the loop's users is
  stopped until that many tiles in front of it are full, and opens for whatever fills them. The mod stops the tile
  by the same hidden combinator it uses for the overflow gates. It is what carried Py science 1 through a day; on
  the logistic science layouts a loop that the other users had already drained left its gate shut for good, and the
  users behind it got nothing: set it to 0 to compare.
- **Shares** (a setting, on by default): where one machine puts an item onto several belts, its inserters would
  share it out by turns whatever each belt's users need (Py logistic science's water-barrel filler: two belts planned
  94% and 6% of its barrels, half going to each). The belt of a user waiting for the item comes first; among those,
  and when nobody waits, a belt ahead of its planned share is held while one behind it has room. The mod reads only
  the inserters' hands and the users' waiting.
- **Slow machines** and **cap fill** (settings, off): slow machines counted over the time three of their planned
  crafts take, and a capped machine left running for a user that is itself behind its plan. They stop slow machines
  running at ten times their plan, and in the in-game check the factory then starved (night log of 2026-11-18).
  **Loop strict** (off): users outside a loop wait whenever a loop user is short, not only while it is behind plan.
- **Furnaces without a recipe**: a furnace the blueprint names no recipe for (an older layout's barrel fillers) is
  tagged with the plan's recipes for its kind, and taken for the one it crafts or, before its first craft, the one
  that takes the fluid it holds.

A setting, *side-product cap* (default 0, off), holds a machine wanted only for a product it was not planned for to that
many times its planned rate (over the last ten minutes, plus two crafts). Its purpose comes with the tags: its main
product, or every product the plan uses when the main one leaves as a by-product. Without it a trace by-product drives a
machine as fast as it can go, and everything else it makes piles up: Py production science's coal gas plant ran at nine
times its plan for a little iron oxide, and its coke filled the robot provider it feeds.

A setting, *pull ahead* (default 1), lets makers run while a user works with less than that many crafts of an
ingredient, not only once it stands short. Another, *quota window* (minutes, default 0), makes quotas count only
what a machine took in that last stretch, so one that ran ahead early on is not held for hours. Machines the mod switches off show "Disabled by script". Each rule can be
turned off in the map settings (Mod settings → Map), as can the whole mod; changing a setting starts everything the mod
stopped again, and the rules still on take over from the next second.

## Use

1. Tag the blueprint (it carries each machine's planned intake, its place in the production loops, the target makers
   and the overflow belts):

   ```bash
   python3 tools/factory-flow/tag.py solves/<run> --job jobs/<job>.json
   ```

   This writes `solves/<run>/flow_blueprint.txt` from the run's `best_output_string.txt`, with the run's `plan.json` and
   `ports.json`.

   For a belt-built factory, add what the in-game check builds from real parts and a player needs too: an overflow
   exit at every machine that a product nobody takes could stop (a fast inserter whose filter a decider sets, a steel
   chest, one decider per product, wired to the machine), and at every fluid output Py's overflow valve with an
   exhaust pipe or a sinkhole behind it. One short run of the check writes them out, and `--built` merges them in:

   ```bash
   python3 tools/factorio-verify/verify.py solves/<run> --flow --relief-exits --real-voids --warmup 60 --measure 60 \
       --export-built solves/<run>/built_blueprint.txt
   python3 tools/factory-flow/tag.py solves/<run> --job jobs/<job>.json --built solves/<run>/built_blueprint.txt
   ```

   The exits' chests fill up and have to be emptied (or led to a burner): how fast is in the night log of 2026-11-18.
2. Pack the mod and put the zip in Factorio's mods folder (on a Mac `~/Library/Application Support/factorio/mods`):

   ```bash
   python3 tools/factory-flow/pack.py
   ```

3. Build the tagged blueprint with the mod enabled. The mod learns its machines from the build (the tags travel with the
   ghosts), so a factory built before the mod was installed, or from an untagged blueprint, is left alone.
   `/factory-flow` in the console says what it is doing.
4. For a belt-built factory set *Rate cap* to 2 and *Quota window* to 30 minutes in the map's mod settings, and
   *Stock* to 1: the settings the in-game check ran it with.

Loops (barrels, cages, Py's bricks) still need their carriers put in once by hand, as the blueprint's item requests
say, and a big ring needs many: Py logistic science's ring of animals and its water-barrel filler took about 600
barrels. When a machine has waited two minutes for a loop's carrier while no maker of it stands full, the mod says so
in the console, with the machine's place. A machine replaced by an upgrade loses its tags.

## Tested

`verify.py <run> --job <job> --flow-mod` builds the tagged test copy with this mod installed and runs it as the
in-game check does, with the check's options as the mod's settings (`--with-built FILE` for a blueprint that brings
its exits and voids). What it does and does not hold is in `docs/NIGHT_LOG_2026-11-18.md`: Py science 1 (56 machines)
makes its planned packs through a 24-hour run under the mod, on starters for the first two hours and nothing
after; the logistic science layouts (337 to 352 machines) do not hold yet.
