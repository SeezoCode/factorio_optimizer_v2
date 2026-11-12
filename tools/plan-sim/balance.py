"""Material balance of one in-game check against the plan: what came in, what left, what was made and used (items).
  balance.py <run>/plan_rebalanced.json <run>/verify_report.json data/<game data>.json"""
import json, sys, collections
plan = json.load(open(sys.argv[1])); rep = json.load(open(sys.argv[2])); D = json.load(open(sys.argv[3])); R = D['recipes']
secs = rep['measured_s']
def base(n): return n.split('#')[0].split('@')[0]
fluids = set(D['fluids'])
def amt(p):
    a = p.get('amount'); a = (p.get('amount_min', 0) + p.get('amount_max', 0)) / 2 if a is None else a
    return a * p.get('probability', p.get('independent_probability', 1))
made = collections.Counter(); used = collections.Counter(); pmade = collections.Counter(); pused = collections.Counter()
for m in rep['machines']:
    r = R.get(m['recipe']) or {}
    for p in r.get('products', []): made[p['name']] += amt(p) * m['crafts'] / secs
    for g in r.get('ingredients', []): used[g['name']] += g.get('amount', 1) * m['crafts'] / secs
for t in plan['terminals']:
    if t['role'] == 'PRODUCER': pmade[base(t['item'])] += t['rate']
    elif t['role'] == 'CONSUMER': pused[base(t['item'])] += t['rate']
pin = collections.Counter(); 
for k, v in plan['rawRates'].items(): pin[base(k)] += v
pout = collections.Counter()
for k, v in plan['outputs'].items(): pout[base(k)] += v
gin = collections.Counter(); gout = collections.Counter(); gover = collections.Counter()
for s in rep['inputs']:
    for k, v in s['per_s'].items(): gin[k] += v
for s in rep['outputs']:
    for k, v in s['per_s'].items(): gout[k] += v
solid = lambda c: sum(v for k, v in c.items() if k not in fluids)
print('items only (fluids leaving are not measured):')
print('  raw in:  planned %.1f/s, in game %.1f/s' % (solid(pin), solid(gin)))
print('  leaving: planned %.1f/s, in game %.1f/s' % (solid(pout), solid(gout)))
print('  made:    planned %.1f/s, in game %.1f/s (%.0f%%)' % (solid(pmade), solid(made), 100 * solid(made) / solid(pmade)))
print('  used:    planned %.1f/s, in game %.1f/s (%.0f%%)' % (solid(pused), solid(used), 100 * solid(used) / solid(pused)))
# what left that the plan wanted used inside
leak = {k: (gout[k], pout.get(k, 0), pused.get(k, 0)) for k in gout if k not in fluids}
unplanned = sum(max(0, g - p) for g, p, u in leak.values())
print('  left beyond the plan: %.1f/s; of that, items the factory itself uses: %.1f/s' % (unplanned, sum(max(0, g - p) for g, p, u in leak.values() if u > 0)))
top = sorted(((g - p, k, g, p, u, made[k], pmade[k]) for k, (g, p, u) in leak.items() if u > 0 and g - p > 0.01), reverse=True)[:25]
print('  biggest (item: left/s, planned to leave, planned use inside, made in game, planned make):')
for d, k, g, p, u, mk, pm in top: print('    %-28s left %.2f  plan-leave %.2f  plan-use %.2f  made %.2f  plan-make %.2f' % (k, g, p, u, mk, pm))
# raw inputs: delivered against plan
print('  raw inputs taken against plan (items):')
for k in sorted(pin, key=lambda k: -pin[k]):
    if k in fluids: continue
    print('    %-22s plan %.2f  game %.2f  (%.0f%%)' % (k, pin[k], gin.get(k, 0), 100 * gin.get(k, 0) / pin[k] if pin[k] else 0))
# over- and under-running recipes, weighted
