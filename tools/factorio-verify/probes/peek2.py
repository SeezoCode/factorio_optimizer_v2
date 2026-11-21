"""peek2.py <run folder name> <plan> : recipes behind their plan in a running check, what they lack, and whether the makers of that kept to plan."""
import subprocess, sys, re, json, collections
import importlib.util
args = sys.argv[1:]; sys.argv = ['verify.py']
spec = importlib.util.spec_from_file_location('verify', 'tools/factorio-verify/verify.py'); v = importlib.util.module_from_spec(spec); spec.loader.exec_module(v)
want, planf = args[0], args[1]
ps = subprocess.run(['ps', '-axo', 'pid,ppid,command'], capture_output=True, text=True).stdout.splitlines()
procs = {}
for l in ps:
    m = re.match(r'\s*(\d+)\s+(\d+)\s+(.*)', l)
    if m: procs[int(m.group(1))] = (int(m.group(2)), m.group(3))
target = None
for pid, (ppid, cmd) in procs.items():
    if '--rcon-bind' in cmd and ppid in procs and ('solves/' + want) in procs[ppid][1]:
        target = (int(re.search(r'--rcon-bind 127\.0\.0\.1:(\d+)', cmd).group(1)), re.search(r'--rcon-password (\S+)', cmd).group(1))
assert target, 'no running check for ' + want
LUA = r'''
local run = storage.run
local net = run.surface.find_logistic_network_by_position(run.machines[1].e.position, game.forces.player)
local rec = {}
for _, m in pairs(run.machines) do
  local e = m.e
  if e.valid then
    local r = e.get_recipe()
    if r then
      local t = rec[r.name] or { pf = 0, n = 0, st = {}, lack = {}, out = {} }
      rec[r.name] = t
      t.pf = t.pf + e.products_finished; t.n = t.n + 1
      local key = "other"
      for k, v in pairs(defines.entity_status) do if v == e.status then key = k end end
      t.st[key] = (t.st[key] or 0) + 1
      for _, ing in pairs(r.ingredients) do
        local have = 0
        if ing.type == "item" then have = e.get_item_count(ing.name) else for k = 1, e.fluids_count do local f = e.get_fluid(k); if f and f.name == ing.name then have = have + f.amount end end end
        if have < ing.amount then t.lack[(ing.type == "fluid" and "[" or "") .. ing.name .. (ing.type == "fluid" and "]" or "")] = math.floor(have) end
      end
      local o = e.get_output_inventory()
      if o then for _, c in pairs(o.get_contents()) do t.out[c.name] = (t.out[c.name] or 0) + c.count end end
    end
  end
end
local stock = {}
if net then for _, c in pairs(net.get_contents()) do stock[c.name] = (stock[c.name] or 0) + c.count end end
local fst = {}
for k, byt in pairs(run.fstore or {}) do local n = 0; for _, a in pairs(byt) do n = n + a end; fst[k] = math.floor(n) end
rcon.print(helpers.table_to_json({ tick = game.tick - run.t0, phase = run.phase, rec = rec, stock = stock, fstock = fst, seeded = run.seeded, voided = run.voided, kept = run.void_kept }))
'''
r = v.Rcon(target[0], target[1])
res = r.command('/c __factorio-optimizer-verify__ ' + ' '.join(LUA.split('\n')))
r.close()
try: d = json.loads(res)
except Exception: print('reply:', repr(res[:2000])); sys.exit(1)
if len(args) > 2: json.dump(d, open(args[2], 'w'))
plan = json.load(open(planf)); R = json.load(open('data/pyanodon.json'))['recipes']
def base(n): return n.split('#')[0].split('@')[0]
rate = collections.defaultdict(float)
for m in plan['machines']: rate[base(m['recipe'])] += m['crafts']
T = d['tick'] / 60.0
frac = {k: (t['pf'] / (rate[k] * T) if rate.get(k) else None) for k, t in d['rec'].items()}
makers = collections.defaultdict(list)
for k in d['rec']:
    for p in R[k].get('products', []): makers[('[' + p['name'] + ']') if p['type'] == 'fluid' else p['name']].append(k)
print(want, 'hour %.1f' % (T / 3600), d['phase'], '; recipes under 85%% of plan so far: %d of %d' % (sum(1 for k, f in frac.items() if f is not None and f < 0.85 and rate[k] * T >= 2), len(frac)))
rows = []
for k, t in d['rec'].items():
    f = frac[k]
    if f is None or f >= 0.85 or rate[k] * T < 2: continue
    lacks = []
    root = True
    for item, have in t['lack'].items():
        mk = makers.get(item, [])
        mf = [frac[m] for m in mk if frac.get(m) is not None]
        st = d['stock'].get(item) if not item.startswith('[') else d['fstock'].get(item[1:-1])
        if mk and mf and min(mf) < 0.85: root = False
        lacks.append('%s (stock %s; makers %s)' % (item, st, ', '.join('%s %.0f%%' % (m, 100 * frac[m]) for m in mk if frac.get(m) is not None) or 'none here'))
    rows.append((root and bool(t['lack']), f, k, t, lacks))
for tag, sel in (('LACK SOMETHING WHOSE MAKERS KEPT TO PLAN (or that nothing here makes):', True), ('others behind:', False)):
    print(tag)
    for root, f, k, t, lacks in sorted(rows, key=lambda x: x[1]):
        if root != sel: continue
        print('  %-30s %3.0f%% of plan (%d crafts) %s out %s' % (k[:30], 100 * f, t['pf'], t['st'], t['out'] or ''))
        for l in lacks: print('       lacks', l)