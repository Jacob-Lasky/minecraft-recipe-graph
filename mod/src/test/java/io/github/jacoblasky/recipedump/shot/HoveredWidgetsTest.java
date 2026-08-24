package io.github.jacoblasky.recipedump.shot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import io.github.jacoblasky.recipedump.ClassFiles;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * `ModularGuiContext.getAllHovered()` MAY NOT BE CALLED FROM THIS MOD, and why, in tests.
 *
 * <h2>The crash this file exists to keep fixed</h2>
 *
 * The `planner-machine-tip` verdict screen parked a cursor on a tree row's machine cell in the
 * 365-jar client, walked `getAllHovered()` looking for the widget carrying the tooltip, ran off
 * the end of the walk and killed the client with a `StackOverflowError` -- 1024 identical frames
 * at `ModularGuiContext.java:71` with nothing of ours interleaved. The recursion is in
 * `modularui-3.1.5`: the iterator behind that method returns `computeNext()` instead of
 * `endOfData()` when its backing iterator is spent, so exhausting it never terminates. It is
 * unconditional -- no widget-tree shape is needed -- and it is unavoidable from the caller's
 * side, because the overflow happens inside the `hasNext()` that would have ended the loop.
 *
 * NOTHING IN THE PRODUCTION WIDGET TREE WAS EVER INVOLVED, which the first test below states as
 * bytecode rather than as reassurance: the recursion walks a flat `List` field and calls into no
 * widget, and the only caller that has ever existed anywhere is this harness.
 *
 * <h2>Why a bytecode gate and not a comment</h2>
 *
 * `HoveredWidgets` exists to read the same set without the iterator, and its javadoc carries the
 * full argument. What a javadoc cannot do is stop the next author from writing the obvious call
 * -- it reads as ordinary ModularUI API and compiles fine -- and it cannot notice a ModularUI
 * upgrade that fixes the defect and makes the ban pointless. Both are gates, so both are here.
 *
 * THE THREE GATES BELOW ARE THE CHECKLIST FOR A MODULARUI BUMP, and issue #337 spells out what to
 * do with each answer: the ban itself, the jar-level reason the ban exists, and the field the
 * replacement reads. A red here on an upgrade is the intended outcome, not a regression.
 */
public class HoveredWidgetsTest {

    /** The iterator that recurses, reached only from `getAllHovered()`. */
    private static final String BROKEN_ITERATOR =
            "com/cleanroommc/modularui/screen/viewport/ModularGuiContext$1";

    /** Its sibling behind `getAllBelowMouse()`, which ends correctly. The control. */
    private static final String WORKING_ITERATOR =
            "com/cleanroommc/modularui/screen/viewport/ModularGuiContext$HoveredIterable$1";

    /**
     * Banned BY METHOD NAME rather than by `owner.nameDescriptor`.
     *
     * A call written against a subtype, or against a `GuiContext` that grew the method, would
     * carry a different owner in the pool and be the same crash. The name is the hazard.
     */
    private static final String BANNED = ".getAllHovered(";

    /**
     * A ModularUI call the harness certainly makes, so an empty result cannot pass as clean.
     *
     * `PlannerShot` asks for the current screen on every verdict frame. Without this control the
     * gate below is green when the scan reads no classes, resolves no pool, or silently drops
     * every reference into a dependency -- which is the shape of failure this repository has
     * measured most often, and it looks exactly like correct code.
     */
    private static final String CONTROL =
            "com/cleanroommc/modularui/screen/ModularScreen.getCurrent()"
            + "Lcom/cleanroommc/modularui/screen/ModularScreen;";

    /**
     * No class this mod ships names `getAllHovered`, `shot/` INCLUDED.
     *
     * THE HARNESS IS IN SCOPE HERE AND IS EXCLUDED FROM EVERY OTHER GATE, which is deliberate
     * and is the whole point. `ProductionCallers` skips `shot/` because harness code is not a
     * player-reachable path -- correct for "is this feature wired up", exactly wrong here, since
     * the harness is the only place the crash has ever happened and a shot that kills the client
     * takes the whole build's evidence with it.
     */
    @Test
    public void nothingInTheModCallsTheHoveredIteratorThatCannotEnd() throws IOException {
        List<String> offenders = new ArrayList<String>();
        boolean sawControl = false;
        int scanned = 0;
        for (File file : ClassFiles.under(ClassFiles.ROOT_PACKAGE)) {
            scanned++;
            Set<String> references = ClassFiles.methodReferences(ClassFiles.read(file));
            for (String reference : references) {
                if (reference.contains(BANNED)) {
                    offenders.add(ClassFiles.internalName(file) + " -> " + reference);
                }
            }
            sawControl |= references.contains(CONTROL);
        }
        assertTrue("the scan read no classes at all, so it is not asking anything", scanned > 0);
        assertTrue("the scan cannot see " + CONTROL + ", which the harness certainly calls, so a"
                   + " clean result would mean nothing", sawControl);
        assertEquals("these classes call ModularGuiContext.getAllHovered(), whose iterator"
                     + " recurses forever on exhaustion in modularui-3.1.5 and takes the client"
                     + " with it -- read `HoveredWidgets` and use that instead",
                     "[]", offenders.toString());
    }

    /**
     * The reason for the ban still holds against the ModularUI on the classpath.
     *
     * READ OFF THE JAR SO AN UPGRADE CAN RETIRE THE BAN. If a later ModularUI calls `endOfData()`
     * the way `AbstractIterator` requires, this test fails and says so -- which is the only way
     * a workaround gets removed rather than outliving its cause by years.
     *
     * THE SELF-CALL IS ASSERTED, NOT JUST THE ABSENCE OF `endOfData`. "It does not end properly"
     * and "it calls itself" are different claims and only the second explains a stack of 1024
     * identical frames, so the stack in the crash report and the bytecode here are the same
     * finding stated twice.
     */
    @Test
    public void theIteratorBehindGetAllHoveredStillRecursesInsteadOfEnding() throws IOException {
        byte[] broken = ClassFiles.readResource(BROKEN_ITERATOR);
        assertTrue("ModularGuiContext$1 no longer calls itself, so the StackOverflowError this"
                   + " ban exists for may be fixed -- recheck the jar and retire the ban",
                   ClassFiles.methodReferences(broken)
                           .contains(BROKEN_ITERATOR + ".computeNext()"
                                     + "Lcom/cleanroommc/modularui/api/widget/IWidget;"));
        assertFalse("ModularGuiContext$1 now names endOfData, so `getAllHovered()` may be safe to"
                    + " iterate -- recheck the jar and retire the ban",
                    ClassFiles.contains(broken, "endOfData"));

        // THE CONTROL, AND IT IS DOING THE WORK. A substring search that never matches would
        // satisfy the assertion above no matter what the jar contains. Its sibling iterator --
        // same file, same superclass, behind `getAllBelowMouse()` -- does call `endOfData`, so
        // the search is proved able to find the very thing it just failed to find.
        assertTrue("the endOfData search matches nothing anywhere, so its absence above is a"
                   + " claim about the search rather than about the jar",
                   ClassFiles.contains(ClassFiles.readResource(WORKING_ITERATOR), "endOfData"));
    }

    /** The private field `HoveredWidgets` reads reflectively instead of iterating. */
    private static final String FIELD = "hovered";

    /**
     * A field on the same class that is NOT a `List`. The control on the type comparison.
     *
     * `focusedWidget` is a `LocatedWidget`, so it proves `assertEquals(List.class, ...)` below
     * can actually tell the two apart. Without it, a type check that compared something to
     * itself -- or that read the wrong reflective property -- would be green on a build that had
     * quietly retyped `hovered`, and this jar offers no retyped field to catch that with.
     */
    private static final String NOT_A_LIST_FIELD = "focusedWidget";

    /**
     * A ModularUI that renamed or retyped `hovered` FAILS HERE, not in a prodshot.
     *
     * WHY THIS GATE EXISTS SEPARATELY FROM THE BAN. `HoveredWidgets` reads a private field, and
     * it is deliberately forgiving about that: a build that moved the field yields a downgraded
     * verdict with the cause printed rather than an exception, because a shot must not die on a
     * ModularUI internal it has no contract with. That forgiveness is the hazard -- an upgrade
     * would sail through the suite and surface as a puzzling downgrade line in a log fifteen
     * minutes into a 365-jar client boot, which is the most expensive place in this project to
     * learn anything. So the rename is made loud at suite time instead.
     *
     * ASKED OF `HoveredWidgets` FIRST AND OF THE JAR SECOND, and the order is the point. The
     * static initialiser's own verdict is the only thing that says the PROBE is wired; asking
     * `ModularGuiContext` directly only says the field exists, which a gate that re-implements
     * the lookup would happily report while the real read was broken.
     */
    @Test
    public void theFieldTheProbeReadsIsStillOnThisModularUiAndStillAList() {
        assertNull("HoveredWidgets could not wire its reflective read of ModularGuiContext."
                   + FIELD + " on this ModularUI, so every hover verdict will downgrade instead"
                   + " of asserting. Fix mod/src/main/java/io/github/jacoblasky/recipedump/shot/"
                   + "HoveredWidgets.java -- do not wait for a prodshot to tell you. Cause: "
                   + HoveredWidgets.fieldUnavailable(),
                   HoveredWidgets.fieldUnavailable());

        Field hovered;
        try {
            hovered = ModularGuiContext.class.getDeclaredField(FIELD);
        } catch (NoSuchFieldException absent) {
            fail("ModularGuiContext." + FIELD + " is gone in this ModularUI. `HoveredWidgets`"
                 + " reads it because `getAllHovered()` cannot be iterated to the end; find what"
                 + " the field is called now, or -- if the iterator was fixed -- retire the whole"
                 + " workaround. See mod/src/main/java/io/github/jacoblasky/recipedump/shot/"
                 + "HoveredWidgets.java");
            return;
        }
        assertEquals("ModularGuiContext." + FIELD + " is no longer a List, so `HoveredWidgets`"
                     + " cannot walk it. See mod/src/main/java/io/github/jacoblasky/recipedump/"
                     + "shot/HoveredWidgets.java", List.class, hovered.getType());

        // THE CONTROL ON THAT COMPARISON, and it is doing real work: this jar has no retyped
        // `hovered` to catch a broken type check with, so the check is proved against a field
        // that really is something else.
        assertNotEquals("the type check cannot discriminate, so the assertion above is a claim"
                        + " about itself", List.class,
                        fieldType(ModularGuiContext.class, NOT_A_LIST_FIELD));
    }

    /** The declared type of a field that must exist, so a typo in a control cannot pass as one. */
    private static Class<?> fieldType(Class<?> owner, String name) {
        try {
            return owner.getDeclaredField(name).getType();
        } catch (NoSuchFieldException absent) {
            fail(owner.getName() + "." + name + " is the control for the type check and is gone;"
                 + " pick another non-List field on that class rather than dropping the control");
            return null;
        }
    }

    /**
     * With no screen open the read reports WHY rather than reporting an empty hover set.
     *
     * THE TWO MUST NOT COLLAPSE, which is the whole reason `Hovered` is a pair and not a
     * nullable list: "the cursor is over nothing" fails `planner-machine-tip` and "this build
     * would not say" downgrades it, and a verdict screen that confuses them either cries wolf
     * or goes quiet. `elements()` is still empty rather than null so a caller that ignores the
     * distinction gets a harmless loop instead of a NullPointerException inside a shot.
     */
    @Test
    public void anUnreadableHoverSetSaysWhyAndIsNotAnEmptyOne() {
        HoveredWidgets.Hovered hovered = HoveredWidgets.underCursor(null);
        assertFalse(hovered.available());
        assertNotNull("an unavailable read must name its cause", hovered.why());
        assertTrue("the cause must name the screen, which is what was missing: " + hovered.why(),
                   hovered.why().contains("ModularScreen"));
        assertEquals(0, hovered.elements().size());
    }
}
