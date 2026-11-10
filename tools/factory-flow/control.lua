-- Factory flow: keeps a factory laid out by factorio_optimizer near its plan, for blueprints tagged by tag.py.
--
-- Machines built from a tagged blueprint carry tags.flow = { take = { [item or fluid] = planned intake per second },
-- target = true (makes a target) } and furnaces tags.recipe (their planned recipe); each overflow port's last belt
-- carries tags.flow = { overflow = item }. Once a second the mod:
--   pull:   switches a machine off while no machine it feeds (along its output belts, or by hand) waits for one of its
--           products, unless it makes a target, its outputs could not be traced, or a machine standing full has one of
--           its ingredients stuck in its output (push);
--   quotas: stops the inserters bringing an item into a machine that has taken more of it than its plan allows so far
--           (planned intake x factor + two crafts), and for a fluid stops the machine itself, while a machine still
--           behind its own plan waits for that item anywhere in the factory;
--   gates:  on each overflow port's belt, stops the belt just past the last machine taking from it while a machine on
--           it waits, or while no machine putting onto it stands with a full output.
-- Tested in game by tools/factorio-verify/verify.py --flow-mod (the same rules as its --flow).

local CRAFTERS = { ["assembling-machine"] = true, ["furnace"] = true }
local BELTS = { ["transport-belt"] = true, ["underground-belt"] = true, ["splitter"] = true, ["linked-belt"] = true }
local SHORT = {
  [defines.entity_status.no_ingredients] = true, [defines.entity_status.item_ingredient_shortage] = true,
  [defines.entity_status.fluid_ingredient_shortage] = true, [defines.entity_status.no_fuel] = true,
}
-- (Py's wood processing units say "waiting for space in destination" when their output is full.)
local FULL = { [defines.entity_status.full_output] = true, [defines.entity_status.full_burnt_result_output] = true,
  [defines.entity_status.waiting_for_space_in_destination] = true }

local function setting(name) return settings.global["factory-flow-" .. name].value end

local function init()
  storage.machines = storage.machines or {}   -- unit number -> { e, take, target, recipe, fq, off, stop, held }
  storage.ends = storage.ends or {}           -- unit number -> { e, item }: overflow ports' last belts
  storage.stats = storage.stats or {}
  storage.dirty = true
end

script.on_init(init)
script.on_configuration_changed(init)

local function recipe_of(m)
  local e = m.e
  return e.get_recipe() or (m.recipe and prototypes.recipe[m.recipe])
end

-- What a machine burns: what it takes besides its recipe's ingredients (the plan counts fuel among a machine's intake).
local function fuels(m, r)
  local ings, out = {}, {}
  for _, ing in pairs(r.ingredients) do ings[ing.name] = true end
  for name in pairs(m.take or {}) do
    if not ings[name] and prototypes.item[name] then out[#out + 1] = name end
  end
  return out
end

-- Building and removing ----------------------------------------------------------------------------------------------

local function on_built(ev)
  local e = ev.entity or ev.destination
  if not (e and e.valid and e.unit_number) then return end
  local tags = ev.tags
  local f = tags and tags.flow
  if f and CRAFTERS[e.type] then
    storage.machines[e.unit_number] = { e = e, take = f.take or {}, target = f.target, recipe = tags.recipe, carriers = f.carriers }
  elseif f and f.overflow and BELTS[e.type] then
    storage.ends[e.unit_number] = { e = e, item = f.overflow }
  end
  if CRAFTERS[e.type] or BELTS[e.type] or e.type == "inserter" then storage.dirty = true end
end

for _, name in pairs({ "on_built_entity", "on_robot_built_entity", "script_raised_built", "script_raised_revive",
  "on_space_platform_built_entity" }) do
  if defines.events[name] then script.on_event(defines.events[name], on_built) end
end

local function on_removed(ev)
  local e = ev.entity
  if not (e and e.valid and e.unit_number) then return end
  storage.machines[e.unit_number] = nil
  storage.ends[e.unit_number] = nil
  local a = (storage.anchors or {})[e.unit_number]
  if a then
    if a.anchor.valid then a.anchor.destroy() end
    storage.anchors[e.unit_number] = nil
  end
  if CRAFTERS[e.type] or BELTS[e.type] or e.type == "inserter" then storage.dirty = true end
end

for _, name in pairs({ "on_player_mined_entity", "on_robot_mined_entity", "on_entity_died", "script_raised_destroy",
  "on_space_platform_mined_entity" }) do
  if defines.events[name] then script.on_event(defines.events[name], on_removed) end
end
script.on_event(defines.events.on_player_rotated_entity, function() storage.dirty = true end)

-- Where things go: belt networks, and which inserters feed which machine ------------------------------------------

local function at(surface, p)
  for _, x in pairs(surface.find_entities_filtered { area = { { p.x - 0.05, p.y - 0.05 }, { p.x + 0.05, p.y + 0.05 } } }) do
    if BELTS[x.type] or CRAFTERS[x.type] then return x end
  end
end

local function rebuild()
  local T = { into = {}, hands = {}, feeds = {}, drops = {}, net_of = {}, pick = {}, gates = {}, ins_net = {} }
  -- A belt's network: everything joined to it through belt neighbours, undergrounds and splitters.
  local function net(b)
    local id = T.net_of[b.unit_number]
    if id then return id end
    id = b.unit_number
    T.net_of[id] = id
    local queue, head = { b }, 1
    while head <= #queue and head < 200000 do
      local cur = queue[head]
      head = head + 1
      local nb = cur.belt_neighbours or {}
      local next = {}
      for _, o in pairs(nb.inputs or {}) do next[#next + 1] = o end
      for _, o in pairs(nb.outputs or {}) do next[#next + 1] = o end
      if cur.type == "underground-belt" and cur.underground_belt_neighbour then next[#next + 1] = cur.underground_belt_neighbour end
      if cur.type == "linked-belt" and cur.linked_belt_neighbour then next[#next + 1] = cur.linked_belt_neighbour end
      for _, o in pairs(next) do
        if o.valid and o.unit_number and not T.net_of[o.unit_number] then
          T.net_of[o.unit_number] = id
          queue[#queue + 1] = o
        end
      end
    end
    return id
  end
  local function add(t, k, v) local s = t[k] or {}; s[v] = true; t[k] = s end
  for unit, m in pairs(storage.machines) do
    local e = m.e
    if e.valid then
      local bb = e.bounding_box
      local area = { { bb.left_top.x - 3, bb.left_top.y - 3 }, { bb.right_bottom.x + 3, bb.right_bottom.y + 3 } }
      for _, i in pairs(e.surface.find_entities_filtered { area = area, type = "inserter" }) do
        local d = i.drop_target or at(e.surface, i.drop_position)
        local pk = i.pickup_target or at(e.surface, i.pickup_position)
        if d and d.unit_number == unit then
          local l = T.into[unit] or {}
          l[#l + 1] = i
          T.into[unit] = l
          if pk and BELTS[pk.type] then
            add(T.feeds, unit, net(pk))
            T.pick[pk.unit_number] = true
            T.ins_net[i.unit_number] = net(pk)
          elseif pk and CRAFTERS[pk.type] then
            add(T.hands, pk.unit_number, unit)
          end
        elseif pk and pk.unit_number == unit and d then
          if BELTS[d.type] then add(T.drops, unit, net(d)) end
          if CRAFTERS[d.type] then add(T.hands, unit, d.unit_number) end
        end
      end
    end
  end
  -- Gates: walking up from each overflow port's last belt, the belt just past the first one a machine takes from.
  for _, en in pairs(storage.ends) do
    local b = en.e
    if b.valid then
      local seen, queue, head, gate, toward = { [b.unit_number] = true }, { { b, nil } }, 1, nil, {}
      while head <= #queue and head < 5000 do
        local cur, child = queue[head][1], queue[head][2]
        head = head + 1
        -- (Or a splitter: its other side may lead to users, which a gate before it would cut off: chemical science's
        -- borax, 1,237 of it queued in front of a splitter whose other side fed ten machines waiting for it.)
        if T.pick[cur.unit_number] or (cur.type == "splitter" and child) then gate = child; break end
        local ups = (cur.belt_neighbours or {}).inputs or {}
        if cur.type == "underground-belt" and cur.belt_to_ground_type == "output" and cur.underground_belt_neighbour then
          ups = { cur.underground_belt_neighbour }
        end
        for _, u in pairs(ups) do
          if u.valid and not seen[u.unit_number] then seen[u.unit_number] = true; toward[u.unit_number] = cur; queue[#queue + 1] = { u, cur } end
        end
      end
      -- (An underground or splitter just past it: the first plain belt on toward the sink, a few tiles of queue more.)
      while gate and gate.type ~= "transport-belt" do gate = toward[gate.unit_number] end
      if gate and gate.type == "transport-belt" then
        local g = { e = gate, item = en.item, net = net(gate), makers = {} }
        for unit in pairs(storage.machines) do
          if (T.drops[unit] or {})[g.net] then g.makers[#g.makers + 1] = unit end
        end
        -- (With no machine of ours putting onto it, nothing tells when to open: no gate.)
        if #g.makers > 0 then T.gates[#T.gates + 1] = g end
      end
    end
  end
  -- Each gate hangs on a circuit network of its own anchor (data.lua): a belt cannot be stopped by script, but one on
  -- a network stops by its enable condition. Gates from before that are no longer gates run again.
  local now = {}
  for _, g in pairs(T.gates) do now[g.e.unit_number] = true end
  storage.anchors = storage.anchors or {}
  for unit, a in pairs(storage.anchors) do
    if not now[unit] then
      if a.belt.valid then pcall(function() a.belt.get_or_create_control_behavior().circuit_enable_disable = false end) end
      if a.anchor.valid then a.anchor.destroy() end
      storage.anchors[unit] = nil
    end
  end
  for _, g in pairs(T.gates) do
    local a = storage.anchors[g.e.unit_number]
    if not (a and a.anchor.valid) then
      local anchor = g.e.surface.create_entity { name = "factory-flow-gate-anchor", position = g.e.position, force = g.e.force }
      local ok = anchor ~= nil and pcall(function()
        g.e.get_wire_connector(defines.wire_connector_id.circuit_red, true)
          .connect_to(anchor.get_wire_connector(defines.wire_connector_id.circuit_red, true), false)
        local cb = g.e.get_or_create_control_behavior()
        cb.circuit_condition = { first_signal = { type = "virtual", name = "signal-A" }, comparator = ">", constant = 0 }
        cb.circuit_enable_disable = false
      end)
      if not ok and anchor then anchor.destroy() end
      a = ok and { anchor = anchor, belt = g.e, closed = false } or nil
      storage.anchors[g.e.unit_number] = a
    end
    g.anchor = a
  end
  storage.topo = T
  storage.dirty = false
  storage.rebuilt = game.tick
end

-- Letting go of everything the mod stopped.
local function release()
  for _, m in pairs(storage.machines) do
    if m.e.valid and (m.off or m.stop) then m.e.disabled_by_script = false end
    m.off, m.stop, m.disabled = nil, nil, nil
    for _, i in pairs(m.held or {}) do if i.valid then i.disabled_by_script = false end end
    m.held = nil
  end
  for _, a in pairs(storage.anchors or {}) do
    if a.closed and a.belt.valid then pcall(function() a.belt.get_or_create_control_behavior().circuit_enable_disable = false end) end
    a.closed = false
  end
end

script.on_event(defines.events.on_runtime_mod_setting_changed, function(ev)
  if ev.setting and ev.setting:find("^factory%-flow%-") then release() end
end)

-- Once a second --------------------------------------------------------------------------------------------------

local function control()
  local T = storage.topo
  local tick = game.tick
  local stats = { machines = 0, off = 0, held = 0, stopped = 0, gates = 0, gates_closed = 0 }
  -- What machines wait for: item -> belt networks along which one waits, machine -> items, fluids.
  local short_net, short_at, fluid_at, short_fluid = {}, {}, {}, {}
  local function wait(unit, item)
    local sn = short_net[item] or {}
    for n in pairs(T.feeds[unit] or {}) do sn[n] = true end
    short_net[item] = sn
    local a = short_at[unit] or {}
    a[item] = true
    short_at[unit] = a
  end
  local recipes = {}
  for unit, m in pairs(storage.machines) do
    local e = m.e
    if e.valid then
      stats.machines = stats.machines + 1
      local r = recipe_of(m)
      recipes[unit] = r
      if r and not e.disabled_by_script and SHORT[e.status] then
        if e.status == defines.entity_status.no_fuel then for _, f in pairs(fuels(m, r)) do wait(unit, f) end end
        for _, ing in pairs(r.ingredients) do
          if ing.type == "item" then
            if e.get_item_count(ing.name) < ing.amount then wait(unit, ing.name) end
          elseif (e.get_fluid_count(ing.name) or 0) < ing.amount then
            short_fluid[ing.name] = true
            local a = fluid_at[unit] or {}
            a[ing.name] = true
            fluid_at[unit] = a
          end
        end
      end
    end
  end

  -- What full makers push: a machine standing with a full output gets the users of what is stuck in it going (on the
  -- belts it drops onto, the machines it hands to; a fluid anywhere, when no item is stuck). A ring stops for good
  -- otherwise: a renderer full of cages, and the caging machine off since the renderer is full, not waiting.
  local push_net, push_at, push_fluid = {}, {}, {}
  for unit, m in pairs(storage.machines) do
    local e, r = m.e, recipes[unit]
    if r and e.valid and not e.disabled_by_script and (e.status == defines.entity_status.full_output
        or e.status == defines.entity_status.waiting_for_space_in_destination) then
      local out = e.get_output_inventory()
      local items_stuck = false
      for _, p in pairs(r.products) do
        if p.type == "item" and out and out.get_item_count(p.name) > 0 then items_stuck = true end
      end
      for _, p in pairs(r.products) do
        if p.type == "fluid" then
          if not items_stuck and (e.get_fluid_count(p.name) or 0) > 0 then push_fluid[p.name] = true end
        elseif out and out.get_item_count(p.name) > 0 then
          local pn = push_net[p.name] or {}
          for n in pairs(T.drops[unit] or {}) do pn[n] = true end
          push_net[p.name] = pn
          for u in pairs(T.hands[unit] or {}) do
            local a = push_at[u] or {}
            a[p.name] = true
            push_at[u] = a
          end
        end
      end
    end
  end
  local function pushed(unit, ing)
    if ing.type == "fluid" then return push_fluid[ing.name] == true end
    if push_at[unit] and push_at[unit][ing.name] then return true end
    local pn = push_net[ing.name]
    if pn then for n in pairs(T.feeds[unit] or {}) do if pn[n] then return true end end end
    return false
  end

  -- Pull ahead (setting above 1): a machine working with less than that many crafts' worth of an item counts as
  -- wanting it too, so its makers run ahead of it.
  local want_net, want_at = short_net, short_at
  local ahead = setting("pull-ahead")
  if ahead > 1 then
    want_net, want_at = {}, {}
    for item, nets in pairs(short_net) do local c = {}; for n in pairs(nets) do c[n] = true end; want_net[item] = c end
    for u, items in pairs(short_at) do local c = {}; for i in pairs(items) do c[i] = true end; want_at[u] = c end
    for unit, m in pairs(storage.machines) do
      local e, r = m.e, recipes[unit]
      if r and e.valid and not e.disabled_by_script and e.status == defines.entity_status.working then
        for _, ing in pairs(r.ingredients) do
          if ing.type == "item" and e.get_item_count(ing.name) < ahead * ing.amount then
            local wn = want_net[ing.name] or {}
            for n in pairs(T.feeds[unit] or {}) do wn[n] = true end
            want_net[ing.name] = wn
            local a = want_at[unit] or {}
            a[ing.name] = true
            want_at[unit] = a
          end
        end
      end
    end
  end

  -- Pull.
  if setting("pull") then
    for unit, m in pairs(storage.machines) do
      local r = recipes[unit]
      if m.e.valid and r then
        local wanted, known = m.target == true, m.target == true
        if not wanted then
          local drops, hands = T.drops[unit] or {}, T.hands[unit] or {}
          local products = {}
          for _, p in pairs(r.products) do products[#products + 1] = p end
          -- (What its fuel burns to counts as made too: ash for planter boxes.)
          for _, f in pairs(fuels(m, r)) do
            local b = prototypes.item[f].burnt_result
            if b then products[#products + 1] = { name = b.name, type = "item" } end
          end
          for _, p in pairs(products) do
            if p.type == "fluid" then
              known = true
              if short_fluid[p.name] then wanted = true end
            else
              local sn = want_net[p.name]
              for n in pairs(drops) do known = true; if sn and sn[n] then wanted = true end end
              for u in pairs(hands) do known = true; if want_at[u] and want_at[u][p.name] then wanted = true end end
            end
            if wanted then break end
          end
        end
        if not wanted then
          for _, ing in pairs(r.ingredients) do
            if pushed(unit, ing) then wanted = true; known = true; break end
          end
        end
        m.off = known and not wanted
      end
    end
  end

  -- Quotas: those ahead of their plan give way to those behind.
  if setting("quotas") then
    local factor = setting("quota-factor")
    local over, behind = {}, {}
    for unit, m in pairs(storage.machines) do
      local e, r = m.e, recipes[unit]
      if e.valid and r then
        m.fq = m.fq or { t0 = tick, pf0 = e.products_finished, c0 = {}, item_of = {} }
        local secs = (tick - m.fq.t0) / 60
        local window = setting("quota-window") * 60   -- seconds; 0: since the machine was built
        local o = {}
        for _, ing in pairs(r.ingredients) do
          local have = ing.type == "item" and e.get_item_count(ing.name) or (e.get_fluid_count(ing.name) or 0)
          local c0 = m.fq.c0[ing.name]
          if c0 == nil then c0 = have; m.fq.c0[ing.name] = c0 end
          local taken = (e.products_finished - m.fq.pf0) * ing.amount + have - c0
          local span = secs
          -- (A window: what it took in that last stretch, so running ahead early on does not hold it for hours.)
          if window > 0 then
            m.fq.hist = m.fq.hist or {}
            local h = m.fq.hist[ing.name]
            if not h then h = { { tick = tick, taken = taken } }; m.fq.hist[ing.name] = h end
            if tick - h[#h].tick >= 3600 then h[#h + 1] = { tick = tick, taken = taken } end
            while #h > 1 and tick - h[2].tick >= window * 60 do table.remove(h, 1) end
            span = (tick - h[1].tick) / 60
            taken = taken - h[1].taken
          end
          if taken > (m.take[ing.name] or 0) * span * factor + ing.amount * 2 then
            o[ing.name] = true
          elseif (short_at[unit] and short_at[unit][ing.name]) or (fluid_at[unit] and fluid_at[unit][ing.name]) then
            behind[ing.name] = true
          end
        end
        over[unit] = o
      end
    end
    for unit, m in pairs(storage.machines) do
      local e, r, o = m.e, recipes[unit], over[unit]
      if e.valid and r and o then
        local capped, stop = {}, false
        for _, ing in pairs(r.ingredients) do
          -- (Not what a full maker pushes to it: taking that stops no one behind.)
          if o[ing.name] and behind[ing.name] and not pushed(unit, ing) then
            if ing.type == "fluid" then stop = true else capped[ing.name] = true end
          end
        end
        m.stop = stop
        if stop then stats.stopped = stats.stopped + 1 end
        -- A returned catalyst stuck in its output (it stands full): it takes that item only from its own loop until
        -- the loop drains (a corral giving back bedding onto a short belt into its own input, kept full from a supply
        -- belt, stopped for good).
        local returned = {}
        if e.status == defines.entity_status.full_output then
          local out = e.get_output_inventory()
          for _, ing in pairs(r.ingredients) do
            if ing.type == "item" and out and out.get_item_count(ing.name) > 0 then returned[ing.name] = true end
          end
        end
        local drops = T.drops[unit] or {}
        local held = {}
        for _, i in pairs(T.into[unit] or {}) do
          if i.valid then
            local h = i.held_stack
            if h and h.valid_for_read then m.fq.item_of[i.unit_number] = h.name end
            local it = m.fq.item_of[i.unit_number]
            local net = T.ins_net[i.unit_number]
            -- (Never one taking from its own return loop, a belt it drops onto: held, its returns could not come back.)
            local own_loop = net ~= nil and drops[net] ~= nil and drops[net] ~= false
            if it ~= nil and not own_loop and (capped[it] or (returned[it] and net ~= nil)) then
              held[i.unit_number] = i
              stats.held = stats.held + 1
              if not i.disabled_by_script then i.disabled_by_script = true end
            end
          end
        end
        for n, i in pairs(m.held or {}) do
          if not held[n] and i.valid then i.disabled_by_script = false end
        end
        m.held = held
      end
    end
  end

  -- Machines: off by pull, or stopped on a fluid.
  for _, m in pairs(storage.machines) do
    local e = m.e
    if e.valid then
      local want = (m.off or m.stop) == true
      if want then stats.off = stats.off + 1 end
      if want ~= (m.disabled == true) then
        e.disabled_by_script = want
        m.disabled = want
      end
    end
  end

  -- Gates.
  if setting("gates") then
    for _, g in pairs(T.gates) do
      if g.e.valid then
        stats.gates = stats.gates + 1
        local close = true
        for _, unit in pairs(g.makers) do
          local mm = storage.machines[unit]
          if mm and mm.e.valid and FULL[mm.e.status] then close = false; break end
        end
        if short_net[g.item] and short_net[g.item][g.net] then close = true end
        if close then stats.gates_closed = stats.gates_closed + 1 end
        local a = g.anchor
        if a and a.anchor.valid and close ~= a.closed then
          a.closed = close
          pcall(function() g.e.get_or_create_control_behavior().circuit_enable_disable = close end)
        end
      end
    end
  end
  -- A loop starving for its carriers (barrels, cages, bricks): a machine that has waited two minutes for one, while no
  -- machine making it stands full, gets a message (once in ten minutes per item). The mod puts nothing in itself: a
  -- new loop needs its carriers put in by hand once, as many as its machines and belts hold.
  local full_of = {}
  for unit, m in pairs(storage.machines) do
    local e, r = m.e, recipes[unit]
    if r and e.valid and FULL[e.status] then for _, p in pairs(r.products) do full_of[p.name] = true end end
  end
  storage.alerted = storage.alerted or {}
  for unit, m in pairs(storage.machines) do
    if m.carriers and m.e.valid then
      m.cwait = m.cwait or {}
      for _, item in pairs(m.carriers) do
        if short_at[unit] and short_at[unit][item] then
          m.cwait[item] = m.cwait[item] or tick
          if tick - m.cwait[item] >= 7200 and not full_of[item] and tick - (storage.alerted[item] or -1e9) >= 36000 then
            storage.alerted[item] = tick
            local p = m.e.position
            game.print({ "", "[factory-flow] A loop is short of its ", (prototypes.fluid[item] and "[fluid=" or "[item=") .. item .. "]",
              string.format(": the machine at [gps=%d,%d,%s] has waited two minutes for it. Put some in once; the loop keeps them.",
                math.floor(p.x), math.floor(p.y), m.e.surface.name) })
          end
        else
          m.cwait[item] = nil
        end
      end
    end
  end
  storage.stats = stats
end

script.on_nth_tick(60, function()
  if not setting("enabled") then return end
  if storage.dirty and game.tick - (storage.rebuilt or -1e9) >= 120 then rebuild() end
  if storage.topo then control() end
end)

-- /factory-flow: what the mod is doing.
commands.add_command("factory-flow", "Factory flow: machines controlled, switched off, inserters held, gates closed.", function(c)
  local s = storage.stats or {}
  local text = string.format("factory-flow: %d machines, %d switched off or stopped (%d on fluids), %d inserters held, %d of %d gates closed",
    s.machines or 0, s.off or 0, s.stopped or 0, s.held or 0, s.gates_closed or 0, s.gates or 0)
  if c.player_index then game.get_player(c.player_index).print(text) else rcon.print(text) end
end)

remote.add_interface("factory-flow", {
  status = function() return storage.stats end,
})
