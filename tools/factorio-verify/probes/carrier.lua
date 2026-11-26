local ITEM = "water-barrel"
local MIN = 15
local ok, err = pcall(function()
  local run = storage.run
  local count, tiles = {}, {}
  for _, b in pairs(run.belts) do
    local e = b.e
    if e.valid then
      local n = 0
      for li = 1, e.get_max_transport_line_index() do n = n + e.get_transport_line(li).get_item_count(ITEM) end
      local net = run.net_of[e.unit_number]
      if net and n > 0 then
        count[net] = (count[net] or 0) + n
        tiles[net] = (tiles[net] or 0) + 1
      end
    end
  end
  local out = { string.format("h %.2f %s", (game.tick - run.t0) / 216000, ITEM) }
  local drops, takes = {}, {}
  for _, i in pairs(run.inserters) do
    local ie = i.e
    if ie.valid then
      local pk, d = ie.pickup_target, ie.drop_target
      local pn = pk and pk.unit_number and run.net_of[pk.unit_number]
      local dn = d and d.unit_number and run.net_of[d.unit_number]
      if dn and pk and pk.type ~= "transport-belt" and pk.get_recipe then
        local r = pk.get_recipe()
        local mk = false
        for _, p in pairs(r and r.products or {}) do if p.name == ITEM then mk = true end end
        if mk then drops[dn] = (drops[dn] or "") .. " " .. r.name .. "@" .. math.floor(pk.position.x) .. "," .. math.floor(pk.position.y) end
      end
      if pn and d and d.type ~= "transport-belt" and d.get_recipe then
        local r = d.get_recipe()
        local us = false
        for _, g in pairs(r and r.ingredients or {}) do if g.name == ITEM then us = true end end
        if us then
          local n = 0
          for li = 1, pk.get_max_transport_line_index() do n = n + pk.get_transport_line(li).get_item_count(ITEM) end
          local have = d.get_item_count(ITEM)
          takes[pn] = (takes[pn] or "") .. string.format(" %s@%d,%d[tile %d, holds %d%s]", r.name, d.position.x, d.position.y, n, have, d.disabled_by_script and " off" or "")
        end
      end
    end
  end
  local nets = {}
  for n in pairs(count) do nets[#nets + 1] = n end
  for n in pairs(takes) do if not count[n] then nets[#nets + 1] = n; count[n] = 0 end end
  table.sort(nets, function(a, b) return count[a] > count[b] end)
  for _, n in pairs(nets) do
    if count[n] >= MIN or takes[n] then
      out[#out + 1] = string.format("net %s: %d on %d tiles | made by%s | taken by%s", n, count[n], tiles[n] or 0, drops[n] or " -", takes[n] or " -")
    end
  end
  rcon.print(table.concat(out, "\n"))
end)
if not ok then rcon.print("ERR " .. tostring(err)) end
