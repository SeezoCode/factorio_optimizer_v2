-- Exports what factorio_optimizer needs from the loaded game (base game and any mods) to
-- script-output/factorio-optimizer-data.json. The recipes, items, fluids, quality and crafting machine parts keep the
-- layout of the optimizer's recipes.json; "fluid_boxes" on machines and the "logistics" section are what lets it use
-- machines, belts, inserters and poles it has no built-in table for.

local OUT = "factorio-optimizer-data.json"
local DIRS = { [defines.direction.north] = "N", [defines.direction.east] = "E", [defines.direction.south] = "S", [defines.direction.west] = "W" }

local function keys(t)
  if t == nil then return nil end
  local out = {}
  for k, _ in pairs(t) do out[#out + 1] = k end
  table.sort(out)
  if #out == 0 then return nil end
  return out
end

local function name_of(o) if o then return o.name end return nil end
local function pos(p) if p == nil then return nil end return { x = p.x or p[1], y = p.y or p[2] } end

local data, pending, pending_count, started_tick = nil, nil, 0, nil

local function write()
  helpers.write_file(OUT, helpers.table_to_json(data))
  game.print("factorio-optimizer-exporter: wrote script-output/" .. OUT)
  log("factorio-optimizer-exporter: wrote " .. OUT)
  data, pending, pending_count, started_tick = nil, nil, 0, nil
end

-- Translated names arrive later (on_string_translated); the file is written once all have, or after a few seconds.
local function translate(player, localised, target)
  local id = player.request_translation(localised)
  if id then pending[id] = target; pending_count = pending_count + 1 else target.translated_name = target.name end
end

local function group_entry(groups, g)
  if g and not groups[g.name] then
    groups[g.name] = { name = g.name, type = g.type, order = g.order }
    if g.type == "item-group" then groups[g.name].order_in_recipe = g.order_in_recipe end
  end
  return name_of(g)
end

local function fluid_box_entry(fb)
  local conns = {}
  for _, c in pairs(fb.pipe_connections or {}) do
    local ps = {}
    for i, p in pairs(c.positions or {}) do ps[i] = pos(p) end
    conns[#conns + 1] = {
      flow_direction = c.flow_direction, connection_type = c.connection_type, direction = DIRS[c.direction],
      positions = ps, max_underground_distance = c.max_underground_distance, connection_category = c.connection_category,
    }
  end
  return { index = fb.index, production_type = fb.production_type, filter = name_of(fb.filter), connections = conns }
end

local function fluid_boxes(e)
  local out = {}
  for _, fb in pairs(e.fluidbox_prototypes or {}) do
    local conns = {}
    for _, c in pairs(fb.pipe_connections or {}) do
      local ps = {}
      for i, p in pairs(c.positions or {}) do ps[i] = pos(p) end
      conns[#conns + 1] = {
        flow_direction = c.flow_direction, connection_type = c.connection_type, direction = DIRS[c.direction],
        positions = ps, max_underground_distance = c.max_underground_distance, connection_category = c.connection_category,
      }
    end
    out[#out + 1] = { index = fb.index, production_type = fb.production_type, filter = name_of(fb.filter), connections = conns }
  end
  if #out == 0 then return nil end
  return out
end


-- Which fluid box the game gives each fluid of a recipe. A recipe without fluidbox_index leaves that to the game, and
-- it does not simply take the machine's boxes in order (Py's fbreactor puts formamide's three fluids in input boxes 1,
-- 3 and 4 of 4). Each machine with several fluid boxes of a kind is built on a scratch surface with each recipe it can
-- craft; where a fluid's box is not the one the optimizer assumes (fluidbox_index, else the n-th fluid in the n-th box
-- of its kind), data.fluid_box_assignment[recipe][machine][fluid] = the box's 1-based number among its kind
-- ("in" or "out" prefix: "in:3").
local function probe_fluid_boxes(player)
  local by_category = {}
  for _, r in pairs(prototypes.recipe) do
    local fluids = 0
    for _, i in pairs(r.ingredients) do if i.type == "fluid" then fluids = fluids + 1 end end
    for _, p in pairs(r.products) do if p.type == "fluid" then fluids = fluids + 1 end end
    if fluids > 0 then
      for c, _ in pairs(r.categories or { [r.category] = true }) do
        local cat = type(c) == "number" and r.categories[c] or c
        by_category[cat] = by_category[cat] or {}
        table.insert(by_category[cat], r)
      end
    end
  end
  local ok_surface, surface = pcall(function() return game.create_surface("optimizer-fluid-probe", { width = 64, height = 64 }) end)
  if not ok_surface then return end
  surface.generate_with_lab_tiles = true
  surface.request_to_generate_chunks({ 0, 0 }, 2)
  surface.force_generate_chunk_requests()
  player.force.enable_all_recipes()
  local out, probed = {}, 0
  for _, e in pairs(prototypes.entity) do
    if e.type == "assembling-machine" and not e.hidden and e.crafting_categories then
      -- Boxes per kind, in prototype order: [proto index] = number among its kind.
      -- The energy source's own box (a machine burning a fuel fluid) is listed too, numbered 1 again: not a recipe box.
      local eb = e.fluid_energy_source_prototype and e.fluid_energy_source_prototype.fluid_box
      local function is_energy(fb)
        if not eb then return false end
        local same = false
        pcall(function() same = (fb == eb) end)
        if same then return true end
        local a, b = (fb.pipe_connections or {})[1], (eb.pipe_connections or {})[1]
        return a ~= nil and b ~= nil and a.direction == b.direction and a.positions[1].x == b.positions[1].x and a.positions[1].y == b.positions[1].y
          and fb.production_type == eb.production_type and #(fb.pipe_connections) == #(eb.pipe_connections)
      end
      local kind_no, count = {}, { input = 0, output = 0 }
      for _, fb in pairs(e.fluidbox_prototypes or {}) do
        if not is_energy(fb) then
          local k = fb.production_type == "output" and "output" or "input"
          count[k] = count[k] + 1
          kind_no[fb.index] = count[k]
        end
      end
      if count.input > 1 or count.output > 1 then
        local ok, ent = pcall(function() return surface.create_entity { name = e.name, position = { 0, 0 }, force = player.force } end)
        if ok and ent then
          local seen = {}
          for cat, _ in pairs(e.crafting_categories) do
            for _, r in pairs(by_category[cat] or {}) do
              if not seen[r.name] then
                seen[r.name] = true
                if pcall(function() ent.set_recipe(r.name) end) then
                  probed = probed + 1
                  -- What the optimizer assumes: fluidbox_index, else order among the recipe's fluids of that kind. A
                  -- fluid the recipe has twice (Py's gas separators: uf6 out at two enrichments) is "out:uf6" the first
                  -- time and "out:uf6/2" the second: the game fills the boxes filtered to it in the recipe's order.
                  local assumed, times = {}, {}
                  local function key_of(k, name)
                    times[k .. name] = (times[k .. name] or 0) + 1
                    return k .. ":" .. name .. (times[k .. name] > 1 and ("/" .. times[k .. name]) or "")
                  end
                  local n = 0
                  for _, i in pairs(r.ingredients) do
                    if i.type == "fluid" then n = n + 1; assumed[key_of("in", i.name)] = i.fluidbox_index or n end
                  end
                  n = 0
                  for _, p in pairs(r.products) do
                    if p.type == "fluid" then n = n + 1; assumed[key_of("out", p.name)] = p.fluidbox_index or n end
                  end
                  times = {}
                  for i = 1, (ent.fluids_count or 0) do
                    local f = ent.get_fluid_filter(i)
                    local okp, proto = pcall(function() return ent.get_fluid_box_prototype(i) end)
                    if f and okp and proto then
                      if proto.index == nil and proto[1] then proto = proto[1] end
                      if is_energy(proto) then proto = nil end
                    end
                    if f and okp and proto then
                      local k = proto.production_type == "output" and "out" or "in"
                      local no = kind_no[proto.index]
                      local key = key_of(k, f.name)
                      if no and assumed[key] ~= nil and assumed[key] ~= no then
                        out[r.name] = out[r.name] or {}
                        out[r.name][e.name] = out[r.name][e.name] or {}
                        out[r.name][e.name][key] = no
                      end
                    end
                  end
                end
              end
            end
          end
          ent.destroy()
        end
      end
    end
  end
  data.fluid_box_assignment = out
  log("factorio-optimizer-exporter: probed " .. probed .. " machine and recipe pairs for fluid boxes")
  game.delete_surface(surface)
end

local CRAFTING = { ["assembling-machine"] = true, ["furnace"] = true, ["rocket-silo"] = true, ["beacon"] = true, ["boiler"] = true }

local function export(player)
  if data ~= nil then return end
  data, pending, pending_count, started_tick = {}, {}, 0, game.tick
  data.game_version = "2.1"
  data.exporter = "factorio-optimizer-exporter 0.1.0"
  local mods = {}
  for name, version in pairs(script.active_mods) do mods[name] = version end
  data.mods = mods

  local groups = {}
  data.groups = groups

  data.quality = {}
  for _, q in pairs(prototypes.quality) do
    data.quality[q.name] = {
      name = q.name, level = q.level, next = name_of(q.next), next_probability = q.next_probability,
      chain_probability = q.chain_probability,
      beacon_power_usage_multiplier = q.beacon_power_usage_multiplier,
      mining_drill_resource_drain_multiplier = q.mining_drill_resource_drain_multiplier,
      module_quality_multiplier = q.module_quality_multiplier, module_speed_multiplier = q.module_speed_multiplier,
      module_productivity_multiplier = q.module_productivity_multiplier, module_consumption_multiplier = q.module_consumption_multiplier,
      group = group_entry(groups, q.group), subgroup = group_entry(groups, q.subgroup),
    }
    translate(player, q.localised_name, data.quality[q.name])
  end
  data.quality_names = {}
  local qn = "normal"
  while qn do
    data.quality_names[#data.quality_names + 1] = qn
    qn = data.quality[qn] and data.quality[qn].next
  end

  data.recipes = {}
  for _, r in pairs(player.force.recipes) do
    local p = r.prototype
    data.recipes[r.name] = {
      name = r.name, categories = r.categories, category = r.categories[1],
      ingredients = r.ingredients, products = r.products, main_product = p.main_product,
      allowed_effects = p.allowed_effects, maximum_productivity = p.maximum_productivity,
      allowed_module_categories = p.allowed_module_categories and keys(p.allowed_module_categories) or nil,
      energy = r.energy, order = r.order, group = group_entry(groups, r.group), subgroup = group_entry(groups, r.subgroup),
      enabled = r.enabled, hidden = p.hidden, productivity_bonus = r.productivity_bonus,
    }
    translate(player, r.localised_name, data.recipes[r.name])
  end

  data.items = {}
  for _, i in pairs(prototypes.item) do
    data.items[i.name] = {
      name = i.name, type = i.type, order = i.order, group = group_entry(groups, i.group), subgroup = group_entry(groups, i.subgroup),
      stack_size = i.stack_size, weight = i.weight, fuel_categories = i.fuel_categories, fuel_value = i.fuel_value,
      module_effects = i.module_effects, rocket_launch_products = i.rocket_launch_products, flags = keys(i.flags),
      place_result = name_of(i.place_result), burnt_result = name_of(i.burnt_result),
    }
    if i.type == "module" then data.items[i.name].category = i.category end
    translate(player, i.localised_name, data.items[i.name])
  end

  data.fluids = {}
  for _, f in pairs(prototypes.fluid) do
    data.fluids[f.name] = { name = f.name, order = f.order, group = group_entry(groups, f.group), subgroup = group_entry(groups, f.subgroup), fuel_value = f.fuel_value,
      default_temperature = f.default_temperature, max_temperature = f.max_temperature }
    translate(player, f.localised_name, data.fluids[f.name])
  end

  data.entities = {}
  -- What can be mined or pumped (raw resources): each resource with what mining it gives and what it needs.
  data.resources = {}
  for _, e in pairs(prototypes.entity) do
    if e.type == "resource" and not e.hidden then
      local mp = e.mineable_properties
      data.resources[e.name] = { name = e.name, category = e.resource_category, infinite = e.infinite_resource,
        products = mp and mp.products, required_fluid = mp and mp.required_fluid, fluid_amount = mp and mp.fluid_amount }
    end
  end
  -- Mod data (2.0 mod-data prototypes) as the mods wrote it, e.g. Pyanodon's farm buildings and their modules.
  data.mod_data = {}
  for name, md in pairs(prototypes.mod_data or {}) do
    local ok, d = pcall(function() return md.data end)
    if ok then data.mod_data[name] = { data_type = md.data_type, data = d } end
  end
  local logistics = { belts = {}, undergrounds = {}, splitters = {}, inserters = {}, poles = {}, pipes = {}, pipes_to_ground = {} }
  data.logistics = logistics
  for _, e in pairs(prototypes.entity) do
    local t = e.type
    if CRAFTING[t] then
      local energy, drain, source, fuel = nil, nil, nil, nil
      if e.electric_energy_source_prototype and e.energy_usage then
        energy = e.energy_usage * 60; drain = e.electric_energy_source_prototype.drain * 60; source = "electric"
      elseif e.burner_prototype and e.energy_usage then
        energy = e.energy_usage * 60; drain = 0; source = "burner"; fuel = keys(e.burner_prototype.fuel_categories)
      elseif e.fluid_energy_source_prototype and e.energy_usage then
        energy = e.energy_usage * 60; drain = 0; source = "fluid"   -- burns a fuel fluid (Py's glassworks)
      else
        energy = 0; drain = 0; source = "void"   -- needs no energy (or one the optimizer has no use for)
      end
      local info = {
        name = e.name, type = t, order = e.order, group = name_of(e.group), subgroup = name_of(e.subgroup),
        crafting_speed = {}, supply_area_distance = {},
        crafting_categories = keys(e.crafting_categories), allowed_effects = keys(e.allowed_effects),
        module_inventory_size = e.module_inventory_size, allowed_module_categories = keys(e.allowed_module_categories), fixed_recipe = name_of(e.fixed_recipe), effect_receiver = e.effect_receiver,
        rocket_parts_required = e.rocket_parts_required,
        distribution_effectivity = e.distribution_effectivity,
        distribution_effectivity_bonus_per_quality_level = e.distribution_effectivity_bonus_per_quality_level,
        energy_consumption = energy, drain = drain, energy_source = source, fuel_categories = fuel,
        width = e.tile_width, height = e.tile_height, flags = keys(e.flags), hidden = e.hidden,
        fluid_boxes = fluid_boxes(e), fluid_boxes_off_when_no_fluid_recipe = e.fluid_boxes_off_when_no_fluid_recipe,
        -- Where it puts what it makes, from its centre facing north (Py's wood processing units drop onto the tile in
        -- front of them): nothing may stand there but what is meant to take it.
        vector_to_place_result = (function()
          local ok, v = pcall(function() return e.vector_to_place_result end)
          if ok and v then return { x = v.x or v[1], y = v.y or v[2] } end
        end)(),
      }
      for _, q in pairs(data.quality_names) do
        info.crafting_speed[q] = e.get_crafting_speed(q)
        info.supply_area_distance[q] = e.get_supply_area_distance(q)
      end
      if e.fluid_energy_source_prototype then
        local fe = e.fluid_energy_source_prototype
        info.fluid_energy = { fluid_box = fluid_box_entry(fe.fluid_box), burns_fluid = fe.burns_fluid, effectivity = fe.effectivity,
          fluid_usage_per_tick = fe.fluid_usage_per_tick, scale_fluid_usage = fe.scale_fluid_usage }
      end
      if next(info.crafting_speed) == nil then info.crafting_speed = nil end
      if next(info.supply_area_distance) == nil then info.supply_area_distance = nil end
      data.entities[e.name] = info
      translate(player, e.localised_name, info)
    elseif t == "transport-belt" then
      logistics.belts[#logistics.belts + 1] = { name = e.name, speed = e.belt_speed, items_per_second = e.belt_speed * 480,
        underground = name_of(e.related_underground_belt), hidden = e.hidden }
    elseif t == "underground-belt" then
      logistics.undergrounds[#logistics.undergrounds + 1] = { name = e.name, speed = e.belt_speed, max_distance = e.max_underground_distance, hidden = e.hidden }
    elseif t == "splitter" then
      logistics.splitters[#logistics.splitters + 1] = { name = e.name, speed = e.belt_speed, width = e.tile_width, height = e.tile_height, hidden = e.hidden }
    elseif t == "inserter" then
      logistics.inserters[#logistics.inserters + 1] = {
        name = e.name, rotation_speed = e.get_inserter_rotation_speed(), extension_speed = e.get_inserter_extension_speed(),
        pickup = pos(e.inserter_pickup_position), drop = pos(e.inserter_drop_position), bulk = e.bulk, filter_count = e.filter_count,
        stack_size_bonus = e.inserter_stack_size_bonus, energy_source = e.burner_prototype and "burner" or "electric", hidden = e.hidden,
      }
    elseif t == "electric-pole" then
      logistics.poles[#logistics.poles + 1] = { name = e.name, width = e.tile_width, height = e.tile_height,
        supply_area_distance = e.get_supply_area_distance(), max_wire_distance = e.get_max_wire_distance(), hidden = e.hidden }
    elseif t == "pipe" then
      logistics.pipes[#logistics.pipes + 1] = { name = e.name, hidden = e.hidden }
    elseif t == "pipe-to-ground" then
      local max = nil
      for _, fb in pairs(e.fluidbox_prototypes or {}) do
        for _, c in pairs(fb.pipe_connections or {}) do
          if c.connection_type == "underground" then max = c.max_underground_distance end
        end
      end
      logistics.pipes_to_ground[#logistics.pipes_to_ground + 1] = { name = e.name, max_distance = max, hidden = e.hidden }
    end
  end
  probe_fluid_boxes(player)
  if pending_count == 0 then write() end
end

script.on_event(defines.events.on_string_translated, function(event)
  if pending == nil or pending[event.id] == nil then return end
  local target = pending[event.id]
  target.translated_name = event.translated and event.result or target.name
  pending[event.id] = nil
  pending_count = pending_count - 1
  if pending_count == 0 then write() end
end)

commands.add_command("export_optimizer_data", "Writes script-output/" .. OUT .. " for factorio_optimizer.", function(command)
  export(game.get_player(command.player_index))
end)

-- By itself once a player is in the game; translations that never arrive do not hold the file back.
script.on_nth_tick(60, function()
  if data ~= nil then
    if started_tick and game.tick - started_tick > 600 then
      for _, t in pairs(pending) do t.translated_name = t.translated_name or t.name end
      write()
    end
    return
  end
  if storage.exported then return end
  local p = game.players[1]
  if p and p.connected then
    storage.exported = true
    export(p)
  end
end)
