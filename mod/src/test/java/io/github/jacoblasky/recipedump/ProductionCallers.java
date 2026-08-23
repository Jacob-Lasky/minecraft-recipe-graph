package io.github.jacoblasky.recipedump;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * WHAT COUNTS AS A CALL FROM THE SHIPPED MOD, in one place, for every gate that asks.
 *
 * <h2>Why this is one walk and not one per gate</h2>
 *
 * {@link SeamInstallationTest} carried three identical copies of this loop until #205 removed
 * them, and its note says why in the strongest available terms: "three implementations that
 * agree today drift one at a time, and a gate that drifts stops DISCRIMINATING rather than
 * failing, so nothing reports it." #323 needed a FOURTH gate -- every panel builder must have
 * an opener -- which is the same question about a different population, so the loop moved here
 * rather than being copied again.
 *
 * NOT IN {@link ClassFiles}, WHICH IS THE OTHER OBVIOUS HOME AND IS THE WRONG ONE. That class
 * is about READING BYTES -- where the build put the classes, how a constant pool is walked --
 * and it makes no claim about what any of it means. Everything here is POLICY: the harness is
 * not production, a class does not call itself, a nested class is its outer class for that
 * purpose. Policy in a bytes-reader is policy nobody reviews when they change the reader.
 *
 * <h2>The two exclusions, and what each is for</h2>
 *
 * THE HARNESS IS NOT PRODUCTION. `shot/` runs only under `-Dmcrecipedump.shot`, so code no
 * player reaches is not production -- and a joint wired only there gives exactly the working
 * screenshot and broken game that #191, #313 and #323 each produced. #323 is the sharpest case:
 * the flow diagram had three green screenshots and no opener, and every artifact saying the
 * feature worked was produced by the only caller.
 *
 * A CLASS DOES NOT COUNT AS ITS OWN CALLER, NESTED CLASSES INCLUDED. A default reinstalling its
 * own default is the exact no-op these gates exist to notice, and `PlannerWidgets.statePanel`
 * calling its own three-argument overload is the same shape one level down.
 *
 * <h2>What no gate built on this can see</h2>
 *
 * THAT THE CALL IS REACHED. A `Methodref` behind a condition that is never true satisfies every
 * question asked here, because the pool records what javac COMPILED rather than what runs. See
 * {@link ClassFiles#methodReferences} for why that is the safe direction, and
 * {@link SeamInstallationTest}'s class note for the four kinds of blindness measured around it.
 */
final class ProductionCallers {

    /** Anything under here is the screenshot harness, not production. See the class note. */
    private static final String HARNESS = ClassFiles.ROOT_PACKAGE + "/shot/";

    private ProductionCallers() {
    }

    /**
     * Fill in, for every `owner.nameDescriptor` key already present, the production classes
     * whose constant pool names it. A key with no caller keeps the empty set it arrived with,
     * which is what every gate reads as "nothing in the shipped mod calls this".
     */
    static void record(Map<String, Set<String>> into) throws IOException {
        record(into, false);
    }

    /**
     * The same walk, with a choice about whether a class counts as its own caller.
     *
     * WHY THE CHOICE EXISTS AT ALL, since the default is the rule this file argues for above.
     * The two gates built on this walk are asking different questions and the self-call means
     * opposite things in each:
     *
     * <ul>
     * <li>{@link SeamInstallationTest} asks whether anything INSTALLS a seam. A class installing
     *     its own seam is a default reinstalling its own default -- the exact no-op that whole
     *     file exists to notice -- so self must not count, and `countDeclaringClass` is false.</li>
     * <li>{@link PanelReachabilityTest} asks whether anything OPENS a panel. A screen class that
     *     builds a panel and opens it a line later is the production path, not a no-op:
     *     `PlannerScreen.openPlan` calling `planPanel` and `BrowseScreen`'s tab handler calling
     *     `panelFor` are both exactly that shape. Excluding self there makes the gate report
     *     correct code as broken, and a gate that fails on correct code gets deleted rather than
     *     fixed -- which this repository has already watched happen once, to #223's first seam
     *     test. So that gate passes true.</li>
     * </ul>
     *
     * NEITHER SETTING WEAKENS THE OTHER GATE, and that is worth stating because a shared walk
     * with a flag invites the assumption that it does. #323's own defect is red either way:
     * nothing in `PlannerWidgets` called `flowPanel` or `todoPanel`, so counting its self-calls
     * finds no opener for either.
     */
    static void record(Map<String, Set<String>> into, boolean countDeclaringClass)
            throws IOException {
        for (File file : ClassFiles.under(ClassFiles.ROOT_PACKAGE)) {
            String caller = ClassFiles.internalName(file);
            if (isHarness(caller)) {
                continue;
            }
            for (String reference : ClassFiles.methodReferences(ClassFiles.read(file))) {
                Set<String> callers = into.get(reference);
                if (callers != null
                        && (countDeclaringClass || !isSelf(ownerOf(reference), caller))) {
                    callers.add(caller);
                }
            }
        }
    }

    /** A caller map with every key present and no callers found yet. */
    static Map<String, Set<String>> emptyMap(Set<String> references) {
        Map<String, Set<String>> map = new LinkedHashMap<String, Set<String>>();
        for (String reference : references) {
            map.put(reference, new LinkedHashSet<String>());
        }
        return map;
    }

    static boolean isHarness(String internalName) {
        return internalName.startsWith(HARNESS);
    }

    /** True when `caller` is the reference's own class, counting its nested classes as one. */
    static boolean isSelf(String owner, String caller) {
        return caller.equals(owner) || caller.startsWith(owner + "$");
    }

    /**
     * The declaring class of an `owner.nameDescriptor` reference.
     *
     * THE FIRST `.` IS THE SEPARATOR AND THAT IS NOT AN ACCIDENT: an owner is an INTERNAL name,
     * so it is `a/b/C` and cannot contain a dot, while everything after the first one is the
     * method name and its descriptor. Spelled once because it was open-coded at three call
     * sites, and the assumption is worth saying rather than leaving to be re-derived.
     */
    static String ownerOf(String reference) {
        return reference.substring(0, reference.indexOf('.'));
    }

    /**
     * A method's JVM descriptor, which is how a `Methodref` spells it.
     *
     * THE LOOKUP KEY, so getting it wrong means the gate matches nothing and reports every
     * entry orphaned -- or, with the population assertions relaxed, finds nothing to check and
     * passes. `SeamInstallationTest.aDescriptorIsSpeltTheWayTheConstantPoolSpellsIt` asserts it
     * against literals rather than against a second call to this code.
     */
    static String descriptorOf(java.lang.reflect.Method method) {
        StringBuilder descriptor = new StringBuilder("(");
        for (Class<?> parameter : method.getParameterTypes()) {
            descriptor.append(typeDescriptor(parameter));
        }
        return descriptor.append(')').append(typeDescriptor(method.getReturnType())).toString();
    }

    static String typeDescriptor(Class<?> type) {
        if (type.isArray()) {
            return "[" + typeDescriptor(type.getComponentType());
        }
        if (!type.isPrimitive()) {
            return "L" + internalNameOf(type) + ";";
        }
        if (type == void.class) {
            return "V";
        }
        if (type == boolean.class) {
            return "Z";
        }
        if (type == byte.class) {
            return "B";
        }
        if (type == char.class) {
            return "C";
        }
        if (type == short.class) {
            return "S";
        }
        if (type == int.class) {
            return "I";
        }
        if (type == long.class) {
            return "J";
        }
        return type == float.class ? "F" : "D";
    }

    static String internalNameOf(Class<?> type) {
        return type.getName().replace('.', '/');
    }

    /**
     * Load without INITIALISING, which matters: several of these classes register a keybind or
     * touch Forge state in a static initialiser, and running that here would either throw or
     * quietly change global state for every test after this one.
     *
     * A class that cannot be loaded at all -- one naming a mod that is `compileOnly`, say -- is
     * skipped by the caller rather than failed. It cannot be introspected, and failing on it
     * would make a gate depend on which pack jars happen to be on the classpath.
     */
    static Class<?> load(String internalName) {
        try {
            return Class.forName(internalName.replace('/', '.'), false,
                                 ProductionCallers.class.getClassLoader());
        } catch (Throwable absent) {
            return null;
        }
    }
}
