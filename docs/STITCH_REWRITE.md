# Wiring between blocks: the rewrite

How a factory of blocks gets from finished blocks to one valid factory: who owns the ends of links, how corridors are
sized, how belts and pipes are routed through them, and how the work uses the machine's cores. Written 2026-11-10,
after an evening of Py production science runs in which every new kind of port end broke the stitch somewhere else.

## Why

The stitch today (`blocks/Factory.kt`: `assembleFactory`, `Stitching`, `stitchRoute`):

1. The floorplan packs blocks (sequence pairs) with padding per side: half a corridor plus room for the side's ports.
2. `stitchRoute` places what belongs at ports outside the blocks: chests for local sinks, provider and requester
   chests for robot links, valves and voids for fluid by-products, splitters for shared outputs, junctions for lane
   pairs. Then it builds one routing problem over the whole factory (every link a net between fixed port tiles,
   blocks as obstacles) and runs the same fine-grid router the blocks use.
3. Where routing fails it widens near the trouble and tries again; once it routes, empty padding is given back and
   the factory is squeezed.

What went wrong, all on Py production science (24 blocks, 869 machines):

- **Ends placed too late.** Chests, requesters and voids are placed into whatever room the floorplan left. Each new
  kind failed differently: two fluid ports side by side wanted the same sinkhole tiles; a block sat against its
  neighbour where a requester was to go; a chest took the tile a corridor belt had to start on ("goals reachable 0").
  The floorplan never knew these ends needed room, and widening moved the problem along with the block.
- **Trial and error on the corridors.** Some 640 links, each its own belt through 20-tile corridors. One routing
  attempt took 10 to 18 minutes; a failure only said how many conflicts, and the answer was to widen and try again.
- **One core.** One attempt routes its links one after another; `search.stop_after` even held the final stitch to
  one floorplan.

## The design

### 1. Ends belong to blocks

Each block port has an *end*: `edge` (a belt or pipe on to the corridors or the factory's edge, as now), `chest` (a
local sink), `provider` and `requester` (robot links), `void` (a fluid by-product destroyed in place). The split
decides it (`withLocalSinks`, `withBotLinks`) and the block plan carries it on the port.

The block search lays out the block as now. When a layout is finished (`search.Trial`), every port that is not an
`edge` gets its end built just outside the routed box, on the port's side: an inserter and chest, a requester chest
and inserter, a valve then the void (a few pipes on where neighbouring voids would overlap). The block's box grows by
that apron on each side that has one, so the block, its ends and their room travel together: the floorplan packs
them as one rectangle, the stitch only copies them, and nothing outside a block ever needs room for an end.

Consequences:

- `stitchRoute` places no chests, requesters, valves or voids; links with such ends are not corridor links.
- The block's own validation sees its ends (an output into a chest, a requester feeding its port belt).
- A block's test copy feeds a requester as an infinity chest of its item (no robots in a block on its own).
- Resumed block layouts without their ends are dropped and laid out again.

### 2. Corridors sized before they are routed (global routing)

Detailed routing on the fine grid stays (it knows undergrounds, lanes, splitters and pipe-to-ground), but it only
runs once the corridors are known to be wide enough:

- A coarse grid over the floorplan (cells of 4 x 4 tiles; a cell inside a block is closed). The capacity of a cell
  side is the number of free tile rows across it (each holds one belt or pipe along the corridor, and undergrounds
  let one cross under another).
- Every corridor link (belt or pipe tree) is routed on the coarse grid with negotiated congestion (the same idea as
  the fine router: costs rise where demand exceeds capacity, every link is routed again, a few dozen rounds; with a
  few thousand cells this takes seconds).
- Where demand still exceeds capacity, the corridor there is widened by exactly the overflow (the padding of the
  blocks on both sides), the floorplan is packed again, and global routing repeats. No 15-minute attempt is spent on
  a floorplan that cannot fit its links.
- Once nothing overflows, each link's coarse route becomes a corridor of cells it may use: the fine router routes it
  inside them (tiles outside cost a lot, not forbidden), so each search covers a strip, not the whole factory.

### 3. Every core

- Several floorplans side by side, the first that routes taken (done: commit 7b57704).
- Detailed routing in parallel: links whose coarse corridors do not overlap are independent; groups of them route on
  separate threads, then the conflicts between groups (few, at shared corridors) are negotiated as now.

### What stays

The floorplanner (sequence pairs), shared outputs with splitters, lane-pair junctions, pipe trees between blocks
(hub links), edge ports and their test harness, squeezing, validation, and everything inside blocks.

## Order of work

1. Ends in blocks: port ends on the block plan, ends built in `Trial`, the box grown, `stitchRoute` without its stub
   code, test copies, resume check. Checked on the vanilla factory jobs (`FactoryTest`) and Py production science.
2. Global routing on the coarse grid, the corridors widened from its overflow, before any fine routing. Behind
   `blocks.stitch: "global"` (default the old way) until it is better on the same jobs.
3. Fine routing inside the coarse corridors, then in parallel.
4. Make it the default; take out widen-and-retry.

Each step leaves a working program: the same tests, then the same jobs in game.
