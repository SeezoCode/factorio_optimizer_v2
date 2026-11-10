# Factory flow (mod)

A Factorio 2.1 mod that keeps a factory laid out by the optimizer near its plan. Deep factories (Py's above all) start
badly without it: early machines take everything, loops starve, and by-products pass their users on the way out. The
in-game check showed the same rules work as `verify.py --flow`. This mod is those rules for your own game.

Once a second, for the machines of a tagged blueprint:

- **Pull**: a machine runs while a machine it feeds (along its output belts, or by hand) waits for one of its products,
  or while it makes a target. Machines whose outputs could not be traced always run. What a machine's fuel burns to
  (ash) counts as one of its products.
- **Quotas**: a machine that has taken more of an item than its plan allows so far (planned intake × 1.5, plus two
  crafts) has the inserters bringing that item stopped while a machine still behind its own plan waits for it, anywhere
  in the factory. For a fluid, the machine itself stops. A machine nobody behind plan waits behind takes what it likes.
- **Gates**: on each overflow port's belt, the belt just past the last machine taking from it stops while a machine on
  that belt waits, or while no machine putting onto it stands with a full output. Items queue in front of their users,
  and the overflow only takes what would otherwise stop a maker.

Machines the mod switches off show "Disabled by script". Each rule can be turned off in the map settings (Mod settings →
Map), as can the whole mod; turning a setting off starts everything the mod stopped again.

## Use

1. Tag the blueprint (it carries each machine's planned intake, the target makers and the overflow belts):

   ```bash
   python3 tools/factory-flow/tag.py solves/<run> --job jobs/<job>.json
   ```

   This writes `solves/<run>/flow_blueprint.txt` from the run's `best_output_string.txt`, with the run's `plan.json` and
   `ports.json`.
2. Pack the mod and put the zip in Factorio's mods folder (on a Mac `~/Library/Application Support/factorio/mods`):

   ```bash
   python3 tools/factory-flow/pack.py
   ```

3. Build the tagged blueprint with the mod enabled. The mod learns its machines from the build (the tags travel with the
   ghosts), so a factory built before the mod was installed, or from an untagged blueprint, is left alone.
   `/factory-flow` in the console says what it is doing.

Loops (barrels, cages, Py's bricks) still need their carriers put in once by hand, as the blueprint's item requests
say. A machine replaced by an upgrade loses its tags.

## Tested

`verify.py <run> --job <job> --flow-mod` builds the tagged test copy with this mod installed and runs it as the
in-game check does.
