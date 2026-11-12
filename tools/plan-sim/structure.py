"""What makes a plan fragile: how far machines can outrun their plan, how many items are balanced exactly and made
only as one of several products, and how much of the plan is one loop.   structure.py <run>/plan_rebalanced.json"""
import json, sys, collections
p = json.load(open(sys.argv[1]))
def base(n): return n.split('@')[0]
M = p['machines']
# per recipe: products and ingredients with rates (items and fluids together)
prod = collections.defaultdict(lambda: collections.defaultdict(float)); cons = collections.defaultdict(lambda: collections.defaultdict(float))
count = collections.Counter(); crafts = collections.Counter(); maxc = collections.Counter()
T = p['terminals']
for t in T:
    if t['role'] == 'PRODUCER': prod[M[t['machine']]['recipe']][t['item']] += t['rate']
    elif t['role'] == 'CONSUMER' and 'machine' in t: cons[M[t['machine']]['recipe']][t['item']] += t['rate']
for m in M: count[m['recipe']] += 1; crafts[m['recipe']] += m['crafts']; maxc[m['recipe']] += m['maxCrafts']
recipes = sorted(count)
print('machines', len(M), 'recipes', len(recipes))
# S1 capacity over plan
ratio = sorted(maxc[r] / crafts[r] for r in recipes if crafts[r] > 0)
def q(a, f): return a[min(len(a) - 1, int(f * len(a)))]
print('S1 capacity / planned rate per recipe: median %.1f  p25 %.1f  p75 %.1f  p90 %.1f;  >=2x: %d  >=10x: %d  >=100x: %d of %d' % (
    q(ratio, .5), q(ratio, .25), q(ratio, .75), q(ratio, .9), sum(x >= 2 for x in ratio), sum(x >= 10 for x in ratio), sum(x >= 100 for x in ratio), len(ratio)))
# S2 coupling
np_ = collections.Counter(len(prod[r]) for r in recipes)
print('S2 products per recipe:', dict(sorted(np_.items())), ' multi-product recipes: %d (%.0f%%)' % (sum(v for k, v in np_.items() if k > 1), 100 * sum(v for k, v in np_.items() if k > 1) / len(recipes)))
makers = collections.defaultdict(dict); users = collections.defaultdict(dict)
for r in recipes:
    for i, v in prod[r].items(): makers[i][r] = v
    for i, v in cons[r].items(): users[i][r] = v
items = sorted(set(makers) | set(users))
out = {k: v for k, v in p.get('outputs', {}).items()}
print('items', len(items), ' made in plan', len(makers), ' raw', len([i for i in items if i not in makers]))
print('   items with >1 maker recipe: %d ; with >1 user recipe: %d' % (sum(len(v) > 1 for v in makers.values()), sum(len(v) > 1 for v in users.values())))
# S3 knife edge: an item whose supply cannot follow its demand by itself
kn = collections.Counter(); ex = collections.defaultdict(list)
for i in makers:
    sup = sum(makers[i].values()); use = sum(users.get(i, {}).values()); surplus = sup - use
    solo = [r for r in makers[i] if len(prod[r]) == 1]         # makers that make only this
    multi = [r for r in makers[i] if len(prod[r]) > 1]
    if not users.get(i): k = 'only leaves (by-product or target)'
    elif surplus > 1e-6 * max(1, sup) + 1e-9: k = 'used, with planned surplus leaving'
    elif not multi: k = 'exact, made only by single-product makers (self-regulating)'
    elif solo: k = 'exact, by-product supply plus a dedicated maker'
    else: k = 'exact, made ONLY as one of several products (knife edge)'
    kn[k] += 1; ex[k].append(i)
print('S3 items by how their supply meets demand:')
for k, v in kn.most_common(): print('   %4d  %s   e.g. %s' % (v, k, ', '.join(ex[k][:6])))
# share of machines whose every product is exact (nothing can leave)
# S4 loops
g = collections.defaultdict(set)
for i in makers:
    for a in makers[i]:
        for b in users.get(i, {}): g[a].add(b)
sys.setrecursionlimit(100000)
idx = {}; low = {}; st = []; on = set(); sccs = []; n = [0]
def sc(v):
    idx[v] = low[v] = n[0]; n[0] += 1; st.append(v); on.add(v)
    for w in g[v]:
        if w not in idx: sc(w); low[v] = min(low[v], low[w])
        elif w in on: low[v] = min(low[v], idx[w])
    if low[v] == idx[v]:
        c = []
        while True:
            w = st.pop(); on.discard(w); c.append(w)
            if w == v: break
        sccs.append(c)
for r in recipes:
    if r not in idx: sc(r)
big = sorted((len(c) for c in sccs), reverse=True)
print('S4 loops: recipes in a loop with others: %d of %d; biggest loops: %s' % (sum(x for x in big if x > 1), len(recipes), big[:8]))
bigc = max(sccs, key=len)
print('   machines in the biggest loop: %d' % sum(count[r] for r in bigc))
json.dump({'big': bigc}, open(sys.argv[2], 'w')) if len(sys.argv) > 2 else None
