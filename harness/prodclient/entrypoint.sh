#!/bin/sh
# Bring up an X server, then run whatever was asked for against it.
#
# Same job as `harness/entrypoint.sh`, and deliberately the same shape. What runs on top
# differs (a production `java ... net.minecraft.launchwrapper.Launch` here, a Gradle
# `runClient` there), but the display contract LWJGL 2 needs is identical, so DO NOT let the
# two drift: a fix to one is a fix to both.
#
# The display socket is POLLED FOR rather than waited on with a fixed sleep, because a fixed
# sleep is a race in both directions: too short and LWJGL's Display.create() fails with
# "Could not open display", which reads as a missing library rather than as a timing bug;
# too long and every launch pays for it.
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
        echo "prodclient: Xvfb did not come up in 10s; its log follows" >&2
        cat /tmp/xvfb.log >&2
        exit 1
    fi
    sleep 0.1
done

# `exec`, so the client's exit code IS the container's exit code. A wrapper shell would
# swallow it, and a headless launch is only useful if a failed one can be told from a
# successful one without reading the log. Xvfb stays a child of pid 1 across the exec and
# goes away with the container.
exec "$@"
