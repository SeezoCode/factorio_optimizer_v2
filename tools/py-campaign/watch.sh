#!/usr/bin/env bash
# watch.sh: one line per new event of the campaign (for a Monitor): a solve queue logs an end, a check run reaches its
# verdict or throws, the scheduler or a solve queue is gone. What was reported is kept in solves/.watch_seen, so a
# re-armed watch does not report it again.
cd "$(dirname "$0")/../.."
seen=solves/.watch_seen; touch $seen
while true; do
  for q in solves/queue_*.log; do
    grep -h "end solves" "$q" 2>/dev/null | while read -r l; do grep -qxF "$l" $seen || { echo "SOLVE: $l" | cut -c1-220; echo "$l" >> $seen; }; done
  done
  for f in solves/*/verify.out; do
    k="$f $(stat -c %Y "$(dirname "$f")/cmd.txt" 2>/dev/null)"  # a run name used again is a new run
    if grep -qE "^(PASS|FAIL)" "$f" && ! grep -qxF "$k" $seen && ! grep -qxF "$f" $seen; then echo "RUN: $f $(grep -E '^(PASS|FAIL)' "$f" | head -1)"; echo "$k" >> $seen; fi
    if grep -qE "Traceback|^Error|creating the game failed" "$f" && ! grep -qxF "ERR $f" $seen; then echo "RUN ERROR: $f $(grep -E 'Traceback|^Error|creating the game failed' "$f" | head -1 | cut -c1-150)"; echo "ERR $f" >> $seen; fi
  done
  if ! pgrep -f "launch.py sched" >/dev/null; then grep -qxF SCHEDDEAD $seen || { echo "SCHEDULER DEAD"; echo SCHEDDEAD >> $seen; }; else sed -i '/^SCHEDDEAD$/d' $seen; fi
  sleep 60
done
