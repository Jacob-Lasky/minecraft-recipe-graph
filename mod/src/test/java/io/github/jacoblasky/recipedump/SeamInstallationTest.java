package io.github.jacoblasky.recipedump;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

/**
 * EVERY SETTABLE SEAM MUST BE INSTALLED BY PRODUCTION, not only by the test that covers it.
 *
 * <h2>The defect class this exists for</h2>
 *
 * A seam here is a producer and a consumer joined by a settable field: JEI's gui handler asks
 * {@code PlannerHooks} where the planner is, the keybind hands {@code PlannerHooks} an item to
 * plan, {@code ScenarioSource} asks a reader whether an input is live. Each defaults to a
 * deliberate, correct no-op, and each is covered by a test that INSTALLS AN IMPLEMENTATION
 * FIRST -- so the test passes whether or not anything in the shipped mod ever does.
 *
 * That is invisible from every angle that normally works. The producer is tested, the consumer
 * is tested, no null is dereferenced, nothing logs, and the nearby comment says the wiring
 * "lands separately", which was true when it was written. #191 found three at once: the `=`
 * keybind resolved a JEI target and threw it away for the whole of #19 phase 4, and its
 * SIBLING seam on the same class was wired -- which is what proved the search discriminated
 * rather than just failing to find things.
 *
 * <h2>Why this is a scan and not a list</h2>
 *
 * A test naming the three seams #191 found would go green the moment they were fixed and stay
 * green through the fourth. The seams are ENUMERATED FROM THE CODE by their shape, so a new
 * one is covered the day it is written rather than the day somebody remembers this file.
 * {@link #KNOWN_SEAMS} is not the list under test -- it is a floor, asserted so a predicate
 * that quietly stops matching anything cannot pass by finding nothing.
 *
 * <h2>What "installed" means, exactly</h2>
 *
 * Something OTHER than the declaring class, and outside the screenshot harness, must call the
 * installer. The harness is excluded because `shot/` runs only under `-Dmcrecipedump.shot`:
 * code no player reaches is not production, and a seam wired only there would give exactly
 * the working screenshot and broken game this whole family of bugs produces.
 *
 * WHAT IT CANNOT SEE, stated rather than implied: that the call is REACHED. A call site behind
 * a condition that is never true would satisfy this. Answering that needs the behavioural
 * tests beside this one -- `PlanTargetTest`, `PlannerStockTest`, and #195's
 * `dumpPluginInstallsTheSeamAndTheGraphArrivesAfterwards` -- which drive the production entry
 * point itself. This is the net that catches the ones nobody thought to write one for.
 */
public class SeamInstallationTest {

    /** Anything under here is the screenshot harness, not production. See the class note. */
    private static final String HARNESS = ClassFiles.ROOT_PACKAGE + "/shot/";

    /**
     * Seams that must still be found by the scan, so a broken predicate cannot pass by
     * matching nothing. NOT the list under test -- see the class note.
     *
     * These five are the ones #191 reasoned about: two on `PlannerHooks` that sat side by side
     * with one wired and one not, the `NodeActions` holder #157 filled, `ScenarioSource`'s
     * per-input reader, and the graph-load listener. If the shape of any of them changes, this
     * assertion is the thing that says so rather than the scan silently shrinking.
     */
    private static final List<String> KNOWN_SEAMS = Arrays.asList(
            "client/jei/PlannerHooks.setAreaSource",
            "client/jei/PlannerHooks.setTargetListener",
            "client/planner/NodeActionsHolder.install",
            "common/ScenarioSource.readBy",
            "common/GraphService.onLoad");

    /**
     * What one pass over the compiled classes found.
     *
     * THE SKIPPED LIST IS CARRIED RATHER THAN SWALLOWED. Some classes cannot be introspected
     * here at all -- they name a type from a mod that is `compileOnly`, so the signature
     * resolves at compile time and not on a JUnit classpath -- and skipping them is the only
     * option. Reporting them alongside the count is what stops a classpath change from
     * shrinking this gate to nothing while it goes on passing.
     */
    private static final class Scan {

        final Map<String, Seam> seams = new LinkedHashMap<String, Seam>();
        final List<String> introspected = new ArrayList<String>();
        final List<String> skipped = new ArrayList<String>();

        @Override
        public String toString() {
            return introspected.size() + " classes introspected, " + skipped.size()
                    + " skipped (" + skipped + ")";
        }
    }

    /** An installer and the production classes that call it. */
    private static final class Seam {

        /** Internal name of the class that declares the installer. */
        final String owner;
        final String method;
        /** JVM descriptor, so an overload cannot be mistaken for its sibling. */
        final String descriptor;
        final Set<String> callers = new LinkedHashSet<String>();

        Seam(String owner, String method, String descriptor) {
            this.owner = owner;
            this.method = method;
            this.descriptor = descriptor;
        }

        /** What a `Methodref` to this installer looks like in a caller's constant pool. */
        String reference() {
            return owner + "." + method + descriptor;
        }

        /** `package/Class.method`, which is how a human names it. */
        String label() {
            return owner + "." + method;
        }
    }

    // -- the gate ------------------------------------------------------------------------------

    @Test
    public void everySettableSeamIsInstalledSomewhereInProduction() throws Exception {
        Scan scan = findSeams();
        assertTheScanFoundTheSeamsItIsKnownToHave(scan);
        recordCallers(scan.seams);

        List<String> unwired = new ArrayList<String>();
        for (Seam seam : scan.seams.values()) {
            if (seam.callers.isEmpty()) {
                unwired.add(seam.label() + seam.descriptor);
            }
        }
        // PRINTED ON SUCCESS, NOT ONLY ON FAILURE. A derived gate that says nothing when it
        // passes cannot be told from one whose predicate has stopped matching -- the count in
        // the log is what makes "it found six" checkable by a reader instead of a claim in a
        // PR body. The floor assertion above catches the collapse to zero; this shows the
        // drift from six to four, which no assertion can, because the right number is not
        // knowable in advance.
        System.out.println("seams found (" + scan.seams.size() + "), " + scan);
        for (Seam seam : scan.seams.values()) {
            System.out.println("  " + seam.label() + " <- "
                    + (seam.callers.isEmpty() ? "NOTHING" : seam.callers.toString()));
        }
        if (!unwired.isEmpty()) {
            fail("these seams exist, and nothing in the shipped mod installs one -- so the"
                 + " feature behind each is present, tested, and absent in the game:\n  "
                 + String.join("\n  ", unwired)
                 + "\n(install one from production, or delete the seam)");
        }
    }

    /**
     * A THIRD SHAPE: the class that installs a seam is itself never switched on.
     *
     * The scan above asks whether ANYTHING calls `ScenarioSource.readBy`. `PinStore` does, and
     * has since #140 -- so that seam read as installed while `HAVE` had no reader at all, which
     * is one of the three defects #191 filed. Per-CONSTANT granularity is not recoverable from a
     * constant pool: `HAVE.readBy(...)` and `PINS.readBy(...)` are the same `Methodref` behind
     * different `getstatic`s, and pairing them by co-occurrence in one class would be a
     * heuristic that can pass wrongly, which is worse than no gate.
     *
     * WHAT IS RECOVERABLE is the layer above. A class whose job is to install a seam turns
     * itself on through a public static no-argument entry point -- `PlanTarget.install()`,
     * `PlannerStock.install()` -- and whether PRODUCTION calls that is an exact question. So
     * the rule is derived rather than listed: any class that references a seam installer and is
     * not the seam's own declaring class must have every such entry point called from outside
     * itself. Installing `HAVE`'s reader is now covered, and so is the next class of this shape
     * on the day it is written.
     *
     * WHAT IT STILL DOES NOT COVER, stated because the PR says so too: that the seam a helper
     * installs is the one it was supposed to install. `PlannerStock.install()` being called
     * proves a reader was installed, not that it was installed on `HAVE`. Only
     * `PlannerStockTest` says that, and it installs the reader itself.
     */
    @Test
    public void everyClassThatInstallsASeamIsItselfSwitchedOnByProduction() throws Exception {
        Scan scan = findSeams();
        assertTheScanFoundTheSeamsItIsKnownToHave(scan);
        Map<String, Set<String>> entryPoints = installerEntryPoints(scan);

        assertTrue("no installer entry points found, so this gate is asserting nothing; the"
                   + " `install()` shape must have moved", entryPoints.size() >= 2);
        assertTrue("PlannerStock installs the HAVE reader through a no-argument entry point,"
                   + " which is the case this gate exists for; found " + entryPoints.keySet(),
                   entryPoints.containsKey(
                           ClassFiles.ROOT_PACKAGE + "/client/PlannerStock.install()V"));

        List<String> orphaned = new ArrayList<String>();
        for (Map.Entry<String, Set<String>> entry : entryPoints.entrySet()) {
            System.out.println("installer entry point " + entry.getKey() + " <- "
                    + (entry.getValue().isEmpty() ? "NOTHING" : entry.getValue().toString()));
            if (entry.getValue().isEmpty()) {
                orphaned.add(entry.getKey());
            }
        }
        assertEquals("these classes install a seam and nothing in the shipped mod switches them"
                     + " on, so the seam they install is installed only by their tests",
                     Collections.emptyList(), orphaned);
    }

    /**
     * `owner.install()V` for every seam-installing class, mapped to its production callers.
     *
     * PUBLIC, STATIC, VOID AND NO ARGUMENTS, because that is the shape of "turn this on": a
     * method taking something is being handed a collaborator and is a seam in its own right,
     * already covered above.
     */
    private static Map<String, Set<String>> installerEntryPoints(Scan scan) throws IOException {
        Set<String> seamReferences = new LinkedHashSet<String>(scan.seams.keySet());
        Map<String, Set<String>> found = new LinkedHashMap<String, Set<String>>();
        for (File file : ClassFiles.under(ClassFiles.ROOT_PACKAGE)) {
            String internal = ClassFiles.internalName(file);
            if (isHarness(internal) || declaresASeam(scan, internal)) {
                continue;
            }
            if (Collections.disjoint(ClassFiles.methodReferences(ClassFiles.read(file)),
                                     seamReferences)) {
                continue;
            }
            Class<?> owner = load(internal);
            if (owner == null) {
                continue;
            }
            try {
                for (Method method : owner.getDeclaredMethods()) {
                    int modifiers = method.getModifiers();
                    if (Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers)
                            && !method.isSynthetic() && method.getParameterTypes().length == 0
                            && method.getReturnType() == void.class) {
                        found.put(internal + "." + method.getName() + "()V",
                                  new LinkedHashSet<String>());
                    }
                }
            } catch (NoClassDefFoundError absent) {
                continue;
            }
        }
        for (File file : ClassFiles.under(ClassFiles.ROOT_PACKAGE)) {
            String caller = ClassFiles.internalName(file);
            if (isHarness(caller)) {
                continue;
            }
            for (String reference : ClassFiles.methodReferences(ClassFiles.read(file))) {
                Set<String> callers = found.get(reference);
                if (callers != null && !isSelf(reference.substring(0, reference.indexOf('.')),
                                               caller)) {
                    callers.add(caller);
                }
            }
        }
        return found;
    }

    private static boolean declaresASeam(Scan scan, String internal) {
        for (Seam seam : scan.seams.values()) {
            if (isSelf(seam.owner, internal)) {
                return true;
            }
        }
        return false;
    }

    /**
     * THE OTHER SHAPE OF THE SAME DEFECT: a proxy hook with an empty body and no override.
     *
     * `CommonProxy`'s hooks are empty deliberately -- a dedicated server has no screen to open
     * and receives no snapshot -- which is exactly what makes an unimplemented one invisible.
     * `applyStockSnapshot` was empty on both sides from the day the packet was written: the
     * server read the grid, sent a megabyte of it, and every reply fell into the braces (#191).
     * The scan above cannot see this, because an override is not a settable field.
     *
     * IF A HOOK IS EVER GENUINELY A NO-OP ON BOTH SIDES, this fails and should: overriding it
     * with an empty body and a comment saying why is a cheap way to say so, and the comment is
     * the thing that was missing.
     */
    @Test
    public void everyEmptyProxyHookIsOverriddenByTheClientProxy() throws Exception {
        byte[] common = ClassFiles.read(classFile("common/CommonProxy"));
        byte[] client = ClassFiles.read(classFile("client/ClientProxy"));
        Set<String> stubs = ClassFiles.emptyMethods(common);
        Set<String> overridden = ClassFiles.emptyMethods(client);
        Set<String> implemented = ClassFiles.declaredMethods(client);

        // The population, before the property. A detector that found no empty methods would
        // pass this test on any codebase forever, and one that called everything empty would
        // pass it too once the overrides existed. Both directions are pinned.
        assertTrue("CommonProxy's hooks are empty on purpose, so some must be found; got "
                   + stubs, stubs.size() >= 3);
        assertTrue("applyStockSnapshot's body on the common side is the empty one this whole"
                   + " test is about; got " + stubs,
                   stubs.contains("applyStockSnapshot(Lnet/minecraft/nbt/NBTTagCompound;)V"));
        assertFalse("preInit registers a capability and loads the graph, so a detector that"
                    + " calls it empty is matching nothing in particular",
                    stubs.contains("preInit(Lnet/minecraftforge/fml/common/event/"
                                   + "FMLPreInitializationEvent;)V"));

        List<String> unimplemented = new ArrayList<String>();
        for (String stub : stubs) {
            if (!implemented.contains(stub)) {
                unimplemented.add(stub);
            } else if (overridden.contains(stub)) {
                unimplemented.add(stub + " (overridden, but the override is empty too)");
            }
        }
        assertEquals("CommonProxy hooks with an empty body that ClientProxy never fills, so"
                     + " whatever the server sends lands in a pair of braces",
                     Collections.emptyList(), unimplemented);
    }

    // -- teeth ---------------------------------------------------------------------------------

    /**
     * The scan finds a call it must find, and does not find one that does not exist.
     *
     * Without this, a constant-pool parser that returned an empty set for every class would
     * make the gate above pass on everything forever -- which is indistinguishable from
     * success and is precisely the failure mode the gate is about.
     */
    @Test
    public void theCallSiteScanSeesARealCallAndInventsNoFalseOne() throws Exception {
        Set<String> fromAreaSource = ClassFiles.methodReferences(ClassFiles.read(
                classFile("client/planner/PlannerAreaSource")));
        assertTrue("PlannerAreaSource.install calls PlannerHooks.setAreaSource; the scan of its"
                   + " constant pool must see it. Saw: " + fromAreaSource,
                   fromAreaSource.contains("io/github/jacoblasky/recipedump/client/jei/"
                           + "PlannerHooks.setAreaSource(Lio/github/jacoblasky/recipedump/"
                           + "client/jei/PlannerHooks$AreaSource;)V"));
        assertFalse("the scan reported a call to a method that does not exist",
                    fromAreaSource.contains("io/github/jacoblasky/recipedump/client/jei/"
                            + "PlannerHooks.setAreaSourceNotReally()V"));
    }

    /**
     * A seam installed ONLY by the screenshot harness does not count as installed.
     *
     * Asserted on the exclusion itself rather than on an example, because there is no such
     * seam today and the assertion has to keep meaning something when there is. `shot/` is
     * scanned for classes -- so the exclusion is doing work rather than looking at an empty
     * directory -- and then contributes no callers.
     */
    @Test
    public void theHarnessIsNotProduction() throws Exception {
        List<File> harnessClasses = ClassFiles.under(ClassFiles.ROOT_PACKAGE + "/shot");
        assertTrue("expected the screenshot harness to have classes; if it moved, this"
                   + " exclusion is silently excluding nothing", harnessClasses.size() >= 5);
        for (File file : harnessClasses) {
            assertTrue(ClassFiles.internalName(file) + " should be excluded as harness code",
                       isHarness(ClassFiles.internalName(file)));
        }
        assertFalse("production code must not be mistaken for the harness",
                    isHarness(ClassFiles.ROOT_PACKAGE + "/client/ClientProxy"));
    }

    /**
     * A class does not install its own seam.
     *
     * `PlannerHooks.setTargetListener(null)` inside `PlannerHooks` would otherwise read as
     * production wiring, and a default reinstalling its own default is the exact no-op this
     * whole gate exists to notice.
     */
    @Test
    public void aClassInstallingItsOwnSeamDoesNotCount() {
        String hooks = ClassFiles.ROOT_PACKAGE + "/client/jei/PlannerHooks";
        assertTrue(isSelf(hooks, hooks));
        assertTrue("a nested class is still the same class for this purpose",
                   isSelf(hooks, hooks + "$1"));
        assertFalse(isSelf(hooks, ClassFiles.ROOT_PACKAGE + "/client/PlanTarget"));
    }

    // -- finding the seams ---------------------------------------------------------------------

    /**
     * What makes something a seam, in one place.
     *
     * A PUBLIC VOID METHOD TAKING ONE OF THIS MOD'S OWN INTERFACES, on a class that HOLDS a
     * field of that interface. Each clause earns its place:
     *
     * <ul>
     * <li>the parameter is an interface OF THIS MOD, so a `Runnable` or a Forge callback --
     *     which the platform installs, not this code -- is not counted;</li>
     * <li>the class holds a field of that type, which is what separates INSTALLING an
     *     implementation from merely being handed one to use and forget. It is the field that
     *     makes the default outlive the call and the absence invisible;</li>
     * <li>void, because a seam's installer answers nothing -- a method returning a value is
     *     being asked a question rather than being given a collaborator.</li>
     * </ul>
     *
     * A field of a COLLECTION of the interface counts too, so a multi-listener holder is not
     * quietly exempt from a rule its single-listener sibling obeys.
     */
    private static Scan findSeams() throws Exception {
        Scan scan = new Scan();
        for (File file : ClassFiles.under(ClassFiles.ROOT_PACKAGE)) {
            String internal = ClassFiles.internalName(file);
            if (isHarness(internal)) {
                continue;
            }
            Class<?> owner = load(internal);
            if (owner == null) {
                scan.skipped.add(internal);
                continue;
            }
            try {
                for (Method method : owner.getDeclaredMethods()) {
                    if (!isSeamInstaller(owner, method)) {
                        continue;
                    }
                    String descriptor =
                            "(L" + internalNameOf(method.getParameterTypes()[0]) + ";)V";
                    Seam seam = new Seam(internal, method.getName(), descriptor);
                    scan.seams.put(seam.reference(), seam);
                }
            } catch (NoClassDefFoundError absent) {
                // A signature naming a type from a `compileOnly` mod. Reflection cannot read
                // the member list at all when one entry will not resolve, so the whole class
                // goes unexamined -- recorded rather than swallowed, because a classpath
                // change that started skipping everything would otherwise pass silently.
                scan.skipped.add(internal);
                continue;
            }
            scan.introspected.add(internal);
        }
        return scan;
    }

    private static boolean isSeamInstaller(Class<?> owner, Method method) {
        int modifiers = method.getModifiers();
        if (!Modifier.isPublic(modifiers) || method.isSynthetic() || method.isBridge()) {
            return false;
        }
        if (method.getReturnType() != void.class || method.getParameterTypes().length != 1) {
            return false;
        }
        Class<?> parameter = method.getParameterTypes()[0];
        if (!parameter.isInterface() || !isOurs(parameter)) {
            return false;
        }
        return holdsAFieldOf(owner, parameter);
    }

    private static boolean holdsAFieldOf(Class<?> owner, Class<?> parameter) {
        for (Field field : owner.getDeclaredFields()) {
            if (field.getType() == parameter) {
                return true;
            }
            Type generic = field.getGenericType();
            if (!(generic instanceof ParameterizedType)) {
                continue;
            }
            for (Type argument : ((ParameterizedType) generic).getActualTypeArguments()) {
                if (argument == parameter) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Every production class that names one of the seams in its constant pool. */
    private static void recordCallers(Map<String, Seam> seams) throws IOException {
        for (File file : ClassFiles.under(ClassFiles.ROOT_PACKAGE)) {
            String caller = ClassFiles.internalName(file);
            if (isHarness(caller)) {
                continue;
            }
            for (String reference : ClassFiles.methodReferences(ClassFiles.read(file))) {
                Seam seam = seams.get(reference);
                if (seam != null && !isSelf(seam.owner, caller)) {
                    seam.callers.add(caller);
                }
            }
        }
    }

    private static void assertTheScanFoundTheSeamsItIsKnownToHave(Scan scan) {
        // ASSERT THE POPULATION BEFORE THE PROPERTY, as `CommonSideSafetyTest` does. A scan
        // that introspected nothing finds no unwired seams and reports success.
        assertTrue("only " + scan, scan.introspected.size() >= 40);
        Set<String> found = new LinkedHashSet<String>();
        for (Seam seam : scan.seams.values()) {
            found.add(seam.label().substring(ClassFiles.ROOT_PACKAGE.length() + 1));
        }
        List<String> missing = new ArrayList<String>(KNOWN_SEAMS);
        missing.removeAll(found);
        assertEquals("the scan stopped recognising a seam it is known to have, so it is no"
                     + " longer looking for the right shape. Found: " + found + "; " + scan,
                     Collections.emptyList(), missing);
    }

    // -- small helpers -------------------------------------------------------------------------

    private static boolean isHarness(String internalName) {
        return internalName.startsWith(HARNESS);
    }

    /** True when `caller` is the seam's own class, counting its nested classes as the same. */
    private static boolean isSelf(String owner, String caller) {
        return caller.equals(owner) || caller.startsWith(owner + "$");
    }

    private static boolean isOurs(Class<?> type) {
        return type.getName().startsWith(ClassFiles.ROOT_PACKAGE.replace('/', '.') + ".");
    }

    private static String internalNameOf(Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static File classFile(String relative) {
        File file = new File(ClassFiles.classesRoot(),
                             ClassFiles.ROOT_PACKAGE + "/" + relative + ".class");
        assertTrue("expected a class at " + file, file.isFile());
        return file;
    }

    /**
     * Load without INITIALISING, which matters: several of these classes register a keybind or
     * touch Forge state in a static initialiser, and running that here would either throw or
     * quietly change global state for every test after this one.
     *
     * A class that cannot be loaded at all -- one naming a mod that is `compileOnly`, say --
     * is skipped rather than failed. It cannot be scanned for seams, and failing on it would
     * make this gate depend on which pack jars happen to be on the classpath.
     */
    private static Class<?> load(String internalName) {
        try {
            return Class.forName(internalName.replace('/', '.'), false,
                                 SeamInstallationTest.class.getClassLoader());
        } catch (Throwable absent) {
            return null;
        }
    }
}
