package io.github.jacoblasky.recipedump.client.jei;

import io.github.jacoblasky.recipedump.RecipeDumpMod;
import io.github.jacoblasky.recipedump.client.planner.NodeActions;
import io.github.jacoblasky.recipedump.client.planner.NodeStatus;
import io.github.jacoblasky.recipedump.client.planner.NodeActionsHolder;
import io.github.jacoblasky.recipedump.plan.Machines;
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
 * `canShowUses` is asked per node, and for an `ore:` node it says no even though the
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
 * Through {@link GraphAccess}, injected. `DumpPlugin.onRuntimeAvailable` installs the live one
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
     *
     * DO NOT RENAME THIS BACK TO `GraphSource`. That was its first name and it collides with
     * {@link io.github.jacoblasky.recipedump.common.GraphSource}, which does an unrelated job
     * -- it decides WHERE the graph file lives on disk. Two unrelated types under one name in
     * one mod is ten minutes lost to importing the wrong one and finding that it compiles.
     * `JeiNodeActionsTest` names both, which is how close together they actually sit.
     */
    public interface GraphAccess {

        /** The graph the planner is planning against. Null before one is loaded. */
        RecipeGraph graph();
    }

    /**
     * A source that never has a graph.
     *
     * NOT A TEST DOUBLE -- it is the honest answer on a client that has a graph and has not
     * loaded it, and it is the target a null {@link GraphAccess} collapses to in the
     * constructor. Production wiring is `DumpPlugin`'s live source, not this.
     */
    public static final GraphAccess NO_GRAPH = new GraphAccess() {
        @Override
        public RecipeGraph graph() {
            return null;
        }
    };

    private final GraphAccess graphs;

    /**
     * The graph {@link #catalysts} was built from, or null. See that field.
     *
     * IDENTITY AND NOT A FLAG, so a graph swapped underneath a running client rebuilds rather
     * than answering from the old one's category ids -- which would not throw, it would draw a
     * different machine.
     */
    private RecipeGraph catalystsFor;

    /**
     * `Machines.catalystsByCategory`'s answer for {@link #catalystsFor}, built on first use.
     *
     * <h2>THIS IS A MEMO AND NOT THE SECOND HOLDER THE CLASS HEADER FORBIDS</h2>
     *
     * That rule is about WHERE THE GRAPH COMES FROM: no static field, nothing cached in the
     * constructor, `graphs.graph()` asked per invocation, because `onRuntimeAvailable` fires
     * long before the 5.47 s load finishes and anything resolved once would be null for the rest
     * of the session. That rule is intact here -- {@link #iconForCategory} still asks
     * `graphs.graph()` first, every time, and this cache is INVALIDATED BY that answer changing.
     *
     * WHAT IT BUYS: `catalystsByCategory` is a whole-graph pass -- two maps over 645 categories
     * plus a sort each -- and `PlannerWidgets.row` asks once per tree row. `plan-fluid-chain` is
     * 703 rows, so an uncached lookup is that pass seven hundred times per panel build.
     *
     * NOT VOLATILE, DELIBERATELY. Every caller is a GUI build on the client thread; a torn read
     * here would cost a rebuild of a map that is a pure function of the graph, which is why the
     * pair is written graph-last so a reader can never see a map without its graph.
     */
    private java.util.Map<Integer, int[]> catalysts;

    public JeiNodeActions(GraphAccess graphs) {
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
    public static void install(GraphAccess graphs) {
        NodeActionsHolder.install(new JeiNodeActions(graphs));
        LogManager.getLogger(RecipeDumpMod.MODID).info(installMessage(graphs));
    }

    /**
     * What {@link #install} says it did.
     *
     * SEPARATE SO THE STRINGS CAN BE ASSERTED. An edit that collapsed both branches onto one
     * message would undo the whole reason the line exists while every other test stayed green
     * -- and a log line is not something a screenshot or a layout assertion can notice.
     *
     * ASSERTED WHOLE, NOT BY SUBSTRING, and that is deliberate rather than fussy: a
     * `contains` check on these was already caught passing in both directions across a
     * reword. Expect `theInstallLogSaysWhichOfTheTwoIndistinguishableStatesItIsIn` to redden
     * if you touch a character here; that test carries the three rules these strings encode.
     */
    static String installMessage(GraphAccess graphs) {
        // NEITHER BRANCH MAY SAY "graph source". `common.GraphSource` exists and does
        // something else entirely, so a log line naming it sends the reader to the wrong
        // class -- the same collision the interface above is named `GraphAccess` to avoid.
        // `theInstallLogSaysWhichOfTheTwoIndistinguishableStatesItIsIn` asserts both strings
        // exactly and asserts the phrase is absent, so this rule cannot rot back.
        if (graphs == null || graphs == NO_GRAPH) {
            return "JeiNodeActions installed with no graph access: the planner's recipe-viewer"
                    + " entries stay hidden for as long as this install stands, and no graph"
                    + " load will change that. DumpPlugin.onRuntimeAvailable installs the live"
                    + " one, so this line means some other caller reached install().";
        }
        return "JeiNodeActions installed, reading the graph from "
                + graphs.getClass().getName() + ".";
    }

    /**
     * {@inheritDoc}
     *
     * <p>A TOKEN ANSWERS NO EVEN THOUGH IT HAS A STACK, which is the whole reason this is not one
     * method with {@link #canShowUses}. `contenttweaker:dungeon_drop` is a REGISTERED item, so
     * `stackFor` succeeds and JEI opens happily, on the recipes that MAKE a Dungeon Drop, of which
     * there are none: the solver classified it as an instruction and kept it out of `shopping_list`
     * for that reason. #174 was reported on a reader concluding a token was an item, and an entry
     * that opens an empty screen is that conclusion with a click behind it.
     *
     * DELEGATES RATHER THAN REPEATING THE TWO CHECKS, so the token gate is the only difference
     * between the two answers and a reader can see that it is.
     */
    @Override
    public boolean canShowRecipes(PlanNode node) {
        return !NodeStatus.isToken(node) && canShowUses(node);
    }

    /**
     * {@inheritDoc}
     *
     * <p>THE OLD `canShowInRecipeViewer`, BODY UNCHANGED. Both halves are load-bearing. The stack
     * decides whether there is anything to focus on; {@link JeiBridge#isAvailable} decides whether
     * focusing it would do anything.
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
    public boolean canShowUses(PlanNode node) {
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

    /**
     * {@inheritDoc}
     *
     * <p>THROUGH THE SAME PRIVATE LOOKUP {@link #iconFor} USES, which is what makes the two
     * agree by construction rather than by a comment asking them to. The tree and the shopping
     * list sit in one window, and a key answered one way for a node and another way for a
     * string would draw the same item with an icon in one panel and without in the other.
     */
    @Override
    public ItemStack iconForKey(String key) {
        ItemStack stack = stackForKey(key);
        return stack == null ? ItemStack.EMPTY : stack;
    }

    /**
     * {@inheritDoc}
     *
     * <p>THE ORDERING IS `Machines`' AND IS NOT RE-DERIVED HERE, which is the one thing about
     * this method that matters. JEI lists whatever opens a category's recipes, and for Modular
     * Machinery that is the generic Machine Blueprint: measured against the live `data/graph.json`,
     * 226 of the pack's 227 `modularmachinery.*` categories list `modularmachinery:itemblueprint`
     * FIRST, so `catalysts().row(cid)[0]` draws the same blueprint on essentially every Modular
     * Machinery step in the pack -- a wrong answer that looks entirely plausible.
     * `Machines.orderBySpecificity` fixes it by ranking on how many categories an item catalyses,
     * fewest first, and {@link Machines#isNotAMachine} drops the blueprint outright where the
     * ordering has nothing to demote it below.
     *
     * <p>NOT THROUGH `MachineStates` / `MachineTable`, WHICH LOOKS LIKE THE CLEAN ROUTE AND
     * SILENTLY LOSES THE FURNACE. `MachineInfo.candidates()` is null or empty on four
     * short-circuit paths -- a manual override, `Machines.ALWAYS_AVAILABLE`, hand crafting and
     * `needsNoMachine` -- and `minecraft.smelting` is in `ALWAYS_AVAILABLE` while carrying 23
     * catalysts in `graph.json`. So that route would draw no icon on the 180 smelting steps in
     * the fixtures and would look like a lookup that merely failed.
     *
     * <p>THE FIRST RESOLVABLE CANDIDATE AND NOT THE FIRST CANDIDATE. A key that survives the
     * ordering can still have no stack in this client's index -- `StackIndex` is built from
     * JEI's item list -- and answering EMPTY on it would hide a machine the NEXT candidate can
     * picture perfectly well.
     */
    @Override
    public ItemStack iconForCategory(PlanNode node) {
        RecipeGraph graph = graphs.graph();
        if (graph == null || node == null || node.category() == null) {
            return ItemStack.EMPTY;
        }
        int categoryId = graph.categoryId(node.category());
        if (categoryId < 0) {
            return ItemStack.EMPTY;
        }
        int[] candidates = orderedCatalysts(graph).get(Integer.valueOf(categoryId));
        if (candidates == null) {
            return ItemStack.EMPTY;
        }
        for (int i = 0; i < candidates.length; i++) {
            if (Machines.isNotAMachine(graph.key(candidates[i]))) {
                continue;
            }
            ItemStack stack = JeiBridge.stackFor(candidates[i], graph);
            if (stack != null && !stack.isEmpty()) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /** {@link #catalysts} for `graph`, building it if this is a graph we have not seen. */
    private java.util.Map<Integer, int[]> orderedCatalysts(RecipeGraph graph) {
        if (catalystsFor != graph) {
            java.util.Map<Integer, int[]> built = Machines.catalystsByCategory(graph);
            // THE MAP FIRST AND THE GRAPH SECOND. A reader that saw the graph updated before its
            // map would answer from the previous graph's category ids, which is a wrong picture
            // rather than a missing one.
            catalysts = built;
            catalystsFor = graph;
        }
        return catalysts;
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
        // `key` is "" and never null on both of PlanNode's construction paths, and the null
        // check is here anyway because the failure mode is asymmetric: `StringTable.idOf`
        // hashes the string before it can answer -1, so a null arrives as an NPE inside a
        // render pass rather than as a missing icon.
        return node == null ? null : stackForKey(node.key());
    }

    /**
     * {@link #stackFor}'s body, reached from a bare key as well as from a node.
     *
     * THE ONE LOOKUP BOTH PUBLIC METHODS RUN, so `iconFor(node)` and `iconForKey(node.key())`
     * cannot come apart. A second copy would drift the way `NodeActions`'s own header says a
     * second discriminator derivation drifts: a mis-keyed lookup returns a wrong item rather
     * than an error, so nothing would report the disagreement.
     */
    private ItemStack stackForKey(String key) {
        RecipeGraph graph = graphs.graph();
        if (graph == null || key == null) {
            return null;
        }
        int keyId = graph.keyId(key);
        return keyId < 0 ? null : JeiBridge.stackFor(keyId, graph);
    }
}
