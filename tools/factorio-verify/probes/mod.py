"""mod.py <run> [item ...]: what the factory-flow mod is doing in a live --flow-mod run."""
import json, sys, subprocess, os
S = os.path.dirname(os.path.abspath(__file__)); d = sys.argv[1]; want = set(sys.argv[2:])
r = json.loads(subprocess.run(['python3', '-I', S + '/lua.py', d, S + '/mod.lua'], capture_output=True, text=True).stdout)
print(d, 'hour %.1f' % r['h'], r['status'], r.get('info'))
sh = r.get('shares') or []
if isinstance(sh, dict): sh = list(sh.values())
for g in sorted(sh, key=lambda g: g['item']):
    if want and g['item'] not in want: continue
    nets = g['nets'] if isinstance(g['nets'], list) else list(g['nets'].values())
    tot = sum(n['sum'] for n in nets) or 1
    if not want and max(n['sum'] / tot - n['share'] for n in nets) < 0.05: continue
    print(' %s: ' % g['item'] + ' | '.join('plan %.1f%% got %.1f%% (%d) bal %d%s%s%s' % (100 * n['share'], 100 * n['sum'] / tot, n['sum'], n['bal'], ' HELD' if n['hold'] else '', ' W' if n['wait'] else '', '' if n['free'] else ' blocked') for n in sorted(nets, key=lambda n: -n['share'])))