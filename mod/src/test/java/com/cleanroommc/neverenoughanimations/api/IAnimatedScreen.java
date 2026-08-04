package com.cleanroommc.neverenoughanimations.api;

/**
 * A LINKAGE PLACEHOLDER for an interface that belongs to another mod, present only so the
 * JVM can link `com.cleanroommc.modularui.screen.ModularScreen` in a test.
 *
 * ModularUI 3.1.5's `IMuiScreen` extends this, and it lives in NeverEnoughAnimations, which is
 * in NEITHER pack: measured 2026-08-03 (#119, #208), no jar among the client's 367 or the
 * server's 364 provides `IAnimatedScreen` at all. ModularUI simply ships a soft reference
 * MeatballCraft never satisfies. Without it, `new ModularScreen(...)` dies during verification
 * with `NoClassDefFoundError: com/cleanroommc/neverenoughanimations/api/IAnimatedScreen` before
 * a single widget is sized -- measured, not guessed: the same call succeeds under
 * `-Xverify:none`, which is what proves this is a link-time requirement and nothing more.
 *
 * IT IS EMPTY ON PURPOSE AND MUST STAY EMPTY. Nothing implements it, nothing calls it, and no
 * production code in this mod references it. If it ever grows a method, or something starts
 * implementing it, this has stopped being a placeholder and has become a fake implementation
 * of somebody else's API, which is the thing to avoid.
 *
 * THIS PLACEHOLDER IS PERMANENT AND NO JAR COPY REMOVES IT. DO NOT go looking for a client
 * instance to resolve NeverEnoughAnimations from: the reason this comment used to give for
 * deleting it -- that the interface would arrive if `mod/build.gradle`'s `packJars` learned to
 * read a CLIENT instance, whose "~410 mods include it" -- was never true. NEA is in neither
 * pack, so no parity work reaches it. Delete it only if the real jar is ever genuinely on the
 * test classpath, because two definitions of one interface on one classpath is a split-brain
 * nobody will enjoy diagnosing.
 */
public interface IAnimatedScreen {
}
