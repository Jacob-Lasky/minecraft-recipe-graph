#!/bin/sh
#
# Run everything, including the two gates that skip themselves into invisibility.
#
#   tools/check.sh                  # python + java + the oracle gate, ~14 min
#   tools/check.sh --python         # ~11 min with an oracle present, ~17s without
#   tools/check.sh --java           # ~3 min
#   GRADLE_CACHE=<dir> tools/check.sh
#
# ELEVEN MINUTES FOR THE PYTHON SUITE IS CORRECT, NOT A HANG. Without an oracle it is 17
# seconds; with one, `tests/test_plan_fixtures.py` regenerates every fixture and `cost.estimate`
# is about two minutes per distinct priced scenario on this pack. That is the price of the
# assertion actually running, and it is why it is opt-in rather than always-on. Use `--java`
# during a Java change; run the whole thing before you merge.
#
# WHY THIS EXISTS RATHER THAN A LINE IN THE README. Verifying this repository properly means a
# twelve-line `docker run` with four bind mounts, two of which are wrong by default:
#
#   - The mount source is resolved on the UnRAID HOST, so `/coding/x` has to become
#     `/mnt/user/misc/coding/x` or the `-v` silently succeeds against an EMPTY directory.
#   - It must mount the REPOSITORY ROOT, not `mod/`. A `mod/`-only mount compiles and then
#     fails `DigestFixtureTest` with a bare IOException, because that test reads
#     `tests/fixtures/` from outside `mod/`. That has cost a full cold build.
#
# Assembling it by hand every time is how it ends up assembled wrong.
#
# THE REAL REASON, THOUGH, IS THAT TWO ASSERTIONS SKIP UNLESS AN ENVIRONMENT VARIABLE IS SET,
# AND A SKIP READS EXACTLY LIKE A PASS. `PlanFixtureTest.everyFixturePlansExactlyAsThePython`
# `OracleDoes` is the assertion that the ported Java planner still reproduces the Python
# oracle across every plan fixture -- the entire correctness proof of the port -- and a plain
# `./gradlew test` reports it `SKIPPED` in a screen of `PASSED` lines and still prints BUILD
# SUCCESSFUL. `tests/test_plan_fixtures.py` has the same shape on the Python side.
#
# So this passes the oracle in, and then COUNTS THE SKIPS AND SAYS SO. A run that quietly
# skipped its most important assertion is the failure this file exists to prevent, and
# "the tests pass" is not evidence the port still agrees.

set -eu

cd "$(dirname "$0")/.."
ROOT=$(pwd)
ORACLE=${RECIPEGRAPH_ORACLE:-/coding/.recipegraph-build/graph-oracle.json}
PACK_MODS=${PACK_MODS:-/coding/.recipegraph-build/deps}
GRADLE_CACHE=${GRADLE_CACHE:-/coding/.recipegraph-build/gradle-cache}
BUILD_DIR=$(dirname "$ORACLE")

want_python=1
want_java=1
case "${1:-}" in
    --python) want_java=0 ;;
    --java) want_python=0 ;;
    "") ;;
    *) echo "usage: tools/check.sh [--python|--java]" >&2; exit 2 ;;
esac

# Host paths, because a bind mount source is resolved on the host, not on this container.
host_path() {
    echo "$1" | sed 's|^/coding|/mnt/user/misc/coding|'
}

# SWEEP STALE BUILDS FIRST. `mod/build/libs` accumulates a jar per build, and since the jar
# stopped being tracked that directory is what `test_dist_jar` reads. Three leftover versions
# produce three failures that read as a packaging defect; two people diagnosed it that way
# before anyone noticed they were just old files. Only jars for versions OTHER than the
# current one go, so a legitimately stale current-version jar still fails loudly, which is the
# assertion worth keeping.
version=$(sed -n 's/^mod_version=//p' mod/gradle.properties)
if [ -d mod/build/libs ] && [ -n "$version" ]; then
    swept=$(find mod/build/libs -name 'mc-recipe-dump-*.jar' \
        ! -name "mc-recipe-dump-$version.jar" ! -name "mc-recipe-dump-$version-dev.jar" \
        -print -delete 2>/dev/null | wc -l)
    [ "$swept" -eq 0 ] || echo "swept $swept stale jar(s) from mod/build/libs"
fi

fail=0

PYLOG=$(mktemp)
trap 'rm -f "$PYLOG"' EXIT

# COUNT THE PYTHON SKIPS AND SAY SO, exactly as the java arm below already does. `unittest -q`
# prints `OK (skipped=N)` and exits 0, so a run whose most important assertions all skipped is
# indistinguishable from a clean one in this script's output -- which is the failure this file
# exists to prevent, one level up from the gate it was written for.
report_python_skips() {
    skipped=$(sed -n 's/^OK (skipped=\([0-9]*\))$/\1/p' "$PYLOG")
    [ -n "$skipped" ] || skipped=0
    echo "python: $skipped skipped"
    if [ "$skipped" -gt 0 ] && [ -n "$jar_warning" ]; then
        echo "!! those skips include test_dist_jar; build the jar or they prove nothing"
    fi
}

# SAY WHEN THERE IS NO JAR TO CHECK, for the same reason as the oracle warning below.
# `test_dist_jar` calls `skipTest` when `mod/build/libs` holds no jar, so an unbuilt worktree
# turns its eleven assertions into eleven silent skips -- and `unittest` exits 0 on a skip, so
# this script printed "all green" over them. Measured, not imagined: the same tree gave three
# failures with stale jars present and a clean "all green" after `rm -rf mod/build/libs`, and
# the second reads as the better result. Deleting the artifact an assertion inspects is a way
# of passing it.
jar_warning=""
if [ -n "$version" ] && [ ! -f "mod/build/libs/mc-recipe-dump-$version.jar" ]; then
    jar_warning="no mc-recipe-dump-$version.jar in mod/build/libs -- test_dist_jar will SKIP"
fi

if [ "$want_python" -eq 1 ]; then
    echo "== python =="
    [ -z "$jar_warning" ] || echo "!! $jar_warning"
    if [ -f "$ORACLE" ]; then
        RECIPEGRAPH_ORACLE="$ORACLE" python3 -m unittest discover -s tests -q 2>"$PYLOG" || fail=1
        report_python_skips
    else
        echo "!! no oracle at $ORACLE -- the fixture regeneration test will SKIP."
        echo "!! build one with: python3 -m recipegraph.cli build --dump-dir data/mc-recipe-dump \\"
        echo "!!                  --graph $ORACLE"
        python3 -m unittest discover -s tests -q 2>"$PYLOG" || fail=1
        report_python_skips
    fi
fi

if [ "$want_java" -eq 1 ]; then
    echo "== java =="
    if [ ! -f "$ORACLE" ]; then
        echo "!! no oracle at $ORACLE -- THE GOLDEN PLAN GATE WILL NOT RUN."
        echo "!! Everything else will pass and prove nothing about the port."
    fi
    log=$(mktemp)
    # 8g, not 4g: the gate holds the whole 121 MB graph in the test JVM. 4g dies partway
    # through with an OOM that looks like a hang.
    docker run --rm --user 99:100 --memory=8g --memory-swap=8g \
        -v "$(host_path "$ROOT")":/repo \
        -v "$(host_path "$PACK_MODS")":/deps:ro \
        -v "$(host_path "$BUILD_DIR")":/build:ro \
        -v "$(host_path "$GRADLE_CACHE")":/gradle \
        -e GRADLE_USER_HOME=/gradle \
        -e RECIPEGRAPH_ORACLE="/build/$(basename "$ORACLE")" \
        -w /repo/mod eclipse-temurin:25-jdk \
        ./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx6g -Ppack_mods=/deps \
            cleanTest test > "$log" 2>&1 || fail=1

    passed=$(grep -c ' PASSED$' "$log" || true)
    failed=$(grep -c ' FAILED$' "$log" || true)
    skipped=$(grep -cE '^[A-Za-z].* > .* SKIPPED$' "$log" || true)
    echo "java: $passed passed, $failed failed, $skipped skipped"
    grep -E '^[A-Za-z].* > .* (FAILED|SKIPPED)$' "$log" || true

    # A SKIPPED gate is reported as a problem, not as a detail. This is the whole point.
    if grep -q 'everyFixturePlansExactlyAsThePythonOracleDoes SKIPPED' "$log"; then
        echo "!! THE GOLDEN PLAN GATE SKIPPED. The Java port was NOT checked against the oracle."
        fail=1
    fi
    [ "$fail" -eq 0 ] || echo "full log: $log"
fi

if [ "$fail" -eq 0 ]; then
    echo "== all green =="
else
    echo "== FAILURES ==" >&2
fi
exit "$fail"
