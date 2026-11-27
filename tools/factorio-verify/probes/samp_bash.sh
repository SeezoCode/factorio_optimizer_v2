#!/bin/bash
# samp.sh <lua probe> <run...>: every 2 minutes, the probe on every live run, appended to out/samp2/<run>.jsonl;
# ends when none of the runs is live. Run from anywhere.
S=$(cd "$(dirname "$0")" && pwd)
cd "$S/../../.."
probe=$1; shift
mkdir -p $S/out/samp2
while true; do
  live=0
  for d in "$@"; do
    if pgrep -f "verify.py solves/$d " >/dev/null; then
      live=1
      out=$(timeout 60 python3 -I $S/lua.py $d $probe 2>/dev/null)
      [[ $out == \{* ]] && printf "%s\n" "$out" >> $S/out/samp2/$d.jsonl
    fi
  done
  [ $live = 0 ] && exit 0
  sleep 120
done
