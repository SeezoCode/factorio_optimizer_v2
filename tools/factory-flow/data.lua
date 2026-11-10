-- A belt cannot be stopped by script, but one on a circuit network stops by its enable condition. Each belt gate gets
-- this anchor under it: an invisible constant combinator, wired to the belt, that gives no signal ("signal-A > 0" never
-- holds, so the gate stands while its enable condition is on).
local util = require("util")

local function blank4() return { north = util.empty_sprite(), east = util.empty_sprite(), south = util.empty_sprite(), west = util.empty_sprite() } end

local anchor = table.deepcopy(data.raw["constant-combinator"]["constant-combinator"])
anchor.name = "factory-flow-gate-anchor"
anchor.flags = { "placeable-off-grid", "not-on-map", "not-blueprintable", "not-deconstructable", "not-repairable",
  "not-flammable", "no-copy-paste", "not-in-kill-statistics", "not-selectable-in-game", "not-upgradable" }
anchor.hidden = true
anchor.hidden_in_factoriopedia = true
anchor.selectable_in_game = false
anchor.minable = nil
anchor.fast_replaceable_group = nil
anchor.next_upgrade = nil
anchor.corpse = nil
anchor.dying_explosion = nil
anchor.collision_mask = { layers = {} }
anchor.selection_box = nil
anchor.draw_circuit_wires = false
anchor.sprites = blank4()
anchor.activity_led_sprites = blank4()
anchor.activity_led_light = nil
anchor.created_effect = nil
data:extend({ anchor })
