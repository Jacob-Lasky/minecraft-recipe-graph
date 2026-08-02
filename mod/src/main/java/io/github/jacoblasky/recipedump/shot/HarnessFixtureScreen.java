package io.github.jacoblasky.recipedump.shot;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.factory.ClientGUI;
import com.cleanroommc.modularui.screen.CustomModularScreen;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;

import io.github.jacoblasky.recipedump.RecipeDumpMod;

/**
 * THE HARNESS'S OWN TEST PATTERN. It is not the planner UI and must never grow into one --
 * #19 Phase 3 owns that, in its own screen, registered in {@link ShotScreens} beside this one.
 *
 * What it is for: proving that a container with no GPU rendered a REAL ModularUI panel, which
 * is the gate on #124. So every widget here is chosen to exercise a rendering path a flat
 * coloured rectangle would not, and dropping one weakens the proof:
 *
 *   the panel itself   a nine-slice UITexture, so the GUI atlas is bound and sampled
 *   TextWidget         the bitmap font renderer, which is its own texture and draw path
 *   ButtonWidget       a themed widget, so ModularUI's theme lookup ran and resolved
 *   Flow.column()      the layout pass, so the widgets are POSITIONED rather than piled up
 *
 * A screenshot showing the panel border, legible text and a button therefore says llvmpipe
 * carried textures, the font and the layout at once.
 */
public class HarnessFixtureScreen extends CustomModularScreen {

    /** Panel name. ModularUI keys open panels by it, so it has to be unique within this mod. */
    private static final String PANEL = "mcrecipedump_harness_fixture";

    /**
     * Opened through `ClientGUI` rather than `Minecraft.displayGuiScreen`, because a
     * ModularScreen is NOT a GuiScreen -- it needs the `GuiScreenWrapper` that `ClientGUI`
     * builds around it, and handed straight to displayGuiScreen it would not compile, let
     * alone draw.
     */
    static void open() {
        ClientGUI.open(new HarnessFixtureScreen());
    }

    public HarnessFixtureScreen() {
        // The owner id, NOT the no-arg constructor: that one owns the panel to "modularui"
        // and logs an error telling you to pass your own mod id.
        super(RecipeDumpMod.MODID);
    }

    @Override
    public ModularPanel buildUI(ModularGuiContext context) {
        return ModularPanel.defaultPanel(PANEL, 220, 116)
                .child(Flow.column()
                        .pos(8, 8)
                        .size(204, 100)
                        .child(text("mc-recipe-dump harness fixture"))
                        .child(text("mod " + RecipeDumpMod.version()))
                        // Says what the picture IS to anyone who meets it attached to a PR
                        // with no surrounding context, which is how a screenshot gets read.
                        .child(text("rendered headlessly on llvmpipe (#124)"))
                        .child(button("Button")));
    }

    /**
     * ModularUI's widgets are F-bounded (`TextWidget&lt;W extends TextWidget&lt;W&gt;&gt;`) so that
     * their fluent setters return the concrete subclass. Nothing here subclasses them, so the
     * parameter has no useful value to take and these two helpers pin the raw-type
     * suppression in one place instead of scattering it through `buildUI`.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static TextWidget<?> text(String line) {
        TextWidget widget = new TextWidget(IKey.str(line));
        widget.marginBottom(2);
        return widget;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ButtonWidget<?> button(String label) {
        ButtonWidget widget = new ButtonWidget();
        widget.size(72, 18);
        widget.overlay(IKey.str(label));
        return widget;
    }
}
