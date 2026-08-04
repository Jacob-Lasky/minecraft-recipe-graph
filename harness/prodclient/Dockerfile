# Headless Minecraft 1.12.2 PRODUCTION (obfuscated) Forge client. Same Xvfb plus llvmpipe
# environment as `harness/Dockerfile`, different JVM and a different reason to exist.
#
# WHY A SECOND IMAGE INSTEAD OF A FLAG ON THE FIRST. `harness/` runs the mod in
# RetroFuturaGradle's DEOBFUSCATED dev workspace, and that workspace breaks any coremod whose
# transformer hardcodes SRG names. Measured: ThaumcraftFix's transformer throws
# `IllegalArgumentException: Target method boolean
# thaumcraft/common/entities/construct/EntityArcaneBore.func_184645_a(...) does not exist`,
# because in the dev workspace that method carries its MCP name and the SRG name it is asked
# for is not there. A production launch runs against obfuscated classes, where the SRG names
# the coremod was written against are the names actually present, so the transformer works as
# designed. That is a property of the LAUNCH, not of a setting, which is why this is a
# separate image and not a switch.
#
# JAVA 8, NOT 25, AND THIS IS THE INVERSE OF THE CONSTRAINT ON `harness/Dockerfile`. That
# image needs a modern JDK because it runs the GRADLE build (RetroFuturaGradle 2.0.2 ships
# class file version 69 and dies on anything older), and Gradle then provisions a separate
# Java 8 toolchain for the game. Nothing here runs Gradle: this image launches the production
# client directly, so the JVM in the image IS the JVM the game runs on, and 1.12.2 plus Forge
# plus LaunchWrapper require 8. THE POSITIVE HALF IS MEASURED: on 2026-08-03 a real obfuscated
# Forge 1.12.2-14.23.5.2860 client launched in this image and bare Forge reached the main menu
# headlessly. The negative half is not, so treat it as the expected shape and not as evidence:
# on a modern JDK a 1.12.2 launch is reported to die in LaunchWrapper's reflection into the
# system class loader, with a stack naming `sun.misc.Unsafe` or an `InaccessibleObjectException`
# rather than naming the Java version, which reads as a broken mod list. Nobody needs to
# reproduce that to keep this image on 8; the main menu is the reason.
#
# Based directly on `eclipse-temurin:8-jdk` rather than copying a JDK onto the JDK 25 base.
# That tag is Ubuntu 26.04 (resolute), the SAME distribution as `eclipse-temurin:25-jdk`,
# verified 2026-08-03 by reading /etc/os-release out of both images, and all fifteen packages
# below resolve on it (verified with `apt-get install --simulate`). A multi stage
# `COPY --from=eclipse-temurin:8-jdk /opt/java/openjdk` would work too and is strictly more
# machinery for the same result, so it is not used. If a future Temurin 8 tag stops tracking
# the same distribution as the JDK 25 tag, revisit that decision rather than papering over a
# package rename.
FROM eclipse-temurin:8-jdk

# Xvfb plus mesa's llvmpipe give LWJGL 2 a real OpenGL context with no GPU. This is a
# software rasteriser standing in for a card, NOT a stub that fakes the API, which is the
# distinction that makes rendered output trustworthy, and the reason not to reach for
# HeadlessMC's LWJGL patching (see harness/README.md, "Why not HeadlessMC").
#
# 1.12.2 asks for OpenGL 2.1 and the context is much wider than that. The load-bearing claim
# is "much wider", not the exact numbers; those are the evidence, and they are re-readable
# from any run's log rather than to be trusted from here. Measured on 2026-08-03 inside THIS
# image, not by `glxinfo` but by the thing that actually has to work: LWJGL 2.9.4 nightly
# 20150209, the exact build 1.12.2 ships, calling `Display.create()` on this image's Java 8:
#
#   java.version  = 1.8.0_492
#   LWJGL version = 2.9.4
#   GL_VENDOR     = Mesa
#   GL_VERSION    = 4.5 (Compatibility Profile) Mesa 26.0.3-1ubuntu1
#   GL_RENDERER   = llvmpipe (LLVM 21.1.8, 256 bits)
#   readback RGBA = 64 128 191 255   after a glClear to (0.25, 0.5, 0.75, 1.0)
#
# THE LAST LINE IS THE ONE THAT MATTERS. A vendor string can be answered by a context that
# rasterises nothing; a glReadPixels that returns the colour just cleared to cannot. `glxinfo`
# reports the same three strings and is a weaker test, because it proves GL is reachable from
# a C program and says nothing about the JVM, the natives, or AWT.
#
# Worth knowing before writing this container off for a heavier mod set: a 4.5 compatibility
# profile covers essentially anything a 1.12.2 mod can ask for, so "a client mod refuses the
# software rasteriser's GL level" is not the thing that would stop it (#146).
#
# Every X library below is one LWJGL 2 dlopen()s at Display.create(), and a missing one
# fails as `UnsatisfiedLinkError: no lwjgl in java.library.path`, which names LWJGL and
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
#                                            libXtst.so.6`, verified 2026-08-02 on the JDK 25
#                                            image. THE PATH MOVED AND THE DEPENDENCY DID NOT:
#                                            Java 8 ships it at
#                                            `/opt/java/openjdk/jre/lib/amd64/libawt_xawt.so`,
#                                            under a `jre/` and an arch directory that modern
#                                            JDKs dropped, so a `find` written against the
#                                            JDK 25 layout comes back empty and reads as an
#                                            absent library. `ldd` on it here resolves
#                                            libXtst.so.6 and libXrender.so.1 against these
#                                            packages, verified 2026-08-03.
#   libopenal1                               the sound engine; without it the client logs a
#                                            sound error and renders anyway, but the error
#                                            looks like a crash in the log, so install it
#   x11-xserver-utils                        the `xrandr` BINARY, not a library. LWJGL 2 does
#                                            not call libXrandr to list display modes, it
#                                            shells out to `xrandr` and parses the text. With
#                                            the tool absent it gets an empty screen list and
#                                            dies in Minecraft.setWindowIcon with a bare
#                                            `ArrayIndexOutOfBoundsException: 0` out of
#                                            LinuxDisplay.getAvailableDisplayModes, which
#                                            looks like a broken display and not a missing
#                                            package, verified 2026-08-02.
#
# mesa-utils is deliberately NOT here, matching `harness/Dockerfile`. `glxinfo` is a smoke
# test, not a runtime dependency, and baking a diagnostic into the image it diagnoses means
# the next person cannot tell whether the client is finding GL or whether the probe is. To
# re-measure, layer a throwaway image on this one and delete it afterwards:
#
#   printf 'FROM mcrecipedump-prodclient:latest\nRUN apt-get update \\\n
#     && apt-get install -y --no-install-recommends mesa-utils\n' > /tmp/p/Dockerfile
#   docker build -t glprobe:throwaway /tmp/p
#   docker run --rm --memory=4g --memory-swap=4g --user 99:100 glprobe:throwaway glxinfo -B
#
# The entrypoint brings Xvfb up first, so `glxinfo` needs no arrangement beyond that.
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
#
# MESA_SHADER_CACHE_DIR is pointed somewhere writable because this container runs as
# `--user 99:100`, which has no passwd entry, so HOME is `/` and mesa cannot create `//.cache`.
# It then prints, on its own line and before any of the client's output:
#
#   Failed to create //.cache for shader cache (Permission denied)---disabling.
#
# which is harmless (it disables a cache) and looks exactly like a failed launch to whoever
# reads the log next. Fixed in the image rather than left to the caller to pass HOME, because
# the caller getting it wrong produces a scary message and not an error. /tmp is inside the
# container and dies with it, so nothing persists across runs and no rendered output depends
# on whether a previous run warmed it. Measured 2026-08-03: with this set the message is gone
# and `/tmp/mesa-shader-cache/mesa_shader_cache` is created.
ENV LIBGL_ALWAYS_SOFTWARE=1 \
    GALLIUM_DRIVER=llvmpipe \
    DISPLAY=:99 \
    MESA_SHADER_CACHE_DIR=/tmp/mesa-shader-cache

COPY entrypoint.sh /usr/local/bin/entrypoint.sh

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
