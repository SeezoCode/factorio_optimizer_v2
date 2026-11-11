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

-- An ingredient in a machine: in its input slots only. A Py farm runs on animals in its module slots, and a reproductive
-- complex holding xeno as modules, short of the xeno its recipe takes, was never seen waiting for it: its maker stayed
-- off for good (production science).
local function held(e, name)
  local inv = e.get_inventory(defines.inventory.crafter_input)
  if inv then return inv.get_item_count(name) end
  return e.get_item_count(name)
end

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
-- A warm start (params.prime_all): two crafts' worth of each ingredient (one of a carrier); fluids at a temperature
-- the recipe takes (a product's of the same name, a seeded one, or the fluid's default).
-- Items the plan has a machine of recipe [r] take that the recipe does not: its fuel (a burner's biomass, coal).
local function fuel_items(run, r)
  local out = {}
  local ings = {}
  for _, ing in pairs(r.ingredients) do ings[ing.name] = true end
  for item in pairs((run.params.demand_of or {})[r.name] or {}) do
    if not ings[item] and prototypes.item[item] then out[#out + 1] = item end
  end
  return out
end

-- The belt a sink takes from: its loader's input, or for a local sink (an inserter into a chest beside a block) the
-- belt the inserter picks from.
local function sink_belt(s)
  if s.belt then return s.belt.valid and s.belt or nil end
  return s.loader and s.loader.valid and ((s.loader.belt_neighbours or {}).inputs or {})[1] or nil
end

local function warm_start(run, e, r)
  -- A burner's fuel too (it only ever got the recipe's ingredients: Py logistic science's biomass powerplant, heating
  -- the molten salt the outlet gas ring is reheated with, never ran).
  pcall(function()
    local fuel = e.get_fuel_inventory()
    if fuel then for _, f in pairs(fuel_items(run, r)) do if fuel.get_item_count(f) < 5 then fuel.insert { name = f, count = 5 } end end end
  end)
  local carrier = {}
  for _, name in pairs(run.params.seed_carriers or {}) do carrier[name] = true end
  for _, ing in pairs(r.ingredients) do
    if ing.type == "item" then
      pcall(function()
        local want = math.ceil(ing.amount * (carrier[ing.name] and 1 or 2))
        local have = held(e, ing.name)
        if have < want then e.insert { name = ing.name, count = want - have } end
      end)
    else
      pcall(function()
        local temps = (run.params.seed_temps or {})[ing.name] or {}
        local cands = {}
        for _, t in pairs(temps) do cands[#cands + 1] = t end
        cands[#cands + 1] = prototypes.fluid[ing.name].default_temperature
        local t = nil
        for _, c in ipairs(cands) do
          if (ing.minimum_temperature == nil or c >= ing.minimum_temperature) and (ing.maximum_temperature == nil or c <= ing.maximum_temperature) then t = c; break end
        end
        if not t then t = ing.minimum_temperature or cands[1] end
        for k = 1, (e.fluids_count or 0) do
          local f = e.get_fluid_filter(k)
          local proto = e.get_fluid_box_prototype(k)
          if proto and proto.index == nil and proto[1] then proto = proto[1] end
          if f and f.name == ing.name and proto and proto.production_type == "input" then
            if not pcall(function() e.set_fluid(k, { name = ing.name, amount = ing.amount * 2, temperature = t }) end) then
              pcall(function() e.set_fluid(k, { name = ing.name, amount = ing.amount, temperature = t }) end)
            end
          end
        end
      end)
    end
  end
end

local function prime(run, e, r, crafted, rec)
  local back, made_at, seeded, own = {}, {}, {}, {}
  for _, p in pairs(r.products) do back[p.name] = true; made_at[p.name] = p.temperature; own[p.name] = true end
  for _, name in pairs(run.params.seeds or {}) do back[name] = true; seeded[name] = true end
  local carrier = {}
  for _, name in pairs(run.params.seed_carriers or {}) do carrier[name] = true end
  local any = false
  for _, ing in pairs(r.ingredients) do
    if back[ing.name] then
      any = true
      if ing.type == "item" and crafted and carrier[ing.name] then
        -- (a loop that has started holds what was put in: added to after that, Py science 2's two-machine brick ring,
        -- passing bricks hand to hand, took in 75 bricks, both outputs filled, and each refused the other's bricks.
        -- Too few only slow a ring; too many lock it.)
      elseif ing.type == "item" then
        local want = math.ceil(ing.amount * (carrier[ing.name] and 1 or (seeded[ing.name] and 5 or 2)))
        -- Once it has crafted, an item it gives back itself only while it lacks a craft's worth, up to that: topped up
        -- past what its inserters fill to, it takes nothing from its own return loop, and its returns pile up until it
        -- stands full for good (Py production's ulric corral, topped up to 5 bedding while it waited for raw fiber).
        if crafted and own[ing.name] then want = ing.amount end
        -- (A catalyst the machine also gives to others, Py's moss farm's coarse, is topped up like a seed: the
        -- classifier took the first batch and the farm stood short of the 15 it needs.)
        pcall(function()
          local have = held(e, ing.name)
          local add = want - have
          -- A top-up once the loop runs: within 5 times the plan's use by this machine since the start (Py science
          -- 1's seaweed burner, fed whenever short, crafted 1375 times instead of about 15 and filled its belts with ash).
          if crafted and rec then
            local rate = ((run.params.seed_rates or {})[r.name] or {})[ing.name]
            if rate then
              rec.given = rec.given or {}
              local allowed = rate * 5 * (game.tick - run.t0) / 60 + want
              add = math.min(add, math.floor(allowed - (rec.given[ing.name] or 0)))
            end
          end
          if add > 0 then
            local n = e.insert { name = ing.name, count = add }
            if rec then rec.given = rec.given or {}; rec.given[ing.name] = (rec.given[ing.name] or 0) + n end
          end
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
-- Top-ups go only to a machine standing short of ingredients (see seed_tick for how often). Items a loop only passes round
-- (params.seed_carriers) only until the machine has crafted: topping those up floods the loop (Py science 2's
-- stone-brick heat ring: bricks are only heated and cooled, so every top-up stayed in the ring until its belts were
-- full, every machine's output was full, and a machine with a full output takes no ingredients: gridlock). Seeds a
-- loop uses up (Py science 1's seaweed: its fertilizer also needs ash that three other users share) are topped up
-- until the loop runs; fluids too (chemical loops whose crafts do not line up: ammonia -> formamide -> formic acid).
local SEED_TOPUPS = 10
local function seed_tick(run)
  -- Through the whole warm-up (never while measuring: a loop that cannot keep itself going shows there).
  if run.phase ~= "warmup" or game.tick % 120 ~= 0 then return end
  local short = { [defines.entity_status.no_ingredients] = true, [defines.entity_status.item_ingredient_shortage] = true,
    [defines.entity_status.fluid_ingredient_shortage] = true }
  for _, p in pairs(run.primes or {}) do
    if p.e.valid and short[p.e.status] then
      local crafted = p.e.products_finished > (p.pf0 or 0)
      -- After the first craft only seeds a loop uses up (prime leaves carriers alone then), as often as it stands short;
      -- before it, at most SEED_TOPUPS times.
      if crafted or (p.topups or 0) < SEED_TOPUPS then
        p.topups = (p.topups or 0) + 1
        prime(run, p.e, p.r, crafted, p)
      end
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
  -- params.mod_settings: runtime-global settings of other mods to set first (the factory-flow mod's, for --flow-mod).
  for k, v in pairs(params.mod_settings or {}) do
    pcall(function() settings.global[k] = { value = v } end)
  end
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
      -- (params.raise_built: as a player's build, so a mod like factory-flow sees the entity and its tags.)
      local _, ent = g.revive { raise_revive = params.raise_built == true }
      if ent then revived = revived + 1 else add(failed, name, 1) end
    end
  end
  local modules = fulfil_proxies(surface)
  local networks, unwired = power(run, surface, force, maxx + run.dx + 6, miny + run.dy)
  -- Robot links (blocks.bot_links): logistic robots into every roboport, as a player would put them in.
  if params.robots_per_roboport and params.robots_per_roboport > 0 and prototypes.item["logistic-robot"] then
    for _, r in pairs(surface.find_entities_filtered { type = "roboport", force = force }) do
      pcall(function() r.insert { name = "logistic-robot", count = params.robots_per_roboport } end)
    end
  end

  -- Furnace-type machines' planned recipes (blueprint tags; the game sets theirs only once something arrives).
  local planned = {}
  for _, be in pairs(bp_entities) do
    if be.tags and be.tags.recipe then planned[be.position.x .. "," .. be.position.y] = be.tags.recipe end
  end
  run.machines, run.inserters, run.belts, run.sources, run.sinks, run.drains = {}, {}, {}, {}, {}, {}
  run.primes = {}
  local counts = {}
  for _, e in pairs(surface.find_entities_filtered { force = force }) do
    add(counts, e.name, 1)
    local t = e.type
    if CRAFTERS[t] then
      local r = e.get_recipe()
      local pos = bp_pos(run, e.position)
      local plan_recipe = planned[pos.x .. "," .. pos.y]
      if not r and plan_recipe then r = prototypes.recipe[plan_recipe] end
      run.machines[#run.machines + 1] = { e = e, name = e.name, recipe = r and r.name, plan_recipe = plan_recipe, pos = pos, statuses = {} }
      -- Catalysts (an item or fluid the recipe takes and gives back): a new build starts with none in its loop, so
      -- each such machine gets a few crafts' worth (plan.Port.priming; a real game fills the loop once by hand).
      if r and prime(run, e, r) then run.primes[#run.primes + 1] = { e = e, r = r, pf0 = e.products_finished } end
      -- params.prime_all: a warm start, every machine given two crafts' worth of everything it takes (one of what a
      -- loop only passes round), so a check shows whether the factory runs once going, apart from how it starts.
      if r and run.params.prime_all then warm_start(run, e, r) end
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
          local s = { chest = c, loader = e, gate = e, inv = c.get_inventory(defines.inventory.chest), pos = bp_pos(run, e.position), items = {} }
          run.sinks[#run.sinks + 1] = s
        else
          local keep = {}
          for _, f in pairs(c.infinity_container_filters or {}) do
            keep[#keep + 1] = { name = str(f.name), quality = str(f.quality) or "normal", count = math.max(f.count or 50, 50) }
          end
          c.infinity_container_filters = {}
          c.remove_unfiltered_items = false
          run.sources[#run.sources + 1] = { chest = c, loader = e, inv = c.get_inventory(defines.inventory.chest), pos = bp_pos(run, e.position), keep = keep, items = {} }
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

  -- Local sinks (blocks.local_sinks): an inserter taking a small by-product off the end of its belt inside the box,
  -- into a chest beside it (an infinity chest in the test copy). It counts what arrives as a loader's sink does, and
  -- closes as an overflow's loader would (the inserter is its gate). It is no machine's inserter: left out of the
  -- inserter reports and the belt networks' feeds.
  do
    local function find(p, pred)
      for _, x in pairs(surface.find_entities_filtered { area = { { p.x - 0.05, p.y - 0.05 }, { p.x + 0.05, p.y + 0.05 } }, force = force }) do
        if pred(x) then return x end
      end
    end
    local keep = {}
    for _, i in pairs(run.inserters) do
      local e = i.e
      local c = e.valid and find(e.drop_position, function(x) return x.type == "infinity-container" end)
      if c then
        c.infinity_container_filters = {}
        c.remove_unfiltered_items = false
        local b = find(e.pickup_position, function(x) return BELTS[x.type] end)
        run.sinks[#run.sinks + 1] = { chest = c, gate = e, belt = b, inv = c.get_inventory(defines.inventory.chest),
          pos = bp_pos(run, e.position), items = {}, ["local"] = true }
      else
        keep[#keep + 1] = i
      end
    end
    run.inserters = keep
  end

  -- An overflow port's sink (params.overflow_at): closed until its belt backs up. A mixed belt's (params.mixed_at)
  -- always takes: it carries only leftovers, whatever items on it are overflows elsewhere (Py science 1's leftovers
  -- belt, with stone on it, stayed shut and stopped the lime that the moss farm's CO2 came with). Each port marks the one
  -- sink nearest it: ports a tile apart have sinks a tile apart (Py logistic science's science sink, beside two
  -- overflows', was taken for one and stayed shut: 33 packs made, none out).
  local function nearest_sink(at)
    local best, bd = nil, 3.5 + 1e-6
    for _, s in pairs(run.sinks) do
      local d = math.abs(s.pos.x - at[1]) + math.abs(s.pos.y - at[2])
      if d < bd then best, bd = s, d end
    end
    return best
  end
  for _, at in pairs(run.params.mixed_at or {}) do local s = nearest_sink(at); if s then s.mixed = true end end
  for _, at in pairs(run.params.overflow_at or {}) do
    local s = nearest_sink(at)
    if s and not s.mixed then s.overflow = true; s.gate.disabled_by_script = true end
  end

  -- Belt networks (belts joined through their neighbours, undergrounds and splitters): which ones each machine's
  -- inserters take from (run.feeds, for the governor) and drop onto (run.drops, for overflow sinks).
  do
    local parent = {}
    local function find(x) while parent[x] ~= x do parent[x] = parent[parent[x]]; x = parent[x] end return x end
    for _, b in pairs(run.belts) do if b.e.valid then parent[b.e.unit_number] = b.e.unit_number end end
    local function union(a, c) if parent[a] and parent[c] then local ra, rc = find(a), find(c); if ra ~= rc then parent[ra] = rc end end end
    for _, b in pairs(run.belts) do
      local e = b.e
      if e.valid then
        for _, o in pairs((e.belt_neighbours or {}).outputs or {}) do union(e.unit_number, o.unit_number) end
        if e.type == "underground-belt" and e.underground_belt_neighbour then union(e.unit_number, e.underground_belt_neighbour.unit_number) end
      end
    end
    -- Robot links (params.bots, blocks.bot_links): the belt into a provider chest and the belts out of requester chests
    -- of the same item are one network, as robots carry between them (pull then reaches the makers behind a provider).
    if run.params.bots then
      local function chest_at(b)
        local p = { b.x + 0.5 + run.dx, b.y + 0.5 + run.dy }
        return surface.find_entities_filtered { position = p, type = "logistic-container", force = force }[1]
      end
      local function belt_at(p)
        for _, x in pairs(surface.find_entities_filtered { area = { { p.x - 0.05, p.y - 0.05 }, { p.x + 0.05, p.y + 0.05 } }, force = force }) do
          if BELTS[x.type] then return x end
        end
      end
      local function inside(c, p) return math.abs(p.x - c.position.x) < 0.5 and math.abs(p.y - c.position.y) < 0.5 end
      local first = {}
      local function join(item, belt)
        if not (belt and parent[belt.unit_number]) then return end
        if first[item] then union(first[item], belt.unit_number) else first[item] = belt.unit_number end
      end
      for _, b in pairs(run.params.bots.providers or {}) do
        local c = chest_at(b)
        if c then for _, i in pairs(run.inserters) do if i.e.valid and inside(c, i.e.drop_position) then join(b.item, belt_at(i.e.pickup_position)) end end end
      end
      for _, b in pairs(run.params.bots.requesters or {}) do
        local c = chest_at(b)
        if c then for _, i in pairs(run.inserters) do if i.e.valid and inside(c, i.e.pickup_position) then join(b.item, belt_at(i.e.drop_position)) end end end
      end
    end
    run.feeds, run.drops, run.net_of = {}, {}, {}
    for u in pairs(parent) do run.net_of[u] = find(u) end
    -- What is at an inserter's pickup or drop position (its pickup_target and drop_target are not set yet right after
    -- the build: every machine came out on no network).
    local function at(p)
      for _, x in pairs(surface.find_entities_filtered { area = { { p.x - 0.05, p.y - 0.05 }, { p.x + 0.05, p.y + 0.05 } }, force = force }) do
        if BELTS[x.type] or CRAFTERS[x.type] then return x end
      end
    end
    for _, i in pairs(run.inserters) do
      local d = i.e.valid and (i.e.drop_target or at(i.e.drop_position))
      local pk = i.e.valid and (i.e.pickup_target or at(i.e.pickup_position))
      if d and pk and parent[pk.unit_number] then
        local f = run.feeds[d.unit_number] or {}
        f[find(pk.unit_number)] = true
        run.feeds[d.unit_number] = f
      end
      if d and pk and parent[d.unit_number] then
        local f = run.drops[pk.unit_number] or {}
        f[find(d.unit_number)] = true
        run.drops[pk.unit_number] = f
      end
      if d and pk and CRAFTERS[d.type] and parent[pk.unit_number] then
        run.pick_belts = run.pick_belts or {}; run.pick_belts[pk.unit_number] = true
        run.ins_net = run.ins_net or {}; run.ins_net[i.e.unit_number] = find(pk.unit_number)
      end
      -- The inserters bringing things into each machine (for --flow's quotas).
      if d and CRAFTERS[d.type] then
        run.into = run.into or {}
        local l = run.into[d.unit_number] or {}
        l[#l + 1] = i.e
        run.into[d.unit_number] = l
      end
      -- Machine to machine (direct insertion): which machines each one hands its products to.
      if d and pk and CRAFTERS[d.type] and CRAFTERS[pk.type] then
        run.hands = run.hands or {}
        local f = run.hands[pk.unit_number] or {}
        f[d.unit_number] = true
        run.hands[pk.unit_number] = f
      end
    end
  end

  -- --flow's belt gates: on each overflow sink's belt, the belt just past the last one an inserter takes from (walking
  -- up from the sink). Closed while a machine on that network waits for an overflow item, or while no machine dropping
  -- onto it stands full: items queue in front of the users instead of passing them to the far end (Py science 2: 549 raw fiber stood at the overflow, 300 tiles past a
  -- cellulose machine waiting for it).
  if run.params.flow and run.pick_belts then
    run.gates = {}
    for _, sk in pairs(run.sinks) do
      local b = sk.overflow and sk.gate.valid and sink_belt(sk)
      if b then
        local seen, queue, head, gate, toward = { [b.unit_number] = true }, { { b, nil } }, 1, nil, {}
        while head <= #queue and head < 3000 do
          local cur, child = queue[head][1], queue[head][2]
          head = head + 1
          -- (Or a splitter: its other side may lead to users, which a gate before it would cut off: chemical science's
          -- borax, 1,237 of it queued in front of a splitter whose other side fed ten machines waiting for it.)
          if run.pick_belts[cur.unit_number] or (cur.type == "splitter" and child) then gate = child; break end
          local ups = (cur.belt_neighbours or {}).inputs or {}
          if cur.type == "underground-belt" and cur.belt_to_ground_type == "output" and cur.underground_belt_neighbour then ups = { cur.underground_belt_neighbour } end
          for _, u in pairs(ups) do
            if u.valid and not seen[u.unit_number] then seen[u.unit_number] = true; toward[u.unit_number] = cur; queue[#queue + 1] = { u, cur } end
          end
        end
        -- (An underground or splitter just past it: the first plain belt on toward the sink, a few tiles of queue more.)
        while gate and gate.type ~= "transport-belt" do gate = toward[gate.unit_number] end
        if gate and gate.type == "transport-belt" then run.gates[#run.gates + 1] = { e = gate, net = run.net_of[gate.unit_number] } end
      end
    end
    -- A belt cannot be stopped by script (disabled_by_script does nothing to it: Py science 2's barrels and chemical
    -- science's chromite sand went on past "closed" gates), but one on a circuit network stops by its enable condition.
    -- Every gate hangs on one empty network (a constant combinator beside the factory, wired by script): "signal-A > 0"
    -- never holds there, so a gate with its enable condition on stands, one with it off runs.
    if #run.gates > 0 then
      local at = surface.find_non_colliding_position("constant-combinator", run.gates[1].e.position, 50, 1)
      local anchor = at and surface.create_entity { name = "constant-combinator", position = at, force = force }
      local ac = anchor and anchor.get_wire_connector(defines.wire_connector_id.circuit_red, true)
      for _, g in pairs(run.gates) do
        g.wired = ac ~= nil and pcall(function()
          g.e.get_wire_connector(defines.wire_connector_id.circuit_red, true).connect_to(ac, false)
          local cb = g.e.get_or_create_control_behavior()
          cb.circuit_condition = { first_signal = { type = "virtual", name = "signal-A" }, comparator = ">", constant = 0 }
          cb.circuit_enable_disable = false
        end)
        if not g.wired then warnings[#warnings + 1] = "a belt gate could not be wired to a circuit network" end
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
  -- The machines that make each overflow item (furnaces, which choose their recipe as they go, aside).
  if not run.makers then
    run.makers = {}
    for _, m in pairs(run.machines or {}) do
      local r = m.recipe and prototypes.recipe[m.recipe]
      if r then for _, p in pairs(r.products) do
        if overflow[p.name] then run.makers[p.name] = run.makers[p.name] or {}; table.insert(run.makers[p.name], m.e) end
      end end
      -- A burner makes its fuel's burnt result too (Py's ash).
      pcall(function()
        local inv = m.e.get_inventory(defines.inventory.fuel)
        for _, it in pairs(inv and inv.get_contents() or {}) do
          local b = prototypes.item[it.name] and prototypes.item[it.name].burnt_result
          if b and overflow[b.name] then run.makers[b.name] = run.makers[b.name] or {}; table.insert(run.makers[b.name], m.e) end
        end
      end)
    end
  end
  -- A surplus: some maker of the item stands with a full output (an item no machine here is known to make: the belt
  -- backing up decides alone).
  -- Per belt network [net] when known: a maker that drops onto it stands full. Any maker anywhere let every overflow of
  -- the item drain (Py science 2: one coke oven blocked on one belt, and the overflow after the fluidized-coke block
  -- took 2/s of coke while the steel and graphite on other belts had none).
  local function full(e)
    return e.valid and (e.status == defines.entity_status.full_output or e.status == defines.entity_status.full_burnt_result_output)
  end
  local function surplus(item, net)
    local ms = run.makers[item]
    if not ms or #ms == 0 then return true end
    local on = false
    if net then
      for _, e in pairs(ms) do
        if e.valid and (run.drops[e.unit_number] or {})[net] then on = true; if full(e) then return true end end
      end
    end
    if on then return false end
    for _, e in pairs(ms) do if full(e) then return true end end
    return false
  end
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
      if caps then s.gate.disabled_by_script = s.inv.get_item_count() >= 20 end
    end
    -- An overflow (params.overflow: items the factory also uses) takes only while the belt into its loader is
    -- backed up and a maker of the item stands full: the factory's own users always take first, however fast they
    -- run, and a belt that runs on past its users to the edge fills up as their buffer instead of draining what
    -- passed them (Py science 2's stone-brick ring, which nothing adds to, starved that way).
    if next(overflow) and s.gate.valid and not s.mixed then
      local b = sink_belt(s)
      if b and b.valid then
        local mine, full, extra = false, false, false
        for i = 1, 2 do
          local ok, line = pcall(function() return b.get_transport_line(i) end)
          if ok and line then
            local net = run.net_of and run.net_of[b.unit_number]
            for _, it in pairs(line.get_contents()) do
              if overflow[it.name] then
                mine = true
                -- (--flow: not while a machine on this network waits for it: a by-product its own chain needs stays for it)
                local waits = run.params.flow and run.short and net and run.short.net[it.name] and run.short.net[it.name][net]
                if surplus(it.name, net) and not waits then extra = true end
              end
            end
            if line.get_item_count() >= 3 then full = true end
          end
        end
        -- Backed up along its own chain: the 8 belts before the loader (or all of a shorter chain) hold items on a lane
        -- (2 or more: a curve's inner lane holds fewer). A maker standing full somewhere is no sign this chain has more
        -- than its users take (Py logistic science's barrels left by one chain's overflow while fillers on other chains
        -- had none).
        local function backed_up()
          local cur, n = b, 0
          while cur and cur.valid and n < 8 do
            local lane_full = false
            for i = 1, 2 do
              local ok, line = pcall(function() return cur.get_transport_line(i) end)
              if ok and line and line.get_item_count() >= 2 then lane_full = true end
            end
            if not lane_full then return false end
            n = n + 1
            local nxt = ((cur.belt_neighbours or {}).inputs or {})[1]
            if not nxt and cur.type == "underground-belt" and cur.belt_to_ground_type == "output" then nxt = cur.underground_belt_neighbour end
            if not nxt then return true end
            cur = nxt
          end
          return true
        end
        if mine or s.overflow then
          s.overflow = true
          if not (full and extra and backed_up()) then s.gate.disabled_by_script = true elseif not (caps and s.inv.get_item_count() >= 20) then s.gate.disabled_by_script = false end
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
    inserters = #run.inserters, belts = #run.belts, fed = run.fed, fed_measure = run.fed_measure, bootstrapped = run.bootstrapped, bootstrapped_measure = run.bootstrapped_measure, debug = run.debug,
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
        -- The belt networks its inserters take from and drop onto (see run.net_of).
        feeds = (function() local o = {}; for n in pairs((run.feeds or {})[e.unit_number] or {}) do o[#o + 1] = n end; return o end)(),
        drops = (function() local o = {}; for n in pairs((run.drops or {})[e.unit_number] or {}) do o[#o + 1] = n end; return o end)(),
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
              -- Where the game puts the box's pipe connections (the tile a pipe must be on), and whether one is there.
              local conns = {}
              local okc, errc = pcall(function()
                for _, c in pairs(e.get_fluid_box_pipe_connections(k)) do
                  local at = c.target_position or c.position
                  if at then
                    local tp = bp_pos(run, at)
                    conns[#conns + 1] = { x = tp.x, y = tp.y, linked = c.target ~= nil, type = c.connection_type, flow = c.flow_direction }
                  end
                end
              end)
              if not okc then conns = { { error = tostring(errc) } } end
              o[#o + 1] = { io = proto and proto.production_type or "?", name = (f and f.name) or (flt and flt.name) or nil,
                amount = f and f.amount or 0, temperature = f and f.temperature or nil, conns = conns }
            end
          end)
          return o end)(),
      }
    end
  end
  for _, s in pairs(run.sinks) do
    local rates = {}
    for k, v in pairs(s.items) do rates[k] = v / secs end
    local b = s.gate.valid and sink_belt(s)
    report.outputs[#report.outputs + 1] = { x = s.pos.x, y = s.pos.y, per_s = rates, net = b and (run.net_of or {})[b.unit_number] or nil,
      overflow = s.overflow or nil, mixed = s.mixed or nil, ["local"] = s["local"] or nil }
  end
  for _, s in pairs(run.sources) do
    local rates = {}
    for k, v in pairs(s.items) do rates[k] = v / secs end
    for _, k in pairs(s.keep) do rates[key(k.name, k.quality)] = rates[key(k.name, k.quality)] or 0 end
    local b = s.loader and s.loader.valid and ((s.loader.belt_neighbours or {}).outputs or {})[1]
    report.inputs[#report.inputs + 1] = { x = s.pos.x, y = s.pos.y, per_s = rates, net = b and (run.net_of or {})[b.unit_number] or nil }
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
      report.dump_belts[#report.dump_belts + 1] = { n = b.e.name, x = p.x, y = p.y, d = b.e.direction, t = b.e.type == "underground-belt" and b.e.belt_to_ground_type or nil, lanes = lanes,
        net = (run.net_of or {})[b.e.unit_number] }
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
  -- Robot links (blocks.bot_links): the logistic networks, their robots, and what the provider and requester chests hold.
  local okb, errb = pcall(function()
    local surface = run.surface
    local nets, robots, idle, charging = {}, 0, 0, 0
    for _, r in pairs(surface.find_entities_filtered { type = "roboport" }) do
      local n = r.logistic_network
      if n and not nets[n.network_id] then
        nets[n.network_id] = true
        robots = robots + n.all_logistic_robots; idle = idle + n.available_logistic_robots
      end
    end
    local count = 0; for _ in pairs(nets) do count = count + 1 end
    local req, reqEmpty, prov, provItems, unnetworked = 0, 0, 0, 0, 0
    for _, c in pairs(surface.find_entities_filtered { type = "logistic-container" }) do
      local inv = c.get_inventory(defines.inventory.chest)
      local n = inv and inv.get_item_count() or 0
      if c.prototype.logistic_mode == "requester" then
        req = req + 1; if n == 0 then reqEmpty = reqEmpty + 1 end
      else
        prov = prov + 1; provItems = provItems + n
      end
      if not c.logistic_network then unnetworked = unnetworked + 1 end
    end
    -- A few empty requesters: what they ask for, and what the network has of it.
    local samples = {}
    for _, c in pairs(surface.find_entities_filtered { type = "logistic-container" }) do
      if #samples >= 6 then break end
      local inv = c.get_inventory(defines.inventory.chest)
      if c.prototype.logistic_mode == "requester" and inv and inv.get_item_count() == 0 then
        local asks = {}
        local okp, pt = pcall(function() return c.get_requester_point() end)
        if okp and pt then
          for _, sec in pairs(pt.sections or {}) do
            for _, f in pairs(sec.filters or {}) do
              if f.value then
                local name = type(f.value) == "table" and f.value.name or f.value
                local n = c.logistic_network
                asks[#asks + 1] = { item = name, min = f.min, in_network = n and n.get_item_count(name) or -1, active = sec.active }
              end
            end
          end
          samples[#samples + 1] = { x = bp_pos(run, c.position).x, y = bp_pos(run, c.position).y, enabled = pt.enabled, asks = asks,
            targeted = pt.targeted_items_deliver and table_size(pt.targeted_items_deliver) or nil }
        else
          samples[#samples + 1] = { x = bp_pos(run, c.position).x, y = bp_pos(run, c.position).y, error = tostring(pt) }
        end
      end
    end
    if count > 0 or req > 0 then
      report.bots = { networks = count, robots = robots, robots_idle = idle, requesters = req, requesters_empty = reqEmpty,
        providers = prov, items_in_providers = provItems, chests_outside_networks = unnetworked, empty_requesters = samples }
    end
  end)
  if not okb then report.bots_error = tostring(errb) end
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

-- params.feed_short: every machine standing short of an ingredient gets it (as a warm start does: two crafts' worth),
-- and what was put in is counted per item (run.fed; run.fed_measure while measuring). With supply never short, what
-- still stands is the layout's own fault (an output with no way out, an inserter missing); what had to be fed shows
-- where the factory's own supply falls short, without everything behind it starving too.
local FEED_SHORT = { [defines.entity_status.no_ingredients] = true, [defines.entity_status.item_ingredient_shortage] = true,
  [defines.entity_status.fluid_ingredient_shortage] = true }
local function feed_temperature(run, ing)
  local cands = {}
  for _, t in pairs((run.params.seed_temps or {})[ing.name] or {}) do cands[#cands + 1] = t end
  cands[#cands + 1] = prototypes.fluid[ing.name].default_temperature
  for _, c in ipairs(cands) do
    if (ing.minimum_temperature == nil or c >= ing.minimum_temperature) and (ing.maximum_temperature == nil or c <= ing.maximum_temperature) then return c end
  end
  return ing.minimum_temperature or cands[1]
end

-- "full": as much as a warm start gives, whenever short (does the layout work with supply never short?). "plan": each
-- machine at most its planned use (params.feed_rates, per second, from two crafts' worth at the start), so what was fed
-- is what the factory's own supply left short of the plan.
local function feed_short(run)
  local mode = run.params.feed_short
  if not mode or game.tick % 60 ~= 0 then return end
  -- params.feed_stop: feeding ends that many game seconds in (a bootstrap, as a player fills a new factory's loops by
  -- hand); measured after it, the factory shows whether it keeps itself going.
  if run.params.feed_stop and (game.tick - run.t0) / 60 >= run.params.feed_stop then return end
  run.fed = run.fed or {}
  run.fed_measure = run.fed_measure or {}
  local function record(ing, d)
    local k = ing.type == "item" and ing.name or ("[" .. ing.name .. "]")
    run.fed[k] = (run.fed[k] or 0) + d
    if run.phase == "measure" then run.fed_measure[k] = (run.fed_measure[k] or 0) + d end
  end
  for _, m in pairs(run.machines) do
    local e = m.e
    local r = e.valid and (e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe]))
    if r then pcall(function()
      local function count(ing) return ing.type == "item" and held(e, ing.name) or (e.get_fluid_count(ing.name) or 0) end
      if mode == "full" then
        if not FEED_SHORT[e.status] then return end
        local before = {}
        for _, ing in pairs(r.ingredients) do before[ing.name] = count(ing) end
        warm_start(run, e, r)
        for _, ing in pairs(r.ingredients) do local d = count(ing) - before[ing.name]; if d > 0 then record(ing, d) end end
        return
      end
      local rates = (run.params.feed_rates or {})[r.name] or {}
      m.fb = m.fb or {}
      for _, ing in pairs(r.ingredients) do
        local rate = rates[ing.name] or 0
        m.fb[ing.name] = math.min((m.fb[ing.name] or ing.amount * 2) + rate, math.max(ing.amount * 2, rate * 60))
      end
      -- Only a machine standing short for a while: 10 s, or two of its planned crafts' time (between deliveries a
      -- machine waits as a matter of course, and feeding it then took the belts' place: raw ore-quartz, which the edge
      -- brings without limit, came out fed at 100% of its use).
      if not FEED_SHORT[e.status] then m.short_s = 0; return end
      m.short_s = (m.short_s or 0) + 1
      local c = (run.params.feed_crafts or {})[r.name]
      if m.short_s < math.max(10, c and c > 0 and 2 / c or 10) then return end
      m.short_s = 0
      -- Only what it lacks for a craft (topping up every ingredient counted those it had plenty of as fed).
      for _, ing in pairs(r.ingredients) do
        local give = count(ing) < ing.amount and math.min(ing.amount * 2 - count(ing), m.fb[ing.name]) or 0
        if ing.type == "item" then give = math.floor(give) end
        if give > 0 then
          local done = ing.type == "item" and e.insert { name = ing.name, count = give }
            or e.insert_fluid { name = ing.name, amount = give, temperature = feed_temperature(run, ing) }
          if done and done > 0 then m.fb[ing.name] = m.fb[ing.name] - done; record(ing, done) end
        end
      end
    end) end
  end
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
  feed_short(run)
  -- params.governor (recipe -> planned crafts/s per machine): a machine ahead of governor_factor (2) times its plan,
  -- plus 10 crafts, waits while a machine fed from the same belt stands short of one of its item ingredients (one item
  -- runs on many separate belts; holding a barrel filler for one on another belt starved its own users): it takes no more than its
  -- share while others go without, as it would once the belts and pipes after it were full (Py logistic science: a
  -- zogna incubator early on the petri-dish belt, its fluid filling a 250-tile pipe, ran at 7.6 times its plan and
  -- took every dish the bacteria machine further on waited for). Holding every machine ahead of 1.5 times its plan
  -- instead also slowed the loops that must run ahead to fill up (bones and bonemeal, barrels), and they jammed.
  -- params.pull: a machine runs only while a machine that uses one of its products stands short of it, on a belt network
  -- it drops onto (or one it hands to directly; for a fluid, anywhere), or it makes a target. Production follows demand:
  -- a machine whose every product has a way out (overflows, by-product drains) otherwise never stops and eats inputs
  -- others need (Py logistic science: a sand washer at 16 times its plan), and planning can rarely pick one product to
  -- stop it, as nearly every Py product feeds back into the others through loops. Unlike holding machines to their plan,
  -- this lets them run ahead for as long as users wait, filling long belts.
  -- What machines stand short of, every second (for --pull and --flow): item -> belt networks along which one waits for
  -- it, machine -> items it waits for, fluids one waits for. (A machine switched off waits for nothing.)
  -- Fluid demand by pipe network: fluid -> { [segment id] = true, any = true when a machine's box had none }. A fluid
  -- maker counted as wanted while a machine anywhere stood short of its fluid, even on pipes it does not feed: Py
  -- production science's coal gas plant ran at ten times its plan for a far consumer of its gas, until the coke it makes
  -- as well filled its output and stopped it.
  local function segments(e, name)
    local out = {}
    -- (Not every machine has fluid boxes to ask: some have no fluidbox key at all.)
    pcall(function()
      local fb = e.fluidbox
      for i = 1, #fb do
        local f = fb[i]
        local filt = fb.get_filter(i)
        if (f and f.name == name) or (filt and filt.name == name) then
          local ok, id = pcall(function() return fb.get_fluid_segment_id(i) end)
          if ok and id then out[#out + 1] = id end
        end
      end
    end)
    return out
  end
  local function fmark(tab, e, name)
    local t = tab[name] or {}
    tab[name] = t
    local segs = segments(e, name)
    if #segs == 0 then t.any = true end
    for _, id in pairs(segs) do t[id] = true end
  end
  local function fhit(tab, e, name)
    local t = tab and tab[name]
    if not t then return false end
    if t.any then return true end
    for _, id in pairs(segments(e, name)) do if t[id] then return true end end
    return false
  end
  run.fhit = fhit
  if (run.params.pull or run.params.flow or run.params.bootstrap) and game.tick % 60 == 0 and run.drops then
    local SHORT = { [defines.entity_status.no_ingredients] = true, [defines.entity_status.item_ingredient_shortage] = true,
      [defines.entity_status.fluid_ingredient_shortage] = true, [defines.entity_status.no_fuel] = true }
    local short_net, short_at, short_fluid, fluid_at = {}, {}, {}, {}
    local function wait_for(e, item)
      local sn = short_net[item] or {}
      for net in pairs((run.feeds or {})[e.unit_number] or {}) do sn[net] = true end
      short_net[item] = sn
      short_at[e.unit_number] = short_at[e.unit_number] or {}
      short_at[e.unit_number][item] = true
    end
    -- params.debug_at ("x,y" machine positions): each second's pull inputs and decision for those, in the report.
    local dbg = {}
    for _, k in pairs(run.params.debug_at or {}) do dbg[k] = true end
    local function dlog(m, what)
      if not dbg[m.pos.x .. "," .. m.pos.y] then return end
      run.debug = run.debug or {}
      -- (The last 400 lines: the steady state, not the first minutes.)
      if #run.debug >= 400 then table.remove(run.debug, 1) end
      run.debug[#run.debug + 1] = game.tick .. " " .. m.pos.x .. "," .. m.pos.y .. " " .. what
    end
    run.dlog = dlog
    for _, m in pairs(run.machines) do
      local e = m.e
      if e.valid and dbg[m.pos.x .. "," .. m.pos.y] then
        local r = e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe])
        local ings = {}
        for _, ing in pairs(r and r.ingredients or {}) do
          ings[#ings + 1] = ing.name .. ":" .. ing.type .. ":" .. tostring(ing.type == "item" and held(e, ing.name) or e.get_fluid_count(ing.name)) .. "/" .. ing.amount
        end
        dlog(m, "status " .. tostring(e.status) .. " off " .. tostring(e.disabled_by_script) .. " recipe " .. tostring(r and r.name) .. " " .. table.concat(ings, " "))
      end
      if e.valid and not e.disabled_by_script and SHORT[e.status] then
        local r = e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe])
        if r then
          -- (A burner without fuel waits for its fuel, which is no ingredient of the recipe.)
          if e.status == defines.entity_status.no_fuel then for _, f in pairs(fuel_items(run, r)) do wait_for(e, f) end end
          for _, ing in pairs(r.ingredients) do
            if ing.type == "item" then
              if held(e, ing.name) < ing.amount then wait_for(e, ing.name) end
            elseif (e.get_fluid_count(ing.name) or 0) < ing.amount then
              fmark(short_fluid, e, ing.name)
              fluid_at[e.unit_number] = fluid_at[e.unit_number] or {}
              fluid_at[e.unit_number][ing.name] = true
            end
          end
        end
      end
    end
    -- What full makers push: a machine standing with a full output gets the users of what is stuck in it going (on
    -- the belts it drops onto, the machines it hands to; a fluid anywhere). Py logistic science: the scrondrix renderer
    -- stood full of cages, and the caging machine, the cages' only user, was off, since the renderer it makes for was
    -- full, not waiting: the breeding ring stopped for good.
    local push_net, push_at, push_fluid = {}, {}, {}
    for _, m in pairs(run.machines) do
      local e = m.e
      if e.valid and not e.disabled_by_script and (e.status == defines.entity_status.full_output
          or e.status == defines.entity_status.waiting_for_space_in_destination
          or e.status == defines.entity_status.full_burnt_result_output) then
        local r = e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe])
        local out = r and e.get_output_inventory()
        -- (What its fuel burns to, stuck in its burnt result slot, too: Py's basic oxygen furnace stood with its ash
        -- slot full, the ash belt's one user switched off, and the slacked lime behind it never came.)
        if r and e.status == defines.entity_status.full_burnt_result_output then
          local burnt = e.get_burnt_result_inventory()
          for k, c in pairs(burnt and burnt.get_contents() or {}) do
            local name = type(c) == "table" and c.name or k
            local pn = push_net[name] or {}
            for net in pairs((run.drops or {})[e.unit_number] or {}) do pn[net] = true end
            push_net[name] = pn
            for u in pairs((run.hands or {})[e.unit_number] or {}) do
              push_at[u] = push_at[u] or {}
              push_at[u][name] = true
            end
          end
        end
        if r then
          -- (A fluid only when no item is what stands in the way, and only one it holds: Py chemical science's tar
          -- refinery stood full of middle oil, its other three fluids drained.)
          local items_stuck = false
          for _, p in pairs(r.products) do
            if p.type == "item" and out and out.get_item_count(p.name) > 0 then items_stuck = true end
          end
          for _, p in pairs(r.products) do
            if p.type == "fluid" then
              if not items_stuck and (e.get_fluid_count(p.name) or 0) > 0 then fmark(push_fluid, e, p.name) end
            elseif out and out.get_item_count(p.name) > 0 then
              local pn = push_net[p.name] or {}
              for net in pairs((run.drops or {})[e.unit_number] or {}) do pn[net] = true end
              push_net[p.name] = pn
              for u in pairs((run.hands or {})[e.unit_number] or {}) do
                push_at[u] = push_at[u] or {}
                push_at[u][p.name] = true
              end
            end
          end
        end
      end
    end
    -- params.pull_ahead (N > 1): for pull, a machine working or waiting with less than N crafts' worth of an item
    -- counts as wanting it too, so its makers run ahead of it instead of starting only once it has stopped (a maker's
    -- craft and the belt trip then overlap the user's craft).
    local low_net, low_at = short_net, short_at
    local ahead = run.params.pull_ahead or 1
    if ahead > 1 then
      low_net, low_at = {}, {}
      for item, nets in pairs(short_net) do local c = {}; for n in pairs(nets) do c[n] = true end; low_net[item] = c end
      for u, items in pairs(short_at) do local c = {}; for i in pairs(items) do c[i] = true end; low_at[u] = c end
      for _, m in pairs(run.machines) do
        local e = m.e
        if e.valid and not e.disabled_by_script and e.status == defines.entity_status.working then
          local r = e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe])
          if r then
            for _, ing in pairs(r.ingredients) do
              if ing.type == "item" and held(e, ing.name) < ahead * ing.amount then
                local ln = low_net[ing.name] or {}
                for net in pairs((run.feeds or {})[e.unit_number] or {}) do ln[net] = true end
                low_net[ing.name] = ln
                low_at[e.unit_number] = low_at[e.unit_number] or {}
                low_at[e.unit_number][ing.name] = true
              end
            end
          end
        end
      end
    end
    -- params.pull_stock (N > 0): every machine, switched off or not, keeps N crafts' worth of each ingredient, so its
    -- makers run until it holds that much. Without it a machine switched off waits for nothing: whole chains stayed off
    -- because the machine above was off (Py production science: ten levels of uranium enrichment, all off for good, and
    -- three quarters of the time off on every level 2 to 8 steps from the target), and when demand finally came each
    -- level started from empty, one belt trip at a time. With it the chain stays primed: a small, bounded buffer at
    -- every step, not everything on.
    local stock = run.params.pull_stock or 0
    local low_fluid = short_fluid
    if stock > 0 then
      low_fluid = {}
      for f, t in pairs(short_fluid) do local c = {}; for k, v in pairs(t) do c[k] = v end; low_fluid[f] = c end
      if low_net == short_net then
        low_net, low_at = {}, {}
        for item, nets in pairs(short_net) do local c = {}; for n in pairs(nets) do c[n] = true end; low_net[item] = c end
        for u, items in pairs(short_at) do local c = {}; for i in pairs(items) do c[i] = true end; low_at[u] = c end
      end
      for _, m in pairs(run.machines) do
        local e = m.e
        if e.valid then
          local r = e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe])
          if r then
            for _, ing in pairs(r.ingredients) do
              if ing.type == "item" then
                if held(e, ing.name) < stock * ing.amount then
                  local ln = low_net[ing.name] or {}
                  for net in pairs((run.feeds or {})[e.unit_number] or {}) do ln[net] = true end
                  low_net[ing.name] = ln
                  low_at[e.unit_number] = low_at[e.unit_number] or {}
                  low_at[e.unit_number][ing.name] = true
                end
              elseif (e.get_fluid_count(ing.name) or 0) < stock * ing.amount then
                fmark(low_fluid, e, ing.name)
              end
            end
          end
        end
      end
    end
    run.short = { net = short_net, at = short_at, fluid = short_fluid, fluid_at = fluid_at, push_net = push_net, push_at = push_at, push_fluid = push_fluid,
      low_net = low_net, low_at = low_at, low_fluid = low_fluid }
  end
  if run.params.pull and game.tick % 60 == 0 and run.short then
    local short_net, short_at, short_fluid = run.short.low_net or run.short.net, run.short.low_at or run.short.at, run.short.low_fluid or run.short.fluid
    local target = {}
    for _, n in pairs(run.params.pull_wanted or {}) do target[n] = true end
    run.pulled_off = 0
    for _, m in pairs(run.machines) do
      local e = m.e
      local r = e.valid and (e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe]))
      if r then
        local drops, hands = run.drops[e.unit_number] or {}, (run.hands or {})[e.unit_number] or {}
        local wanted, known = false, false
        -- (What its fuel burns to counts as made too: Py automation science's planter boxes wait for the ash of the
        -- molten salt furnace's fuel, which no one uses the molten salt of.)
        local products = {}
        for _, p in pairs(r.products) do products[#products + 1] = p end
        for _, f in pairs(fuel_items(run, r)) do
          local b = prototypes.item[f].burnt_result
          if b then products[#products + 1] = { name = b.name, type = "item" } end
        end
        for _, p in pairs(products) do
          if target[p.name] then wanted = true; known = true
          elseif p.type == "fluid" then
            known = true
            if fhit(short_fluid, e, p.name) then wanted = true end
          else
            local sn = short_net[p.name]
            for net in pairs(drops) do known = true; if sn and sn[net] then wanted = true end end
            for u in pairs(hands) do known = true; if short_at[u] and short_at[u][p.name] then wanted = true end end
          end
          if wanted then break end
        end
        if not wanted then
          local feeds, pushed = (run.feeds or {})[e.unit_number] or {}, run.short.push_at[e.unit_number] or {}
          for _, ing in pairs(r.ingredients) do
            if ing.type == "fluid" then
              if fhit(run.short.push_fluid, e, ing.name) then wanted = true end
            elseif pushed[ing.name] then
              wanted = true
            else
              local pn = run.short.push_net[ing.name]
              if pn then for net in pairs(feeds) do if pn[net] then wanted = true; break end end end
            end
            if wanted then known = true; break end
          end
        end
        -- (A machine whose outputs could not be traced keeps running.)
        local off = known and not wanted
        if run.dlog then
          local ds = {}
          for n in pairs(drops) do ds[#ds + 1] = tostring(n) end
          local sw = {}
          for _, p in pairs(r.products) do
            local sn = short_net[p.name]
            local ns = {}
            if sn then for n in pairs(sn) do ns[#ns + 1] = tostring(n) end end
            sw[#sw + 1] = p.name .. "<-[" .. table.concat(ns, " ") .. "]"
          end
          run.dlog(m, "pull recipe " .. r.name .. " drops [" .. table.concat(ds, " ") .. "] waited " .. table.concat(sw, "; ") .. " known " .. tostring(known) .. " wanted " .. tostring(wanted))
        end
        if off then run.pulled_off = run.pulled_off + 1 end
        if off or e.disabled_by_script then e.disabled_by_script = off end
      end
    end
  end
  -- params.flow: quotas. A machine that has taken more of an item or fluid than its plan allows so far (flow_rates: per
  -- second, times 1.5, plus two crafts' worth) is held while a machine behind its own quota stands short of it, anywhere
  -- in the factory: its inserters bringing that item stop, or for a fluid the machine itself. No user takes what those
  -- behind plan need (Py logistic science: the petri-dish-bacteria machine took all 100 dishes, 12 times its plan, and
  -- the zogna incubator after it got none; meat-to-cyanic took meat at 100 times its plan from its own belt off the
  -- renderer, short of meat itself, while the animal sample on another belt got none). One nobody behind plan waits
  -- behind takes what it likes.
  if run.params.flow and game.tick % 60 == 0 and run.short and run.into then
    local secs = (game.tick - run.t0) / 60
    run.quota_held, run.quota_stopped = 0, 0
    -- Pass 1: what each machine took against its quota.
    local over, behind_short = {}, {}
    for _, m in pairs(run.machines) do
      local e = m.e
      local r = e.valid and (e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe]))
      if r then
        local rates = (run.params.flow_rates or {})[r.name] or {}
        m.fq = m.fq or { pf0 = e.products_finished, c0 = {}, item_of = {} }
        local mine = run.short.at[e.unit_number] or {}
        local o = {}
        for _, ing in pairs(r.ingredients) do
          local c0 = m.fq.c0[ing.name]
          -- (A fluid held in its box counts up to two crafts' worth: Py's olefin barreler filled its box with 1000
          -- olefin while it had no barrels, stood over its quota for hours, and the simik farms waiting for its
          -- barrels never got one.)
          local now = ing.type == "item" and held(e, ing.name) or math.min(e.get_fluid_count(ing.name) or 0, 2 * ing.amount)
          if c0 == nil then c0 = now; m.fq.c0[ing.name] = c0 end
          local taken = (e.products_finished - m.fq.pf0) * ing.amount + now - c0
          -- params.quota_window (seconds): what it took in that last stretch, not since the start, so that running
          -- ahead early on does not lock a machine out for hours (chemical science's REE concentrate made 10 crafts
          -- against a plan of 2 in 20 minutes at the start, then stood held while reduction waited for its sand).
          local span = secs
          if run.params.quota_window then
            m.fq.hist = m.fq.hist or {}
            local h = m.fq.hist[ing.name]
            if not h then h = { { tick = game.tick, taken = taken } }; m.fq.hist[ing.name] = h end
            if game.tick - h[#h].tick >= 3600 then h[#h + 1] = { tick = game.tick, taken = taken } end
            while #h > 1 and game.tick - h[2].tick >= run.params.quota_window * 60 do table.remove(h, 1) end
            span = (game.tick - h[1].tick) / 60
            taken = taken - h[1].taken
          end
          if taken > (rates[ing.name] or 0) * span * 1.5 + ing.amount * 2 then
            o[ing.name] = true
          elseif mine[ing.name] or (ing.type == "fluid" and run.short.fluid_at[e.unit_number] and run.short.fluid_at[e.unit_number][ing.name]) then
            behind_short[ing.name] = (behind_short[ing.name] or 0) + 1
          end
        end
        over[e.unit_number] = o
        m.fq.r = r
      end
    end
    -- Pass 2: hold those ahead while someone behind waits.
    for _, m in pairs(run.machines) do
      local e = m.e
      local o = e.valid and over[e.unit_number]
      if o then
        local capped, stop = {}, false
        -- (Not what a full maker pushes to it: taking that stops no one behind.)
        local feeds, pushed = (run.feeds or {})[e.unit_number] or {}, run.short.push_at[e.unit_number] or {}
        local function is_pushed(ing)
          if ing.type == "fluid" then return run.fhit(run.short.push_fluid, e, ing.name) end
          if pushed[ing.name] then return true end
          local pn = run.short.push_net[ing.name]
          if pn then for net in pairs(feeds) do if pn[net] then return true end end end
          return false
        end
        for _, ing in pairs(m.fq.r.ingredients) do
          if o[ing.name] and behind_short[ing.name] and not is_pushed(ing) then
            if ing.type == "fluid" then stop = true else capped[ing.name] = true end
          end
        end
        -- A returned catalyst stuck in its output (it stands full): it takes that item only from its own loop until
        -- the loop drains. Py production's ulric corral takes a bedding and gives 0 to 1 back onto a two-tile belt
        -- into its own input; a supply belt kept that input full, the loop filled, and the corral stopped for good.
        local returned = {}
        if e.status == defines.entity_status.full_output then
          local out = e.get_output_inventory()
          for _, ing in pairs(m.fq.r.ingredients) do
            if ing.type == "item" and out and out.get_item_count(ing.name) > 0 then returned[ing.name] = true end
          end
        end
        local drops = (run.drops or {})[e.unit_number] or {}
        for _, i in pairs(run.into[e.unit_number] or {}) do
          if i.valid then
            local h = i.held_stack
            if h and h.valid_for_read then m.fq.item_of[i.unit_number] = h.name end
            local it = m.fq.item_of[i.unit_number]
            local net = (run.ins_net or {})[i.unit_number]
            -- (Never one taking from its own return loop, a belt it drops onto: held, its returns could not come back.)
            local own_loop = net ~= nil and drops[net] == true
            local off = it ~= nil and not own_loop and (capped[it] == true or (returned[it] == true and net ~= nil))
            if off then run.quota_held = run.quota_held + 1 end
            if off or i.disabled_by_script then i.disabled_by_script = off end
          end
        end
        if stop then run.quota_stopped = run.quota_stopped + 1; e.disabled_by_script = true end
      end
    end
  end
  -- --flow: a starved loop gets its carriers. A machine that has waited two minutes running for a loop's carrier
  -- (barrels, cages, bricks), while no maker of it stands with a full output, gets five minutes of its planned use (at
  -- least a craft's worth); then two more minutes. Py science 2's barrel loop never started: its water-barrel filler had
  -- no empty barrels, and the animals that give barrels back get them only with water barrels. (A ring with too many
  -- locks: a full maker stops this.)
  -- (params.bootstrap: this alone, under another flow control such as the factory-flow mod: a player's carriers.)
  if (run.params.flow or run.params.bootstrap) and game.tick % 60 == 0 and run.short then
    local carrier = {}
    for _, n in pairs(run.params.seed_carriers or {}) do carrier[n] = true end
    local full = {}
    for _, m in pairs(run.machines) do
      local e = m.e
      if e.valid and (e.status == defines.entity_status.full_output or e.status == defines.entity_status.waiting_for_space_in_destination) then
        local r = e.get_recipe()
        if r then for _, p in pairs(r.products) do full[p.name] = true end end
      end
    end
    run.bootstrapped = run.bootstrapped or {}
    for _, m in pairs(run.machines) do
      local e = m.e
      local wants = e.valid and run.short.at[e.unit_number]
      m.cshort = m.cshort or {}
      for item in pairs(m.cshort) do if not (wants and wants[item]) then m.cshort[item] = nil end end
      if wants then
        local r = e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe])
        for item in pairs(wants) do
          if carrier[item] and r then
            m.cshort[item] = m.cshort[item] or game.tick
            if game.tick - m.cshort[item] >= 7200 and not full[item] then
              local amount = 1
              for _, ing in pairs(r.ingredients) do if ing.name == item then amount = ing.amount end end
              local rate = ((run.params.demand_of or {})[r.name] or {})[item] or 0
              local ok, done = pcall(function() return e.insert { name = item, count = math.max(amount, math.ceil(rate * 300)) } end)
              if ok and done and done > 0 then
                run.bootstrapped[item] = (run.bootstrapped[item] or 0) + done
                if run.phase == "measure" then
                  run.bootstrapped_measure = run.bootstrapped_measure or {}
                  run.bootstrapped_measure[item] = (run.bootstrapped_measure[item] or 0) + done
                end
              end
              m.cshort[item] = game.tick
            end
          end
        end
      end
    end
  end
  if run.params.flow and game.tick % 60 == 0 and run.short and run.gates then
    local overflow = {}
    for _, n in pairs(run.params.overflow or {}) do overflow[n] = true end
    run.gates_closed = 0
    for _, g in pairs(run.gates) do
      if g.e.valid then
        -- Closed too unless a machine dropping onto the belt stands with a full output: the overflow only takes what
        -- would stop a maker. (Py's wood processing units say "waiting for space in destination" when full.) (Py science 2: barrels the animals give back went past the water-barrel filler while it
        -- was full, on to the sink, which kept the last 54 there, 60 tiles past the filler waiting for them.)
        if not g.makers then
          g.makers = {}
          for _, m in pairs(run.machines) do
            if m.e.valid and (run.drops[m.e.unit_number] or {})[g.net] then g.makers[#g.makers + 1] = m.e end
          end
        end
        local close = true
        for _, e in pairs(g.makers) do
          if e.valid and (e.status == defines.entity_status.full_output or e.status == defines.entity_status.full_burnt_result_output
              or e.status == defines.entity_status.waiting_for_space_in_destination) then close = false; break end
        end
        for item, nets in pairs(run.short.net) do if overflow[item] and nets[g.net] then close = true; break end end
        if close then run.gates_closed = run.gates_closed + 1 end
        if close ~= (g.closed or false) then
          g.closed = close
          if g.wired then pcall(function() g.e.get_or_create_control_behavior().circuit_enable_disable = close end) end
        end
      end
    end
  end
  if run.params.governor and game.tick % 60 == 0 then
    local secs = (game.tick - run.t0) / 60
    local factor = run.params.governor_factor or 2
    -- Item -> belt networks along which some machine stands short of it.
    local starved = {}
    for _, m in pairs(run.machines) do
      local e = m.e
      if e.valid and (e.status == defines.entity_status.item_ingredient_shortage or e.status == defines.entity_status.no_ingredients) then
        local r = e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe])
        local inv = r and e.get_inventory(defines.inventory.crafter_input)
        local feeds = run.feeds and run.feeds[e.unit_number]
        if inv and feeds then
          for _, ing in pairs(r.ingredients) do
            if ing.type == "item" and inv.get_item_count(ing.name) < ing.amount then
              local s = starved[ing.name] or {}
              for net in pairs(feeds) do s[net] = true end
              starved[ing.name] = s
            end
          end
        end
      end
    end
    run.held = 0
    for _, m in pairs(run.machines) do
      local e = m.e
      if e.valid then
        local r = e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe])
        local g = r and run.params.governor[r.name]
        local hold = false
        if g then
          m.gov0 = m.gov0 or e.products_finished
          local feeds = run.feeds and run.feeds[e.unit_number]
          -- (Plus 10 crafts: a machine planned at 0.0001 crafts/s is a few crafts ahead for hours after a warm start, and
          -- holding it at 1 times its plan plus 3 stopped the rare things such machines make.)
          if feeds and e.products_finished - m.gov0 > g * secs * factor + 10 then
            for _, ing in pairs(r.ingredients) do
              local s = ing.type == "item" and starved[ing.name]
              if s then for net in pairs(feeds) do if s[net] then hold = true; break end end end
              if hold then break end
            end
          end
        end
        if hold then run.held = run.held + 1 end
        if hold or e.disabled_by_script then e.disabled_by_script = hold end
      end
    end
  end
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
  -- How it is going: machines working now, the targets' crafts so far, and what flow control holds back.
  local working, made = 0, 0
  local target = {}
  for _, n in pairs(run.params.pull_wanted or run.params.targets or {}) do target[n] = true end
  for _, m in pairs(run.machines or {}) do
    if m.e.valid then
      if m.e.status == defines.entity_status.working then working = working + 1 end
      local r = m.e.get_recipe()
      if r then for _, p in pairs(r.products) do if target[p.name] then made = made + m.e.products_finished end end end
    end
  end
  local st = { phase = run.phase, tick = game.tick, game_s = at / 60, total_s = (run.warmup + run.measure) / 60, speed = game.speed, report = run.phase == "done" and REPORT or nil,
    working = working, machines = #(run.machines or {}), target_crafts = made, off = run.pulled_off, held = run.quota_held, stopped = run.quota_stopped, gates = run.gates_closed }
  -- (Under the factory-flow mod: what it is doing.)
  if remote.interfaces["factory-flow"] then
    local s = remote.call("factory-flow", "status") or {}
    st.off, st.held, st.stopped, st.gates = s.off, s.held, s.stopped, s.gates_closed
  end
  reply(st)
end)
