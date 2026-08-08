#!/bin/sh
#
# The Java port's pure core, compiled and run with NO Minecraft, NO Gradle and NO pack jars,
# so that it runs on a stock GitHub runner in about a minute.
#
#   tools/ci-java.sh                    # from the repository root, or from anywhere
#   WORK=<dir> tools/ci-java.sh         # scratch tree elsewhere, e.g. outside a read-only mount
#
# THE THREE JARS IT DOWNLOADS ARE CACHED IN `$WORK/deps` AND RE-VERIFIED ON EVERY RUN, which is
# also how to run this inside `eclipse-temurin:25-jdk` on Tower: that image ships neither curl
# nor wget, so seed `$WORK/deps` from a host that has one and this skips the fetch. The sha256
# check does not skip.
#
# WHY A SEPARATE COMPILE INSTEAD OF `./gradlew test`. The real build cannot run in CI, and the
# reason is not fixable from this repository:
#
#   - `mod/build.gradle` compiles against three jars taken FROM THE PACK -- HadEnoughItems
#     4.28.1, ModularUI 3.1.5 and AE2-UEL -- because those exact builds are not on maven, and
#     `checkPackJars` is a dependency of `compileJava`, so `test` fails before javac starts.
#     Probed 2026-08-03: `HadEnoughItems` returns zero items from the GTNH nexus search API
#     (control: the same query for `modularui` returns GTNH's own ModularUI 1.2.14), Modrinth
#     404s on `hadenoughitems`, and maven.cleanroommc.com 404s at its root. They are
#     CurseForge distributions.
#   - RetroFuturaGradle then downloads and fernflower-decompiles Minecraft, which is ~9 minutes
#     cold on this hardware before a single test runs.
#
# BLOCKED: a GitHub runner cannot build the RFG half of the suite. pocket-dev and the desktop
# can, from a staged pack directory. Unblocks when: the three pack jars are mirrored somewhere
# CI may fetch them from, which is a supply-chain decision rather than a code change.
#
# WHAT IS LEFT IS NOT A CONSOLATION PRIZE. `graph/` and `plan/` import nothing but `java.*` and
# gson: that is the graph reader, the solver, the cost model and every ordering contract the
# golden fixtures freeze, which is to say all of the port that is about being RIGHT rather than
# about being a Minecraft mod. It is a minority of the suite, and the run PRINTS the fraction
# rather than this comment carrying a number that has to be maintained to stay true. This
# sentence carried one anyway until #244: "a little over 40%", which was approximately right
# about the TEST count and noticeably wrong about the FILE count, and named neither, so it
# could not be checked at all. Do not put the new figures here either.
#
# The purity check below ASSERTS that boundary rather than assuming it, so the day someone
# imports `net.minecraft` into `plan/` this job says exactly that instead of emitting forty
# "package does not exist" errors.
#
# WHAT THIS JOB DOES NOT COVER, stated here because a green tick that overstates itself is the
# defect this repository has hit more than any other:
#
#   - THE GOLDEN PLAN GATE. `PlanFixtureTest.everyFixturePlansExactlyAsThePythonOracleDoes`
#     needs a 121 MB oracle graph that is not in git and never will be, and it `Assume`-skips
#     without `$RECIPEGRAPH_ORACLE`. It is the single most important assertion in the
#     repository. It is on the allowlist below, it is REPORTED as a skip on every run, and any
#     other skip fails the job. A synthetic oracle cannot stand in for it: the stored plans are
#     the real pack's plans, so a small graph would have to come with its own expected results
#     and would prove the port agrees with itself. Run `tools/check.sh` before merging; that is
#     the only thing that runs this gate.
#   - Everything that needs the patched Minecraft or a pack jar: `DigestFixtureTest`,
#     `NbtTraceTest`, `DiscriminatorTest`, `SchemaFiveTest`, `ModularUiLayoutTest` and the
#     `client/`, `common/` and `shot/` packages -- most of the rest of the suite.
#   - `tests/test_dist_jar.py`, which needs a built jar. It skips in the python job, which is
#     what `tools/check.sh` builds a jar to prevent.
#
# AND THE RUN SAYS SO IMMEDIATELY ABOVE ITS VERDICT, because this header is read once and the
# output is read every time (#244). See the counting block above `== run ==`.
#
# IT IS STILL ALLOWED TO EXIT 0, and that was a real design question rather than an oversight.
# The alternative considered in #244 was a `--merge-gate` flag that fails on principle, to stop
# this job ever being the last word before a merge. It is not here because nothing wraps this
# script: CI runs it as one of three jobs, and a job engineered to go red would be muted within
# a week, taking the compile and purity checks it also performs down with it. The green tick is
# honest as long as it states its scope, and now it does. Revisit only if something starts
# consuming this exit status as a merge condition.
#
# DO NOT "SIMPLIFY" THIS INTO A GRADLE INVOCATION without solving the pack-jar problem first;
# it will fail on `checkPackJars` and the message will name a missing jar rather than the CI
# design decision recorded above.

set -eu

cd "$(dirname "$0")/.."
ROOT=$(pwd)
WORK=${WORK:-$ROOT/.ci-java}
CLASSES=$WORK/classes
RUNNER=$WORK/runner
DEPS=$WORK/deps

CORE_MAIN="mod/src/main/java/io/github/jacoblasky/recipedump/graph
mod/src/main/java/io/github/jacoblasky/recipedump/plan"
# THE WHOLE TEST TREE, which `CORE_TEST` is a FRACTION OF and which the run now states rather
# than leaving to this header (#244). `CORE_TEST` is built FROM it rather than beside it: the one
# way the coverage arithmetic below can lie while everything still passes is the two of them
# counting different trees, and deriving one from the other makes that structurally impossible
# instead of merely checked.
ALL_TEST=mod/src/test/java
CORE_TEST="$ALL_TEST/io/github/jacoblasky/recipedump/graph
$ALL_TEST/io/github/jacoblasky/recipedump/plan"
# A file that MUST fail the purity check, so a rule that has stopped matching anything cannot
# report the core as pure. A zero from a search is a claim about the search until the search has
# been made to fail on purpose.
IMPURE_CONTROL=mod/src/main/java/io/github/jacoblasky/recipedump/client/jei/JeiBridge.java

die() {
    echo "!! $*" >&2
    exit 1
}

# -- the purity boundary, asserted rather than assumed ----------------------------------------

# `java.` and `javax.` are the platform, gson is a real dependency of the graph reader and the
# plan writer, `io.github.jacoblasky.recipedump.` is this port, and org.junit/org.hamcrest are
# the test framework. Anything else is a dependency this job cannot supply, and the job must not
# paper over that by failing to notice.
#
# THIS CHECK IS THE LEGIBLE HALF AND NOT THE WHOLE BOUNDARY, deliberately: it passes a
# `recipedump.client.jei` import, because narrowing it to the core's own packages would need a
# second list of them here. The closure check after the compile is what refuses those, on what
# javac ACTUALLY pulled in rather than on what an import line says.
ALLOWED_MAIN='^import (static )?(java|javax)\.|^import (static )?com\.google\.gson\.|^import (static )?io\.github\.jacoblasky\.recipedump\.'
ALLOWED_TEST="$ALLOWED_MAIN|^import (static )?org\\.junit\\.|^import (static )?org\\.hamcrest\\."

impure_imports() {
    # $1: the allowed-import pattern, $2..: files or directories
    pattern=$1
    shift
    grep -rhE '^import ' "$@" | grep -vE "$pattern" | sort -u || true
}

echo "== the core is still pure java =="
control=$(impure_imports "$ALLOWED_MAIN" "$IMPURE_CONTROL")
[ -n "$control" ] || die "the purity check matched nothing in $IMPURE_CONTROL, which imports
   Minecraft -- the check itself is broken, so its verdict on graph/ and plan/ means nothing"
echo "control: $IMPURE_CONTROL trips it, so the check discriminates"

# shellcheck disable=SC2086
strays=$(impure_imports "$ALLOWED_MAIN" $CORE_MAIN; impure_imports "$ALLOWED_TEST" $CORE_TEST)
[ -z "$strays" ] || die "graph/ and plan/ have stopped being pure Java, so this job can no
   longer compile them without Gradle:
$strays
   Either that import does not belong in the core, or this job has to become a Gradle run and
   the pack-jar problem at the top of this file has to be solved first."
echo "graph/ and plan/ import only java.*, gson, junit and this port"

# -- the two dependencies, at the versions mod/build.gradle pins ------------------------------

# PARSED OUT OF `mod/build.gradle`, NOT RESTATED HERE. Two copies of a version is two chances
# to drift, and the gson pin in particular is load-bearing: 2.8.0 is the version Minecraft
# 1.12.2 itself ships, so the port must not be tested against a newer one. Do not "update" it.
junit_version=$(sed -n "s/.*testImplementation 'junit:junit:\([0-9][0-9.]*\)'.*/\1/p" \
    mod/build.gradle)
gson_version=$(sed -n \
    "s/.*testImplementation 'com.google.code.gson:gson:\([0-9][0-9.]*\)'.*/\1/p" \
    mod/build.gradle)
[ -n "$junit_version" ] || die "no junit version found in mod/build.gradle"
[ -n "$gson_version" ] || die "no gson version found in mod/build.gradle"

# sha256 PER ARTIFACT AND VERSION, so a bump in mod/build.gradle lands here as a hard failure
# rather than as a silently unverified download. Verify a new one against the artifact you
# actually intend, not against whatever the mirror served.
case $junit_version in
4.13.2) junit_sha=8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3
        # hamcrest-core is junit 4.x's own runtime dependency and is versioned with it.
        hamcrest_version=1.3
        hamcrest_sha=66fdef91e9739348df7a096aa384a5685f4e875584cce89386a7a47251c4d8e9 ;;
*) die "no pinned sha256 for junit $junit_version; add one to $0" ;;
esac
case $gson_version in
2.8.0) gson_sha=c6221763bd79c4f1c3dc7f750b5f29a0bb38b367b81314c4f71896e340c40825 ;;
*) die "no pinned sha256 for gson $gson_version; add one to $0" ;;
esac

fetch() {
    # $1: maven path, $2: expected sha256
    jar=$DEPS/$(basename "$1")
    if [ ! -f "$jar" ]; then
        url=https://repo1.maven.org/maven2/$1
        if command -v curl > /dev/null 2>&1; then
            curl -sfL --retry 3 "$url" -o "$jar.part" || die "could not fetch $url"
        elif command -v wget > /dev/null 2>&1; then
            wget -q -O "$jar.part" "$url" || die "could not fetch $url"
        else
            die "no curl and no wget, and $jar is not cached. Seed $DEPS from a host that has
   one; every jar in it is checksummed on use, so a copied cache is as verified as a fetch."
        fi
        mv "$jar.part" "$jar"
    fi
    # `sum` and not `got`: this shell has no locals, and `got` is the compiled-class count
    # further down. Two uses of one name in one script is a bug waiting for someone to reorder
    # the file.
    sum=$(sha256sum "$jar" | cut -d' ' -f1)
    [ "$sum" = "$2" ] || die "$jar has sha256 $sum, expected $2"
    echo "$jar"
}

# CLASSES ONLY. The dependency cache survives, because it is checksummed on every run rather
# than trusted for having been downloaded once -- and because the JDK container has no curl.
rm -rf "$CLASSES" "$RUNNER"
mkdir -p "$CLASSES" "$RUNNER" "$DEPS"
echo "== dependencies =="
junit_jar=$(fetch "junit/junit/$junit_version/junit-$junit_version.jar" "$junit_sha")
hamcrest_jar=$(fetch \
    "org/hamcrest/hamcrest-core/$hamcrest_version/hamcrest-core-$hamcrest_version.jar" \
    "$hamcrest_sha")
gson_jar=$(fetch "com/google/code/gson/gson/$gson_version/gson-$gson_version.jar" "$gson_sha")
echo "junit $junit_version, hamcrest $hamcrest_version, gson $gson_version, all sha256-verified"

# -- compile -----------------------------------------------------------------------------------

# `--release 8` AND NOT `-source/-target 8`: the mod ships for Minecraft 1.12.2 on Java 8, and
# only `--release` also restricts the API surface to Java 8's, so a `Map.of` or a diamond on an
# anonymous class fails here rather than at class-load time in the game.
#
# `-sourcepath` PLUS A CHECK ON WHAT IT PULLED IN, rather than a hand-listed closure. Some of
# `client/planner` is pure Java too -- `PlanJson`, `PlanView`, `NodeStatus` and friends read and
# render a plan and import nothing but `java.*`, gson and `plan.` -- and `PlanNodeRoundTripTest`
# goes through `client.planner.PlanJson` deliberately, because reading a fixture tree there and
# writing it back through `plan.PlanJson` is the only assertion that the two halves agree. So
# javac resolves the closure and the check below says what it actually compiled and refuses
# anything outside the three pure packages. A hand-listed set would be a fourth place to
# maintain, and the day it went stale it would go stale as a compile error in CI.
echo "== compile =="
find $CORE_MAIN $CORE_TEST -name '*.java' > "$WORK/sources.txt"
echo "$(wc -l < "$WORK/sources.txt") source files named explicitly"
javac --release 8 -encoding UTF-8 \
    -sourcepath mod/src/main/java -implicit:class \
    -cp "$junit_jar:$hamcrest_jar:$gson_jar" \
    -d "$CLASSES" @"$WORK/sources.txt"
# The runner compiles to its OWN output directory, so the closure check below has no exception
# to make for it and cannot be weakened by one later.
javac --release 8 -encoding UTF-8 -cp "$junit_jar" -d "$RUNNER" \
    tools/ci/JavaCoreSuite.java

# WHAT THE CLOSURE ACTUALLY DRAGGED IN. Printed, and refused if it leaves the pure packages: a
# reference from the core into `client/jei` or `common/` would otherwise fail as forty "package
# net.minecraft does not exist" lines that read like a broken toolchain rather than like the
# boundary this job rests on having moved.
packages=$(cd "$CLASSES" && find . -name '*.class' | sed 's|^\./||' \
    | awk -F/ '{ if (NF == 1) print "<the default package>"; \
                 else { sub(/\/[^/]*$/, ""); print } }' | sort -u)
echo "compiled packages:"
echo "$packages" | sed 's|^|  |'
outside=$(echo "$packages" \
    | grep -vE '^io/github/jacoblasky/recipedump/(graph|plan|client/planner)$' || true)
[ -z "$outside" ] || die "the core's closure has left the pure packages:
$outside
   Either that reference does not belong in graph/, plan/ or client/planner/, or this job has to
   become a Gradle run and the pack-jar problem at the top of this file has to be solved first."
for expected in io/github/jacoblasky/recipedump/graph io/github/jacoblasky/recipedump/plan; do
    echo "$packages" | grep -qx "$expected" \
        || die "nothing compiled into $expected, so the closure check above read an empty tree"
done

# -- run ---------------------------------------------------------------------------------------

# EVERY *Test CLASS THAT EXISTS, derived from the tree rather than listed, and then checked
# against the number of source files so a class that fails to compile into the run cannot go
# missing quietly. `$` excludes inner classes.
classes=$(cd "$CLASSES" && find . -name '*Test.class' ! -name '*$*' \
    | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort)
[ -n "$classes" ] || die "no *Test.class in $CLASSES; nothing would have run"

# -- the fraction, measured against the real tree ----------------------------------------------

# WHAT THIS JOB LEAVES OUT, COUNTED AT RUN TIME AND PRINTED NEXT TO THE VERDICT (#244). The
# header above already says which packages are excluded and why, but the header is read once and
# the output is read every time. The output said `classes: 24, tests: 324 run, 0 failed` and
# `java core green`, with nothing on either line to say that the count is a FRACTION. #243
# shipped four stale assertions past exactly that: it moved values four `client/planner` tests
# read, this job could not see any of them, and gradle caught them afterwards by luck.
#
# THE EXCLUDED COUNT IS NOT PINNED TO A NUMBER, deliberately, and this is the design question the
# issue left open. Asserting "44 excluded" would fail on every legitimate test file anyone adds
# under `client/`, and a check that fires on correct work gets edited until it stops rather than
# read. What IS asserted below is the arithmetic and a non-empty exclusion, neither of which can
# go stale and both of which catch what a pinned number cannot: a `find` that has stopped
# matching the tree it is supposed to be measuring.
# shellcheck disable=SC2086
find $CORE_TEST -name '*Test.java' | sort > "$WORK/core-tests.txt"
find "$ALL_TEST" -name '*Test.java' | sort > "$WORK/all-tests.txt"
want=$(wc -l < "$WORK/core-tests.txt")
total=$(wc -l < "$WORK/all-tests.txt")
[ "$want" -gt 0 ] || die "no *Test.java under $CORE_TEST, so this job would compile nothing"
# `-x -F` and not a substring match: one test file's path is a prefix of no other today, and
# relying on that is how this quietly starts excluding a file it does run.
grep -vxFf "$WORK/core-tests.txt" "$WORK/all-tests.txt" > "$WORK/not-run-tests.txt" || true
not_run=$(wc -l < "$WORK/not-run-tests.txt")
# Interpolated values sit at the END of their line on purpose: `die` prints the string as
# written, so a long path spliced mid-sentence produces a 150-column line in a CI log.
[ "$((want + not_run))" -eq "$total" ] || die "the two searches disagree: $want core test files
   plus $not_run excluded is not the $total found under this root, so one of them is reading a
   different tree than the other and the coverage line below would be arithmetic over nothing:
   $ALL_TEST"
# A ZERO HERE WOULD BE A CLAIM ABOUT THE SEARCH, not news about the suite, in the same way the
# purity check's IMPURE_CONTROL is. This job cannot cover the whole tree while the pack-jar
# problem at the top of this file is unsolved, so "nothing is excluded" means the search broke.
[ "$not_run" -gt 0 ] || die "this job appears to run all $total test files, which cannot be true
   while the pack jars named at the top of this file are unavailable to CI. The exclusion search
   has stopped matching, so the banner would claim coverage that does not exist."

# GROUPED BY TOP-LEVEL PACKAGE, from the tree rather than from a list. The package prefix comes
# out of CORE_TEST rather than being restated, so there is no second copy to go stale; a file
# outside it keeps its full path in the summary, which is ugly and true rather than silently
# mislabelled.
core_pkg=$(dirname "$(echo "$CORE_TEST" | head -n 1)")
# THE LABEL IS EVERYTHING AFTER `uniq -c`'s COUNT, not `$2`. Two of the labels this can produce
# contain a space (the root-package one, and the full path a file outside `$core_pkg` keeps), and
# `$2` truncated them mid-word: "(the root package) 8" printed as "(the 8".
groups=$(sed "s|^$core_pkg/||" "$WORK/not-run-tests.txt" \
    | awk -F/ '{ print (NF == 1 ? "(the root package)" : $1 "/") }' \
    | sort | uniq -c | sort -rn \
    | awk '{ count = $1; sub(/^ *[0-9]+ +/, ""); \
             printf "%s%s %s", (NR > 1 ? ", " : ""), $0, count } END { print "" }')
# WRAPPED BY HAND at a width that survives the runner's indent. It prints continuation lines
# under the label, so a line near 100 characters here comes out past it in a CI log column.
not_run_note="$not_run of $total *Test.java files: $groups.
They need ModularUI, a pack jar or the patched Minecraft, none of which this job
can fetch. tools/check.sh runs the whole suite; run it before merging."

got=$(echo "$classes" | wc -l)
[ "$got" -eq "$want" ] || die "$want *Test.java files but $got *Test.class classes to run"
echo "== run =="

# THE ALLOWLIST IS THE HONEST STATEMENT OF WHAT CI CANNOT COVER. Exactly one entry, and see the
# header for why no synthetic graph can retire it. Any OTHER skip fails the job.
# 6g, MIRRORING `mod/build.gradle`'s `test { maxHeapSize }` AND FOR THE SAME REASON: in CI the
# golden gate skips and this is irrelevant, but the moment someone runs this locally with
# $RECIPEGRAPH_ORACLE set it holds a 121 MB graph plus a cost table, and the default heap dies
# partway through with an OutOfMemoryError that looks like a hang.
exec java -Xmx"${CI_JAVA_HEAP:-6g}" \
    -cp "$RUNNER:$CLASSES:$junit_jar:$hamcrest_jar:$gson_jar" JavaCoreSuite \
    --scope 'core only' \
    --not-run "$not_run_note" \
    --allow-skip \
'io.github.jacoblasky.recipedump.plan.PlanFixtureTest.everyFixturePlansExactlyAsThePythonOracleDoes' \
    $classes
