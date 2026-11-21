"""item.py <run> <item>: makers and users of an item in a live run: crafts against plan, status, what each lacks."""
import json, sys, subprocess, os
S = os.path.dirname(os.path.abspath(__file__)); d, item = sys.argv[1], sys.argv[2]
subprocess.run(['python3', '-I', S + '/peek2.py', d, f'solves/{d}/plan_rebalanced.json', S + f'/out/{d}_st.json'], capture_output=True)
st = json.load(open(S + f'/out/{d}_st.json')); rec = st['rec']; h = st['tick'] / 216000
p = json.load(open(f'solves/{d}/plan_rebalanced.json'))
def b(n): return n.split('#')[0].split('@')[0]
plan = {}
for m in p['machines']:
    r = b(m['recipe']); e = plan.setdefault(r, {'crafts': 0, 'n': 0, 'in': {}, 'out': {}})
    e['crafts'] += m['crafts']; e['n'] += 1
    for k, v in m.get('demand', {}).items(): e['in'][b(k)] = e['in'].get(b(k), 0) + v
    for k, v in m.get('products', {}).items(): e['out'][b(k)] = e['out'].get(b(k), 0) + v
print(d, 'hour %.1f' % h, item, 'on belts/in machines:', (st.get('stock') or {}).get(item))
for kind, key in (('maker', 'out'), ('user', 'in')):
    for r, e in sorted(plan.items(), key=lambda kv: -kv[1][key].get(item, 0)):
        if item in e[key]:
            t = rec.get(r, {})
            due = e['crafts'] * h * 3600
            print('  %-5s %-34s plan %7.1f/h of it, crafts %5d of %7.1f due (%3d%%) %s lacks %s out %s' % (kind, r, e[key][item] * 3600, t.get('pf', 0), due, 100 * t.get('pf', 0) / due if due else 0, list((t.get('st') or {}).keys()), t.get('lack'), t.get('out')))