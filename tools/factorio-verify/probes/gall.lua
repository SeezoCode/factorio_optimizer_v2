local ok, err = pcall(function()
  local run = storage.run
  local h = (game.tick - run.t0) / 216000
  local n, shut, long = 0, 0, {}
  for _, g in pairs((run.rs or {}).gates or {}) do
    if g.e.valid and not g.dead then
      n = n + 1
      if g.closed then shut = shut + 1 end
      if g.shut_s > h * 3600 * 0.9 then
        local pf = g.pbe and g.pbe.valid and g.pbe or nil
        local c = 0
        if g.s.valid then for k = 1, g.s.get_max_transport_line_index() do c = c + g.s.get_transport_line(k).get_item_count() end end
        long[#long + 1] = g.key .. " shut " .. math.floor(g.shut_s / 60) .. "min sensor items " .. c
      end
    end
  end
  rcon.print(string.format("h %.2f gates %d shut now %d; shut 90%% of the run or more: %d", h, n, shut, #long))
  for _, l in pairs(long) do rcon.print("  " .. l) end
end)
if not ok then rcon.print("ERR " .. tostring(err)) end