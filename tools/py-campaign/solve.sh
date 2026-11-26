#!/usr/bin/env bash
# solve.sh <suffix> <margin> <threads> <science...>
# Solves jobs/py_sciences/<science>.json one after another into solves/<science>_<suffix>, with the planner margin
# and thread count given, no PNGs (FO_NO_IMAGES=1). Each run's log: solves/<science>_<suffix>/solve.log.
# JOB_PATCH='{"blocks": {...}}' (environment) is merged into each job, dicts key by key.
cd "$(dirname "$0")/../.."
suffix=$1; margin=$2; threads=$3; shift 3
export FO_NO_IMAGES=1 JAVA_HOME=$(ls -d /opt/jdk/jdk-25* | head -1)
mkdir -p solves/jobs
for s in "$@"; do
  out=solves/${s}_${suffix}
  [ -e "$out/best_output_string.txt" ] && { echo "done already: $out"; continue; }
  job=solves/jobs/${s}_${suffix}.json
  python3 - "$s" "$margin" "$threads" "$job" <<'PY'
import json, sys
s, margin, threads, job = sys.argv[1], float(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
j = json.load(open(f"jobs/py_sciences/{s}.json"))
j["search"]["threads"] = threads
if margin > 0:
    j.setdefault("tuning", {}).setdefault("planner", {})["margin"] = margin
import os
def merge(a, b):
    for k, v in b.items():
        if isinstance(v, dict) and isinstance(a.get(k), dict): merge(a[k], v)
        else: a[k] = v
merge(j, json.loads(os.environ.get("JOB_PATCH") or "{}"))
json.dump(j, open(job, "w"), indent=1)
PY
  mkdir -p "$out"
  echo "$(date -u +%FT%TZ) start $out"
  nice -n 5 build/install/factorio_optimizer_v2/bin/factorio_optimizer_v2 --config="$job" --out="$out" --plain > "$out/solve.log" 2>&1
  echo "$(date -u +%FT%TZ) end $out exit $? $(tail -n 1 $out/solve.log)"
done
