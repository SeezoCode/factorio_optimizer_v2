local s
for _, sf in pairs(game.surfaces) do if sf.name == "verify" then s = sf end end
local names = {}
for k, v in pairs(defines.entity_status) do names[v] = k end
local o = {}
for _, e in pairs(s.find_entities_filtered { type = { "assembling-machine", "furnace" } }) do
  local v = e.prototype.vector_to_place_result
  if v then
    local d = e.drop_position
    local t = s.find_entities_filtered { position = d, limit = 3 }
    local n = {}
    for _, x in pairs(t) do n[#n + 1] = x.name == "item-on-ground" and ("ground:" .. x.stack.name) or x.name end
    local out = {}
    local inv = e.get_output_inventory()
    if inv then for _, c in pairs(inv.get_contents()) do out[#out + 1] = c.name .. "=" .. c.count end end
    o[#o + 1] = string.format("%s %s pf=%d %s drop-> %s out[%s]", e.name, tostring(e.get_recipe() and e.get_recipe().name), e.products_finished, names[e.status] or "?", table.concat(n, ","), table.concat(out, " "))
  end
end
rcon.print(table.concat(o, "\n"))