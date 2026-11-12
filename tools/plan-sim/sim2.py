"""The plan-level model again (see sim.py: one pool per item, no layout), with who gets a short item decided item by
item, and rates that may come from a plan with margins.   sim2.py plan.json target [key=value ...]
  caps=FILE    each machine's rate limit from margin.py (rates with a margin); else cap=F times its plan (0: none)
  noise=F      every limit off by up to +-F        lo=F  every limit low by up to F (a machine that cannot keep up)
  share=order|small|large|prop   a short item goes to its users in a fixed random order (as positions on a belt) /
               smallest planned user first / largest first / in proportion
  rule=free|stock   machines run whenever they can / only while a pool of a product somebody uses is not full
  outlets=all|check|plan|none   which pools overflow when full (caps' carriers never do)
  sink=F  raw=F  buf=S  seedbuf=S (pools of the loops' seed items hold S seconds)  seedkeep=0|1|warm
  outage=F:T0:T1  hours=H  seed=N (noise and order)  runs=N (repeat with seeds seed..seed+N-1, print the spread)  trace=1"""
import json, sys, random, collections
plan = json.load(open(sys.argv[1])); target = sys.argv[2]
DEF = dict(caps='', cap='0', noise='0', lo='0', share='order', rule='free', outlets='all', sink='0', raw='0', buf='60', seedbuf='0', seedkeep='0',
           outage='', hours='8', seed='1', runs='1', trace='0', dt='5', quiet='0', big='1', units='0', lag='0', ringseed='0.2')
opt = dict(DEF); opt.update(a.split('=') for a in sys.argv[3:])
M = plan['machines']; T = plan['terminals']
items = sorted({t['item'] for t in T}); ix = {n: i for i, n in enumerate(items)}; NI = len(items); NM = len(M)
ing = [dict() for _ in M]; pro = [dict() for _ in M]
flow = [0.0] * NI; planout = [False] * NI; is_raw = [True] * NI; used = [False] * NI
for t in T:
    i = ix[t['item']]
    if t['role'] == 'OUTPUT': planout[i] = True
    if 'machine' not in t: continue
    m = t['machine']; c = M[m]['crafts']
    if c <= 0: continue
    if t['role'] == 'PRODUCER': pro[m][i] = pro[m].get(i, 0) + t['rate'] / c; flow[i] += t['rate']; is_raw[i] = False
    elif t['role'] == 'CONSUMER': ing[m][i] = ing[m].get(i, 0) + t['rate'] / c; used[i] = True
rawflow = [0.0] * NI; srcrate = [0.0] * NI
for t in T:
    i = ix[t['item']]
    if t['role'] == 'CONSUMER' and 'machine' in t and is_raw[i]: rawflow[i] += t['rate']
    if t['role'] == 'SOURCE' and not is_raw[i]: srcrate[i] += t['rate']
ti = ix[target]; used[ti] = True
pc = [m['crafts'] for m in M]
ratio = [1.0] * NM; exempt = set()
if opt['caps']:
    cf = json.load(open(opt['caps'])); ratio = cf['ratio']; exempt = {ix[i] for i in cf['exempt'] if i in ix}
capf = float(opt['cap'])
om = opt['outlets']
outrate = [0.0] * NI
if om == 'check':
    has_out = [planout[i] and (used[i] and i != ti or items[i].startswith('mixed-')) for i in range(NI)]
    for k, v in plan.get('outputs', {}).items():
        if k in ix and not used[ix[k]] and not k.startswith('mixed-'): outrate[ix[k]] = v
else:
    has_out = [{'plan': planout[i], 'all': True, 'none': False}[om] for i in range(NI)]
for i in exempt: has_out[i] = False
dt = float(opt['dt']); buf = float(opt['buf'])
c0 = [pc[m] * ratio[m] for m in range(NM)]                 # the rate each machine is meant to run at
cflow = [0.0] * NI
for m in range(NM):
    for i, a in ing[m].items(): cflow[i] += a * c0[m]
big = [0.0] * NI
for m in range(NM):
    for i, a in list(ing[m].items()) + list(pro[m].items()): big[i] = max(big[i], a)
seeds = {ix[s] for s in plan.get('seeds', []) if s in ix}
for q in plan.get('ports', []):
    if q.get('priming') and q['item'] in ix: seeds.add(ix[q['item']])
for m in range(NM):
    for i in ing[m]:
        if i in pro[m]: seeds.add(i)
seeds |= exempt
sb = float(opt['seedbuf'])
capv = [max((sb if sb > 0 and i in seeds else buf) * max(cflow[i], flow[i], rawflow[i]), 4 * big[i], 1.0) for i in range(NI)]
mk = collections.defaultdict(list); us = collections.defaultdict(list)
for m in range(NM):
    if c0[m] <= 0: continue
    for j in pro[m]: mk[j].append(m)
    for i in ing[m]: us[i].append(m)
ingl = [list(d.items()) for d in ing]; prol = [list(d.items()) for d in pro]
wantl = [[j for j in d if used[j]] for d in pro]
stock = opt['rule'] == 'stock'; rawf = float(opt['raw']); sinkf = float(opt['sink'])
seedkeep = opt['seedkeep'] in ('1', 'warm'); seedwarm = opt['seedkeep'] == 'warm'
steps = int(float(opt['hours']) * 3600 / dt); warm = steps * 3 // 4
ringseed = float(opt['ringseed'])
nz = float(opt['noise']); lo = float(opt['lo']); units = opt['units'] == '1'
def run(seed, verbose):
    rng = random.Random(seed)
    capm = [((capf if capf > 0 else 0) if not opt['caps'] else ratio[m]) * (1 + rng.uniform(-nz, nz)) * (1 - rng.uniform(0, lo)) for m in range(NM)]
    lim = [(capm[m] * pc[m] if (capf > 0 or opt['caps']) else max(M[m]['maxCrafts'], pc[m])) for m in range(NM)]
    order = {}
    for i in range(NI):
        u = list(us[i])
        if opt['share'] == 'order': rng.shuffle(u)
        elif opt['share'] == 'small': u.sort(key=lambda m: ing[m][i] * c0[m])
        elif opt['share'] == 'large': u.sort(key=lambda m: -ing[m][i] * c0[m])
        order[i] = u
    prop = opt['share'] == 'prop'
    pool = [0.0] * NI
    # (carriers that only go round start low: a ring whose whole stock fits one pool cannot lock up)
    for i in seeds: pool[i] = capv[i] * (ringseed if i in exempt else 0.5)
    down = set(); o0 = o1 = -1
    if opt['outage']:
        f, a, b = opt['outage'].split(':'); o0 = int(float(a) * 3600 / dt); o1 = int(float(b) * 3600 / dt)
        down = set(rng.sample([m for m in range(NM) if c0[m] > 0], int(float(f) * NM)))
    donem = [0.0] * NM; leak = 0.0; rawin = 0.0; sci = 0.0; hist = []
    x = [0.0] * NM; want = [0.0] * NM; tr2 = verbose and opt['trace'] == '2'; win = [0.0] * NM; was = [False] * NM; fell = {}
    active = [m for m in range(NM) if c0[m] > 0]
    mach_order = list(active); rng.shuffle(mach_order)
    for s in range(steps):
        for i in range(NI):
            if is_raw[i]:
                add = min(rawf * rawflow[i] * dt, capv[i] - pool[i]) if rawf > 0 else capv[i] - pool[i]
                pool[i] += add
                if s >= warm: rawin += add
            if srcrate[i] > 0: pool[i] += min(rawf * srcrate[i] * dt, capv[i] - pool[i]) if rawf > 0 else capv[i] - pool[i]
            if outrate[i] > 0: pool[i] -= min(pool[i], outrate[i] * dt)
        if seedkeep and not (seedwarm and s >= warm):
            for i in seeds:
                if pool[i] < 0.1 * capv[i]: pool[i] = 0.1 * capv[i]
        take = min(pool[ti], sinkf * flow[ti] * dt) if sinkf > 0 else pool[ti]
        pool[ti] -= take
        if s >= warm: sci += take
        if s % int(3600 / dt) == 0: hist.append(0.0)
        hist[-1] += take
        out_now = o0 <= s < o1
        # what each machine would make if it had everything
        for m in active:
            if out_now and m in down: x[m] = 0.0; want[m] = 0.0; continue
            v = lim[m] * dt
            if stock:
                room = 0.0
                for j in wantl[m]:
                    r = (capv[j] - pool[j]) / pro[m][j]
                    if r > room: room = r
                if room < v: v = room
            for j, p in prol[m]:
                if not has_out[j] and not stock:
                    r = (capv[j] - pool[j]) / p
                    if r < v: v = r
            x[m] = v if v > 0 else 0.0
            want[m] = x[m]
        # share out what is short, twice (the second time with what the first freed)
        for _ in range(2):
            for i in range(NI):
                u = order[i]
                if not u: continue
                need = 0.0
                for m in u: need += ing[m][i] * x[m]
                if need <= pool[i] + 1e-12: continue
                if prop:
                    f = pool[i] / need if need > 0 else 0.0
                    for m in u: x[m] *= f
                else:
                    left = pool[i]
                    for m in u:
                        n = ing[m][i] * x[m]
                        if n > left:
                            x[m] = left / ing[m][i] if left > 0 else 0.0
                            left = 0.0
                        else: left -= n
        # take; then whoever was cut back picks up what the sharing left unused
        for m in active:
            v = x[m]
            if v <= 0: continue
            for i, a in ingl[m]:
                pool[i] -= a * v
                if pool[i] < 0: pool[i] = 0.0
        for m in mach_order:
            more = want[m] - x[m]
            if more <= 1e-12: continue
            for i, a in ingl[m]:
                r = pool[i] / a
                if r < more: more = r
            if more <= 1e-12: continue
            for i, a in ingl[m]: pool[i] -= a * more
            x[m] += more
        for m in active:
            v = x[m]
            if tr2:
                win[m] += v
                if s % 120 == 119:
                    r = win[m] / (120 * dt * c0[m]); win[m] = 0.0
                    if r >= 0.9: was[m] = True
                    elif r < 0.5 and was[m] and m not in fell:
                        short = [items[i] for i, a in ingl[m] if pool[i] < a * lim[m] * dt]
                        fell[m] = (s * dt / 3600, short)
            if v <= 0: continue
            for j, p in prol[m]: pool[j] += p * v
            if s >= warm: donem[m] += v
        for i in range(NI):
            if pool[i] > capv[i]:
                if s >= warm: leak += pool[i] - capv[i]
                pool[i] = capv[i]
    span = (steps - warm) * dt
    rel = [donem[m] / span / c0[m] for m in active]
    res = dict(target=sci / span / flow[ti], ok=sum(r >= .9 for r in rel), dead=sum(r < .1 for r in rel), n=len(active),
               crafts=sum(donem) / span / sum(pc), raw=rawin / span / max(1e-9, sum(rawflow)), leak=leak / span, hist=hist)
    if tr2:
        print('   first machines to fall under half their rate after running at it (hour, what they were short of):')
        for m, (h, short) in sorted(fell.items(), key=lambda t: t[1][0])[:22]: print('     %.2f h  %-40s short of %s' % (h, M[m]['recipe'], ', '.join(short) or '-'))
    if verbose and opt['trace'] == '1':
        relm = {m: donem[m] / span / c0[m] for m in active}
        low = sorted((relm[m], M[m]['recipe']) for m in active)[:12]
        print('   furthest behind: ' + ', '.join('%s %.0f%%' % (r, 100 * v) for v, r in low))
        ex = sorted((pool[i] / capv[i], items[i], sum(1 for m in mk[i]), sum(1 for m in us[i])) for i in range(NI) if not has_out[i] and mk[i])
        print('   pools that cannot overflow: ' + ', '.join('%s %.0f%% (%d makers, %d users)' % (n, 100 * v, a, b) for v, n, a, b in ex))
        blocked = [M[m]['recipe'] for m in active if any((not has_out[j]) and pool[j] >= capv[j] - 1e-9 for j in pro[m])]
        print('   machines blocked by a full one: %d %s' % (len(blocked), ', '.join(blocked[:12])))
        emp = sorted(((pool[i] / capv[i], items[i]) for i in range(NI) if us[i] and not is_raw[i]))[:14]
        print('   emptiest pools: ' + ', '.join('%s %.0f%%' % (n, 100 * v) for v, n in emp))
    return res
name = ' '.join('%s=%s' % (k, v if k != 'caps' else v.split('/')[-1]) for k, v in opt.items() if v != DEF[k] and k not in ('trace', 'quiet', 'runs')) or 'as built'
runs = int(opt['runs']); s0 = int(opt['seed'])
rs = [run(s0 + k, k == 0) for k in range(runs)]
t = sorted(r['target'] for r in rs)
if runs == 1:
    r = rs[0]
    print('%-64s target %3.0f%% | at rate (>=90%%) %3d, dead %3d of %d | crafts %3.0f%% | raw %3.0f%% | leaving %4.0f/s' % (name, 100 * r['target'], r['ok'], r['dead'], r['n'], 100 * r['crafts'], 100 * r['raw'], r['leak']))
    if opt['quiet'] != '1': print('   target by hour: ' + ' '.join('%.0f' % (100 * h / (flow[ti] * 3600)) for h in r['hist']))
else:
    print('%-64s target over %d runs: min %3.0f%%, median %3.0f%%, max %3.0f%%; runs at 95%% or more: %d; dead machines, median %d; raw %3.0f%%' % (
        name, runs, 100 * t[0], 100 * t[len(t) // 2], 100 * t[-1], sum(v >= .95 for v in t), sorted(r['dead'] for r in rs)[runs // 2], 100 * sorted(r['raw'] for r in rs)[runs // 2]))
