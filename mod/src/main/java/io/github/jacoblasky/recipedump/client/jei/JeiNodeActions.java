package io.github.jacoblasky.recipedump.client.jei;

import io.github.jacoblasky.recipedump.RecipeDumpMod;
import io.github.jacoblasky.recipedump.client.planner.NodeActions;
import io.github.jacoblasky.recipedump.client.planner.NodeActionsHolder;
import io.github.jacoblasky.recipedump.plan.PlanNode;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;
import net.minecraft.item.ItemStack;
import org.apache.logging.log4j.LogManager;

/**
 * The Phase 4 half of the planner's node menu: the entries that need JEI and an ItemStack.
 *
 * IT LIVES IN THIS PACKAGE AND NOT IN `client.planner` BECAUSE OF WHAT IT IMPORTS. Everything
 * JEI-shaped is confined to `client.jei` so that a client without JEI never loads any of it,
 * and `NodeActions` exists precisely so the planner can name this behaviour without naming
 * `mezz.jei`. Putting the implementation on the other side of the seam would defeat the seam.
 *
 * <h2>An ore or fluid node answers false, and that is the right answer rather than a gap</h2>
 *
 * `canShowInRecipeViewer` is asked per node, and for an `ore:` node it says no even though the
 * plan does know which concrete item was chosen. That is deliberate. `Solver.resolveOre`
 * attaches the resolved member AS THE NODE'S ONLY CHILD -- `solve.py`'s `resolve_ore` returns
 * `{"status": "oredict", "resolved_to": best, "children": [child]}` -- so the concrete item is
 * already its own row directly beneath, and that row answers true on its own. Following
 * `resolvedTo()` here would give two adjacent rows that open the same JEI screen, and it would
 * have the group row claim to be an item it is not. A `fluid:` node has no such child and
 * genuinely has nothing to focus on; 1,198 of this pack's fluids exist only as `fluid:<name>`.
 *
 * <h2>Where the graph comes from</h2>
 *
 * Through {@link GraphSource}, injected. `DumpPlugin.onRuntimeAvailable` installs the live one
 * over {@code GraphService.get().graph()} (#161), so the graph arrives through this seam and
 * nowhere else. DO NOT ADD A SECOND HOLDER -- no static field here, no graph cached in the
 * constructor. The source is asked PER INVOCATION on purpose: `onRuntimeAvailable` fires long
 * before the 5.47 s load finishes, so anything resolved once would be null for the rest of the
 * session. A volatile field read is what makes asking per frame free.
 *
 * {@link #NO_GRAPH} remains the answer for a client whose graph has not loaded, and every
 * method then answers the way it would for a node with no item behind it: the menu simply does
 * not draw the two entries. The install log below is what keeps that state from looking like
 * "never installed" to whoever is debugging the menu.
 *
 * NOTHING HERE MAY THROW. Every method is called from a GUI build, a render pass or a click
 * handler, and an exception in any of those takes the screen down. The two boundaries that
 * could raise -- JEI's own layout and its ingredient list -- are already caught inside
 * {@link JeiBridge}; the lookups this class adds cannot, because `RecipeGraph.keyId` is a hash
 * probe over a table `GraphBuilder` always builds with its lookup index.
 */
public final class JeiNodeActions implements NodeActions {

    /**
     * Where the client's currently loaded graph comes from, or null when none is loaded.
     *
     * AN INTERFACE RATHER THAN A DIRECT CALL for the same reason {@link
     * io.github.jacoblasky.recipedump.client.StackIndex} takes its stacks as an argument: it
     * is what lets the whole of this class be asserted with no client, no JEI and no graph
     * loader in the JVM. It is also the entire coupling to the in-game work -- the real holder
     * is one anonymous implementation at the {@link #install} call site in `DumpPlugin` and
     * nothing else.
     */
    public interface GraphSource {

        /** The graph the planner is planning against. Null before one is loaded. */
        RecipeGraph graph();
    }

    /**
     * A source that never has a graph.
     *
     * NOT A TEST DOUBLE -- it is the honest answer on a client that has a graph and has not
     * loaded it, and it is the target a null {@link GraphSource} collapses to in the
     * constructor. Production wiring is `DumpPlugin`'s live source, not this.
     */
    public static final GraphSource NO_GRAPH = new GraphSource() {
        @Override
        public RecipeGraph graph() {
            return null;
        }
    };

    private final GraphSource graphs;

    public JeiNodeActions(GraphSource graphs) {
        // Null collapses to NO_GRAPH rather than being kept, matching NodeActionsHolder's own
        // rule: a null in a field every render pass reads is a crash per frame.
        this.graphs = graphs == null ? NO_GRAPH : graphs;
    }

    /**
     * Installs this as the planner's {@link NodeActions} and says, once, which state it is in.
     *
     * THE LOG LINE IS THE POINT OF THIS METHOD. "Installed but answering false because no
     * graph is loaded" and "never installed" produce an identical menu, and telling them apart
     * from a screenshot is impossible -- so the difference is written down at the only moment
     * anything knows it.
     */
    public static void install(GraphSource graphs) {
        NodeActionsHolder.install(new JeiNodeActions(graphs));
        LogManager.getLogger(RecipeDumpMod.MODID).info(installMessage(graphs));
    }

    /**
     * What {@link #install} says it did.
     *
     * SEPARATE SO THE TWO STATES CAN BE ASSERTED TO DIFFER, which is the only property of
     * this string that matters. An edit that collapsed both branches onto one message would
     * undo the whole reason the line exists while every other test stayed green -- and a log
     * line is not something a screenshot or a layout assertion can notice.
     */
    static String installMessage(GraphSource graphs) {
        if (graphs == null || graphs == NO_GRAPH) {
            return "JeiNodeActions installed with no graph source: the planner's recipe-viewer"
                    + " entries stay hidden for as long as this install stands, and no graph"
                    + " load will change that. DumpPlugin.onRuntimeAvailable installs the live"
                    + " source, so this line means some other caller reached install().";
        }
        return "JeiNodeActions installed, graph source " + graphs.getClass().getName() + ".";
    }

    /**
     * {@inheritDoc}
     *
     * <p>Both halves are load-bearing. The stack decides whether there is anything to focus
     * on; {@link JeiBridge#isAvailable} decides whether focusing it would do anything.
     *
     * THE RUNTIME CHECK IS NOT REDUNDANT, though it reads that way: the index is built from
     * JEI's item list, so no JEI usually means no stacks anyway. But `DumpPlugin` captures the
     * ingredient registry in `register` and the runtime in `onRuntimeAvailable`, which are two
     * separate callbacks -- a JEI that fails between them leaves a populated index and a null
     * runtime, and that is exactly the state where the entries would draw and do nothing.
     * {@link NodeActions} says an entry that advertises a feature and then reads as broken is
     * worse than no entry at all.
     */
    @Override
    public boolean canShowInRecipeViewer(PlanNode node) {
        return JeiBridge.isAvailable() && stackFor(node) != null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Deliberately NOT gated on {@link JeiBridge#isAvailable}, unlike the method above. The
     * icon is drawn by the planner's own widget and is useful whether or not a recipe screen
     * can be opened; it is empty without JEI only because the index has nothing in it, which
     * is a fact about the index rather than a rule about the icon.
     */
    @Override
    public ItemStack iconFor(PlanNode node) {
        ItemStack stack = stackFor(node);
        return stack == null ? ItemStack.EMPTY : stack;
    }

    @Override
    public void showRecipes(PlanNode node) {
        // The boolean is dropped on purpose, per JeiBridge: by the time this runs the player
        // has already clicked, and a plan reloaded between the menu opening and the click is
        // the only way to get here with nothing to show. An error dialog helps nobody.
        JeiBridge.showRecipesFor(stackFor(node));
    }

    @Override
    public void showUses(PlanNode node) {
        JeiBridge.showUsesOf(stackFor(node));
    }

    /**
     * The stack a plan node names, or null.
     *
     * NO `baseKey` FALLBACK HERE, and that is the difference from {@link JeiBridge#keyIdFor}.
     * That method keys something the player pointed AT, which may be an NBT variant the dump
     * never recorded, so weakening the key is what stops the keybind doing nothing. A plan node
     * came out of this graph, so its key is either in the table or the graph has been swapped
     * underneath the plan -- and answering with a different item's stack in that case would be
     * a wrong answer wearing a right one's clothes. {@link
     * io.github.jacoblasky.recipedump.client.StackIndex} still applies its own three steps in
     * the other direction, which is where a missing NBT variant is handled.
     */
    private ItemStack stackFor(PlanNode node) {
        RecipeGraph graph = graphs.graph();
        // `key` is "" and never null on both of PlanNode's construction paths, and the null
        // check is here anyway because the failure mode is asymmetric: `StringTable.idOf`
        // hashes the string before it can answer -1, so a null arrives as an NPE inside a
        // render pass rather than as a missing icon.
        if (node == null || graph == null || node.key() == null) {
            return null;
        }
        int keyId = graph.keyId(node.key());
        return keyId < 0 ? null : JeiBridge.stackFor(keyId, graph);
    }
}
