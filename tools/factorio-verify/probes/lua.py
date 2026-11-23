"""lua.py <run or rcon-port:password> <lua file> : run Lua in the check's mod of a live run; print the reply."""
import subprocess, sys, re, json
import importlib.util
args = sys.argv[1:]; sys.argv = ['verify.py']
spec = importlib.util.spec_from_file_location('verify', 'tools/factorio-verify/verify.py'); v = importlib.util.module_from_spec(spec); spec.loader.exec_module(v)
want = args[0]
ps = subprocess.run(['ps', '-axo', 'pid,ppid,command'], capture_output=True, text=True).stdout.splitlines()
procs = {}
for l in ps:
    m = re.match(r'\s*(\d+)\s+(\d+)\s+(.*)', l)
    if m: procs[int(m.group(1))] = (int(m.group(2)), m.group(3))
target = None
if re.match(r'^\d+:\S+$', want): target = (int(want.split(':')[0]), want.split(':', 1)[1])
for pid, (ppid, cmd) in procs.items():
    if '--rcon-bind' in cmd and ppid in procs and ('solves/' + want + ' ') in procs[ppid][1] + ' ':
        target = (int(re.search(r'--rcon-bind 127\.0\.0\.1:(\d+)', cmd).group(1)), re.search(r'--rcon-password (\S+)', cmd).group(1))
assert target
LUA = '\n'.join(l for l in open(args[1]).read().split('\n') if not l.strip().startswith('--'))
for attempt in range(4):
    r = v.Rcon(target[0], target[1]); res = r.command('/c __factorio-optimizer-verify__ ' + ' '.join(LUA.split('\n'))); r.close()
    if res.strip(): break
print(res)