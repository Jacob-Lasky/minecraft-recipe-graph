package io.github.jacoblasky.recipedump.shot;

import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.viewport.LocatedWidget;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The widgets ModularUI has resolved under the live cursor, read WITHOUT `getAllHovered()`.
 *
 * <h2>DO NOT USE `ModularGuiContext.getAllHovered()`. IT CANNOT BE ITERATED TO THE END.</h2>
 *
 * In `modularui-3.1.5` the iterator behind that method recurses into itself instead of ending,
 * so a for-each that runs off the last element does not terminate -- it dies with a
 * `StackOverflowError` and takes the client with it. This is not conditional on anything in our
 * widget tree; it is unconditional on exhaustion, and a probe cannot avoid the exhausting call
 * because the overflow happens inside the very `hasNext()` that would have reported the end.
 *
 * THE BYTECODE, since a claim this strong about somebody else's jar has to be checkable.
 * `getAllHovered()` returns the `hoveredIterable` field, whose `iterator()` is a lambda
 * building `ModularGuiContext$1`, an `AbstractIterator` whose `computeNext()` compiles to:
 *
 * <pre>
 *   24: aload_0; getfield it; invokeinterface Iterator.hasNext()Z
 *   33: ifeq 57
 *   36: ... it.next().getElement()          &lt;- an element
 *   54: goto 61
 *   57: aload_0; invokevirtual ModularGuiContext$1.computeNext()   &lt;- ITSELF, not endOfData()
 * </pre>
 *
 * The whole ternary is source line 71, which is why the crash stack is 1024 identical frames at
 * `ModularGuiContext.java:71` with nothing of ours interleaved. `endOfData` appears NOWHERE in
 * that class's constant pool, and `AbstractIterator` is documented to require it -- without it
 * the state never reaches DONE. `ModularGuiContext$HoveredIterable$1`, the sibling iterator
 * behind `getAllBelowMouse()`, does call `endOfData()`, so the defect is one iterator and not
 * the pattern. `HoveredWidgetsTest` asserts all of this against the dep jar on the classpath,
 * so a ModularUI upgrade that fixes it will say so rather than leaving this in place forever.
 * The finding, the bytecode and what to do on a ModularUI bump are recorded in issue #337.
 *
 * NO PLAYER CAN REACH IT AND THAT IS WHY THIS LIVES IN `shot/`. Nothing inside `modularui`
 * calls `getAllHovered()` -- the tooltip renderer is per-widget (`Widget.drawForeground` draws
 * when `isHoveringFor(showUpTimer)`), and the hover bookkeeping walks the `hovered` list
 * directly. The only caller in existence was this harness's own verdict walk.
 *
 * <h2>SO IT READS THE FIELD THE ITERATOR WOULD HAVE READ</h2>
 *
 * `ModularGuiContext$1` iterates `ModularGuiContext.hovered`, reached through the synthetic
 * `access$100`. That field IS the set the probe wants -- ModularUI's own per-frame resolution
 * from the live cursor, innermost widget first, already filtered by `isValid`, `canHover`,
 * `isHovering` and `canHoverThrough` -- and it is a plain `List`, so iterating it terminates.
 * Reading it is the same data by the same order with none of the recursion.
 *
 * <h2>WHO FILLS IT, AND WHEN -- THE FACT THAT MAKES THIS PROBE HONEST RATHER THAN VACUOUS</h2>
 *
 * The obvious objection to reading a field instead of calling a method is that the field might
 * be stale, or empty because only the call would have populated it. It is neither, and the
 * reason is that MODULARUI FILLS IT ITSELF, off the vanilla client loop, with nothing of ours
 * involved. Traced in `modularui-3.1.5`'s bytecode:
 *
 * <pre>
 *   MinecraftMixin.timer(CallbackInfo)        @Inject into Minecraft.runGameLoop,
 *                                             At(INVOKE Profiler.startSection, Shift.AFTER)
 *     for i in 0 .. min(20, proxy.getTimer60Fps().elapsedTicks):
 *       ClientScreenHandler.onFrameUpdate()   returns early unless a ModularScreen is current
 *         ModularScreen.onFrameUpdate()
 *           ModularGuiContext.onFrameUpdate() putfield `hovered` at 207, from the private
 *                                             getHoveredWidgets(getAllHoveredWidgetsList(false));
 *                                             or Collections.emptyList() at 268 when the mouse
 *                                             is over nothing
 * </pre>
 *
 * So `hovered` is rebuilt from the live cursor about sixty times a second, on ModularUI's own
 * mixin, independently of our `RenderTick` listener and of anything the harness calls. A verdict
 * that has polled for frames after parking the cursor is reading a set that has been rebuilt many
 * times since the move. There is no path where calling `getAllHovered()` would have populated
 * something that reading the field does not see -- the broken iterator reads this very field.
 *
 * IT IS REPLACED, NOT MUTATED, which is why every verdict reads it exactly ONCE and passes the
 * result down. `ModularGuiContext$1` opens by comparing the current `hovered` against the one it
 * captured BY REFERENCE and throwing `ConcurrentModificationException` if they differ, which is
 * upstream's own evidence that the field is swapped wholesale each frame.
 *
 * NOT RECOMPUTED FROM `PanelManager.getAllHoveredWidgetsList`, WHICH IS THE OBVIOUS
 * ALTERNATIVE AND IS NOT SAFE HERE. That call is only half of it: `ModularGuiContext` then runs
 * the list through the private `getHoveredWidgets`, which fires `onMouseStartHover`, sets the
 * resize cursor icon and calls `setHoveredSlot` on the screen wrapper. A probe that recomputes
 * would be driving the hover state it is trying to observe.
 *
 * AND NOT BY ASKING OUR OWN WIDGETS `isHovering()`, which is the other obvious alternative and
 * is the weak question `PlannerShot.tooltipVerdict`'s javadoc argues against at length: it is a
 * statement about the widget tree and was green before a cursor existed.
 *
 * REFLECTION IS GUARDED AND REPORTS WHY, never silently empty. An empty hovered list and an
 * unreadable one mean opposite things to a verdict -- "the cursor is over nothing" is a
 * failure, "this ModularUI build moved the field" is not -- so {@link Hovered} keeps them
 * apart and makes the caller spend the difference.
 */
final class HoveredWidgets {

    /**
     * `ModularGuiContext.hovered`, or null when this build does not have it.
     *
     * RESOLVED ONCE AND TYPE-CHECKED, so the per-call path cannot be surprised by a field of
     * the same name holding something else. A build that renames it, or declares it as
     * anything but a `List`, lands in {@link #UNAVAILABLE} and every verdict downgrades with
     * the reason printed rather than guessing.
     */
    private static final Field HOVERED;

    /** Why {@link #HOVERED} is null, or null when it is not. */
    private static final String UNAVAILABLE;

    static {
        Field field = null;
        String why = null;
        try {
            field = ModularGuiContext.class.getDeclaredField("hovered");
            if (!List.class.equals(field.getType())) {
                why = "ModularGuiContext.hovered is a " + field.getType().getName()
                      + " in this ModularUI build, not a List";
                field = null;
            } else {
                field.setAccessible(true);
            }
        } catch (Throwable t) {
            why = "ModularGuiContext.hovered is not readable in this ModularUI build: "
                  + t.getClass().getName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
            field = null;
        }
        HOVERED = field;
        UNAVAILABLE = why;
    }

    private HoveredWidgets() {
    }

    /**
     * Null when the reflective read is wired on this ModularUI, else the reason it is not.
     *
     * EXISTS SO `HoveredWidgetsTest` CAN FAIL THE SUITE ON A RENAME, which is the whole point of
     * it being visible at all. Everything else here degrades politely -- a build that moved
     * `hovered` gets a downgraded verdict with the cause printed, never an exception -- and that
     * politeness is exactly what would let a rename sit undetected until someone read a shot log
     * fifteen minutes into a client boot. The test asserts this is null, so the rename lands as a
     * named failure in `mod/src/test/.../shot/HoveredWidgetsTest.java` pointing at THIS file.
     *
     * NOT A RE-DERIVATION OF THE FIELD LOOKUP. The test could ask `ModularGuiContext` for the
     * field itself and does, as an independent check -- but that asserts a fact about the jar,
     * not that THIS class successfully read it. Only the static initialiser's own verdict says
     * whether the probe is wired, and a gate that re-implements the thing it is checking passes
     * while the real one is broken.
     */
    static String fieldUnavailable() {
        return UNAVAILABLE;
    }

    /**
     * What ModularUI says is under the cursor, or the reason it could not be said.
     *
     * TWO STATES AND NOT A NULLABLE LIST, because the third state -- read fine, nothing there
     * -- is a legitimate answer that a null would collapse into the failure case.
     */
    static final class Hovered {

        private final List<Object> elements;
        private final String why;

        private Hovered(List<Object> elements, String why) {
            this.elements = elements;
            this.why = why;
        }

        /** True when {@link #elements()} is ModularUI's answer rather than a missing one. */
        boolean available() {
            return this.elements != null;
        }

        /** The hovered widgets, innermost first. Empty is an answer; see {@link #available()}. */
        List<Object> elements() {
            return this.elements == null ? Collections.<Object>emptyList() : this.elements;
        }

        /** Why there is no answer, for a log line that names the cause. Null when there is one. */
        String why() {
            return this.why;
        }
    }

    /**
     * Every widget ModularUI resolved under the cursor on the frame this is called.
     *
     * ONE READ PER FRAME IS THE INTENDED USE. `hovered` is replaced wholesale by
     * `ModularGuiContext.onFrameUpdate`, so two reads in one verdict are two chances to
     * disagree with each other -- which is the same hazard `getAllHovered()`'s own
     * `ConcurrentModificationException` guard exists to catch, and the reason
     * `PlannerShot.tooltipVerdict` reads once and passes the result down.
     */
    static Hovered underCursor(ModularScreen screen) {
        if (screen == null) {
            return new Hovered(null, "no ModularScreen is current");
        }
        if (HOVERED == null) {
            return new Hovered(null, UNAVAILABLE);
        }
        Object value;
        try {
            value = HOVERED.get(screen.getContext());
        } catch (Throwable t) {
            return new Hovered(null, "ModularGuiContext.hovered would not read: "
                                     + t.getClass().getName());
        }
        if (!(value instanceof List)) {
            return new Hovered(null, "ModularGuiContext.hovered held a "
                                     + (value == null ? "null" : value.getClass().getName()));
        }
        List<Object> out = new ArrayList<Object>();
        for (Object entry : (List<?>) value) {
            if (!(entry instanceof LocatedWidget)) {
                // THE FIELD STILL EXISTS BUT NO LONGER MEANS WHAT THIS READS IT AS, which is
                // exactly the case a silent skip would hide. Reported, not filtered.
                return new Hovered(null, "ModularGuiContext.hovered holds a "
                                         + (entry == null ? "null" : entry.getClass().getName())
                                         + ", not a LocatedWidget");
            }
            out.add(((LocatedWidget) entry).getElement());
        }
        return new Hovered(out, null);
    }
}
