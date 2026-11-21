#!/bin/zsh
# stop.sh <run...>: stop the check runs (verify.py and its game), then delete temp folders no live game uses.
S=$(cd "$(dirname "$0")" && pwd)
for d in "$@"; do for pid in $(pgrep -f "verify.py solves/$d "); do kids=$(pgrep -P $pid); kill $pid ${=kids}; done; done
sleep 10
python3 -I $S/clean.py
