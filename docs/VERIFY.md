# In-game verification kit

The generator relies on a handful of Factorio 2.0 facts that were taken from the Lua API docs or from memory
and are marked `VERIFY` in `src/main/kotlin/data/GameConstants.kt`. Each blueprint in `docs/verify/` tests one
of them. Import them in an editor or sandbox save (`/editor`), add power (an electric-energy-interface next
to the medium pole), and record the result below. Fix `GameConstants.kt` if a check fails.

Regenerate the strings with:

```sh
./gradlew run --args="--verify-kit=docs/verify"
```

| File | Fact | What to look for | Result |
|------|------|------------------|--------|
| `v1-inserter-direction.txt` | An inserter's blueprint direction points at its pickup side | Plates move from the infinity chest into the steel chest | ☐ |
| `v2-inserter-lanes.txt` | Which lane an inserter drops on (perpendicular, parallel away, parallel toward, curve) | Note the lane in each row | ☐ |
| `v3-underground-pairing.txt` | Pairing when an opposite-facing pair of the same tier sits inside another pair's span | Do plates reach the chest at the right? | ☐ |
| `v4-sideload-underground.txt` | Sideloading into an underground entrance | Which lane comes out of the exit | ☐ |
| `v5-poles-without-wires.txt` | Poles in a blueprint without a `wires` array | Are the two poles connected after building? (The generator always writes wires.) | ☐ |
| `v6-infinity-settings.txt` | `infinity_settings` format for pipes and chests | Pipe set to sulfuric acid, chest to iron plates | ☐ |
| `v7-underground-directions.txt` | Underground `type` and direction in all four directions | Items come out of every exit | ☐ |
| `v8-inserter-rates.txt` | Chest-to-chest inserter rates | Items per minute per row (expected 50, 72, 139, 139 × hand size) | ☐ |
| `v9-fluid-input-side.txt` | Assembler fluid input is the middle of the side it faces | All four machines receive acid | ☐ |
| `v10-chemical-plant-connections.txt` | Chemical plant connections in all four directions | All four lubricant plants run | ☐ |
| `v11-refinery-connections.txt` | Refinery connections, rotation and mirroring | All three refineries run (north, north mirrored, east) | ☐ |
| `v12-pipe-to-ground-span.txt` | Pipe-to-ground gap limit (9 tiles) and direction | Only the top plant runs | ☐ |
| `v13-splitter-lanes.txt` | A splitter keeps lanes | Both outputs: iron south lane, copper north lane | ☐ |
| `v14-assembler-fluid-sides.txt` | Whether an assembler takes fluid on its output side too | Does the right assembler fill? | ☐ |
| `v15-void-chest.txt` | Infinity chest with no filters deletes items (test-mode output void) | The chest stays empty, the inserter keeps working | ☐ |
| `v16-underground-weaving.txt` | Undergrounds of different tiers do not pair, so they can cross the same row (weaving) | Iron leaves at x 6, copper east of x 7 | ☐ |
| `v17-space-age-fluid-connections.txt` | Foundry, electromagnetic plant, cryogenic plant and biochamber connections, rotation, and which box each recipe fluid takes | Every pipe connects; each fluid shows in its box | ☐ |
| `v18-filtered-output-inserters.txt` | Inserter filters in blueprints split a machine's products (recycler, crusher, uranium) | Each chest holds only its item | ☐ |
| `v20-beacons.txt` | Beacon supply area (3 tiles beyond its edge), the 2.0 profile 1/√n with effectivity 1.5, module item requests (inventory 1 beacon, 4 machine) | Machine speeds 2.375, 3.15 and 0.5, productivity +40 %, every slot filled | ☐ |
| `v19-cracking-on-surplus.txt` | Storage tank connections, red wire ids, a crafting machine's circuit condition (`control_behavior`), wire reach | The tank fills and the plant runs; with the heavy oil pipe at 40 % the plant stops | ☐ |

Also from the plan's list:

- [ ] Wire tuple `[a, 5, b, 5]` at the blueprint's top level connects poles (any generated layout shows this).
- [ ] Belt pickup derate (default 0.7): time an inserter taking from a full belt into a chest.
- [ ] A belt directly after an underground exit may turn (`turn_after_ug_exit` in tuning; set it to false if not).

The milestone M1 check, a hand-written gear layout (`Fixtures.t1Hand` in the tests), is written to
`docs/verify/t1-hand.txt` by the same command: import it, feed iron plates into the west belt, and gears
should collect in the steel chest at about 1/s.
