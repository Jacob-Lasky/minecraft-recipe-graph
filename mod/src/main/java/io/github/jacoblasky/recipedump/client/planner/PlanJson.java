package io.github.jacoblasky.recipedump.client.planner;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Reads the frozen plan shape into {@link PlanView}.
 *
 * WHY A READER EXISTS AT ALL, given that #141 will hand back objects directly: because the
 * shape is the contract, and reading it is how the panel can be built and tested before a
 * Java solver exists. The 22 fixtures under `tests/fixtures/plan/` are real solved plans
 * from the reference pack -- a 347-node fluid chain, a truncated tree, a cycle, six items
 * called "Iron Plate" -- which is far better test data than anything anyone would write by
 * hand, and it is already checked in.
 *
 * IT READS A SUBSET OF THE RESULT AND IGNORES THE REST, on purpose. `target`, `qty`, `tree`,
 * `truncated`, `exhausted`, `nodes`, `max_nodes`, `shopping_list` and `machines_to_build` are
 * what the panel draws; `work`, `work_budget`, `pins_overruled`, `used_from_stock`,
 * `from_emc`, `from_sources` and `tokens_needed` are read past without complaint. An unknown
 * field is not an error -- the solver is free to add one -- and a panel that refused to render
 * a plan because it carried a field nobody draws would be the worse failure.
 *
 * EVERY FIELD IS READ DEFENSIVELY, and that is not paranoia about the fixtures. This same
 * reader is what a future `PlanResult.toJson` round-trip would go through, and a missing
 * optional field must produce a node that renders rather than an exception in the middle of
 * a GUI. What it will NOT do is invent a key or a label: those are the identity of the row.
 */
public final class PlanJson {

    private PlanJson() {
    }

    /** Read a whole fixture file, which wraps the plan in a `result` object. */
    public static PlanView readFixture(InputStream in) {
        JsonObject root = parse(in);
        JsonObject result = root.getAsJsonObject("result");
        if (result == null) {
            throw new IllegalArgumentException(
                    "not a plan fixture: no `result` object, found " + root.entrySet().size()
                    + " top-level keys");
        }
        return readResult(result);
    }

    /**
     * Read a bare plan result from JSON text.
     *
     * The door for the in-game path: the Java solver's own `plan.PlanJson.toJson` produces
     * exactly this, and that serializer is the one the golden gate pins against the Python
     * oracle. So a plan that renders in game rendered from bytes provably identical to what
     * Python produces, and there is no third place the shape lives.
     */
    public static PlanView readResult(String json) {
        return readResult(new JsonParser().parse(json).getAsJsonObject());
    }

    /** Read a bare plan result, which is what a solver hands back. */
    public static PlanView readResult(JsonObject result) {
        JsonObject treeJson = result.getAsJsonObject("tree");
        if (treeJson == null) {
            throw new IllegalArgumentException("a plan result must carry a `tree`");
        }
        List<PlanView.ShoppingRow> shopping = new ArrayList<PlanView.ShoppingRow>();
        JsonArray rows = result.getAsJsonArray("shopping_list");
        if (rows != null) {
            for (JsonElement row : rows) {
                JsonObject o = row.getAsJsonObject();
                shopping.add(new PlanView.ShoppingRow(string(o, "key"), string(o, "label"),
                                                      number(o, "qty", 0L)));
            }
        }
        List<PlanView.MachineRow> machines = new ArrayList<PlanView.MachineRow>();
        JsonArray machineRows = result.getAsJsonArray("machines_to_build");
        if (machineRows != null) {
            for (JsonElement row : machineRows) {
                JsonObject o = row.getAsJsonObject();
                machines.add(new PlanView.MachineRow(string(o, "category"),
                                                     string(o, "machine"),
                                                     string(o, "state"),
                                                     string(o, "why")));
            }
        }
        return new PlanView(string(result, "target"), string(result, "target_name"),
                            number(result, "qty", 1L), readNode(treeJson),
                            bool(result, "truncated"), bool(result, "exhausted"),
                            (int) number(result, "nodes", 0L),
                            (int) number(result, "max_nodes", 0L),
                            shopping, machines);
    }

    /**
     * One node and its whole subtree.
     *
     * ITERATIVE WOULD BE SAFER AND IS NOT WORTH IT: the deepest fixture is a 347-node chain
     * whose depth is nowhere near a stack frame limit, and the planner's own `--depth` cap
     * bounds it. If a plan ever arrives deep enough to overflow this, the tree was already
     * too deep to read.
     */
    public static PlanNode readNode(JsonObject json) {
        PlanNode.Builder builder = new PlanNode.Builder();
        builder.key = string(json, "key");
        builder.kind = has(json, "kind") ? string(json, "kind") : "item";
        builder.label = string(json, "label");
        builder.name = has(json, "name") ? string(json, "name") : builder.label;
        builder.need = number(json, "need", 1L);
        builder.status = has(json, "status") ? string(json, "status") : NodeStatus.CRAFT;

        builder.category = optional(json, "category");
        builder.machine = optional(json, "machine");
        builder.machineState = optional(json, "machine_state");
        builder.machineWhy = optional(json, "machine_why");
        builder.recipe = optional(json, "recipe");
        builder.runs = number(json, "runs", 0L);
        builder.perRun = number(json, "per_run", 0L);

        builder.alternatives = (int) number(json, "alternatives", 0L);
        builder.altCount = (int) number(json, "alt_count", 0L);
        builder.note = optional(json, "note");
        builder.resolvedTo = optional(json, "resolved_to");
        builder.dimension = optional(json, "dimension");
        builder.tokenKind = optional(json, "token_kind");
        builder.fromStock = number(json, "from_stock", 0L);
        builder.pinned = bool(json, "pinned");
        builder.unsourced = bool(json, "unsourced");

        JsonArray kids = json.getAsJsonArray("children");
        if (kids == null || kids.size() == 0) {
            builder.children = Collections.emptyList();
        } else {
            List<PlanNode> children = new ArrayList<PlanNode>(kids.size());
            for (JsonElement kid : kids) {
                children.add(readNode(kid.getAsJsonObject()));
            }
            builder.children = children;
        }
        return builder.build();
    }

    private static JsonObject parse(InputStream in) {
        Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
        return new JsonParser().parse(reader).getAsJsonObject();
    }

    private static boolean has(JsonObject json, String field) {
        JsonElement element = json.get(field);
        return element != null && !element.isJsonNull();
    }

    private static String string(JsonObject json, String field) {
        return has(json, field) ? json.get(field).getAsString() : "";
    }

    /** "" and null are different answers: null means the field was not there at all. */
    private static String optional(JsonObject json, String field) {
        return has(json, field) ? json.get(field).getAsString() : null;
    }

    private static long number(JsonObject json, String field, long fallback) {
        return has(json, field) ? json.get(field).getAsLong() : fallback;
    }

    private static boolean bool(JsonObject json, String field) {
        return has(json, field) && json.get(field).getAsBoolean();
    }
}
