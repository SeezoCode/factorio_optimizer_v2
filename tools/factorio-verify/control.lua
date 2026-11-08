-- Builds a factorio_optimizer "test copy" blueprint for real and measures it. The blueprint string arrives in pieces
-- (/verify_append, RCON packets are small), /verify_start builds it on a lab-tile surface, powers every electric
-- network with an energy interface, runs the game for a warm-up and a measuring window and writes
-- script-output/factorio-optimizer-verify.json. verify.py drives this over RCON on a headless server.
--
-- The test copy feeds every input port with a loader from an infinity chest and empties every output port with a
-- loader into an infinity chest (fluids: infinity pipes, a drain at outputs). To count what flows, the chests become
-- plain stores: inputs are topped up by script every tick (what was added is what the factory took) and outputs are
-- emptied every tick (what was removed is what the factory made). Fluid drains are emptied the same way.

local REPORT = "factorio-optimizer-verify.json"
-- Ticks between status samples while measuring: a prime, so no machine or inserter cycle stays in step with it.
local SAMPLE = 47

local STATUS = {}
for k, v in pairs(defines.entity_status) do STATUS[v] = k end

local CRAFTERS = { ["assembling-machine"] = true, ["furnace"] = true, ["rocket-silo"] = true }
local BELTS = { ["transport-belt"] = true, ["underground-belt"] = true, ["splitter"] = true }
local LOADERS = { ["loader"] = true, ["loader-1x1"] = true }

local function reply(t)
  local s = helpers.table_to_json(t)
  if rcon then rcon.print(s) end
  log("factorio-optimizer-verify: " .. s)
end

local function str(x)
  if type(x) == "string" then return x end
  if x == nil then return nil end
  return x.name
end

local function round(v, step) return math.floor(v / step + 0.5) * step end

local function bp_pos(run, p)
  return { x = round(p.x - run.dx, 0.5), y = round(p.y - run.dy, 0.5) }
end

local function add(t, k, n) t[k] = (t[k] or 0) + n end

-- "name", or "name@quality" for anything but normal (as the solver writes items in ports.json).
local function key(name, quality)
  quality = str(quality)
  if quality and quality ~= "normal" then return name .. "@" .. quality end
  return name
end

-- Items on a belt as one string, so two snapshots compare by equality: unchanged means nothing on it moved.
local function belt_key(e)
  local parts = {}
  for i = 1, e.get_max_transport_line_index() do
    local ok, items = pcall(function() return e.get_transport_line(i).get_detailed_contents() end)
    if ok then
      for _, it in pairs(items) do parts[#parts + 1] = i .. ":" .. it.unique_id .. "@" .. string.format("%.3f", it.position) end
    end
  end
  if #parts == 0 then return nil end
  return table.concat(parts, ",")
end

local function belt_items(e)
  local out = {}
  for i = 1, e.get_max_transport_line_index() do
    for _, it in pairs(e.get_transport_line(i).get_contents()) do add(out, key(it.name, it.quality), it.count) end
  end
  return out
end

local function set_research(force, params, warnings)
  force.enable_all_recipes()
  for name, _ in pairs(prototypes.quality) do
    if name ~= "quality-unknown" then pcall(function() force.unlock_quality(name) end) end
  end
  local level = params.inserter_capacity or 0
  for i = 1, level do
    local t = force.technologies["inserter-capacity-bonus-" .. i]
    if t then t.researched = true else warnings[#warnings + 1] = "no technology inserter-capacity-bonus-" .. i end
  end
  if (params.belt_stack_size or 1) > 1 then force.belt_stack_size_bonus = params.belt_stack_size - 1 end
  for name, bonus in pairs(params.productivity or {}) do
    if force.recipes[name] then force.recipes[name].productivity_bonus = bonus
    else warnings[#warnings + 1] = "no recipe " .. name .. " for productivity research" end
  end
end

local function surface_for(radius_chunks)
  local name = "verify"
  if game.surfaces[name] then game.delete_surface(name) name = "verify-" .. game.tick end
  local s = game.create_surface(name)
  s.generate_with_lab_tiles = true
  s.always_day = true
  s.request_to_generate_chunks({ 0, 0 }, radius_chunks)
  s.force_generate_chunk_requests()
  return s
end

-- Item request proxies (modules for machines and beacons): put the items straight in.
-- Catalysts (an item or fluid the recipe takes and gives back): a new build starts with none in its loop, so each
-- such machine gets a few crafts' worth (plan.Port.priming; a real game fills the loop once by hand). Production loops
-- (params.seeds, plan.json "seeds", e.g. Py's farms feeding each other) too. Only what the machine is short of: this
-- runs again through the first half of the warm-up (see seed_tick), which starts loops whose crafts do not line up (Py's
-- ammonia -> formamide -> formic acid -> ammonia). Returns whether the machine takes any of them.
local function prime(run, e, r)
  local back, made_at, seeded = {}, {}, {}
  for _, p in pairs(r.products) do back[p.name] = true; made_at[p.name] = p.temperature end
  for _, name in pairs(run.params.seeds or {}) do back[name] = true; seeded[name] = true end
  local any = false
  for _, ing in pairs(r.ingredients) do
    if back[ing.name] then
      any = true
      if ing.type == "item" then
        local want = math.ceil(ing.amount) * (seeded[ing.name] and 5 or 2)
        pcall(function()
          local have = e.get_item_count(ing.name)
          if have < want then e.insert { name = ing.name, count = want - have } end
        end)
      else
        local ok_, err = pcall(function()
          for k = 1, (e.fluids_count or 0) do
            local f = e.get_fluid_filter(k)
            local proto = e.get_fluid_box_prototype(k)
            if proto and proto.index == nil and proto[1] then proto = proto[1] end
            -- At the temperature the recipe gives it back at, else one the plan seeds it at (Py's outlet gas ring:
            -- the stone-brick heaters take it at 1000 and give it back cooler), else its default; none when no such
            -- temperature is one it takes (Py's warmer-stone-brick takes coke oven gas at 500 and gives it at 250).
            local function takes(t) return (ing.minimum_temperature == nil or t >= ing.minimum_temperature) and
              (ing.maximum_temperature == nil or t <= ing.maximum_temperature) and (ing.temperature == nil or t == ing.temperature) end
            local cands = {}
            if made_at[ing.name] then cands[#cands + 1] = made_at[ing.name] end
            if seeded[ing.name] then for _, st in pairs((run.params.seed_temps or {})[ing.name] or {}) do cands[#cands + 1] = st end end
            if not made_at[ing.name] then cands[#cands + 1] = prototypes.fluid[ing.name].default_temperature end
            local t, ok = cands[1], false
            for _, c in ipairs(cands) do if takes(c) then t, ok = c, true; break end end
            local have = e.get_fluid(k)
            if ok and f and f.name == ing.name and proto and proto.production_type == "input" and (not have or have.amount < ing.amount) then
              -- Two crafts' worth, or one where the box holds less.
              if not pcall(function() e.set_fluid(k, { name = ing.name, amount = ing.amount * 2, temperature = t }) end) then
                e.set_fluid(k, { name = ing.name, amount = ing.amount, temperature = t })
              end
            end
          end
        end)
        if not ok_ and not run.prime_error then run.prime_error = true; run.warnings[#run.warnings + 1] = "priming " .. ing.name .. " in " .. e.name .. ": " .. tostring(err) end
      end
    end
  end
  return any
end

-- Tops up the primed machines every 2 s through the first half of the warm-up.
-- Top-ups go only to a machine standing short of ingredients, and at most three times: topping up running machines
-- flooded closed loops (Py science 2's stone-brick heat ring: bricks are never used up, so every top-up added to
-- the ring until its belts and machines were full and nothing moved).
local SEED_TOPUPS = 3
local function seed_tick(run)
  if run.phase ~= "warmup" or game.tick - run.t0 >= run.warmup / 2 or game.tick % 120 ~= 0 then return end
  local short = { [defines.entity_status.no_ingredients] = true, [defines.entity_status.item_ingredient_shortage] = true,
    [defines.entity_status.fluid_ingredient_shortage] = true }
  for _, p in pairs(run.primes or {}) do
    if p.e.valid and short[p.e.status] and (p.topups or 0) < SEED_TOPUPS then
      p.topups = (p.topups or 0) + 1
      prime(run, p.e, p.r)
    end
  end
end

local function fulfil_proxies(surface)
  local n = 0
  for _, p in pairs(surface.find_entities_filtered { type = "item-request-proxy" }) do
    local target = p.proxy_target
    if target then
      for _, plan in pairs(p.insert_plan or {}) do
        local item = { name = str(plan.id.name), quality = str(plan.id.quality) or "normal" }
        for _, at in pairs((plan.items or {}).in_inventory or {}) do
          item.count = at.count or 1
          local inv = target.get_inventory(at.inventory)
          local done = inv and inv[at.stack + 1] and inv[at.stack + 1].set_stack(item)
          if not done then target.insert(item) end
          n = n + item.count
        end
      end
    end
    p.destroy()
  end
  return n
end

-- One energy interface per electric network of the blueprint, wired to one of its poles, off to the side.
local function power(run, surface, force, x0, y0)
  local nets = {}
  for _, p in pairs(surface.find_entities_filtered { type = "electric-pole", force = force }) do
    local id = p.electric_network_id
    if id then nets[id] = nets[id] or {}; table.insert(nets[id], p) end
  end
  local y, count, unwired = y0, 0, 0
  for _, poles in pairs(nets) do
    local eei = surface.create_entity { name = "electric-energy-interface", position = { x0, y }, force = force }
    eei.electric_buffer_size = 1e15
    eei.power_production = 1e13
    eei.energy = 1e15
    local hub = surface.create_entity { name = "medium-electric-pole", position = { x0 + 1.5, y - 0.5 }, force = force }
    local from = hub.get_wire_connector(defines.wire_connector_id.pole_copper, true)
    local ok = false
    for _, p in ipairs(poles) do
      local to = p.get_wire_connector(defines.wire_connector_id.pole_copper, true)
      if to and from.connect_to(to, false, defines.wire_origin.script) then ok = true; break end
    end
    if not ok then unwired = unwired + 1 end
    count = count + 1
    y = y + 4
  end
  return count, unwired
end

local function start(params)
  local text = table.concat(storage.buf or {})
  storage.buf = {}
  local inv = game.create_inventory(1)
  local stack = inv[1]
  if stack.import_stack(text) == 1 or not stack.valid_for_read then inv.destroy(); return { error = "could not import the blueprint string" } end
  if stack.is_blueprint_book then
    local book = stack.get_inventory(defines.inventory.item_main)
    stack = book[stack.active_index or 1]
  end
  if not stack.is_blueprint then inv.destroy(); return { error = "not a blueprint" } end
  local bp_entities = stack.get_blueprint_entities() or {}
  if #bp_entities == 0 then inv.destroy(); return { error = "the blueprint is empty" } end
  local minx, miny, maxx, maxy = math.huge, math.huge, -math.huge, -math.huge
  for _, e in pairs(bp_entities) do
    minx = math.min(minx, e.position.x); miny = math.min(miny, e.position.y)
    maxx = math.max(maxx, e.position.x); maxy = math.max(maxy, e.position.y)
  end

  local warnings = {}
  local force = game.forces.player
  set_research(force, params, warnings)
  local surface = surface_for(math.ceil(math.max(maxx - minx, maxy - miny) / 64) + 2)
  local ghosts = stack.build_blueprint { surface = surface, force = force, position = { 0, 0 },
                                        build_mode = defines.build_mode.forced, skip_fog_of_war = false }
  inv.destroy()

  -- Blueprint coordinates of what is built, so positions in the report match the solver's layout.
  local gx, gy = math.huge, math.huge
  for _, g in pairs(ghosts) do
    if g.valid then gx = math.min(gx, g.position.x); gy = math.min(gy, g.position.y) end
  end
  local run = { dx = gx - minx, dy = gy - miny, warnings = warnings, params = params, surface = surface }

  local revived, failed, tiles, entity_ghosts = 0, {}, 0, 0
  for _, g in pairs(ghosts) do
    if g.valid and g.type == "tile-ghost" then
      if g.revive() then tiles = tiles + 1 end
    end
  end
  for _, g in pairs(ghosts) do
    if g.valid and g.type == "entity-ghost" then
      entity_ghosts = entity_ghosts + 1
      local name = g.ghost_name
      local _, ent = g.revive()
      if ent then revived = revived + 1 else add(failed, name, 1) end
    end
  end
  local modules = fulfil_proxies(surface)
  local networks, unwired = power(run, surface, force, maxx + run.dx + 6, miny + run.dy)

  run.machines, run.inserters, run.belts, run.sources, run.sinks, run.drains = {}, {}, {}, {}, {}, {}
  run.primes = {}
  local counts = {}
  for _, e in pairs(surface.find_entities_filtered { force = force }) do
    add(counts, e.name, 1)
    local t = e.type
    if CRAFTERS[t] then
      local r = e.get_recipe()
      run.machines[#run.machines + 1] = { e = e, name = e.name, recipe = r and r.name, pos = bp_pos(run, e.position), statuses = {} }
      -- Catalysts (an item or fluid the recipe takes and gives back): a new build starts with none in its loop, so
      -- each such machine gets a few crafts' worth (plan.Port.priming; a real game fills the loop once by hand).
      if r and prime(run, e, r) then run.primes[#run.primes + 1] = { e = e, r = r } end
      if t == "rocket-silo" then run.silos = run.silos or {}; run.silos[#run.silos + 1] = { e = e, launches = 0, failed = 0 } end
    elseif t == "inserter" then
      run.inserters[#run.inserters + 1] = { e = e, held_all = true, moved = false, samples = 0 }
    elseif BELTS[t] then
      run.belts[#run.belts + 1] = { e = e }
    elseif LOADERS[t] then
      local c = e.loader_container
      if not (c and c.valid) then
        -- The chest the solver put at the loader's far end (either end: input loaders face the other way).
        local d = ({ [defines.direction.north] = { 0, 1 }, [defines.direction.east] = { 1, 0 }, [defines.direction.south] = { 0, 1 }, [defines.direction.west] = { 1, 0 } })[e.direction]
        local reach = (e.type == "loader") and 1.5 or 1
        for _, sign in pairs(d and { 1, -1 } or {}) do
          c = c or surface.find_entities_filtered { type = "infinity-container", position = { e.position.x + d[1] * reach * sign, e.position.y + d[2] * reach * sign } }[1]
        end
      end
      if c and c.type == "infinity-container" then
        if e.loader_type == "input" then
          c.infinity_container_filters = {}
          c.remove_unfiltered_items = false
          local s = { chest = c, loader = e, inv = c.get_inventory(defines.inventory.chest), pos = bp_pos(run, e.position), items = {} }
          -- An overflow port's sink (params.overflow_at): closed until its belt backs up.
          for _, at in pairs(run.params.overflow_at or {}) do
            if math.abs(s.pos.x - at[1]) + math.abs(s.pos.y - at[2]) <= 3.5 then s.overflow = true; e.disabled_by_script = true end
          end
          run.sinks[#run.sinks + 1] = s
        else
          local keep = {}
          for _, f in pairs(c.infinity_container_filters or {}) do
            keep[#keep + 1] = { name = str(f.name), quality = str(f.quality) or "normal", count = math.max(f.count or 50, 50) }
          end
          c.infinity_container_filters = {}
          c.remove_unfiltered_items = false
          run.sources[#run.sources + 1] = { chest = c, inv = c.get_inventory(defines.inventory.chest), pos = bp_pos(run, e.position), keep = keep, items = {} }
        end
      end
    elseif t == "infinity-pipe" then
      local f = e.get_infinity_pipe_filter()
      if f and (f.mode == "remove" or ((f.mode == "exactly" or f.mode == "at-most") and (f.percentage or 0) == 0)) then
        e.set_infinity_pipe_filter(nil)
        run.drains[#run.drains + 1] = { e = e, fluid = f.name, pos = bp_pos(run, e.position), amount = 0 }
      end
    end
  end

  run.build = {
    blueprint_entities = #bp_entities, ghosts = entity_ghosts, tiles = tiles, revived = revived, failed = failed, modules_inserted = modules,
    power_networks = networks, power_networks_unwired = unwired, entities = counts,
  }
  local speed = params.speed or 1000
  run.warmup = math.floor((params.warmup_s or 300) * 60)
  run.measure = math.floor((params.measure_s or 300) * 60)
  run.t0 = game.tick
  run.phase = "warmup"
  storage.run = run
  game.speed = speed
  return {
    phase = run.phase, machines = #run.machines, inserters = #run.inserters, belts = #run.belts, sources = #run.sources,
    sinks = #run.sinks, drains = #run.drains, build = { revived = revived, failed = failed, ghosts = entity_ghosts, tiles = tiles,
    power_networks = networks, power_networks_unwired = unwired, modules_inserted = modules }, warnings = warnings,
  }
end

local function flows(run, counting)
  for _, s in pairs(run.sources) do
    if s.chest.valid then
      for _, k in pairs(s.keep) do
        local have = s.inv.get_item_count({ name = k.name, quality = k.quality })
        if have < k.count then
          local n = s.inv.insert { name = k.name, quality = k.quality, count = k.count - have }
          if counting then add(s.items, key(k.name, k.quality), n) end
        end
      end
    end
  end
  local caps = run.params.sink_rates
  local overflow = {}
  for _, n in pairs(run.params.overflow or {}) do overflow[n] = true end
  for _, s in pairs(run.sinks) do
    if s.chest.valid and not s.inv.is_empty() then
      for _, it in pairs(s.inv.get_contents()) do
        local k = key(it.name, it.quality)
        local n = it.count
        if caps and caps[k] then
          run.budget = run.budget or {}
          local b = run.budget[k] or 0
          n = math.min(n, math.floor(b))
          run.budget[k] = b - n
        end
        if n > 0 then
          if counting then add(s.items, k, n) end
          s.inv.remove { name = it.name, quality = it.quality, count = n }
        end
      end
      if caps then s.loader.disabled_by_script = s.inv.get_item_count() >= 20 end
    end
    -- An overflow (params.overflow: items the factory also uses) takes only while the belt into its loader is
    -- backed up, so the factory's own users always take first, however fast they run.
    if next(overflow) and s.loader.valid then
      local belts = (s.loader.belt_neighbours or {}).inputs or {}
      local b = belts[1]
      if b and b.valid then
        local mine, full = false, false
        for i = 1, 2 do
          local ok, line = pcall(function() return b.get_transport_line(i) end)
          if ok and line then
            for _, it in pairs(line.get_contents()) do if overflow[it.name] then mine = true end end
            if line.get_item_count() >= 3 then full = true end
          end
        end
        if mine or s.overflow then
          s.overflow = true
          if not full then s.loader.disabled_by_script = true elseif not (caps and s.inv.get_item_count() >= 20) then s.loader.disabled_by_script = false end
        end
      end
    end
  end
  if caps then
    run.budget = run.budget or {}
    -- At most a second's worth (5 items for slow outputs) carried over, so the measured rate is the planned one
    -- when the factory keeps up, not the plan plus a stored-up burst.
    for k, r in pairs(caps) do run.budget[k] = math.min((run.budget[k] or 0) + r / 60, math.max(5, r)) end
  end
  for _, d in pairs(run.drains) do
    if d.e.valid then
      local ok, f = pcall(function() return d.e.get_fluid_segment_fluid(1) end)
      if ok then
        if f and f.amount > 0 then
          if counting then d.amount = d.amount + f.amount end
          d.e.clear_fluid_segment_fluid(1)
        end
      else
        f = d.e.get_fluid(1)
        if f and f.amount > 0 then
          if counting then d.amount = d.amount + f.amount end
          d.e.clear_fluid(1)
        end
      end
    end
  end
end

local function begin_measure(run)
  run.phase = "measure"
  run.m0 = game.tick
  for _, m in pairs(run.machines) do if m.e.valid then m.start = m.e.products_finished end end
  -- Rocket parts built so far: finished rockets times the parts each takes, plus the parts of the one being built.
  for _, s in pairs(run.silos or {}) do
    if s.e.valid then s.req = s.e.prototype.rocket_parts_required or 1; s.p0 = s.e.products_finished * s.req + s.e.rocket_parts end
  end
  for _, b in pairs(run.belts) do if b.e.valid then b.key = belt_key(b.e) end end
end

local function sample(run)
  for _, m in pairs(run.machines) do
    if m.e.valid then add(m.statuses, STATUS[m.e.status] or "none", 1) end
  end
  for _, i in pairs(run.inserters) do
    local e = i.e
    if e.valid then
      local p = e.held_stack_position
      if not e.held_stack.valid_for_read then i.held_all = false end
      if i.p then
        if math.abs(p.x - i.p.x) > 0.01 or math.abs(p.y - i.p.y) > 0.01 then i.moved = true end
      else
        i.p = { x = p.x, y = p.y }
      end
      i.samples = i.samples + 1
      i.status = STATUS[e.status]
      if e.status == defines.entity_status.working then i.worked = true end
    end
  end
end

local function most(t)
  local best, n = nil, -1
  for k, v in pairs(t) do if v > n then best, n = k, v end end
  return best
end

local function finish(run)
  local secs = (game.tick - run.m0) / 60
  local report = {
    measured_s = secs, warmup_s = run.warmup / 60, tick = game.tick, build = run.build, warnings = run.warnings,
    machines = {}, outputs = {}, inputs = {}, fluid_outputs = {}, stuck_inserters = {}, idle_inserters = 0, stuck_belts = {},
    inserters = #run.inserters, belts = #run.belts,
    research = { inserter_stack_size_bonus = game.forces.player.inserter_stack_size_bonus,
                 bulk_inserter_capacity_bonus = game.forces.player.bulk_inserter_capacity_bonus,
                 belt_stack_size_bonus = game.forces.player.belt_stack_size_bonus },
  }
  for _, m in pairs(run.machines) do
    local e = m.e
    if e.valid then
      local crafts = e.products_finished - (m.start or 0)
      local recipe = e.get_recipe()
      if not recipe and e.type == "furnace" and e.previous_recipe then recipe = e.previous_recipe.name end
      report.machines[#report.machines + 1] = {
        name = m.name, recipe = str(recipe) or m.recipe or "", x = m.pos.x, y = m.pos.y, status = STATUS[e.status], usual_status = most(m.statuses),
        statuses = m.statuses, crafts = crafts, crafts_per_min = crafts / secs * 60, progress = e.crafting_progress,
        products_finished = e.products_finished, speed = e.crafting_speed,
        input = (function() local inv = e.get_inventory(defines.inventory.crafter_input); local o = {}
          if inv then for _, it in pairs(inv.get_contents()) do o[it.name] = it.count end end; return o end)(),
        output = (function() local inv = e.get_inventory(defines.inventory.crafter_output); local o = {}
          if inv then for _, it in pairs(inv.get_contents()) do o[it.name] = it.count end end; return o end)(),
        -- Each fluid box: input or output, what it holds (or is filtered to), how much, how hot.
        fluids = (function() local o = {}
          pcall(function()
            for k = 1, (e.fluids_count or 0) do
              local proto = e.get_fluid_box_prototype(k)
              if proto and proto.index == nil and proto[1] then proto = proto[1] end
              local f = e.get_fluid(k); local flt = e.get_fluid_filter(k)
              o[#o + 1] = { io = proto and proto.production_type or "?", name = (f and f.name) or (flt and flt.name) or nil,
                amount = f and f.amount or 0, temperature = f and f.temperature or nil }
            end
          end)
          return o end)(),
      }
    end
  end
  for _, s in pairs(run.sinks) do
    local rates = {}
    for k, v in pairs(s.items) do rates[k] = v / secs end
    report.outputs[#report.outputs + 1] = { x = s.pos.x, y = s.pos.y, per_s = rates }
  end
  for _, s in pairs(run.sources) do
    local rates = {}
    for k, v in pairs(s.items) do rates[k] = v / secs end
    for _, k in pairs(s.keep) do rates[key(k.name, k.quality)] = rates[key(k.name, k.quality)] or 0 end
    report.inputs[#report.inputs + 1] = { x = s.pos.x, y = s.pos.y, per_s = rates }
  end
  for _, d in pairs(run.drains) do
    report.fluid_outputs[#report.fluid_outputs + 1] = { x = d.pos.x, y = d.pos.y, fluid = d.fluid, per_s = d.amount / secs }
  end
  for _, i in pairs(run.inserters) do
    if i.e.valid and i.samples > 1 and not i.moved and not i.worked then
      if i.held_all then
        local h = i.e.held_stack
        local p = bp_pos(run, i.e.position)
        -- Machine to machine (direct insertion) into a machine that is not short of anything: it waits because the
        -- consumer holds enough, as it should when the producer is faster than the consumer needs.
        local dt, pt = i.e.drop_target, i.e.pickup_target
        local crafting = { ["assembling-machine"] = true, ["furnace"] = true }
        local waiting = dt ~= nil and pt ~= nil and crafting[dt.type] == true and crafting[pt.type] == true
          and dt.status ~= defines.entity_status.item_ingredient_shortage
        -- Waiting for room where it drops (a backed-up belt: outputs are taken at their planned rate, so surplus
        -- backs up by design).
        if i.e.status == defines.entity_status.waiting_for_space_in_destination then waiting = true end
        report.stuck_inserters[#report.stuck_inserters + 1] = { name = i.e.name, x = p.x, y = p.y, status = i.status,
          holding = h.valid_for_read and h.name or nil, drop_target = dt and dt.name or nil, waiting = waiting }
      else
        report.idle_inserters = report.idle_inserters + 1
      end
    end
  end
  for _, b in pairs(run.belts) do
    if b.e.valid and b.key and b.key == belt_key(b.e) then
      local p = bp_pos(run, b.e.position)
      report.stuck_belts[#report.stuck_belts + 1] = { name = b.e.name, x = p.x, y = p.y, items = belt_items(b.e) }
    end
  end
  if run.params.dump then
  report.dump_belts = {}
  for _, b in pairs(run.belts) do
    if b.e.valid then
      local p = bp_pos(run, b.e.position)
      local lanes = {}
      for i = 1, b.e.get_max_transport_line_index() do
        local o = {}
        for _, it in pairs(b.e.get_transport_line(i).get_contents()) do o[it.name] = it.count end
        lanes[i] = o
      end
      report.dump_belts[#report.dump_belts + 1] = { n = b.e.name, x = p.x, y = p.y, d = b.e.direction, t = b.e.type == "underground-belt" and b.e.belt_to_ground_type or nil, lanes = lanes }
    end
  end
  report.dump_pipes = {}
  local okp, errp = pcall(function()
  for _, e in pairs(run.surface.find_entities_filtered { type = { "pipe", "pipe-to-ground", "storage-tank" } }) do
    local f = e.get_fluid(1)
    if f then
      local p = bp_pos(run, e.position)
      local seg = 0
      pcall(function() seg = e.get_fluid_segment_fluid(1).amount end)
      local nb = nil
      pcall(function()
        local names = {}
        for _, group in pairs(e.neighbours or {}) do
          if type(group) == "table" then
            for _, o in pairs(group) do if type(o) == "userdata" and o.valid then names[#names + 1] = o.name end end
          end
        end
        nb = table.concat(names, ",")
      end)
      report.dump_pipes[#report.dump_pipes + 1] = { x = p.x, y = p.y, n = e.name, f = f.name, a = f.amount, seg = seg, nb = nb }
    end
  end
    for _, m in pairs(run.machines) do
      if m.e.valid and (m.recipe == "heavy-oil-cracking" or m.recipe == "lubricant" or #run.machines <= 40 or
          m.e.status == defines.entity_status.fluid_ingredient_shortage) then
        local cb = m.e.get_control_behavior()
        local info = { x = m.pos.x, y = m.pos.y, n = m.recipe, status = STATUS[m.e.status] }
        if cb then
          info.enable = tostring(cb.circuit_enable_disable)
          pcall(function() info.cond = serpent.line(cb.circuit_condition) end)
        end
        pcall(function() info.sig = m.e.get_signal({ type = "fluid", name = "heavy-oil" }, defines.wire_connector_id.circuit_red) end)
        local okf, errf = pcall(function() local fb = {}; for i = 1, #m.e.fluidbox do local f = m.e.fluidbox[i]; fb[i] = f and (f.name .. "=" .. f.amount) or "-" end; info.fb = fb end)
        if not okf then info.fberr = tostring(errf) end
        -- Per fluid box: the fluid the recipe gave it (filter) and what it touches, to check connections against the plan.
        pcall(function()
          local ff = {}
          for i = 1, #m.e.fluidbox do
            local f = m.e.fluidbox.get_filter(i)
            local cs = {}
            for _, c in pairs(m.e.fluidbox.get_pipe_connections(i) or {}) do
              cs[#cs + 1] = string.format("%s(%.1f,%.1f)%s", c.flow_direction or "?", c.target_position and c.target_position.x or 0, c.target_position and c.target_position.y or 0, c.target and "+" or "")
            end
            ff[i] = (f and f.name or "-") .. " " .. table.concat(cs, " ")
          end
          info.filters = ff
        end)
        if not info.filters then
          -- 2.1 renamed parts of the fluid box API: which of these names exist, and what they give.
          local have = {}
          for _, k in pairs({ "fluidbox", "fluidboxes", "fluids_count", "get_fluid", "get_fluid_count", "get_fluidbox", "get_pipe_connections",
                              "get_fluid_filter", "get_fluidbox_filter", "get_fluid_contents", "fluid_contents", "get_fluid_segment_fluid" }) do
            local ok, v = pcall(function() return m.e[k] end)
            if ok and v ~= nil then have[#have + 1] = k .. ":" .. type(v) end
          end
          info.api = table.concat(have, " ")
          local got = {}
          for i = 1, (m.e.fluids_count or 0) do
            local okg, f = pcall(function() return m.e.get_fluid(i) end)
            local okf2, flt = pcall(function() return m.e.get_fluid_filter(i) end)
            got[i] = (okg and f and (f.name .. "=" .. f.amount) or (okg and "-" or "err:" .. tostring(f))) .. "/" .. (okf2 and (flt and (flt.name or serpent.line(flt)) or "nofilter") or ("err:" .. tostring(flt)))
          end
          info.get_fluid = table.concat(got, " ")
          pcall(function() info.fluids_count = m.e.fluids_count end)
        end
        report.dump_pipes[#report.dump_pipes + 1] = info
      end
    end
  end)
  if not okp then report.dump_pipes_error = tostring(errp) end
  report.dump_inserters = {}
  for _, i in pairs(run.inserters) do
    local e = i.e
    if e.valid then
      local p = bp_pos(run, e.position)
      local pp = bp_pos(run, e.pickup_position); local dp = bp_pos(run, e.drop_position)
      local h = e.held_stack
      report.dump_inserters[#report.dump_inserters + 1] = { n = e.name, x = p.x, y = p.y, px = pp.x, py = pp.y, dx = dp.x, dy = dp.y,
        s = STATUS[e.status], h = h.valid_for_read and h.name or nil, moved = i.moved, worked = i.worked }
    end
  end
  end
  report.silos = {}
  for _, s in pairs(run.silos or {}) do
    if s.e.valid then report.silos[#report.silos + 1] = { x = bp_pos(run, s.e.position).x, y = bp_pos(run, s.e.position).y, launches = s.launches, failed_launches = s.failed, status = s.e.rocket_silo_status,
      parts_required = s.req, parts = s.e.products_finished * (s.req or 1) + s.e.rocket_parts - (s.p0 or 0) } end
  end
  helpers.write_file(REPORT, helpers.table_to_json(report))
  run.phase = "done"
  game.speed = 1
  log("factorio-optimizer-verify: wrote " .. REPORT)
end

script.on_event(defines.events.on_tick, function()
  local run = storage.run
  if not run or run.phase == "done" or run.phase == nil then return end
  flows(run, run.phase == "measure")
  -- Rocket silos launch each finished rocket (nothing comes back without Space Age's platforms; the parts are what count).
  if run.silos and game.tick % 30 == 0 then
    for _, s in pairs(run.silos) do
      local e = s.e
      if e.valid and e.rocket_silo_status == defines.rocket_silo_status.rocket_ready then
        if e.launch_rocket() then
          if run.phase == "measure" then s.launches = s.launches + 1 end
        else s.failed = s.failed + 1 end
      end
    end
  end
  seed_tick(run)
  if run.phase == "warmup" and game.tick - run.t0 >= run.warmup then begin_measure(run) end
  if run.phase == "measure" then
    local t = game.tick - run.m0
    if t > 0 and t % SAMPLE == 0 then sample(run) end
    if t >= run.measure then finish(run) end
  end
end)

commands.add_command("verify_clear", "Forget any blueprint pieces sent so far.", function()
  storage.buf = {}
  reply { ok = true }
end)

commands.add_command("verify_append", "Append a piece of the blueprint string to verify.", function(c)
  storage.buf = storage.buf or {}
  storage.buf[#storage.buf + 1] = c.parameter or ""
  reply { ok = true, pieces = #storage.buf }
end)

commands.add_command("verify_start", "Build the blueprint sent with /verify_append and run it. Parameter: JSON {warmup_s, measure_s, speed, inserter_capacity, belt_stack_size, productivity}.", function(c)
  local params = {}
  if c.parameter and c.parameter ~= "" then params = helpers.json_to_table(c.parameter) or {} end
  local ok, result = pcall(start, params)
  if ok then reply(result) else reply { error = tostring(result) } end
end)

commands.add_command("verify_status", "Where a verify run is.", function()
  local run = storage.run
  if not run then reply { phase = "idle", tick = game.tick }; return end
  local at = game.tick - run.t0
  reply { phase = run.phase, tick = game.tick, game_s = at / 60, total_s = (run.warmup + run.measure) / 60, speed = game.speed, report = run.phase == "done" and REPORT or nil }
end)
