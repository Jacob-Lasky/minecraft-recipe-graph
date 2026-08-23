package io.github.jacoblasky.recipedump.client.planner;

/**
 * Where the flow diagram's widget comes from, for the one package that may not name it. #323.
 *
 * WHY THIS EXISTS AT ALL, when `new FlowCanvas(plan.tree())` inside {@link LivePlannerActions}
 * would do. It would point the wrong way. `client.flow` depends on `client.planner` -- its nodes
 * ARE `PlannerWidgets.planNodeContent` and its chrome IS `PlannerWidgets.flowPanel` -- and both
 * of those files say in as many words that the reverse must not happen. `PlannerLayoutTest`
 * keeps to it as well, writing `FlowLayout`'s node width out as a literal rather than importing
 * it, with a comment saying a test that inverted the direction "would be the first thing in the
 * package to do so". One import here would make this the first thing in the package to do so.
 *
 * So the one production class that has to build a canvas -- {@link LivePlannerActions}, which
 * owns the panel handler the button opens -- is handed the finished widget by a caller allowed
 * to name both packages. `PlannerScreen` is that caller: it is the layer above both, and it
 * already imports this package to build the panel at all.
 *
 * A CONSTRUCTOR ARGUMENT AND NOT A SETTABLE SEAM, deliberately, and this is the half worth
 * arguing. The seam shape -- a field, a public installer, a correct-looking no-op default -- is
 * how every joint in this mod that has ever come apart was built: #191 found three at once, #313
 * was the keybind that resolved a target and threw it away, and #323 is the panel this interface
 * is being written for. `SeamInstallationTest` exists because that failure is invisible from
 * every angle that normally works -- the producer is tested, the consumer is tested, nothing is
 * null and nothing logs. A constructor argument cannot be forgotten: the compiler asks for it at
 * the one call site there is, and there is no default sitting there to be silently correct.
 *
 * NOT `Function<PlanView, IWidget>`, which is the same shape with none of the meaning. This file
 * is where the dependency-direction argument above lives, and a `Function` at the call site would
 * carry it nowhere -- the next person to want a diagram would re-derive the import and find out
 * why it is wrong the hard way, or not at all.
 */
public interface FlowSource {

    /**
     * The diagram for `plan`, SIZED, and ready to be handed to `PlannerWidgets.flowPanel`.
     *
     * "SIZED" IS PART OF THE CONTRACT AND NOT A COURTESY. A widget added to a ModularUI panel
     * without a size is not stretched to fit it -- it comes back at nothing, and
     * `WidgetTree.resizeInternal` catches Throwable around the whole resize and only logs it, so
     * the panel opens and draws an empty grey rectangle with nothing on screen saying why.
     * `FlowCanvas.forPanel` is the implementation of this sentence.
     *
     * @param actions where a click on a diagram node goes. #325: until this argument existed the
     *                canvas built every node through the inert overload of
     *                `PlannerWidgets.planNodeContent`, so a diagram was a picture -- no node menu,
     *                no "Add to TODO", no recipe picker, and no way to originate a selection --
     *                while `planNodeContent`'s own javadoc said the node was clickable "LIKE row".
     *                A CONSTRUCTOR ARGUMENT AND NOT A SETTER, for the reason this interface's
     *                class note gives about itself: a settable seam with a well-behaved no-op
     *                default is how every joint in this mod that has come apart was built.
     */
    Diagram diagramFor(PlanView plan, PlannerActions actions);
}
