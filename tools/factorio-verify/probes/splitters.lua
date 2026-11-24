-- splitters.lua: every splitter, the items on it, and the machines (recipes) taking from each output side, followed
-- down the belt through undergrounds and further splitters (on the factory's surface). Run: lua.py <run> splitters.lua.
local ok, err = pcall(function()
  local sf; for _, m in pairs(storage.run.machines) do if m.e.valid then sf = m.e.surface; break end end
  local out = {}
  local function users_from(start, seen, acc)
    local todo = { start }
    while #todo > 0 do
      local b = table.remove(todo)
      if b and b.valid and not seen[b.unit_number] then
        seen[b.unit_number] = true
        local bb = b.bounding_box
        for _, i in pairs(sf.find_entities_filtered { area = { { bb.left_top.x - 3, bb.left_top.y - 3 }, { bb.right_bottom.x + 3, bb.right_bottom.y + 3 } }, type = "inserter" }) do
          if i.pickup_target == b then
            local t = i.drop_target
            local r = t and t.valid and t.type == "assembling-machine" and t.get_recipe()
            acc[r and r.name or (t and t.name or "?")] = (acc[r and r.name or (t and t.name or "?")] or 0) + 1
          end
        end
        for _, n in pairs(b.belt_neighbours.outputs) do todo[#todo + 1] = n end
        if b.type == "underground-belt" and b.belt_to_ground_type == "input" and b.underground_belt_neighbour then todo[#todo + 1] = b.underground_belt_neighbour end
      end
    end
  end
  for _, s in pairs(sf.find_entities_filtered { type = "splitter" }) do
    local items = {}
    for i = 1, s.get_max_transport_line_index() do
      for _, c in pairs(s.get_transport_line(i).get_contents()) do items[c.name] = (items[c.name] or 0) + c.count end
    end
    local outs = s.belt_neighbours.outputs
    local ins = s.belt_neighbours.inputs
    local sides = {}
    for k, o in pairs(outs) do
      local acc = {}
      users_from(o, { [s.unit_number] = true }, acc)
      local l = {}
      for r, n in pairs(acc) do l[#l + 1] = r .. "x" .. n end
      sides[#sides + 1] = "[" .. table.concat(l, " ") .. "]"
    end
    local il = {}
    for n, c in pairs(items) do il[#il + 1] = n .. "=" .. c end
    out[#out + 1] = string.format("%s @%.1f,%.1f in %d out %d prio in=%s out=%s items {%s} sides %s", s.name, s.position.x, s.position.y,
      #ins, #outs, s.splitter_input_priority, s.splitter_output_priority, table.concat(il, " "), table.concat(sides, " | "))
  end
  rcon.print(table.concat(out, "\n"))
end)
if not ok then rcon.print("ERR " .. tostring(err)) end
