package io.github.jacoblasky.recipedump.client.planner;

/**
 * What the planner should say when there is no plan to draw yet.
 *
 * SEPARATE FROM {@link PlanView} RATHER THAN FAKED AS AN EMPTY ONE. Loading the graph is
 * several seconds off the main thread, so the panel WILL be opened before a plan exists --
 * and "still loading" and "no graph found, here is why" are different sentences that a reader
 * has to be able to tell apart. `PlanView.empty()` says "nothing planned yet", which is a
 * third, and would be a lie for the other two.
 *
 * A MESSAGE ON THE STATE, not a message table in the widget, so the caller that knows WHY the
 * load failed can say so. The widget renders whatever it is handed.
 */
public final class PlannerState {

    /** Nothing has been asked for. The calculator item opened on an idle planner. */
    public static final PlannerState IDLE = new PlannerState(Kind.IDLE, "nothing planned yet");

    private final Kind kind;
    private final String message;

    private PlannerState(Kind kind, String message) {
        this.kind = kind;
        this.message = message;
    }

    /** The graph is being read. `detail` is free text: "loading graph, 40%". */
    public static PlannerState loading(String detail) {
        return new PlannerState(Kind.LOADING, detail);
    }

    /** The graph could not be read. `why` is shown verbatim, so make it actionable. */
    public static PlannerState failed(String why) {
        return new PlannerState(Kind.FAILED, why);
    }

    /** A plan is being solved. */
    public static PlannerState solving(String detail) {
        return new PlannerState(Kind.SOLVING, detail);
    }

    public Kind kind() {
        return kind;
    }

    public String message() {
        return message;
    }

    /** Red for a failure, muted for the rest: a slow load is not an error. */
    public int colour() {
        return kind == Kind.FAILED ? NodeStatus.INK_NEED : NodeStatus.INK_MUTED;
    }

    public enum Kind {
        IDLE,
        LOADING,
        SOLVING,
        FAILED
    }
}
