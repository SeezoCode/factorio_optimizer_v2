local ok, err = pcall(function()
  local run = storage.run
  local names = {}
  for k, v in pairs(defines.entity_status) do names[v] = k end
  local W = { ["breed-fish-2-agressive-selection"] = 1, ["breed-fish-egg-2"] = 1, ["xyhiphoe-cub-1"] = 1, ["xyhiphoe-single"] = 1, ["korlex-milk-1-pressured"] = 1, ["empty-milk-barrel"] = 1, ["zipir-eggs-1"] = 1 }
  rcon.print(string.format("h %.2f", (game.tick - run.t0) / 216000))
  for _, m in pairs(run.machines) do
    local e = m.e
    local r = e.valid and (e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe]))
    if r and W[r.name] then
      local function inv(i)
        local t = {}
        local v = e.get_inventory(i)
        if v then for _, c in pairs(v.get_contents()) do t[#t + 1] = c.name .. "=" .. c.count end end
        return table.concat(t, " ")
      end
      local fl = {}
      for k = 1, (e.fluids_count or 0) do local f = e.get_fluid(k); if f then fl[#fl + 1] = f.name .. "=" .. math.floor(f.amount) end end
      rcon.print(string.format("%s @%d,%d %s off=%s pf=%d in[%s] out[%s] fl[%s]", r.name, e.position.x, e.position.y, names[e.status] or "?", tostring(e.disabled_by_script), e.products_finished, inv(defines.inventory.crafter_input), inv(defines.inventory.crafter_output), table.concat(fl, " ")))
      -- inserters taking from it
      for _, i in pairs(e.surface.find_entities_filtered { area = { { e.position.x - 5, e.position.y - 5 }, { e.position.x + 5, e.position.y + 5 } }, type = "inserter" }) do
        if i.pickup_target == e then
          local d = i.drop_target
          local h = i.held_stack
          rcon.print(string.format("   out-ins @%.1f,%.1f off=%s active=%s held=%s -> %s", i.position.x, i.position.y, tostring(i.disabled_by_script), tostring(i.active), (h and h.valid_for_read) and (h.name .. "x" .. h.count) or "-", d and (d.name .. (d.type == "transport-belt" and (" belt items " .. d.get_transport_line(1).get_item_count() + d.get_transport_line(2).get_item_count()) or "")) or "nil"))
        end
      end
    end
  end
end)
if not ok then rcon.print("ERR " .. tostring(err)) end