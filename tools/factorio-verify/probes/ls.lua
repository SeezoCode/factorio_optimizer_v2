local ok, err = pcall(function()
  local run = storage.run
  local names = {}
  for k, v in pairs(defines.entity_status) do names[v] = k end
  local XS = { "seaweed", "fertilizer", "fish", "fish-egg", "filtration-media", "sea-sponge", "biomass", "ash", "coarse", "logistic-science-pack", "py-science-pack-2", "meat", "scrondrix", "caged-scrondrix", "bones", "bonemeal", "cage", "barrel", "water-barrel", "natural-gas-barrel", "arqad-honey-barrel", "geothermal-water-barrel", "saps", "saps-mk04", "sap-seeds-mk04", "sap-tree-mk04", "fish-food-01", "korlex-food-01", "barrel-milk", "empty-barrel-milk" }
  local want = {}
  for _, x in pairs(XS) do if prototypes.item[x] then want[x] = true end end
  local out = { h = (game.tick - run.t0) / 216000, nets = {}, m = {} }
  for _, b in pairs(run.belts) do
    if b.e.valid then
      for i = 1, b.e.get_max_transport_line_index() do
        for _, c in pairs(b.e.get_transport_line(i).get_contents()) do
          if want[c.name] then
            local net = tostring(run.net_of[b.e.unit_number] or 0)
            out.nets[c.name] = out.nets[c.name] or {}
            out.nets[c.name][net] = (out.nets[c.name][net] or 0) + c.count
          end
        end
      end
    end
  end
  for _, m in pairs(run.machines) do
    local e = m.e
    local r = e.valid and (e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe]))
    if r then
      local rel = {}
      for _, i in pairs(r.ingredients) do if want[i.name] then rel[#rel + 1] = i.name .. "<" .. i.amount .. "=" .. e.get_item_count(i.name) end end
      for _, p in pairs(r.products) do if want[p.name] then rel[#rel + 1] = p.name .. ">" .. e.get_item_count(p.name) end end
      if #rel > 0 then
        out.m[#out.m + 1] = { r.name .. "@" .. math.floor(e.position.x) .. "," .. math.floor(e.position.y), names[e.status], e.products_finished, table.concat(rel, " ") }
      end
    end
  end
  rcon.print(helpers.table_to_json(out))
end)
if not ok then rcon.print("ERR " .. tostring(err)) end
