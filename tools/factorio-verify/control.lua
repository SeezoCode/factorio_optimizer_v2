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
          -- (A furnace has no recipe and so no filter before it is fed: Py's barrelling machine. Its first empty
          -- input box then.)
          local furnace = not f and run.params.prime_full and e.type == "furnace" and e.get_fluid(k) == nil
          if (furnace or (f and f.name == ing.name)) and proto and proto.production_type == "input" then
            -- (params.prime_full: the whole box, as it stands in a factory that has run for a while. A box far
            -- larger than its craft fills before anyone else on the pipe is served: Py logistic science's wax
            -- barreller, planned for a craft in three hours, holds 1,000 wax and had 862 of the first 1,200 made
            -- while the mold machine stood at 18 of its 20 for eight hours, and the casting chain behind it.)
            local full = run.params.prime_full and e.get_fluid_capacity(k) or 0
            if not pcall(function() e.set_fluid(k, { name = ing.name, amount = math.max(full, ing.amount * 2), temperature = t }) end) then
              pcall(function() e.set_fluid(k, { name = ing.name, amount = ing.amount, temperature = t }) end)
            end
          end
        end
      end)
    end
  end
end

-- What the check hands out to loops (report "seeded", by hour in the timeline): a loop that makes its own starter
-- stops drawing on it, and the hour it does is when the loop closed.
local function seeded_count(run, name, n)
  if not n or n <= 0 then return end
  run.seeded = run.seeded or {}
  run.seeded[name] = (run.seeded[name] or 0) + n
  if run.phase == "measure" then
    run.seeded_measure = run.seeded_measure or {}
    run.seeded_measure[name] = (run.seeded_measure[name] or 0) + n
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
            seeded_count(run, ing.name, n)
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
              local now = e.get_fluid(k)
              seeded_count(run, "[" .. ing.name .. "]", (now and now.amount or 0) - (have and have.amount or 0))
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
  -- params.seed_through: while measuring too. A loop the size of the factory had not gone round once in 60 game-hours
  -- from empty (Py logistic science: tin, acetone, fish, lamps, circuits, tin plate), and 26 to 33 recipes that ran at
  -- their rate stood within three hours of the hand-outs ending; what is handed out is counted by the hour instead.
  if not (run.phase == "warmup" or (run.phase == "measure" and run.params.seed_through)) or game.tick % 120 ~= 0 then return end
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

-- How many crafts' expected worth of a product in its output stops a machine taking ingredients: the game's overload
-- multiplier, one more than the crafts it finishes in a second, at least two.
local FULL_OUT_STATUS = { [defines.entity_status.full_output] = true }
local function relief_limit(e, r)
  return math.max(2, math.ceil(e.crafting_speed / r.energy) + 1)
end

-- The pipe networks a machine's fluid storage [i] belongs to (its own segment, or those of the pipes it connects to).
local function box_segments(e, i)
  local out = {}
  pcall(function()
    if e.has_fluid_segment(i) then out[#out + 1] = e.get_fluid_segment_id(i)
    else
      for _, c in pairs(e.get_fluid_box_pipe_connections(i) or {}) do
        local j = c.target_fluidbox_index or 1
        if c.target and c.target.valid and c.target.has_fluid_segment(j) then out[#out + 1] = c.target.get_fluid_segment_id(j) end
      end
    end
  end)
  return out
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
  -- (params.robot: which; else the slowest logistic robot a player can build: Pyanodon hides the base game's, and its
  -- own mk01 comes with green science. The check used to put in the hidden base one, 3 tiles/s and one item a trip.)
  local robot = params.robot
  if not robot then
    local best
    for name, it in pairs(prototypes.item) do
      local pr = it.place_result
      if pr and pr.type == "logistic-robot" and not it.hidden and (not best or (pr.speed or 0) < best.speed) then
        best = { name = name, speed = pr.speed or 0 }
      end
    end
    robot = best and best.name or "logistic-robot"
  end
  run.robot = robot
  if params.robots_per_roboport and params.robots_per_roboport > 0 and prototypes.item[robot] then
    for _, r in pairs(surface.find_entities_filtered { type = "roboport", force = force }) do
      pcall(function() r.insert { name = robot, count = params.robots_per_roboport } end)
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
      if f and (run.params.dead_end or run.params.relief_gate or run.params.relief_built)
          and (f.mode == "remove" or f.mode == "at-most" or (f.mode == "exactly" and (f.percentage or 0) == 0)) then
        -- Under the relief rules every fluid port is a plain pipe end (no filter: it takes nothing) and its pipes
        -- are stock; relief_fluid_gates lets a craft's worth out for a maker it stops. A fluid overflow kept nine
        -- tenths full, or a planned surplus drained without limit, made every fluid look wanted: a lamp maker with
        -- methane and oxygen beside the lamps ran at 5,000 times its plan.
        -- (A plain pipe in the infinity pipe's place. An infinity pipe with no filter is no pipe end: it destroys
        -- what its pipes hold. Tried in the game 2026-11-16: 150 water put into two pipes and such an infinity pipe was
        -- gone three seconds later. Five batches of runs had every fluid port a hole that way.)
        -- (The pipe beside it gives the kind: Py's pipes do not join the plain "pipe".)
        local pos, kind = e.position, "pipe"
        for _, x in pairs(surface.find_entities_filtered { position = pos, radius = 1.2, type = { "pipe", "pipe-to-ground" }, force = force }) do
          if x.type == "pipe" then kind = x.name
          elseif kind == "pipe" and prototypes.entity[(x.name:gsub("%-to%-ground", ""))] then kind = (x.name:gsub("%-to%-ground", "")) end
        end
        e.destroy()
        local np = surface.create_entity { name = kind, position = pos, force = force }
        run.rgates = run.rgates or {}
        if np then run.rgates[#run.rgates + 1] = { e = np, filter = f, open = false }
        else warnings[#warnings + 1] = "a fluid port could not be turned into a pipe end" end
      elseif f and (f.mode == "remove" or ((f.mode == "exactly" or f.mode == "at-most") and (f.percentage or 0) == 0)) then
        e.set_infinity_pipe_filter(nil)
        run.drains[#run.drains + 1] = { e = e, fluid = f.name, pos = bp_pos(run, e.position), amount = 0 }
      elseif f and f.mode == "at-most" and run.params.fluid_gates then
        -- params.fluid_gates: the overflow keeps its pipes at most nine tenths full (see the gates in on_tick).
        run.fgates = run.fgates or {}
        local g = { e = e, filter = f, open = false }
        run.fgates[#run.fgates + 1] = g
        e.set_infinity_pipe_filter({ name = f.name, percentage = 0.9, mode = "at-most", temperature = f.temperature })
      elseif f and f.mode == "at-most" and run.params.overflow_keep then
        -- params.overflow_keep: how full a fluid overflow (an infinity pipe keeping its pipe at most so full) keeps
        -- its pipe, instead of the test copy's half. Users' boxes fill only as far as the pipe is full.
        f.percentage = run.params.overflow_keep
        e.set_infinity_pipe_filter(f)
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
  -- Under the relief rules every port but a target's is a relief port: a leftovers belt and a by-product's planned
  -- surplus too. Left open, whatever went onto them looked wanted for good (a nexelit ore crusher with its tailings
  -- dust on a leftovers belt: 14,700 crafts in four hours, 2,100 times its plan).
  run.relief_mode = (run.params.relief_gate or run.params.relief_built or run.params.dead_end) and true or false
  if run.relief_mode then
    local keep = {}
    for _, at in pairs(run.params.target_at or {}) do local s = nearest_sink(at); if s then keep[s] = true end end
    for _, s in pairs(run.sinks) do
      if not keep[s] then s.overflow = true; s.mixed = nil; s.gate.disabled_by_script = true end
    end
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
      -- params.provider_slots: each provider chest holds at most that many stacks (its inventory bar). Robots carry
      -- only what requesters ask for; the rest of what the makers send piled up in the providers (Py production
      -- science: 186,043 items in 444 providers while 335 of 444 requesters stood empty and their makers' other users
      -- went short).
      if run.params.provider_slots then
        for _, b in pairs(run.params.bots.providers or {}) do
          local c = chest_at(b)
          if c then pcall(function()
            local inv = c.get_inventory(defines.inventory.chest)
            if inv and inv.supports_bar() then inv.set_bar(run.params.provider_slots + 1) end
          end) end
        end
      end
      for _, b in pairs(run.params.bots.providers or {}) do
        local c = chest_at(b)
        if c then for _, i in pairs(run.inserters) do if i.e.valid and inside(c, i.e.drop_position) then join(b.item, belt_at(i.e.pickup_position)) end end end
      end
      run.req_list = {}
      for _, b in pairs(run.params.bots.requesters or {}) do
        local c = chest_at(b)
        if c then for _, i in pairs(run.inserters) do if i.e.valid and inside(c, i.e.pickup_position) then
          local belt = belt_at(i.e.drop_position)
          join(b.item, belt)
          if belt then run.req_list[#run.req_list + 1] = { chest = c, item = b.item, belt = belt } end
        end end end
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
      -- Each machine's inserters onto belts, with the network they drop onto (params.belt_pull).
      if d and pk and parent[d.unit_number] and CRAFTERS[pk.type] then
        run.outs = run.outs or {}
        run.outs[#run.outs + 1] = { e = i.e, m = pk, net = find(d.unit_number) }
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

  -- What each machine drops onto each belt network: its filtered inserters' items, or everything it makes ("*").
  run.onto = {}
  for _, o in pairs(run.outs or {}) do
    if o.e.valid and o.m.valid then
      local t = run.onto[o.m.unit_number] or {}; run.onto[o.m.unit_number] = t
      local items = t[o.net] or {}; t[o.net] = items
      local filtered = false
      pcall(function()
        if o.e.use_filters then for k = 1, o.e.filter_slot_count do
          local f = o.e.get_filter(k)
          if f then filtered = true; items[type(f) == "table" and f.name or f] = true end
        end end
      end)
      if not filtered then items["*"] = true end
    end
  end

  -- params.relief_built: params.relief_gate (with params.relief_demand: and its demand) from real parts, so the rule
  -- runs on the game alone. Every pole gets a red and a green wire along its copper ones: two networks over the factory.
  --  * Demand (green), with params.relief_demand: at each machine, for each ingredient, a decider combinator on a red
  --    wire from the machine (which puts its contents on it) sends a signal of its own for that item and each belt
  --    network the machine takes from (for a fluid: each pipe network of its box; for a hand-over: its maker) while the
  --    machine holds less than a craft's worth.
  --  * Relief (red): each port but a target's has its last belt wired to the nearest pole and enabled only while the
  --    port's own signal is on the red network. For each maker dropping onto that belt network a product it could be
  --    stopped by, a decider near the machine (red in from the machine, green in from the poles, red out to the poles)
  --    sends the port's signal while
  --      (P at its limit AND Q under its limit AND Q's demand on) OR ...
  --    for the products P it drops there and its other used products Q, each with the demand signals it can serve.
  --    Without params.relief_demand the demand term is left out.
  --  * A fluid port has such deciders too; the test copy's port is an infinity pipe, so the script stands in for the
  --    pump: it reads the port's signal off the poles and lets a little out while it is on (relief_fluid_gates).
  -- Limits as in relief_scan.
  if run.params.relief_built or run.params.pull_built then
    local W = defines.wire_connector_id
    local rb = { poles = 0, pole_wires = 0, ports = 0, gates_wired = 0, gates_unwired = 0, deciders = 0, demand_deciders = 0, no_room = 0, dead_ends = 0,
      fluid_ports = 0, fluid_deciders = 0, signals = {}, errors = {} }
    run.rb = rb
    local with_demand = (run.params.relief_demand or run.params.pull_built) and true or false
    local function fail(what, err) rb.errors[what .. ": " .. tostring(err)] = (rb.errors[what .. ": " .. tostring(err)] or 0) + 1 end
    local poles = surface.find_entities_filtered { type = "electric-pole", force = force }
    for _, p in pairs(poles) do
      rb.poles = rb.poles + 1
      local ok, err = pcall(function()
        local c = p.get_wire_connector(W.pole_copper, false)
        for _, conn in pairs(c and c.connections or {}) do
          local q = conn.target.owner
          if q.valid and q.type == "electric-pole" and q.unit_number > p.unit_number then
            if p.get_wire_connector(W.circuit_red, true).connect_to(q.get_wire_connector(W.circuit_red, true), false) then rb.pole_wires = rb.pole_wires + 1 end
            p.get_wire_connector(W.circuit_green, true).connect_to(q.get_wire_connector(W.circuit_green, true), false)
          end
        end
      end)
      if not ok then fail("pole", err) end
    end
    run.rb_anchor = poles[1]
    -- The pole nearest [e] that its [connector] reaches, on the pole wire of colour [color]; wired when [wire].
    local function to_pole(e, connector, color, wire)
      local c = e.get_wire_connector(connector, true)
      local best, bd
      for _, p in pairs(surface.find_entities_filtered { type = "electric-pole", force = force, position = e.position, radius = 24 }) do
        local pc = p.get_wire_connector(color, true)
        if p ~= e and not (rb.loose or {})[p.unit_number] and c.can_wire_reach(pc) then
          local d = (p.position.x - e.position.x) ^ 2 + (p.position.y - e.position.y) ^ 2
          if not bd or d < bd then best, bd = pc, d end
        end
      end
      if best and wire ~= false then return c.connect_to(best, false) end
      return best ~= nil
    end
    -- A port's belt at the factory's edge is often out of a pole's reach (a belt's wire spans 9 tiles): medium poles
    -- are set toward the nearest pole until one reaches (a planner would place them with the port).
    local function wire_to_network(e, connector)
      if to_pole(e, connector, W.circuit_red) then return true end
      local target, td
      for _, p in pairs(poles) do
        if p.valid then
          local d = (p.position.x - e.position.x) ^ 2 + (p.position.y - e.position.y) ^ 2
          if not td or d < td then target, td = p, d end
        end
      end
      if not target then return false end
      rb.loose = rb.loose or {}
      local prev, prev_conn, px, py, chain = e, connector, e.position.x, e.position.y, {}
      for _ = 1, 6 do
        local dx, dy = target.position.x - px, target.position.y - py
        local len = math.sqrt(dx * dx + dy * dy)
        local hop = math.min(7, len)
        local at = surface.find_non_colliding_position("medium-electric-pole", { px + dx / len * hop, py + dy / len * hop }, 2.5, 0.5)
        local np = at and surface.create_entity { name = "medium-electric-pole", position = at, force = force }
        if not np then break end
        rb.helper_poles = (rb.helper_poles or 0) + 1
        chain[#chain + 1] = np
        rb.loose[np.unit_number] = true
        if not prev.get_wire_connector(prev_conn, true).connect_to(np.get_wire_connector(W.circuit_red, true), false) then break end
        if to_pole(np, W.circuit_red, W.circuit_red) then
          for _, q in pairs(chain) do rb.loose[q.unit_number] = nil end
          return true
        end
        prev, prev_conn, px, py = np, W.circuit_red, np.position.x, np.position.y
      end
      return false
    end
    local names = {}
    for n, it in pairs(prototypes.item) do if not it.hidden then names[#names + 1] = n end end
    table.sort(names)
    local next_name = 0
    local function new_signal() next_name = next_name + 1; return { type = "item", name = names[next_name] } end
    local used_by = {}
    for _, o in pairs(run.machines) do
      local ro = o.e.valid and (o.e.get_recipe() or (o.plan_recipe and prototypes.recipe[o.plan_recipe]))
      if ro then for _, ing in pairs(ro.ingredients) do
        used_by[ing.name] = used_by[ing.name] or {}
        used_by[ing.name][o.e.unit_number] = true
      end end
    end
    local onto = run.onto
    -- A decider beside machine [m]: red in from the machine; with [green_in] green in from the poles; its output on
    -- the poles' wire of colour [out_color]. Tried ring by ring around the machine, both ways round.
    local function place_decider(m, green_in, out_color, to_machine)
      local bb = m.bounding_box
      local out_conn = out_color == W.circuit_green and W.combinator_output_green or W.combinator_output_red
      for ring = 1, 10 do
        local x0, y0, x1, y1 = math.floor(bb.left_top.x) - ring, math.floor(bb.left_top.y) - ring, math.ceil(bb.right_bottom.x) + ring - 1, math.ceil(bb.right_bottom.y) + ring - 1
        for x = x0, x1 do for y = y0, y1 do
          if x == x0 or x == x1 or y == y0 or y == y1 then
            for _, dir in pairs({ defines.direction.north, defines.direction.east }) do
              local pos = dir == defines.direction.north and { x + 0.5, y + 1 } or { x + 1, y + 0.5 }
              if surface.can_place_entity { name = "decider-combinator", position = pos, direction = dir, force = force } then
                local d = surface.create_entity { name = "decider-combinator", position = pos, direction = dir, force = force }
                if d then
                  local inp = d.get_wire_connector(W.combinator_input_red, true)
                  -- (And only where a pole powers it: a combinator without power sends nothing. The first run with pull
                  -- built this way stood still for that: 129 of 1,247 deciders had no power.)
                  if d.is_connected_to_electric_network() and inp.can_wire_reach(m.get_wire_connector(W.circuit_red, true)) and (to_machine or to_pole(d, out_conn, out_color, false))
                      and (not green_in or to_pole(d, W.combinator_input_green, W.circuit_green, false)) then
                    inp.connect_to(m.get_wire_connector(W.circuit_red, true), false)
                    -- (to_machine: the output on the machine's own red wire, where its enable condition reads it.)
                    if to_machine then d.get_wire_connector(W.combinator_output_red, true).connect_to(m.get_wire_connector(W.circuit_red, true), false)
                    else to_pole(d, out_conn, out_color) end
                    if green_in then to_pole(d, W.combinator_input_green, W.circuit_green) end
                    local cbm = m.get_or_create_control_behavior()
                    cbm.circuit_read_contents = true
                    -- (With demand: the craft in hand counts as held, so a machine at work is not short of what it
                    -- is using up. Counted as short all through each craft, users kept their makers on for good:
                    -- the chlorine cells at 6.5 times their plan in the first run with pull built.)
                    pcall(function() cbm.include_in_crafting = with_demand end)
                    return d
                  end
                  d.destroy()
                end
              end
            end
          end
        end end
      end
    end
    local RED, GREEN = { red = true, green = false }, { red = false, green = true }
    -- Demand: a signal for each (item, belt network), (fluid, pipe network) and (item, maker handing it over).
    local dsig = {}
    local function demand_signal(key) if not dsig[key] then dsig[key] = new_signal() end return dsig[key] end
    if with_demand then
      local hands_to = {}
      for maker, users in pairs(run.hands or {}) do for u in pairs(users) do hands_to[u] = hands_to[u] or {}; hands_to[u][maker] = true end end
      for _, m in pairs(run.machines) do
        local e = m.e
        local r = e.valid and (e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe]))
        if r then
          for _, ing in pairs(r.ingredients) do
            local ok, err = pcall(function()
              local keys = {}
              if ing.type == "fluid" then
                for i = 1, (e.fluids_count or 0) do
                  local flt = e.get_fluid_filter(i)
                  local proto = e.get_fluid_box_prototype(i)
                  if proto and proto.index == nil and proto[1] then proto = proto[1] end
                  if flt and flt.name == ing.name and proto and proto.production_type ~= "output" then
                    for _, seg in pairs(box_segments(e, i)) do keys["f:" .. ing.name .. ":" .. seg] = true end
                  end
                end
              else
                for net in pairs(run.feeds[e.unit_number] or {}) do keys["i:" .. ing.name .. ":" .. net] = true end
                for maker in pairs(hands_to[e.unit_number] or {}) do keys["h:" .. ing.name .. ":" .. maker] = true end
              end
              if not next(keys) then return end
              local d = place_decider(e, false, W.circuit_green)
              if not d then rb.no_room = rb.no_room + 1; return end
              local outs = {}
              for key in pairs(keys) do outs[#outs + 1] = { signal = demand_signal(key), copy_count_from_input = false, constant = 1 } end
              d.get_or_create_control_behavior().parameters = {
                conditions = { { first_signal = { type = ing.type == "fluid" and "fluid" or "item", name = ing.name }, comparator = "<", constant = math.max(1, math.ceil(ing.amount)), first_signal_networks = RED } },
                outputs = outs }
              rb.demand_deciders = rb.demand_deciders + 1
            end)
            if not ok then fail("demand", err) end
          end
        end
      end
    end
    -- One machine's products as a decider sees them: each with its signal, its limit, and the demand signals the
    -- machine can serve (items: per belt network it drops the item onto, and its hand-overs; fluids: per pipe network).
    local function products_of(m, r)
      local e = m.e
      local own = {}
      for _, ing in pairs(r.ingredients) do own[ing.name] = true end
      local function used(name) for u in pairs(used_by[name] or {}) do if u ~= e.unit_number then return true end end return false end
      local list = {}
      for _, p in pairs(r.products) do
        if not own[p.name] then
          local amount = p.amount or ((p.amount_min or 0) + (p.amount_max or 0)) / 2
          local c
          if p.type == "item" then
            local stack = prototypes.item[p.name] and prototypes.item[p.name].stack_size or 100
            c = { name = p.name, signal = { type = "item", name = p.name },
              limit = math.max(1, math.min(math.ceil(relief_limit(e, r) * amount * (p.probability or p.independent_probability or 1)), stack - math.ceil(amount) + 1)), demand = {} }
            for net, its in pairs(onto[e.unit_number] or {}) do
              if (its[p.name] or its["*"]) and dsig["i:" .. p.name .. ":" .. net] then c.demand[#c.demand + 1] = dsig["i:" .. p.name .. ":" .. net] end
            end
            if dsig["h:" .. p.name .. ":" .. e.unit_number] then c.demand[#c.demand + 1] = dsig["h:" .. p.name .. ":" .. e.unit_number] end
          else
            for i = 1, (e.fluids_count or 0) do
              local proto = e.get_fluid_box_prototype(i)
              if proto and proto.index == nil and proto[1] then proto = proto[1] end
              local flt = e.get_fluid_filter(i)
              if proto and proto.production_type == "output" and flt and flt.name == p.name then
                c = c or { name = p.name, fluid = true, signal = { type = "fluid", name = p.name }, limit = math.max(1, math.floor(e.get_fluid_capacity(i) - amount) + 1), demand = {}, segs = {}, amount = amount }
                for _, seg in pairs(box_segments(e, i)) do
                  c.segs[seg] = true
                  if dsig["f:" .. p.name .. ":" .. seg] then c.demand[#c.demand + 1] = dsig["f:" .. p.name .. ":" .. seg] end
                end
              end
            end
          end
          -- (params.relief_main: only what the machine was planned for counts as wanted.)
          local purpose = run.params.relief_main and (run.params.purpose or {})[r.name]
          if c then c.used = used(p.name) and (not purpose or purpose[p.name] == true); c.any_used = used(p.name); list[#list + 1] = c end
        end
      end
      return list
    end
    -- The conditions under which products [P] of a machine are let out: each stopped by it and another used product
    -- still leaving (and, with demand, asked for).
    local function conditions(P, all)
      local conds, any_used = {}, false
      for _, q in pairs(all) do if q.any_used then any_used = true end end
      for _, p in pairs(P) do
        if not any_used then
          -- (A machine none of whose products is used runs to take something away: whatever stops it leaves.)
          conds[#conds + 1] = { first_signal = p.signal, comparator = ">=", constant = p.limit, first_signal_networks = RED, compare_type = "or" }
        else
          for _, q in pairs(all) do
            if q.used and q.name ~= p.name then
              if not with_demand then
                conds[#conds + 1] = { first_signal = p.signal, comparator = ">=", constant = p.limit, first_signal_networks = RED, compare_type = "or" }
                conds[#conds + 1] = { first_signal = q.signal, comparator = "<", constant = q.limit, first_signal_networks = RED, compare_type = "and" }
              else
                for _, dsg in pairs(q.demand) do
                  conds[#conds + 1] = { first_signal = p.signal, comparator = ">=", constant = p.limit, first_signal_networks = RED, compare_type = "or" }
                  conds[#conds + 1] = { first_signal = q.signal, comparator = "<", constant = q.limit, first_signal_networks = RED, compare_type = "and" }
                  conds[#conds + 1] = { first_signal = dsg, comparator = ">", constant = 0, first_signal_networks = GREEN, compare_type = "and" }
                end
              end
            end
          end
        end
      end
      return conds
    end
    for _, sk in pairs(run.params.relief_built and run.sinks or {}) do
      local b = sk.overflow and not sk.mixed and sk.gate.valid and sink_belt(sk)
      local net = b and b.valid and run.net_of[b.unit_number]
      if net then
        rb.ports = rb.ports + 1
        local sig = new_signal()
        local deciders = 0
        for _, m in pairs(run.machines) do
          local items = m.e.valid and (onto[m.e.unit_number] or {})[net]
          local r = items and (m.e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe]))
          if r then
            local ok, err = pcall(function()
              local all = products_of(m, r)
              local P = {}
              for _, c in pairs(all) do if not c.fluid and (items[c.name] or items["*"]) then P[#P + 1] = c end end
              local conds = conditions(P, all)
              if #conds == 0 then return end
              local d = place_decider(m.e, with_demand, W.circuit_red)
              if not d then rb.no_room = rb.no_room + 1; return end
              d.get_or_create_control_behavior().parameters = { conditions = conds, outputs = { { signal = sig, copy_count_from_input = false, constant = 1 } } }
              rb.deciders = rb.deciders + 1
              deciders = deciders + 1
            end)
            if not ok then fail("decider", err) end
          end
        end
        if deciders == 0 then
          -- (No maker here can be stopped by this belt and wanted for something else: the port never opens.)
          rb.dead_ends = rb.dead_ends + 1
          sk.dead_end = true
        elseif b.type == "transport-belt" and pcall(function()
              assert(wire_to_network(b, W.circuit_red))
              local cb = b.get_or_create_control_behavior()
              cb.circuit_condition = { first_signal = sig, comparator = ">", constant = 0 }
              cb.circuit_enable_disable = true
            end) then
          rb.gates_wired = rb.gates_wired + 1
          sk.wired = true
          rb.signals[#rb.signals + 1] = { x = sk.pos.x, y = sk.pos.y, signal = sig.name, deciders = deciders }
        else
          rb.gates_unwired = rb.gates_unwired + 1
          sk.dead_end = true
        end
      end
    end
    -- Fluid ports: a signal each, sent by a decider at every maker with an output box of that fluid on its pipes.
    for _, g in pairs(run.params.relief_built and run.rgates or {}) do
      local ok, err = pcall(function()
        local seg = g.e.get_fluid_segment_id(1)
        if not seg then g.no_segment = true; return end
        rb.fluid_ports = rb.fluid_ports + 1
        local sig = new_signal()
        g.signal, g.pulse = sig, 0
        for _, m in pairs(run.machines) do
          local r = m.e.valid and (m.e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe]))
          if r then
            local all = products_of(m, r)
            local P = {}
            for _, c in pairs(all) do if c.fluid and c.name == g.filter.name and c.segs[seg] then P[#P + 1] = c; g.pulse = math.max(g.pulse, 2 * c.amount) end end
            if #P > 0 then
              local conds = conditions(P, all)
              if #conds > 0 then
                local d = place_decider(m.e, with_demand, W.circuit_red)
                if d then
                  d.get_or_create_control_behavior().parameters = { conditions = conds, outputs = { { signal = sig, copy_count_from_input = false, constant = 1 } } }
                  rb.fluid_deciders = rb.fluid_deciders + 1
                  g.deciders = (g.deciders or 0) + 1
                else rb.no_room = rb.no_room + 1 end
              end
            end
          end
        end
      end)
      if not ok then fail("fluid port", err) end
    end
    -- params.pull_built: pull from real parts. Each machine works only while its own signal is on its wire: a decider
    -- beside it, green in from the demand wire over the poles, sends that signal onto the machine's red wire while
    -- any demand signal the machine can serve is on (a user on a belt it drops the item onto, a machine it hands to,
    -- a user on its fluid's pipes, holding less than a craft's worth). A machine making a target, one none of whose
    -- products is used, and one no demand signal reaches work whenever they can.
    if run.params.pull_built then
      rb.pull_deciders, rb.pull_free, rb.pull_no_room = 0, 0, 0
      local target = {}
      for _, n in pairs(run.params.targets or {}) do target[n] = true end
      local go = { type = "virtual", name = "signal-R" }
      for _, m in pairs(run.machines) do
        local e = m.e
        local r = e.valid and (e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe]))
        if r then
          local ok, err = pcall(function()
            local free, conds, seen = false, {}, {}
            for _, p in pairs(r.products) do if target[p.name] then free = true end end
            local any_used = false
            for _, c in pairs(products_of(m, r)) do
              if c.any_used then any_used = true end
              for _, dsg in pairs(c.demand) do
                if not seen[dsg.name] then
                  seen[dsg.name] = true
                  conds[#conds + 1] = { first_signal = dsg, comparator = ">", constant = 0, first_signal_networks = GREEN, compare_type = "or" }
                end
              end
            end
            if free or not any_used or #conds == 0 then rb.pull_free = rb.pull_free + 1; return end
            local d = place_decider(e, true, W.circuit_red, true)
            if not d then rb.pull_no_room = rb.pull_no_room + 1; return end
            d.get_or_create_control_behavior().parameters = { conditions = conds, outputs = { { signal = go, copy_count_from_input = false, constant = 1 } } }
            local cb = e.get_or_create_control_behavior()
            cb.circuit_condition = { first_signal = go, comparator = ">", constant = 0 }
            cb.circuit_enable_disable = true
            rb.pull_deciders = rb.pull_deciders + 1
          end)
          if not ok then fail("pull", err) end
        end
      end
    end
    rb.signals_used = next_name
  end

  -- params.relief_exits: params.relief for items from real parts, at the machine itself. Each machine with a product
  -- that could stop it and another used product gets one filter inserter out of it into a chest of its own (an
  -- infinity chest that empties itself here; in a game an active provider chest, or a belt to a port), and a decider
  -- per such product on a red wire from the machine: "P at its limit AND (Q1 under its limit OR Q2 ...)" sends P's own
  -- signal to the inserter, which takes what it is sent (filters set by the circuit). Nothing leaves by a belt or a
  -- port that another maker shares. Fluids stay with params.relief's script, and so do machines with no room for
  -- the inserter and its chest (counted).
  if run.params.relief_exits then
    local W = defines.wire_connector_id
    local rx = { machines = 0, inserters = 0, deciders = 0, no_room = 0, no_decider = 0, errors = {} }
    run.rx = rx
    run.rx_done = {}
    local used_by = {}
    for _, o in pairs(run.machines) do
      local ro = o.e.valid and (o.e.get_recipe() or (o.plan_recipe and prototypes.recipe[o.plan_recipe]))
      if ro then for _, ing in pairs(ro.ingredients) do
        used_by[ing.name] = used_by[ing.name] or {}
        used_by[ing.name][o.e.unit_number] = true
      end end
    end
    local sides = { { 0, -1, defines.direction.south }, { 1, 0, defines.direction.west }, { 0, 1, defines.direction.north }, { -1, 0, defines.direction.east } }
    for _, m in pairs(run.machines) do
      local e = m.e
      local r = e.valid and (e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe]))
      if r then
        local ok, err = pcall(function()
          local own = {}
          for _, ing in pairs(r.ingredients) do own[ing.name] = true end
          local function used(name) for u in pairs(used_by[name] or {}) do if u ~= e.unit_number then return true end end return false end
          local items, all, any_used = {}, {}, false
          for _, p in pairs(r.products) do
            if not own[p.name] then
              local amount = p.amount or ((p.amount_min or 0) + (p.amount_max or 0)) / 2
              local c
              if p.type == "item" then
                local stack = prototypes.item[p.name] and prototypes.item[p.name].stack_size or 100
                c = { name = p.name, signal = { type = "item", name = p.name },
                  limit = math.max(1, math.min(math.ceil(relief_limit(e, r) * amount * (p.probability or p.independent_probability or 1)), stack - math.ceil(amount) + 1)) }
                items[#items + 1] = c
              else
                for i = 1, (e.fluids_count or 0) do
                  local proto = e.get_fluid_box_prototype(i)
                  if proto and proto.index == nil and proto[1] then proto = proto[1] end
                  local flt = e.get_fluid_filter(i)
                  if proto and proto.production_type == "output" and flt and flt.name == p.name then
                    c = { name = p.name, signal = { type = "fluid", name = p.name }, limit = math.max(1, math.floor(e.get_fluid_capacity(i) - amount) + 1) }
                  end
                end
              end
              if c then c.used = used(p.name); if c.used then any_used = true end; all[#all + 1] = c end
            end
          end
          local jobs = {}
          for _, p in pairs(items) do
            local conds = {}
            if not any_used then
              conds[1] = { first_signal = p.signal, comparator = ">=", constant = p.limit }
            else
              for _, q in pairs(all) do
                if q.used and q.name ~= p.name then
                  conds[#conds + 1] = { first_signal = p.signal, comparator = ">=", constant = p.limit, compare_type = "or" }
                  conds[#conds + 1] = { first_signal = q.signal, comparator = "<", constant = q.limit, compare_type = "and" }
                end
              end
            end
            if #conds > 0 then jobs[#jobs + 1] = { p = p, conds = conds } end
          end
          if #jobs == 0 then return end
          rx.machines = rx.machines + 1
          -- The inserter on a free tile against the machine with a free tile behind it for the chest.
          local bb = e.bounding_box
          local x0, y0, x1, y1 = math.floor(bb.left_top.x + 0.5), math.floor(bb.left_top.y + 0.5), math.ceil(bb.right_bottom.x - 0.5) - 1, math.ceil(bb.right_bottom.y - 0.5) - 1
          local ins, chest
          for _, sd in pairs(sides) do
            if ins then break end
            local from, to = (sd[1] ~= 0) and y0 or x0, (sd[1] ~= 0) and y1 or x1
            for k = from, to do
              local ix = sd[1] > 0 and x1 + 1 or sd[1] < 0 and x0 - 1 or k
              local iy = sd[2] > 0 and y1 + 1 or sd[2] < 0 and y0 - 1 or k
              local ip, cp = { ix + 0.5, iy + 0.5 }, { ix + sd[1] + 0.5, iy + sd[2] + 0.5 }
              if surface.can_place_entity { name = "fast-inserter", position = ip, direction = sd[3], force = force }
                  and surface.can_place_entity { name = "infinity-chest", position = cp, force = force } then
                ins = surface.create_entity { name = "fast-inserter", position = ip, direction = sd[3], force = force }
                if ins and not ins.is_connected_to_electric_network() then ins.destroy(); ins = nil end
                chest = ins and surface.create_entity { name = "infinity-chest", position = cp, force = force }
                if ins and chest then break end
                if ins then ins.destroy(); ins = nil end
              end
            end
          end
          if not (ins and chest) then rx.no_room = rx.no_room + 1; return end
          chest.remove_unfiltered_items = true
          rx.inserters = rx.inserters + 1
          local icb = ins.get_or_create_control_behavior()
          ins.use_filters = true
          icb.circuit_set_filters = true
          local cbm = e.get_or_create_control_behavior()
          cbm.circuit_read_contents = true
          pcall(function() cbm.include_in_crafting = (run.params.pull_built or run.params.relief_demand) and true or false end)
          local placed = 0
          for _, j in pairs(jobs) do
            local d
            for ring = 1, 6 do
              if d then break end
              for dx = -ring, ring do for dy = -ring, ring do
                if not d and (math.abs(dx) == ring or math.abs(dy) == ring) then
                  for _, dir in pairs({ defines.direction.north, defines.direction.east }) do
                    local pos = { ins.position.x + dx + (dir == defines.direction.east and 0.5 or 0), ins.position.y + dy + (dir == defines.direction.north and 0.5 or 0) }
                    if not d and surface.can_place_entity { name = "decider-combinator", position = pos, direction = dir, force = force } then
                      local c = surface.create_entity { name = "decider-combinator", position = pos, direction = dir, force = force }
                      if c then
                        local inp, outp = c.get_wire_connector(W.combinator_input_red, true), c.get_wire_connector(W.combinator_output_green, true)
                        if c.is_connected_to_electric_network() and inp.can_wire_reach(e.get_wire_connector(W.circuit_red, true)) and outp.can_wire_reach(ins.get_wire_connector(W.circuit_green, true)) then
                          inp.connect_to(e.get_wire_connector(W.circuit_red, true), false)
                          outp.connect_to(ins.get_wire_connector(W.circuit_green, true), false)
                          c.get_or_create_control_behavior().parameters = { conditions = j.conds, outputs = { { signal = j.p.signal, copy_count_from_input = false, constant = 1 } } }
                          d = c
                        else c.destroy() end
                      end
                    end
                  end
                end
              end end
            end
            if d then placed = placed + 1; rx.deciders = rx.deciders + 1 else rx.no_decider = rx.no_decider + 1 end
          end
          if placed == #jobs then run.rx_done[e.unit_number] = true end
        end)
        if not ok then rx.errors[tostring(err)] = (rx.errors[tostring(err)] or 0) + 1 end
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

-- params.relief_demand: what users stand short of, once a second: a machine holding less than a craft's worth of an
-- ingredient marks that item on the belt networks it takes from (and on a machine that hands to it), that fluid on
-- its box's pipe network. In a blueprint: a decider at each user per ingredient, on a wire from the machine, putting
-- a signal of its own for that item and network on a second wire over the poles.
local function relief_demand(run)
  if not run.params.relief_demand or game.tick % 60 ~= 29 then return end
  local items, fluids, direct = {}, {}, {}
  local hands_to = run.hands_to
  if not hands_to then
    hands_to = {}
    for maker, users in pairs(run.hands or {}) do for u in pairs(users) do hands_to[u] = hands_to[u] or {}; hands_to[u][maker] = true end end
    run.hands_to = hands_to
  end
  for _, m in pairs(run.machines) do
    local e = m.e
    local r = e.valid and (e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe]))
    if r then
      for _, ing in pairs(r.ingredients) do
        if ing.type == "fluid" then
          for i = 1, (e.fluids_count or 0) do
            local flt = e.get_fluid_filter(i)
            local proto = e.get_fluid_box_prototype(i)
            if proto and proto.index == nil and proto[1] then proto = proto[1] end
            if flt and flt.name == ing.name and proto and proto.production_type ~= "output" then
              local f = e.get_fluid(i)
              if (f and f.amount or 0) < ing.amount then
                fluids[ing.name] = fluids[ing.name] or {}
                for _, seg in pairs(box_segments(e, i)) do fluids[ing.name][seg] = true end
              end
            end
          end
        elseif e.get_item_count(ing.name) < ing.amount then
          items[ing.name] = items[ing.name] or {}
          for net in pairs(run.feeds[e.unit_number] or {}) do items[ing.name][net] = true end
          for maker in pairs(hands_to[e.unit_number] or {}) do direct[maker] = direct[maker] or {}; direct[maker][ing.name] = true end
        end
      end
    end
  end
  run.demand = { items = items, fluids = fluids, direct = direct }
end

-- What a decider combinator wired to a machine would see (params.relief, params.relief_gate): which of its products
-- block it, and whether another product of its that some other machine uses is still leaving. A product blocks at
-- the game's own limit, measured in a sandbox 2026-11-16: a machine stops taking ingredients once five crafts'
-- expected worth of a product lies in its output (one item at a 50% chance: 3), whatever the recipe's speed, and
-- reads "output full" from then on; what it already holds it still crafts. A fluid blocks when a craft's worth no
-- longer fits its box. A catalyst, which comes back into the machine's own input, is neither wanted nor in the way.
local function relief_scan(run, m)
  local e = m.e
  local r = e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe])
  if not r then return nil end
  if not run.used_by then
    run.used_by = {}
    for _, o in pairs(run.machines) do
      local ro = o.e.valid and (o.e.get_recipe() or (o.plan_recipe and prototypes.recipe[o.plan_recipe]))
      if ro then for _, ing in pairs(ro.ingredients) do
        run.used_by[ing.name] = run.used_by[ing.name] or {}
        run.used_by[ing.name][o.e.unit_number] = true
      end end
    end
  end
  local function used(name)
    for u in pairs(run.used_by[name] or {}) do if u ~= e.unit_number then return true end end
    return false
  end
  -- params.relief_demand: a product still leaving counts as wanted only while a user it can reach stands short of
  -- it. Room in the output alone is no sign of that: on a belt or pipe shared with other makers, room comes from
  -- whatever lets anything out, another maker's relief too (a propene maker with lead plates as input ran at 93 times
  -- its plan and threw the propene away, while the one user of propene and the other user of lead plates stood short).
  local dm = run.params.relief_demand and (run.demand or { items = {}, fluids = {}, direct = {} })
  -- params.relief_main (with params.purpose): only what the machine was planned for counts as wanted (its main
  -- product, or every product the plan uses when the main one leaves as a by-product). A by-product with many users
  -- is always leaving: a propene maker, with water beside the propene, ran for the water at 93 times its plan and
  -- took the lead plates another machine waited for.
  local purpose = run.params.relief_main and (run.params.purpose or {})[r.name]
  local function wanted(p, box)
    if not used(p.name) then return false end
    if purpose and not purpose[p.name] then return false end
    if not dm then return true end
    if p.type == "fluid" then
      local t = dm.fluids[p.name]
      if t then for _, seg in pairs(box_segments(e, box)) do if t[seg] then return true end end end
      return false
    end
    if (dm.direct[e.unit_number] or {})[p.name] then return true end
    local t = dm.items[p.name]
    if t then for net, its in pairs((run.onto or {})[e.unit_number] or {}) do if (its[p.name] or its["*"]) and t[net] then return true end end end
    return false
  end
  local out = e.get_output_inventory()
  local own = {}
  for _, ing in pairs(r.ingredients) do own[ing.name] = true end
  local want, blocking, any_used = false, {}, false
  -- (The limit in crafts: relief_limit. A survey of every machine standing full in three runs, 2026-11-16: two crafts'
  -- worth for a craft of 1.25 s or longer, three at 0.75 to 1 s, five at 0.25 s; and a product whose next craft
  -- would not fit its stack stops the machine whatever the count, 73 flasks at 40 a craft.)
  local mult = relief_limit(e, r)
  local most, most_ratio = nil, 0
  for _, p in pairs(r.products) do
    local amount = p.amount or ((p.amount_min or 0) + (p.amount_max or 0)) / 2
    if not own[p.name] and used(p.name) then any_used = true end
    if own[p.name] then
    elseif p.type == "item" then
      local n = out and out.get_item_count(p.name) or 0
      local expected = amount * (p.probability or p.independent_probability or 1)
      local limit = mult * expected
      local stack = prototypes.item[p.name] and prototypes.item[p.name].stack_size or 100
      local b = { p = p, n = n, keep = math.max(0, math.min(math.ceil(limit), stack) - math.max(1, math.ceil(amount))) }
      if expected > 0 and n / expected > most_ratio then most, most_ratio = b, n / expected end
      if n > 0 and (n >= limit or n + amount > stack) then b.keep = math.min(b.keep, math.max(0, stack - 2 * math.ceil(amount))); blocking[#blocking + 1] = b
      elseif wanted(p) then want = true end
    else
      for k = 1, (e.fluids_count or 0) do
        local f = e.get_fluid(k)
        local proto = e.get_fluid_box_prototype(k)
        if proto and proto.index == nil and proto[1] then proto = proto[1] end
        local flt = e.get_fluid_filter(k)
        if proto and proto.production_type == "output" and ((f and f.name == p.name) or (flt and flt.name == p.name)) then
          local cap = e.get_fluid_capacity(k)
          local have = f and f.amount or 0
          if cap - have < amount then blocking[#blocking + 1] = { p = p, k = k, f = f, keep = math.max(0, cap - 2 * amount) }
          elseif wanted(p, k) then want = true end
        end
      end
    end
  end
  -- (A machine none of whose products is used runs to take something away: whatever stops it leaves.)
  if not any_used then want = true end
  -- (Standing full with nothing at its limit by this count: the product it holds most crafts' worth of is the one.)
  if #blocking == 0 and most and most_ratio >= 1 and FULL_OUT_STATUS[e.status] then
    most.keep = math.max(0, math.ceil(most.n - (most.p.amount or 1)))
    blocking[1] = most
  end
  return { r = r, out = out, want = want, blocking = blocking }
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
  -- Only a maker that makes something besides the item counts (another product, or the item is what its fuel burns
  -- to): standing full, it would stop that too. One making nothing else just has no more users and should stop; its
  -- overflow open, it ran on and took the inputs others waited for (Py production science: 25/s of nickel plate, coke,
  -- sodium hydroxide, gravel and more went into the blocks' by-product chests while the factory's own users of them
  -- stood short).
  local function full(e, item)
    if not (e.valid and not e.disabled_by_script and (e.status == defines.entity_status.full_output or e.status == defines.entity_status.full_burnt_result_output)) then return false end
    local r = e.get_recipe()
    if not r then return true end
    local n, makes = 0, false
    for _, p in pairs(r.products) do n = n + 1; if p.name == item then makes = true end end
    return n > 1 or not makes
  end
  local function surplus(item, net)
    local ms = run.makers[item]
    if not ms or #ms == 0 then return true end
    local on = false
    if net then
      for _, e in pairs(ms) do
        if e.valid and (run.drops[e.unit_number] or {})[net] then on = true; if full(e, item) then return true end end
      end
    end
    if on then return false end
    for _, e in pairs(ms) do if full(e, item) then return true end end
    return false
  end
  for _, s in pairs(run.sinks) do
    if s.chest.valid and not s.inv.is_empty() then
      for _, it in pairs(s.inv.get_contents()) do
        local k = key(it.name, it.quality)
        local n = it.count
        if caps and caps[k] and not (run.relief_mode and s.overflow) then
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
      if caps and not (run.relief_mode and s.overflow) then s.gate.disabled_by_script = s.inv.get_item_count() >= 20 end
    end
    -- An overflow (params.overflow: items the factory also uses) takes only while the belt into its loader is
    -- backed up and a maker of the item stands full: the factory's own users always take first, however fast they
    -- run, and a belt that runs on past its users to the edge fills up as their buffer instead of draining what
    -- passed them (Py science 2's stone-brick ring, which nothing adds to, starved that way).
    if run.params.relief_built and s.gate.valid and not s.mixed and s.overflow then
      -- (Built: the belt before the port is the gate, on its wire. The port itself takes whatever comes; one no maker
      -- can open, or whose belt no wire reached, stays shut.)
      s.gate.disabled_by_script = s.dead_end or false
      local b = s.wired and game.tick % 60 == 11 and sink_belt(s)
      if b and b.valid then pcall(function() if not b.get_control_behavior().disabled then s.open_s = (s.open_s or 0) + 1 end end) end
    elseif run.params.relief_gate and s.gate.valid and not s.mixed and s.overflow then
      -- params.relief_gate: the overflow port of an item the factory uses is a gate on a wire from the makers on its
      -- belt. Open while one of them has a product at its limit that this belt has carried and another used product
      -- of its still leaving (relief_scan: what a decider at the machine reads); shut otherwise, whatever the belt
      -- holds. Nothing is taken at the machine: surplus leaves through the ports the layout has.
      local b = sink_belt(s)
      local net = game.tick % 30 == 7 and b and b.valid and run.net_of and run.net_of[b.unit_number]
      if net then
        if not s.makers then
          s.makers = {}
          for _, m in pairs(run.machines) do
            if m.e.valid and (run.drops[m.e.unit_number] or {})[net] then s.makers[#s.makers + 1] = m end
          end
        end
        local open = false
        for _, m in pairs(s.makers) do
          if m.e.valid then
            local ok, sc = pcall(relief_scan, run, m)
            if ok and sc and sc.want then
              local items = (run.onto[m.e.unit_number] or {})[net] or {}
              for _, bl in pairs(sc.blocking) do
                if not bl.k and (items[bl.p.name] or items["*"]) then open = true end
              end
            elseif not ok then run.relief_errors = run.relief_errors or {}; run.relief_errors[tostring(sc)] = (run.relief_errors[tostring(sc)] or 0) + 1 end
          end
        end
        s.gate.disabled_by_script = not open
        if open then s.open_s = (s.open_s or 0) + 0.5 end
      end
    elseif next(overflow) and s.gate.valid and not s.mixed then
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
          if run.params.dead_end or not (full and extra and backed_up()) then s.gate.disabled_by_script = true elseif not (caps and s.inv.get_item_count() >= 20) then s.gate.disabled_by_script = false end
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

-- Where every item is when the run ends (report "where"): on belts, in chests by kind, in inserters' hands, with robots,
-- in machines. Fourteen reduced tin were made in a 60-hour run and five could be found in the report.
local function where_is(run)
  local out = {}
  local function put(kind, name, n)
    if n and n > 0 then out[name] = out[name] or {}; out[name][kind] = (out[name][kind] or 0) + n end
  end
  local function contents(kind, inv)
    if not inv then return end
    for k, c in pairs(inv.get_contents()) do
      put(kind, type(c) == "table" and c.name or k, type(c) == "table" and c.count or c)
    end
  end
  local ok, err = pcall(function()
    local sf = run.surface
    for _, e in pairs(sf.find_entities_filtered { type = { "transport-belt", "underground-belt", "splitter", "loader", "loader-1x1" } }) do
      for i = 1, e.get_max_transport_line_index() do
        for _, c in pairs(e.get_transport_line(i).get_contents()) do put("belt", c.name, c.count) end
      end
    end
    local sink = {}
    for _, s in pairs(run.sinks or {}) do if s.chest.valid then sink[s.chest.unit_number] = true end end
    for _, e in pairs(sf.find_entities_filtered { type = { "container", "logistic-container" } }) do
      local kind = sink[e.unit_number] and "sink" or (e.type == "logistic-container" and (e.prototype.logistic_mode or "chest")) or "chest"
      contents(kind, e.get_inventory(defines.inventory.chest))
    end
    for _, e in pairs(sf.find_entities_filtered { type = "inserter" }) do
      local h = e.held_stack
      if h.valid_for_read then put("hand", h.name, h.count) end
    end
    for _, e in pairs(sf.find_entities_filtered { type = { "logistic-robot", "construction-robot" } }) do
      contents("robot", e.get_inventory(defines.inventory.robot_cargo))
    end
    for _, m in pairs(run.machines or {}) do
      local e = m.e
      if e.valid then
        pcall(function() contents("machine_in", e.get_inventory(defines.inventory.crafter_input or defines.inventory.assembling_machine_input)) end)
        pcall(function() contents("machine_out", e.get_output_inventory()) end)
      end
    end
  end)
  if not ok then out["error"] = { [tostring(err)] = 1 } end
  return out
end

local function finish(run)
  local secs = (game.tick - run.m0) / 60
  local report = {
    measured_s = secs, warmup_s = run.warmup / 60, tick = game.tick, build = run.build, warnings = run.warnings,
    machines = {}, outputs = {}, inputs = {}, fluid_outputs = {}, stuck_inserters = {}, idle_inserters = 0, stuck_belts = {},
    inserters = #run.inserters, belts = #run.belts, fed = run.fed, fed_measure = run.fed_measure, bootstrapped = run.bootstrapped, bootstrapped_measure = run.bootstrapped_measure, debug = run.debug,
    seeded = run.seeded, seeded_measure = run.seeded_measure, where = where_is(run),
    sink_temperatures = run.sink_temps,
    provider_level = run.pv_outs and (function()
      local n, closed, secs = 0, 0, 0
      local items = {}
      for _, o in pairs(run.pv_outs) do
        n = n + 1
        secs = secs + o.closed_s
        if o.closed_s > 0 then closed = closed + 1 end
        items[o.item] = (items[o.item] or 0) + o.closed_s
      end
      return { exits = n, closed = closed, closed_s = secs, by_item = items }
    end)() or nil,
    overflow_last = run.ov_outs and (function() local n, opened, secs_open = 0, 0, 0
      for _, o in pairs(run.ov_outs) do n = n + 1; if o.open_s > 0 then opened = opened + 1; secs_open = secs_open + o.open_s end end
      return { exits = n, opened = opened, open_s = secs_open } end)() or nil,
    voided = run.voided, voided_measure = run.voided_measure, voided_by = run.voided_by, relief_built = run.rb, relief_exits = run.rx, duty_clocks = run.duty_clocks, relief_fluid_ports = run.rgates and (function() local o = {}
      for _, g in pairs(run.rgates) do if g.e.valid then o[#o + 1] = { fluid = g.filter.name, x = bp_pos(run, g.e.position).x, y = bp_pos(run, g.e.position).y, open_s = g.open_s or 0, out = g.out, out_measure = g.out_measure, makers = g.makers and #g.makers or nil, no_segment = g.no_segment } end end
      return o end)() or nil, relieved = run.relieved, relieved_measure = run.relieved_measure, relieved_by = run.relieved_by, relief_errors = run.relief_errors, void_kept = run.void_kept, moved = run.moved, moved_measure = run.moved_measure, stored = run.store, stored_fluids = run.fstore, timeline = run.timeline, orphan_s = run.orphan_s,
    pumped = run.pumped, pumped_measure = run.pumped_measure,
    fluid_gates = run.fgates and (function() local o = {}
      for _, g in pairs(run.fgates) do if g.e.valid then o[#o + 1] = { fluid = g.filter.name, x = bp_pos(run, g.e.position).x, y = bp_pos(run, g.e.position).y, open_s = g.opened or 0,
        seg = g.seg, push_s = g.push_s or 0, short_s = g.short_s or 0 } end end
      return o end)() or nil,
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
        voided = m.voided,
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
              -- (The pipe network the box belongs to, when the game says: flow control's fluid demand goes by it.)
              local okg, seg = pcall(function() return e.has_fluid_segment(k) and e.get_fluid_segment_id(k) or nil end)
              o[#o + 1] = { io = proto and proto.production_type or "?", name = (f and f.name) or (flt and flt.name) or nil,
                amount = f and f.amount or 0, temperature = f and f.temperature or nil, conns = conns,
                seg = okg and seg or nil, seg_error = (not okg) and tostring(seg) or nil }
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
      overflow = s.overflow or nil, mixed = s.mixed or nil, ["local"] = s["local"] or nil, open_s = s.open_s }
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
    local byItem = {}
    for _, c in pairs(surface.find_entities_filtered { type = "logistic-container" }) do
      local inv = c.get_inventory(defines.inventory.chest)
      local n = inv and inv.get_item_count() or 0
      if c.prototype.logistic_mode == "requester" then
        req = req + 1; if n == 0 then reqEmpty = reqEmpty + 1 end
      else
        prov = prov + 1; provItems = provItems + n
        for _, it in pairs(inv and inv.get_contents() or {}) do byItem[it.name] = (byItem[it.name] or 0) + it.count end
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
      report.bots = { networks = count, robots = robots, robots_idle = idle, requesters = req, requesters_empty = reqEmpty, robot = run.robot,
        providers = prov, items_in_providers = provItems, chests_outside_networks = unnetworked, empty_requesters = samples,
        requests_mapped = run.req_mapped, requests_set = run.req_set, request_error = run.req_err, request_trip_max_s = run.req_trip_max,
        provider_items = byItem }
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
      -- (params.feed_items: only those, to find which few the factory needs help with.)
      local only = run.params.feed_items
      for _, ing in pairs(r.ingredients) do
        local give = count(ing) < ing.amount and math.min(ing.amount * 2 - count(ing), m.fb[ing.name]) or 0
        if only and not only[ing.name] then give = 0 end
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

-- params.virtual_bots, a helper: items travel as through one robot network with a chest at every machine. Once a
-- second a machine holding less than a craft's worth of an item ingredient gets up to two crafts' worth out of other
-- machines' output inventories (their products and burnt fuel), first come first served; a burner without fuel
-- likewise. Nothing is made up: what is given is taken from where it lies. With params.belt_pull the machines'
-- output inserters then stand (only targets leave by belt), so belts carry the raw inputs alone and pipes the fluids.
-- The plan-level model has one stock per item that every maker fills and every user draws from, and under it the
-- metered plans with margins hold; belts are not that (a metered maker's output is not shared out along them). This
-- puts the question to the real machines: do they hold once items reach whoever needs them?
local function virtual_bots(run)
  if not run.params.virtual_bots or game.tick % 60 ~= 0 then return end
  local stock = {}
  local function add(name, inv, n)
    if n > 0 then local l = stock[name] or {}; l[#l + 1] = { inv = inv, n = n }; stock[name] = l end
  end
  for _, m in pairs(run.machines) do
    local e = m.e
    local r = e.valid and (e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe]))
    if r then pcall(function()
      local out = e.get_output_inventory()
      if out then
        for _, p in pairs(r.products) do
          if p.type == "item" then add(p.name, out, out.get_item_count(p.name)) end
        end
      end
      local burnt = e.get_burnt_result_inventory()
      if burnt then
        for k, c in pairs(burnt.get_contents()) do
          local name = type(c) == "table" and c.name or k
          add(name, burnt, type(c) == "table" and c.count or c)
        end
      end
    end) end
  end
  -- What a machine stands full of goes to the network's storage, as an active provider chest would send it, and
  -- users draw from the storage first: carriers (params.void_except: cages, barrels, bricks) without limit, anything
  -- else up to params.vb_caps (two hours of its planned use, say). Past that the virtual sinks delete it. Without a
  -- stock the only buffer is a machine's own output, a few crafts, and a loop lives hand to mouth: Py chemical
  -- science ran its fertilizer and seaweed loop for 28 primed hours and lost it within four once priming ended.
  run.store = run.store or {}
  local caps = run.params.vb_caps
  for _, m in pairs(run.machines) do
    local e = m.e
    if e.valid and not e.disabled_by_script and (e.status == defines.entity_status.full_output or e.status == defines.entity_status.waiting_for_space_in_destination) then
      local r = e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe])
      local out = r and e.get_output_inventory()
      if out then
        for _, p in pairs(r.products) do
          if p.type == "item" then
            local n = out.get_item_count(p.name)
            local room = n
            if not (run.params.void_except or {})[p.name] then
              room = caps and math.max(0, math.floor((caps[p.name] or 0) - (run.store[p.name] or 0))) or 0
            end
            local d = math.min(n, room)
            if d > 0 then
              d = out.remove { name = p.name, count = d }
              run.store[p.name] = (run.store[p.name] or 0) + d
              for _, s2 in ipairs(stock[p.name] or {}) do if s2.inv == out then s2.n = math.max(0, s2.n - d) end end
            end
          end
        end
      end
    end
  end
  local function available(name)
    local n = run.store[name] or 0
    for _, s in ipairs(stock[name] or {}) do n = n + s.n end
    return n
  end
  local function take(name, want)
    local got = math.min(run.store[name] or 0, want)
    if got > 0 then run.store[name] = run.store[name] - got end
    for _, s in ipairs(stock[name] or {}) do
      if got >= want then break end
      if s.n > 0 then
        local d = s.inv.remove { name = name, count = math.min(s.n, want - got) }
        s.n = s.n - d; got = got + d
      end
    end
    return got
  end
  run.moved = run.moved or {}
  run.moved_measure = run.moved_measure or {}
  local function record(name, d)
    run.moved[name] = (run.moved[name] or 0) + d
    if run.phase == "measure" then run.moved_measure[name] = (run.moved_measure[name] or 0) + d end
  end
  -- params.vb_share "small": of the machines waiting for an item the one planned to use least of it is served first
  -- (else in the machines' fixed order, first come first served). An item that runs a few percent short then costs
  -- its biggest user those few percent; first come first served takes the whole shortfall out of the last user, whose
  -- chain stops. In the plan-level model it decides only where something is short already: fluids kept in nothing but
  -- their makers' output boxes (Py chemical science: 0 to 55% of the target first come first served, 102% smallest
  -- first).
  local wants, small = {}, run.params.vb_share == "small"
  local crafts = run.params.plan_crafts or {}
  for _, m in pairs(run.machines) do
    local e = m.e
    local r = e.valid and (e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe]))
    if r then pcall(function()
      for _, ing in pairs(r.ingredients) do
        if ing.type == "item" then
          local have = held(e, ing.name)
          if have < ing.amount then
            local w = { e = e, want = math.ceil(ing.amount * (run.params.vb_stock or 2)) - have, size = ing.amount * (crafts[r.name] or 0) }
            if small then
              local l = wants[ing.name] or {}
              l[#l + 1] = w
              wants[ing.name] = l
            else
              local give = math.min(w.want, available(ing.name))
              if give > 0 then
                local done = e.insert { name = ing.name, count = give }
                if done and done > 0 then take(ing.name, done); record(ing.name, done) end
              end
            end
          end
        end
      end
      if e.status == defines.entity_status.no_fuel then
        local fuel = e.get_fuel_inventory()
        for _, f in pairs(fuel_items(run, r)) do
          local give = math.min(2, available(f))
          if fuel and give > 0 then
            local done = fuel.insert { name = f, count = give }
            if done and done > 0 then take(f, done); record(f, done) end
          end
        end
      end
    end) end
  end
  for name, l in pairs(wants) do
    table.sort(l, function(a, b) return a.size < b.size end)
    for _, w in ipairs(l) do
      local give = math.min(w.want, available(name))
      if give <= 0 then break end
      local ok, done = pcall(function() return w.e.insert { name = name, count = give } end)
      if ok and done and done > 0 then take(name, done); record(name, done) end
    end
  end
end

-- params.request_need: a robot link's requester chest asks only for what the machines it feeds lack to a craft's
-- worth of its item (or to two minutes of the link's flow, for fast machines; in a blueprint: the chest's requests
-- set from its machine's ingredients over a wire). As built a chest asks for ten minutes' worth, at least 10, and the
-- machine behind it keeps two crafts' worth: every user stocks up before the next sees anything. In the plan-level
-- model that hoarding puts the first pack at hour 12 to 49 from empty, where machines short of a first craft served
-- before any second one get it at hour 2 to 22 (sim2.py needfirst).
local function request_need(run)
  if not (run.params.request_need and run.req_list) or game.tick % 60 ~= 45 then return end
  for _, rq in pairs(run.req_list) do
    local c = rq.chest
    if c.valid and rq.belt.valid then
      if not rq.users then
        -- The machines fed from the belt the chest's inserter drops onto (the block's own belts, not the robot link).
        rq.users = {}
        local seen, queue, qi = { [rq.belt.unit_number] = true }, { rq.belt }, 1
        while qi <= #queue and qi < 800 do
          local b = queue[qi]
          qi = qi + 1
          for _, o in pairs((b.belt_neighbours or {}).outputs or {}) do
            if not seen[o.unit_number] then seen[o.unit_number] = true; queue[#queue + 1] = o end
          end
          local n = b.type == "underground-belt" and b.underground_belt_neighbour
          if n and not seen[n.unit_number] then seen[n.unit_number] = true; queue[#queue + 1] = n end
        end
        if not run.by_unit then
          run.by_unit = {}
          for _, m in pairs(run.machines) do if m.e.valid then run.by_unit[m.e.unit_number] = m end end
        end
        for _, i in pairs(run.inserters) do
          local pk, d = i.e.valid and i.e.pickup_target, i.e.valid and i.e.drop_target
          if pk and d and seen[pk.unit_number] and CRAFTERS[d.type] then
            local m = run.by_unit[d.unit_number]
            local r = d.get_recipe() or (m and m.plan_recipe and prototypes.recipe[m.plan_recipe])
            for _, ing in pairs(r and r.ingredients or {}) do
              if ing.type == "item" and ing.name == rq.item and not rq.users[d.unit_number] then
                rq.users[d.unit_number] = { e = d, amount = ing.amount }
              end
            end
          end
        end
        run.req_mapped = (run.req_mapped or 0) + (next(rq.users) and 1 or 0)
        rq.belts = queue
      end
      if next(rq.users) then
        -- (What its machines lack to one craft's worth each, or to two minutes of the link's planned flow in the
        -- chest and on the belt behind it, whichever is more: a machine that crafts every few seconds cannot wait a
        -- robot's trip for each craft. Not counting what the machines hold: Py's coke gasifier, a craft of five coke
        -- every 29 s on a chest shared with a lard machine that kept 11, was asked 9 at a time and ran at 90% with
        -- 4,800 coke in the provider chest. Its ammonia was a tenth short, the smallest user took all of the
        -- shortfall, and five recipes on the science pack came out at 78% of plan.)
        if not rq.count0 then
          rq.count0 = 10
          pcall(function()
            local f = c.get_requester_point().sections[1].filters[1]
            if f and f.min then rq.count0 = f.min end
          end)
        end
        local lack = 0
        for _, u in pairs(rq.users) do
          if u.e.valid then
            local h = held(u.e, rq.item)
            if h < u.amount then lack = lack + u.amount - h end
          end
        end
        local base = rq.count0 > 10 and rq.count0 / 5 or 0
        -- params.request_trip: and never less than that many robot trips' worth of the link's flow, from the nearest
        -- provider chest of the item (the chest's ten minutes' worth tells the flow). A chest that asks a craft's worth
        -- when its machine runs short waits a trip for every craft: Py logistic science stitched 798 x 779, the coke
        -- quencher 623 tiles from its red-hot coke (130 s at the robot's 4.8 tiles a second, and a recharge on the
        -- way), ran at 24 crafts an hour of 31.3 for sixteen hours with 200 red-hot coke in the provider chest; in
        -- the same factory stitched 544 x 536 the two stood 260 tiles apart and the quencher kept its rate.
        if run.params.request_trip then
          if not rq.trip_s then
            local near
            for _, b in pairs(run.params.bots.providers or {}) do
              if b.item == rq.item then
                local d = math.sqrt((b.x + 0.5 + run.dx - c.position.x) ^ 2 + (b.y + 0.5 + run.dy - c.position.y) ^ 2)
                if not near or d < near then near = d end
              end
            end
            local speed = 0.05
            pcall(function() speed = prototypes.entity[run.robot].speed or speed end)
            rq.trip_s = (near or 0) / (speed * 60)
            run.req_trip_max = math.max(run.req_trip_max or 0, rq.trip_s)
          end
          base = math.max(base, rq.count0 / 600 * rq.trip_s * run.params.request_trip)
        end
        local inchest, piped = c.get_item_count(rq.item), 0
        if base > 0 then
          for _, b in pairs(rq.belts or {}) do
            if b.valid then
              for li = 1, b.get_max_transport_line_index() do piped = piped + b.get_transport_line(li).get_item_count(rq.item) end
            end
          end
        end
        local want = math.ceil(math.max(0, lack - inchest, base - inchest - piped))
        if want ~= rq.asked then
          local ok, err = pcall(function()
            local sec = c.get_requester_point().sections[1]
            if want > 0 then sec.set_slot(1, { value = { type = "item", name = rq.item, quality = "normal" }, min = want })
            else sec.clear_slot(1) end
          end)
          if ok then rq.asked = want; run.req_set = (run.req_set or 0) + 1 else run.req_err = tostring(err) end
        end
      end
    end
  end
end

-- params.vb_fluids (with virtual_bots or alone, over real robots): fluids move as virtual_bots moves items, with a
-- stock, as storage on every fluid link (or barrels by robot) would. Once a second what a machine's output boxes hold
-- goes to the storage (up to params.vb_caps of the fluid; carriers without limit), and a machine with less than a
-- craft's worth of a fluid ingredient gets up to vb_stock crafts' worth from it, at a temperature its recipe takes.
-- Pipes alone hold next to nothing against Py's batches: chemical science's fusion reactor takes 10,000 pressured
-- water a craft and holds 20,000, four hours of all that is made; next to the maker it took 66,000 of the first
-- 69,000 and the heat exchanger at the far end of the pipe ran at a fifth of its rate, the steam chain behind it
-- pacing the science packs. In the plan-level model chemical science with fluids kept in nothing but their makers'
-- output boxes makes 0 to 62% of its target, with one batch of its biggest user in store 96 to 101% (sim2.py fluidk).
local function virtual_tanks(run)
  if not run.params.vb_fluids or game.tick % 60 ~= 15 then return end
  run.fstore = run.fstore or {}
  run.moved = run.moved or {}
  run.moved_measure = run.moved_measure or {}
  local caps, keep = run.params.vb_caps or {}, run.params.void_except or {}
  local function is_output(e, k)
    local proto = e.get_fluid_box_prototype(k)
    if proto and proto.index == nil and proto[1] then proto = proto[1] end
    return proto and proto.production_type == "output"
  end
  for _, m in pairs(run.machines) do
    local e = m.e
    if e.valid then pcall(function()
      for k = 1, (e.fluids_count or 0) do
        local f = e.get_fluid(k)
        if f and f.amount > 0.01 and is_output(e, k) then
          local st = run.fstore[f.name] or {}
          run.fstore[f.name] = st
          local total = 0
          for _, a in pairs(st) do total = total + a end
          local d = math.min(f.amount - 0.001, keep[f.name] and f.amount or math.max(0, (caps[f.name] or 0) - total))
          if d > 0 then
            local t = math.floor(f.temperature + 0.5)
            st[t] = (st[t] or 0) + d
            e.set_fluid(k, { name = f.name, amount = f.amount - d, temperature = f.temperature })
          end
        end
      end
    end) end
  end
  local order = {}
  for _, m in pairs(run.machines) do order[#order + 1] = m end
  if run.params.vb_share == "small" then
    -- (smallest planned user first, by the machine's whole planned intake of fluids: one order for all fluids)
    local crafts = run.params.plan_crafts or {}
    local function size(m)
      if m.vb_fsize then return m.vb_fsize end
      local n = 0
      pcall(function()
        local r = m.e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe])
        for _, ing in pairs(r.ingredients) do if ing.type == "fluid" then n = n + ing.amount * (crafts[r.name] or 0) end end
      end)
      m.vb_fsize = n
      return n
    end
    table.sort(order, function(a, b) return size(a) < size(b) end)
  end
  -- (With vb_share "small" a machine still short of a craft's worth then takes from what bigger users of the fluid
  -- hold in their input boxes, biggest first: a maker's output box empties into its pipe at once, and the machine
  -- next to it has the fluid before the stock ever sees it. Py chemical science's pressured water never reached the
  -- stock: the fusion reactor held 15,482 of it and the heat exchanger 58 of the 100 it takes.)
  local small = run.params.vb_share == "small"
  local pcrafts = run.params.plan_crafts or {}
  local needy, donors = {}, {}
  for _, m in ipairs(order) do
    local e = m.e
    local r = e.valid and (e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe]))
    if r then pcall(function()
      for _, ing in pairs(r.ingredients) do
        local st = ing.type == "fluid" and (run.fstore[ing.name] or (small and {}))
        if st then
          local have = 0
          for k = 1, (e.fluids_count or 0) do
            local f = e.get_fluid(k)
            if f and f.name == ing.name and not is_output(e, k) then
              have = have + f.amount
              if small then
                local l = donors[ing.name] or {}
                l[#l + 1] = { e = e, k = k, amount = f.amount, temp = f.temperature, size = ing.amount * (pcrafts[r.name] or 0), keep = 0 }
                donors[ing.name] = l
              end
            end
          end
          -- (Only the coolest temperature its recipe takes, of those ever stored: a user of steam at 150 degrees
          -- must not empty the 500-degree steam another machine waits for.)
          local t
          for t2 in pairs(st) do
            if (not ing.minimum_temperature or t2 >= ing.minimum_temperature - 0.5)
                and (not ing.maximum_temperature or t2 <= ing.maximum_temperature + 0.5) and (not t or t2 < t) then t = t2 end
          end
          if small and have < ing.amount then
            local l = needy[ing.name] or {}
            l[#l + 1] = { e = e, ing = ing, have = have, size = ing.amount * (pcrafts[r.name] or 0), t = t }
            needy[ing.name] = l
          end
          if have < ing.amount then
            local want = ing.amount * (run.params.vb_stock or 2) - have
            local a = t and st[t] or 0
            if a > 0 and want > 0 then
              local put = e.insert_fluid { name = ing.name, amount = math.min(a, want), temperature = t } or 0
              if put > 0 then
                st[t] = a - put
                local key = "[" .. ing.name .. "]"
                run.moved[key] = (run.moved[key] or 0) + put
                if run.phase == "measure" then run.moved_measure[key] = (run.moved_measure[key] or 0) + put end
              end
            end
          end
        end
      end
    end) end
  end
  for name, l in pairs(needy) do
    local ds = donors[name]
    if ds then
      table.sort(l, function(a, b) return a.size < b.size end)
      table.sort(ds, function(a, b) return a.size > b.size end)
      for _, n in ipairs(l) do
        pcall(function()
          local have = 0
          for k2 = 1, (n.e.fluids_count or 0) do
            local f = n.e.get_fluid(k2)
            if f and f.name == name and not is_output(n.e, k2) then have = have + f.amount end
          end
          local want = n.ing.amount - have
          for _, d in ipairs(ds) do
            if want <= 0.01 then break end
            -- (read again: the stock may have filled the box since it was listed)
            local f = d.size > n.size and d.e.valid and d.e ~= n.e and d.e.get_fluid(d.k)
            -- (And only at that same temperature: Py logistic science's steam users at 250 degrees took the 1,000
            -- and 500-degree steam out of the two coolers' input boxes, 164,000 of 355,000 made in 20 hours, each
            -- unit of it worth 3.4 and 1.85 of theirs, and the steam chain ran at 55 and 27% of its plan.)
            if f and f.name == name and f.amount > 0.01 and (n.t == nil or math.abs(f.temperature - n.t) < 0.6)
                and (not n.ing.minimum_temperature or f.temperature >= n.ing.minimum_temperature - 0.5)
                and (not n.ing.maximum_temperature or f.temperature <= n.ing.maximum_temperature + 0.5) then
              local take = math.min(want, f.amount - 0.001)
              local put = take > 0 and n.e.insert_fluid { name = name, amount = take, temperature = f.temperature } or 0
              if put > 0 then
                d.e.set_fluid(d.k, { name = name, amount = f.amount - put, temperature = f.temperature })
                want = want - put
                local key = "[" .. name .. "]"
                run.moved[key] = (run.moved[key] or 0) + put
                if run.phase == "measure" then run.moved_measure[key] = (run.moved_measure[key] or 0) + put end
              end
            end
          end
        end)
      end
    end
  end
end

-- Every game hour: crafts finished so far by recipe, and the storage (report "timeline"), so one long run shows when
-- each chain came up and when it stopped.
local function timeline(run)
  if game.tick % (3600 * 60) ~= 0 or not run.machines or not run.t0 then return end
  local pf = {}
  for _, m in pairs(run.machines) do
    local e = m.e
    local r = e.valid and (e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe]))
    if r then pf[r.name] = (pf[r.name] or 0) + e.products_finished end
  end
  run.timeline = run.timeline or {}
  local st = {}
  for k, v in pairs(run.store or {}) do st[k] = v end
  for k, byt in pairs(run.fstore or {}) do
    local n = 0
    for _, a in pairs(byt) do n = n + a end
    st["[" .. k .. "]"] = math.floor(n)
  end
  local sd = {}
  for k, v in pairs(run.seeded or {}) do sd[k] = v end
  local vd = {}
  for k, v in pairs(run.voided or {}) do vd[k] = v end
  for k, v in pairs(run.relieved or {}) do vd[k] = (vd[k] or 0) + v end
  run.timeline[#run.timeline + 1] = { h = (game.tick - run.t0) / 216000, phase = run.phase, pf = pf, store = st, seeded = sd, voided = vd }
end

-- params.void_full, a helper ("virtual sinks"): a machine standing with a full output and not switched off by flow
-- control has its surplus deleted every second: half of each product item in its output, half of each product fluid in
-- its output boxes, half of its burnt result. Counted per item, per recipe and per machine. It does what a gated sink
-- at the end of every belt would (open only while a maker would otherwise stop), without building one: does the factory
-- run once every by-product has somewhere to go, and where would real sinks be needed, and how big?
local FULL_OUT = { [defines.entity_status.full_output] = true, [defines.entity_status.waiting_for_space_in_destination] = true,
  [defines.entity_status.full_burnt_result_output] = true }
local function void_full(run)
  if not run.params.void_full or game.tick % 60 ~= 0 then return end
  run.voided = run.voided or {}
  run.voided_measure = run.voided_measure or {}
  run.voided_by = run.voided_by or {}
  local function record(m, r, k, d)
    run.voided[k] = (run.voided[k] or 0) + d
    if run.phase == "measure" then
      run.voided_measure[k] = (run.voided_measure[k] or 0) + d
      local rk = r.name .. " " .. k
      run.voided_by[rk] = (run.voided_by[rk] or 0) + d
      m.voided = m.voided or {}
      m.voided[k] = (m.voided[k] or 0) + d
    end
  end
  -- Only a machine that has stood full for a while (params.void_wait seconds, 30): a craft of 63 tin plates reads
  -- "full" until its inserters have carried them off, and halving that every second deleted 85 of 126 plates in a
  -- 60-hour run while the four users of tin plate held none. And what a user still lacks a craft's worth of is left
  -- for ten minutes more (params.void_wait_users, 600; counted in "void_kept"): it has somewhere to go.
  local wait, wait_users = run.params.void_wait or 30, run.params.void_wait_users or 600
  if not run.users_of then
    run.users_of = {}
    for _, m in pairs(run.machines) do
      local r = m.e.valid and (m.e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe]))
      if r then for _, ing in pairs(r.ingredients) do
        run.users_of[ing.name] = run.users_of[ing.name] or {}
        table.insert(run.users_of[ing.name], { e = m.e, amount = ing.amount, fluid = ing.type == "fluid" })
      end end
    end
  end
  local function lacked(name, self)
    for _, u in pairs(run.users_of[name] or {}) do
      if u.e.valid and u.e ~= self then
        local have = 0
        if u.fluid then
          for k = 1, (u.e.fluids_count or 0) do
            local f = u.e.get_fluid(k)
            if f and f.name == name then have = have + f.amount end
          end
        else
          have = held(u.e, name)
        end
        if have < u.amount then return true end
      end
    end
    return false
  end
  for _, m in pairs(run.machines) do
    local e = m.e
    if e.valid and not (not e.disabled_by_script and FULL_OUT[e.status]) then m.full_s = 0 end
    if e.valid and not e.disabled_by_script and FULL_OUT[e.status] then m.full_s = (m.full_s or 0) + 1 end
    if e.valid and not e.disabled_by_script and FULL_OUT[e.status] and m.full_s > wait then
      local r = e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe])
      if r then pcall(function()
        local out = e.get_output_inventory()
        for _, p in pairs(r.products) do
          -- (params.void_except: carriers that only go round, barrels and cages and bricks, are never deleted.)
          if (run.params.void_except or {})[p.name] then
          elseif m.full_s <= wait + wait_users and lacked(p.name, e) then
            run.void_kept = run.void_kept or {}
            run.void_kept[p.name] = (run.void_kept[p.name] or 0) + 1
          elseif p.type == "item" then
            local n = out and out.get_item_count(p.name) or 0
            -- (Half, rounded up: a machine standing full of one brain was never relieved.)
            local d = math.ceil(n / 2)
            if d > 0 then
              local removed = out.remove { name = p.name, count = d }
              if removed > 0 then record(m, r, p.name, removed) end
            end
          else
            for k = 1, (e.fluids_count or 0) do
              local f = e.get_fluid(k)
              if f and f.name == p.name and f.amount > 1 then
                -- (Only an output box: a catalyst's input box holds the same fluid.)
                local proto = e.get_fluid_box_prototype(k)
                if proto and proto.index == nil and proto[1] then proto = proto[1] end
                if proto and proto.production_type == "output" then
                  local d = f.amount / 2
                  e.set_fluid(k, { name = f.name, amount = f.amount - d, temperature = f.temperature })
                  record(m, r, "[" .. p.name .. "]", d)
                end
              end
            end
          end
        end
        if e.status == defines.entity_status.full_burnt_result_output then
          local burnt = e.get_burnt_result_inventory()
          for k, c in pairs(burnt and burnt.get_contents() or {}) do
            local name = type(c) == "table" and c.name or k
            local n = type(c) == "table" and c.count or c
            local d = math.floor(n / 2)
            if d > 0 then
              local removed = burnt.remove { name = name, count = d }
              if removed > 0 then record(m, r, name, removed) end
            end
          end
        end
      end) end
    end
  end
end

-- params.relief: overflow at the producer, by what a circuit at the machine could read (its own output and nothing
-- else). Once a second, a machine standing with a full output has a product taken out when that product blocks it (a
-- product at its limit in the output; its burnt result) AND another product of
-- its that some other machine uses is still leaving (under the same limit): the machine is wanted for the one and
-- stopped by the other. A product blocks at the game's own limit: five crafts' worth in the output, or a fluid box
-- with no room for a craft. A machine whose used products all lie in its
-- output is wanted for nothing and stands, so no relief lets it run on and eat inputs (the sand washer at 16 times its
-- plan). Unlike params.void_full it waits for no time and asks no user. With params.dead_end the overflow ports of
-- items the factory uses stay shut for good: their belts are stock, and surplus leaves here only.
local function relief(run)
  if not run.params.relief or game.tick % 60 ~= 30 then return end
  run.relieved = run.relieved or {}
  run.relieved_measure = run.relieved_measure or {}
  run.relieved_by = run.relieved_by or {}
  for _, m in pairs(run.machines) do
    local e = m.e
    if e.valid and not e.disabled_by_script and FULL_OUT[e.status] then
      local ok, err = pcall(function()
        local sc = relief_scan(run, m)
        if not sc then return end
        local burnt = e.status == defines.entity_status.full_burnt_result_output and e.get_burnt_result_inventory()
        if not sc.want then return end
        local function record(k, d)
          run.relieved[k] = (run.relieved[k] or 0) + d
          if run.phase == "measure" then
            run.relieved_measure[k] = (run.relieved_measure[k] or 0) + d
            local rk = sc.r.name .. " " .. k
            run.relieved_by[rk] = (run.relieved_by[rk] or 0) + d
          end
        end
        for _, b in pairs(sc.blocking) do
          if (run.params.void_except or {})[b.p.name] then
          elseif not b.k and run.rx_done and run.rx_done[e.unit_number] then
            -- (params.relief_exits: this machine's items leave by its own inserter.)
          elseif b.k then
            e.set_fluid(b.k, { name = b.f.name, amount = b.keep, temperature = b.f.temperature })
            record("[" .. b.p.name .. "]", b.f.amount - b.keep)
          else
            local removed = sc.out.remove { name = b.p.name, count = b.n - b.keep }
            if removed > 0 then record(b.p.name, removed) end
          end
        end
        if burnt and not burnt.is_empty() then
          for _, it in pairs(burnt.get_contents()) do
            local removed = burnt.remove { name = it.name, count = it.count }
            if removed > 0 then record(it.name, removed) end
          end
        end
      end)
      if not ok then run.relief_errors = run.relief_errors or {}; run.relief_errors[tostring(err)] = (run.relief_errors[tostring(err)] or 0) + 1 end
    end
  end
end

-- params.relief_gate, params.relief_built: a fluid port lets a little out while a maker on its pipes has no room for a
-- craft's worth of that fluid and another used product of its still leaves; shut otherwise. In a blueprint: a pump before the port, enabled by a decider at the maker like an item port's belt. (By
-- script here in both modes: the test copy's port is an infinity pipe.)
local function relief_fluid_gates(run)
  if not run.rgates or game.tick % 30 ~= 13 or not (run.params.relief_gate or run.params.relief_built) then return end
  if run.params.relief_built then
    -- (Built: the deciders decide; the script is the pump at the port, on while the port's signal is on the red wire.)
    local anchor = run.rb_anchor
    if not (anchor and anchor.valid) then return end
    for _, g in pairs(run.rgates) do
      if g.e.valid and g.signal then
        local ok, err = pcall(function()
          local on = anchor.get_signal(g.signal, defines.wire_connector_id.circuit_red) > 0
          g.open = on
          if on then
            g.open_s = (g.open_s or 0) + 0.5
            local sf = g.e.get_fluid_segment_fluid(1)
            if sf and sf.amount > 0 then
              local got = g.e.remove_fluid_segment_fluid(1, math.min(sf.amount, g.pulse))
              got = type(got) == "table" and got.amount or tonumber(got) or 0
              g.out = (g.out or 0) + got
              if run.phase == "measure" then g.out_measure = (g.out_measure or 0) + got end
            end
          end
        end)
        if not ok then run.relief_errors = run.relief_errors or {}; run.relief_errors[tostring(err)] = (run.relief_errors[tostring(err)] or 0) + 1 end
      end
    end
    return
  end
  for _, g in pairs(run.rgates) do
    if g.e.valid then
      if not g.makers then
        g.makers = {}
        local ok, seg = pcall(function() return g.e.get_fluid_segment_id(1) end)
        if ok and seg then
          for _, m in pairs(run.machines) do
            if m.e.valid then pcall(function()
              for i = 1, (m.e.fluids_count or 0) do
                local proto = m.e.get_fluid_box_prototype(i)
                if proto and proto.index == nil and proto[1] then proto = proto[1] end
                local flt = m.e.get_fluid_filter(i)
                if proto and proto.production_type == "output" and flt and flt.name == g.filter.name then
                  local hit = false
                  if m.e.has_fluid_segment(i) then hit = m.e.get_fluid_segment_id(i) == seg
                  else
                    for _, c in pairs(m.e.get_fluid_box_pipe_connections(i) or {}) do
                      local j = c.target_fluidbox_index or 1
                      if c.target and c.target.valid and c.target.has_fluid_segment(j) and c.target.get_fluid_segment_id(j) == seg then hit = true end
                    end
                  end
                  if hit then g.makers[#g.makers + 1] = { m = m, k = i } end
                end
              end
            end) end
          end
        else g.no_segment = true end
      end
      -- (Two crafts' worth for each maker it stops, every half second it does: a pump on a decider's signal lets out
      -- little more. Kept four tenths full while open, the pipes had room for every other maker on them as well.)
      local open, amount = false, 0
      for _, mk in pairs(g.makers) do
        if mk.m.e.valid then
          local ok, sc = pcall(relief_scan, run, mk.m)
          if ok and sc and sc.want then for _, b in pairs(sc.blocking) do
            if b.k == mk.k then open = true; amount = amount + 2 * (b.p.amount or ((b.p.amount_min or 0) + (b.p.amount_max or 0)) / 2) end
          end end
        end
      end
      g.open = open
      if open then
        g.open_s = (g.open_s or 0) + 0.5
        local ok, err = pcall(function()
          local sf = g.e.get_fluid_segment_fluid(1)
          if sf and sf.amount > 0 then
            local got = g.e.remove_fluid_segment_fluid(1, math.min(sf.amount, amount))
            got = type(got) == "table" and got.amount or tonumber(got) or 0
            g.out = (g.out or 0) + got
            if run.phase == "measure" then g.out_measure = (g.out_measure or 0) + got end
          end
        end)
        if not ok then run.relief_errors = run.relief_errors or {}; run.relief_errors[tostring(err)] = (run.relief_errors[tostring(err)] or 0) + 1 end
      end
    end
  end
end

-- params.overflow_last: a machine's exit to an overflow chest works only once the machine has stood full for a minute
-- (params.overflow_last_wait). A block built for robots gives each maker two output inserters, one toward the provider
-- chest its users are served from and one toward the block's overflow chest, and they share whatever is made: of
-- fourteen reduced tin made in 60 game-hours, nine lay on the three belts before the overflow chest (planned to take
-- one in 60 hours) and the molten tin machine got five. A built factory would wire the overflow inserter to the stock
-- ("more than N in the network"); the maker standing full says the same here, its provider chest and belt being full.
-- Only exits whose belts feed no machine and no chest but the overflow's, on machines with another way out.
local function overflow_last(run)
  if not run.params.overflow_last or game.tick % 60 ~= 15 or not run.outs then return end
  if not run.ov_outs then
    local fed = {}
    for _, f in pairs(run.feeds or {}) do for net in pairs(f) do fed[net] = true end end
    local ov = {}
    for _, sk in pairs(run.sinks) do
      if sk["local"] and sk.belt and sk.belt.valid then
        local n = run.net_of[sk.belt.unit_number]
        if n and not fed[n] then ov[n] = true end
      end
    end
    -- (Another way out for the same item: a by-product whose only exit is its overflow chest must leave at once.
    -- Py's quartz crusher has an exit for crushed quartz to a user, one to a provider chest, and one for its stone to
    -- an overflow chest alone; shut, the stone stopped the crusher for a minute at every opening.)
    local function filters(ins)
      local f, n = {}, 0
      for k = 1, (ins.filter_slot_count or 0) do
        local fl = ins.get_filter(k)
        if fl then f[fl.name] = true; n = n + 1 end
      end
      return n > 0 and f or nil
    end
    local other, hands = {}, {}
    for _, o in pairs(run.outs) do
      if o.e.valid and o.m.valid and not ov[o.net] then
        local l = other[o.m.unit_number] or {}
        l[#l + 1] = filters(o.e) or "all"
        other[o.m.unit_number] = l
      end
    end
    for u in pairs(run.hands or {}) do hands[u] = true end
    run.ov_outs = {}
    for _, o in pairs(run.outs) do
      if o.e.valid and o.m.valid and ov[o.net] then
        local mine = filters(o.e)
        local ok = hands[o.m.unit_number] or false
        for _, f in pairs(other[o.m.unit_number] or {}) do
          if f == "all" then ok = true
          elseif mine then for name in pairs(mine) do if f[name] then ok = true end end end
        end
        if ok then run.ov_outs[#run.ov_outs + 1] = { e = o.e, m = o.m, full_s = 0, open_s = 0 } end
      end
    end
  end
  local wait = run.params.overflow_last_wait or 60
  for _, o in pairs(run.ov_outs) do
    if o.e.valid and o.m.valid then
      if FULL_OUT[o.m.status] then o.full_s = o.full_s + 1 else o.full_s = 0 end
      local open = o.full_s >= wait
      if o.e.disabled_by_script ~= (not open) then o.e.disabled_by_script = not open end
      if open then o.open_s = o.open_s + 1 end
    end
  end
end

-- A fluid overflow (an infinity pipe keeping its pipe "at most" so full) deletes whatever is in the pipe at another
-- temperature than its filter's, at once and all of it. The test copy writes the fluid's default temperature; Py's
-- boric acid has default 0 and is made at 10, so everything its maker made beyond what fitted into the one user's box
-- that instant was gone: half of it in every run, and boron trioxide, the lenses, the lab instruments, the plasmids,
-- the animal samples and the science packs behind it never passed 57% of their plan. Once, at the start: a filter at
-- a temperature no machine here makes the fluid at takes the one they do (fluids made at several keep theirs: each
-- temperature has its own pipes and its own overflow).
local function sink_temperatures(run)
  if run.sink_temps_done then return end
  run.sink_temps_done = true
  local made = {}
  for _, m in pairs(run.machines) do
    local r = m.e.valid and (m.e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe]))
    for _, p in pairs(r and r.products or {}) do
      if p.type == "fluid" then
        made[p.name] = made[p.name] or {}
        made[p.name][p.temperature or prototypes.fluid[p.name].default_temperature] = true
      end
    end
  end
  run.sink_temps = {}
  for _, e in pairs(run.surface.find_entities_filtered { type = "infinity-pipe" }) do
    local f = e.get_infinity_pipe_filter()
    local temps = f and f.mode == "at-most" and made[f.name]
    if temps then
      local ft = f.temperature or prototypes.fluid[f.name].default_temperature
      local n, only = 0, nil
      for t in pairs(temps) do n = n + 1; only = t end
      if not temps[ft] and n == 1 then
        f.temperature = only
        e.set_infinity_pipe_filter(f)
        run.sink_temps[#run.sink_temps + 1] = f.name .. " " .. ft .. " -> " .. only
      end
    end
  end
end

-- params.provider_level: a machine's exit to a robot provider chest works only while the network holds at most so
-- many stacks of the item (default 2), where the machine also feeds a user of that item directly or by belt. As built
-- the two exits take turns, half and half whatever each side needs: Py logistic science's limestone maker put 625 of
-- 5,800 into its provider chest in eight hours, robots asking for none of it, while the sodium alginate machine on its
-- own belt ran at 78% of its plan for want of limestone (lead plate and the lead-antimony alloy, fertilizer: the same).
-- A built factory would wire the provider's inserter to the stock ("at most N in the network"): together with the
-- overflow exit's "more than N" one of the two is always open, and the user beside the maker is served first.
-- Only exits carrying one item (the inserter's filter, or the recipe's only item product).
local function provider_level(run)
  if not run.params.provider_level or game.tick % 60 ~= 20 then return end
  if not run.pv_outs then
    run.pv_outs = {}
    local picks, byunit = {}, {}
    for _, m in pairs(run.machines) do if m.e.valid then byunit[m.e.unit_number] = m end end
    for _, i in pairs(run.inserters) do
      local pk = i.e.valid and i.e.pickup_target
      if pk and pk.unit_number then
        local l = picks[pk.unit_number] or {}
        l[#l + 1] = i.e
        picks[pk.unit_number] = l
      end
    end
    local BELT = { ["transport-belt"] = true, ["underground-belt"] = true, ["splitter"] = true }
    for _, m in pairs(run.machines) do
      local e = m.e
      local r = e.valid and (e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe]))
      if r then pcall(function()
        local exits = {}
        for _, ins in pairs(picks[e.unit_number] or {}) do
          local d = ins.drop_target
          local x = { e = ins, user = false, chest = nil, other = false }
          if d and CRAFTERS[d.type] then x.user = true
          elseif d and BELT[d.type] then
            local seen, queue, qi = { [d.unit_number] = true }, { d }, 1
            while qi <= #queue and qi < 400 do
              local b = queue[qi]
              qi = qi + 1
              for _, o in pairs((b.belt_neighbours or {}).outputs or {}) do
                if not seen[o.unit_number] then seen[o.unit_number] = true; queue[#queue + 1] = o end
              end
              local n = b.type == "underground-belt" and b.underground_belt_neighbour
              if n and not seen[n.unit_number] then seen[n.unit_number] = true; queue[#queue + 1] = n end
              for _, j in pairs(picks[b.unit_number] or {}) do
                local dd = j.drop_target
                if dd and CRAFTERS[dd.type] then x.user = true
                elseif dd and dd.type == "logistic-container" and dd.prototype.logistic_mode == "passive-provider" then x.chest = dd
                else x.other = true end
              end
            end
          else x.other = true end
          exits[#exits + 1] = x
        end
        local user = false
        for _, x in pairs(exits) do if x.user then user = true end end
        if not user then return end
        for _, x in pairs(exits) do
          if x.chest and not x.user and not x.other then
            local item, n = nil, 0
            for k = 1, (x.e.filter_slot_count or 0) do
              local f = x.e.get_filter(k)
              if f then item = f.name; n = n + 1 end
            end
            if n == 0 then
              for _, pr in pairs(r.products) do if pr.type == "item" then item = pr.name; n = n + 1 end end
            end
            if n == 1 and prototypes.item[item] then
              run.pv_outs[#run.pv_outs + 1] = { e = x.e, chest = x.chest, item = item, closed_s = 0,
                level = (run.params.provider_level or 2) * prototypes.item[item].stack_size }
            end
          end
        end
      end) end
    end
  end
  for _, o in pairs(run.pv_outs) do
    if o.e.valid and o.chest.valid then
      local net = o.chest.logistic_network
      local close = net ~= nil and net.get_item_count(o.item) > o.level
      if o.e.disabled_by_script ~= close then o.e.disabled_by_script = close end
      if close then o.closed_s = o.closed_s + 1 end
    end
  end
end

-- params.virtual_pumps, a helper: once a second, a machine short of a fluid ingredient (less than a craft's worth in its
-- box) gets up to two crafts' worth moved from the pipe network it connects to, as a pump in front of it would: a pump
-- fills its box whatever the pipe's level. Without one a machine's box fills only as far as its pipes are full, and a
-- fluid made slowly into a long pipe never got it there: Py production science's printers held 47 to 49 of the 50
-- fetal serum a craft takes, the silver caster 72 of 100 molten silver. Moves only what the pipes hold (counted).
local function virtual_pumps(run)
  if not run.params.virtual_pumps or game.tick % 60 ~= 30 then return end
  run.pumped = run.pumped or {}
  run.pumped_measure = run.pumped_measure or {}
  for _, m in pairs(run.machines) do
    local e = m.e
    if e.valid and not e.disabled_by_script then
      local r = e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe])
      if r then pcall(function()
        for _, ing in pairs(r.ingredients) do
          if ing.type == "fluid" then
            local have = e.get_fluid_count(ing.name) or 0
            local want = 2 * ing.amount - have
            if have < ing.amount then
              for k = 1, (e.fluids_count or 0) do
                local f = e.get_fluid(k)
                local okf, filt = pcall(function() return e.get_fluid_filter(k) end)
                if (f and f.name == ing.name) or (okf and filt and filt.name == ing.name) then
                  local okc, conns = pcall(function() return e.get_fluid_box_pipe_connections(k) end)
                  for _, c in pairs(okc and conns or {}) do
                    local t = c.target
                    if want > 0 and t and t.valid then
                      local j = c.target_fluidbox_index or 1
                      local oks, sf = pcall(function() return t.has_fluid_segment(j) and t.get_fluid_segment_fluid(j) or nil end)
                      if oks and sf and sf.name == ing.name and sf.amount > 0 then
                        local got = t.remove_fluid_segment_fluid(j, math.min(sf.amount, want))
                        if got and got.amount > 0 then
                          local put = e.insert_fluid { name = got.name, amount = got.amount, temperature = got.temperature } or 0
                          if put < got.amount then
                            pcall(function() t.add_fluid_segment_fluid(j, { name = got.name, amount = got.amount - put, temperature = got.temperature }) end)
                          end
                          if put > 0 then
                            want = want - put
                            run.pumped[ing.name] = (run.pumped[ing.name] or 0) + put
                            if run.phase == "measure" then run.pumped_measure[ing.name] = (run.pumped_measure[ing.name] or 0) + put end
                          end
                        end
                      end
                    end
                  end
                end
              end
            end
          end
        end
      end) end
    end
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
  virtual_bots(run)
  virtual_tanks(run)
  request_need(run)
  void_full(run)
  relief_demand(run)
  relief(run)
  relief_fluid_gates(run)
  sink_temperatures(run)
  overflow_last(run)
  provider_level(run)
  timeline(run)
  virtual_pumps(run)
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
    -- (Factorio 2.1: an entity's fluid storages are reached through its own methods; there is no LuaFluidBox. A
    -- machine's storage may belong to no pipe network: then the networks of the pipes it connects to.)
    pcall(function()
      for i = 1, (e.fluids_count or 0) do
        local f = e.get_fluid(i)
        local okf, filt = pcall(function() return e.get_fluid_filter(i) end)
        if (f and f.name == name) or (okf and filt and filt.name == name) then
          local okh, has = pcall(function() return e.has_fluid_segment(i) end)
          if okh and has then
            local ok, id = pcall(function() return e.get_fluid_segment_id(i) end)
            if ok and id then out[#out + 1] = id end
          else
            local okc, conns = pcall(function() return e.get_fluid_box_pipe_connections(i) end)
            for _, c in pairs(okc and conns or {}) do
              if c.target and c.target.valid then
                local j = c.target_fluidbox_index or 1
                local okt, tid = pcall(function() return c.target.has_fluid_segment(j) and c.target.get_fluid_segment_id(j) or nil end)
                if okt and tid then out[#out + 1] = tid end
              end
            end
          end
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
    -- Demand no maker hears: a machine short of an item on none of whose belt networks a machine making it drops, and
    -- that no maker hands it directly. Counted per item and user recipe, in seconds (the factory's inputs aside, in
    -- verify.py): pull switches makers on only for users on their own belts.
    if not run.makes_on then
      run.makes_on, run.handed = {}, {}
      for _, m in pairs(run.machines) do
        local r = m.e.valid and (m.e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe]))
        if r then
          for _, p in pairs(r.products) do
            local t = run.makes_on[p.name] or {}
            for n in pairs(run.drops[m.e.unit_number] or {}) do t[n] = true end
            run.makes_on[p.name] = t
            for u in pairs((run.hands or {})[m.e.unit_number] or {}) do
              run.handed[u] = run.handed[u] or {}
              run.handed[u][p.name] = true
            end
          end
        end
      end
    end
    run.orphan_s = run.orphan_s or {}
    local function heard(e, item)
      if (run.handed[e.unit_number] or {})[item] then return true end
      local mk = run.makes_on[item] or {}
      for net in pairs((run.feeds or {})[e.unit_number] or {}) do if mk[net] then return true end end
      return false
    end
    local function wait_for(e, item)
      if not heard(e, item) then
        local r = e.get_recipe()
        local k = item .. " <- " .. (r and r.name or "?")
        run.orphan_s[k] = (run.orphan_s[k] or 0) + 1
      end
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
    -- params.requester_demand: a robot link's requester chest holding less than its request counts as a machine
    -- waiting for the item on its network (the provider's belt and the requester's are one). Robots take minutes to
    -- cross a large factory; a maker that ran only once the machines past the requester stood short left them waiting
    -- a trip each time (Py production science: 349 of 444 requesters empty).
    if run.params.requester_demand and run.req_list then
      for _, rq in pairs(run.req_list) do
        local c = rq.chest
        if c.valid and rq.belt.valid then
          if not rq.count then
            rq.count = 10
            pcall(function()
              local pt = c.get_logistic_point(defines.logistic_member_index.logistic_container)
              for _, f in pairs(pt and pt.filters or {}) do if f.name == rq.item then rq.count = f.count end end
            end)
          end
          if c.get_item_count(rq.item) < rq.count then
            local net = run.net_of and run.net_of[rq.belt.unit_number]
            if net then
              local sn = short_net[rq.item] or {}
              sn[net] = true
              short_net[rq.item] = sn
              run.req_short = (run.req_short or 0) + 1
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
    -- params.belt_pull: an inserter putting a machine's products onto a belt works only while a user on that belt
    -- network holds less than its stock of one of them (or the product is a target). A metered machine makes just its
    -- planned amount; dropping onto three belts it hands a third to each by turns, whatever each needs, and the belt
    -- whose users are stocked silts up for hours (800 plates on 100 tiles) before it backs up to the machine, while the
    -- users on the others get none (Py chemical science under meters: titanium plate made at its rate, three of its
    -- seven users without any). What no belt wants stays in the machine, where the virtual sinks take it.
    if run.params.belt_pull and run.outs then
      if not run.mrec then run.mrec = {}; for _, m in pairs(run.machines) do if m.e.valid then run.mrec[m.e.unit_number] = m end end end
      local sn = low_net or short_net
      local target = {}
      for _, n in pairs(run.params.pull_wanted or run.params.targets or {}) do target[n] = true end
      local held = 0
      for _, o in pairs(run.outs) do
        if o.e.valid and o.m.valid then
          local rec = run.mrec[o.m.unit_number]
          local r = o.m.get_recipe() or (rec and rec.plan_recipe and prototypes.recipe[rec.plan_recipe])
          local want = r == nil
          if r then
            for _, p in pairs(r.products) do
              if p.type == "item" then
                local on = sn[p.name]
                -- (params.virtual_bots: only targets leave by belt; the rest waits in the machine to be fetched.)
                if target[p.name] or (not run.params.virtual_bots and on and on[o.net]) then want = true; break end
              end
            end
          end
          if not want then held = held + 1 end
          if o.e.disabled_by_script ~= (not want) then o.e.disabled_by_script = not want end
        end
      end
      run.belt_held = held
    end
  end
  if run.params.pull and game.tick % 60 == 0 and run.short then
    local short_net, short_at, short_fluid = run.short.low_net or run.short.net, run.short.low_at or run.short.at, run.short.low_fluid or run.short.fluid
    local target = {}
    for _, n in pairs(run.params.pull_wanted or {}) do target[n] = true end
    run.pulled_off = 0
    run.side_capped = 0
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
        -- params.side_cap (with params.purpose and params.plan_crafts): what the machine was planned for (its main
        -- product, or every product the plan uses when the main one leaves as a by-product). Wanted only for another of
        -- its products, it runs no faster than side_cap times its plan (over the last ten minutes, plus two crafts).
        -- Py production science's coal gas plant ran at nine times its plan for a trace of iron oxide on its own belt
        -- (and coke), and the coke filled the robot provider it feeds and stopped it.
        local purpose = (run.params.side_cap or run.params.rate_cap) and (run.params.purpose or {})[r.name]
        local main_wanted = false
        local capped = false
        for _, p in pairs(products) do
          local hit = false
          if target[p.name] then hit = true; known = true
          elseif p.type == "fluid" then
            known = true
            if fhit(short_fluid, e, p.name) then hit = true end
          else
            local sn = short_net[p.name]
            for net in pairs(drops) do known = true; if sn and sn[net] then hit = true end end
            for u in pairs(hands) do known = true; if short_at[u] and short_at[u][p.name] then hit = true end end
          end
          if hit then
            wanted = true
            if not purpose or purpose[p.name] or target[p.name] then main_wanted = true; break end
          end
        end
        if purpose then
          m.sc = m.sc or {}
          local h = m.sc
          h[#h + 1] = { tick = game.tick, pf = e.products_finished }
          while #h > 1 and game.tick - h[1].tick > 600 * 60 do table.remove(h, 1) end
          local c = (run.params.plan_crafts or {})[r.name]
          local span = (game.tick - h[1].tick) / 60
          if wanted and not main_wanted and c and run.params.side_cap then
            if e.products_finished - h[1].pf > c * span * run.params.side_cap + 2 then
              wanted = false
              capped = true
              run.side_capped = run.side_capped + 1
            end
          end
          -- params.rate_cap: no machine runs faster than that many times its plan over the last ten minutes (plus
          -- three crafts), whatever wants it. Loops and machines with several products kept each other wanted and
          -- spun far past their plan (Py production science, fed where short: 330 recipes above 1.5 times their plan,
          -- the uranium enrichment cascade at 20 to 33 times), taking the intermediates other chains needed and
          -- pouring the rest into by-product chests.
          local makes_target = false
          for _, p in pairs(r.products) do if target[p.name] then makes_target = true end end
          if wanted and c and run.params.rate_cap and not makes_target then
            if e.products_finished - h[1].pf > c * span * run.params.rate_cap + 3 then
              wanted = false
              capped = true
              run.side_capped = run.side_capped + 1
            end
          end
        end
        -- (A machine held to its cap stays off: a full maker pushing it would start it again.)
        if not wanted and not capped then
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
  -- params.meter: every machine held to that many times its planned rate (params.plan_crafts), as a meter would: a
  -- token bucket of one craft that fills at the planned rate, a machine with none left stands until it has one again.
  -- Unlike rate_cap (ten minutes' plan plus three crafts, which for a machine planned at a craft every five minutes is
  -- two and a half times its plan) this holds the slow machines too. The plan run with ideal transport (one pool per
  -- item) makes its target only when no machine runs past its plan: one that does takes what an exactly balanced
  -- neighbour needed. Runs after pull, and only ever switches off what pull left on.
  if run.params.meter and game.tick % 60 == 0 then
    local crafts, held = run.params.plan_crafts or {}, 0
    for _, m in pairs(run.machines) do
      local e = m.e
      if e.valid then
        local r = e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe])
        local c = r and crafts[r.name]
        if c and c > 0 then
          local pf = e.products_finished
          local t = m.mt
          -- (params.meter_start: crafts each recipe's meter starts with in hand, enough to give every user of its
          -- products one craft's worth: without it a machine planned at a craft in three hours keeps a new factory
          -- waiting for its second craft, and chains of them take a day to start.)
          if not t then t = { tok = (run.params.meter_start or {})[r.name] or 1, pf = pf, tick = game.tick }; m.mt = t end
          local rate = c * run.params.meter
          -- (params.meter_fill {k, hours}: for the first hours every meter runs k times faster, a fast start. The
          -- plan-level model has the built logistic science plan make its first pack at hour 36 from empty once every
          -- machine keeps two crafts' worth of each input for itself, and at hour 19 with meters five times faster
          -- for the first twelve.)
          local mf = run.params.meter_fill
          if mf and run.t0 and game.tick - run.t0 < mf[2] * 216000 then rate = rate * mf[1] end
          -- (params.meter_bucket: the seconds of its planned rate a waiting machine may save up, 60 unless given. With a
          -- minute a machine that stood short for an hour has lost that hour for good. A user that takes a big batch
          -- seldom fills its tank of two batches first, and a small steady user on the same pipe gets its share in
          -- bursts: Py chemical science's fusion reactor takes 10,000 pressured water a craft, a craft in two and a
          -- half hours, and its heat exchanger, 100 a craft, ran at a fifth of its rate.)
          t.tok = math.min(t.tok + rate * (game.tick - t.tick) / 60, math.max(t.tok, 1 + rate * (run.params.meter_bucket or 60))) - (pf - t.pf)
          t.pf, t.tick = pf, game.tick
          if t.tok <= 0 then
            held = held + 1
            m.meter_held = true
            e.disabled_by_script = true
          elseif m.meter_held then
            m.meter_held = false
            if not run.params.pull then e.disabled_by_script = false end
          end
        end
      end
    end
    run.metered = held
  end
  -- params.duty: every machine held to that many times its planned rate the way a blueprint can do it with no
  -- combinator of its own: an enable condition on the machine, "clock < K", against a clock signal on a wire over the
  -- poles. The machine works K ticks of every period, K = period x factor x planned rate / its top rate. Three clocks
  -- (10 s, 10 min, 10 h): each machine takes the shortest whose window is a second or more, so slivers get a window
  -- at all (a craft that does not fit one window goes on in the next). Unlike params.meter nothing is saved up, and
  -- every machine on one clock works at the same time.
  if run.params.duty then
    local crafts = run.params.plan_crafts or {}
    if not run.duty_set then
      run.duty_set = true
      local n = { 0, 0, 0, 0 }
      for _, m in pairs(run.machines) do
        local e = m.e
        local r = e.valid and (e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe]))
        local c = r and crafts[r.name]
        if c and c > 0 then
          local top = e.crafting_speed / r.energy
          local d = math.min(1, run.params.duty * c / top)
          if d < 1 then
            for i, period in ipairs({ 600, 36000, 2160000 }) do
              if d * period >= 60 or i == 3 then m.duty = { period = period, k = math.max(1, math.floor(d * period + 0.5)) }; n[i] = n[i] + 1; break end
            end
          else n[4] = n[4] + 1 end
        end
      end
      run.duty_clocks = { ["10s"] = n[1], ["10min"] = n[2], ["10h"] = n[3], free = n[4] }
    end
    local held = 0
    for _, m in pairs(run.machines) do
      if m.duty and m.e.valid then
        local off = (game.tick - run.t0) % m.duty.period >= m.duty.k
        if off then held = held + 1 end
        if off ~= (m.duty_off or false) then m.duty_off = off; m.e.disabled_by_script = off end
      end
    end
    run.metered = held
  end
  -- params.flow: quotas. A machine that has taken more of an item or fluid than its plan allows so far (flow_rates: per
  -- second, times 1.5, plus two crafts' worth) is held while a machine behind its own quota stands short of it, anywhere
  -- in the factory: its inserters bringing that item stop, or for a fluid the machine itself. No user takes what those
  -- behind plan need (Py logistic science: the petri-dish-bacteria machine took all 100 dishes, 12 times its plan, and
  -- the zogna incubator after it got none; meat-to-cyanic took meat at 100 times its plan from its own belt off the
  -- renderer, short of meat itself, while the animal sample on another belt got none). One nobody behind plan waits
  -- behind takes what it likes.
  if run.params.flow and not run.params.no_quotas and game.tick % 60 == 0 and run.short and run.into then
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
          -- (params.quota_factor: how far ahead of its plan, default 1.5. Supply at plan, a user at 1.4 times its plan
          -- took everything: Py production science's molten steel all 6/s of molten iron, four casting machines none.)
          if taken > (rates[ing.name] or 0) * span * (run.params.quota_factor or 1.5) + ing.amount * 2 then
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
          if e.valid and not e.disabled_by_script and (e.status == defines.entity_status.full_output or e.status == defines.entity_status.full_burnt_result_output
              or e.status == defines.entity_status.waiting_for_space_in_destination) then
            -- (Only one that makes something else too, or burns fuel to ash: one making only what the belt carries
            -- just has no more users and should stop, not pour into the overflow.)
            local r = e.get_recipe()
            local n = 0
            for _ in pairs(r and r.products or {}) do n = n + 1 end
            if not r or n > 1 or e.status == defines.entity_status.full_burnt_result_output then close = false; break end
          end
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
  -- params.fluid_gates: a fluid overflow (the infinity pipe at a 0/s fluid output, or a block's local fluid sink) keeps
  -- its pipes at most nine tenths full, so users' boxes fill, and at most four tenths while a maker of that fluid on
  -- the same pipes stands full (no room for a batch) and no user there waits for it. Held at half full for good, users
  -- whose craft takes half their box (the game sizes a machine's box at about twice its recipe's amount) never filled
  -- it: Py production science's treated wood held 56 and 86 of the 100 creosote a craft takes, the shell printer 48
  -- of 50 fetal serum. Shut until a maker stood full, they never opened: pull switches makers off before they fill.
  if run.params.fluid_gates and run.fgates and run.short and game.tick % 60 == 0 then
    run.fgates_open = 0
    -- (Its pipe network by the infinity pipe's own box; else, not knowing it, any maker of that fluid anywhere.)
    local function hit(tab, g, name)
      local t = tab and tab[name]
      if not t then return false end
      if t.any then return true end
      local ok, id = pcall(function() return g.e.get_fluid_segment_id(1) end)
      if ok and id then return t[id] == true end
      return next(t) ~= nil
    end
    for _, g in pairs(run.fgates) do
      if g.e.valid then
        local name = g.filter.name
        local open = hit(run.short.push_fluid, g, name) and not hit(run.short.fluid, g, name)
        if open then run.fgates_open = run.fgates_open + 1; g.opened = (g.opened or 0) + 1 end
        -- (What it saw, for the report: its network, and seconds a maker stood full / a user waited there.)
        local ok, id = pcall(function() return g.e.get_fluid_segment_id(1) end)
        g.seg = ok and id or g.seg
        if hit(run.short.push_fluid, g, name) then g.push_s = (g.push_s or 0) + 1 end
        if hit(run.short.fluid, g, name) then g.short_s = (g.short_s or 0) + 1 end
        if open ~= g.open then
          g.open = open
          pcall(function() g.e.set_infinity_pipe_filter({ name = name, percentage = open and 0.4 or 0.9, mode = "at-most", temperature = g.filter.temperature }) end)
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
    working = working, machines = #(run.machines or {}), target_crafts = made, off = run.pulled_off, held = run.quota_held, stopped = run.quota_stopped, gates = run.gates_closed,
    capped = run.side_capped, metered = run.metered, belt_held = run.belt_held, fgates = run.fgates and #run.fgates or nil, fgates_open = run.fgates_open }
  -- (Under the factory-flow mod: what it is doing.)
  if remote.interfaces["factory-flow"] then
    local s = remote.call("factory-flow", "status") or {}
    st.off, st.held, st.stopped, st.gates, st.capped = s.off, s.held, s.stopped, s.gates_closed, s.capped
  end
  reply(st)
end)
