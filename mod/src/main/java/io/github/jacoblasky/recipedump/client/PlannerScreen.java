package io.github.jacoblasky.recipedump.client;

import java.util.List;
import java.util.function.Function;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.factory.ClientGUI;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;

import io.github.jacoblasky.recipedump.RecipeDumpMod;
import io.github.jacoblasky.recipedump.common.PlanBook;

/**
 * PLACEHOLDER. It shows the plan book the server synced and nothing else.
 *
 * #19 Phase 3 owns the real planner -- the tree panel, the node menu, the recipe picker -- and
 * will replace {@link #buildPanel}. What is worth keeping from this is the two things it
 * proves end to end today, both of which Phase 3 would otherwise have to establish while also
 * building a UI: the calculator item reaches a ModularUI window at all, and the capability
 * that arrived over the network is readable from the client.
 *
 * A `ListWidget`, NOT a `ScrollWidget` over a `Column`. #125 measured the difference: a plain
 * `ScrollWidget` never calls `setScrollSize`, so it lays out perfectly and its scrollbar can
 * never activate. `ListWidget` is the one that publishes its content height.
 */
public final class PlannerScreen {

    /** Panel name. ModularUI keys open panels by it, so it must be unique within this mod. */
    private static final String PANEL = "mcrecipedump_planner";

    static final int PANEL_WIDTH = 220;
    static final int PANEL_HEIGHT = 140;
    private static final int PADDING = 8;
    /** Panel width less the padding on both sides. Every line is this wide; see {@link #text}. */
    static final int CONTENT_WIDTH = PANEL_WIDTH - PADDING * 2;
    private static final int LINE_HEIGHT = 10;
    private static final int ROW_HEIGHT = 12;

    private PlannerScreen() {
    }

    /**
     * DO NOT TURN THIS BACK INTO A `CustomModularScreen` SUBCLASS.
     *
     * `CustomModularScreen`'s constructor hands `this::buildUI` to `ModularScreen`, which
     * CALLS IT during `super(...)` -- before any field of the subclass has been assigned. A
     * screen that held the book in a field therefore read null while building its own panel,
     * and the only symptom a client gives is `opening 'planner' threw NullPointerException`
     * from `ShotScreens`, with the stack swallowed. Measured, on the first dev-client boot.
     *
     * A captured local cannot go wrong that way: `book` is fully initialised before the
     * constructor is entered. It also leaves {@link #buildPanel} a plain static function of
     * its input, which is what makes the layout testable with no screen at all.
     */
    public static void open(final PlanBook book) {
        ClientGUI.open(new ModularScreen(RecipeDumpMod.MODID,
                new Function<ModularGuiContext, ModularPanel>() {
                    @Override
                    public ModularPanel apply(ModularGuiContext context) {
                        return buildPanel(book);
                    }
                }));
    }

    /** The whole window, as a function of the book. No client state, no screen, no context. */
    public static ModularPanel buildPanel(PlanBook book) {
        return ModularPanel.defaultPanel(PANEL, PANEL_WIDTH, PANEL_HEIGHT)
                .child(Flow.column()
                        .pos(PADDING, PADDING)
                        .size(CONTENT_WIDTH, PANEL_HEIGHT - PADDING * 2)
                        .child(text("Planner (placeholder -- #19 Phase 3)"))
                        .child(text(summary(book)))
                        .child(rows(book)));
    }

    /** The one line that says whether the capability actually made it across. */
    private static String summary(PlanBook book) {
        if (book.isEmpty()) {
            return "plan book empty";
        }
        return book.favourites().size() + " favourite(s), " + book.todoKeys().size() + " to do";
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ListWidget<?, ?> rows(PlanBook book) {
        ListWidget list = new ListWidget();
        list.size(CONTENT_WIDTH, PANEL_HEIGHT - PADDING * 2 - LINE_HEIGHT * 2 - 8);
        List<String> favourites = book.favourites();
        for (String key : favourites) {
            list.child(row("* " + key));
        }
        for (String key : book.todoKeys()) {
            list.child(row(book.todoQuantity(key) + "x " + key));
        }
        if (favourites.isEmpty() && book.todoKeys().isEmpty()) {
            // A ListWidget with no children still has to lay out; giving it one row keeps the
            // empty case on the same code path as every other, rather than a special case
            // nobody exercises until the day a book is empty.
            list.child(row("nothing starred yet"));
        }
        return list;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static TextWidget<?> row(String line) {
        TextWidget widget = new TextWidget(IKey.str(line));
        widget.size(CONTENT_WIDTH, ROW_HEIGHT);
        return widget;
    }

    /**
     * EVERY TEXT WIDGET IS GIVEN AN EXPLICIT SIZE, and that is not styling.
     *
     * A `TextWidget` left to size itself asks `TextRenderer` how wide the string is, which
     * needs the `FontRenderer`, which needs a GL context -- so in a headless JUnit JVM it
     * throws `NoClassDefFoundError: org/lwjgl/LWJGLException` from inside the resize pass.
     * `WidgetTree.resizeInternal` swallows that, so the SYMPTOM is not an error: the whole
     * tree comes back at 0x0, including the widgets that had been sized perfectly well.
     * Measured, in `PlannerScreenLayoutTest`, which is why that test exists.
     *
     * It is also the right thing for the planner regardless of testing. #125 measured that
     * ModularUI neither clamps nor clips a child that is wider than its parent, and a node
     * row holding a registry id is exactly the case that would otherwise overflow.
     *
     * ModularUI's widgets are F-bounded (`TextWidget&lt;W extends TextWidget&lt;W&gt;&gt;`) so that
     * their fluent setters return the concrete subclass. Nothing here subclasses them, so the
     * parameter has no useful value to take and these two helpers pin the raw-type
     * suppression in one place instead of scattering it through {@link #buildPanel}.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static TextWidget<?> text(String line) {
        TextWidget widget = new TextWidget(IKey.str(line));
        widget.size(CONTENT_WIDTH, LINE_HEIGHT);
        widget.marginBottom(2);
        return widget;
    }
}
