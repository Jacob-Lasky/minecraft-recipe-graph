package io.github.jacoblasky.recipedump.client.planner;

/**
 * The one installed {@link NodeActions}. `client.jei.JeiNodeActions.install` replaces the
 * no-op at `onRuntimeAvailable` (#157).
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

    /** Called once from the JEI plugin via `JeiNodeActions.install`. Null restores the no-op. */
    public static void install(NodeActions actions) {
        installed = actions == null ? NodeActions.NONE : actions;
    }
}
