package io.github.jacoblasky.recipedump.client.planner;

import io.github.jacoblasky.recipedump.plan.PlanNode;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Reads the frozen plan shape into {@link PlanView}.
 *
 * WHY A READER EXISTS AT ALL, given that #141 will hand back objects directly: because the
 * shape is the contract, and reading it is how the panel can be built and tested before a
 * Java solver exists. The 21 plan fixtures under `tests/fixtures/plan/` are real solved plans
 * from the reference pack -- a 634-node fluid chain, a truncated tree, a cycle, six items
 * called "Iron Plate" -- which is far better test data than anything anyone would write by
 * hand, and it is already checked in.
 *
 * IT READS A SUBSET OF THE RESULT AND IGNORES THE REST, on purpose. `target`, `qty`, `tree`,
 * `truncated`, `exhausted`, `nodes`, `max_nodes`, `shopping_list`, `machines_to_build` and
 * `pins_overruled` are what the panel draws; `work`, `work_budget`, `used_from_stock`,
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
        // SORTED BY THE MESSAGE, matching `render.py`'s warnbar and `cli.cmd_plan`, so a plan
        // reads the same way in game as it does in the browser and the terminal.
        List<String> overruled = new ArrayList<String>();
        if (result.has("pins_overruled") && result.get("pins_overruled").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e
                    : result.getAsJsonObject("pins_overruled").entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    overruled.add(e.getValue().getAsString());
                }
            }
        }
        Collections.sort(overruled);
        return new PlanView(string(result, "target"), string(result, "target_name"),
                            number(result, "qty", 1L), readNode(treeJson),
                            bool(result, "truncated"), bool(result, "exhausted"),
                            (int) number(result, "nodes", 0L),
                            (int) number(result, "max_nodes", 0L),
                            shopping, machines, overruled);
    }

    /**
     * One node and its whole subtree.
     *
     * ITERATIVE WOULD BE SAFER AND IS NOT WORTH IT: the deepest fixture is a 634-node chain
     * whose depth is nowhere near a stack frame limit, and the planner's own `--depth` cap
     * bounds it. If a plan ever arrives deep enough to overflow this, the tree was already
     * too deep to read.
     */
    public static PlanNode readNode(JsonObject json) {
        // ABSENT STAYS ABSENT. This reader used to default every optional number to 0 and
        // every flag to false, which was harmless while it fed a renderer-only class -- the
        // panel draws the same row either way. It feeds the SHARED class now, the one
        // `plan.PlanJson` writes from, so a 0 substituted for a missing key would make the
        // emitter start writing `"runs": 0` where Python omits it and every golden fixture
        // would disagree. `longOrNull` and friends exist for that and for nothing else.
        PlanNode.Builder builder = new PlanNode.Builder()
                .key(string(json, "key"))
                .kind(has(json, "kind") ? string(json, "kind") : "item")
                .label(string(json, "label"))
                .need(number(json, "need", 1L))
                .status(has(json, "status") ? string(json, "status") : NodeStatus.CRAFT)
                .category(optional(json, "category"))
                .machine(optional(json, "machine"))
                .machineState(optional(json, "machine_state"))
                .machineWhy(optional(json, "machine_why"))
                .recipe(optional(json, "recipe"))
                .runs(longOrNull(json, "runs"))
                .perRun(longOrNull(json, "per_run"))
                .alternatives(intOrNull(json, "alternatives"))
                .altCount(intOrNull(json, "alt_count"))
                .note(optional(json, "note"))
                .resolvedTo(optional(json, "resolved_to"))
                .dimension(optional(json, "dimension"))
                .tokenKind(optional(json, "token_kind"))
                .fromStock(longOrNull(json, "from_stock"))
                .pinned(boolOrNull(json, "pinned"))
                .unsourced(boolOrNull(json, "unsourced"));
        // `name` IS THE ONE FIELD THAT KEEPS A FALLBACK, and it is safe where the others
        // were not. It is present on all 571 nodes across the fixtures and `PlanJson` writes
        // it unconditionally, so it is not an optional key -- the fallback covers hand-written
        // or partial JSON only. Making it strict would be worse than the disease: the writer
        // does not null-guard it, so an absent name would come back out as `"name": null`,
        // which no fixture carries either.
        builder.name(has(json, "name") ? string(json, "name") : string(json, "label"));

        JsonArray kids = json.getAsJsonArray("children");
        if (kids != null && kids.size() > 0) {
            List<PlanNode> children = new ArrayList<PlanNode>(kids.size());
            for (JsonElement kid : kids) {
                children.add(readNode(kid.getAsJsonObject()));
            }
            builder.children(children);
        }
        return builder.build();
    }

    /** The value, or null when the key is absent. See {@link #readNode} for why null. */
    private static Long longOrNull(JsonObject json, String field) {
        return has(json, field) ? Long.valueOf(json.get(field).getAsLong()) : null;
    }

    private static Integer intOrNull(JsonObject json, String field) {
        return has(json, field) ? Integer.valueOf(json.get(field).getAsInt()) : null;
    }

    /**
     * TRUE OR NULL, never `Boolean.FALSE`.
     *
     * Python writes these flags only when they are true, so "present and false" is a state
     * the wire format does not have. Returning FALSE for an absent key would round-trip into
     * `"pinned": false` and disagree with every fixture.
     */
    private static Boolean boolOrNull(JsonObject json, String field) {
        return has(json, field) && json.get(field).getAsBoolean() ? Boolean.TRUE : null;
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
