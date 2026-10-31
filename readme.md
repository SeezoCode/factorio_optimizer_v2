# Factorio layout optimizer v2

> See also: [factorio_optimizer](https://github.com/SeezoCode/factorio_optimizer), the original version, a
CP-SAT bot-mall model that places machines on a macro grid and connects them with requester and provider
chests.

Generates importable Factorio 2.0 blueprints for production blocks. A linear program picks recipes and machine
counts (by-products included), simulated annealing places the machines, a router lays belts (with splitters
and several belts per item), undergrounds, inserters and pipes between them, poles are added, an independent
validator checks the result, and an outer loop shrinks the box for as long as it has time. Inputs and outputs
are ports on the block's edge, listed in `ports.json`, so you connect blocks yourself. Every valid improvement
is written as a blueprint string. Jobs too big for one block are built as a [factory of
blocks](#big-jobs-a-factory-of-blocks).

**Space Age (experimental):** foundries, electromagnetic plants and biochambers work, with their built-in
productivity, research productivity and space-platform jobs (see [Space Age](#space-age)). Others only partly:
crushers only on space platforms, cryogenic plants without Aquilo's heating (heat pipes are not modelled), and
recyclers not at all (the planner never uses recycling recipes). Gleba layouts are the hardest to route, since
every biochamber also needs a nutrient belt, and spoilage is not modelled.

## Examples

Each folder in [docs/examples](docs/examples) has the blueprint string (`best_output_string.txt`, paste it into
Factorio 2.0 with Import string), a render, `ports.json` (where to feed it and where products leave) and the
job file next to it, and most have a screenshot of the layout built in game. All were run with 10 threads
(M5).

Compared with my most compact hand-built layouts, the generated ones take 110–120% of their area. More compute
closes only a little of that; the rest is the algorithm. Hand builds run one belt down a whole column of
machines, put two inputs on one belt, and feed everything from a bus, while the router still treats each item
as separate chains between scattered machines.

| Example | Machines | Box | Search ran | Best layout found after | J*b |
|---------|---------:|----:|-----------:|------------------------:|-----|
| [Red + green science, 0.5/s each](docs/examples/red_green_science) | 10 | 16 × 16 | 5 min | 5 s | [details](docs/examples/red_green_science.json) |
| [Red + green science, 0.5/s each, two items per belt](docs/examples/red_green_science_shared) | 10 | 15 × 15 | 5 min | 2 min 48 s | [details](docs/examples/red_green_science_shared.json) |
| [Chemical science 0.5/s from ore and crude oil](docs/examples/chemical_science) | 43 | 36 × 36 | 15 min | 10 min (36 × 36 first at 5 min 20 s) | [details](docs/examples/chemical_science.json) |
| [Six science 1/s each from plates, as a factory of 3 blocks, inputs west and products east](docs/examples/six_science_factory) | 177 | 182 × 79 | 4 h 57 min | 4 h 57 min | [details](docs/examples/six_science_factory.json) |
| [Electromagnetic science 1.5/s (Space Age, Fulgora)](docs/examples/electromagnetic_science) (at the bottom of the page) | 36 | 41 × 41 | 15 min | 13 min 35 s (41 × 41 first at 6 min 26 s) | [details](docs/examples/electromagnetic_science.json) |
| [Metallurgic science 4/s (Space Age, Vulcanus)](docs/examples/metallurgic_science) (at the bottom of the page) | 38 | 46 × 46 | 15 min | 14 min 41 s (46 × 46 first at 5 min 34 s) | [details](docs/examples/metallurgic_science.json) |
| [Agricultural science 5/s + carbon fiber 5/s (Space Age, Gleba)](docs/examples/gleba_agricultural_carbon_fiber) (at the bottom of the page) | 43 | 50 × 50 | 15 min | 15 min | [details](docs/examples/gleba_agricultural_carbon_fiber.json) |
| [Six science 1/s each from ore, as a factory of 10 blocks](docs/status/six_science_9_blocks) (at the bottom of the page) | 618 | 317 × 137 | 41 min | 30 min | [details](docs/status/six_science_9_blocks.json) |

Each render next to the same blueprint built in game. Red + green was generated with belt sharing on
(`lane_sharing`, two items per belt, one per lane), chemical science with it off (one item per belt).

![Red and green science, render and in game](docs/examples/red_green_science_shared/side_by_side.png)

*Red and green science at 0.5/s each (15 × 15): iron and copper plates enter on the west edge, copper on the
left lane of a belt it shares with an item made inside. In the render, belts are coloured per chain and brown
dots are poles.*

![Chemical science, render and in game](docs/examples/chemical_science/side_by_side.png)

The six-science factory's three blocks were searched one after another with belt sharing on, then stitched:

| Block | Machines | Box | Search ran | Best layout found after |
|-------|---------:|----:|-----------:|------------------------:|
| 1 | 60 | 57 × 57 | 1 h 40 min | 1 h 37 min |
| 2 | 57 | 57 × 57 | 1 h 35 min | 1 h 31 min |
| 3 | 60 | 54 × 54 | 1 h 40 min | 16 min |

All 19 inputs (plates, steel, bricks, stone, coal, and pipes of petroleum gas, heavy oil and water) enter on the
west edge and run along the corridor under the blocks; the six science packs leave on the east edge. Stitching
and compaction took under a minute. Repack and merge were off: in earlier runs they never found a smaller
factory, so the blocks got the whole budget.

The six-science factory; block borders are drawn as floor tiles in the blueprint:

![Six science factory](docs/examples/six_science_factory/best.png)

Built in game:

![Six science factory, in game](docs/examples/six_science_factory/in_game.jpg)

Building it showed a bug: near the utility science assemblers, a few inserters
dropping onto a belt curve put their items on the wrong lane. On a curve an inserter drops onto the far side of
the bend, the inside lane when it stands outside the curve, and the router and validator did not model that.
It is fixed now; this blueprint was generated before the fix (9 of its 206 belt drops land on a curve's other
lane).

## How to run

Java 25 (LTS) with Gradle 9.8.2 (the wrapper downloads it) and Kotlin 2.4; it runs on macOS, Linux and
Windows. Point `JAVA_HOME` at a JDK 25, for example on macOS:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
```

on Linux (Debian/Ubuntu package `openjdk-25-jdk`, or any JDK 25 build such as Temurin or Zulu), point it at
the JDK that `java` runs from:

```sh
export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
```

Rendering needs FreeType and fontconfig, which minimal Linux images leave out (Debian/Ubuntu:
`sudo apt-get install -y libfreetype6 fontconfig fonts-dejavu-core`).

On Windows use `gradlew.bat` instead of `./gradlew`.

Or open the folder in a GitHub Codespace or in VS Code with the Dev Containers extension: `.devcontainer/` uses
the Java 25 image (`JAVA_HOME` is already set), installs the font libraries and compiles everything on
creation.

Run a job (the job file is a classpath resource name or a path):

```sh
./gradlew run --args="--config=src/test/resources/jobs/t3.json"
```

Results go to `src/main/resources/solves/<time> - sa/` (or `--out=<dir>`):

| File | Contents |
|------|----------|
| `best_output_string.txt` | Blueprint string of the best layout so far |
| `best_blueprint.json` | The same blueprint as readable JSON |
| `best.png`, `renders/` | Render of the best layout, and one per improvement |
| `allSolves/` | Blueprint string of every improvement |
| `scores.tsv`, `score_vs_time.svg` | Score log and graph |
| `plan.json` | Stage 1 output: machines, terminals, nets and rates |
| `ports.json` | Where each input and output enters or leaves the block (tile, edge, item, rate) |
| `log.txt` | Search log |

Ctrl-C stops the search and rewrites the best result first. Other flags:

- `--print-tuning`: every tuning knob with the value the job uses.
- `--tuning=<file>`: a tuning file instead of the built-in defaults.
- `--calibrate=<job>,<job>,... --budget=<s>`: refits `tuning.json` from short searches.
- `--verify-kit=<dir>`: in-game check blueprints (see [docs/VERIFY.md](docs/VERIFY.md)).
- `--render=<dir>`: redraws `best.png` of an output folder from its blueprint and `ports.json`.
- `--resume=<dir>`: continues a factory-of-blocks run in that output folder. The split and every finished block
  were saved in `<dir>/resume/`, so only blocks without a layout are searched; then the factory is floorplanned,
  stitched and validated again (seconds). Useful after stopping a run, or to re-stitch finished blocks with newer
  code. It uses the job saved there unless `--config` is given.

Renders show each machine's name and recipe, and mark every input (green) and output (red) with a number on its
tile, listed underneath with item, rate and tile. Input belts also say which lane the item goes on: "both lanes"
for a belt of its own, "left lane" or "right lane" when it shares the belt with a second item, seen looking
along the belt into the block (for a belt entering from the west, left is the north side).

## Job config

Unknown keys are an error. Everything except `targets` has a default.

```json
{
  "targets": [{ "name": "electronic-circuit", "amount": 2.0 }],
  "machine": "assembling-machine-3",
  "machines": ["assembling-machine-3", "electric-furnace", "chemical-plant", "oil-refinery", "centrifuge"],
  "belt_tier": "fast",
  "weave_tier": "auto",
  "lane_sharing": false,
  "inserters": { "allowed": ["inserter", "long-handed-inserter", "fast-inserter"], "belt_pickup_derate": 0.7 },
  "research": { "inserter_capacity": 0, "productivity": { "steel-plate": 0.2 } },
  "raw_resources": null,
  "sources": [
    { "name": "iron-plate", "edge": "west", "from": 0, "to": 20 },
    { "name": "copper-plate", "x": -1, "y": 12 },
    { "name": "crude-oil", "edge": "south" }
  ],
  "outputs": [{ "name": "electronic-circuit", "edge": "east" }],
  "box": { "aspect": 1.0, "fixed_width": null, "start_size": null, "max_size": 80 },
  "sinks": { "mode": "port" },
  "fluids": { "mode": "pipes" },
  "power": { "mode": "set-cover", "pole": "medium-electric-pole", "allow_outside_ring": true },
  "search": { "time_budget_s": 300, "seeds_per_size": 3, "threads": 4, "seed": 12345, "verbose": true },
  "score": { "belt": 0.3, "underground": 1.0, "inserter": 0.2 },
  "test_mode": false,
  "blocks": { "max_machines": 120, "split_at": ["iron-plate", "copper-plate"], "corridor": 4,
              "link_belt_share": 0.9, "split_at_weight": 0.1, "block_time_share": 0.9,
              "link_cost": 1.0, "fluid_link_cost": 3.0, "block_cost": 4.0, "aspect_weight": 0.5, "wire_weight": 2.0,
              "pass_through": false, "lane_pairs": true, "rotate": true, "floorplan_tries": 3,
              "compact_s": null, "shape_variants": [], "shape_variant_s": null,
              "compact": true, "repack": false, "repack_share": 0.25, "merge": false, "merge_share": 0.3 },
  "reserved": [{ "w": 10, "h": 8, "corner": "south-east" }],
  "ports_on_ring": true,
  "marker_tile": "hazard-concrete-left",
  "tuning": { "route": { "p_mult": 1.1 }, "place": { "beta2": 1.0 } }
}
```

- Amounts are items (or fluid units) per second. Anything listed in `sources` enters the block at a port (a
  fixed tile, an edge segment, or `"edge": "any"`); everything else is made inside from ores, crude oil and
  water, which enter at ports anywhere on the edge. A port one tile wide carries one belt, which branches with
  splitters to all its consumers; give a busy input a few tiles of edge so it can enter on several belts.
  Fluid ports may touch: the generator starts them with pipe-to-ground, which you feed through its open side.
- Products and surplus by-products leave through output ports (`outputs` pins their edge; otherwise the
  generator picks one and reports it in `ports.json`). `"sinks": {"mode": "chest"}` puts products into chests
  instead, as in v1.
- Oil cracking takes only what is left over: wherever heavy (light) oil is cracked and something else also uses it
  (lubricant, or oil leaving the block), a storage tank goes on that oil's pipes with a red wire to the cracking
  plants, which work only while the tank is more than half full (`fluids.surplus_only`, recipe -> fluid, `{}` turns
  it off; `fluids.surplus_level`). Machine counts are rounded up, so uncontrolled crackers run faster than the plan
  needs, starve lubricant and fill the petroleum pipes until the refineries stop. The tank reads its own contents,
  which follow the fill level of the whole pipe network, so the threshold does not depend on pipe length.
- Fluids are routed in pipes and pipe-to-ground (`"fluids": {"mode": "infinity-pipe"}` puts an infinity pipe on
  every fluid connection instead, `"manual"` leaves the connection tiles free).
- `box.max_size` caps the box (the side of a square of the box's area): the search starts there and, if it
  fails, keeps trying new seeds at that size instead of growing. `box.start_size` sets where it starts
  (default: `max_size`, else twice the estimate from machine area). Either way it then shrinks the box.
- `weave_tier` is the second underground tier that may cross under the main tier's undergrounds ("auto":
  express under fast belts; "none" turns weaving off). `lane_sharing: true` lets two items share a belt, one
  per lane (opt-in until the drop-lane facts are checked in game, see docs/VERIFY.md).
- `research.inserter_capacity` (0–7) sets inserter hand sizes; `machines` picks one machine per recipe category.
- `research.productivity` adds research productivity per recipe on top of the machine's own (+50% in a foundry,
  electromagnetic plant or biochamber), capped at the recipe's maximum. Rates and machine counts follow from it.
- `raw_resources` lists what may enter without a listed source. By default that is ores, stone, coal, wood, crude
  oil, water and fish; `[]` means only `sources` enter (a space platform).
- `reserved` keeps empty areas for building by hand later: `w` × `h` at a `corner` (`north-west`, `north-east`,
  `south-west`, `south-east`; by default the corners in turn) or at a fixed `x`, `y`. Nothing is placed there,
  the box grows to fit, and the border is marked. In a factory of blocks they sit east of the blocks.
- `ports_on_ring` (default on) moves every input and output one tile out of the box, onto the ring around it,
  so ports stand out. `marker_tile` is the floor tile drawn around blocks and reserved areas in the blueprint
  (`"none"` for no tiles, e.g. on a space platform, where concrete cannot be placed).
- `test_mode` feeds every input from infinity chests or pipes outside the box and empties every output into
  void chests or drains, for testing in the editor.
- `tricks` toggles individual techniques (I1–I13, B1–B13, F1, F2, P1, P2, H1); v1 ones are on by default.
- The old CP-SAT fields (`forbidden_places`, source `force`) are still accepted so old job files load, but do nothing.

### Big jobs: a factory of blocks

With a `blocks` section, a job with more than `max_machines` machines is built as several blocks connected by
belts and pipes, in one blueprint:

1. **Split.** The whole job is planned once, so every rate is exact, then its machines are divided into blocks of
   at most `max_machines`. The split pays for what it cuts: every item a block makes more or less of than it uses
   is a port on that block and a belt (or pipe) through the corridors, so it costs `link_cost` per belt
   (`fluid_link_cost` per pipe port), plus a little per belt's worth of rate, plus `block_cost` per block.
   Items in `split_at` cost `split_at_weight` times as much (plates are natural cut points). A greedy merge of
   same-recipe groups gives a first split; simulated annealing then moves single machines, swaps two, and moves
   whole recipe groups between blocks. `factory.txt` reports the ports of the split against the greedy one.
   Machines joined by pipes may end up in different blocks: oil processing no longer has to be one block.
   Machine counts are rounded up, so most machines have room to spare; a linear program then lets machines of
   one recipe in different blocks run at different rates (within their crafting speed and planned inserters) so
   each block makes what it uses, instead of trading slivers like 0.06 cable/s on a belt of their own. The split
   kept is the one (greedy or annealed) that is cheapest after this; `plan_rebalanced.json` has its rates.
2. **Plan the floorplan.** The blocks, with shapes estimated from their machines' area, are arranged by
   simulated annealing over sequence pairs: any arrangement, not just rows. It minimises the factory's area,
   its distance from the job's `box.aspect` (`aspect_weight`), and the length of the belts between blocks
   (`wire_weight` per tile, to the factory's edge for inputs and outputs). This plan decides each block's shape
   and which side of the block each port goes on: towards the block it trades with, or towards the edge the job
   gives the item. A block that ends up on an allowed edge (`"any"`, or a whole side) takes that input or output
   straight through it; the others run through the corridors to the edge.
3. **Blocks.** Every belt between two blocks is a link with an exact rate (at most `link_belt_share` of a belt).
   With `pass_through`, what one supplier (a block, or the factory edge for raw inputs) sends to several blocks
   passes through them in a chain: the nearest block keeps its share and passes the rest on to the next, as long
   as the chain fits one belt and each next block is nearer the chain's end than the supplier. It is off by
   default: on six science the belts passing through made blocks bigger than the corridor belts they saved.
   Two items going between the same two blocks that each fit a lane share one belt (`lane_pairs`): the consumer
   takes them on one input belt, and next to the supplier their belts side-load onto it from either side.
   Every fluid traded between blocks gets one pipe tree through the corridors joining one port on each block
   that makes or uses it (and the factory edge, if it enters or leaves there), so any number of blocks can share
   a fluid. Each block is generated on its own, at its planned aspect, with its share of the time budget.
4. **Floorplan and stitch.** The finished blocks are arranged again with their real sizes and port positions, each
   block turned in whichever quarter turn packs best (`rotate`; its ports turn with it),
   keeping free space around each block for the corridors (half a `corridor` plus room for that side's ports;
   none on a side whose ports leave the factory straight out, which must stay on the factory's edge). The belts
   and pipes are routed through the corridors. Where routing fails, the block sides near the trouble get more
   room and it tries again; once it routes, lines of corridor that stayed empty are given back, the blocks are
   rearranged with the corridors as measured, and single corridor lines are squeezed out one block side at a time
   while it still routes (for the stitching share of the time budget). This is done from `floorplan_tries`
   different floorplans and the smallest valid factory wins. Poles cover the whole factory and
   the result is validated end to end against the full plan. A pipe port right beside another fluid's pipe is
   left through a pipe-to-ground pair.
5. Rows and columns that only straight belts, straight pipes or underground spans cross are removed
   (`compact`, on by default; the factory is validated again, and kept as stitched if that ever fails).
   Compaction then continues for `compact_s` seconds (default: the stitching share of the budget; `--compact=<s>`
   on the command line): wherever every longest chain of blocks across the factory can lose a padding line, it
   does, as long as the corridors still route; when that stalls, the blocks are floorplanned again from where they
   are with a fresh seed and a lighter price on belt length. Every smaller valid factory is saved on the way.
   With `shape_variants` (e.g. `[0.6, 1.7]`) each block is also searched at its aspect times those factors
   (`shape_variant_s` seconds each), and the floorplan picks the shapes that pack best.
6. With `repack`, a floorplan of the blocks' real areas with free shapes says which shape each block should have;
   blocks far from it are searched again at that aspect, and the floorplan then picks, per block, the layout that
   packs best. Kept if the factory gets smaller.
7. With `merge`, until the time budget runs out, two neighbouring blocks become one block, searched starting from
   their placements as they lie in the factory, kept if the factory gets smaller (merged blocks have at most
   `merge_max_machines`, default twice `max_machines`).

Inputs and outputs take the edges in `sources` and `outputs`, belts and pipes alike, as for a single block, with
`from` and `to` counting tiles of the factory as stitched. Factory jobs take edges only, not `x` and `y`.
Reserved areas are placed by the floorplan in their corners. `rows` is ignored now (old job files still load).

Every smaller valid factory is saved as it is found, so stopping the run early keeps the best so far.

The output folder holds the factory (`best_output_string.txt`, `best.png`, `ports.json`, `factory.txt` with the
split, the links, the pipe trees and every stitching step) and each block in `block_<n>/`.

### Space Age

The foundry, electromagnetic plant and biochamber are supported (their sizes and fluid connections come from
the 2.0 game data). The crusher works on space platforms only. The cryogenic plant is placed like any other
machine, but Aquilo's heating is not modelled. The recycler is not supported: recycling recipes are left out
of the plan, so Fulgora jobs list what recyclers would deliver as raw resources instead. List them in
`machines`, in the order you prefer them for recipes several machines can make. The planner counts their
built-in productivity, a biochamber burns nutrients like a furnace burns coal, and a machine with several
products (asteroid crushing returns part of its chunk) gets one filtered output inserter per product.

There is no special mode for space platforms; the normal options describe one:

```json
{
  "targets": [{ "name": "iron-gear-wheel", "amount": 2.0 }, { "name": "copper-cable", "amount": 4.0 }],
  "machines": ["crusher", "electric-furnace", "assembling-machine-3"],
  "raw_resources": [],
  "sources": [{ "name": "metallic-asteroid-chunk", "edge": "west" }],
  "forbidden_recipes": ["metallic-asteroid-reprocessing"],
  "research": { "productivity": { "iron-gear-wheel": 0.1 } },
  "box": { "fixed_width": 24, "max_size": 40 },
  "forbidden_tiles": [{ "x": 0, "y": 0, "w": 8, "h": 8 }],
  "reserved": [{ "w": 6, "h": 6, "corner": "north-east" }],
  "marker_tile": "none",
  "power": { "pole": "medium-electric-pole" }
}
```

`raw_resources: []` keeps ore from simply entering, so the crushers make it (here advanced crushing, the only
platform source of copper); `sources` are where the chunks
arrive (an edge, or exact tiles next to your collectors); `forbidden_recipes` and `recipe_overrides` decide
which recipes may be used; `box`, `forbidden_tiles` and `reserved` fit the layout to the platform's shape and
leave room for what you build by hand. Not modelled: spoilage, the stack inserter's belt stacking, quality
outputs, and the space platform foundation itself.

### Tuning knobs

Every algorithm knob (placer, router, search) has a measured default in `src/main/resources/tuning.json`. A
job overrides any of them in its `tuning` section, with the same layout and only the keys it changes; unknown
keys are an error. `--print-tuning` prints every knob with the value the job would use, and each run saves the
values it used as `tuning.json` in its output folder. The ones that matter most:

| Knob | Default | What it does |
|------|--------:|--------------|
| `route.p_mult` | 1.1 | How fast the price of a contested tile rises per routing iteration. Slower finds more solutions but needs more iterations (1.5 was the old default; 1.05 routes a bit more per trial but is slower per minute). |
| `route.max_iterations`, `route.stagnation_stop` | 120, 40 | Routing iterations, and how many without improvement end them early. Raise both with a lower `p_mult`. |
| `route.endgame_rounds`, `route.endgame_time_factor` | 30, 2 | Rip-up-and-reroute rounds after negotiation stalls, and their time cap (multiple of the negotiation's time). |
| `route.ug_penalty`, `route.turn_penalty` | 2.0, 0.2 | Cost of an underground pair and of a belt turn, in belt tiles: shape preferences. |
| `route.weave_penalty` | 1.0 | Extra cost of a second-tier underground (weaving). |
| `route.max_chain_visits` | 12 | Inserters one belt chain serves before an item's net is split into several chains. A port gets no more chains than it has tiles, so a one-tile port feeds a single belt that branches with splitters. |
| `route.chain_belt_share` | 0.9 | Share of a belt one chain may carry before the item gets another belt. |
| `route.unrouted_penalty` | 10 | How many tile conflicts one unrouted chain counts as when routing iterations and rounds are compared. |
| `route.max_expansions` | 3000000 | Search states one belt or pipe path search may expand before giving up. |
| `place.access_demand` | 1.0 | Room kept around machines for the belts that serve them (0 turns it off). |
| `place.beta2` | 1.0 | How strongly a failed route's congestion map moves machines in the next placement round. |
| `place.feedback_rounds` | 3 | Placement and routing rounds per trial before it counts as failed. |
| `place.moves_per_machine` | 20000 | Annealing effort per machine. |
| `place.near_miss_max`, `place.near_miss_attempts` | 4, 4 | A trial that ends within this many conflicts nudges a machine and retries this many times. |
| `place.initial` | random | Starting placement: "random", "columns" (machines of a recipe stacked in columns with belt corridors between them, `column_gap`, `column_spacing`, `column_moves_only`, `column_swap_across`, `column_t0_scale`), or "mixed" (columns on even seeds). Columns matched the random start in benchmarks; worth trying per job. |
| `place.belt_cost`, `place.path_weight` | hpwl, 1.0 | How a net's belt length is estimated: bounding box x q(k), or "path" (bounding box plus the detours of a path between the machines). |
| `search.grow_factor`, `search.grow_rounds` | 1.25, 2 | Box growth after a failed size, and seed rounds per size before growing. |
| `planner.access_share` | 0.75 | Share of the tiles around a machine its inserters may take; a recipe that would need more gets extra machines (fast Space Age machines with productivity hit this). |

The full list with explanations is in `src/main/kotlin/config/Tuning.kt`; measurements in
[docs/TUNING.md](docs/TUNING.md). The block options `link_belt_share` (a link between blocks above this share
of a belt gets a second belt), `split_at_weight` (how much `split_at` items' traffic counts when grouping
machines into blocks) and `block_time_share` (budget share for generating blocks; the rest is for stitching)
sit in the job's `blocks` section.

### What the terminal shows

At the start the search prints the plan: machines per recipe, and per item its rate, inserters, belts and port
(with its tiles, and a note when one belt has to serve more inserters than `route.max_chain_visits`). Then, with
`search.verbose` (default on), one line per finished trial:

```
[   18.1s]   14x14 try 1: no route in 18.0s, 4 routing runs; closest: 3 conflicts, unrouted [copper-cable] (copper-plate/iron-plate x2 near (5,9))
[   41.0s]   14x14 try 3: ok in 6.5s, 2 routing runs, score 618.0
```

"closest" is the routing run that came nearest: tile conflicts left, chains it could not route, and where the
conflicts are, grouped by the items that meet there. After each box size comes the history of every box tried,
smallest first, with its tries, successes, and best score or closest miss. The same lines go to `log.txt`, and
every trial is a row of `attempts.tsv` (box, seed, stage, seconds, routing runs, conflicts, unrouted items,
score, where). A box that fails every seed at the same spot, whatever the size, points at the job (a port too
narrow, fluids pinned side by side) rather than the box.

## How it works

| Stage | Package | What it does |
|-------|---------|--------------|
| 1 | `plan` | LP over recipe rates (by-products, recipe choice) → machine instances, terminals, nets and ports |
| 2 | `place` | Simulated annealing (VPR schedule) on HPWL × q(k), RUDY congestion, pin access, direct-insertion bonus, rotation and mirroring, fluid connection legality |
| 3 | `route` | Negotiated-congestion routing: belt chains leg by leg with A* over (tile, heading, belt/underground), splitter branches, pipe trees with pipe-to-ground, and a rip-up-and-reroute endgame around the last conflicts |
| 4 | `power` | Pole set cover and MST wiring (lattice fallback), test-mode harness |
| 5 | `search` | Box size search (gentle growth to a first success, then bisection), K seeds per size in parallel, warm starts, anytime outputs, calibration |
| — | `validate` | Re-derives belts, lanes, splitters, underground pairing, items, order-aware rates, power, fluid networks and ports from the entity list alone |
| — | `emit`, `viz` | Blueprint 2.0 encode/decode, PNG renders |

Two things go beyond the original plan and are described in [docs/TUNING.md](docs/TUNING.md): nets with many
inserter visits are split into several belt chains, and chains may interleave producers and consumers when the
supply upstream already covers a consumer.

## Tests

```sh
./gradlew test
```

The suite covers Stage 1 counts (T1–T4), blueprint round trips, one fixture per validator check, router
fixtures and random belt and fluid instances, placer properties, full trials and the search loop.

Three diagnostics are skipped unless their environment variable is set: `DiagTest` (`DIAG=<job>,<size>,<seeds>`)
runs single trials and prints why routing fails (`DIAG_ITEM=<item>` lists the plan's terminals of an item,
`DIAG_AT=<x>,<y>,<radius>` the entities around a tile); `SweepTest` (`SWEEP=<spec file>`) runs many trials in
parallel for parameter sweeps (see [docs/TUNING.md](docs/TUNING.md)); `JobFuzzTest` (`FUZZ=<minutes>`, with
`FUZZ_THREADS`, `FUZZ_SEED`, `FUZZ_OUT`) makes random jobs (base game, Space Age, space platform), builds each at
a roomy size and records every planner or trial exception, invalid layout or failed blueprint round trip in
`results.tsv`, saving the job for `DiagTest`. Run them with `--rerun -i`.

## Status

[docs/STATUS.md](docs/STATUS.md) maps every milestone acceptance test to the code. T1–T4 produce validated
layouts in seconds. Chemical science at 0.5/s from ore and crude oil (43 machines, oil processing included)
validates at 36 × 36 in about 5 minutes, and red + green + chemical science at 1/s from ore (110 machines) at
64 × 64 within 20 minutes. Six science at 1/s each from ore (618 machines) is built as a factory of 10 blocks
at 317 × 137 in 30 minutes (see [More layouts](#more-layouts)).

Space Age layouts for Vulcanus, Fulgora and Gleba are in [More layouts](#more-layouts). Cryogenic science is
not shown: Aquilo needs heat pipes to keep machines warm, and the generator does not place them.

## When the planner outsmarts you

The planner is a linear program that minimises machine area plus small costs for inputs and larger ones for
by-products leaving the block. It takes those costs literally, and several jobs came back technically optimal
and practically useless:

- **Vulcanus stone.** Melting lava for iron and copper leaves stone. Asked to make metallurgic science, the
  planner turned the stone into landfill, because three landfill machines cost less than stone leaving the
  block. Landfill forbidden: it made stone bricks and walls (27 furnaces). Those forbidden: stone furnaces.
  The fix was to make leftovers almost free (`planner.surplus_cost_item`), so stone simply leaves.
- **Selling the input back.** A test job asked for sulfuric acid and also allowed sulfuric acid in as a raw
  resource. The planner's cheapest "factory" was no machines at all: take the acid in on one edge and send it
  straight out the other, a bare pipe that the router could not even build. Targets are now always made inside
  the block, never passed through.
- **Space platforms.** Asked for iron on a platform, it ignored the crushers and imported iron ore, since ore
  was on the default list of raw resources. `raw_resources` now says exactly what may come in.
- **Fulgora plastic.** Electromagnetic science needs superconductors, superconductors need plastic, plastic
  needs coal, and Fulgora has none: no plan at all until plastic (from recycling) was allowed in.

## Not verified in game yet

Some 2.0 facts are marked `VERIFY` in `data/GameConstants.kt` (inserter rates, underground pairing details,
fluid input side, wire format, Space Age fluid connections, inserter filters). [docs/VERIFY.md](docs/VERIFY.md)
has a blueprint for each.

## More layouts

Electromagnetic science at 1.5/s (Space Age, Fulgora): electromagnetic plants with assemblers, chemical plants
and an oil refinery, no machines from other planets. Fulgora's real input is scrap, and a recycler turns it
into about a dozen products at once, too many for one block to take out, so the block is fed what Fulgora's
recyclers deliver instead: holmium ore, ice, stone, batteries, plastic, iron and copper plates, copper cable,
plus heavy oil from the ocean.

![Electromagnetic science](docs/examples/electromagnetic_science/best.png)

Built in game:

![Electromagnetic science, in game](docs/examples/electromagnetic_science/in_game.png)

Metallurgic science at 4/s (Space Age, Vulcanus), from what Vulcanus has: lava, calcite, coal, tungsten ore and
sulfuric acid. Foundries melt lava into molten iron and copper and cast tungsten plate and carbide, chemical
plants make carbon from coal. Melting lava leaves stone, which leaves the block as a by-product (33/s); the job
lowers `planner.surplus_cost_item` so the planner lets it go instead of turning it into landfill or walls.

![Metallurgic science](docs/examples/metallurgic_science/best.png)

Built in game:

![Metallurgic science, in game](docs/examples/metallurgic_science/in_game.png)

Agricultural science at 5/s and carbon fiber at 5/s (Space Age, Gleba): biochambers fed nutrients, foundries and
chemical plants. Yumako and jellynut seeds come out as by-products of processing the fruit (here 0.49 and 0.06
seeds/s, through output ports); to get more, add them to `targets` with the amount you want (for example
`{"name": "yumako-seed", "amount": 2.0}`), and the planner processes more fruit to make them.

![Agricultural science and carbon fiber](docs/examples/gleba_agricultural_carbon_fiber/best.png)

Built in game:

![Agricultural science and carbon fiber, in game](docs/examples/gleba_agricultural_carbon_fiber/in_game.png)

Six science at 1/s each from ore (618 machines) does not fit one block; as a factory of 10 blocks of at most
69 machines (10 threads (M5), belt sharing on) it validates at 317 × 137 after 30 minutes. The blocks are dense,
but 37% of the factory is still space between blocks: the two rows differ in width, and repacking left too
little room for the links between blocks.

| Block | Machines | Box | Search ran |
|-------|---------:|----:|-----------:|
| 1 | 32 | 28 × 28 | 1 min 28 s |
| 2 | 39 | 32 × 32 | 1 min 46 s |
| 3 | 69 | 45 × 45 | 3 min 15 s |
| 4 | 69 | 45 × 45 | 3 min 10 s |
| 5 | 64 | 55 × 55 | 2 min 57 s |
| 6 | 69 | 51 × 51 | 3 min 16 s |
| 7 | 69 | 53 × 53 | 3 min 20 s |
| 8 | 69 | 53 × 53 | 3 min 11 s |
| 9 | 69 | 66 × 66 | 4 min 6 s |
| 10 | 69 | 78 × 78 | 3 min 24 s |

![Six science, 10 blocks](docs/status/six_science_9_blocks/best.png)

Built in game:

![Six science, 10 blocks, in game](docs/status/six_science_9_blocks/in_game.jpg)

## License

Copyright © 2026 Filip Hostinský. Licensed under the [GNU General Public License v3.0](LICENSE): you may use,
change and share this program, including commercially, as long as anything you distribute that is based on it
is also released under GPL-3.0 with its source code. `recipes.json` is extracted from Factorio's game data,
which belongs to Wube Software and is not covered by this license.
