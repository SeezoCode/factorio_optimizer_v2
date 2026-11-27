#!/usr/bin/env bash
# go.sh <new run> <run to copy the layout from> <verify flags...>
# Copies a layout into solves/<new run> and starts the in-game check on it in the background
# (solves/<new run>/verify.out; the command line in solves/<new run>/cmd.txt). Paths for the cloud container's game
# (docs/CLOUD_SETUP.md) unless FACTORIO / FACTORIO_USER_DIR are set.
cd "$(dirname "$0")/../.."
name=$1; src=$2; shift 2
[ -e solves/$name ] && { echo "exists: $name"; exit 1; }
[ -e solves/$src/test_output_string.txt ] || { echo "no layout in solves/$src"; exit 1; }
export FACTORIO=${FACTORIO:-/opt/factorio/bin/x64/factorio} FACTORIO_USER_DIR=${FACTORIO_USER_DIR:-/opt/factorio-user}
mkdir solves/$name
for f in best_output_string.txt blocks.json plan.json plan_rebalanced.json ports.json test_feeds.txt test_output_string.txt tuning.json; do [ -e solves/$src/$f ] && cp solves/$src/$f solves/$name/; done
[ -d solves/$src/resume ] && cp -R solves/$src/resume solves/$name/
# a single-block run has no resume/: its job (written by solve_queue.sh) goes there, where verify.py and the probes look
[ -e solves/$name/resume/job.json ] || { mkdir -p solves/$name/resume; cp solves/jobs/$src.json solves/$name/resume/job.json; }
echo "$src $*" > solves/$name/cmd.txt
# Games at a lower priority than solves (a stitch works to wall-clock deadlines and starves under contention).
setsid nohup nice -n 10 python3 tools/factorio-verify/verify.py solves/$name "$@" > solves/$name/verify.out 2>&1 < /dev/null &
echo "started $name"
