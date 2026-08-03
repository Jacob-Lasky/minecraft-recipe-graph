package io.github.jacoblasky.recipedump.client.planner;

/**
 * The one installed {@link NodeActions}. `client.jei.JeiNodeActions` replaces the no-op (#157);
 * see {@link #install} for the two places that do it.
 *
 * A HOLDER RATHER THAN A CONSTRUCTOR PARAMETER, because the thing that knows how to talk to
 * JEI is the JEI plugin, and it learns it can at `onRuntimeAvailable` -- long after the
 * planner's widgets are written and possibly after a panel has already been opened once.
 * Threading it through every widget factory would put a parameter nobody reads on every
 * signature in this package.
 *
 * CLIENT-SIDE AND SINGLE-THREADED. Every caller is the client thread building a GUI, so this
 * needs no synchronisation and would be misleading with it -- a volatile field would imply a
 * cross-thread contract that does not exist and that nothing enforces.
 */
public final class NodeActionsHolder {

    private static NodeActions installed = NodeActions.NONE;

    private NodeActionsHolder() {
    }

    public static NodeActions actions() {
        return installed;
    }

    /**
     * Called once per client, through `JeiNodeActions.install`. Null restores the no-op.
     *
     * TWO CALLERS, and they are not alternatives: `DumpPlugin.onRuntimeAvailable` in a real
     * client, and `PlannerShot` in the screenshot harness, which has no JEI runtime callback
     * to hang off. See `PlannerActions.NONE` for why the harness installs the real holder and
     * the layout tests must not.
     */
    public static void install(NodeActions actions) {
        installed = actions == null ? NodeActions.NONE : actions;
    }
}
