#!/bin/sh
#
# Launch the assembled production client. Runs INSIDE the harness container; the host side
# is `prodshot.sh`. Everything this needs was put on disk by `assemble.py`.
#
# This is the same JVM invocation a launcher makes, written out longhand, and that is the
# whole point: no Gradle, no RetroFuturaGradle, no deobfuscation. The pack's 75 coremods see
# the obfuscated names they were compiled against.
set -e

ROOT="${PRODCLIENT_ROOT:-/prodclient}"
GAME_DIR="${GAME_DIR:-/instance}"

# THE DEFAULTS BELOW MUST MATCH `prodshot.sh`'s, AND `prodshot.sh` IS WHERE THEY ARE ARGUED
# FOR. Two files quietly disagreeing about the same knob is worse than either value: the
# number that takes effect then depends on which one launched the run, which is invisible in
# the output. These exist only so that this script can be run by hand against a container
# started some other way.
#
# AND THE COMMENT ABOVE WAS NOT ENOUGH. This said 4G while `prodshot.sh` said 5G, written in that
# order, an hour apart, with the warning already in place. `tests/test_prodclient_harness.py`
# compares them now, because an instruction to keep two numbers in step is not a mechanism for
# keeping two numbers in step.
SHOT_WIDTH="${SHOT_WIDTH:-1280}"
SHOT_HEIGHT="${SHOT_HEIGHT:-800}"
HEAP="${CLIENT_HEAP:-5G}"

# The image IS a Java 8 image, so the JVM on PATH is the right one. Overridable for the case
# where a future image carries more than one.
JAVA="${JAVA8:-java}"

# classpath.txt holds paths RELATIVE to the assembled tree, so that the tree can be mounted
# anywhere. Absolutise them against wherever it actually landed. A JVM given a classpath entry
# that does not exist says nothing at all and fails later on a missing class, so every entry is
# checked here instead.
CP=$(awk -v r="$ROOT" 'BEGIN{RS=":"; ORS=""} {if(NR>1) print ":"; print r "/" $0}' \
        "$ROOT/classpath.txt")
# Split on ':' and nothing else. `for entry in $(... | tr ':' ' ')` would also split on spaces,
# so a mount point with a space in it would report every path as missing and the message would
# name fragments rather than files.
missing=0
OLDIFS=$IFS
IFS=:
for entry in $CP; do
    [ -e "$entry" ] || { echo "launch.sh: classpath entry missing: $entry" >&2; missing=1; }
done
IFS=$OLDIFS
[ "$missing" -eq 0 ] || exit 2
ASSET_INDEX=$(sed -n 's/.*"assetIndex": *"\([^"]*\)".*/\1/p' "$ROOT/launch.json")
VERSION=$(sed -n 's/.*"id": *"\([^"]*\)".*/\1/p' "$ROOT/launch.json")

mkdir -p "$GAME_DIR"
cd "$GAME_DIR"

echo "launch.sh: $($JAVA -version 2>&1 | head -1)"
echo "launch.sh: version=$VERSION assetIndex=$ASSET_INDEX heap=$HEAP gameDir=$GAME_DIR"
echo "launch.sh: classpath entries: $(echo "$CP" | tr ':' '\n' | wc -l)"
echo "launch.sh: mods: $(ls "$GAME_DIR/mods"/*.jar 2>/dev/null | wc -l)"

# `-Dfml.ignoreInvalidMinecraftCertificates` is required because the client jar we assembled
# is the plain Mojang one and Forge checks a signature the launcher normally strips. Without
# it FML aborts before any mod loads, with a message about the certificate rather than about
# the jar.
#
# `-Dfml.ignorePatchDiscrepancies` is NOT cosmetic here either: Forge binary patches the
# vanilla jar at runtime and refuses to continue if any patch does not apply cleanly.
exec "$JAVA" \
    -Xmx"$HEAP" -Xms1G \
    -XX:+UseG1GC -XX:MaxGCPauseMillis=100 \
    -Djava.library.path="$ROOT/natives" \
    -Djava.net.preferIPv4Stack=true \
    -Dfml.ignoreInvalidMinecraftCertificates=true \
    -Dfml.ignorePatchDiscrepancies=true \
    -Dlog4j2.formatMsgNoLookups=true \
    "$@" \
    -cp "$CP" \
    net.minecraft.launchwrapper.Launch \
    --username Headless \
    --version "$VERSION" \
    --gameDir "$GAME_DIR" \
    --assetsDir "$ROOT/assets" \
    --assetIndex "$ASSET_INDEX" \
    --uuid 00000000000040008000000000000000 \
    --accessToken 0 \
    --userType legacy \
    --versionType Forge \
    --width "$SHOT_WIDTH" \
    --height "$SHOT_HEIGHT" \
    --tweakClass net.minecraftforge.fml.common.launcher.FMLTweaker
