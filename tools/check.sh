#!/bin/sh
#
# Run everything, including the two gates that skip themselves into invisibility.
#
#   tools/check.sh                  # python + java + the oracle gate, ~14 min
#   tools/check.sh --python         # ~11 min with an oracle present, ~17s without
#   tools/check.sh --java           # ~3 min
#   GRADLE_CACHE=<dir> tools/check.sh
#   GATE_LOCK= tools/check.sh       # opt out of the one-container-at-a-time gate
#
# IN THE BACKGROUND, `setsid nohup`, NEVER A BARE `&`:
#
#   setsid nohup tools/check.sh --java > /tmp/check.log 2>&1 < /dev/null &
#
# A bare `&` leaves this in the launching shell's process group and the whole group dies when
# that shell is reaped -- the run dies and the WRAPPER survives as a zombie, whose `etime` goes
# on climbing so every liveness check says it is fine. There are 23 defunct `check.sh` entries
# on this box as of 2026-08-08. Check `ps -o stat=,etime= -p <pid>` for a Z and `ps --ppid
# <pid>` for children; `/proc/<pid>` existing proves identity, never liveness. The same rule and
# the measurement behind it are in `harness/README.md`.
#
# IT TAKES THE CONTAINER GATE AROUND ITS CONTAINERS AND NOT AROUND ITSELF. Several agents share
# this host and three 8 GB JVMs against 20 GB free is a real problem, so `tools/gate.sh`
# serialises them -- but the python arm is eleven minutes that starts no container, and holding
# the lock across it would starve everyone else for nothing. The wait is announced and names
# the holder, because a script blocking silently is indistinguishable from a script that hung.
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
# THE DEFAULT IS THE GRAPH THE FIXTURES NAME, AND A PINNED FILENAME IS WHY THAT NEEDED SAYING.
# This defaulted to `graph-oracle.json` while #248 regenerated every plan fixture against
# `graph-oracle-248.json`, so the golden gate regenerated against a graph the fixtures did not
# come from and disagreed with itself. It went red on master for anyone running this with no
# environment set -- not a port defect, an oracle mismatch wearing one, and the most expensive
# possible disguise because `everyFixturePlansExactlyAsThePythonOracleDoes` is the assertion
# people trust most.
#
# THE CHECK BELOW IS THE POINT, NOT THIS FILENAME. A default that names one file is a second
# source of truth about which graph is current, and it goes stale the next time anyone
# regenerates -- which is exactly what happened. So the run now REFUSES when the oracle it is
# about to use is not the one the fixtures record, and says which file to pass instead. #281
# tracks making the selection schema-aware; until then, a wrong oracle fails loudly at the top
# rather than as a mystery in the Java arm twenty minutes later.
ORACLE=${RECIPEGRAPH_ORACLE:-/coding/.recipegraph-build/graph-oracle-248.json}
PACK_MODS=${PACK_MODS:-/coding/.recipegraph-build/deps}
GRADLE_CACHE=${GRADLE_CACHE:-/coding/.recipegraph-build/gradle-cache}
BUILD_DIR=$(dirname "$ORACLE")
# EXPORTED because `mod/tools/build-jar.sh` reads both from the environment, and it is invoked
# below as a plain command rather than with inline assignments so that it can take the
# container gate itself. See `tools/gate.sh` on why nesting the gate would deadlock.
export PACK_MODS GRADLE_CACHE

# REFUSE AN ORACLE THE FIXTURES DID NOT COME FROM, BEFORE SPENDING TWENTY MINUTES ON IT.
# The fixtures record the sha256 of the graph they were generated against, so the mismatch is
# checkable in a second and is otherwise invisible until the Java arm reports a plan diff --
# which reads as a port regression and is not one. Silent on any fixture that records no
# sha256, and on a missing oracle, because both are absence of evidence rather than evidence
# of mismatch; the arms below already fail loudly on a missing oracle for their own reasons.
if [ -f "$ORACLE" ]; then
    want=$(sed -n 's/.*"sha256": *"\([0-9a-f]\{64\}\)".*/\1/p' \
           tests/fixtures/plan/plan-in-stock.json 2>/dev/null | head -1)
    if [ -n "$want" ]; then
        have=$(sha256sum "$ORACLE" | cut -d' ' -f1)
        if [ "$want" != "$have" ]; then
            echo "!! THE ORACLE IS NOT THE ONE THE FIXTURES WERE GENERATED AGAINST."
            echo "!!   fixtures name : $want"
            echo "!!   $ORACLE"
            echo "!!   hashes to     : $have"
            echo "!! The golden gate would regenerate against this graph and disagree with the"
            echo "!! stored plans, which reads as a port regression and is not one. Pass"
            echo "!! RECIPEGRAPH_ORACLE=<the graph the fixtures name>, or regenerate the"
            echo "!! fixtures against this one with tools/make-java-fixtures.py."
            exit 1
        fi
    fi
fi

# One heavy container at a time on this host; `gated` blocks, announces the wait and names the
# holder. This script's own container is the java arm at the bottom.
. tools/gate.sh

want_python=1
want_java=1
# A no-argument run is the PRE-MERGE gate and behaves differently from the iteration flags:
# it builds a missing jar rather than warning about one. See the jar block below.
full_run=1
case "${1:-}" in
    --python) want_java=0; full_run=0 ;;
    --java) want_python=0; full_run=0 ;;
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
keep_pylog=0
# Keep the log when something failed, since that is exactly when its contents are wanted.
trap '[ "$keep_pylog" -eq 1 ] || rm -f "$PYLOG"' EXIT

# COUNT THE PYTHON SKIPS AND SAY SO, exactly as the java arm below already does. `unittest -q`
# prints `OK (skipped=N)` and exits 0, so a run whose most important assertions all skipped is
# indistinguishable from a clean one in this script's output -- which is the failure this file
# exists to prevent, one level up from the gate it was written for.
# AND SHOW THE FAILURES, because `unittest` writes ALL of its output to stderr and capturing
# it to count the skips silently swallowed the rest. A failing run then printed a skip count,
# nothing else, and `== FAILURES ==` at the very end with no indication of what failed -- which
# sent me looking for a broken container. Introduced by the fix for the skip-counting problem
# and caught by it happening: exactly the shape this file exists to prevent, in this file.
report_python_skips() {
    # BOTH summary forms. `unittest` writes `OK (skipped=N)` when everything passed and
    # `FAILED (failures=1, skipped=N)` when it did not, and matching only the first reported
    # "0 skipped" next to a summary line saying skipped=1 -- a tool for catching silent skips,
    # under-reporting skips.
    skipped=$(sed -n 's/.*skipped=\([0-9]*\).*/\1/p' "$PYLOG" | tail -1)
    [ -n "$skipped" ] || skipped=0
    echo "python: $skipped skipped"
    # ONLY WHEN THE JAR IS ABSENT, which is the state that makes `test_dist_jar` SKIP. A STALE
    # jar makes it FAIL instead, and attributing the oracle gate's skip to a jar that is sitting
    # right there sends the reader to rebuild something that is not the problem. Caught by
    # running the stale case: this line fired next to `skipped=1` where the 1 was the oracle.
    if [ "$skipped" -gt 0 ] && [ "$jar_state" = absent ]; then
        echo "!! those skips include test_dist_jar; build the jar or they prove nothing"
    fi
    # The summary always, and the failures themselves when there are any.
    grep -E '^(OK|FAILED|Ran )' "$PYLOG" || true
    if grep -qE '^(FAIL|ERROR):' "$PYLOG"; then
        echo "-- python failures --"
        grep -E '^(FAIL|ERROR):' "$PYLOG"
        echo "-- full python output: $PYLOG (kept because the run failed) --"
        keep_pylog=1
    fi
}

# SAY WHEN THERE IS NO JAR TO CHECK, for the same reason as the oracle warning below.
# `test_dist_jar` calls `skipTest` when `mod/build/libs` holds no jar, so an unbuilt worktree
# turns its eleven assertions into eleven silent skips -- and `unittest` exits 0 on a skip, so
# this script printed "all green" over them. Measured, not imagined: the same tree gave three
# failures with stale jars present and a clean "all green" after `rm -rf mod/build/libs`, and
# the second reads as the better result. Deleting the artifact an assertion inspects is a way
# of passing it.
#
# A WARNING IS NOT ENOUGH FOR THE FULL RUN, so it builds the jar instead. A `!!` line in a
# fourteen-minute run scrolls past, and the run would still end in "all green" over an
# unasserted contract -- relying on the reader noticing, which is the exact habit this whole
# family of bugs is about not relying on. The right move is the one this finding itself
# argues for: change the artifact rather than look harder at it. Three minutes on top of
# fourteen is nothing for a pre-merge gate, and it leaves behind a current verified jar,
# which is what someone needs before installing one anyway.
#
# THE ITERATION FLAGS DELIBERATELY DO NOT BUILD. `--java` and `--python` are for the inner
# loop, where an unbuilt `libs` is the normal state and a three-minute build every time would
# teach people to stop running this at all. That asymmetry is intentional; do not "fix" it.
# IS THE JAR THE ONE THIS SOURCE TREE WOULD PRODUCE? Answered with `test_dist_jar`'s OWN
# comparison -- the sha256 `stampSourceHash` bakes into the jar against a hash recomputed over
# `mod/src/main/java` -- because that is the question the suite will ask half an hour from now,
# and two ways of asking one question is how the two answers drift apart.
#
# DELIBERATELY NOT "IS ANY SOURCE FILE NEWER THAN THE JAR". That is a weaker question wearing
# the same clothes: it misses a DELETED file, which moves the digest while leaving nothing
# newer behind, and it misses a rename that swaps two names with identical bytes. Both of those
# fail `test_dist_jar` and neither trips an mtime check.
#
# Exits 0 current, 1 stale, 2 could not tell -- and 2 SAYS SO rather than being read as 0,
# because "the check could not run" reading as "the check passed" is the failure this whole
# file exists to prevent.
jar_matches_source() {
    python3 - "$1" <<'PY_STAMP'
import sys
import zipfile

sys.path.insert(0, ".")
try:
    from tests.test_dist_jar import _source_hash
except Exception as exc:
    print("cannot import tests.test_dist_jar: %s" % exc)
    sys.exit(2)

try:
    with zipfile.ZipFile(sys.argv[1]) as jar:
        stamps = [n for n in jar.namelist() if n.endswith("mcrecipedump-source.sha256")]
        if len(stamps) != 1:
            print("it carries no source stamp, so it predates stampSourceHash")
            sys.exit(1)
        got = jar.read(stamps[0]).decode("utf-8").strip()
except Exception as exc:
    print("cannot read %s: %s" % (sys.argv[1], exc))
    sys.exit(2)

want = _source_hash()
if got == want:
    sys.exit(0)
print("stamped %s, this tree hashes to %s" % (got[:12], want[:12]))
sys.exit(1)
PY_STAMP
}

# SAY WHEN THERE IS NO JAR TO CHECK, for the same reason as the oracle warning below.
# `test_dist_jar` calls `skipTest` when `mod/build/libs` holds no jar, so an unbuilt worktree
# turns its eleven assertions into eleven silent skips -- and `unittest` exits 0 on a skip, so
# this script printed "all green" over them. Measured, not imagined: the same tree gave three
# failures with stale jars present and a clean "all green" after `rm -rf mod/build/libs`, and
# the second reads as the better result. Deleting the artifact an assertion inspects is a way
# of passing it.
#
# AND SAY WHEN THE JAR IS STALE-BUT-PRESENT, which is the same hole one step along and was
# found by a real gate failure: the absence test above is satisfied by a jar at the CURRENT
# version built before this session's edits, so nothing rebuilt, and the run reached
# `test_it_was_built_from_the_source_that_is_checked_in_now` and failed there on a hash
# mismatch. Nothing unsafe happened -- the digest check did exactly its job -- but a bare
# mismatch of two hex strings reads as a packaging defect, and the skill records two people
# diagnosing stale-jar symptoms as real faults before noticing the files were merely old.
#
# The existing sweep above removes jars for OTHER versions only, on purpose, so that a stale
# CURRENT-version jar still fails loudly. That design is right and stays; what was missing is
# that "loudly" meant "opaquely".
#
# A WARNING IS NOT ENOUGH FOR THE FULL RUN, so it builds the jar instead -- now for a stale one
# as well as a missing one. A `!!` line in a fourteen-minute run scrolls past, and the run
# would still end in "all green" over an unasserted contract -- relying on the reader noticing,
# which is the exact habit this whole family of bugs is about not relying on. The right move is
# the one this finding itself argues for: change the artifact rather than look harder at it.
# Three minutes on top of fourteen is nothing for a pre-merge gate, and it leaves behind a
# current verified jar, which is what someone needs before installing one anyway.
#
# THE ITERATION FLAGS DELIBERATELY DO NOT BUILD. `--java` and `--python` are for the inner
# loop, where an unbuilt `libs` is the normal state and a three-minute build every time would
# teach people to stop running this at all. That asymmetry is intentional; do not "fix" it.
# They now PREDICT the failure instead, naming it before the eleven-minute python arm rather
# than leaving it to be discovered as a hash mismatch after it.
jar_warning=""
jar_path="mod/build/libs/mc-recipe-dump-$version.jar"
jar_state=ok
jar_why=""
if [ -n "$version" ]; then
    if [ ! -f "$jar_path" ]; then
        jar_state=absent
        jar_why="there is no $jar_path"
    else
        set +e
        detail=$(jar_matches_source "$jar_path")
        rc=$?
        set -e
        case $rc in
        0) ;;
        1) jar_state=stale
           jar_why="$jar_path was built from different source than mod/ holds now ($detail)" ;;
        *) echo "!! cannot tell whether $jar_path is current: $detail" >&2 ;;
        esac
    fi
fi

# The two states fail `test_dist_jar` in DIFFERENT ways, and saying which is the whole point:
# an absent jar makes its eleven assertions skip (and `unittest` exits 0 over a skip), a stale
# one makes the digest assertion fail.
if [ "$jar_state" = absent ]; then
    failure_word="SKIP and prove nothing"
else
    failure_word="FAIL on the source digest"
fi

if [ "$jar_state" != ok ]; then
    if [ "$full_run" -eq 1 ]; then
        echo "== jar =="
        echo "$jar_why; building so test_dist_jar checks THIS source tree"
        if mod/tools/build-jar.sh; then
            :
        else
            echo "!! build-jar.sh failed; test_dist_jar will not prove anything" >&2
            jar_warning="build failed -- test_dist_jar will $failure_word"
            fail=1
        fi
    else
        jar_warning="$jar_why -- test_dist_jar will $failure_word. Rebuild with mod/tools/build-jar.sh"
    fi
fi

# SAID ON EVERY ARM, and not only inside the python one where this line used to live. `--java`
# is the arm that never looks at the jar and the one most likely to LEAVE a stale one behind
# for somebody's later full run to trip over, so an iteration run is exactly where the warning
# is worth most and exactly where it was not being printed. The two behaviours -- a full run
# that builds and an iteration run that does not -- are each right and were jointly a trap.
[ -z "$jar_warning" ] || echo "!! $jar_warning"

if [ "$want_python" -eq 1 ]; then
    echo "== python =="
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
    # CONTAINER ON TOWER, DIRECT GRADLE ON A WORKSTATION. This arm hard-coded `docker run`, so
    # on cachyos-desktop -- JDK 25, no docker -- it exited 127 and this script reported
    # "java: 0 passed, 0 failed, 0 skipped" and carried on. Same defect `mod/tools/build-jar.sh`
    # had, one file over, and worse here: see the passed-nothing guard below for why 0/0/0 was
    # nearly a silent pass.
    #
    # 8g, not 4g: the gate holds the whole 121 MB graph in the test JVM. 4g dies partway
    # through with an OOM that looks like a hang.
    if command -v docker >/dev/null 2>&1; then
        gated docker run --rm --user 99:100 --memory=8g --memory-swap=8g \
            -v "$(host_path "$ROOT")":/repo \
            -v "$(host_path "$PACK_MODS")":/deps:ro \
            -v "$(host_path "$BUILD_DIR")":/build:ro \
            -v "$(host_path "$GRADLE_CACHE")":/gradle \
            -e GRADLE_USER_HOME=/gradle \
            -e RECIPEGRAPH_ORACLE="/build/$(basename "$ORACLE")" \
            -w /repo/mod eclipse-temurin:25-jdk \
            ./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx6g -Ppack_mods=/deps \
                cleanTest test > "$log" 2>&1 || fail=1
    elif command -v java >/dev/null 2>&1; then
        # Gradle's own default GRADLE_USER_HOME when the shared cache is absent: naming a
        # missing directory does not fail, it makes RFG re-decompile Minecraft (~9 min) every run.
        if [ -d "$GRADLE_CACHE" ]; then
            GRADLE_USER_HOME=$GRADLE_CACHE
            export GRADLE_USER_HOME
        fi
        RECIPEGRAPH_ORACLE="$ORACLE" gated sh -c 'cd mod && ./gradlew --no-daemon \
            -Dorg.gradle.jvmargs=-Xmx6g -Ppack_mods="$1" cleanTest test' -- "$PACK_MODS" \
            > "$log" 2>&1 || fail=1
    else
        echo "!! neither docker nor java found -- THE JAVA ARM DID NOT RUN AT ALL."
        fail=1
    fi

    # ALL THREE COUNT WITH THE SAME ANCHORED PATTERN THE DISPLAY BELOW USES, and `failed` did
    # not until #234. `grep -c ' FAILED$'` also matches the two lines gradle prints that are
    # not tests:
    #
    #   PlannerLayoutTest > aTokenNodeMarksTheIconColumn FAILED   a test, count it
    #   PlannerLayoutTest FAILED                                  the class roll-up
    #   > Task :test FAILED                                       the gradle task
    #
    # so two real failures were reported as four, and the inflation grows with the number of
    # failing CLASSES rather than staying a fixed offset. `skipped` was already anchored and
    # `passed` is safe by accident, because gradle prints no class-level PASSED line -- which is
    # why the arithmetic looked right on a green run and only broke when something failed.
    #
    # THE COMPILE CASE IS THE ONE WORTH FIXING FOR. A run that never builds prints
    # `> Task :compileJava FAILED` and nothing else, so the old count said "1 failed" while the
    # display named no test at all: a build that never ran, reported as one flaky test. Anchoring
    # makes it "0 failed" with `fail=1` still set from gradle's exit status, so the run fails on
    # the exit code and the summary stops inventing a test.
    tests_re='^[A-Za-z].* > .* '
    passed=$(grep -cE "${tests_re}PASSED$" "$log" || true)
    failed=$(grep -cE "${tests_re}FAILED$" "$log" || true)
    skipped=$(grep -cE "${tests_re}SKIPPED$" "$log" || true)
    echo "java: $passed passed, $failed failed, $skipped skipped"
    grep -E "${tests_re}(FAILED|SKIPPED)$" "$log" || true

    # AN ARM THAT ASSERTED NOTHING IS A FAILURE, NOT A COUNT OF ZERO. Every guard below reads
    # the log for a string, so all of them go SILENT when the run produced no log at all: the
    # golden-gate check greps for `... SKIPPED` and finds nothing, which is indistinguishable
    # from the gate having passed. Observed: docker missing, arm exits 127, output reads
    # "java: 0 passed, 0 failed, 0 skipped" with no alarm, and only the `|| fail=1` above kept
    # it from printing "all green" over a port nothing checked.
    #
    # This also covers the case that `|| fail=1` cannot see: a run that exits 0 having collected
    # nothing -- a `--tests` filter matching no class, a Gradle no-op, or a changed output format,
    # since `passed` comes from grepping ' PASSED$', which is Gradle's presentation and not a
    # contract this repository controls.
    if [ "$passed" -eq 0 ]; then
        echo "!! THE JAVA ARM ASSERTED NOTHING: 0 tests reported PASSED."
        echo "!! That is not an empty suite -- it means the run did not happen, or its output"
        echo "!! format changed and every check below is reading a log that says nothing."
        echo "!! full log: $log"
        fail=1
    fi

    # A SKIPPED gate is reported as a problem, not as a detail. This is the whole point.
    if grep -q 'everyFixturePlansExactlyAsThePythonOracleDoes SKIPPED' "$log"; then
        echo "!! THE GOLDEN PLAN GATE SKIPPED. The Java port was NOT checked against the oracle."
        fail=1
    fi
    # And the gate PASSING has to be observed, not inferred from the absence of a SKIPPED line.
    # Those are different claims, and the check above only ever made the weaker one.
    if [ -f "$ORACLE" ] \
       && ! grep -q 'everyFixturePlansExactlyAsThePythonOracleDoes PASSED' "$log"; then
        echo "!! THE GOLDEN PLAN GATE DID NOT PASS, and did not report SKIPPED either."
        echo "!! An oracle is present at $ORACLE, so it should have run. full log: $log"
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
