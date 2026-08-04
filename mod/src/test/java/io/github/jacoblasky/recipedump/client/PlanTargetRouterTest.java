package io.github.jacoblasky.recipedump.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.jacoblasky.recipedump.common.GraphDocuments;
import io.github.jacoblasky.recipedump.common.GraphService;
import io.github.jacoblasky.recipedump.common.GraphSource;
import io.github.jacoblasky.recipedump.common.PlanBook;
import io.github.jacoblasky.recipedump.common.PlannerService;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * What the JEI plan key does, once something is finally listening for it.
 *
 * THE THING UNDER TEST IS A JOIN, not a component. Every piece of this path already had
 * tests -- the keybind reads an ingredient, `PlannerHooks` defines the handover, `PlanBook`
 * holds a TODO, `PlannerEntry` opens a window -- and the feature was still dead on every real
 * client, because nothing in the shipped mod ever called `PlannerHooks.setTargetListener` and
 * every test installed a listener of its own. So these tests deliberately assert the things
 * only the join can get wrong: that the target is the item the player pointed at, that it
 * lands in the book before the window opens, and that all three ways of not knowing what was
 * pointed at decline quietly instead of opening an empty planner.
 */
public class PlanTargetRouterTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private String saved;

    private PlanBook book;

    private String openedTarget;

    private PlanBook openedBook;

    private int opens;

    @BeforeClass
    public static void bootstrap() {
        Bootstrap.register();
    }

    @Before
    public void isolate() {
        saved = System.getProperty(GraphSource.PROPERTY);
        System.clearProperty(GraphSource.PROPERTY);
        GraphService.get().reset();
        PlannerService.get().reset();
        book = new PlanBook();
        openedTarget = null;
        openedBook = null;
        opens = 0;
    }

    @After
    public void restore() {
        if (saved == null) {
            System.clearProperty(GraphSource.PROPERTY);
        } else {
            System.setProperty(GraphSource.PROPERTY, saved);
        }
        GraphService.get().reset();
        PlannerService.get().reset();
    }

    // -- the happy path -------------------------------------------------------------------

    @Test
    public void theItemUnderTheCursorIsTheItemPlanned() throws Exception {
        loadGraphKeyedTo("minecraft:stick");
        assertTrue(router().onPlanTarget(new ItemStack(Items.STICK)));

        assertEquals("the planner must be opened on what was pointed at",
                     "minecraft:stick", openedTarget);
        assertEquals(1, opens);
        assertEquals(book, openedBook);
    }

    /**
     * THE TODO IS WRITTEN BEFORE THE WINDOW OPENS, not after.
     *
     * The panel draws its TODO list and its tree from the same book on the same frame, so a
     * book updated afterwards shows a plan for an item that is not in the list beside it --
     * which reads as a bug in the list rather than as an ordering mistake. Asserted by
     * checking the book from inside the opener, since afterwards both orders look identical.
     */
    @Test
    public void theTargetIsInTheBookByTheTimeTheWindowOpens() throws Exception {
        loadGraphKeyedTo("minecraft:stick");
        final String[] seen = new String[1];
        PlanTargetRouter router = new PlanTargetRouter(books(), new PlanTargetRouter.Opener() {
            @Override
            public void open(PlanBook opening, String target) {
                seen[0] = opening.todoKeys().isEmpty() ? null : opening.todoKeys().get(0);
            }
        });
        assertTrue(router.onPlanTarget(new ItemStack(Items.STICK)));
        assertEquals("minecraft:stick", seen[0]);
        assertEquals(1L, book.todoQuantity("minecraft:stick"));
    }

    /**
     * An NBT variant the dump never recorded still plans, under its base item.
     *
     * The single weakening `JeiBridge.keyFor` makes, and the router has to inherit it rather
     * than re-deriving the key: without it, pressing the key on an enchanted or renamed stack
     * does nothing, and doing nothing is indistinguishable from a broken keybind.
     */
    @Test
    public void anNbtVariantPlansUnderItsBaseItem() throws Exception {
        loadGraphKeyedTo("minecraft:stick");
        ItemStack named = new ItemStack(Items.STICK);
        named.setTagCompound(new NBTTagCompound());
        named.getTagCompound().setString("Species", "NeverDumped");

        assertTrue(router().onPlanTarget(named));
        assertEquals("minecraft:stick", openedTarget);
    }

    // -- the three ways to decline --------------------------------------------------------

    @Test
    public void anItemTheGraphHasNoKeyForOpensNothing() throws Exception {
        loadGraphKeyedTo("minecraft:stick");
        assertFalse(router().onPlanTarget(new ItemStack(Items.DIAMOND)));
        assertEquals(0, opens);
        assertTrue("nothing may be added to the book either", book.todoKeys().isEmpty());
    }

    @Test
    public void aPressBeforeTheGraphHasLoadedOpensNothing() {
        assertNull(GraphService.get().graph());
        assertFalse(router().onPlanTarget(new ItemStack(Items.STICK)));
        assertEquals(0, opens);
    }

    @Test
    public void aPressWithNoPlayerBookOpensNothing() throws Exception {
        loadGraphKeyedTo("minecraft:stick");
        PlanTargetRouter router = new PlanTargetRouter(new PlanTargetRouter.BookSource() {
            @Override
            public PlanBook book() {
                return null;
            }
        }, recordingOpener());
        assertFalse(router.onPlanTarget(new ItemStack(Items.STICK)));
        assertEquals(0, opens);
    }

    // -- plumbing -------------------------------------------------------------------------

    private PlanTargetRouter router() {
        return new PlanTargetRouter(books(), recordingOpener());
    }

    private PlanTargetRouter.BookSource books() {
        return new PlanTargetRouter.BookSource() {
            @Override
            public PlanBook book() {
                return book;
            }
        };
    }

    private PlanTargetRouter.Opener recordingOpener() {
        return new PlanTargetRouter.Opener() {
            @Override
            public void open(PlanBook opening, String target) {
                opens++;
                openedBook = opening;
                openedTarget = target;
            }
        };
    }

    /** The shared tiny document, keyed to something a real `ItemStack` can be built for. */
    private void loadGraphKeyedTo(String key) throws Exception {
        TestGraphs.load(folder.getRoot(), GraphDocuments.craftedFrom(key, "Stick"));
    }
}
