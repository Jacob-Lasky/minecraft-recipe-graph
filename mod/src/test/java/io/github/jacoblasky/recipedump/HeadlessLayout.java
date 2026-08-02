package io.github.jacoblasky.recipedump;

import java.util.ArrayList;
import java.util.List;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widget.sizer.Area;

/**
 * Runs ModularUI's real layout pass in a plain JUnit JVM, with no window and no GL context.
 *
 * This exists because this machine cannot run the game (only the desktop can, by hand), so
 * without it every "does this row fit" question costs a manual launch of a 410-mod pack.
 * ModularUI's sizer is pure arithmetic -- `widget/sizer/Area`, `widgets/layout/Column` and
 * `widget/scroll/` reference no Minecraft and no GL symbols at all, and `StandardResizer`
 * touches only `net.minecraft.inventory.Slot`, which RetroFuturaGradle already puts on the
 * test classpath. So the layout pass runs here unchanged; what follows is the bootstrapping
 * needed to reach it, and nothing else.
 *
 * NOTHING HERE COMPUTES A BOX. Every number a test reads back was produced by ModularUI's
 * own `WidgetTree`/`StandardResizer`; this class only supplies the screen the sizer measures
 * against, which is what a `GuiScreen` would supply in game. A helper that did its own
 * arithmetic would make the tests agree with themselves rather than with the mod.
 *
 * TWO THINGS OUTSIDE THIS FILE ARE PART OF THE HARNESS, and removing either breaks it in a
 * way that does not look like a build problem:
 *
 *   - `mod/build.gradle` puts the ModularUI jar on the test classpath through `rfg.deobf`,
 *     because a pack jar is SRG-named and the test classpath's Minecraft is MCP-named. The
 *     comment there explains what fails and how quietly.
 *   - `mod/src/test/java/com/cleanroommc/neverenoughanimations/api/IAnimatedScreen.java` is
 *     an empty placeholder for a client-only mod's interface, without which `ModularScreen`
 *     cannot even be linked.
 */
public final class HeadlessLayout {

    /**
     * 854x480, Minecraft's smallest supported window, at GUI scale 2. The tightest real
     * screen a panel has to fit inside, so a layout that survives this survives the rest.
     */
    public static final int SCREEN_WIDTH = 427;
    public static final int SCREEN_HEIGHT = 240;

    private HeadlessLayout() {
    }

    /**
     * A `ModularScreen` that reports itself as an overlay.
     *
     * DO NOT "clean this up" by constructing a real screen instead. In game a `ModularScreen`
     * is paired with an `IMuiScreen` wrapper around a vanilla `GuiScreen`, and there is no way
     * to supply one here: `IMuiScreen` extends `IAnimatedScreen` from NeverEnoughAnimations,
     * which is a SEPARATE client-only mod, so anything that implements `IMuiScreen` -- ours or
     * ModularUI's own `overlay.ScreenWrapper` -- needs a jar this machine does not have.
     * Reporting as an overlay is the one switch that keeps every wrapper call out of the path:
     *
     *   - `onResize` skips notifying the wrapper of the new panel area;
     *   - `isClientOnly()` short-circuits to true instead of asking the wrapper, which
     *     `Widget.onInitInternal` calls on every widget as the tree initialises;
     *   - `ModularPanel.isExcludeAreaInRecipeViewer()` returns false instead of reaching for
     *     recipe-viewer settings that only a constructed screen has.
     *
     * It is safe because `isOverlay()` is read in exactly those three places plus
     * `getContainer()` and `ItemSlot.onInit`, and NONE of them feed the sizer -- verified by
     * disassembling every caller in the jar. An overlay lays out identically to a screen.
     */
    private static final class OverlayLikeScreen extends ModularScreen {

        OverlayLikeScreen(ModularPanel panel) {
            // The two-argument constructor on purpose: the single-argument one hardcodes the
            // owner to "modularui" and warns about it, and it reads `ModularUI.isDev` to
            // decide whether to warn, which drags in the mod class.
            super("mcrecipedump", panel);
        }

        @Override
        public boolean isOverlay() {
            return true;
        }
    }

    /**
     * Wraps the widgets in a panel of the given size, lays it out on the reference screen,
     * prints the resolved tree and returns the panel.
     *
     * This is the entry point a layout test should use. It takes the roots rather than a
     * ready-made panel so that no test has to repeat the open-a-screen-and-resize dance, and
     * so the dump below is never accidentally left out of a failing run.
     */
    public static ModularPanel layOutPanel(String name, int width, int height,
                                           IWidget... roots) {
        return layOutPanelOnScreen(name, width, height, SCREEN_WIDTH, SCREEN_HEIGHT, roots);
    }

    /**
     * The same, on a screen of a stated size, for asserting what a panel does when the window
     * is not the reference one.
     *
     * `onResize` is the whole entry point into ModularUI: it updates the context's screen
     * area, opens the main panel (which is what links every widget's resizer to its parent's),
     * checks the resize tree for cycles, and only then runs `WidgetTree.resizeInternal`.
     * Calling the sizer directly instead would skip the panel opening and resize an unlinked
     * tree, which fails silently rather than loudly -- see `dump` below.
     */
    public static ModularPanel layOutPanelOnScreen(String name, int width, int height,
                                                   int screenWidth, int screenHeight,
                                                   IWidget... roots) {
        ModularPanel panel = new ModularPanel(name);
        panel.size(width, height);
        for (IWidget root : roots) {
            panel.child(root);
        }
        new OverlayLikeScreen(panel).onResize(screenWidth, screenHeight);
        System.out.println(name + ":\n" + dump(panel));
        return panel;
    }

    /**
     * A leaf widget of a fixed size, standing in for a node row.
     *
     * `Widget` is concrete but its type parameter is self-referential (`W extends Widget<W>`),
     * so it cannot be instantiated directly. This subclass adds no behaviour and overrides
     * nothing -- the resizer it inherits from `Widget` is the same `StandardResizer` every
     * real widget gets.
     */
    public static final class Leaf extends Widget<Leaf> {
    }

    /** A named leaf sized in pixels. The name is what makes a failed assertion readable. */
    public static Leaf leaf(String name, int width, int height) {
        Leaf leaf = new Leaf();
        leaf.name(name);
        leaf.size(width, height);
        return leaf;
    }

    /** Every widget in the tree, parents before children, for whole-tree assertions. */
    public static List<IWidget> flatten(IWidget root) {
        List<IWidget> all = new ArrayList<>();
        collect(root, all);
        return all;
    }

    private static void collect(IWidget widget, List<IWidget> into) {
        into.add(widget);
        for (IWidget child : widget.getChildren()) {
            collect(child, into);
        }
    }

    /**
     * The resolved tree, one widget per line, as `name [absX,absY wxh] rel(rx,ry)`.
     *
     * Printed on every run rather than only on failure, because
     * `WidgetTree.resizeInternal` CATCHES EVERY THROWABLE and only logs it. A sizer that blew
     * up leaves the boxes at their construction values and returns normally, so the dump in
     * the test report is the difference between "the layout is wrong" and "the layout never
     * ran".
     */
    public static String dump(IWidget root) {
        StringBuilder sb = new StringBuilder();
        dump(root, 0, sb);
        return sb.toString();
    }

    private static void dump(IWidget widget, int depth, StringBuilder sb) {
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        Area area = widget.getArea();
        sb.append(widget.getClass().getSimpleName());
        if (widget.getName() != null) {
            sb.append(' ').append(widget.getName());
        }
        sb.append(" [").append(area.x()).append(',').append(area.y())
          .append(' ').append(area.w()).append('x').append(area.h())
          .append("] rel(").append(area.rx).append(',').append(area.ry).append(")\n");
        for (IWidget child : widget.getChildren()) {
            dump(child, depth + 1, sb);
        }
    }
}
