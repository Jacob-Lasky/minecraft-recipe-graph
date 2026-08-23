package io.github.jacoblasky.recipedump;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
 * EVERY PANEL A BUILDER CAN PRODUCE MUST BE OPENABLE WITHOUT THE SCREENSHOT HARNESS. #323
 *
 * <h2>The defect class this exists for</h2>
 *
 * A panel builder is a non-private static method that returns a `ModularPanel` or a subclass of
 * one: a whole window, built from data, ready to be opened. The failure mode is that NOTHING
 * OPENS IT except `shot/` and the tests -- so the window exists, renders, photographs, is
 * asserted over, and no player can ever see it. Both halves of that definition were narrower
 * once and each narrowing was an open door; see the population section below.
 *
 * That is what #323 was. `PlannerWidgets.flowPanel` -- the flow diagram, the visual half of
 * #19's MVP -- had exactly three callers, all of them in `PlannerShot`, and
 * `PlannerScreen.openPanel`'s own javadoc says what those are for: "for the screenshot harness
 * to photograph". `todoPanel` was the same, and worse, because its WRITE path shipped: "Add to
 * TODO" in the node menu sent `PlanBookEdit.SET_TODO` to the server, so a player could add to a
 * list they could never open. Mod 0.10.0 shipped both.
 *
 * <h2>Why the existing gates could not see it</h2>
 *
 * {@link SeamInstallationTest} guards SETTABLE SEAMS -- a producer and a consumer joined by a
 * field with a do-nothing default. A panel nobody opens is not that shape: there is no field, no
 * default, and nothing is inconsistent. Its three gates were all green across both defects.
 *
 * And `shot/` lives in `mod/src/main/java`, so any "is there a production caller" scan that
 * forgets the exclusion counts the harness as production. That is this repository's documented
 * exclude-`shot/` trap and #323 is its worked example: the harness performs the wiring
 * production omits, so every screenshot said the feature worked and every screenshot was
 * produced by the only caller. See {@link ProductionCallers} for the exclusion and why it is
 * shared rather than restated here.
 *
 * <h2>Why the population is DERIVED and why it is derived BY RETURN TYPE</h2>
 *
 * A test naming `flowPanel` and `todoPanel` would go green the day #323 was fixed and stay green
 * through the third one. So the builders are enumerated from the compiled classes, and the
 * enumeration is a static method whose return type IS a `ModularPanel` -- the shape of "a window
 * this code can build" -- rather than a name ending in `Panel`.
 *
 * "IS A" AND NOT "IS SPELT", AND THE DIFFERENCE WAS AN OPEN DOOR. An exact-name match on the
 * return type misses `Dialog`, `MenuPanel` and `ConfigPanel`, which the shipped ModularUI jar
 * carries and which are all panels -- so a builder returning the more specific type, which is
 * what a careful author would write, was a whole window this gate could not see. It asks
 * `ModularPanel.class.isAssignableFrom` now.
 *
 * ANY VISIBILITY BUT PRIVATE, for the same kind of reason. `BrowseScreen.panelFor` is
 * package-private, and a package-private builder whose only caller is a same-package TEST is
 * #323's shape exactly -- the visibility keyword has nothing to do with whether a player can get
 * there. Private is the one real exclusion and it is not a loophole: a private method can only be
 * called from inside its own class, so its reachability is entirely that class's business and
 * what this gate checks is the class's own non-private entry points. See
 * {@link #aPrivateBuilderIsNotInThePopulation}.
 *
 * THE NAME WOULD HAVE MISSED THREE OF THEM. `nodeMenu`, `rowMenu`, `recipePicker` and
 * `modPicker` all return a whole panel and none of them is called `*Panel`; each is reachable
 * today through a `LivePlannerActions` or `LiveMachinesActions` handler, and each would be
 * exactly as invisible as the diagram was if its opener were deleted. A gate that only watches
 * the surfaces that happen to be named consistently is a gate whose coverage depends on
 * somebody's naming discipline at the moment the next window is written.
 *
 * {@link #KNOWN_BUILDERS} is not the list under test -- it is a floor, asserted so a predicate
 * that quietly stops matching anything cannot pass by finding nothing. That is
 * `SeamInstallationTest.KNOWN_SEAMS`' rule and it is here for its reason.
 *
 * <h2>What this cannot see</h2>
 *
 * THAT THE OPENER IS REACHABLE BY A PLAYER, as opposed to merely compiled. A button behind a
 * condition that is never true satisfies this, because a constant pool records what javac
 * compiled.
 *
 * AND THAT A CLASS OPENING ONLY ITS OWN PANEL GOES ANYWHERE. This gate counts the declaring class
 * as an opener -- it has to, or `PlannerScreen.openPlan` calling `planPanel` reads as unreachable
 * (see {@link #COUNT_DECLARING_CLASS}) -- so a builder called solely from inside its own class
 * passes on that call alone, and whether anything reaches that class's own entry point is a
 * question this gate does not ask.
 *
 * WHAT ANSWERS BOTH OF THOSE IS A PICTURE, and for the two panels #323 was about there is one:
 * `planner-view-flow` and `planner-view-todo` park the real cursor over each button on an Xvfb
 * display, click through `ModularScreen.onMousePressed` so the dispatch is by position, and
 * refuse the run unless the panel that opened is the one the button claims. This gate is the net
 * that catches the next window nobody remembers to photograph.
 *
 * AND IT SAYS NOTHING ABOUT THE PANEL BEING USABLE ONCE OPEN. #323's fix had a second half for
 * exactly that reason: `flowPanel` took a canvas that only the harness ever sized, so the first
 * production opener would have opened a correctly-wired window around a zero-sized diagram.
 * `FlowCanvas.forPanel` and `PlannerLayoutTest` cover that; a caller count cannot.
 */
public class PanelReachabilityTest {

    /** What a panel builder returns. Internal name, because that is how a descriptor spells it. */
    private static final String PANEL = "com/cleanroommc/modularui/screen/ModularPanel";

    /**
     * A class DOES count as an opener of its own panel. See {@link ProductionCallers#record}.
     *
     * THE OPPOSITE OF {@link SeamInstallationTest}'S RULE, ON PURPOSE. A screen that builds a
     * panel and opens it a line later is the production path -- `PlannerScreen.openPlan` into
     * `planPanel`, `BrowseScreen`'s tab handler into `panelFor` -- and calling those unreachable
     * would be a gate failing on correct code. Named rather than passed as a bare `true`, so the
     * two call sites below cannot drift apart and the reason has somewhere to live.
     */
    private static final boolean COUNT_DECLARING_CLASS = true;

    /**
     * Builders that must still be found by the scan, so a broken predicate cannot pass by
     * matching nothing. NOT the list under test -- see the class note.
     *
     * THE TWO #323 DEFECTS PLUS THREE THAT WERE ALWAYS FINE, deliberately mixed. A floor made
     * only of the broken ones would stop discriminating the moment they were fixed; including
     * `plannerPanel`, `machinesPanel` and `graphPanel` -- reachable since the day each was
     * written -- means the scan has to keep finding builders it has never had a complaint about.
     */
    private static final List<String> KNOWN_BUILDERS = Arrays.asList(
            "client/planner/PlannerWidgets.flowPanel",
            "client/planner/PlannerWidgets.plannerPanel",
            "client/planner/PlannerWidgets.todoPanel",
            "client/machines/MachinesWidgets.machinesPanel",
            "client/browse/GraphWidgets.graphPanel");

    /** One builder, keyed the way a `Methodref` spells it. */
    private static final class Builder {

        final String owner;
        final String method;
        /** JVM descriptor, so an overload cannot be mistaken for its sibling. */
        final String descriptor;

        Builder(String owner, Method method) {
            this.owner = owner;
            this.method = method.getName();
            this.descriptor = ProductionCallers.descriptorOf(method);
        }

        String reference() {
            return owner + "." + method + descriptor;
        }

        /** `package/Class.method`, which is how a human names it. */
        String label() {
            return owner + "." + method;
        }
    }

    /**
     * What one pass over the compiled classes found.
     *
     * THE SKIPPED LIST IS CARRIED RATHER THAN SWALLOWED, for `SeamInstallationTest.Scan`'s
     * reason: a class naming a `compileOnly` mod cannot be introspected on a JUnit classpath at
     * all, and a classpath change that started skipping everything would otherwise shrink this
     * gate to nothing while it went on passing.
     */
    private static final class Scan {

        final Map<String, Builder> builders = new LinkedHashMap<String, Builder>();
        final List<String> introspected = new ArrayList<String>();
        final List<String> skipped = new ArrayList<String>();

        @Override
        public String toString() {
            return introspected.size() + " classes introspected, " + skipped.size()
                    + " skipped (" + skipped + ")";
        }
    }

    // -- the gate ------------------------------------------------------------------------------

    @Test
    public void everyPanelBuilderIsOpenedBySomethingOutsideTheHarness() throws Exception {
        Scan scan = findBuilders();
        assertTheScanFoundTheBuildersItIsKnownToHave(scan);
        Map<String, Set<String>> openers = ProductionCallers.emptyMap(scan.builders.keySet());
        ProductionCallers.record(openers, COUNT_DECLARING_CLASS);

        List<String> unreachable = new ArrayList<String>();
        // PRINTED ON SUCCESS, NOT ONLY ON FAILURE, which is `SeamInstallationTest`'s rule: a
        // derived gate that says nothing when it passes cannot be told from one whose predicate
        // has stopped matching. The floor assertion above catches a collapse to zero; this shows
        // a drift from twelve builders to eight, which no assertion can, because the right
        // number is not knowable in advance.
        System.out.println("panel builders found (" + scan.builders.size() + "), " + scan);
        for (Builder builder : scan.builders.values()) {
            Set<String> found = openers.get(builder.reference());
            System.out.println("  " + builder.label() + " <- "
                    + (found.isEmpty() ? "NOTHING" : found.toString()));
            if (found.isEmpty()) {
                unreachable.add(builder.label() + builder.descriptor);
            }
        }
        assertEquals("these builders make a whole window and nothing outside `shot/` and the"
                     + " tests ever opens one, so the surface behind each is present, rendered,"
                     + " photographed and unreachable by a player -- which is #323 exactly"
                     + " (give it an opener, or delete the builder)",
                     Collections.<String>emptyList(), unreachable);
    }

    // -- teeth ---------------------------------------------------------------------------------

    /**
     * THE HARNESS IS SCANNED FOR CLASSES AND THEN CONTRIBUTES NO OPENERS.
     *
     * Asserted on a REAL, NAMED CALL rather than on the exclusion in the abstract, because this
     * is the half of the gate #323 turned on. `PlannerShot` opens the flow panel three times; if
     * `shot/` counted, the gate would have been green through both defects and this file would
     * be decoration. The positive half -- that the reference really is in `PlannerShot`'s pool --
     * is what stops this passing because the descriptor is misspelt.
     */
    @Test
    public void theHarnessOpensTheFlowPanelAndStillDoesNotCount() throws Exception {
        String flowPanel = ClassFiles.ROOT_PACKAGE + "/client/planner/PlannerWidgets.flowPanel"
                + "(Lcom/cleanroommc/modularui/api/widget/IWidget;)L" + PANEL + ";";
        String shot = ClassFiles.ROOT_PACKAGE + "/shot/PlannerShot";

        Set<String> fromHarness = ClassFiles.methodReferences(
                ClassFiles.read(classFile("shot/PlannerShot")));
        assertTrue("PlannerShot opens the flow panel, and if this reference cannot be found the"
                   + " descriptor above is wrong and the whole gate is matching nothing. Saw: "
                   + fromHarness, fromHarness.contains(flowPanel));
        assertTrue("`shot/` must be excluded, or a harness-only opener reads as production",
                   ProductionCallers.isHarness(shot));

        Map<String, Set<String>> openers = ProductionCallers.emptyMap(
                new LinkedHashSet<String>(Collections.singletonList(flowPanel)));
        ProductionCallers.record(openers, COUNT_DECLARING_CLASS);
        assertFalse("the walk found no opener at all for the flow panel, so either the fix has"
                    + " been reverted or the walk is broken; the gate above says which",
                    openers.get(flowPanel).isEmpty());
        assertFalse("the harness contributed itself as an opener",
                    openers.get(flowPanel).contains(shot));
    }

    /**
     * A PRIVATE BUILDER IS NOT A PANEL THIS GATE OWNS, and one exists to prove the filter runs.
     *
     * `PlannerWidgets.menuPanel` assembles the node menu and the row menu and is private, so the
     * only things that can call it are already inside the class that has to be reachable for its
     * own reasons. Counting it would report a defect that cannot exist -- a gate that fails on
     * correct code gets deleted rather than fixed, which is the mistake `SeamInstallationTest`
     * records having made once already with a class-narrowed scan.
     *
     * AND THE PACKAGE-PRIVATE HALF IS ASSERTED HERE TOO, because "not private" is a wider filter
     * than "public" and the widening is the thing that could silently stop working.
     * `BrowseScreen.panelFor` has no visibility keyword at all and must be in the population.
     */
    @Test
    public void aPrivateBuilderIsNotInThePopulation() throws Exception {
        Scan scan = findBuilders();
        List<String> labels = new ArrayList<String>();
        for (Builder builder : scan.builders.values()) {
            labels.add(builder.label());
        }
        String widgets = ClassFiles.ROOT_PACKAGE + "/client/planner/PlannerWidgets";
        assertTrue("the public builders on PlannerWidgets must be in the population, or the"
                   + " return-type filter is matching nothing; found " + labels,
                   labels.contains(widgets + ".plannerPanel"));
        assertTrue("`BrowseScreen.panelFor` is package-private and must be counted, or the"
                   + " visibility filter has narrowed back to public; found " + labels,
                   labels.contains(ClassFiles.ROOT_PACKAGE + "/client/BrowseScreen.panelFor"));
        assertFalse("`menuPanel` is private and must not be counted; found " + labels,
                    labels.contains(widgets + ".menuPanel"));
    }

    /**
     * THE RETURN-TYPE PREDICATE REALLY ACCEPTS A SUBCLASS, fed a case it must accept.
     *
     * WITHOUT THIS, H1 IS A CLAIM. Nothing in this mod returns a `ModularPanel` subclass today,
     * so swapping `isAssignableFrom` back for name equality would not move the population by one
     * entry and no test would notice -- the hardening would be decorative and would stay that way
     * until the day it mattered. `Dialog` is a real `ModularPanel` subclass in the ModularUI jar,
     * which is the shape the review named, so the predicate is asked about it directly.
     *
     * AND THE NEGATIVE HALF IS DOING THE WORK. A predicate that answered true to everything would
     * pass the first assertion forever, so `IWidget` -- a panel IS one, in the other direction --
     * and a type from outside the framework entirely must both be refused.
     */
    @Test
    public void aPanelSubclassCountsAsAPanelAndAWidgetDoesNot() throws Exception {
        assertTrue("a Dialog IS a ModularPanel, and a builder returning one is a whole window"
                   + " this gate must be able to see",
                   returnsAPanel(com.cleanroommc.modularui.widgets.Dialog.class));
        assertTrue("the exact type still counts, obviously",
                   returnsAPanel(com.cleanroommc.modularui.screen.ModularPanel.class));
        assertFalse("a panel is an IWidget, not the reverse; accepting the supertype would put"
                    + " every widget builder in the mod into this population",
                    returnsAPanel(com.cleanroommc.modularui.api.widget.IWidget.class));
        assertFalse("a predicate that says yes to anything asserts nothing",
                    returnsAPanel(String.class));
    }

    /**
     * BOTH OVERLOADS OF `statePanel` ARE SEPARATE ENTRIES, which is what keying by descriptor
     * buys and is not hypothetical here.
     *
     * `PlannerWidgets.statePanel(PlannerState)` is opened by `PlannerScreen`, and the
     * three-argument one is opened by `MachinesWidgets` and by its own one-argument sibling. If
     * the two collapsed onto one key, the sibling's call would make the pair look reachable
     * whichever of them had lost its opener -- and a class does not count as its own caller
     * precisely so that cannot happen. This asserts the two keys exist separately.
     */
    @Test
    public void anOverloadIsItsOwnEntry() throws Exception {
        Scan scan = findBuilders();
        List<String> statePanels = new ArrayList<String>();
        for (Builder builder : scan.builders.values()) {
            if (builder.label().endsWith("PlannerWidgets.statePanel")) {
                statePanels.add(builder.descriptor);
            }
        }
        assertEquals("PlannerWidgets declares two `statePanel` overloads and they must be two"
                     + " entries; got " + statePanels, 2, statePanels.size());
    }

    // -- finding the builders ------------------------------------------------------------------

    /**
     * What makes something a panel builder, in one place.
     *
     * PUBLIC, STATIC, AND RETURNING A `ModularPanel`. Each clause earns its place:
     *
     * <ul>
     * <li>public, because a private one can only be called from a class that already needs an
     *     opener of its own -- see {@link #aPrivateBuilderIsNotInThePopulation};</li>
     * <li>static, because that is how every widget file in this mod builds a window: the whole
     *     `PlannerWidgets` contract is functions of plain data with no screen anywhere;</li>
     * <li>returning the panel rather than opening it, because a method that OPENS one is the
     *     opener this gate is looking for, not the thing that needs one.</li>
     * </ul>
     *
     * BY REFLECTION AND NOT BY PARSING DESCRIPTORS OUT OF THE POOL, because the population is a
     * question about DECLARATIONS and reflection answers it exactly, including which are public.
     * The callers are a question about call sites, which reflection cannot answer at all -- so
     * that half is the constant-pool walk. `SeamInstallationTest.findSeams` splits the same way.
     */
    private static Scan findBuilders() throws Exception {
        Scan scan = new Scan();
        for (File file : ClassFiles.under(ClassFiles.ROOT_PACKAGE)) {
            String internal = ClassFiles.internalName(file);
            if (ProductionCallers.isHarness(internal)) {
                continue;
            }
            Class<?> owner = ProductionCallers.load(internal);
            if (owner == null) {
                scan.skipped.add(internal);
                continue;
            }
            try {
                for (Method method : owner.getDeclaredMethods()) {
                    if (!isPanelBuilder(method)) {
                        continue;
                    }
                    Builder builder = new Builder(internal, method);
                    scan.builders.put(builder.reference(), builder);
                }
            } catch (NoClassDefFoundError absent) {
                // A signature naming a type from a `compileOnly` mod. Reflection cannot read the
                // member list at all when one entry will not resolve, so the whole class goes
                // unexamined -- recorded rather than swallowed, because a classpath change that
                // started skipping everything would otherwise pass silently.
                scan.skipped.add(internal);
                continue;
            }
            scan.introspected.add(internal);
        }
        return scan;
    }

    private static boolean isPanelBuilder(Method method) {
        int modifiers = method.getModifiers();
        if (Modifier.isPrivate(modifiers) || !Modifier.isStatic(modifiers)
                || method.isSynthetic() || method.isBridge()) {
            return false;
        }
        return returnsAPanel(method.getReturnType());
    }

    /**
     * Whether this return type IS a panel, subclasses included.
     *
     * ASSIGNABILITY AND NOT EQUALITY, WHICH IS AN EVASION ROUTE THAT WAS OPEN. An exact-name
     * match asks "is the return type spelt `ModularPanel`", and the shipped ModularUI jar
     * carries `Dialog`, `MenuPanel` and `ConfigPanel`, all of them panels -- so a builder
     * returning one of those was a whole window this gate could not see, and the more specific
     * return type is the one a careful author would write. Nothing in this mod returns a
     * subclass today; the gate's printed inventory is what shows that, and it is why the
     * inventory is printed on success rather than only on failure.
     *
     * BY REFLECTION RATHER THAN BY WALKING NAMES, because the hierarchy is the question and
     * `Class.isAssignableFrom` is the answer to it. A panel type that fails to load at all
     * cannot be a builder's return type on a classpath that compiled, so there is no third case.
     */
    private static boolean returnsAPanel(Class<?> returned) {
        Class<?> panel = ProductionCallers.load(PANEL);
        assertTrue("the ModularPanel class must be loadable, or this predicate matches nothing"
                   + " and the whole gate passes on anything", panel != null);
        return panel.isAssignableFrom(returned);
    }

    private static void assertTheScanFoundTheBuildersItIsKnownToHave(Scan scan) {
        // ASSERT THE POPULATION BEFORE THE PROPERTY. A scan that introspected nothing finds no
        // unreachable builders and reports success, which is this whole file's own failure mode
        // wearing the gate's clothes.
        assertTrue("only " + scan, scan.introspected.size() >= 40);
        Set<String> found = new LinkedHashSet<String>();
        for (Builder builder : scan.builders.values()) {
            found.add(builder.label().substring(ClassFiles.ROOT_PACKAGE.length() + 1));
        }
        List<String> missing = new ArrayList<String>(KNOWN_BUILDERS);
        missing.removeAll(found);
        assertEquals("the scan stopped recognising a panel builder it is known to have, so it is"
                     + " no longer looking for the right shape. Found: " + found + "; " + scan,
                     Collections.<String>emptyList(), missing);
    }

    private static File classFile(String relative) {
        File file = new File(ClassFiles.classesRoot(),
                             ClassFiles.ROOT_PACKAGE + "/" + relative + ".class");
        assertTrue("expected a class at " + file, file.isFile());
        return file;
    }
}
