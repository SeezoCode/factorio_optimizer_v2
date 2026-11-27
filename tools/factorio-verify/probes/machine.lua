-- machine.lua: one recipe's machines: status, input and output contents, and every inserter into or out of them with
-- the belt tile it uses (contents, circuit enable state). Edit RECIPE.
local RECIPE = "Moss-4-without-sludge"
local out = {}
for _, m in pairs(storage.run.machines) do
  local e = m.e
  if e.valid and e.get_recipe() and e.get_recipe().name == RECIPE then
    local inp, outp = {}, {}
    for _, it in pairs(e.get_inventory(defines.inventory.crafter_input).get_contents()) do inp[#inp + 1] = it.name .. "=" .. it.count end
    for _, it in pairs(e.get_inventory(defines.inventory.crafter_output).get_contents()) do outp[#outp + 1] = it.name .. "=" .. it.count end
    out[#out + 1] = string.format("%s at %d,%d status %s in [%s] out [%s] crafts %d", RECIPE, e.position.x, e.position.y, tostring(e.status), table.concat(inp, " "), table.concat(outp, " "), e.products_finished)
    for _, i in pairs(e.surface.find_entities_filtered { type = "inserter", area = { { e.position.x - 4, e.position.y - 4 }, { e.position.x + 4, e.position.y + 4 } } }) do
      local into, from = i.drop_target == e, i.pickup_target == e
      if into or from then
        local b = into and i.pickup_target or i.drop_target
        local desc = "-"
        if b and b.valid and b.type:find("belt") then
          local c = {}
          for k = 1, b.get_max_transport_line_index() do for _, it in pairs(b.get_transport_line(k).get_contents()) do c[#c + 1] = it.name .. "=" .. it.count end end
          local cb = b.get_control_behavior()
          desc = string.format("%s at %d,%d [%s] enable_cond %s", b.name, b.position.x, b.position.y, table.concat(c, " "), cb and tostring(cb.circuit_enable_disable) .. (cb.disabled and " DISABLED" or "") or "none")
        elseif b and b.valid then desc = b.name end
        local h = i.held_stack
        out[#out + 1] = string.format("  %s %s %s hand %s filt %s | %s", into and "INTO" or "OUT ", i.name, tostring(i.status), h.valid_for_read and (h.name .. "x" .. h.count) or "-", i.get_filter(1) or "-", desc)
      end
    end
  end
end
rcon.print(table.concat(out, "\n"))
