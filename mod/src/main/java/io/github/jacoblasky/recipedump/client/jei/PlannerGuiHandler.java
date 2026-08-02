package io.github.jacoblasky.recipedump.client.jei;

import java.awt.Rectangle;
import java.util.Collection;
import java.util.List;
import mezz.jei.api.gui.IGlobalGuiHandler;

/**
 * Tells JEI which parts of the screen the planner is using, so it draws its item list clear
 * of them.
 *
 * <h2>Why this is a GLOBAL handler and not an advanced one</h2>
 *
 * The obvious choice is `IAdvancedGuiHandler`, and it CANNOT WORK HERE. Checked against
 * HadEnoughItems 4.28.1 with `javap`, its signature is
 * `IAdvancedGuiHandler&lt;T extends GuiContainer&gt;` -- it only ever fires for a screen that
 * is a `GuiContainer`. The planner is opened through `ClientGUI.open(ModularScreen)`, which
 * wraps into `GuiScreenWrapper extends GuiScreen`, so an advanced handler registered for it
 * would compile, register, and silently never be called.
 *
 * `IGlobalGuiHandler` is the one that applies to any screen: `getGuiExtraAreas()` with no
 * argument, consulted whatever is open. DO NOT "correct" this back to an advanced handler on
 * the strength of a JEI tutorial; the public docs describe a different version and the jar in
 * the pack is the authority.
 *
 * <h2>Why the areas come from a seam</h2>
 *
 * The rectangle is a fact about the widget tree, and computing it here would put layout
 * knowledge in the JEI package. So the screen tells {@link PlannerHooks} where it is and this
 * reads it. Until the planner wires that up, the answer is empty -- which means JEI lays out
 * exactly as it does today, rather than pretending to avoid a panel it cannot see.
 */
public final class PlannerGuiHandler implements IGlobalGuiHandler {

    /**
     * EMPTY WHEN THE PLANNER IS CLOSED, never null.
     *
     * JEI calls this every frame on every screen, so it is a hot path in the literal sense.
     * An empty list is also the honest answer for "the planner is not open": handing JEI a
     * stale rectangle would push its item list aside on screens the planner has nothing to
     * do with.
     */
    @Override
    public Collection<Rectangle> getGuiExtraAreas() {
        return PlannerHooks.occupiedAreas();
    }
}
