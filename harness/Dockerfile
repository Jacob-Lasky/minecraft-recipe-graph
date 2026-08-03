# Headless Minecraft 1.12.2 client, for rendering our own GUIs to a PNG without a launch
# by hand (#124). See harness/README.md for what it is for and harness/shot.sh for the
# one command that drives it.
#
# JDK 25, NOT 21. RetroFuturaGradle 2.0.2 ships class file version 69, so a 21 image dies
# on UnsupportedClassVersionError loading `com.gtnewhorizons.retrofuturagradle.UserDevPlugin`
# before any mod source is looked at -- and the error names the PLUGIN, so it reads as a
# broken build script rather than as a wrong JDK. The mod itself still targets Java 8
# through the Azul toolchain Gradle provisions; that is a separate JVM and unaffected.
FROM eclipse-temurin:25-jdk

# Xvfb plus mesa's llvmpipe give LWJGL 2 a real OpenGL context with no GPU. This is a
# software rasteriser standing in for a card, NOT a stub that fakes the API -- which is the
# distinction that makes rendered output trustworthy, and the reason not to reach for
# HeadlessMC's LWJGL patching (see harness/README.md, "Why not HeadlessMC").
#
# 1.12.2 asks for OpenGL 2.1 and the context is much wider than that. The load-bearing claim
# is "much wider", not the exact numbers; those are the evidence, read off the client's own
# banner on 2026-08-02 and re-readable from any run's log rather than trusted from here:
#
#   GL info: Vendor: 'Mesa' Version: '4.5 (Compatibility Profile) Mesa 26.0.3-1ubuntu1'
#            Renderer: 'llvmpipe (LLVM 21.1.8, 256 bits)'
#
# This comment previously asserted a 2.1 context. That was assumed rather than measured, and
# it was wrong in the direction that makes the container look less capable than it is.
#
# Worth knowing before writing this container off for a heavier mod set: a 4.5 compatibility
# profile covers essentially anything a 1.12.2 mod can ask for, so "a client mod refuses the
# software rasteriser's GL level" is not the thing that would stop it (#146).
#
# Every X library below is one LWJGL 2 dlopen()s at Display.create(), and a missing one
# fails as `UnsatisfiedLinkError: no lwjgl in java.library.path` -- which names LWJGL and
# not the library actually absent, so DO NOT trim this list on the grounds that a name looks
# unrelated to Minecraft.
#   libgl1 / libglx-mesa0 / libgl1-mesa-dri  the GL entry points and the llvmpipe driver
#   libglu1-mesa                             GLU, which LWJGL 2's util package links against
#   libx11-6 libxext6                        the X connection itself
#   libxcursor1 libxrandr2 libxxf86vm1 libxi6  cursor, mode-setting and input, all probed
#   libxtst6 libxrender1 libfontconfig1      NOT optional and not obviously LWJGL's: LWJGL 2's
#                                            LinuxSysImplementation touches `java.awt.Toolkit`
#                                            in its static initialiser, which dlopens the
#                                            JDK's libawt_xawt.so, which links libXtst. Absent,
#                                            the client dies before Minecraft.main with
#                                            `UnsatisfiedLinkError: libawt_xawt.so:
#                                            libXtst.so.6` -- verified 2026-08-02.
#   libopenal1                               the sound engine; without it the client logs a
#                                            sound error and renders anyway, but the error
#                                            looks like a crash in the log, so install it
#   x11-xserver-utils                        the `xrandr` BINARY, not a library. LWJGL 2 does
#                                            not call libXrandr to list display modes -- it
#                                            shells out to `xrandr` and parses the text. With
#                                            the tool absent it gets an empty screen list and
#                                            dies in Minecraft.setWindowIcon with a bare
#                                            `ArrayIndexOutOfBoundsException: 0` out of
#                                            LinuxDisplay.getAvailableDisplayModes, which
#                                            looks like a broken display and not a missing
#                                            package -- verified 2026-08-02.
RUN apt-get update && apt-get install -y --no-install-recommends \
        xvfb \
        x11-xserver-utils \
        libgl1 \
        libglx-mesa0 \
        libgl1-mesa-dri \
        libglu1-mesa \
        libx11-6 \
        libxext6 \
        libxcursor1 \
        libxrandr2 \
        libxxf86vm1 \
        libxi6 \
        libxtst6 \
        libxrender1 \
        libfontconfig1 \
        libopenal1 \
    && rm -rf /var/lib/apt/lists/*

# Force the software path explicitly rather than relying on there being no DRI device to
# fall back from: a host that later exposes /dev/dri would otherwise change what this
# renders, and a harness whose output depends on the host is not a harness.
ENV LIBGL_ALWAYS_SOFTWARE=1 \
    GALLIUM_DRIVER=llvmpipe \
    DISPLAY=:99

COPY entrypoint.sh /usr/local/bin/entrypoint.sh

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
