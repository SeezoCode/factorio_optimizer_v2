"""sum.py <run> [window hours]: a live or finished run in a few lines: packs by hour, recipes at their rate in the last
hours, carriers and starters handed in, what the overflow took."""
import json, sys, os, subprocess, collections
S = os.path.dirname(os.path.abspath(__file__))
d = sys.argv[1]; W = int(sys.argv[2]) if len(sys.argv) > 2 else 4
rep = f'solves/{d}/verify_report.json'
if os.path.exists(rep):
    r = json.load(open(rep)); tl = r['timeline']; h = tl[-1]['h'] if tl else 0; phase = 'finished'; now = None; rv = {}
    for b in (r.get('real_voids') or {}).get('built', []): rv[b['fluid']] = rv.get(b['fluid'], 0) + b['voided']
else:
    out = subprocess.run(['python3', '-I', S + '/lua.py', d, S + '/tl.lua'], capture_output=True, text=True).stdout
    try: r = json.loads(out)
    except Exception: print(d, 'no reply', out[:200]); sys.exit(0)
    tl = r.get('tl') or []; h = r['h']; phase = r['phase']; now = r['now']; rv = r.get('rv') or {}
    if isinstance(tl, dict): tl = [tl[k] for k in sorted(tl, key=int)]
    tl = tl + [{'h': h, 'pf': now['pf'], 'seeded': now.get('seeded') or {}, 'voided': now.get('voided') or {}, 'boot': now.get('boot') or {}}]
plan = json.load(open(f'solves/{d}/plan_rebalanced.json' if os.path.exists(f'solves/{d}/plan_rebalanced.json') else f'solves/{d}/plan.json'))
def base(n): return n.split('#')[0].split('@')[0]
rate = collections.defaultdict(float)
for m in plan['machines']: rate[base(m['recipe'])] += m['crafts']
target = json.load(open(f'solves/{d}/resume/job.json'))['targets'][0]['name']
pk = [t['pf'].get(target, 0) for t in tl]
per = [b - a for a, b in zip([0] + pk, pk)]
print('%s: hour %.1f (%s); pack crafts %d, due %.0f; by hour %s' % (d, h, phase, pk[-1] if pk else 0, h * rate[target] * 3600, ' '.join(str(x) for x in per)))
if len(tl) > W:
    a, b = tl[-W - 1], tl[-1]; secs = (b['h'] - a['h']) * 3600
    rel = {k: (b['pf'].get(k, 0) - a['pf'].get(k, 0)) / secs / rate[k] for k in rate if rate[k] * secs >= 2}
    print('   last %.1f h: packs %d of %.1f due (%d%%); recipes due two crafts or more: %d, at 90%% or more %d, over 150%% %d, over 300%% %d, under 50%% %d, at nothing %d' % (
        secs / 3600, pk[-1] - pk[-W - 1], secs * rate[target], round(100 * (pk[-1] - pk[-W - 1]) / (secs * rate[target])), len(rel),
        sum(x >= .9 for x in rel.values()), sum(x > 1.5 for x in rel.values()), sum(x > 3 for x in rel.values()), sum(x < .5 for x in rel.values()), sum(x == 0 for x in rel.values())))
    def diff(key, n=8):
        da = a.get(key) or {}; db = b.get(key) or {}
        t = sorted(((k, db[k] - da.get(k, 0)) for k in db), key=lambda kv: -kv[1])
        return ', '.join('%s %d' % (k, v) for k, v in t[:n] if v >= 1), sum(v for k, v in t if not k.startswith('[')), sum(v for k, v in t if k.startswith('['))
    s, si, sf = diff('seeded'); v, vi, vf = diff('voided', 10); bt, bi, _ = diff('boot')
    print('   in those hours: carriers handed in: %s | starters: %s | overflow took %d items (%.2f/s), %d fluid: %s' % (bt or 'none', s or 'none', vi, vi / secs, vf, v))
bt = tl[-1].get('boot') or {}
print('   carriers handed in so far: %s; real fluid voids destroyed %s' % (', '.join('%s %d' % kv for kv in bt.items()) or 'none', (', '.join('%s %dk' % (k, v / 1000) for k, v in sorted(rv.items(), key=lambda kv: -kv[1])[:6]) or 'nothing') if rv else 'n/a'))