local ok, err = pcall(function()
  local run = storage.run
  local NET = 44976
  local dirs = { [0] = "N", [4] = "E", [8] = "S", [12] = "W" }
  local belts = {}
  for _, b in pairs(run.belts) do
    if b.e.valid and run.net_of[b.e.unit_number] == NET then belts[#belts + 1] = b.e end
  end
  rcon.print("belts " .. #belts)
  local on = {}
  for _, e in pairs(belts) do
    local c = {}
    for li = 1, e.get_max_transport_line_index() do for _, x in pairs(e.get_transport_line(li).get_contents()) do c[#c + 1] = x.name .. "=" .. x.count end end
    on[e.unit_number] = true
    rcon.print(string.format("  %s @%d,%d %s [%s]", e.type, math.floor(e.position.x), math.floor(e.position.y), dirs[e.direction] or e.direction, table.concat(c, " ")))
  end
  for _, i in pairs(run.inserters) do
    local ie = i.e
    if ie.valid then
      local pk, d = ie.pickup_target, ie.drop_target
      if pk and pk.unit_number and on[pk.unit_number] then
        local r = d and d.type ~= "container" and d.get_recipe and d.get_recipe()
        rcon.print(string.format("  TAKE @%.1f,%.1f -> %s %s off=%s", ie.position.x, ie.position.y, d and d.name or "nil", r and r.name or "", tostring(ie.disabled_by_script)))
      end
      if d and d.unit_number and on[d.unit_number] then
        local r = pk and pk.type ~= "container" and pk.get_recipe and pk.get_recipe()
        rcon.print(string.format("  DROP @%.1f,%.1f <- %s %s", ie.position.x, ie.position.y, pk and pk.name or "nil", r and r.name or ""))
      end
    end
  end
end)
if not ok then rcon.print("ERR " .. tostring(err)) end