local run = storage.run
local lf = run.params.loop_first
local o = { "why " .. serpent.line(run.rs and run.rs.why), "skipped " .. tostring(run.rs and run.rs.skipped) }
o[#o + 1] = "fertilizer inside " .. serpent.line(lf.inside["fertilizer"]) .. " outside " .. serpent.line(lf.outside["fertilizer"])
for _, m in pairs(run.machines) do
  if m.e.valid and m.e.get_recipe() and m.e.get_recipe().name == "sap-tree-mk04-gen" then
    o[#o + 1] = "gen brought fertilizer " .. serpent.line(((run.brought or {})[m.e.unit_number] or {})["fertilizer"]) .. " feeds " .. serpent.line((run.feeds or {})[m.e.unit_number])
  end
end
o[#o + 1] = "makes_on fertilizer " .. serpent.line((run.makes_on or {})["fertilizer"])
rcon.print(table.concat(o, "\n"))