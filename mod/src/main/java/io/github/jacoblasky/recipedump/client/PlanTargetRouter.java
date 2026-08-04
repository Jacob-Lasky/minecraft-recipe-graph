package io.github.jacoblasky.recipedump.client;

import io.github.jacoblasky.recipedump.client.jei.JeiBridge;
import io.github.jacoblasky.recipedump.client.jei.PlannerHooks;
import io.github.jacoblasky.recipedump.common.GraphService;
import io.github.jacoblasky.recipedump.common.PlanBook;
import io.github.jacoblasky.recipedump.graph.RecipeGraph;
import net.minecraft.item.ItemStack;

/**
 * What happens when the player points at an item in JEI and presses the plan key.
 *
 * THE SEAM THIS FILLS WAS EMPTY, AND IT WAS THE ONE THAT MATTERED. `PlanTargetKeybind` read
 * the ingredient, `PlannerHooks.TargetListener` defined the handover, `JeiBridgeTest` and
 * `JeiNodeActionsTest` both installed a listener of their own and asserted against it -- and
 * nothing in the shipped mod ever called `PlannerHooks.setTargetListener`, so on a real client
 * the key resolved a stack, handed it to `NOBODY_LISTENING`, and the player saw nothing at
 * all. `JeiNodeActionsTest` names this exact defect in a comment: a seam whose producer and
 * consumer are both tested and which nothing joins in production.
 *
 * SO THE TESTS HERE ASSERT THE JOIN, not the halves. Both collaborators are constructor
 * arguments with production defaults, because the two things this class does that are worth
 * getting right -- resolving the stack to a key the graph actually holds, and refusing rather
 * than opening an empty window when it cannot -- are both unreachable from a test that has to
 * boot a client to find a `PlanBook`.
 */
public final class PlanTargetRouter implements PlannerHooks.TargetListener {

    /** Where the player's book comes from. Null when there is no player yet. */
    public interface BookSource {
        PlanBook book();
    }

    /** What to do with a resolved target. Production is `PlannerEntry.openFor`. */
    public interface Opener {
        void open(PlanBook book, String target);
    }

    private final BookSource books;

    private final Opener opener;

    /**
     * BOTH COLLABORATORS ARE REQUIRED, and there is deliberately no convenience constructor
     * defaulting the opener to {@link PlannerEntry#openFor}. That call throws on a client
     * without ModularUI, and this runs inside keyboard dispatch where throwing takes the
     * frame; the guard that catches it lives in `ClientProxy` beside the message it shows.
     * A default here would be the unguarded one, and it would be the one a new call site
     * reached for.
     */
    public PlanTargetRouter(BookSource books, Opener opener) {
        this.books = books;
        this.opener = opener;
    }

    /**
     * @return true only when the planner was opened on this stack.
     *
     * FALSE IS AN ORDINARY ANSWER AND MUST STAY SILENT. The key can be pressed over an item
     * this pack has no recipe data for, before the graph has finished loading, or on a title
     * screen with no player -- and the contract `PlanTargetKeybind` documents is that all four
     * of its ways to do nothing are indistinguishable to the player, because a message for any
     * of them fires on an idle keypress.
     *
     * THE TODO ENTRY IS WRITTEN BEFORE THE WINDOW OPENS, so the panel's TODO list and the tree
     * it is drawing agree on the first frame. Doing it the other way round shows a plan for an
     * item that is not in the list beside it, which reads as a bug in the list.
     */
    @Override
    public boolean onPlanTarget(ItemStack stack) {
        RecipeGraph graph = GraphService.get().graph();
        String key = JeiBridge.keyFor(stack, graph);
        if (key == null) {
            return false;
        }
        PlanBook book = books.book();
        if (book == null) {
            return false;
        }
        book.setTodo(key, 1L);
        opener.open(book, key);
        return true;
    }
}
