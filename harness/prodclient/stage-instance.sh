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
. "$(dirname "$0")/../../tools/gate.sh"

HEADLESS_ONLY=0
[ "${1:-}" = "--headless-only" ] && HEADLESS_ONLY=1

# Reinstall the mod jar and nothing else. What `prodshot.sh` runs before every shot, so a run
# cannot measure a jar older than the source tree beside it. Takes no container and no gate,
# which is why it is safe to call from inside a script that is about to take both.
MOD_ONLY=0
[ "${1:-}" = "--mod-only" ] && MOD_ONLY=1

LOCAL_BUILD="${LOCAL_BUILD:-/coding/.recipegraph-build}"
INSTANCE="${INSTANCE:-$LOCAL_BUILD/prodinstance}"
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
    gated docker run --rm --user 0:0 --memory=1g --memory-swap=1g \
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

if [ "$MOD_ONLY" -eq 1 ]; then
    install_mod
    exit 0
fi

if [ "$HEADLESS_ONLY" -eq 0 ]; then
    stage_from_amp
    build_instance
    install_mod
fi
apply_headless_overrides

echo "stage-instance: $(ls "$INSTANCE"/mods/*.jar | wc -l) jars in mods/," \
     "$(ls "$INSTANCE"/mods/1.12.2/*.jar 2>/dev/null | wc -l) in mods/1.12.2/," \
     "$(ls "$INSTANCE"/scripts 2>/dev/null | wc -l) scripts"
