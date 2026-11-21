local ok, err = pcall(function()
  local run = storage.run
  local lfk = run.params.loop_first
  local pc = run.params.plan_crafts or {}
  local out = {}
  local nshort, nall = 0, 0
  for _, m in pairs(run.machines) do
    local e = m.e
    local r = e.valid and (e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe]))
    if r then
      for _, ing in pairs(r.ingredients) do
        if ing.type == "item" and (lfk.inside[ing.name] or {})[r.name] then
          local inv = e.get_inventory(defines.inventory.crafter_input)
          local h = inv and inv.get_item_count(ing.name) or 0
          local want = (lfk.keep_time or 0) * (pc[r.name] or 0) * ing.amount
          local front, queue = 0, false
          for _, b in pairs((run.lk_pick or {})[e.unit_number] or {}) do
            if b.valid then
              local n = 0
              for li = 1, b.get_max_transport_line_index() do n = n + b.get_transport_line(li).get_item_count(ing.name) end
              front = front + n
              if n >= 4 then queue = true end
            end
          end
          local short = h < (lfk.keep or 0) * ing.amount or (h < want and not queue and h + front < want)
          nall = nall + 1
          if short then nshort = nshort + 1 end
          out[#out + 1] = string.format("%s %s held %d front %d want %.0f %s%s", r.name, ing.name, h, front, want, queue and "queue " or "", short and "SHORT" or "")
        end
      end
    end
  end
  rcon.print(string.format("h %.2f  loop users short %d of %d", (game.tick - run.t0) / 216000, nshort, nall))
  for _, l in pairs(out) do rcon.print(l) end
end)
if not ok then rcon.print("ERR " .. tostring(err)) end