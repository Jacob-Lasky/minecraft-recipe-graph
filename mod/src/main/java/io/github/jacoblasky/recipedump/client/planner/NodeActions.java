package io.github.jacoblasky.recipedump.client.planner;

import io.github.jacoblasky.recipedump.plan.PlanNode;

import net.minecraft.item.ItemStack;

/**
 * THE JEI SEAM. What the node menu can do that needs JEI or an ItemStack.
 *
 * A SEAM AND NOT A STUB, which is the distinction #19 asks for: the menu asks
 * {@link #canShowInRecipeViewer} and simply does not draw the entries when the answer is no.
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
        public boolean canShowInRecipeViewer(PlanNode node) {
            return false;
        }

        @Override
        public ItemStack iconFor(PlanNode node) {
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
     * True when the recipe-viewer entries should appear for THIS node.
     *
     * Per node rather than per session, because the answer genuinely differs per node: JEI
     * can be running and the node still be a fluid or an oredict group with nothing to focus
     * on.
     */
    boolean canShowInRecipeViewer(PlanNode node);

    /**
     * The stack to draw in the row's icon column, or {@link ItemStack#EMPTY} for none.
     *
     * The icon column is charged in the layout whether or not anything fills it, so that
     * installing this, or a graph finishing its load mid-session, does not re-flow every row.
     * {@link PlannerWidgets#ICON} is its width.
     */
    ItemStack iconFor(PlanNode node);

    /** Open the recipe viewer focused on what MAKES this node's key. */
    void showRecipes(PlanNode node);

    /** Open the recipe viewer focused on what USES this node's key. */
    void showUses(PlanNode node);
}
