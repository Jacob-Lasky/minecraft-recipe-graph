#!/bin/sh
#
# Build the game directory the production client boots: the pack's jars, the pack's config, the
# pack's CraftTweaker scripts, this mod's jar, and the handful of settings that only a headless
# run needs changed.
#
#   harness/prodclient/stage-instance.sh                 # full rebuild from the AMP instance
#   harness/prodclient/stage-instance.sh --headless-only # just re-apply the overrides
#
# WHY THE OVERRIDES ARE A SCRIPT AND NOT A NOTE. Every one of them is a setting that is CORRECT
# for a person at a keyboard and fatal for a run with nobody there, so each will be re-introduced
# by the next `cp -a` of the pack's config unless something re-applies it. They are applied to the
# INSTANCE, never to the staged copy of the pack, so the pack's own configuration stays the thing
# it is.
#
# THE PACK'S CONFIG IS NOT OPTIONAL, AND NOT ONLY FOR RECIPES. A boot with default configs died in
# mod init on `NoClassDefFoundError:
# com/circulation/ae2wut/AE2UELWirelessUniversalTerminal$GetGui`, out of Cell Terminal's AE2WUT
# integration, because `ae2wut-1.0.5.jar` genuinely has no such inner class. The pack's own
# `cellterminal_server.cfg` sets `enableAE2WUT=false`. Somebody hit that before us and turned it
# off; booting without their config turns it back on.
set -e

# The container gate, sourced by path because this script does not cd to the repository root.
# It applies to the staging container below even though that container is a one minute `cp`:
# `tests/test_container_gate.py` enforces the rule on EVERY `docker run` in the tree, and it is
# right to. An exemption list is where "one container at a time" stops being a constraint and goes
# back to being a sentence in a README, which is the whole argument in `tools/gate.sh`. The copy
# also moves ~800 MB off the array, so it is not free even though it is short.
#
# THE GATE COVERS EVERY WRITE TO `$INSTANCE`, NOT JUST THE CONTAINER, and until #265 it covered
# only the container. `build_instance` opens with `rm -rf "$INSTANCE"` and ran UNGATED, so a
# plain `stage-instance.sh` deleted the game directory out from under whatever client was
# booting from it -- a shared directory, a 28 minute read, and a delete nothing was serialising
# against. `--mod-only` had the same hole one size down: it swaps the jar under test while
# another agent's client is starting, which is the wrong-jar measurement #265 was filed for.
# A gate that covers the cheap container and not the destructive `cp` is guarding the wrong
# thing.
. "$(dirname "$0")/../../tools/gate.sh"

HEADLESS_ONLY=0
[ "${1:-}" = "--headless-only" ] && HEADLESS_ONLY=1

# Reinstall the mod jar and nothing else. What `prodshot.sh` runs before every shot, so a run
# cannot measure a jar older than the source tree beside it. Takes no container, but it DOES take
# the gate now (#265): it writes into a directory another agent's client may be booting from, and
# "it is only a `cp`" is what made that invisible. `prodshot.sh` calls it with `GATE_LOCK=`
# because it already holds the gate across both steps; anything else must let it take its own.
MOD_ONLY=0
[ "${1:-}" = "--mod-only" ] && MOD_ONLY=1

LOCAL_BUILD="${LOCAL_BUILD:-/coding/.recipegraph-build}"
# `INSTANCE` MEANT TWO DIFFERENT THINGS IN THE TWO SCRIPTS OF THIS HARNESS, and `prodshot.sh`
# passes its value straight down here through the environment. There it is a NAME under
# `$LOCAL_BUILD`; here it used to be a FULL PATH. `INSTANCE=prodinstance-228 prodshot.sh` then
# booted `$LOCAL_BUILD/prodinstance-228` while this script staged into `./prodinstance-228`,
# relative to whatever directory the caller happened to be in -- so the shot measured a
# directory nobody had staged, and the workaround in #228 only worked because it was launched
# from `/coding/.recipegraph-build`, where the two spellings happen to resolve alike.
#
# Both spellings are accepted, and a relative one now means the same thing it means one script
# up. The default is unchanged.
INSTANCE="${INSTANCE:-prodinstance}"
case "$INSTANCE" in
    /*) ;;
    *) INSTANCE="$LOCAL_BUILD/$INSTANCE" ;;
esac
PACK_JARS="${PACK_JARS:-$LOCAL_BUILD/packserver377}"
PACK_CONFIG="${PACK_CONFIG:-$LOCAL_BUILD/packconfig}"
MOD_JAR="${MOD_JAR:-}"

# The AMP server instance, on the HOST. Mode 0700 uid 1000, which is why staging out of it needs a
# root container and a `chown 99:100` rather than a plain `cp`.
AMP="${AMP:-/mnt/cache/AMP_Games/instances/Meatballcraft01/Minecraft}"
HOST_BUILD="${HOST_BUILD:-/mnt/user/misc/coding/.recipegraph-build}"

stage_from_amp() {
    if [ -d "$PACK_JARS" ] && [ -d "$PACK_CONFIG" ]; then
        echo "stage-instance: pack already staged; delete $PACK_JARS to re-stage"
        return 0
    fi
    echo "stage-instance: staging the pack out of the AMP instance (root container)"
    # --user 0:0, deliberately and only here: the AMP instance is mode 0700 uid 1000, so a
    # 99:100 container cannot read it at all. The `chown -R 99:100` at the end of the script
    # below is what keeps the rule intact, by handing the copy back to the array's owner rather
    # than leaving root-owned files that wedge every other writer.
    #
    # NO `gated` ON THIS LINE, and that is not an exemption. Every caller of this function is
    # inside `stage_instance`, which is only ever invoked as `gated stage_instance`, so the gate
    # is already held here -- and `flock` is not recursive, so re-taking it would deadlock
    # against this script's own lock. `tests/test_container_gate.py` checks that transitively
    # rather than taking the word of a `gated` prefix, and it carries controls that prove it can
    # still reject an ungated one.
    docker run --rm --user 0:0 --memory=1g --memory-swap=1g \
        -v "$AMP:/mc:ro" -v "$HOST_BUILD:/out" \
        alpine sh -c '
            mkdir -p /out/packserver377 /out/packconfig
            cp -a /mc/mods/. /out/packserver377/
            cp -a /mc/config /out/packconfig/
            cp -a /mc/scripts /out/packconfig/
            [ -d /mc/resources ] && cp -a /mc/resources /out/packconfig/
            chown -R 99:100 /out/packserver377 /out/packconfig'
}

build_instance() {
    echo "stage-instance: building $INSTANCE"
    rm -rf "$INSTANCE"
    mkdir -p "$INSTANCE/mods"
    cp -a "$PACK_JARS"/. "$INSTANCE/mods/"
    cp -a "$PACK_CONFIG/config" "$INSTANCE/"
    cp -a "$PACK_CONFIG/scripts" "$INSTANCE/"
    # Written as a full `if` and NOT as `[ -d X ] && cp ...`, because that form's compound exit
    # status is 1 when the directory is absent, and under `set -e` that aborts the whole script on
    # the perfectly normal case of a pack with no resource pack folder. `harness/shot.sh` carries
    # the same warning about `&&` under `set -e` for its argument shifting; same trap, and it is
    # worth recognising by shape rather than rediscovering.
    if [ -d "$PACK_CONFIG/resources" ]; then
        cp -a "$PACK_CONFIG/resources" "$INSTANCE/"
    fi
    echo "eula=true" > "$INSTANCE/eula.txt"
}

install_mod() {
    jar="$MOD_JAR"
    if [ -z "$jar" ]; then
        # The reobfuscated one, NOT `-dev`. They differ by a few hundred bytes and the filename is
        # the only obvious tell; the reliable one is the SRG reference count, which is ~192 for the
        # real jar and exactly 0 for `-dev`. A dev jar in an obfuscated client dies on
        # NoSuchMethodError the first time the harness touches Minecraft.
        jar=$(ls "$(dirname "$0")/../../mod/build/libs"/mc-recipe-dump-*.jar 2>/dev/null \
              | grep -v -- '-dev\.jar$' | head -1)
    fi
    if [ -z "$jar" ] || [ ! -f "$jar" ]; then
        echo "stage-instance: no reobfuscated mod jar; run mod/tools/build-jar.sh first" >&2
        return 1
    fi
    rm -f "$INSTANCE"/mods/mc-recipe-dump-*.jar
    cp "$jar" "$INSTANCE/mods/"
    echo "stage-instance: installed $(basename "$jar")"
}

# A MODAL DIALOG IS AN INFINITE HANG, NOT AN ERROR, AND IT LOOKS EXACTLY LIKE SLOW PROGRESS.
#
# Modpack Config Checker compares the JVM's max heap against the pack's recommended 7000 MB and,
# when it falls short, calls `JOptionPane.showOptionDialog` from `preInit` on the client thread.
# Measured: `Main.coulddowithmoreram -> MessageClass.PopulateMessage -> Dialog.show ->
# WaitDispatchSupport.enter`, client thread WAITING on an AWT monitor, one core busy, the log
# frozen mid-sentence and no crash report. Nothing times it out because nothing is wrong.
#
# DO NOT "FIX" THIS BY RAISING THE HEAP. 7000 MB of heap needs a container above the 8g ceiling
# that Tower's Home Assistant and doorbell live under, and it would only move the problem: the same
# mod has five other message boxes, all currently disabled by the pack, any one of which would hang
# a run the same way if the pack ever enables one.
#
# DO NOT REACH FOR `-Djava.awt.headless=true` EITHER. It converts the block into a
# HeadlessException thrown out of another mod's preInit, which Forge turns into a hard
# LoaderException crash. Trading a hang for a crash in an unrelated mod is not an improvement.
apply_headless_overrides() {
    cfg="$INSTANCE/config/concheckrmd.cfg"
    if [ ! -f "$cfg" ]; then
        echo "stage-instance: no concheckrmd.cfg yet (written on first boot); re-run" \
             "--headless-only after one" >&2
        return 0
    fi
    # Only the LAUNCH check opens a dialog. The chat variant writes to chat and blocks nothing, so
    # it is deliberately left alone: the fewer settings this diverges from the pack's, the more a
    # headless run is evidence about the pack.
    python3 - "$cfg" <<'PY'
import re, sys
path = sys.argv[1]
with open(path) as fh:
    text = fh.read()
# The launch-dialog block, matched by its own heading rather than by line number, because the file
# is regenerated by Forge and the numbers move.
block = 'amount of ram recommended for game to start" {'
i = text.index(block)
j = text.index('}', i)
head, body, tail = text[:i], text[i:j], text[j:]
new = re.sub(r'(B:"2\) Check RAM meets recommendation\?")=true', r'\1=false', body)
if new == body:
    print("stage-instance: launch RAM dialog already disabled")
else:
    with open(path, 'w') as fh:
        fh.write(head + new + tail)
    print("stage-instance: disabled the launch RAM dialog in concheckrmd.cfg")
PY
}

# A PROBE THAT PLANS NEEDS A `graph.json`, AND NOTHING WAS PUTTING ONE HERE.
#
# The mod reads it from `config/mcrecipedump/graph.json` inside the game directory, and until
# #240 nothing staged one, so every screen that SOLVES rather than photographing reached the
# planner and got "no graph.json. looked in: /instance/config/mcrecipedump/graph.json". That
# cost a full pack boot to find, because it presents as a screen-level verdict rather than as a
# setup error: `jei-keybind` correctly hovered the item, correctly resolved its key, and then
# had nothing to plan against.
#
# DO NOT PIN A FILENAME HERE. The first version of this defaulted to `graph-s7.json` and was
# stale within the same afternoon, because master bumped `DumpCommand.SCHEMA` to 8 underneath
# it. The default is now "the graph with the highest `dump_schema` in the build directory",
# which tracks the jar without naming a file, and the choice is LOGGED with its schema and
# version so a mismatch is visible in the run rather than inferred from a wrong plan.
#
# NEWEST-BY-MTIME WOULD BE WRONG, and it is the obvious alternative. `/coding/.recipegraph-build`
# is shared by a dozen agents, and the newest file there right now is `graph-oracle-248.json`,
# which is schema 5 -- three behind the jar. Recency says who wrote last, not who wrote for
# this jar.
#
# WHY THE SCHEMA MATTERS AT ALL: `GraphJsonReader` is deliberately tolerant and skips sections
# it does not know, so an old graph LOADS. What it does not do is warn, and a schema that
# changed how keys are spelled -- schema 4 did, see `DumpCommand`'s "THIS FORMAT IS PART OF
# SCHEMA 4" -- then answers `keyId` -1 for keys the pack really has, which reads as "that item
# is not in the pack" rather than as a version mismatch. `RECIPEGRAPH_GRAPH` names one
# explicitly when a run wants a specific graph.
#
# ABSENT IS NOT FATAL, because the eleven screens that photograph a fixture do not need it and
# a 120 MB copy is not free. It says so instead, which is what the failing run needed.
#
# `${RECIPEGRAPH_GRAPH-...}` AND NOT `:-`, SO AN EMPTY VALUE MEANS "STAGE NOTHING". That is the
# escape hatch for the case #254 wants: `machines` deliberately photographs the "no graph.json"
# panel, because that is the picture a new player sees. With `:-` an empty value falls back to
# the default and there is no way to ask for no graph at all except by naming a path that does
# not exist, which reads as a mistake rather than as an intent.
# THE JAR'S OWN SCHEMA, read out of the source rather than out of the built jar. It is a
# compile-time constant in the same tree, so it cannot drift from the jar this branch builds,
# and reading it here turns "compare these two numbers yourself" into a check that fires. The
# previous version of this printed both numbers and asked the reader to compare them, which is
# the same shape as every other thing on this host that reports success and means nothing.
jar_schema() {
    sed -n 's/.*static final int SCHEMA = \([0-9]*\);.*/\1/p' \
        "$(dirname "$0")/../../mod/src/main/java/io/github/jacoblasky/recipedump/DumpCommand.java"
}

# Prints "PATH<tab>SCHEMA<tab>NOTE" for the chosen graph, or nothing when no candidate has a
# `dump_schema`. Nothing rather than an error, so the caller's own "no graph" message is the
# one that reaches the log.
#
# THE TIE-BREAK IS NEWEST-MTIME AND IT IS NOT DECORATION. Four of the six graphs in this
# directory are `dump_schema=5` today, so the moment the highest schema is one of those --
# which is as soon as anyone dumps a second graph at the current schema -- this rule decides
# what the pack boots with. SAME-SCHEMA GRAPHS ARE NOT INTERCHANGEABLE: `graph-oracle-248.json`
# carries `offworld_ores` and `graph-oracle.json` does not, and that field is what #248's
# dimension toll rests on, so the loser of a tie can be a graph whose planner cannot express
# the thing under test. A tie is therefore REPORTED, naming what lost, rather than resolved
# silently.
select_graph() {
    python3 - "$LOCAL_BUILD" <<'PYEOF'
import glob, os, re, sys

# SCANNED IN CHUNKS RATHER THAN PARSED. Each candidate is ~120 MB and `prodshot.sh` runs the
# mod-only path before EVERY run, so `json.load` on six of them to read six integers would put
# a minute of array I/O in front of every shot. `dump_schema` is a top-level scalar written by
# `DumpCommand`, so a regex over a sliding window finds it without building the document.
PATTERN = re.compile(rb'"dump_schema"\s*:\s*(\d+)')
WINDOW = 1 << 20


def schema_of(path):
    with open(path, "rb") as fh:
        tail = b""
        while True:
            chunk = fh.read(WINDOW)
            if not chunk:
                return None
            found = PATTERN.search(tail + chunk)
            if found:
                return int(found.group(1))
            # Carry enough to catch a match split across the boundary.
            tail = chunk[-64:]


found = []
for path in sorted(glob.glob(os.path.join(sys.argv[1], "graph-*.json"))):
    try:
        schema = schema_of(path)
    except OSError:
        continue
    if schema is not None:
        found.append((schema, os.path.getmtime(path), path))

if not found:
    sys.exit(0)

# Sorted rather than a running maximum, so the losers of a tie are still in hand for the note.
found.sort(key=lambda row: (row[0], row[1]), reverse=True)
schema, _, winner = found[0]
tied = [os.path.basename(row[2]) for row in found[1:] if row[0] == schema]
note = "sole candidate at that schema" if not tied else (
    "TIE at dump_schema=%d, newest mtime won over %s" % (schema, ", ".join(tied)))
print("%s\t%d\t%s" % (winner, schema, note))
PYEOF
}

selected=$(select_graph)
GRAPH_SCHEMA=$(printf '%s' "$selected" | cut -f2)
GRAPH_NOTE=$(printf '%s' "$selected" | cut -f3)
GRAPH_JSON="${RECIPEGRAPH_GRAPH-$(printf '%s' "$selected" | cut -f1)}"

install_graph() {
    dest="$INSTANCE/config/mcrecipedump"
    if [ -z "$GRAPH_JSON" ]; then
        rm -f "$dest/graph.json"
        echo "stage-instance: RECIPEGRAPH_GRAPH is empty; staged no graph, so screens that" \
             "SOLVE will shoot the 'no graph.json' panel"
        return 0
    fi
    if [ ! -f "$GRAPH_JSON" ]; then
        echo "stage-instance: no graph at $GRAPH_JSON; screens that SOLVE will report" \
             "'no graph.json'. Point RECIPEGRAPH_GRAPH at one." >&2
        return 0
    fi
    # SAY WHICH GRAPH AND WHETHER IT FITS THE JAR, on every run and not only when it changes,
    # because the `cmp` shortcut below returns before the copy and a run that skipped the copy
    # still needs to say what it is booting with.
    want=$(jar_schema)
    if [ -n "$GRAPH_SCHEMA" ] && [ -n "$want" ] && [ "$GRAPH_SCHEMA" != "$want" ]; then
        echo "stage-instance: !! $(basename "$GRAPH_JSON") is dump_schema=$GRAPH_SCHEMA and" \
             "this tree's DumpCommand.SCHEMA is $want. GraphJsonReader is tolerant and will" \
             "LOAD it, so nothing downstream will complain -- but a schema that changed how" \
             "keys are spelled makes keyId answer -1 for keys the pack really has, which" \
             "reads as 'that item is not in the pack'. Dump a current graph, or name one" \
             "with RECIPEGRAPH_GRAPH." >&2
    fi
    echo "stage-instance: graph is $(basename "$GRAPH_JSON")" \
         "(dump_schema=${GRAPH_SCHEMA:-unknown}, jar wants ${want:-unknown};" \
         "${GRAPH_NOTE:-named explicitly})"
    mkdir -p "$dest"
    # `cmp` first: this is 120 MB onto the array, and `prodshot.sh` calls the mod-only path
    # before every run.
    if cmp -s "$GRAPH_JSON" "$dest/graph.json"; then
        echo "stage-instance: graph.json already matches $(basename "$GRAPH_JSON")"
        return 0
    fi
    cp "$GRAPH_JSON" "$dest/graph.json"
    echo "stage-instance: installed graph.json ($(wc -c < "$dest/graph.json") bytes)"
}

# EVERYTHING THAT TOUCHES `$INSTANCE` LIVES IN HERE, so that it can be covered by exactly one
# gate acquisition. One, and not one per step: a run that released between the delete and the
# copy would hand another agent a half-built instance, which is the same defect as #265 with a
# worse blast radius.
stage_instance() {
    if [ "$MOD_ONLY" -eq 1 ]; then
        install_mod
        install_graph
        return 0
    fi

    if [ "$HEADLESS_ONLY" -eq 0 ]; then
        stage_from_amp
        build_instance
        install_mod
        install_graph
    fi
    apply_headless_overrides

    echo "stage-instance: $(ls "$INSTANCE"/mods/*.jar | wc -l) jars in mods/," \
         "$(ls "$INSTANCE"/mods/1.12.2/*.jar 2>/dev/null | wc -l) in mods/1.12.2/," \
         "$(ls "$INSTANCE"/scripts 2>/dev/null | wc -l) scripts"
}

# THE ONLY CALL SITE, AND IT IS GATED. A caller that already holds the gate passes `GATE_LOCK=`,
# gate.sh's own documented opt-out, rather than calling `stage_instance` directly -- so this line
# is the single place the gate is taken and there is no second, ungated way in. `prodshot.sh` is
# that caller and its comment says why.
gated stage_instance
