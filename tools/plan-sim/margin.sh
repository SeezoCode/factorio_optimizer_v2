#!/bin/zsh
# margin.sh <plan.json> <target> <out prefix> <delta> [keep=1] : rates with a margin -> <prefix>_<delta>.caps.json
plan=$1; target=$2; pre=$3; d=$4; extra=$5; here=${0:a:h}; root=${here:h:h}; lp=${pre}_$d.json
solve() { (cd $root && LPFILE=$1 ./gradlew test --tests LpFileTest --rerun -q -i 2>&1 | grep "LPFILE" | grep -v OPTIMAL | sed 's/.*lp\///') }
python3 $here/margin.py write1 $plan $target $d $lp fresh=1 $extra
for k in 1 2 3 4 5 6 7 8; do
  solve $lp; n=$(python3 $here/margin.py collect $plan $target $d $lp $extra)
  [ "$n" = "0" ] && break
  python3 $here/margin.py write1 $plan $target $d $lp $extra
done
python3 $here/margin.py write2 $plan $target $d $lp $extra | cut -c1-500
solve ${lp%.json}.2.json
python3 $here/margin.py read $plan $target $d $lp out=${pre}_$d.caps.json $extra
