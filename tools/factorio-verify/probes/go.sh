#!/bin/zsh
# go.sh <new run> <run to copy the layout from> <verify flags...>
cd "$(dirname "$0")/../../.."
name=$1; src=$2; shift 2
[ -e solves/$name ] && { echo "exists: $name"; exit 1; }
mkdir solves/$name
for f in best_output_string.txt blocks.json plan.json plan_rebalanced.json ports.json test_feeds.txt test_output_string.txt tuning.json; do [ -e solves/$src/$f ] && cp solves/$src/$f solves/$name/; done
cp -R solves/$src/resume solves/$name/
nohup python3 tools/factorio-verify/verify.py solves/$name "$@" > solves/$name/verify.out 2>&1 &
echo "started $name"