"""Rates with a margin: for a plan's own recipes, the rates at which every item the factory uses is still in surplus
when every machine's rate is off by up to delta either way (makers slow, users fast), at the least machines and raw
input. Writes a linear program for the planner's solver (LpFileTest) and reads its answer back.
  margin.py write1 plan.json target delta lp.json   then  LPFILE=lp.json ./gradlew test --tests LpFileTest --rerun
  margin.py write2 plan.json target delta lp.json   then  LPFILE=lp.2.json ./gradlew ...   (lists what cannot be in surplus)
  margin.py read   plan.json target delta lp.json out=caps.json   (each machine's rate against its plan, for sim.py caps=)
An item that cannot be in surplus (a carrier that only goes round: barrels, heat bricks) is found in the first stage
and allowed that shortfall in the second. keep=1: no recipe below its plan."""
import json, sys, collections
mode, planf, target, delta, lpf = sys.argv[1:6]
delta = float(delta); plan = json.load(open(planf))
M = plan['machines']; T = plan['terminals']
rec = sorted({m['recipe'] for m in M if m['crafts'] > 0}); rix = {r: k for k, r in enumerate(rec)}
x = collections.Counter(); speed = {}; nmach = collections.Counter()
for m in M:
    if m['crafts'] > 0: x[m['recipe']] += m['crafts']; speed[m['recipe']] = max(m['maxCrafts'], 1e-9); nmach[m['recipe']] += 1
pro = collections.defaultdict(lambda: collections.Counter()); ing = collections.defaultdict(lambda: collections.Counter())
for t in T:
    if 'machine' not in t or M[t['machine']]['crafts'] <= 0: continue
    r = M[t['machine']]['recipe']
    if t['role'] == 'PRODUCER': pro[r][t['item']] += t['rate']
    elif t['role'] == 'CONSUMER': ing[r][t['item']] += t['rate']
net = {}     # recipe -> item -> per craft, net of what it gives back itself
for r in rec:
    d = collections.Counter()
    for i, v in pro[r].items(): d[i] += v / x[r]
    for i, v in ing[r].items(): d[i] -= v / x[r]
    net[r] = {i: v for i, v in d.items() if abs(v) > 1e-12}
made = {i for r in rec for i, v in net[r].items() if v > 0}; used = {i for r in rec for i, v in net[r].items() if v < 0}
brought = {t['item'] for t in T if t['role'] == 'SOURCE' and t['rate'] > 1e-9}   # (not a 0/s priming port)      # also brought in from outside: as good as raw
items = sorted((made & used) - brought); raw = sorted((used - made) | (used & brought))
flow = collections.Counter()
for r in rec:
    for i, v in net[r].items():
        if v < 0: flow[i] += -v * x[r]
opts = dict(a.split('=') for a in sys.argv[6:] if '=' in a)
keep = opts.get('keep') == '1'
slackof = []
def write(path, stage, allowed):
    """Variables are rates against plan; rows are divided by the item's planned flow. Stage 1: the least total
    shortfall (which items cannot be in surplus). Stage 2: only those may fall short, by no more; the least machines
    and raw input."""
    vars_ = [{'lb': 1.0 if keep else 0, 'ub': None, 'obj': 0.0 if stage == 1 else x[r] / speed[r]} for r in rec]
    if stage == 2:
        for r in rec:
            for i, v in net[r].items():
                if v < 0 and i in raw: vars_[rix[r]]['obj'] += 0.01 * -v * x[r]
    cons = []; slackof.clear()
    for i in items:
        f = max(flow[i], 1e-12)
        # (An item that cannot be in surplus only goes round, cages and bricks: its ring runs at its slowest stage and
        # loses nothing, so in the second stage it is balanced at the rates as they are meant, without the errors.)
        ring = i in allowed
        terms = [[rix[r], net[r][i] * x[r] / f * (1.0 if ring else (1 - delta) if net[r][i] > 0 else (1 + delta))] for r in rec if i in net[r]]
        if stage == 1 and not ring:
            s = len(vars_); vars_.append({'lb': 0, 'ub': None, 'obj': 1.0}); slackof.append(i)
            terms.append([s, 1.0])
        cons.append({'lb': 0, 'ub': 0 if ring else None, 'terms': terms})
    tt = sum(max(0.0, net[r].get(target, 0)) * x[r] for r in rec)
    cons.append({'lb': 1.0, 'ub': None, 'terms': [[rix[r], net[r][target] * x[r] / tt * (1 - delta)] for r in rec if net[r].get(target, 0) > 0]})
    json.dump({'vars': vars_, 'cons': cons, 'maximize': False}, open(path, 'w'))
import os
af = lpf + '.allowed.json'
if mode == 'write1':
    # rings found so far (allowed.json; 'fresh=1' starts over) are balanced as meant; the rest may fall short at a price
    allowed = {} if opts.get('fresh') == '1' or not os.path.exists(af) else json.load(open(af))
    json.dump(allowed, open(af, 'w'))
    write(lpf, 1, allowed)
elif mode == 'collect':
    # after solving stage 1: items still short join the rings; prints how many were new
    allowed = json.load(open(af)); write(lpf + '.tmp', 1, allowed)
    sol = json.load(open(lpf + '.sol.json')); assert sol['status'] == 'OPTIMAL', sol['status']
    new = {i: sol['x'][len(rec) + k] for k, i in enumerate(slackof) if sol['x'][len(rec) + k] > 1e-6}
    allowed.update(new); json.dump(allowed, open(af, 'w')); os.remove(lpf + '.tmp')
    print(len(new))
elif mode == 'write2':
    allowed = json.load(open(af))
    write(lpf.replace('.json', '.2.json'), 2, allowed)
    print('delta %.2f: %d items only go round (balanced exactly, no overflow): %s' % (delta, len(allowed), ', '.join(sorted(allowed)[:24])))
else:
    allowed = json.load(open(lpf + '.allowed.json'))
    sol = json.load(open(lpf.replace('.json', '.2.json') + '.sol.json'))
    c = {r: sol['x'][rix[r]] * x[r] for r in rec}
    ratio = sorted(c[r] / x[r] for r in rec)
    def q(f): return ratio[min(len(ratio) - 1, int(f * len(ratio)))]
    rawp = sum(-v * x[r] for r in rec for i, v in net[r].items() if v < 0 and i in raw)
    rawm = sum(-v * c[r] for r in rec for i, v in net[r].items() if v < 0 and i in raw)
    import math
    mp = sum(nmach.values())
    mm = sum(max(nmach[r] if c[r] > 1e-12 else 0, math.ceil(c[r] * (1 + delta) / speed[r] - 1e-9)) for r in rec)
    print('delta %.2f: %s; rates against plan: min %.2f, median %.2f, p90 %.2f, max %.2f; recipes off: %d; raw input %.2fx; machines %d -> %d; cannot be in surplus: %d' % (
        delta, sol['status'], ratio[0], q(.5), q(.9), ratio[-1], sum(v < 1e-9 for v in ratio), rawm / rawp, mp, mm, len(allowed)))
    if 'out' in opts:
        json.dump({'ratio': [c[m['recipe']] / x[m['recipe']] if m['crafts'] > 0 else 1 for m in M], 'exempt': sorted(allowed), 'delta': delta}, open(opts['out'], 'w'))
