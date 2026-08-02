package io.github.jacoblasky.recipedump;

import java.lang.reflect.Method;

import net.minecraft.item.ItemStack;

/**
 * ProjectE's EMC value for a stack, read reflectively, or nothing at all when ProjectE
 * is absent. Issue #50.
 *
 * REFLECTION RATHER THAN A COMPILE DEPENDENCY, and that is not laziness. This mod's only
 * compile dependency is the HadEnoughItems jar from the pack itself (see build.gradle);
 * adding ProjectE would mean a second `-P` property, a second jar to stage on any machine
 * that builds, and a hard `required-after` on a mod that most packs do not run. A dump from
 * a pack without ProjectE must still be a complete dump -- it simply has no emc.json.
 *
 * DO NOT "simplify" this to `compileOnly files(projecte_jar)` plus a direct call. The
 * direct call is a class reference in a method signature, and a 1.12.2 client resolves
 * those eagerly enough that DumpCommand would fail to load at all without ProjectE
 * installed, taking the whole dump with it. Reflection is what keeps the dependency soft.
 *
 * The API is stable across ProjectE 1.12.2 releases and is the mod's own published
 * interface, not an internal: `ProjectEAPI.getEMCProxy().getValue(ItemStack)`. Verified
 * against ProjectE-1.12.2-PE1.4.1, which is what the reference pack ships.
 */
final class ProjectEBridge {

    private static final String API_CLASS = "moze_intel.projecte.api.ProjectEAPI";
    private static final String PROXY_CLASS = "moze_intel.projecte.api.proxy.IEMCProxy";

    /** The IEMCProxy instance, or null when ProjectE is absent or the API moved. */
    private static Object proxy;
    /** IEMCProxy#getValue(ItemStack), resolved off the INTERFACE; see resolve(). */
    private static Method getValue;
    private static boolean resolved;
    /** Why the bridge is unavailable, for the chat line. Empty when it is available. */
    private static String absence = "not resolved yet";

    private ProjectEBridge() {
    }

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            Class<?> api = Class.forName(API_CLASS);
            Object p = api.getMethod("getEMCProxy").invoke(null);
            if (p == null) {
                absence = "ProjectE present but its EMC proxy is null";
                return;
            }
            // Resolved off the INTERFACE, not off p.getClass(). ProjectE's implementation
            // class is package-private, so a Method found on it is not accessible and the
            // invoke throws IllegalAccessException -- which would read as "ProjectE has no
            // EMC" rather than as a lookup mistake.
            getValue = Class.forName(PROXY_CLASS).getMethod("getValue", ItemStack.class);
            proxy = p;
            absence = "";
        } catch (ClassNotFoundException notInstalled) {
            absence = "ProjectE is not installed";
        } catch (Throwable t) {
            absence = "ProjectE present but its EMC API did not resolve: " + t;
        }
    }

    /** True when {@link #emc} can answer. */
    static boolean available() {
        resolve();
        return proxy != null && getValue != null;
    }

    /** Why {@link #available} is false, or "" when it is true. */
    static String absence() {
        resolve();
        return absence;
    }

    /**
     * This stack's EMC value, or 0 when it has none, when ProjectE is absent, or when the
     * lookup throws.
     *
     * 0 IS THE HONEST ANSWER FOR "NO ROUTE", which is why a throw returns it too. ProjectE
     * uses 0 to mean "this item has no EMC and cannot be transmuted", and the python side
     * only ever records a positive value -- so a failed lookup and a genuinely valueless
     * item land in the same place, and neither asserts a transmutation route that the pack
     * has disabled. That direction matters: #50's stated worst case is a route the tool
     * claims through EMC that the pack actually blocked.
     */
    static long emc(ItemStack stack) {
        if (!available() || stack == null || stack.isEmpty()) {
            return 0L;
        }
        try {
            Object v = getValue.invoke(proxy, stack);
            return v instanceof Number ? ((Number) v).longValue() : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }
}
