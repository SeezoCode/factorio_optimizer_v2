"""Who waits for whom in one in-game check: every machine's usual state, what it lacks or cannot get rid of, and the
state of the machines on the other side, and from the target down to the roots every wait ends at.
  waits.py <run>/plan_rebalanced.json <run>/verify_report.json data/<game data>.json <target>"""
import json, sys, collections
plan = json.load(open(sys.argv[1])); rep = json.load(open(sys.argv[2])); R = json.load(open(sys.argv[3]))['recipes']; target = sys.argv[4]
secs = rep['measured_s']
def base(n): return n.split('#')[0].split('@')[0]
pc = collections.Counter()
for m in plan['machines']: pc[base(m['recipe'])] += m['crafts']
nmach = collections.Counter(base(m['recipe']) for m in plan['machines'])
Ms = rep['machines']
for i, m in enumerate(Ms):
    m['i'] = i; r = R.get(m['recipe']) or {}
    m['ing'] = {x['name']: x.get('amount', 1) for x in r.get('ingredients', [])}
    m['pro'] = {x['name']: x.get('amount', x.get('amount_max', 1)) for x in r.get('products', [])}
    fl = m['fluids']; fl = list(fl.values()) if isinstance(fl, dict) else fl
    m['fin'] = collections.Counter(); m['fout'] = collections.Counter()
    for f in fl:
        if f.get('name'): (m['fin'] if f['io'] == 'input' else m['fout'])[f['name']] += f.get('amount', 0)
    st = m['statuses']; tot = sum(st.values()) or 1
    m['w'] = st.get('working', 0) / tot
    m['plan'] = pc[m['recipe']] / max(1, nmach[m['recipe']])
    m['rel'] = (m['crafts'] / secs) / m['plan'] if m['plan'] > 0 else 0
    groups = collections.Counter()
    for k, v in st.items():
        g = {'working': 'work', 'disabled_by_script': 'off', 'full_output': 'full', 'waiting_for_space_in_destination': 'full', 'full_burnt_result_output': 'full',
             'item_ingredient_shortage': 'short', 'fluid_ingredient_shortage': 'short', 'no_ingredients': 'short', 'no_fuel': 'short'}.get(k, k)
        groups[g] += v / tot
    m['g'] = groups
    nw = {k: v for k, v in groups.items() if k != 'work'}
    m['state'] = 'work' if m['w'] >= 0.8 else (max(nw, key=nw.get) if nw else 'work')
    have = collections.Counter(m.get('input') or {}); have.update(m['fin'])
    m['lack'] = {k for k, a in m['ing'].items() if have.get(k, 0) < a}
    out = collections.Counter(m.get('output') or {})
    # a product it holds at least one craft's worth of (items), or a fluid box holding a craft's worth or more
    m['stuck'] = {k for k, a in m['pro'].items() if out.get(k, 0) >= max(1, a) or m['fout'].get(k, 0) >= a}
def nets(v): return set(v) if isinstance(v, list) else set(v.values()) if isinstance(v, dict) else set()
makers_on = collections.defaultdict(list); users_on = collections.defaultdict(list); makers = collections.defaultdict(list); users = collections.defaultdict(list)
for m in Ms:
    for p in m['pro']:
        makers[p].append(m)
        for n in nets(m['drops']): makers_on[(n, p)].append(m)
    for g in m['ing']:
        users[g].append(m)
        for n in nets(m['feeds']): users_on[(n, g)].append(m)
print('machines %d; crafts %d in %ds; planned %.0f' % (len(Ms), sum(m['crafts'] for m in Ms), secs, sum(pc.values()) * secs))
print('usual state:', dict(collections.Counter(m['state'] for m in Ms)))
print('rate against plan: >=0.9: %d, 0.5-0.9: %d, 0.1-0.5: %d, <0.1: %d, none: %d' % (sum(m['rel'] >= .9 for m in Ms), sum(.5 <= m['rel'] < .9 for m in Ms),
      sum(.1 <= m['rel'] < .5 for m in Ms), sum(0 < m['rel'] < .1 for m in Ms), sum(m['rel'] == 0 for m in Ms)))
fluids = set(json.load(open(sys.argv[3]))['fluids'])
def sup(c, x):
    """Machines that supply item x to machine c: makers on its belts, else (robots, pipes) every maker."""
    d = []
    for n in nets(c['feeds']): d += makers_on.get((n, x), [])
    return (d, 'belt') if d else (makers.get(x, []), 'pipe' if x in fluids else 'far')
def cls(c, x):
    ms, how = sup(c, x)
    if not ms: return 'raw', how, []
    k = []
    for m in ms:
        if m['state'] == 'work': k.append(('flat', m))
        elif m['state'] == 'off': k.append(('off', m))
        elif m['state'] == 'full': k.append(('has' if x in m['stuck'] else 'blocked', m))
        elif m['rel'] >= 0.9: k.append(('atplan', m))
        else: k.append(('short', m))
    order = ['has', 'flat', 'atplan', 'off', 'blocked', 'short']
    best = min(k, key=lambda t: order.index(t[0]))[0]
    return best, how, [m for t, m in k if t == best]
names = {'has': 'a maker stands full of it (it exists, it does not arrive)', 'flat': 'its maker works flat out', 'atplan': 'its maker makes its plan, others take it',
         'off': 'its maker is switched off', 'blocked': 'its maker is blocked by another product', 'short': 'its maker is short itself', 'raw': 'a raw input'}
# 1. every shortage in the factory by the state of the supply
short = [m for m in Ms if m['state'] == 'short']
tally = collections.Counter(); byhow = collections.Counter(); exs = collections.defaultdict(list)
for c in short:
    for x in c['lack']:
        k, how, ms = cls(c, x); tally[k] += 1; byhow[(k, how)] += 1
        if len(exs[(k, how)]) < 6: exs[(k, how)].append('%s for %s' % (x, c['recipe']))
print('\nmachines usually short: %d, lacking %d ingredients in all; the supply side of each:' % (len(short), sum(tally.values())))
for k, v in tally.most_common():
    print('  %4d  %s' % (v, names[k]))
    for (kk, how), n in sorted(byhow.items(), key=lambda t: -t[1]):
        if kk == k: print('          %4d over %-5s e.g. %s' % (n, {'belt': 'belts', 'pipe': 'pipes', 'far': 'robots'}[how], '; '.join(exs[(kk, how)][:4])))
# 2. full machines: is what they hold wanted?
full = [m for m in Ms if m['state'] == 'full']
wanted = collections.Counter(); fex = collections.defaultdict(list)
lackers = collections.defaultdict(list)
for c in short:
    for x in c['lack']: lackers[x].append(c)
for m in full:
    st = m['stuck'] or set(m.get('output') or {})
    w_same = [x for x in st if any(nets(c['feeds']) & nets(m['drops']) for c in lackers.get(x, []))]
    w_any = [x for x in st if lackers.get(x)]
    other = [x for x in m['pro'] if x not in st and lackers.get(x)]
    k = ('holds what a machine on its own belt lacks' if w_same else 'holds what a machine elsewhere lacks' if w_any else
         'holds what nobody lacks, while another of its products is lacked' if other else 'holds what nobody lacks (idle, as it should be)')
    wanted[k] += 1
    if len(fex[k]) < 8: fex[k].append('%s (%s%s)' % (m['recipe'], ','.join(sorted(st)), ' | lacked: ' + ','.join(other) if other and not (w_same or w_any) else ''))
print('\nmachines usually standing full: %d' % len(full))
for k, v in wanted.most_common(): print('  %4d  %s\n          e.g. %s' % (v, k, '; '.join(fex[k][:6])))
# 3. off machines: is their product lacked?
off = [m for m in Ms if m['state'] == 'off']
oc = collections.Counter(); oex = collections.defaultdict(list)
for m in off:
    same = [x for x in m['pro'] if any(nets(c['feeds']) & nets(m['drops']) for c in lackers.get(x, []))]
    anyw = [x for x in m['pro'] if lackers.get(x)]
    k = 'off while a machine on its own belt lacks its product' if same else 'off while a machine elsewhere lacks its product' if anyw else 'off, product not lacked'
    oc[k] += 1
    if len(oex[k]) < 8: oex[k].append('%s (%s)' % (m['recipe'], ','.join(same or anyw)))
print('\nmachines usually switched off: %d' % len(off))
for k, v in oc.most_common(): print('  %4d  %s\n          e.g. %s' % (v, k, '; '.join(oex[k][:6])))
# 4. waits-for graph and its loops
g = collections.defaultdict(set)
for c in Ms:
    if c['state'] == 'short':
        for x in c['lack']:
            for m in sup(c, x)[0]: g[c['i']].add(m['i'])
    elif c['state'] == 'full':
        for x in (c['stuck'] or set(c.get('output') or {})):
            us = []
            for n in nets(c['drops']): us += users_on.get((n, x), [])
            for u in (us or users.get(x, [])): g[c['i']].add(u['i'])
sys.setrecursionlimit(100000)
idx = {}; low = {}; stk = []; on = set(); sccs = []; n = [0]
def sc(v):
    idx[v] = low[v] = n[0]; n[0] += 1; stk.append(v); on.add(v)
    for w in g[v]:
        if w not in idx: sc(w); low[v] = min(low[v], low[w])
        elif w in on: low[v] = min(low[v], idx[w])
    if low[v] == idx[v]:
        c = []
        while True:
            w = stk.pop(); on.discard(w); c.append(w)
            if w == v: break
        sccs.append(c)
for m in Ms:
    if m['i'] not in idx: sc(m['i'])
big = sorted(sccs, key=len, reverse=True)
print('\nwaits-for loops (machines each waiting, directly or through others, for one another): sizes', [len(c) for c in big[:8]])
inloop = set(big[0]) if len(big[0]) > 1 else set()
print('  in the biggest: %s' % dict(collections.Counter(Ms[i]['state'] for i in inloop)))
# 5. from the target down: where the chains end
tm = [m for m in Ms if target in m['pro']]
seen = set(); ends = collections.Counter(); eex = collections.defaultdict(list); stack = list(tm)
while stack:
    c = stack.pop()
    if c['i'] in seen: continue
    seen.add(c['i'])
    if c['state'] != 'short':
        continue
    for x in c['lack']:
        k, how, ms = cls(c, x)
        if k == 'short' :
            stack += ms
        else:
            ends[(k, how)] += 1
            if len(eex[(k, how)]) < 8: eex[(k, how)].append('%s for %s' % (x, c['recipe']))
            if k == 'blocked': stack += []
print('\nfrom %s down through what each machine lacks: %d machines; the chains end at' % (target, len(seen)))
for (k, how), v in ends.most_common(): print('  %4d  %s, over %s\n          e.g. %s' % (v, names[k], {'belt': 'belts', 'pipe': 'pipes', 'far': 'robots'}[how], '; '.join(eex[(k, how)][:5])))

# 6. root causes: from the target follow every wait; where the waits end (or go round in a loop) is a root
def desc(m):
    g = ', '.join('%s %.0f%%' % (k, 100 * v) for k, v in sorted(m['g'].items(), key=lambda t: -t[1])[:3])
    return '%s [%s] %.0f%% of plan; lacks %s; holds %s' % (m['recipe'], g, 100 * m['rel'], ','.join(sorted(m['lack'])) or '-', ','.join(sorted(m['stuck'] or set(m.get('output') or {}))) or '-')
def waits(c):
    """(machines c waits for, why). Short: makers of what it lacks. Full: users of what it holds."""
    out = []
    if c['state'] == 'short':
        for x in c['lack']:
            for m in sup(c, x)[0]: out.append((m, 'lacks ' + x))
            if not sup(c, x)[0]: out.append((None, 'lacks raw ' + x))
    elif c['state'] == 'full':
        for x in (c['stuck'] or set(c.get('output') or {})):
            us = []
            for n in nets(c['drops']): us += users_on.get((n, x), [])
            for u in (us or users.get(x, [])): out.append((u, 'holds ' + x))
    return out
G = {}; why = {}
seen = set(); stack = [m['i'] for m in tm]
while stack:
    i = stack.pop()
    if i in seen: continue
    seen.add(i); G[i] = set()
    for m, w in waits(Ms[i]):
        if m is None: why.setdefault(i, set()).add(w); continue
        G[i].add(m['i']); stack.append(m['i'])
idx = {}; low = {}; stk = []; on = set(); comp = {}; comps = []; n = [0]
def sc2(v):
    idx[v] = low[v] = n[0]; n[0] += 1; stk.append(v); on.add(v)
    for w in G[v]:
        if w not in idx: sc2(w); low[v] = min(low[v], low[w])
        elif w in on: low[v] = min(low[v], idx[w])
    if low[v] == idx[v]:
        c = []
        while True:
            w = stk.pop(); on.discard(w); c.append(w); comp[w] = len(comps)
            if w == v: break
        comps.append(c)
for v in list(G):
    if v not in idx: sc2(v)
sinks = [k for k, c in enumerate(comps) if all(comp[w] == k for v in c for w in G[v])]
# how many machines of the closure reach each sink
reach = collections.Counter()
memo = {}
def rs(k):
    if k in memo: return memo[k]
    memo[k] = set()
    s = {k} if k in sinks else set()
    for v in comps[k]:
        for w in G[v]:
            if comp[w] != k: s |= rs(comp[w])
    memo[k] = s; return s
for k in range(len(comps)):
    for s in rs(k): reach[s] += len(comps[k])
print('\nfollowing every wait from %s: %d machines, %d of them in waits that end at %d roots' % (target, len(seen), len(seen), len(sinks)))
kinds = collections.Counter()
rows = []
for s in sorted(sinks, key=lambda s: -reach[s]):
    c = comps[s]
    if len(c) > 1: kind = 'loop of %d machines waiting for one another' % len(c)
    else:
        m = Ms[c[0]]
        kind = {'work': 'works flat out', 'off': 'switched off', 'full': 'stands full of what nobody takes', 'short': 'short of a raw input' if c[0] in why else 'short (nothing to wait for?)'}[m['state']]
        if m['state'] == 'short' and m['rel'] >= 0.9: kind = 'makes its plan, yet users go short'
    kinds[kind.split(' of ')[0] if kind.startswith('loop') else kind] += 1
    rows.append((reach[s], kind, c))
for k, v in kinds.most_common(): print('  %3d  %s' % (v, k))
print()
for r, kind, c in rows[:45]:
    print('%4d machines wait on: %s' % (r, kind))
    for i in c[:14]: print('        ' + desc(Ms[i])[:230] + ('  <- ' + ';'.join(sorted(why[i])) if i in why else ''))
