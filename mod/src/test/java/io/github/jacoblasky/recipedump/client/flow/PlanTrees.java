package io.github.jacoblasky.recipedump.client.flow;

import java.util.Arrays;

import io.github.jacoblasky.recipedump.plan.PlanNode;

/**
 * Plan-tree shapes the flow tests are built on.
 *
 * SHARED because both test classes want the same four, and two copies of `chain` is two
 * places to change the day `PlanNode` gains a required field -- at which point one of them
 * gets fixed and the other keeps compiling against a node that no longer describes anything.
 */
final class PlanTrees {

    private PlanTrees() {
    }

    static PlanNode node(String key, PlanNode... children) {
        PlanNode node = new PlanNode();
        node.key = key;
        node.name = key;
        node.label = key;
        node.kind = "item";
        node.need = 1;
        if (children.length > 0) {
            node.children = Arrays.asList(children);
        }
        return node;
    }

    /** A chain `depth` deep: the shape that would blow a recursive walk's stack. */
    static PlanNode chain(int depth) {
        PlanNode deepest = node("step" + (depth - 1));
        for (int i = depth - 2; i >= 0; i--) {
            deepest = node("step" + i, deepest);
        }
        return deepest;
    }

    /** One root over `leaves` children: many rows, two columns. */
    static PlanNode fan(int leaves) {
        PlanNode[] children = new PlanNode[leaves];
        for (int i = 0; i < leaves; i++) {
            children[i] = node("leaf" + i);
        }
        return node("root", children);
    }

    /** `breadth` children per node, `depth` deep: many columns AND many rows. */
    static PlanNode deepFan(int breadth, int depth) {
        if (depth == 0) {
            return node("leaf");
        }
        PlanNode[] children = new PlanNode[breadth];
        for (int i = 0; i < breadth; i++) {
            children[i] = deepFan(breadth, depth - 1);
        }
        return node("n" + depth, children);
    }
}
