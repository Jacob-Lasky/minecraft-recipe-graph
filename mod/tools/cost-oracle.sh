#!/bin/sh
#
# Run the java cost model and the python one over the SAME graph and diff every price.
#
# This is the verification behind the port. It is stronger than a fixture digest, because it
# compares all 161,514 prices and all 504 machine verdicts individually rather than hashing
# them -- when it disagrees it says WHICH key, which is the difference between a finding and
# a red light.
#
# Usage, from the repository root:
#
#   mod/tools/cost-oracle.sh <graph.json> <output-dir> [scenario.json]
#
# With a scenario -- a plan fixture, or a bare scenario document -- both sides seed the cost
# model from it, which extends the diff to cover `have`, `freeSource`, `token`,
# `dimensionGated` and `emcAvailable`. Without one, no world state is applied and the diff
# covers the arithmetic alone.
#
# Environment: JDK8 (a Java 8 home), GSON (path to the gson 2.8.0 jar), PYTHON (default
# `python3`), XMX (default 4g).
#
# PRICES ARE COMPARED AS THE HEX OF THEIR 64-BIT PATTERN, NEVER AS DECIMAL TEXT. Measured:
# 1,966 of 6,012 doubles format differently between python's repr() and java's
# Double.toString() while being bit-identical, so a textual diff reports a third of the pack
# as broken and is wrong every time. See JsonFixtures for the full measurement.
#
# WHY JAVA 8 rather than the build JDK: Minecraft 1.12.2 runs on 8, and this is the
# arithmetic the game will execute. log1p was the one real risk here, since Java does not
# require it to be correctly rounded -- measured across 6,011 values spanning the build-cost
# range, Math.log1p agrees with python bit for bit on both 8 and 25.
set -e

GRAPH=${1:?usage: cost-oracle.sh <graph.json> <output-dir> [scenario.json]}
OUT=${2:?usage: cost-oracle.sh <graph.json> <output-dir> [scenario.json]}
SCENARIO=${3:-}
JDK8=${JDK8:?set JDK8 to a Java 8 home}
GSON=${GSON:?set GSON to the gson 2.8.0 jar}
PYTHON=${PYTHON:-python3}
XMX=${XMX:-4g}

HERE=$(cd "$(dirname "$0")/../.." && pwd)
CLASSES=$(mktemp -d)
mkdir -p "$OUT"

"$JDK8/bin/javac" -nowarn -d "$CLASSES" -cp "$GSON" \
  $(find "$HERE/mod/src/main/java/io/github/jacoblasky/recipedump/graph" \
         "$HERE/mod/src/main/java/io/github/jacoblasky/recipedump/plan" -name '*.java') \
  "$HERE/mod/src/test/java/io/github/jacoblasky/recipedump/plan/CostOracleHarness.java" \
  "$HERE/mod/src/test/java/io/github/jacoblasky/recipedump/plan/ScenarioInputs.java"

echo "===== java ====="
"$JDK8/bin/java" -Xmx"$XMX" -cp "$CLASSES:$GSON" \
  io.github.jacoblasky.recipedump.plan.CostOracleHarness "$GRAPH" "$OUT/java" $SCENARIO

echo "===== python ====="
PYTHONPATH="$HERE" "$PYTHON" "$HERE/mod/tools/cost_oracle_dump.py" \
  "$GRAPH" "$OUT/python" $SCENARIO

echo "===== diff ====="
status=0
for kind in cost machines; do
  if cmp -s "$OUT/python.$kind.tsv" "$OUT/java.$kind.tsv"; then
    echo "$kind: IDENTICAL ($(wc -l < "$OUT/java.$kind.tsv") rows)"
  else
    echo "$kind: $(diff "$OUT/python.$kind.tsv" "$OUT/java.$kind.tsv" | grep -c '^[<>]') differing lines"
    diff "$OUT/python.$kind.tsv" "$OUT/java.$kind.tsv" | head -20
    status=1
  fi
done
exit $status
