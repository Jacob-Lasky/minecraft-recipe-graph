#!/bin/sh
# Bring up an X server, then run whatever was asked for against it.
#
# The display socket is POLLED FOR rather than waited on with a fixed sleep, because a fixed
# sleep is a race in both directions: too short and LWJGL's Display.create() fails with
# "Could not open display", which reads as a missing library rather than as a timing bug;
# too long and every screenshot pays for it.
set -e

XVFB_SCREEN="${XVFB_SCREEN:-1280x800x24}"
DISPLAY="${DISPLAY:-:99}"
export DISPLAY

# -ac and -nolisten tcp together: no X authority file to manage (nothing outside this
# container can reach the socket anyway) and no TCP port opened on the host network.
#
# +extension RANDR is REQUIRED, not defensive. LWJGL 2 enumerates display modes by running
# the `xrandr` command and parsing it; with no RANDR extension that prints nothing, LWJGL
# indexes an empty array and the client dies in Minecraft.setWindowIcon with a bare
# ArrayIndexOutOfBoundsException naming neither X nor RANDR.
Xvfb "$DISPLAY" -screen 0 "$XVFB_SCREEN" +extension RANDR -nolisten tcp -ac >/tmp/xvfb.log 2>&1 &

i=0
while [ ! -e "/tmp/.X11-unix/X${DISPLAY#:}" ]; do
    i=$((i + 1))
    if [ "$i" -gt 100 ]; then
        echo "harness: Xvfb did not come up in 10s; its log follows" >&2
        cat /tmp/xvfb.log >&2
        exit 1
    fi
    sleep 0.1
done

# `exec`, so the client's exit code IS the container's exit code. The harness signals a
# missed screenshot with a non-zero exit and a wrapper shell would swallow it. Xvfb stays a
# child of pid 1 across the exec and goes away with the container.
exec "$@"
