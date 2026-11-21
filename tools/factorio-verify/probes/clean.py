"""clean.py: delete the check's temporary game folders that no live process uses."""
import os, shutil, subprocess, tempfile, re
T = tempfile.gettempdir()
assert T.startswith('/var/folders/') and T.endswith('/T'), T
live = set(re.findall(r'factorio-verify-[a-z0-9_]{6,}', subprocess.run(['ps','-axo','command'],capture_output=True,text=True).stdout))
n = 0
for name in sorted(os.listdir(T)):
    if not name.startswith('factorio-verify-') or name in live: continue
    p = os.path.join(T, name)
    assert os.path.dirname(p) == T and os.path.isdir(p) and not os.path.islink(p), p
    shutil.rmtree(p, ignore_errors=True); n += 1
print('removed', n, 'temporary game folders; live', len(live))