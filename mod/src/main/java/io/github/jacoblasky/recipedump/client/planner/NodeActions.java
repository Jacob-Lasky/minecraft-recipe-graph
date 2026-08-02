package io.github.jacoblasky.recipedump.client.planner;

import net.minecraft.item.ItemStack;

/**
 * THE PHASE 4 SEAM. What the node menu can do that needs JEI or an ItemStack.
 *
 * A SEAM AND NOT A STUB, which is the distinction #19 asks for: the menu asks
 * {@link #canShowInRecipeViewer} and simply does not draw the entries when the answer is no.
 * A greyed-out "Show in JEI" that never works is worse than no entry -- it advertises a
 * feature and then reads as broken.
 *
 * Phase 3 does not implement this, and the reason is not laziness: turning a graph key back
 * into an `ItemStack` means re-deriving the NBT discriminator, and #19 puts that in Phase 4
 * precisely so there is ONE implementation of it, reusing what `CollectingIngredients`
 * already does and what `DiscriminatorTest` already pins. A second one here would be a second
 * thing to keep in step with the dump format.
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
     * The icon column is reserved in the layout whether or not anything fills it, so that
     * installing this later does not re-flow every row. {@link PlannerWidgets#ICON} is its
     * width.
     */
    ItemStack iconFor(PlanNode node);

    /** Open the recipe viewer focused on what MAKES this node's key. */
    void showRecipes(PlanNode node);

    /** Open the recipe viewer focused on what USES this node's key. */
    void showUses(PlanNode node);
}
