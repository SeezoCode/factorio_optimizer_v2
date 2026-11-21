local ok, err = pcall(function()
  local run = storage.run
  local pf = {}
  for _, m in pairs(run.machines) do
    local e = m.e
    local r = e.valid and (e.get_recipe() or (m.plan_recipe and prototypes.recipe[m.plan_recipe]))
    if r then pf[r.name] = (pf[r.name] or 0) + e.products_finished end
  end
  local rv = {}
  for _, b in pairs(run.real_voids and run.real_voids.built or {}) do if b.e.valid then rv[b.fluid] = (rv[b.fluid] or 0) + b.e.products_finished * b.amount end end
  rcon.print(helpers.table_to_json({ h = (game.tick - run.t0) / 216000, phase = run.phase, tl = run.timeline, now = { pf = pf, seeded = run.seeded, voided = run.relieved or run.voided, boot = run.bootstrapped }, rv = rv, rx = run.rx, bh = run.bh }))
end)
if not ok then rcon.print("ERR " .. tostring(err)) end