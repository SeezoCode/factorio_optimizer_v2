# Community blueprints as a benchmark

Can the solver learn from designs other players publish? This compares 15 well-regarded Factorio 2.x designs from
factorio.school with the solver's layouts for the same jobs, measured the same way, runs both in the real game,
and lists what the solver should copy.

**Short answer: yes, and the lessons are concrete.** For the same output the community design is smaller on 13
of 15 jobs, by 1.25 to 3.8 times the area (median 1.66); the solver ties on green science 1/s and beats a plastic
"microfactory" (0.82). The gap is structural, not search time: 15-minute reruns of the large jobs mostly did not
move (red 22.5/s with beacons went from 3.8 to 2.6 times), and none of the existing knobs (lane sharing, a bigger direct-insertion bonus, column starts, research) closes it.
Community designs win with a few mechanical patterns: direct-insertion chains (cable straight into circuits), long
straight rows of identical machines on shared belts, two items per belt, and fixed cross-sections for beaconed and
fluid builds. They use about 2 inserters and 5 belt tiles per machine; the solver 3–4 and 10–17. Those patterns
can become templates the placer moves as single units ([What to change](#what-to-change-in-the-solver)).

In the game the picture is mixed. With inserter capacity research, nine community designs made their label rate
(four more could not be fed by the automatic test copy, two carry fluids it does not feed); without research most
of those fall to 24–89 %, since they are built for it. The solver's layouts need no research, but **5 of its 14
fell short in game**: green science makes 79–92 % of plan in four jobs, red science 1.7/s 82 %, all with idle
consumers and surplus upstream and no stuck inserters. That looks like the same fault as the 2/s six-science factory, in layouts of 19–31 machines.

## Sources

| Site | Terms / robots.txt | API or export | Usable? |
|---|---|---|---|
| **factorio.school** (= factorioprints.com, same site and code) | robots.txt: `User-agent: * Allow: /`, Cloudflare content signals `search=yes, ai-train=no, use=reference`; Anthropic's crawler user agents are disallowed (this was not a crawler: a few dozen user-requested fetches with an honest User-Agent) | Yes. Its open-source frontend ([FactorioBlueprints/factorio-prints](https://github.com/FactorioBlueprints/factorio-prints), `src/api`) uses a public REST search (`www.factorio.school/api/blueprintSummaries/search/page/N?text=&orderBy=Favorites&gameVersionLong=`) and serves every blueprint as static JSON on a CDN (`factorio-blueprint-firebase-cdn.pages.dev/<3-char prefix>/<key>.json`, robots.txt without rules) | **Yes, used.** Favourites give a quality signal, tags and titles find jobs, books hold many designs per request |
| factoriobin.com | robots.txt has only the content-signal preamble, no rules | Pastebin: no search, no ratings | Allowed but not useful for finding *good* designs; fine for a single link someone shares |
| Reddit r/factorio | robots.txt `User-agent: * Disallow: /`; Public Content Policy restricts automated access | Official API needs registration and approval | **Not used** |

Fetched for this study: 8 search pages and 13 blueprint records (books) from factorio.school, at most one request
every 2 seconds, cached locally (`~/.cache/factorio-optimizer/blueprints`, outside the repo). Nothing from them is
republished here: the repo holds only keys, links and measured numbers. `tools/blueprints/fetch.py` does the same
politely and cached.

## Designs

All 2.0 or 2.1 blueprints (the version stored in each blueprint), picked from books with many favourites, for jobs
the solver does: red/green science, electronic circuits from plates, plastic, sulfuric acid, and beaconed science.
Most labels state a rate; the "made" column is what `analyze.py` computes from the machines, modules and beacons
(all machines at full speed, then limited by whatever the design's own intermediate machines can supply).

| id | design (book, path in book) | book favourites | game | machines | made /s (computed) |
|---|---|---|---|---|---|
| kosrg | [KoS Factorio Framework red + green science, 1.5/s each (the KoS book labels the same layout 2/s)](https://www.factorio.school/view/-O5tYoVnbCXQNShamF6Y) (The Science Book, `0`) | 39 | 2.1.17 | 27 (assembler 2) | logistic science 1.5, automation science 1.5 |
| red1p5 | [Automation science 1.5/s](https://www.factorio.school/view/-O5tYoVnbCXQNShamF6Y) (The Science Book, `26/0`) | 39 | 2.1.17 | 11 (assembler 2) | automation science 1.5 |
| green1p5 | [Logistic science 1.5/s (circuits in)](https://www.factorio.school/view/-O5tYoVnbCXQNShamF6Y) (The Science Book, `26/3`) | 39 | 2.1.17 | 16 (assembler 2) | logistic science 1.5 |
| red100 | [Red science 100 SPM (assembler 1)](https://www.factorio.school/view/-OEKiQbV_Vqpu--C4GbR) (All 100 SPM. Nauvis science book, `0`) | 42 | 2.0.23 | 19 (assembler 1) | automation science 1.7 |
| green100 | [Green science 100 SPM from plates (assembler 1)](https://www.factorio.school/view/-OEKiQbV_Vqpu--C4GbR) (All 100 SPM. Nauvis science book, `1`) | 42 | 2.0.23 | 31 (assembler 1) | logistic science 1.67 |
| red1 | [Automation science 1/s (assembler 1)](https://www.factorio.school/view/-ON-v5kGTPfWiuj04bIv) (ALL THE SCIENCE, `0/0`) | 51 | 2.0.43 | 11 (assembler 1) | automation science 1 |
| green1 | [Logistic science 1/s from plates (assembler 1)](https://www.factorio.school/view/-ON-v5kGTPfWiuj04bIv) (ALL THE SCIENCE, `1/0`) | 51 | 2.0.43 | 19 (assembler 1) | logistic science 1 |
| circ12 | [12 electronic circuits/s bus build](https://www.factorio.school/view/-O5tdYHBzmLLrYWeydMv) (The Circuit Book, `0`) | 47 | 2.1.17 | 20 (assembler 2) | electronic-circuit 12 |
| circ6 | [Electronic circuits 02 (6/s per tile, tileable)](https://www.factorio.school/view/-O5tdYHBzmLLrYWeydMv) (The Circuit Book, `39/10`) | 47 | 2.1.17 | 10 (assembler 2) | electronic-circuit 6 |
| circ30 | [Green circuits 30/s](https://www.factorio.school/view/-OEKiQbV_Vqpu--C4GbR) (All 100 SPM. Nauvis science book, `7`) | 42 | 2.0.23 | 50 (assembler 2) | electronic-circuit 30 |
| circ28 | [30 electronic circuits/s bus build (28.5/s as built)](https://www.factorio.school/view/-O5tdYHBzmLLrYWeydMv) (The Circuit Book, `1`) | 47 | 2.1.17 | 49 (assembler 2) | electronic-circuit 28.5 |
| plastic24 | [Microfactories: plastic (24/s)](https://www.factorio.school/view/-OEYpgwrnR1zCwk_r2JF) (Microfactories for Space Age, `0/13`) | 83 | 2.0.72 | 12 (chemical-plant) | plastic-bar 24 |
| acid100 | [Production Book: sulfuric acid, 2 plants (100/s)](https://www.factorio.school/view/-N8_DKC5AVE1L5tfVX4l) (Production Book, `26/3`) | 370 | 2.0.77 | 2 (chemical-plant) | sulfuric-acid 100 |
| red22 | [22.5 automation science/s, 8 beacons per assembler](https://www.factorio.school/view/-O5tYoVnbCXQNShamF6Y) (The Science Book, `6`) | 39 | 2.1.17 | 16 (assembler 3), 37 beacons | automation science 24 |
| green22 | [22.5 logistic science/s, 8 beacons per assembler](https://www.factorio.school/view/-O5tYoVnbCXQNShamF6Y) (The Science Book, `7`) | 39 | 2.1.17 | 22 (assembler 3), 50 beacons | logistic science 23.6 |

## Method

- **Measure.** `tools/blueprints/analyze.py` decodes a blueprint (string, file or the site's JSON; `--list` and
  `--pick` for books) and measures the entity bounding box, machines by recipe, beacons, belt tiles (belts,
  undergrounds, splitters × 2), inserters, poles and pipes. Throughput comes from the machines' crafting speed, their
  modules and the beacons in reach (2.0 rules), and a small linear program for the rate the design's own
  intermediate machines can sustain (refinery by-products included). It also counts layout patterns: direct
  insertion, long-handed inserters reaching over a belt, belts served from both sides, belts that must carry two
  items, splitter-free designs, beacons per machine and machines per beacon. `--map` draws the design as text.
- **Solver.** One job per design in [`docs/community_benchmark/jobs/`](community_benchmark/jobs): same products and
  rates, same assembler tier, belt tier, modules and beacons, inputs on the west edge (for science that takes
  circuits in, circuits are an input too). Each ran for 5 minutes on 3 threads (two jobs at a time on a 10-core M5,
  alongside other work), the default settings otherwise. The README's examples ran 5–15 minutes on 10 threads, so
  these are not the solver's best; [Tuning experiments](#tuning-experiments) shows what a few settings change.
  The solver's box below is the entity bounding box of `best_output_string.txt`, which includes the port ring
  (one tile on each side), the same way the community designs are measured.
- **In game.** `tools/blueprints/testcopy.py` turns a community design into a test copy like the solver's own: a
  loader from an infinity chest on every belt that starts open and carries outside items, a loader into a void chest
  on every open end carrying a product. What a belt carries is worked out from the recipes of the machines that take
  from it and drop onto it; when it also carries items made inside (gears next to copper), or two outside items, each
  goes onto its own lane by side-loading, on the lane the inside drops leave free. Lamps and combinators are left out.
  `tools/factorio-verify/verify.py` (from the `claude/zealous-cray-a0cdb6` branch) builds it on a headless server and
  measures 300 s after a 300 s warm-up, once with no research and once with inserter capacity research 7 (hand size
  +2, bulk +10), because many community designs assume it. The solver's own test copies ran the same way with the
  job's research.

## Results

### Size and parts

Area per item/s is the box area divided by the rate the design makes (supported rate, items/s); "solver ÷
community" above 1 means the community design is smaller for the same output. Inserters in brackets: direct
insertion (machine to machine).

| design | made /s | community box = area | machines | beacons | belt tiles | inserters (DI) | poles | solver box = area | machines | beacons | belt tiles | inserters (DI) | poles | solver ÷ community, area per item/s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| [kosrg](https://www.factorio.school/view/-O5tYoVnbCXQNShamF6Y) KoS Factorio Framework red + green science, 1.5/s each (the KoS book labels the same layout 2/s) | logistic 1.5, automation 1.5 | 59×13 = 767 | 27 | 0 | 152 | 57 (2) | 16 | 32×30 = 960 | 27 | 0 | 294 | 84 (3) | 21 | 1.25 |
| [red1p5](https://www.factorio.school/view/-O5tYoVnbCXQNShamF6Y) Automation science 1.5/s | automation 1.5 | 20×13 = 260 | 11 | 0 | 62 | 23 (0) | 7 | 18×18 = 324 | 11 | 0 | 103 | 33 (1) | 9 | 1.25 |
| [green1p5](https://www.factorio.school/view/-O5tYoVnbCXQNShamF6Y) Logistic science 1.5/s (circuits in) | logistic 1.5 | 16×21 = 336 | 16 | 0 | 85 | 34 (2) | 9 | 24×23 = 552 | 16 | 0 | 160 | 50 (2) | 14 | 1.64 |
| [red100](https://www.factorio.school/view/-OEKiQbV_Vqpu--C4GbR) Red science 100 SPM (assembler 1) | automation 1.7 | 16×24 = 384 | 19 | 0 | 99 | 40 (0) | 13 | 27×25 = 675 | 19 | 0 | 217 | 59 (1) | 15 | 1.76 |
| [green100](https://www.factorio.school/view/-OEKiQbV_Vqpu--C4GbR) Green science 100 SPM from plates (assembler 1) | logistic 1.67 | 22×26 = 572 | 31 | 0 | 145 | 63 (7) | 22 | 35×35 = 1225 | 32 | 0 | 326 | 99 (4) | 26 | 2.04 |
| [red1](https://www.factorio.school/view/-ON-v5kGTPfWiuj04bIv) Automation science 1/s (assembler 1) | automation 1 | 7×35 = 245 | 11 | 0 | 66 | 22 (0) | 11 | 19×19 = 361 | 11 | 0 | 116 | 33 (1) | 8 | 1.47 |
| [green1](https://www.factorio.school/view/-ON-v5kGTPfWiuj04bIv) Logistic science 1/s from plates (assembler 1) | logistic 1 | 13×48 = 624 | 19 | 0 | 90 | 38 (5) | 18 | 25×25 = 625 | 19 | 0 | 175 | 56 (3) | 13 | 1.00 |
| [circ12](https://www.factorio.school/view/-O5tdYHBzmLLrYWeydMv) 12 electronic circuits/s bus build | circuit 12 | 23×22 = 506 | 20 | 0 | 107 | 44 (16) | 14 | 29×29 = 841 | 20 | 0 | 287 | 74 (2) | 18 | 1.66 |
| [circ6](https://www.factorio.school/view/-O5tdYHBzmLLrYWeydMv) Electronic circuits 02 (6/s per tile, tileable) | circuit 6 | 9×24 = 216 | 10 | 0 | 60 | 22 (8) | 6 | 19×18 = 342 | 10 | 0 | 114 | 37 (1) | 7 | 1.58 |
| [circ30](https://www.factorio.school/view/-OEKiQbV_Vqpu--C4GbR) Green circuits 30/s | circuit 30 | 22×47 = 1034 | 50 | 0 | 180 | 113 (40) | 29 | 51×49 = 2499 | 50 | 0 | 808 | 190 (4) | 53 | 2.42 |
| [circ28](https://www.factorio.school/view/-O5tdYHBzmLLrYWeydMv) 30 electronic circuits/s bus build (28.5/s as built) | circuit 28.5 | 50×22 = 1100 | 49 | 0 | 288 | 110 (38) | 32 | 51×49 = 2499 | 50 | 0 | 808 | 190 (4) | 53 | 2.16 |
| [plastic24](https://www.factorio.school/view/-OEYpgwrnR1zCwk_r2JF) Microfactories: plastic (24/s) | plastic 24 | 20×22 = 440 | 12 | 0 | 62 | 24 (0) | 4 | 19×19 = 361 | 12 | 0 | 77 | 24 (0) | 9 | 0.82 |
| [acid100](https://www.factorio.school/view/-N8_DKC5AVE1L5tfVX4l) Production Book: sulfuric acid, 2 plants (100/s) | sulfuric-acid 100 | 6×7 = 42 | 2 | 0 | 10 | 4 (0) | 2 | 12×11 = 132 | 2 | 0 | 26 | 10 (0) | 2 | 3.14 |
| [red22](https://www.factorio.school/view/-O5tYoVnbCXQNShamF6Y) 22.5 automation science/s, 8 beacons per assembler | automation 24 | 13×66 = 858 | 16 | 37 | 118 | 37 (0) | 3 | 56×55 = 3080 | 15 | 68 | 283 | 46 (0) | 36 | 3.79 |
| [green22](https://www.factorio.school/view/-O5tYoVnbCXQNShamF6Y) 22.5 logistic science/s, 8 beacons per assembler | logistic 23.6 | 13×83 = 1079 | 22 | 50 | 166 | 60 (0) | 5 | 56×53 = 2968 | 21 | 77 | 340 | 65 (0) | 43 | 2.82 |

### In game

Rates out per second over 300 s, after 300 s of warm-up. ✗: the verifier failed the run (a machine never crafted,
or an inserter was stuck holding an item); the reason is under the table.

| design | community in game: research 0 | research 7 | solver in game |
|---|---|---|---|
| kosrg | logistic 1.51, automation 1.50 | logistic 1.50, automation 1.50 | automation 1.50, logistic 1.31 ✗ |
| red1p5 | automation 1.50 | automation 1.50 | automation 1.50 |
| green1p5 | logistic 1.50 | logistic 1.49 | logistic 1.46 |
| red100 | nothing ✗ | nothing ✗ | automation 1.40 ✗ |
| green100 | nothing ✗ | nothing ✗ | logistic 1.50 ✗ |
| red1 | automation 0.47 ✗ | automation 1.00 | automation 1.00 |
| green1 | logistic 0.46 ✗ | logistic 1.00 | logistic 0.92 ✗ |
| circ12 | circuit 10.67 | circuit 12.00 | circuit 12.00 |
| circ6 | nothing ✗ | nothing ✗ | circuit 5.91 |
| circ30 | circuit 24.75 | circuit 29.98 | circuit 29.83 |
| circ28 | circuit 25.50 ✗ | circuit 28.50 ✗ | circuit 29.83 |
| plastic24 | – | – | plastic 24.00 |
| acid100 | – | – | sulfuric-acid 100.00 |
| red22 | automation 5.35 ✗ | automation 22.50 ✗ | automation 22.55 |
| green22 | logistic 3.57 ✗ | logistic 12.34 ✗ | logistic 17.86 ✗ |

Why the ✗:

- **Test copy could not feed the design** (says nothing about the design): red100 and green100 (an input belt is
  side-loaded into the gear line inside the design, so the lane chosen at the entry is the wrong one), green22 (one
  iron entry had no room for a feed; 8 assemblers starved), circ6 (tileable: its iron arrives through an underground
  from the neighbouring copy; with an entrance added, two of four circuit assemblers ran).
- **Built for research:** red1, green1 (one basic inserter cannot bring a gear assembler 2 iron/s), red22 and
  green22 (bulk inserters without capacity bonus), the circuit builds (two fast inserters per circuit assembler
  move about 4 of the 4.5 cable/s it needs).
- **Harmless:** circ28 at research 7 makes its full 28.5/s; three inserters meant to take from the bus beside it
  point at nothing. red22 at research 7 makes exactly its 22.5/s label: its output belt is one express lane, so 2 of
  the 15 assemblers wait (it could make 24).
- **Solver layouts:** kosrg's job (red + green 1.5/s) makes green 1.31/s, red100's job (red 1.7/s) 1.40/s,
  green100's job 1.50 of 1.67/s, green1's 0.92 of 1/s, green22's 17.86 of 22.5/s. In each, some consumers are short
  of ingredients while their suppliers have spare capacity, and no inserter is stuck. The other nine solver layouts
  (acid, plastic, all circuits, red 1/s, 1.5/s and 22.5/s, green 1.5/s) run at plan.

## Patterns the community designs use

Counted by `analyze.py` (community / solver for the matching job):

| design | inserters per machine (C / S) | belt tiles per machine | undergrounds | splitters | direct insertion | long-handed over a belt | belts with 2+ items | belts served from both sides |
|---|---|---|---|---|---|---|---|---|
| kosrg | 2.1 / 3.1 | 5.6 / 10.9 | 0 / 84 | 0 / 6 | 2 / 3 | 0 / 11 | 2 / 0 | 3 / 9 |
| red1p5 | 2.1 / 3.0 | 5.6 / 9.4 | 10 / 28 | 2 / 1 | 0 / 1 | 4 / 10 | 1 / 0 | 2 / 3 |
| green1p5 | 2.1 / 3.1 | 5.3 / 10.0 | 12 / 38 | 0 / 4 | 2 / 2 | 6 / 8 | 1 / 0 | 2 / 6 |
| red100 | 2.1 / 3.1 | 5.2 / 11.4 | 15 / 64 | 1 / 3 | 0 / 1 | 4 / 9 | 1 / 0 | 2 / 7 |
| green100 | 2.0 / 3.1 | 4.7 / 10.2 | 14 / 86 | 1 / 5 | 7 / 4 | 13 / 9 | 2 / 0 | 2 / 9 |
| red1 | 2.0 / 3.0 | 6.0 / 10.5 | 0 / 24 | 0 / 2 | 0 / 1 | 0 / 5 | 1 / 0 | 0 / 3 |
| green1 | 2.0 / 2.9 | 4.7 / 9.2 | 4 / 58 | 0 / 3 | 5 / 3 | 0 / 5 | 1 / 0 | 1 / 5 |
| circ12 | 2.2 / 3.7 | 5.3 / 14.3 | 8 / 92 | 12 / 3 | 16 / 2 | 0 / 0 | 0 / 0 | 3 / 7 |
| circ6 | 2.2 / 3.7 | 6.0 / 11.4 | 12 / 28 | 5 / 0 | 8 / 1 | 0 / 0 | 0 / 0 | 0 / 5 |
| circ30 | 2.3 / 3.8 | 3.6 / 16.2 | 8 / 264 | 0 / 11 | 40 / 4 | 10 / 0 | 0 / 0 | 2 / 15 |
| circ28 | 2.2 / 3.8 | 5.9 / 16.2 | 44 / 264 | 28 / 11 | 38 / 4 | 0 / 0 | 0 / 0 | 3 / 15 |
| plastic24 | 2.0 / 2.0 | 5.2 / 6.4 | 3 / 18 | 0 / 1 | 0 / 0 | 0 / 0 | 0 / 0 | 2 / 2 |
| acid100 | 2.0 / 5.0 | 5.0 / 13.0 | 4 / 2 | 0 / 1 | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 1 |
| red22 | 2.3 / 3.1 | 7.4 / 18.9 | 9 / 98 | 2 / 5 | 0 / 0 | 0 / 6 | 1 / 0 | 0 / 5 |
| green22 | 2.7 / 3.1 | 7.5 / 16.2 | 20 / 146 | 3 / 3 | 0 / 0 | 0 / 3 | 2 / 0 | 1 / 7 |

What the numbers and the maps (`analyze.py --map`) show, design by design:

1. **Direct insertion chains for high-ratio intermediates.** Every circuit design puts a row of cable assemblers
   against a row of circuit assemblers with inserters straight from one into the other: 8 of 22 inserters in circ6,
   16 of 44 in circ12, 40 of 113 in circ30, 38 of 110 in circ28. The cable never touches a belt. The solver's layouts
   for the same jobs have 1–4 direct inserters and carry the cable on belts, which is most of their extra belt
   (11–16 belt tiles per machine against 4–6) and extra inserters (3.7–3.8 per machine against 2.2–2.3). Science designs
   do the same with their small intermediates: gears straight into the inserter and transport-belt assemblers
   (KoS, green1p5, green1, green100), cable into circuits (green1, green100).
2. **Rows of identical machines on one shared belt pair.** KoS puts 12 green-science assemblers in one row and 10
   red in the next, with one belt between them serving both rows; plastic24 is two columns of six chemical plants
   around one shared output belt; red22/green22 are one column of assemblers between two beacon columns with the
   input belts on one side and the output on the other. Belts run straight past every machine; there are almost no
   undergrounds (0 in KoS, 3 in plastic24) and no splitters. The solver scatters the same machines and links them
   with many short chains (38–264 undergrounds).
3. **Two items per belt, one per lane.** Every science design puts two ingredients on one belt: copper and gears
   for red science (red1, red1p5, red100, red22), inserters and transport belts for green (KoS, green1p5, green1,
   green100, green22). One inserter then feeds both ingredients, which is why community science builds need about
   2.0 inserters per machine (one in, one out) against the solver's 2.9–3.1. The gear (or inserter) assembler drops
   straight onto the lane the outside input leaves free.
4. **Long-handed inserters reaching a second belt.** Columns with two belts on each side, the near one tapped by a
   plain inserter and the far one by a long-handed inserter, give each machine four lanes (red1p5, green1p5, red100,
   green100, circ30). The solver already does this (5–9 per layout); it is not where the gap comes from.
5. **Fluids through the machines, belts outside.** acid100 is two chemical plants side by side with the pipes
   between them and the two belts on the outer edges: 6 × 7 tiles, every tile used, 4 pipes and 4 pipes-to-ground.
   The solver's layout for the same job is 12 × 11 with 22 pipes, 10 pipes-to-ground and 5 inserters per machine.
   plastic24 runs its gas manifold down the middle column between the inserters of the two plant columns.
6. **A fixed beaconed cross-section.** red22 and green22 are 13 tiles wide all the way down: beacon column, input
   belt, inserter, assembler column, inserter, output belt, beacon column (3 + 1 + 1 + 3 + 1 + 1 + 3). The belts fit
   inside the beacons' 3-tile reach, so every assembler gets 8 beacons and each beacon reaches 3.4 assemblers, with
   one straight belt per side and nothing to route. Length is the only thing that changes with the rate.
7. **What the community designs assume.** Several reach their label only with inserter capacity research: without
   it the circuit builds make 82–89 % (two fast inserters cannot move 4.5 cable/s between machines) and the
   assembler-1 science builds 46–47 % (one basic inserter cannot feed a gear assembler 2 iron/s). The solver sizes
   inserters for the research its job states (none here) and so needs no research; its shortfalls are of another
   kind (see [In game](#in-game)).

## What to change in the solver

In order of expected gain for the effort, each tied to the designs that show it:

1. **Direct-insertion cells for cable → circuit (and gear → inserter / belt).** *Evidence: circ6, circ12, circ30,
   circ28 (8–40 direct inserters, 2.2 inserters and 4–6 belt tiles per machine); the solver's layouts for the same
   jobs have 1–4 and 11–16.* Treat a producer–consumer group at its recipe ratio as one placement unit with fixed
   inner geometry: for circuits, 3 cable assemblers flush in a row against 2 circuit assemblers, with the inserter row
   between them (the layout every circuit design here uses, tiled for higher rates). The placer moves the cell as one
   big machine whose ports are the copper belt on one side and the iron belt and circuit output on the other; the
   router never sees the cable. A bigger direct-insertion bonus alone does not get there (see
   [Tuning experiments](#tuning-experiments)): the anneal has to find a 5-machine arrangement by single moves.
2. **Turn on two items per belt for science, after checking the lane facts in game.** *Evidence: all 9 science
   designs; KoS, red1p5, green1p5, red1, green1 and red22 ran at their rate in game with it.* `lane_sharing` exists
   but is opt-in until the drop-lane facts in docs/VERIFY.md (`v2-inserter-lanes`, `v13-splitter-lanes`,
   `v4-sideload-underground`) are confirmed; `tools/factorio-verify` can now confirm them. Then make it the default
   for consumers with two belt-fed solid ingredients and let the producer of one of them (gears, inserters) drop
   onto the free lane of the line that carries the other (copper, transport belts), as red1p5 and KoS do.
3. **Row templates for repeated machines.** *Evidence: KoS (rows of 12 and 10 on one shared belt), plastic24 (two
   columns of 6 around one output belt), red22/green22 (one column between beacon columns), red100/green100
   (columns facing a double belt).* For a recipe with 4 or more machines, place them flush in a straight row along
   an input belt pair and an output belt, both sides of a belt serving machines where there are two rows, and route
   only between rows. The existing `place.initial = "columns"` start is the nearest thing; it is not the default
   and, with the anneal free to break the columns, it did not reproduce rows (see the experiments).
4. **A beaconed cross-section template.** *Evidence: red22, green22 (13 wide: beacon, belt, inserter, machine,
   inserter, belt, beacon; 8 beacons per machine, 3.4 machines per beacon).* With `per_machine` 8 the lattice mode
   should place machines in single columns between beacon columns with one belt per side inside beacon reach,
   instead of annealing them; the community layout is optimal by construction and its length is the only free
   variable.
5. **Pipes through and between machines, belts outside.** *Evidence: acid100 (two plants, 6 × 7, 8 pipe pieces)
   against the solver's 12 × 11 with 32; plastic24 (gas manifold in the middle column between the inserters).* For
   chemical plants in a row, connect fluid boxes of neighbouring plants directly or with one pipe, put item belts
   on the far edges, and route pipes before belts in the row's corridor.
6. **Fewer placement units for large blocks.** *Evidence: circuit 30/s (50 machines) got 9 trials in 5 minutes and
   16 in 15, and stayed at 49 × 49 against the community's 22 × 47; green 22.5/s stayed at 55 × 55 after 15
   minutes.* More time does not help; each trial is too expensive. Templates 1–4 cut the number of placement units
   3–10 times, which is the fix; a start box from the template's estimate instead of twice the machine area would
   save the first minutes of shrinking.
7. **State research in benchmark jobs.** *Evidence: circ12/circ30/circ28 make 82–89 %, red1/green1 46–47 %, red22
   24 % of their label without inserter capacity research.* Community designs assume it; the solver's jobs default
   to none and so use more and faster inserters. Benchmarks should run both sides at the same research. Working
   without research is a real advantage of the solver's layouts; the five that fall short in game (see
   [In game](#in-game)) are a separate fault to fix first, since they would undercut any template too.

## Tuning experiments

Can existing settings close the gap? Same jobs, same budget (5 minutes, 3 threads), one change each: `lane` is
`lane_sharing: true`; `di20` raises the placer's direct-insertion bonus `place.delta` from 6 to 20; `cols` starts
from `place.initial: "columns"` with machines flush (`column_spacing` 0) and only column-keeping moves; `r7` sets
`research.inserter_capacity` 7, as the community designs assume.

| job | variant | box = area | belt tiles | inserters | direct insertion | belts with 2+ items |
|---|---|---|---|---|---|---|
| red_1p5_am2 | base | 18×18 = 324 | 103 | 33 | 1 | 0 |
| red_1p5_am2 | lane | 18×18 = 324 | 87 | 33 | 2 | 1 |
| red_1p5_am2 | di20 | 19×19 = 361 | 117 | 33 | 1 | 0 |
| red_1p5_am2 | cols | 19×19 = 361 | 114 | 33 | 1 | 0 |
| green_1p5_am2 | base | 24×23 = 552 | 160 | 50 | 2 | 0 |
| green_1p5_am2 | lane | 23×23 = 529 | 130 | 49 | 4 | 1 |
| green_1p5_am2 | di20 | 24×23 = 552 | 169 | 49 | 3 | 0 |
| green_1p5_am2 | cols | 24×25 = 600 | 170 | 49 | 2 | 0 |
| circuit_6_am2_fast | base | 19×18 = 342 | 114 | 37 | 1 | 0 |
| circuit_6_am2_fast | lane | 19×19 = 361 | 111 | 39 | 2 | 0 |
| circuit_6_am2_fast | di20 | 18×19 = 342 | 89 | 34 | 5 | 0 |
| circuit_6_am2_fast | cols | 20×18 = 360 | 104 | 38 | 0 | 0 |
| circuit_6_am2_fast | r7 | 17×17 = 289 | 70 | 24 | 1 | 0 |
| circuit_12_am2 | base | 29×29 = 841 | 287 | 74 | 2 | 0 |
| circuit_12_am2 | lane | 29×29 = 841 | 287 | 74 | 2 | 0 |
| circuit_12_am2 | di20 | 31×29 = 899 | 316 | 74 | 8 | 0 |
| circuit_12_am2 | cols | 43×43 = 1849 | 361 | 76 | 0 | 0 |
| circuit_12_am2 | r7 | 27×25 = 675 | 188 | 49 | 1 | 0 |
| rg_1p5_am2 | base | 32×30 = 960 | 294 | 84 | 3 | 0 |
| rg_1p5_am2 | lane | 32×30 = 960 | 328 | 84 | 6 | 1 |
| red_1_am1 | base | 19×19 = 361 | 116 | 33 | 1 | 0 |
| red_1_am1 | r7 | 18×17 = 306 | 108 | 32 | 2 | 0 |
| green_1_am1 | base | 25×25 = 625 | 175 | 56 | 3 | 0 |
| green_1_am1 | r7 | 25×25 = 625 | 166 | 53 | 5 | 0 |

- **Research** helps most: 15–20 % less area for circuits, a third fewer inserters, still 1.3–1.4 times the
  community area. It does not change the structure.
- **Lane sharing** saves belt (−15 to −19 % on red and green 1.5/s) but uses it on one belt per layout and keeps the
  area. The two lane-sharing layouts ran at plan in game (red 1.5/s, green 1.5/s).
- **A bigger direct-insertion bonus** finds more pairs (5 and 8 instead of 1 and 2 on circuits) but not the
  community's 8 and 16, and no smaller box: single-machine moves cannot assemble a 3 + 2 group.
- **Columns** came out worse everywhere, much worse on circuit 12/s (9 trials in the budget).

None of the knobs reproduces the patterns above; they have to be built in (recommendations 1–4). In game, the
`r7` layouts for circuits 6/s and 12/s and red 1/s ran at plan with that research; green 1/s made its full 1/s with one gear inserter
waiting on a full belt (surplus gears, harmless).

## Caveats

- Fifteen designs, picked by hand from six popular books; well-regarded is "many favourites", not "best possible".
  Some are tileable or bus-fed modules meant to sit next to copies of themselves or a main bus, which flatters their
  box (no input routing) compared with the solver's self-contained block with ports.
- The solver ran 5 minutes on 3 threads per job, the README's examples 5–15 minutes on 10. The large jobs were rerun
  for 15 minutes on 4 threads: circuits 30/s stayed 49 × 49 (16 trials instead of 9), green 1.67/s went from 34 to
  33 tiles a side, green 22.5/s stayed 55 × 55, red 22.5/s went from 55 × 55 to 45 × 45 (2.6 times the community
  area per item/s instead of 3.8). The tables use the 5-minute runs.
- The automatic test copy cannot feed every design: designs whose input belt is side-loaded into another belt inside
  them (lanes change) and tileable designs that take an input from their neighbour through an underground came out
  starved, which says nothing about the designs. Those rows are marked.
- The community plastic and sulfuric acid designs were not run in game: `testcopy.py` does not feed pipes yet (the
  solver's layouts for those jobs were, and pass).
- Rates assume every machine runs at full speed; research productivity is off (the jobs have none either).

## Reproducing

```bash
python3 tools/blueprints/fetch.py search "green circuit" --version 2.0
```

```bash
python3 tools/blueprints/fetch.py get -- -O5tdYHBzmLLrYWeydMv
```

```bash
python3 tools/blueprints/analyze.py ~/.cache/factorio-optimizer/blueprints/-O5tdYHBzmLLrYWeydMv.json --list
```

```bash
python3 tools/blueprints/analyze.py ~/.cache/factorio-optimizer/blueprints/-O5tdYHBzmLLrYWeydMv.json --pick 0 --map
```

```bash
for j in docs/community_benchmark/jobs/*.json; do ./optimize --config=$j --out=solves/$(basename $j .json) --plain; done
```

```bash
python3 tools/blueprints/testcopy.py ~/.cache/factorio-optimizer/blueprints/-O5tdYHBzmLLrYWeydMv.json --pick 0 --out tc/circ12.txt
```

```bash
python3 tools/factorio-verify/verify.py tc/circ12.txt --job research7.json
```

```bash
python3 tools/blueprints/benchmark.py docs/community_benchmark/designs.json --solves solves --verify 0=tc0 --verify 7=tc7
```

`designs.json` lists each design (key, `pick` path, matching job); add rows to grow the benchmark. The verify step
needs `tools/factorio-verify` (branch `claude/zealous-cray-a0cdb6`) and `tools/factorio-exporter`.
