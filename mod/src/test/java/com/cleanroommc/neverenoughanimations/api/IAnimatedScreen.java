package com.cleanroommc.neverenoughanimations.api;

/**
 * A LINKAGE PLACEHOLDER for an interface that belongs to another mod, present only so the
 * JVM can link `com.cleanroommc.modularui.screen.ModularScreen` in a test.
 *
 * ModularUI 3.1.5's `IMuiScreen` extends this, and it lives in NeverEnoughAnimations, which
 * is a separate CLIENT-ONLY jar and is therefore not in the 364-jar server pack this machine
 * can reach. Without it, `new ModularScreen(...)` dies during verification with
 * `NoClassDefFoundError: com/cleanroommc/neverenoughanimations/api/IAnimatedScreen` before a
 * single widget is sized -- measured, not guessed: the same call succeeds under
 * `-Xverify:none`, which is what proves this is a link-time requirement and nothing more.
 *
 * IT IS EMPTY ON PURPOSE AND MUST STAY EMPTY. Nothing implements it, nothing calls it, and no
 * production code in this mod references it. If it ever grows a method, or something starts
 * implementing it, this has stopped being a placeholder and has become a fake implementation
 * of somebody else's API, which is the thing to avoid.
 *
 * DELETE IT the moment the real NeverEnoughAnimations jar is on the test classpath -- for
 * example if `mod/build.gradle`'s `packJars` learns to resolve it from a CLIENT instance
 * (the client's ~410 mods include it; Tower's server pack does not). Two definitions of one
 * interface on one classpath is a split-brain nobody will enjoy diagnosing.
 */
public interface IAnimatedScreen {
}
