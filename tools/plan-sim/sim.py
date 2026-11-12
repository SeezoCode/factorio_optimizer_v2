"""A plan run as a factory with ideal transport: one pool per item that every maker fills and every user draws from,
machines at their real speed, pools of finite size, and the rules named on the command line. No layout at all:
whatever goes wrong here goes wrong whatever the layout.   sim.py plan.json target [key=value ...]

  rule=free     machines run whenever they have ingredients and room (as built; the default)
  rule=stock    a machine runs only while a pool of a product somebody uses is not full (pull); full products overflow
  cap=F         a meter: no machine faster than F x its plan      meter=all|brakeless|multi|shared  which machines
  noise=F       every meter off by up to +-F (seeded)             head=F  every recipe can run at least F x plan
  outlets=plan  every planned output and overflow leaves when its pool is full (default)
  outlets=check as the in-game check: what the factory also uses leaves when its pool is full, a pure surplus is
                taken at its planned rate (its makers stand when it backs up), mixed leftovers always leave
  outlets=all|surplus|none
  sink=F        the target is taken at F x plan (0 = all of it)   raw=F  raw inputs arrive at F x plan (0 = any)
  seedkeep=0|1|warm  the loops' seeds (plan.json "seeds", catalysts) are put in once / kept topped up / kept until
                measuring starts (as the check does)
  order=fixed|fair   who draws first from a pool: a fixed order (as positions on a belt) / shuffled every step
  outage=F:T0:T1     a share F of machines stands still from hour T0 to T1
  hours=H (measured over the last quarter)  seed=N  buf=S (a pool holds S seconds of planned flow)  trace=1  quiet=1
  Tried and not kept as answers (see docs/WHY_NO_SCIENCE.md): reserve=F, rule=kanban, tight=FILE:THRESHOLD.

Prints the target's rate against plan, how many machines made 90% of their plan and how many under 10%, crafts and raw
input against plan, and what left. trace=1 follows what each machine waits for from the target to where it ends."""
import json, sys, random, collections
plan = json.load(open(sys.argv[1])); target = sys.argv[2]
DEF = dict(rule='free', cap='0', outlets='plan', reserve='0', sink='0', raw='0', head='0', order='fixed', buf='60', outage='', hours='8', seed='1',
           seedkeep='0', trace='0', dt='5', hops='3', quiet='0', meter='all', noise='0', noise1='0', hi='0.8', dump='', tight='', tcap='1', tnoise='0')
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
rawflow = [0.0] * NI
for t in T:
    if t['role'] == 'CONSUMER' and 'machine' in t and is_raw[ix[t['item']]]: rawflow[ix[t['item']]] += t['rate']
srcrate = [0.0] * NI
for t in T:
    if t['role'] == 'SOURCE' and not is_raw[ix[t['item']]]: srcrate[ix[t['item']]] += t['rate']
surplus = [False] * NI
for k, v in plan.get('outputs', {}).items():
    if k in ix and v > 1e-9: surplus[ix[k]] = True
ti = ix[target]; used[ti] = True
om = opt['outlets']
outrate = [0.0] * NI
if om == 'check':
    # as the in-game check: what the factory also uses leaves only when its pool is full; a pure surplus is taken at
    # its planned rate (its makers stand when it backs up); mixed leftovers always leave
    has_out = [planout[i] and (used[i] and i != ti or items[i].startswith('mixed-')) for i in range(NI)]
    for k, v in plan.get('outputs', {}).items():
        if k in ix and not used[ix[k]] and not k.startswith('mixed-'): outrate[ix[k]] = v
else:
    has_out = [{'plan': planout[i], 'all': True, 'surplus': surplus[i] or not used[i], 'none': False}[om] for i in range(NI)]
pc = [m['crafts'] for m in M]; speed = [max(m['maxCrafts'], m['crafts']) for m in M]
head = float(opt['head'])
if head > 0: speed = [max(s, head * p) for s, p in zip(speed, pc)]
dt = float(opt['dt']); buf = float(opt['buf'])
big = [0.0] * NI
for m in range(NM):
    for i, a in list(ing[m].items()) + list(pro[m].items()): big[i] = max(big[i], a)
capv = [max(buf * max(flow[i], rawflow[i]), 4 * big[i], 1.0) for i in range(NI)]
pool = [0.0] * NI
seeds = {ix[s] for s in plan.get('seeds', []) if s in ix}
for q in plan.get('ports', []):
    if q.get('priming') and q['item'] in ix: seeds.add(ix[q['item']])
for m in range(NM):
    for i in ing[m]:
        if i in pro[m]: seeds.add(i)
for i in seeds: pool[i] = capv[i] * 0.5
rng = random.Random(int(opt['seed'])); order = list(range(NM)); rng.shuffle(order)
mk = collections.defaultdict(list); us = collections.defaultdict(list)
for m in range(NM):
    for j in pro[m]: mk[j].append(m)
    for i in ing[m]: us[i].append(m)
rf = float(opt['reserve']); res = [dict() for _ in M]; nres = 0
if rf > 0:
    nxt = [set() for _ in M]
    for m in range(NM):
        for j in pro[m]: nxt[m].update(us[j])
    hops = int(opt['hops'])
    for i in range(NI):
        if not mk[i] or len(us[i]) < 2: continue
        tgt = set(mk[i]); inloop = []
        for u in us[i]:
            cur = {u}; seenu = {u}; hit = u in tgt
            for _ in range(hops):
                if hit: break
                cur = {w for v in cur for w in nxt[v]} - seenu; seenu |= cur; hit = bool(cur & tgt)
            if hit: inloop.append(u)
        if inloop and len(inloop) < len(us[i]):
            nres += 1
            for u in us[i]:
                if u not in inloop: res[u][i] = rf * capv[i]
ingl = [list(d.items()) for d in ing]; prol = [list(d.items()) for d in pro]
wantl = [[j for j in d if used[j]] for d in pro]
recyc = [set() for _ in M]          # machine -> ingredients it only recycles (takes the surplus of)
if opt['rule'] == 'kanban':
    # walk from the target up through suppliers; a supplier met while it is still on the path is downstream of the
    # machine asking: that draw is a loop closing. Where the item has another maker off the path the loop is recycling.
    sys.setrecursionlimit(100000)
    state = {}; fb = collections.defaultdict(set)
    def walk(u):
        state[u] = 1
        for i in ing[u]:
            for m in mk.get(i, []):
                if state.get(m) == 1: fb[(i, u)].add(m)
                elif m not in state: walk(m)
        state[u] = 2
    for m0 in mk[ix[target]]:
        if m0 not in state: walk(m0)
    nrec = ness = 0
    for (i, u), ms in fb.items():
        if any(m not in ms for m in mk[i]): recyc[u].add(i); nrec += 1
        else: ness += 1
    fwd = [False] * NI
    for i in range(NI):
        fwd[i] = i == ix[target] or any(i not in recyc[u] for u in us[i])
    wantl = [[j for j in d if fwd[j]] for d in pro]
    print('   kanban: %d loop-closing draws, %d of them recycling (surplus only), %d essential; machines with no product that counts as demand: %d' % (
        len(fb), nrec, ness, sum(1 for m in range(NM) if pc[m] > 0 and not wantl[m])))
hi = float(opt.get('hi', '0.8'))
stock = opt['rule'] in ('stock', 'kanban'); capf = float(opt['cap']); rawf = float(opt['raw']); sinkf = float(opt['sink']); seedkeep = opt['seedkeep'] in ('1', 'warm'); seedwarm = opt['seedkeep'] == 'warm'
mt = opt['meter']
nusers = [len(us[i]) for i in range(NI)]
nz = float(opt['noise']); nz1 = float(opt['noise1']); r2 = random.Random(int(opt['seed']) + 77)
capm = [capf * (1 + r2.uniform(-nz, nz) + r2.uniform(0, nz1)) for m in range(NM)]
metered = [mt == 'all' or (mt == 'brakeless' and all(has_out[j] for j in pro[m])) or (mt == 'multi' and len(pro[m]) > 1)
           or (mt == 'outlet' and any(has_out[j] for j in pro[m])) or (mt == 'shared' and any(nusers[i] > 1 for i in ing[m])) or (mt == 'sharednotraw' and any(nusers[i] > 1 and not is_raw[i] for i in ing[m])) for m in range(NM)]
if capf > 0 and mt != 'all': print('   metered machines: %d of %d' % (sum(metered), NM))
if opt['tight']:
    f, thr = opt['tight'].split(':'); prev = json.load(open(f)); tn = float(opt['tnoise']); ntight = 0
    for m in range(NM):
        if prev[m] >= float(thr): capm[m] = float(opt['tcap']) * (1 + r2.uniform(-tn, tn)); ntight += 1
    print('   tightly metered machines: %d of %d' % (ntight, NM))
steps = int(float(opt['hours']) * 3600 / dt); warm = steps * 3 // 4
down = set(); o0 = o1 = -1
if opt['outage']:
    f, a, b = opt['outage'].split(':'); o0 = int(float(a) * 3600 / dt); o1 = int(float(b) * 3600 / dt)
    down = set(rng.sample(range(NM), int(float(f) * NM)))
done = [0.0] * NM; donem = [0.0] * NM; leak = [0.0] * NI; rawin = [0.0] * NI; sci = 0.0; seeded = collections.Counter()
hist = []
for s in range(steps):
    for i in range(NI):
        if is_raw[i]:
            add = min(rawf * rawflow[i] * dt, capv[i] - pool[i]) if rawf > 0 else capv[i] - pool[i]
            pool[i] += add
            if s >= warm: rawin[i] += add
    for i in range(NI):
        if srcrate[i] > 0: pool[i] += min((rawf if rawf > 0 else 1.5) * srcrate[i] * dt, capv[i] - pool[i])
        if outrate[i] > 0: pool[i] -= min(pool[i], outrate[i] * dt)
    if seedkeep and not (seedwarm and s >= warm):
        for i in seeds:
            if pool[i] < 0.1 * capv[i]:
                if s >= warm: seeded[i] += 0.1 * capv[i] - pool[i]
                pool[i] = 0.1 * capv[i]
    take = min(pool[ti], sinkf * flow[ti] * dt) if sinkf > 0 else pool[ti]
    pool[ti] -= take
    if s >= warm: sci += take
    if s % 720 == 0: hist.append(0.0)
    hist[-1] += take
    if opt['order'] == 'fair': rng.shuffle(order)
    out_now = o0 <= s < o1
    for m in order:
        if pc[m] <= 0 or (out_now and m in down): continue
        x = speed[m] * dt
        if capf > 0 and metered[m]: x = min(x, capm[m] * pc[m] * dt)
        if stock:
            room = 0.0
            for j in wantl[m]:
                v = (capv[j] - pool[j]) / pro[m][j]
                if v > room: room = v
            if room < x: x = room
            if x <= 1e-12: continue
        rm = res[m]; rc = recyc[m]
        for i, a in ingl[m]:
            v = (pool[i] - rm[i] if i in rm else pool[i] - hi * capv[i] if i in rc else pool[i]) / a
            if v < x: x = v
        if x <= 1e-12: continue
        if not stock:
            for j, p in prol[m]:
                if has_out[j]: continue
                v = (capv[j] - pool[j]) / p
                if v < x: x = v
            if x <= 1e-12: continue
        for i, a in ingl[m]: pool[i] -= a * x
        for j, p in prol[m]: pool[j] += p * x
        done[m] += x
        if s >= warm: donem[m] += x
    for i in range(NI):
        if pool[i] > capv[i]:
            if s >= warm: leak[i] += pool[i] - capv[i]
            pool[i] = capv[i]
span = (steps - warm) * dt
rel = [donem[m] / span / pc[m] if pc[m] > 0 else 1 for m in range(NM)]
name = ' '.join('%s=%s' % kv for kv in opt.items() if kv[1] != DEF[kv[0]] and kv[0] not in ('trace', 'quiet')) or 'rule=free (as built)'
if opt['dump']: json.dump(rel, open(opt['dump'], 'w'))
planleak = sum(v for k, v in plan.get('outputs', {}).items())
print('%-58s target %3.0f%% | at plan (>=90%%) %3d, dead (<10%%) %3d of %d | crafts %3.0f%% | raw %3.0f%% | leaving %4.0f/s (plan %.0f)' % (
    name, 100 * sci / span / flow[ti], sum(r >= .9 for r in rel), sum(r < .1 for r in rel), NM, 100 * sum(donem) / span / sum(pc),
    100 * sum(rawin) / span / max(1e-9, sum(rawflow)), sum(leak) / span, planleak))
if opt['quiet'] != '1':
    print('   target by hour, %% of plan: ' + ' '.join('%.0f' % (100 * h / (flow[ti] * 3600)) for h in hist))
    if nres: print('   %d loop items keep a reserve' % nres)
    if seedkeep: print('   seeds topped up: %d items, %.2f/s; most: %s' % (len(seeded), sum(seeded.values()) / span, ', '.join('%s %.3f/s' % (items[i], v / span) for i, v in seeded.most_common(6))))
    over = sorted(((rel[m], M[m]['recipe']) for m in range(NM)), reverse=True)[:5]
    print('   furthest over plan: ' + ', '.join('%s %.1fx' % (r, v) for v, r in over))
if opt['trace'] == '1':
    def status(m):
        if stock and not any(pool[j] < capv[j] - 1e-9 for j in wantl[m]): return ('stocked', None)
        lim = capm[m] * pc[m] * dt if capf > 0 and metered[m] else speed[m] * dt
        sh = [i for i, a in ingl[m] if (pool[i] - res[m].get(i, 0) - (hi * capv[i] if i in recyc[m] else 0)) / a < 0.5 * lim]
        if sh and rel[m] < 0.9: return ('short', sh)
        bl = [j for j, p in prol[m] if not stock and not has_out[j] and (capv[j] - pool[j]) / p < 1e-9]
        if bl: return ('blocked', bl)
        return ('running', None)
    G = {}; st = {}; stack = [m for m in range(NM) if ti in pro[m]]; seen = set()
    while stack:
        m = stack.pop()
        if m in seen: continue
        seen.add(m); st[m] = status(m); G[m] = set()
        if st[m][0] == 'short':
            for i in st[m][1]:
                for k in mk.get(i, []): G[m].add(k); stack.append(k)
        elif st[m][0] == 'blocked':
            for j in st[m][1]:
                for k in us.get(j, []): G[m].add(k); stack.append(k)
    sys.setrecursionlimit(100000)
    idx = {}; lw = {}; stk = []; on = set(); comp = {}; comps = []; n = [0]
    def sc(v):
        idx[v] = lw[v] = n[0]; n[0] += 1; stk.append(v); on.add(v)
        for w in G[v]:
            if w not in idx: sc(w); lw[v] = min(lw[v], lw[w])
            elif w in on: lw[v] = min(lw[v], idx[w])
        if lw[v] == idx[v]:
            c = []
            while True:
                w = stk.pop(); on.discard(w); c.append(w); comp[w] = len(comps)
                if w == v: break
            comps.append(c)
    for v in list(G):
        if v not in idx: sc(v)
    sinks = [k for k, c in enumerate(comps) if all(comp[w] == k for v in c for w in G[v])]
    memo = {}
    def rs(k):
        if k in memo: return memo[k]
        memo[k] = set(); s_ = {k} if k in sinks else set()
        for v in comps[k]:
            for w in G[v]:
                if comp[w] != k: s_ |= rs(comp[w])
        memo[k] = s_; return s_
    reach = collections.Counter()
    for k in range(len(comps)):
        for s_ in rs(k): reach[s_] += len(comps[k])
    def d(m):
        k, v = st[m]
        return '%s [%s%s] %.0f%%' % (M[m]['recipe'], k, ' ' + ','.join(items[i] for i in v) if v else '', 100 * rel[m])
    print('   waits from the target: %d machines, ending at %d roots' % (len(seen), len(sinks)))
    for k in sorted(sinks, key=lambda k: -reach[k])[:14]:
        c = comps[k]
        print('     %4d wait on %s' % (reach[k], ('a loop of %d: ' % len(c) if len(c) > 1 else '') + ' | '.join(d(m) for m in c[:6])))
