"""sa.py [runs...]: from the loop samples (out/samp2/<run>.jsonl, samp.sh with ls.lua): pack crafts per game hour, and
each loop item's stock (on belts + in machines) at each whole hour."""
import json, sys, os, glob
S = os.path.dirname(os.path.abspath(__file__))
runs = sys.argv[1:] or sorted(os.path.basename(f)[:-6] for f in glob.glob(S + '/out/samp2/*.jsonl'))
ITEMS = ["seaweed", "fertilizer", "fish", "fish-egg", "filtration-media", "sea-sponge"]
PACKS = ("logistic-science-pack@", "py-science-pack-2@", "py-science-pack-1@")
for d in runs:
    rows = [json.loads(l) for l in open(f'{S}/out/samp2/{d}.jsonl') if l.startswith('{')]
    if not rows:
        continue
    byh = {}
    for o in rows:
        byh.setdefault(int(o['h']), o)   # first sample in each game hour
    hs = sorted(byh)
    def packs(o):
        return sum(m[2] for m in o['m'] if m[0].startswith(PACKS))
    def stock(o, it):
        n = sum(o['nets'].get(it, {}).values())
        for m in o['m']:
            for part in m[3].split(' '):
                if part.startswith(it + '<') and '=' in part:
                    n += int(part.split('=')[1])
                elif part.startswith(it + '>'):
                    n += int(part.split('>')[1])
        return n
    last = rows[-1]
    pk = [packs(byh[h]) for h in hs] + [packs(last)]
    print(f"{d:12} h {last['h']:.1f}  packs/h " + ' '.join(str(b - a) for a, b in zip(pk, pk[1:])))
    for it in ITEMS:
        print(f"   {it:17} " + ' '.join(str(stock(byh[h], it)) for h in hs) + f" | now {stock(last, it)}")
