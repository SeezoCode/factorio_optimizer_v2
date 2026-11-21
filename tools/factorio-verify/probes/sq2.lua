local ok, err = pcall(function()
  local run = storage.run
  local function lanes(b)
    if not (b and b.valid) then return "?" end
    local t = {}
    for k = 1, b.get_max_transport_line_index() do local l = b.get_transport_line(k); t[#t + 1] = l.get_item_count() .. (l.can_insert_at_back() and "" or "F") end
    return math.floor(b.position.x) .. "," .. math.floor(b.position.y) .. " " .. table.concat(t, "/")
  end
  for _, g in pairs(run.rs.gates) do
    if g.e.valid and not g.dead then
      rcon.print(string.format("%s gate %s | sensor %s | pick %s closed=%s shut %dmin pick_full=%s open_until=%s", g.key, lanes(g.e), lanes(g.s), lanes(g.pbe), tostring(g.closed), math.floor(g.shut_s / 60), tostring(g.pick_full and math.floor((game.tick - g.pick_full) / 60)), tostring(g.open_until and math.floor((g.open_until - game.tick) / 60))))
    end
  end
end)
if not ok then rcon.print("ERR " .. tostring(err)) end