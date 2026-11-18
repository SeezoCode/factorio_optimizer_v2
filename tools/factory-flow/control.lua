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
--           it waits, or while no machine putting onto it stands with a full output; for good where a loop's own item
--           or a carrier is put onto that belt;
--   loop first: holds the users outside an item's own production loop (tags.flow.loop_out) from it while a user inside
--           the loop (tags.flow.loop_in) stands short of it and took less than its plan over the last half hour;
--   shares: where one machine puts an item onto several belt networks, holds the inserters of a network nobody waits
--           on while another waits, and of one ahead of its planned share while one behind it is free.
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


-- An ingredient in a machine: in its input slots only. A Py farm runs on animals in its module slots, and a reproductive
-- complex holding xeno as modules, short of the xeno its recipe takes, was never seen waiting for it: its maker stayed
-- off for good (production science).
local function held(e, name)
  local inv = e.get_inventory(defines.inventory.crafter_input)
  if inv then return inv.get_item_count(name) end
  return e.get_item_count(name)
end

local function setting(name) return settings.global["factory-flow-" .. name].value end

local function init()
  storage.machines = storage.machines or {}   -- unit number -> { e, take, target, recipe, fq, off, stop, held }
  storage.ends = storage.ends or {}           -- unit number -> { e, item }: overflow ports' last belts
  storage.bots = storage.bots or {}           -- unit number -> { e, item }: robot links' provider and requester chests
  storage.sinks = storage.sinks or {}         -- unit number -> { e, item }: inserters into blocks' by-product chests
  storage.stats = storage.stats or {}
  storage.dirty = true
end

script.on_init(init)
script.on_configuration_changed(init)

-- A furnace whose blueprint named no recipe (Py's barrel fillers hold none while empty) carries the recipes the plan
-- has for its kind (tags.flow.candidates: recipe -> its tags). It is taken for the one it crafts, last crafted, or,
-- before its first craft, the only one that takes a fluid it holds. Unknown, it stood short of nothing: it pulled no
-- barrels from their maker (the in-game check: 38 water barrels in 2.3 hours of 661 an hour planned).
local function learn(m)
  local e = m.e
  local name
  local cur = e.get_recipe()
  if cur and m.cands[cur.name] then name = cur.name end
  if not name then pcall(function() local pr = e.previous_recipe; if pr then local n = type(pr.name) == "string" and pr.name or pr.name.name; if m.cands[n] then name = n end end end) end
  if not name then
    pcall(function()
      local holds = {}
      for k = 1, (e.fluids_count or 0) do local f = e.get_fluid(k); if f then holds[f.name] = true end end
      local found, n = nil, 0
      for rn in pairs(m.cands) do
        local rp = prototypes.recipe[rn]
        local takes = false
        for _, ing in pairs(rp and rp.ingredients or {}) do if ing.type == "fluid" and holds[ing.name] then takes = true end end
        if takes then found = rn; n = n + 1 end
      end
      if n == 1 then name = found end
    end)
  end
  if not name then return end
  local f = m.cands[name]
  local function set(l) if not l then return nil end local t = {}; for _, x in pairs(l) do t[x] = true end return t end
  m.recipe, m.take, m.target, m.carriers, m.crafts = name, f.take or {}, f.target, f.carriers, f.crafts
  m.purpose, m.loop_in, m.loop_out = set(f.purpose), set(f.loop_in), set(f.loop_out)
  m.cands = nil
  storage.stale = true
end

local function recipe_of(m)
  local e = m.e
  if m.cands and e.valid then learn(m) end
  if m.cands then return nil end
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
    local purpose
    if f.purpose then purpose = {}; for _, p in pairs(f.purpose) do purpose[p] = true end end
    local function set(l) if not l then return nil end local t = {}; for _, x in pairs(l) do t[x] = true end return t end
    storage.machines[e.unit_number] = { e = e, take = f.take or {}, target = f.target, recipe = tags.recipe, carriers = f.carriers,
      purpose = purpose, crafts = f.crafts, loop_in = set(f.loop_in), loop_out = set(f.loop_out), cands = f.candidates }
  elseif f and f.overflow and BELTS[e.type] then
    storage.ends[e.unit_number] = { e = e, item = f.overflow }
  elseif f and f.bot and e.type == "logistic-container" then
    storage.bots = storage.bots or {}
    storage.bots[e.unit_number] = { e = e, item = f.bot }
  elseif f and f.sink and e.type == "inserter" then
    storage.sinks = storage.sinks or {}
    storage.sinks[e.unit_number] = { e = e, item = f.sink }
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
  if storage.bots then storage.bots[e.unit_number] = nil end
  if storage.sinks then storage.sinks[e.unit_number] = nil end
  local a = (storage.anchors or {})[e.unit_number]
  if a then
    if a.anchor.valid then a.anchor.destroy() end
    storage.anchors[e.unit_number] = nil
  end
  local g = (storage.rs or {})[e.unit_number]
  if g then
    if g.anchor and g.anchor.valid then g.anchor.destroy() end
    storage.rs[e.unit_number] = nil
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
  local T = { into = {}, hands = {}, feeds = {}, drops = {}, net_of = {}, pick = {}, gates = {}, ins_net = {}, sinks = {}, reqs = {}, outs = {} }
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
          if BELTS[d.type] then
            add(T.drops, unit, net(d))
            T.outs[#T.outs + 1] = { e = i, unit = unit, net = net(d) }
          end
          if CRAFTERS[d.type] then add(T.hands, unit, d.unit_number) end
        end
      end
    end
  end
  -- Robot links (blocks.bot_links, tagged chests): the belt into a provider chest and those out of requester chests of
  -- one item count as one network, as robots carry between them.
  local rep = {}
  for _, c in pairs(storage.bots or {}) do
    local e = c.e
    if e.valid then
      local bb = e.bounding_box
      for _, i in pairs(e.surface.find_entities_filtered { area = { { bb.left_top.x - 3, bb.left_top.y - 3 }, { bb.right_bottom.x + 3, bb.right_bottom.y + 3 } }, type = "inserter" }) do
        local d = i.drop_target or at(e.surface, i.drop_position)
        local pk = i.pickup_target or at(e.surface, i.pickup_position)
        local belt = (d and d.unit_number == e.unit_number and pk and BELTS[pk.type] and pk)
          or (pk and pk.unit_number == e.unit_number and d and BELTS[d.type] and d)
        if belt then
          local n = net(belt)
          rep[c.item] = rep[c.item] or n
          rep[n] = rep[c.item]
          -- (A requester and the belt it feeds: its own shortfall is demand on the link, see control.)
          if pk and pk.unit_number == e.unit_number and e.prototype.logistic_mode == "requester" then
            T.reqs[#T.reqs + 1] = { e = e, item = c.item, belt_net = n }
          end
        end
      end
    end
  end
  if next(rep) then
    local function remap(t) for k, s in pairs(t) do local o = {}; for n in pairs(s) do o[rep[n] or n] = true end; t[k] = o end end
    remap(T.feeds); remap(T.drops)
    for k, n in pairs(T.ins_net) do T.ins_net[k] = rep[n] or n end
    for _, o in pairs(T.outs) do o.net = rep[o.net] or o.net end
    for _, rq in pairs(T.reqs) do rq.net = rep[rq.belt_net] or rq.belt_net end
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
  -- By-product chests beside blocks (blocks.local_sinks): the inserter into each, with the belt network it takes from
  -- and the machines putting onto it.
  for _, sk in pairs(storage.sinks or {}) do
    local i = sk.e
    if i.valid then
      local pk = i.pickup_target or at(i.surface, i.pickup_position)
      if pk and BELTS[pk.type] then
        local g = { e = i, item = sk.item, net = net(pk), makers = {} }
        for unit in pairs(storage.machines) do
          if (T.drops[unit] or {})[g.net] then g.makers[#g.makers + 1] = unit end
        end
        T.sinks[#T.sinks + 1] = g
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
  storage.stale = true
  storage.rebuilt = game.tick
end

-- Shares: for every item a machine puts onto two or more belt networks by inserters that carry nothing else (their
-- one filter, or the machine's only item product): the networks, each with its share of the planned use by the
-- machines taking from it, its inserters and those users. Balances are kept across a rebuild.
local function share_setup(T, recipes)
  local old = storage.os or {}
  for _, g in pairs(old) do
    for _, t in pairs(g.nets) do
      for _, i in pairs(t.ins) do if t.hold and i.e.valid then i.e.disabled_by_script = false end end
    end
  end
  local os = {}
  local use, users = {}, {}
  for unit, m in pairs(storage.machines) do
    local r = recipes[unit]
    if r then
      for _, ing in pairs(r.ingredients) do
        if ing.type == "item" then
          local rate = m.take[ing.name] or 0
          -- (On the belts seen bringing it the item; on every belt it takes from until one has been seen.)
          for n in pairs((m.brought or {})[ing.name] or T.feeds[unit] or {}) do
            use[ing.name] = use[ing.name] or {}
            use[ing.name][n] = (use[ing.name][n] or 0) + rate
            users[ing.name] = users[ing.name] or {}
            users[ing.name][n] = users[ing.name][n] or {}
            table.insert(users[ing.name][n], unit)
          end
        end
      end
    end
  end
  local by = {}
  for _, o in pairs(T.outs or {}) do
    if o.e.valid then by[o.unit] = by[o.unit] or {}; table.insert(by[o.unit], o) end
  end
  for unit, outs in pairs(by) do
    local r = recipes[unit]
    if r then
      local items = {}
      for _, p in pairs(r.products) do if p.type == "item" then items[#items + 1] = p end end
      for _, o in pairs(outs) do
        o.only = nil
        local n, f1 = 0, nil
        pcall(function()
          if o.e.use_filters then for k = 1, o.e.filter_slot_count do
            local f = o.e.get_filter(k)
            if f then n = n + 1; f1 = type(f) == "table" and f.name or f end
          end end
        end)
        if n == 1 then o.only = f1 elseif n == 0 and #items == 1 then o.only = items[1].name end
      end
      for _, p in pairs(items) do
        local nets, n, total = {}, 0, 0
        for _, o in pairs(outs) do
          if o.only == p.name then
            if not nets[o.net] then
              nets[o.net] = { ins = {}, rate = (use[p.name] or {})[o.net] or 0, users = (users[p.name] or {})[o.net] or {} }
              n = n + 1
              total = total + nets[o.net].rate
            end
            table.insert(nets[o.net].ins, { e = o.e, had = 0 })
          end
        end
        if n >= 2 and total > 0 then
          local key = unit .. ":" .. p.name
          local was = old[key]
          for id, t in pairs(nets) do
            t.share = t.rate / total
            local w = was and was.nets[id]
            t.sum, t.seen, t.bal = w and w.sum or 0, w and w.seen or 0, w and w.bal or 0
          end
          local amount = p.amount or ((p.amount_min or 0) + (p.amount_max or 0)) / 2
          os[key] = { item = p.name, nets = nets, slack = 16 }
        end
      end
    end
  end
  storage.os = os
end

-- Gates that stay shut: the port of a belt onto which a machine puts a loop's own item (tags loop_in of its users)
-- or a carrier. Py's moss farm drops its catalyst onto a belt back to itself; standing full of moss it opened that
-- belt's overflow and the catalyst left the factory.
local function loop_gates(T, recipes)
  local keep = {}
  for _, m in pairs(storage.machines) do
    if setting("loop-first") then for item in pairs(m.loop_in or {}) do keep[item] = true end end
    for _, item in pairs(m.carriers or {}) do keep[item] = true end
  end
  local onto = {}
  for _, o in pairs(T.outs or {}) do
    if o.e.valid then
      local t = onto[o.unit] or {}
      onto[o.unit] = t
      t[o.net] = t[o.net] or {}
      local any = true
      pcall(function()
        if o.e.use_filters then for k = 1, o.e.filter_slot_count do
          local f = o.e.get_filter(k)
          if f then any = false; t[o.net][type(f) == "table" and f.name or f] = true end
        end end
      end)
      if any then t[o.net]["*"] = true end
    end
  end
  for _, g in pairs(T.gates) do
    g.loop = false
    for _, unit in pairs(g.makers) do
      local r = recipes[unit]
      local on = (onto[unit] or {})[g.net] or {}
      for _, p in pairs(r and r.products or {}) do
        if p.type == "item" and keep[p.name] and (on[p.name] or on["*"]) then g.loop = true end
      end
    end
  end
end

-- Reserve (a setting, tiles; 0: off): a standing queue at each of a loop's own users. A belt serves a user only as
-- items pass it, or once the queue from the belt's end stands at its inserter; pull and the caps keep belts lean, so
-- what passes a user that is not asking at that moment parks at the far end. A loop then dies with its stock in
-- sight: Py science 1's seaweed loop stood with its fertilizer maker 2 seaweed short and 146 lying past it on the
-- loop's own belt. Per loop item and belt network, the first plain belt tile past the last of the loop's users on
-- it is a gate that stays shut until a lane of the tile that many tiles back up the belt takes nothing more: that
-- much queue stands at the loop's users before anything goes on to other users or the belt's end. It opens for
-- whatever fills the belt, so a mixed belt cannot lock.
local function reserve_setup(T, recipes)
  storage.rs = storage.rs or {}
  local N = setting("reserve")
  local function retire(g)
    if g.belt.valid then pcall(function() g.belt.get_or_create_control_behavior().circuit_enable_disable = false end) end
    if g.anchor and g.anchor.valid then g.anchor.destroy() end
  end
  if N <= 0 or not setting("loop-first") then
    for u, g in pairs(storage.rs) do retire(g); storage.rs[u] = nil end
    return
  end
  local makes_on = {}
  for unit in pairs(storage.machines) do
    local r = recipes[unit]
    for _, p in pairs(r and r.products or {}) do
      if p.type == "item" then
        makes_on[p.name] = makes_on[p.name] or {}
        for n in pairs(T.drops[unit] or {}) do makes_on[p.name][n] = true end
      end
    end
  end
  local function down(b)
    local outs = (b.belt_neighbours or {}).outputs or {}
    if b.type == "underground-belt" and b.belt_to_ground_type == "input" then outs = { b.underground_belt_neighbour } end
    return outs[1]
  end
  -- The pickup tiles of a loop's own users, by item and network: networks seen bringing the item to the machine, or
  -- that a maker of the item drops onto.
  local cands = {}
  for unit, m in pairs(storage.machines) do
    if recipes[unit] and m.loop_in and m.e.valid then
      for item in pairs(m.loop_in) do
        local nets = {}
        for n in pairs((m.brought or {})[item] or {}) do nets[n] = true end
        for n in pairs(T.feeds[unit] or {}) do if (makes_on[item] or {})[n] then nets[n] = true end end
        for _, i in pairs(T.into[unit] or {}) do
          local n = i.valid and T.ins_net[i.unit_number]
          if n and nets[n] then
            local pb
            for _, x in pairs(i.surface.find_entities_filtered { area = { { i.pickup_position.x - 0.05, i.pickup_position.y - 0.05 }, { i.pickup_position.x + 0.05, i.pickup_position.y + 0.05 } }, type = "transport-belt" }) do pb = x end
            if pb then
              local key = item .. ":" .. n
              cands[key] = cands[key] or {}
              cands[key][pb.unit_number] = pb
            end
          end
        end
      end
    end
  end
  local keep = {}
  for key, picks in pairs(cands) do
    for _, pb in pairs(picks) do
      local cur, last = pb, true
      for _ = 1, 600 do
        cur = down(cur)
        if not cur or not cur.valid or cur.type == "splitter" then break end
        if picks[cur.unit_number] then last = false; break end
      end
      if last then
        local gate, steps = pb, 0
        for _ = 1, 8 do
          gate = gate and down(gate)
          steps = steps + 1
          if not gate or not gate.valid or gate.type == "transport-belt" or gate.type == "splitter" then break end
        end
        -- (Never a belt that is an overflow gate already.)
        if gate and gate.valid and gate.type == "transport-belt" and not (storage.anchors or {})[gate.unit_number] then
          local u = gate.unit_number
          keep[u] = true
          if not storage.rs[u] then
            local sensor = pb
            for _ = 1, N - steps do
              local ins = (sensor.belt_neighbours or {}).inputs or {}
              if #ins ~= 1 or ins[1].type ~= "transport-belt" then break end
              sensor = ins[1]
            end
            local anchor = gate.surface.create_entity { name = "factory-flow-gate-anchor", position = gate.position, force = gate.force }
            local ok = anchor ~= nil and pcall(function()
              gate.get_wire_connector(defines.wire_connector_id.circuit_red, true)
                .connect_to(anchor.get_wire_connector(defines.wire_connector_id.circuit_red, true), false)
              local cb = gate.get_or_create_control_behavior()
              cb.circuit_condition = { first_signal = { type = "virtual", name = "signal-A" }, comparator = ">", constant = 0 }
              cb.circuit_enable_disable = false
            end)
            if ok then storage.rs[u] = { belt = gate, sensor = sensor, anchor = anchor, closed = false }
            elseif anchor then anchor.destroy() end
          end
        end
      end
    end
  end
  for u, g in pairs(storage.rs) do
    if not keep[u] or not g.belt.valid or not g.sensor.valid then retire(g); storage.rs[u] = nil end
  end
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
  for _, g in pairs(storage.os or {}) do
    for _, t in pairs(g.nets) do
      if t.hold then for _, i in pairs(t.ins) do if i.e.valid then i.e.disabled_by_script = false end end end
      t.hold = false
    end
  end
  for _, g in pairs(storage.rs or {}) do
    if g.closed and g.belt.valid then pcall(function() g.belt.get_or_create_control_behavior().circuit_enable_disable = false end) end
    g.closed = false
  end
  storage.stale = true
end

script.on_event(defines.events.on_runtime_mod_setting_changed, function(ev)
  if ev.setting and ev.setting:find("^factory%-flow%-") then release() end
end)

-- Once a second --------------------------------------------------------------------------------------------------

-- Fluid demand by pipe network: fluid -> { [segment id] = true, any = true when a machine's box had none }. A fluid
-- maker counted as wanted while a machine anywhere stood short of its fluid, even on pipes it does not feed (Py
-- production science: a coal gas plant at ten times its plan for a far consumer, until its coke filled its output).
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
  local t = tab[name]
  if not t then return false end
  if t.any then return true end
  for _, id in pairs(segments(e, name)) do if t[id] then return true end end
  return false
end

local function control()
  local T = storage.topo
  local tick = game.tick
  local stats = { machines = 0, off = 0, held = 0, stopped = 0, gates = 0, gates_closed = 0, capped = 0, share_held = 0 }
  local capped_at = {}   -- machine -> items a quota or the loop-first rule holds it from right now
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
  -- (The same for users that are behind their own plan: for those a maker may pass its rate cap, see there.)
  local behind_net, behind_at, behind_fluid = {}, {}, {}
  local fill = setting("cap-fill")
  local recipes = {}
  for unit, m in pairs(storage.machines) do
    local e = m.e
    if e.valid then
      stats.machines = stats.machines + 1
      local r = recipe_of(m)
      recipes[unit] = r
      if r and not e.disabled_by_script and SHORT[e.status] then
        if e.status == defines.entity_status.no_fuel then for _, f in pairs(fuels(m, r)) do wait(unit, f) end end
        -- (Behind its own plan, or waited on in the last seconds by a user that is: the leave passes up the chain.)
        local behind = fill and (not (m.crafts and m.sc and m.sc[1])
          or (e.products_finished - m.sc[1].pf) < m.crafts * (tick - m.sc[1].tick) / 60
          or (m.fb ~= nil and tick - m.fb <= 180))
        for _, ing in pairs(r.ingredients) do
          if ing.type == "item" then
            if held(e, ing.name) < ing.amount then
              wait(unit, ing.name)
              if behind then
                local bn = behind_net[ing.name] or {}
                for n in pairs(T.feeds[unit] or {}) do bn[n] = true end
                behind_net[ing.name] = bn
                behind_at[unit] = behind_at[unit] or {}
                behind_at[unit][ing.name] = true
              end
            end
          elseif (e.get_fluid_count(ing.name) or 0) < ing.amount then
            if behind then fmark(behind_fluid, e, ing.name) end
            fmark(short_fluid, e, ing.name)
            local a = fluid_at[unit] or {}
            a[ing.name] = true
            fluid_at[unit] = a
          end
        end
      end
    end
  end

  -- (Every ten minutes too: which belt brings a user its item is learned as the factory runs.)
  if storage.stale or tick % 36000 == 0 then
    storage.stale = false
    share_setup(T, recipes)
    loop_gates(T, recipes)
    reserve_setup(T, recipes)
  end

  -- Requester demand (setting): a robot link's requester chest holding less than its request counts as a machine
  -- waiting for the item on the link (robots take minutes to cross a large factory; a maker that ran only once the
  -- machines past the requester stood short left them waiting a trip each time).
  if setting("requester-demand") then
    for _, rq in pairs(T.reqs or {}) do
      local c = rq.e
      if c.valid and rq.net then
        if not rq.count then
          rq.count = 10
          pcall(function()
            local pt = c.get_logistic_point(defines.logistic_member_index.logistic_container)
            for _, f in pairs(pt and pt.filters or {}) do if f.name == rq.item then rq.count = f.count end end
          end)
        end
        if c.get_item_count(rq.item) < rq.count then
          local sn = short_net[rq.item] or {}
          sn[rq.net] = true
          short_net[rq.item] = sn
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
        or e.status == defines.entity_status.waiting_for_space_in_destination
        or e.status == defines.entity_status.full_burnt_result_output) then
      -- (What its fuel burns to, stuck in its burnt result slot, too: Py's basic oxygen furnace stood with its ash slot
      -- full and the ash belt's one user switched off.)
      if e.status == defines.entity_status.full_burnt_result_output then
        local burnt = e.get_burnt_result_inventory()
        for k, c in pairs(burnt and burnt.get_contents() or {}) do
          local name = type(c) == "table" and c.name or k
          local pn = push_net[name] or {}
          for n in pairs(T.drops[unit] or {}) do pn[n] = true end
          push_net[name] = pn
          for u in pairs(T.hands[unit] or {}) do
            push_at[u] = push_at[u] or {}
            push_at[u][name] = true
          end
        end
      end
      local out = e.get_output_inventory()
      local items_stuck = false
      for _, p in pairs(r.products) do
        if p.type == "item" and out and out.get_item_count(p.name) > 0 then items_stuck = true end
      end
      for _, p in pairs(r.products) do
        if p.type == "fluid" then
          if not items_stuck and (e.get_fluid_count(p.name) or 0) > 0 then fmark(push_fluid, e, p.name) end
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
    if ing.type == "fluid" then return storage.machines[unit] ~= nil and fhit(push_fluid, storage.machines[unit].e, ing.name) end
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
          if ing.type == "item" and held(e, ing.name) < ahead * ing.amount then
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

  -- Stock (setting above 0): every machine, switched off or not, keeps that many crafts' worth of each ingredient, so
  -- its makers run until it holds that much. Without it a machine switched off waits for nothing, and a long chain
  -- stays off level by level because the machine above it is off; when demand finally comes each level starts from
  -- empty, one belt trip at a time (Py production science: ten levels of uranium enrichment never ran).
  local want_fluid = short_fluid
  local stock = setting("stock")
  if stock > 0 then
    if want_net == short_net then
      want_net, want_at = {}, {}
      for item, nets in pairs(short_net) do local c = {}; for n in pairs(nets) do c[n] = true end; want_net[item] = c end
      for u, items in pairs(short_at) do local c = {}; for i in pairs(items) do c[i] = true end; want_at[u] = c end
    end
    want_fluid = {}
    for f, t in pairs(short_fluid) do local c = {}; for k, v in pairs(t) do c[k] = v end; want_fluid[f] = c end
    for unit, m in pairs(storage.machines) do
      local e, r = m.e, recipes[unit]
      if r and e.valid then
        for _, ing in pairs(r.ingredients) do
          if ing.type == "item" then
            if held(e, ing.name) < stock * ing.amount then
              local wn = want_net[ing.name] or {}
              for n in pairs(T.feeds[unit] or {}) do wn[n] = true end
              want_net[ing.name] = wn
              local a = want_at[unit] or {}
              a[ing.name] = true
              want_at[unit] = a
            end
          elseif (e.get_fluid_count(ing.name) or 0) < stock * ing.amount then
            fmark(want_fluid, e, ing.name)
          end
        end
      end
    end
  end

  -- Pull.
  if setting("pull") then
    -- Side-product cap (setting above 0): a machine wanted only for a product it was not planned for (tags: its main
    -- product, or every product the plan uses when the main one leaves as a by-product) runs no faster than that many
    -- times its plan over the last ten minutes, plus two crafts. Py production science's coal gas plant ran at nine
    -- times its plan for a trace of iron oxide (and coke), and the coke filled the robot provider it feeds and stopped it.
    local cap = setting("side-cap")
    -- Rate cap (setting above 0): no machine runs faster than that many times its plan over the last ten minutes
    -- (plus three crafts), whatever wants it, the target's makers aside. Loops and machines with several products kept
    -- each other wanted and spun far past their plan (Py production science in the check: 330 recipes above 1.5 times
    -- their plan, the uranium enrichment cascade at 20 to 33 times), taking what other chains needed.
    local rate_cap = setting("rate-cap")
    local slow = setting("slow-machines")
    for unit, m in pairs(storage.machines) do
      local r = recipes[unit]
      if m.e.valid and r then
        local wanted, known = m.target == true, m.target == true
        local capping = cap > 0 and m.purpose ~= nil and m.crafts ~= nil
        local held = false
        local main = wanted
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
            local hit = false
            if p.type == "fluid" then
              known = true
              if fhit(want_fluid, m.e, p.name) then hit = true end
            else
              local sn = want_net[p.name]
              for n in pairs(drops) do known = true; if sn and sn[n] then hit = true end end
              for u in pairs(hands) do known = true; if want_at[u] and want_at[u][p.name] then hit = true end end
            end
            if hit then
              wanted = true
              if not capping or m.purpose[p.name] then main = true; break end
            end
          end
        end
        if fill then
          local drops, hands = T.drops[unit] or {}, T.hands[unit] or {}
          for _, p in pairs(r.products) do
            if p.type == "fluid" then
              if fhit(behind_fluid, m.e, p.name) then m.fb = tick end
            else
              local bn = behind_net[p.name]
              for n in pairs(drops) do if bn and bn[n] then m.fb = tick end end
              for u in pairs(hands) do if behind_at[u] and behind_at[u][p.name] then m.fb = tick end end
            end
          end
        end
        if (capping or rate_cap > 0) and m.crafts then
          m.sc = m.sc or {}
          local h = m.sc
          -- (Slow machines, a setting: one planned for fewer than three crafts in ten minutes is capped over the
          -- time three planned crafts take, four hours at most, plus one craft. Ten minutes plus three crafts let a
          -- machine planned for two crafts an hour make twenty-one.)
          local win, slack = 600, 3
          if slow and m.crafts > 0 and m.crafts * 600 < 3 then win, slack = math.min(3 / m.crafts, 14400), 1 end
          if #h == 0 or tick - h[#h].tick >= win * 60 / 600 then h[#h + 1] = { tick = tick, pf = m.e.products_finished } end
          while #h > 1 and tick - h[1].tick > win * 60 do table.remove(h, 1) end
          local made, span = m.e.products_finished - h[1].pf, (tick - h[1].tick) / 60
          if capping and wanted and not main and made > m.crafts * span * cap + 2 then
            wanted = false
            held = true
            stats.capped = stats.capped + 1
          end
          if rate_cap > 0 and wanted and not m.target and made > m.crafts * span * rate_cap + slack then
            -- (Cap fill, a setting: unless a user that is itself behind its plan waits for one of its products. A
            -- belt has to fill once from its end back to each user; what a slow maker's first batches leave in
            -- that stretch is hours of its plan, and held to its cap it left the user without for hours. A user
            -- ahead of its own plan gets no such leave, so a hog is still bounded, by its own cap.)
            local for_behind = false
            if fill then
              local drops, hands = T.drops[unit] or {}, T.hands[unit] or {}
              for _, p in pairs(r.products) do
                if p.type == "fluid" then
                  if fhit(behind_fluid, m.e, p.name) then for_behind = true end
                else
                  local bn = behind_net[p.name]
                  for n in pairs(drops) do if bn and bn[n] then for_behind = true end end
                  for u in pairs(hands) do if behind_at[u] and behind_at[u][p.name] then for_behind = true end end
                end
              end
            end
            if not for_behind then
              wanted = false
              held = true
              stats.capped = stats.capped + 1
            end
          end
        end
        -- (A machine held to its cap stays off: a full maker pushing it would start it again.)
        if not wanted and not held then
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
    -- Loop first (setting): a loop's own users first. A user of an item whose own products come back to a maker of
    -- that item within three recipes is in the item's loop (tags.flow.loop_in); any other user of it (loop_out) is
    -- held from it while one in the loop stands short of it and took less than its plan over the last half hour.
    -- Py logistic science's scrondrix loop is planned with no margin: each animal gives 8.3 meat and takes 7.5 to
    -- breed, and the 11% over is exactly what the other users take. Whatever is lost is never grown back otherwise.
    local lf = setting("loop-first")
    local strict = setting("loop-strict")
    local loop_stock = setting("loop-stock")
    local loop_out, loop_short, out_short = {}, {}, {}
    for unit, m in pairs(storage.machines) do
      local e, r = m.e, recipes[unit]
      if e.valid and r then
        m.fq = m.fq or { t0 = tick, pf0 = e.products_finished, c0 = {}, item_of = {} }
        local secs = (tick - m.fq.t0) / 60
        local window0 = setting("quota-window") * 60   -- seconds; 0: since the machine was built
        local o = {}
        for _, ing in pairs(r.ingredients) do
          -- (A fluid held in its box counts up to two crafts' worth: a full box is no fluid taken from anyone.)
          local have = ing.type == "item" and held(e, ing.name) or math.min(e.get_fluid_count(ing.name) or 0, 2 * ing.amount)
          local c0 = m.fq.c0[ing.name]
          if c0 == nil then c0 = have; m.fq.c0[ing.name] = c0 end
          local taken = (e.products_finished - m.fq.pf0) * ing.amount + have - c0
          if lf and ing.type == "item" then
            if m.loop_out and m.loop_out[ing.name] then
              loop_out[unit] = loop_out[unit] or {}
              loop_out[unit][ing.name] = true
            elseif m.loop_in and m.loop_in[ing.name] then
              m.lf = m.lf or {}
              local h = m.lf[ing.name]
              if not h then h = { { tick = tick, taken = taken } }; m.lf[ing.name] = h end
              if tick - h[#h].tick >= 3600 then h[#h + 1] = { tick = tick, taken = taken } end
              while #h > 1 and tick - h[2].tick >= 1800 * 60 do table.remove(h, 1) end
              -- (Loop strict, a setting: whenever it stands short, however far ahead of its plan it has run. A loop
              -- that ran ahead on its starters is "ahead" for half an hour after they end, and the other users
              -- drain it meanwhile.)
              if short_at[unit] and short_at[unit][ing.name]
                  and (strict or taken - h[1].taken < (m.take[ing.name] or 0) * (tick - h[1].tick) / 60) then
                loop_short[ing.name] = true
              end
              -- (Loop stock, a setting, crafts: also while a loop user that is switched on holds less than that
              -- many crafts' worth. What the loop makes then stays in the loop until its own users are full.)
              if loop_stock > 0 and not e.disabled_by_script and held(e, ing.name) < loop_stock * ing.amount then
                loop_short[ing.name] = true
              end
            end
          end
          local span = secs
          -- (Slow machines, a setting: a user planned for fewer than three crafts in the window is counted over the
          -- time three planned crafts take, with one craft's worth over, not two: two over in half an hour let a
          -- user planned for a craft every 45 minutes take six times its plan.)
          local window, qslack = window0, 2
          if window > 0 and setting("slow-machines") and ing.amount > 0 then
            local cps = (m.take[ing.name] or 0) / ing.amount
            if cps > 0 and cps * window < 3 then window, qslack = math.min(3 / cps, 14400), 1 end
          end
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
          if taken > (m.take[ing.name] or 0) * span * factor + ing.amount * qslack then
            o[ing.name] = true
          elseif (short_at[unit] and short_at[unit][ing.name]) or (fluid_at[unit] and fluid_at[unit][ing.name]) then
            -- (A user outside the loop counts only while the loop is not short of the item itself: held by that
            -- rule it stands short, and counted then it had the quotas hold the loop's own users in turn.)
            if (loop_out[unit] or {})[ing.name] then out_short[ing.name] = true else behind[ing.name] = true end
          end
        end
        over[unit] = o
      end
    end
    for item in pairs(out_short) do if not loop_short[item] then behind[item] = true end end
    capped_at = {}
    for unit, m in pairs(storage.machines) do
      local e, r, o = m.e, recipes[unit], over[unit]
      if e.valid and r and o then
        local capped, stop = {}, false
        for _, ing in pairs(r.ingredients) do
          -- (Not what a full maker pushes to it: taking that stops no one behind.)
          if o[ing.name] and behind[ing.name] and not pushed(unit, ing) then
            if ing.type == "fluid" then stop = true else capped[ing.name] = true end
          end
          if loop_short[ing.name] and (loop_out[unit] or {})[ing.name] and not pushed(unit, ing) then
            capped[ing.name] = true
            stats.loop_held = (stats.loop_held or 0) + 1
          end
        end
        if next(capped) then capped_at[unit] = capped end
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
        local holding = {}
        for _, i in pairs(T.into[unit] or {}) do
          if i.valid then
            local h = i.held_stack
            if h and h.valid_for_read then m.fq.item_of[i.unit_number] = h.name end
            local it = m.fq.item_of[i.unit_number]
            local net = T.ins_net[i.unit_number]
            -- (Which belt network has brought which item into this machine: where the share rule counts it.)
            if it and net then
              m.brought = m.brought or {}
              m.brought[it] = m.brought[it] or {}
              m.brought[it][net] = true
            end
            -- (Never one taking from its own return loop, a belt it drops onto: held, its returns could not come back.)
            local own_loop = net ~= nil and drops[net] ~= nil and drops[net] ~= false
            if it ~= nil and not own_loop and (capped[it] or (returned[it] and net ~= nil)) then
              holding[i.unit_number] = i
              stats.held = stats.held + 1
              if not i.disabled_by_script then i.disabled_by_script = true end
            end
          end
        end
        for n, i in pairs(m.held or {}) do
          if not holding[n] and i.valid then i.disabled_by_script = false end
        end
        m.held = holding
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

  -- Gates. An outlet opens for a maker standing full only when it makes something besides what the belt carries
  -- (another product, or its fuel's ash): standing full it would stop that too. One making only that just has no more
  -- users and should stop; with the outlets open for it, it ran on and took the inputs others waited for (Py
  -- production science in the in-game check: 25/s of nickel plate, coke, sodium hydroxide and more into the blocks'
  -- by-product chests while the factory's own users stood short).
  local function full_other(unit)
    local mm = storage.machines[unit]
    if not (mm and mm.e.valid and not mm.e.disabled_by_script and FULL[mm.e.status]) then return false end
    if mm.e.status == defines.entity_status.full_burnt_result_output then return true end
    local r = recipes[unit]
    local n = 0
    for _ in pairs(r and r.products or {}) do n = n + 1 end
    return r == nil or n > 1
  end
  if setting("gates") then
    -- By-product chests: their inserter works only while such a maker on its belt stands full and no machine there
    -- waits for the item.
    for _, g in pairs(T.sinks or {}) do
      if g.e.valid then
        local open = false
        for _, unit in pairs(g.makers) do if full_other(unit) then open = true; break end end
        if short_net[g.item] and short_net[g.item][g.net] then open = false end
        if g.e.disabled_by_script ~= not open then g.e.disabled_by_script = not open end
      end
    end
    for _, g in pairs(T.gates) do
      if g.e.valid then
        stats.gates = stats.gates + 1
        local close = true
        for _, unit in pairs(g.makers) do
          if full_other(unit) then close = false; break end
        end
        if short_net[g.item] and short_net[g.item][g.net] then close = true end
        if g.loop then close = true end
        if close then stats.gates_closed = stats.gates_closed + 1 end
        local a = g.anchor
        if a and a.anchor.valid and close ~= a.closed then
          a.closed = close
          pcall(function() g.e.get_or_create_control_behavior().circuit_enable_disable = close end)
        end
      end
    end
  end
  -- Shares (setting): demand first, the plan's shares when nobody asks. An inserter shares out what a machine makes
  -- by turns, whatever the plan gave each belt: Py logistic science's water-barrel filler has two belts, planned 94%
  -- and 6% of its barrels, and half went to each (1,479 lying on the small one while the animals on the big one
  -- stood short); its renderer's meat went onto the belts of two users planned 0.9% of it while the breeders ran dry.
  --   a network with a user waiting for the item (short of it, and not held from it by a quota) takes it; among
  --     those, one ahead of its share is held while another that waits is behind its share and free;
  --   a network nobody waits on is held while a waiting one is free to take, and otherwise while it is ahead of its
  --     share and one behind its share is free.
  -- A balance per network: its share of everything carried since the last look, less what it carried. Behind is a
  -- balance above zero, ahead one below minus the slack (16, an inserter's hand); it never rises above four
  -- times the slack, so a belt that was blocked for hours does not claim everything afterwards. Free: one of its
  -- inserters has not held an item for five seconds. It reads nothing but its own inserters and its users' waiting.
  if setting("shares") then
    for _, g in pairs(storage.os or {}) do
      local d = 0
      for _, t in pairs(g.nets) do d = d + t.sum - t.seen end
      for _, t in pairs(g.nets) do
        t.bal = math.min(t.bal + t.share * d - (t.sum - t.seen), 4 * g.slack)
        t.seen = t.sum
      end
      local wait_free, wait_behind_free, behind_free = false, false, false
      for _, t in pairs(g.nets) do
        t.free = false
        for _, i in pairs(t.ins) do
          if i.e.valid and not (i.since and tick - i.since >= 300) then t.free = true end
        end
        t.wait = false
        for _, u in pairs(t.users) do
          if (short_at[u] or {})[g.item] and not (capped_at[u] or {})[g.item] then t.wait = true; break end
        end
        t.behind = t.bal > 0
        t.ahead = t.bal < -g.slack
        if t.free and t.wait then wait_free = true; if t.behind then wait_behind_free = true end end
        if t.free and t.behind then behind_free = true end
      end
      for _, t in pairs(g.nets) do
        local hold
        if t.wait then hold = t.ahead and wait_behind_free
        else hold = wait_free or (t.ahead and behind_free) end
        if hold then stats.share_held = stats.share_held + 1 end
        if hold ~= (t.hold == true) then
          t.hold = hold
          for _, i in pairs(t.ins) do if i.e.valid then i.e.disabled_by_script = hold end end
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

-- Reserve gates: shut until a lane of the tile back up the belt takes nothing more.
script.on_nth_tick(6, function()
  if not storage.rs or not setting("enabled") then return end
  for _, g in pairs(storage.rs) do
    if g.belt.valid and g.sensor.valid then
      local full = false
      for k = 1, g.sensor.get_max_transport_line_index() do
        local line = g.sensor.get_transport_line(k)
        if line.get_item_count() > 0 and not line.can_insert_at_back() then full = true end
      end
      if (not full) ~= g.closed then
        g.closed = not full
        pcall(function() g.belt.get_or_create_control_behavior().circuit_enable_disable = g.closed end)
      end
    end
  end
end)

-- Shares: what each network's inserters carried, from their hands.
script.on_nth_tick(4, function()
  if not storage.os or not setting("enabled") or not setting("shares") then return end
  local tick = game.tick
  for _, g in pairs(storage.os) do
    for _, t in pairs(g.nets) do
      for _, i in pairs(t.ins) do
        if i.e.valid then
          local h = i.e.held_stack
          local c = h.valid_for_read and h.name == g.item and h.count or 0
          if c > i.had then t.sum = t.sum + (c - i.had) end
          if c > 0 then i.since = i.since or tick else i.since = nil end
          i.had = c
        end
      end
    end
  end
end)

-- /factory-flow: what the mod is doing.
commands.add_command("factory-flow", "Factory flow: machines controlled, switched off, inserters held, gates closed.", function(c)
  local s = storage.stats or {}
  local text = string.format("factory-flow: %d machines, %d switched off or stopped (%d on fluids), %d inserters held (%d for a loop's own users), %d of %d gates closed, %d capped, %d belts held to their share",
    s.machines or 0, s.off or 0, s.stopped or 0, s.held or 0, s.loop_held or 0, s.gates_closed or 0, s.gates or 0, s.capped or 0, s.share_held or 0)
  if c.player_index then game.get_player(c.player_index).print(text) else rcon.print(text) end
end)

remote.add_interface("factory-flow", {
  status = function() return storage.stats end,
  -- Sets one of the mod's own map settings (only the mod that owns a setting may write it): the in-game check's way
  -- of running the mod with the options it was given.
  set = function(name, value) settings.global[name] = { value = value }; return settings.global[name].value end,
  -- What the share rule sees: per machine and item, each belt network's planned share, what it carried, its balance.
  shares = function()
    local out = {}
    for key, g in pairs(storage.os or {}) do
      local nets = {}
      for id, t in pairs(g.nets) do
        nets[#nets + 1] = { net = id, share = t.share, sum = t.sum, bal = t.bal, hold = t.hold == true, wait = t.wait == true, free = t.free ~= false, inserters = #t.ins, users = #t.users }
      end
      out[#out + 1] = { key = key, item = g.item, nets = nets }
    end
    return out
  end,
  -- Counts of what the mod knows: machines, those still without a recipe, loop users, gates kept shut for a loop.
  info = function()
    local o = { machines = 0, unknown = 0, loop_in = 0, loop_out = 0, gates = 0, loop_gates = 0, groups = 0, reserve_gates = 0, reserve_shut = 0 }
    for _, g in pairs(storage.rs or {}) do o.reserve_gates = o.reserve_gates + 1; if g.closed then o.reserve_shut = o.reserve_shut + 1 end end
    for _, m in pairs(storage.machines) do
      o.machines = o.machines + 1
      if m.cands then o.unknown = o.unknown + 1 end
      if m.loop_in and next(m.loop_in) then o.loop_in = o.loop_in + 1 end
      if m.loop_out and next(m.loop_out) then o.loop_out = o.loop_out + 1 end
    end
    for _, g in pairs((storage.topo or {}).gates or {}) do o.gates = o.gates + 1; if g.loop then o.loop_gates = o.loop_gates + 1 end end
    for _ in pairs(storage.os or {}) do o.groups = o.groups + 1 end
    return o
  end,
})
