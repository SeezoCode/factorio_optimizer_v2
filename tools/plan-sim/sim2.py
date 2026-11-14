"""The plan-level model again (see sim.py: one pool per item, no layout), with who gets a short item decided item by
item, and rates that may come from a plan with margins.   sim2.py plan.json target [key=value ...]
  caps=FILE    each machine's rate limit from margin.py (rates with a margin); else cap=F times its plan (0: none)
  scale=F      the caps file's rates times F (the whole factory slower: the target is then F of its plan at best)
  noise=F      every limit off by up to +-F        lo=F  every limit low by up to F (a machine that cannot keep up)
  share=order|small|large|prop   a short item goes to its users in a fixed random order (as positions on a belt) /
               smallest planned user first / largest first / in proportion
  rule=free|stock   machines run whenever they can / only while a pool of a product somebody uses is not full
  outlets=all|check|plan|none   which pools overflow when full (caps' carriers never do)
  sink=F  raw=F  buf=S  seedbuf=S (pools of the loops' seed items hold S seconds)  seedkeep=0|1|warm
  units=1 (whole crafts only; the meter is a bucket of bucket=N crafts plus bsecs=S seconds of its rate)  lag=S (what is made arrives S seconds later)
  built=1 (no machine faster than its top speed in the plain plan: an existing layout)
  meter=all|outlet (outlet: only machines with an overflow on some product are metered; the others run until the
           pools they fill are full)
  fill=K:H (for the first H hours every meter runs K times faster, as far as its machine can: a fast start)
  boost=N (a machine runs at its own full speed, outside its meter, while a product somebody uses has less than N
           crafts' worth in its pool: the start-up and catch-up rule)
  prog0=N|auto|users (every meter starts with N crafts in hand; auto: enough to give the biggest user of its products
            one craft; users: enough for every user's craft at once, as the in-game check counts it)
  prog0f=F (auto, users: F crafts' worth instead of one)
  bigk=N (a pool holds at least N crafts' worth of its biggest batch; default 4)
  fluidk=N (a fluid's pool holds N crafts' worth of its biggest batch, a minute of its flow, or two of its makers'
            batches, no more: pipes without storage tanks; default: fluids as items)
  ringroom=F (the carriers' pools hold F times as much, starting as full as before: storage that takes any number of
              cages and barrels, so that a ring never stands full; default 1)
  reqmin=N (with tank: every item ingredient also waits in a requester chest that asks for ten minutes' worth, at
            least N: the robot-built factory's chests ask for at least 10)
  tank=K (each machine keeps its own stock of every input, K crafts' worth, and fills it before anything else, first
          come first served in the share order, working or not: the game's machines hold two crafts' worth)
  outage=F:T0:T1  hours=H  seed=N (noise and order)  runs=N (repeat with seeds seed..seed+N-1, print the spread)  trace=1"""
import json, sys, random, collections
plan = json.load(open(sys.argv[1])); target = sys.argv[2]
DEF = dict(caps='', cap='0', noise='0', lo='0', share='order', rule='free', outlets='all', sink='0', raw='0', buf='60', seedbuf='0', seedkeep='0',
           outage='', hours='8', seed='1', runs='1', trace='0', dt='5', quiet='0', big='1', units='0', lag='0', ringseed='0.2', bucket='2', bsecs='0', bigk='4', prog0='1', scale='1', boost='0', meter='all', fill='1:0', built='0', tank='0', fluidk='', ringroom='1', prog0f='1', reqmin='0')
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
    cf = json.load(open(opt['caps'])); ratio = [r * float(opt['scale']) for r in cf['ratio']]; exempt = {ix[i] for i in cf['exempt'] if i in ix}
capf = float(opt['cap'])
# (a plan the planner solved with a margin names its own carriers)
exempt |= {ix[i] for i in plan.get('rings', []) if i in ix}
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
capv = [max((sb if sb > 0 and i in seeds else buf) * max(cflow[i], flow[i], rawflow[i]), float(opt['bigk']) * big[i], 1.0) for i in range(NI)]
isfluid = [False] * NI
for t in T:
    if t.get('fluid'): isfluid[ix[t['item']]] = True
reqmin = float(opt['reqmin'])
reqst = [{i: (max(reqmin, 600.0 * a * c0[m]) if reqmin > 0 and not isfluid[i] else 0.0) for i, a in ing[m].items()} for m in range(NM)]
bigmake = [0.0] * NI
for m in range(NM):
    for i, a in pro[m].items(): bigmake[i] = max(bigmake[i], a)
if opt['fluidk']:
    fl = {ix[t['item']] for t in T if t.get('fluid')}
    for i in fl:
        # (never less than two of its makers' biggest batch: their own output boxes)
        if i not in exempt: capv[i] = max(60.0 * max(cflow[i], flow[i], rawflow[i]), float(opt['fluidk']) * big[i], 2.0 * bigmake[i], 1.0)
ringroom = float(opt['ringroom']); seedlvl = list(capv)
for i in exempt: capv[i] *= ringroom
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
ringseed = float(opt['ringseed']); fillk, fillh = (float(v) for v in opt['fill'].split(':')); fills = int(fillh * 3600 / dt)
boostk = float(opt['boost']); lowlvl = [boostk * big[i] for i in range(NI)]
bucket = float(opt['bucket']); bsecs = float(opt['bsecs']); prog0 = 1.0 if opt['prog0'] in ('auto', 'users') else float(opt['prog0']); prog0f = float(opt['prog0f'])
nz = float(opt['noise']); lo = float(opt['lo']); units = opt['units'] == '1'; tankk = float(opt['tank'])
def run(seed, verbose):
    rng = random.Random(seed)
    capm = [((capf if capf > 0 else 0) if not opt['caps'] else ratio[m]) * (1 + rng.uniform(-nz, nz)) * (1 - rng.uniform(0, lo)) for m in range(NM)]
    lim = [(capm[m] * pc[m] if (capf > 0 or opt['caps']) else max(M[m]['maxCrafts'], pc[m])) for m in range(NM)]
    if opt['built'] == '1':
        # the machines as they stand in a layout built for the plain plan: none faster than its top speed
        lim = [min(lim[m], M[m]['maxCrafts']) for m in range(NM)]
    if opt['meter'] == 'outlet':
        # only machines that an overflow keeps from ever standing full are metered; the rest run until their pools are full
        for m in range(NM):
            if c0[m] > 0 and not any(has_out[j] for j in pro[m]): lim[m] = max(M[m]['maxCrafts'], lim[m])
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
    for i in seeds: pool[i] = capv[i] * (ringseed / ringroom if i in exempt else 0.5)
    down = set(); o0 = o1 = -1
    if opt['outage']:
        f, a, b = opt['outage'].split(':'); o0 = int(float(a) * 3600 / dt); o1 = int(float(b) * 3600 / dt)
        down = set(rng.sample([m for m in range(NM) if c0[m] > 0], int(float(f) * NM)))
    donem = [0.0] * NM; leak = 0.0; rawin = 0.0; sci = 0.0; hist = []
    bprog = [0.0] * NM; boosted = [False] * NM
    full = [max(M[m]['maxCrafts'], lim[m]) for m in range(NM)]
    prog = [prog0] * NM
    if opt['prog0'] in ('auto', 'users'):
        # each meter starts with enough crafts in hand to give every user of its products one craft's worth
        every = opt['prog0'] == 'users'
        for m in [k for k in range(NM) if c0[k] > 0]:
            need = prog0f
            for j, p in prol[m]:
                if every: need = max(need, prog0f * sum(ing[u][j] for u in us[j]) / p)
                else:
                    for u in us[j]:
                        need = max(need, prog0f * ing[u][j] / p)
            prog[m] = float(int(need + 0.999))
    lagn = int(float(opt['lag']) / dt); lagq = [[0.0] * NI for _ in range(lagn)]
    x = [0.0] * NM; want = [0.0] * NM; tr2 = verbose and opt['trace'] == '2'; win = [0.0] * NM; was = [False] * NM; fell = {}
    active = [m for m in range(NM) if c0[m] > 0]
    mach_order = list(active); rng.shuffle(mach_order)
    have = [dict.fromkeys(d, 0.0) for d in ing]
    leaki = [0.0] * NI
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
                if pool[i] < 0.1 * seedlvl[i]: pool[i] = 0.1 * seedlvl[i]
        take = min(pool[ti], sinkf * flow[ti] * dt) if sinkf > 0 else pool[ti]
        pool[ti] -= take
        if s >= warm: sci += take
        if s % int(3600 / dt) == 0: hist.append(0.0)
        hist[-1] += take
        out_now = o0 <= s < o1
        # what each machine would make if it had everything
        for m in active:
            if out_now and m in down: x[m] = 0.0; want[m] = 0.0; continue
            v = (min(lim[m] * fillk, full[m]) if s < fills else lim[m]) * dt
            fast = False
            if boostk > 0:
                for j in wantl[m]:
                    if pool[j] < lowlvl[j]: fast = True; break
                if fast: v = full[m] * dt
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
            if units and fast:
                # (boosted: whole crafts at the machine's own speed, outside the meter)
                bprog[m] = min(bprog[m] + full[m] * dt, 1.0 + full[m] * dt)
                v = float(int(bprog[m] + 1e-9)) if v > 0 else 0.0
            elif units:
                # whole crafts only: the meter's bucket fills at the machine's rate and holds two crafts at most
                rate_now = v / dt
                prog[m] = min(prog[m] + rate_now * dt, max(prog[m], bucket + bsecs * rate_now + rate_now * dt))
                v = min(v if v > 0 else 0.0, 1e18) if v >= 1e-12 else 0.0
                v = float(int(prog[m] + 1e-9)) if v > 0 else 0.0
            x[m] = v if v > 0 else 0.0
            want[m] = x[m]; boosted[m] = fast
        if tankk > 0:
            # every machine fills its own stock of each input first, whoever comes first in the item's order taking
            # all there is; then crafts from what it holds
            for i in range(NI):
                u = order[i]
                left = pool[i]
                if not u or left <= 0: continue
                for m in u:
                    # (its stock also covers what it crafts in one step: a fast machine is fed all the time)
                    w = (tankk + lim[m] * dt) * ing[m][i] + reqst[m][i] - have[m][i]
                    if w <= 0: continue
                    if w > left: w = left
                    have[m][i] += w; left -= w
                    if left <= 0: break
                pool[i] = left
            for m in active:
                v = x[m]
                if v <= 0: continue
                h = have[m]
                for i, a in ingl[m]:
                    r = h[i] / a
                    if r < v: v = r
                if units: v = float(int(v + 1e-9))
                x[m] = v
                if v > 0:
                    for i, a in ingl[m]: h[i] -= a * v
        # share out what is short, twice (the second time with what the first freed)
        for _ in range(0 if tankk > 0 else 2):
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
        if units:
            for m in active: x[m] = float(int(x[m] + 1e-9))
        # take; then whoever was cut back picks up what the sharing left unused
        for m in (() if tankk > 0 else active):
            v = x[m]
            if v <= 0: continue
            for i, a in ingl[m]:
                pool[i] -= a * v
                if pool[i] < 0: pool[i] = 0.0
        for m in (() if tankk > 0 else mach_order):
            more = want[m] - x[m]
            if more <= 1e-12: continue
            for i, a in ingl[m]:
                r = pool[i] / a
                if r < more: more = r
            if units: more = float(int(more + 1e-9))
            if more <= 1e-12: continue
            for i, a in ingl[m]: pool[i] -= a * more
            x[m] += more
        if lagn:
            late = lagq[s % lagn]
            for j in range(NI):
                if late[j]: pool[j] += late[j]; late[j] = 0.0
        for m in active:
            v = x[m]
            if units and v > 0:
                if boosted[m]: bprog[m] -= v
                else: prog[m] -= v
            if tr2:
                win[m] += v
                if s % 120 == 119:
                    r = win[m] / (120 * dt * c0[m]); win[m] = 0.0
                    if r >= 0.9: was[m] = True
                    elif r < 0.5 and was[m] and m not in fell:
                        short = [items[i] for i, a in ingl[m] if (have[m][i] < a if tankk > 0 else pool[i] < a * lim[m] * dt)]
                        fell[m] = (s * dt / 3600, short)
            if v <= 0: continue
            if lagn:
                late = lagq[s % lagn]
                for j, p in prol[m]: late[j] += p * v
            else:
                for j, p in prol[m]: pool[j] += p * v
            if s >= warm: donem[m] += v
        for i in range(NI):
            if pool[i] > capv[i]:
                if s >= warm: leak += pool[i] - capv[i]; leaki[i] += pool[i] - capv[i]
                pool[i] = capv[i]
    span = (steps - warm) * dt
    rel = [donem[m] / span / c0[m] for m in active]
    res = dict(target=sci / span / flow[ti], ok=sum(r >= .9 for r in rel), dead=sum(r < .1 for r in rel), n=len(active),
               crafts=sum(donem) / span / sum(pc), raw=rawin / span / max(1e-9, sum(rawflow)), leak=leak / span, hist=hist)
    if tr2:
        print('   first machines to fall under half their rate after running at it (hour, what they were short of):')
        for m, (h, short) in sorted(fell.items(), key=lambda t: t[1][0])[:22]: print('     %.2f h  %-40s short of %s' % (h, M[m]['recipe'], ', '.join(short) or '-'))
    if verbose and opt['trace'] == '3':
        # where the items go that machines far behind wait for: made, used and lost while measured, against the plan
        relm = {m: donem[m] / span / c0[m] for m in active}
        short = collections.Counter()
        for m in active:
            if relm[m] < 0.5:
                for i, a in ingl[m]:
                    if (have[m][i] if tankk > 0 else pool[i]) < a: short[i] += 1
        print('   %d machines under half their rate; the items they hold less than a craft of:' % sum(1 for m in active if relm[m] < 0.5))
        rows = []
        for i, n in short.items():
            made = sum(donem[m] * pro[m][i] for m in mk[i]); used = sum(donem[m] * ing[m][i] for m in us[i])
            pl = sum(c0[m] * pro[m][i] for m in mk[i]) * span; plu = sum(c0[m] * ing[m][i] for m in us[i]) * span
            held = sum(have[m][i] for m in us[i]) if tankk > 0 else 0.0
            mrel = [relm[m] for m in mk[i]]
            rows.append((min(mrel) if mrel else -1, items[i], n, made / pl if pl else 0, used / plu if plu else 0, leaki[i] / pl if pl else 0, held, pool[i], len(mk[i]), len(us[i]), is_raw[i]))
        for r in sorted(rows, key=lambda r: -r[0])[:24]:
            print('     %-28s %2d wait; makers %d (slowest at %3.0f%%), made %3.0f%% of plan, used %3.0f%%, lost %3.0f%%; users %d hold %.0f, pool %.0f%s' % (
                r[1][:28], r[2], r[8], 100 * r[0], 100 * r[3], 100 * r[4], 100 * r[5], r[9], r[6], r[7], ' RAW' if r[10] else ''))
    if verbose and opt['trace'] == '1':
        # from the target up: a machine lacking a craft's worth of something waits for its makers; where that ends
        roots = collections.Counter(); seen = set(); stack = list(mk[ti]); info = {}
        while stack:
            m = stack.pop()
            if m in seen: continue
            seen.add(m)
            lack = [i for i, a in ingl[m] if (have[m][i] if tankk > 0 else pool[i]) < a * (1.0 if units else lim[m] * dt) - 1e-12]
            if lack:
                for i in lack:
                    if is_raw[i] or not mk[i]: roots[('no maker of ' + items[i], m)] += 1
                    for k in mk[i]: stack.append(k)
            else:
                info[m] = 'made %.0f%% of its rate, bucket %.2f crafts, rate %.5f/s' % (100 * donem[m] / span / c0[m], prog[m], lim[m])
        print('   waits from the target: %d machines; they end at %d machines that lack nothing:' % (len(seen), len(info)))
        for m, t in sorted(info.items(), key=lambda kv: donem[kv[0]] / c0[kv[0]])[:16]: print('      %-36s %s; makes %s' % (M[m]['recipe'], t, ', '.join(items[j] for j in pro[m])[:60]))
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
