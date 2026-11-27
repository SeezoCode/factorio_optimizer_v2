#!/usr/bin/env bash
# resume.sh <layout> [extra optimizer flags]: continue a factory-of-blocks solve in solves/<layout> with the current
# snapshot (/opt/fo/current): its saved split and finished blocks are kept, missing blocks searched, then it stitches.
# Logs start/end lines to solves/queue_resume.log in solve.sh's format, so launch.py sees the layout as solved.
cd "$(dirname "$0")/../.."
d=solves/$1; shift
export FO_NO_IMAGES=1 JAVA_HOME=$(ls -d /opt/jdk/jdk-25* | head -1)
echo "$(date -u +%FT%TZ) start $d (resume)" >> solves/queue_resume.log
/opt/fo/current/bin/factorio_optimizer_v2 --resume="$d" --out="$d" --plain "$@" > "$d/solve_resume.log" 2>&1
echo "$(date -u +%FT%TZ) end $d exit $? $(tail -n 1 $d/solve_resume.log)" >> solves/queue_resume.log
