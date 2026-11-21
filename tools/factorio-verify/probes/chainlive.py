"""chainlive.py <run>: from the pack down, what each stopped machine lacks right now (live run)."""
import json, sys, subprocess, os
S = os.path.dirname(os.path.abspath(__file__)); d = sys.argv[1]
subprocess.run(['python3', '-I', S + '/peek2.py', d, f'solves/{d}/plan_rebalanced.json', S + f'/out/{d}_st.json'], capture_output=True)
st = json.load(open(S + f'/out/{d}_st.json')); rec = st['rec']
p = json.load(open(f'solves/{d}/plan_rebalanced.json'))
makers = {}
for m in p['machines']:
    for k in m['products']: makers.setdefault(k.split('#')[0], set()).add(m['recipe'].split('#')[0])
seen = set()
def go(r, dep=0):
    if r in seen or dep > 9:
        return
    seen.add(r)
    t = rec.get(r)
    if not t: print('  ' * dep + r + ' ?'); return
    print('  ' * dep + f"{r}: {list(t['st'].keys())} lacks {t['lack']} out {t['out']}"[:230])
    for it in t['lack']:
        for mk in sorted(makers.get(it.strip('[]'), [])): go(mk, dep + 1)
print(d, 'hour', round(st['tick'] / 216000, 1))
go(sys.argv[2] if len(sys.argv) > 2 else 'logistic-science-pack')