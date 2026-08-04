package io.github.jacoblasky.recipedump.client.planner;

import io.github.jacoblasky.recipedump.plan.PlanNode;

import net.minecraft.item.ItemStack;

/**
 * THE JEI SEAM. What the node menu can do that needs JEI or an ItemStack.
 *
 * A SEAM AND NOT A STUB, which is the distinction #19 asks for: the menu asks
 * {@link #canShowRecipes} and {@link #canShowUses} and simply does not draw an entry when the
 * answer is no.
 * A greyed-out "Show in JEI" that never works is worse than no entry -- it advertises a
 * feature and then reads as broken.
 *
 * IMPLEMENTED BY `client.jei.JeiNodeActions` OVER {@link
 * io.github.jacoblasky.recipedump.client.StackIndex} (#157). THAT IS THE ONE INVERSION, and
 * one is the whole point: turning a graph key back into an `ItemStack` means re-deriving the
 * NBT discriminator, which `CollectingIngredients` already does and `DiscriminatorTest`
 * already pins. DO NOT RE-DERIVE THE DISCRIMINATOR HERE, or in any other implementor of this
 * interface -- a second copy is a second thing to keep in step with the dump format, and the
 * two would drift silently because a mis-keyed lookup returns a wrong item rather than an
 * error.
 *
 * Two facts about the keys that arrive here, both measured, both of which shape any
 * implementation:
 *
 *   - An `ore:` or `fluid:` key has NO ItemStack. 1,198 of this pack's fluids exist only as
 *     `fluid:<name>`, and an `ore:` node is an oredict group rather than an item. That is why
 *     the question is asked PER NODE.
 *   - A `#digest` key is not the base item. `Item.getByNameOrId` on the whole string returns
 *     null; the digest has to be stripped and the NBT rebuilt.
 */
public interface NodeActions {

    /** Nothing installed: the menu shows its own entries and none of JEI's. */
    NodeActions NONE = new NodeActions() {
        @Override
        public boolean canShowRecipes(PlanNode node) {
            return false;
        }

        @Override
        public boolean canShowUses(PlanNode node) {
            return false;
        }

        @Override
        public ItemStack iconFor(PlanNode node) {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack iconForKey(String key) {
            return ItemStack.EMPTY;
        }

        @Override
        public void showRecipes(PlanNode node) {
        }

        @Override
        public void showUses(PlanNode node) {
        }
    };

    /**
     * True when "Show recipes" should appear for THIS node.
     *
     * Per node rather than per session, because the answer genuinely differs per node: JEI can be
     * running and the node still be a fluid or an oredict group with nothing to focus on.
     *
     * SPLIT FROM {@link #canShowUses} FOR THE TOKEN CASE (#174). A pack placeholder is a registered
     * item, so it has a stack and JEI will happily open on it, and what opens is a recipe screen for
     * what MAKES a Dungeon Drop, which is nothing. The issue was reported on a reader concluding a
     * token was an item; an entry that opens an empty screen is that same conclusion with a click
     * behind it. This interface's own header already says a greyed-out entry that never works is
     * worse than no entry, and an entry that opens nothing is the same argument one step later.
     */
    boolean canShowRecipes(PlanNode node);

    /**
     * True when "Show uses" should appear for THIS node.
     *
     * THE OLD `canShowInRecipeViewer` UNCHANGED, under a narrower name: a stack to focus on and a
     * JEI runtime to focus it in. A token answers TRUE here and that is the point rather than an
     * oversight. "What consumes a Dungeon Drop" is a real question with real answers, and it is the
     * honest form of what a reader wanted when they clicked a placeholder expecting to learn
     * something about it.
     */
    boolean canShowUses(PlanNode node);

    /**
     * The stack to draw in the row's icon column, or {@link ItemStack#EMPTY} for none.
     *
     * The icon column is charged in the layout whether or not anything fills it, so that
     * installing this, or a graph finishing its load mid-session, does not re-flow every row.
     * {@link PlannerWidgets#ICON} is its width.
     */
    ItemStack iconFor(PlanNode node);

    /**
     * The stack a bare graph KEY names, or {@link ItemStack#EMPTY} for none.
     *
     * FOR THE SURFACES THAT HAVE NO PLAN NODE. The TODO list is `PlanBook`'s, and that book
     * holds keys rather than nodes on purpose -- see its class note: 1,198 of this pack's
     * fluids have no item at all, so storing stacks would make half the plannable things
     * unstorable. {@link #iconFor} cannot serve those rows because there is nothing to hand it.
     *
     * SAME ANSWER AS {@link #iconFor} FOR THE SAME KEY, and that has to stay true: the shopping
     * list and the tree draw the same item beside each other in one window, and two lookups
     * that disagreed would show it with an icon in one place and without in the other.
     * `JeiNodeActions` therefore resolves both through one private method rather than two.
     */
    ItemStack iconForKey(String key);

    /** Open the recipe viewer focused on what MAKES this node's key. */
    void showRecipes(PlanNode node);

    /** Open the recipe viewer focused on what USES this node's key. */
    void showUses(PlanNode node);
}
