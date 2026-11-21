local ok, err = pcall(function()
  local run = storage.run
  for _, m in pairs(run.machines) do
    local e = m.e
    local r = e.valid and e.get_recipe()
    if r and r.name == "breed-fish-2-agressive-selection" then
      local bb = e.bounding_box
      rcon.print(string.format("breeder @%.1f,%.1f box %.1f..%.1f x %.1f..%.1f", e.position.x, e.position.y, bb.left_top.x, bb.right_bottom.x, bb.left_top.y, bb.right_bottom.y))
      for _, i in pairs(e.surface.find_entities_filtered { area = { { bb.left_top.x - 3, bb.left_top.y - 3 }, { bb.right_bottom.x + 3, bb.right_bottom.y + 3 } }, type = "inserter" }) do
        local pk, d = i.pickup_target, i.drop_target
        if pk == e or d == e then
          local h = i.held_stack
          local f = {}
          if i.filter_slot_count > 0 then for k = 1, i.filter_slot_count do local x = i.get_filter(k); if x then f[#f + 1] = (type(x) == "table" and (x.name or "?") or tostring(x)) end end end
          local function desc(t)
            if not t then return "nil" end
            local s = t.name .. "@" .. math.floor(t.position.x) .. "," .. math.floor(t.position.y)
            if t.type == "transport-belt" or t.type == "underground-belt" then
              local c = {}
              for li = 1, 2 do for _, x in pairs(t.get_transport_line(li).get_contents()) do c[#c + 1] = x.name .. "=" .. x.count end end
              s = s .. " [" .. table.concat(c, " ") .. "] net " .. tostring((run.net_of or {})[t.unit_number])
            elseif t.type == "container" then
              s = s .. " [" .. #t.get_inventory(defines.inventory.chest).get_contents() .. " kinds, free " .. t.get_inventory(defines.inventory.chest).count_empty_stacks() .. "]"
            end
            return s
          end
          rcon.print(string.format("  %s %s @%.1f,%.1f off=%s held=%s filters=%s use_filters=%s  from %s -> %s", pk == e and "OUT" or "IN ", i.name, i.position.x, i.position.y, tostring(i.disabled_by_script), (h and h.valid_for_read) and (h.name .. "x" .. h.count) or "-", table.concat(f, ","), tostring(i.use_filters), desc(pk), desc(d)))
        end
      end
    end
  end
end)
if not ok then rcon.print("ERR " .. tostring(err)) end