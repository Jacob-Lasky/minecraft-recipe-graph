#!/bin/sh
#
# Re-run the #126 heap measurement. This script IS the methodology -- if the numbers in that
# PR are ever challenged, this is what reproduces them.
#
# Usage, from the repository root:
#
#   mod/tools/heap-gate.sh <graph.json>                 # the compact model
#   mod/tools/heap-gate.sh <graph.json> --naive         # the object-per-ingredient baseline
#   mod/tools/heap-gate.sh <graph.json> --interned      # the baseline with keys deduplicated
#   XMX=96m mod/tools/heap-gate.sh <graph.json>         # find the heap floor
#
# Environment: JDK8 (a Java 8 home), XMX, GC, ITERATIONS, GSON (path to gson jar), OUT.
#
# WHY IT COMPILES BY HAND INSTEAD OF GOING THROUGH GRADLE. Three reasons, all load-bearing:
#
#  1. JAVA 8, NOT THE BUILD JDK. Minecraft 1.12.2 runs on 8, and 8 has no compact strings --
#     every String costs two bytes per character. Measured on 17 or 25 the whole model
#     under-reports by roughly the size of its text, and the gate would be assessed against
#     a JVM nobody is running. Gradle's test task runs on the daemon's JDK.
#  2. EXACT JVM FLAGS. The retained figure is only meaningful under a COMPACTING collector,
#     because a concurrent collector's "used" includes garbage it has not reached. Gradle
#     forwards neither -Xmx nor -XX to the forked test JVM without editing build.gradle,
#     which is shared with other work.
#  3. A CLEAN BASELINE. The harness measures a post-GC heap delta, and a JUnit runner plus
#     Gradle's worker infrastructure is several megabytes of live objects sitting inside the
#     window being measured.
#
# The graph package imports nothing from Minecraft or Forge, which is what makes this
# possible at all. Keep it that way.
set -e

GRAPH=${1:?usage: heap-gate.sh <graph.json> [--naive|--interned] [--iterations N]}
shift

JDK8=${JDK8:?set JDK8 to a Java 8 home; gradle provisions one under \$GRADLE_USER_HOME/jdks}
GSON=${GSON:?set GSON to the gson 2.8.0 jar; gradle caches it under \$GRADLE_USER_HOME/caches}
XMX=${XMX:-6g}
GC=${GC:--XX:+UseSerialGC}
OUT=${OUT:-$(mktemp -d)}

HERE=$(cd "$(dirname "$0")/.." && pwd)

"$JDK8/bin/javac" -nowarn -d "$OUT" -cp "$GSON" \
  $(find "$HERE/src/main/java/io/github/jacoblasky/recipedump/graph" -name '*.java') \
  "$HERE/src/test/java/io/github/jacoblasky/recipedump/graph/HeapGateHarness.java" \
  "$HERE/src/test/java/io/github/jacoblasky/recipedump/graph/NaiveGraph.java"

exec "$JDK8/bin/java" -Xmx"$XMX" $GC -cp "$OUT:$GSON" \
  io.github.jacoblasky.recipedump.graph.HeapGateHarness "$GRAPH" "$@"
