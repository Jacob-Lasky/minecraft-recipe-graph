package io.github.jacoblasky.recipedump.shot;

import io.github.jacoblasky.recipedump.client.PlannerScreen;
import io.github.jacoblasky.recipedump.common.PlanBook;

/**
 * Opens the planner window against a made-up plan book, for `-Dmcrecipedump.shot=planner`.
 *
 * A FIXTURE BOOK RATHER THAN THE PLAYER'S, because the harness never joins a world: it shoots
 * from the main menu, so there is no player, no capability and nothing synced. Filling the
 * book here is also what makes the picture worth attaching -- an empty planner proves the
 * window opens and nothing else, while a populated one shows the rows laid out, which is the
 * part that can be wrong.
 *
 * The keys are real ones from the reference pack rather than "foo" and "bar", so the shot
 * shows a realistic worst case: `fluid:nethengeic_fluid` is Strong Mythic Essence and the
 * discriminated key is the shape that overflows a row.
 */
final class PlannerShot {

    private PlannerShot() {
    }

    static void open() {
        PlanBook book = new PlanBook();
        book.addFavourite("minecraft:iron_ingot");
        book.addFavourite("fluid:nethengeic_fluid");
        book.addFavourite("thaumadditions:vis_pod#0116bb2287a7");
        book.setTodo("nuclearcraft:borax", 64L);
        book.setTodo("fluid:water", 934400L);
        PlannerScreen.open(book);
    }
}
