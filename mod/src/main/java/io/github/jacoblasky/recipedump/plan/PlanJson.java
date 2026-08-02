package io.github.jacoblasky.recipedump.plan;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import com.google.gson.stream.JsonWriter;

/**
 * {@link PlanResult} as the JSON `solve.py` produces.
 *
 * A NULL FIELD IS OMITTED, NOT WRITTEN AS NULL. The Python original builds dicts and adds
 * keys conditionally, and `tests/fixtures/plan/*.json` freeze exactly which keys are present
 * -- so `"from_stock": null` would differ from every fixture while being behaviourally
 * identical. Every `write` below is guarded for that reason and not out of tidiness.
 *
 * Written by hand rather than by gson's reflection because the field names are snake_case on
 * the wire and camelCase in Java, and because the omission rule above is not gson's default.
 * An `@SerializedName` on twenty fields would be the same amount of code with the mapping
 * spread across two files.
 */
public final class PlanJson {

    private PlanJson() {
    }

    public static String toJson(PlanResult result) {
        StringWriter out = new StringWriter();
        JsonWriter writer = new JsonWriter(out);
        writer.setIndent("  ");
        try {
            write(writer, result);
            writer.flush();
        } catch (IOException e) {
            // A StringWriter cannot fail, so this is unreachable rather than survivable.
            throw new IllegalStateException("writing a plan to a string threw", e);
        }
        return out.toString();
    }

    private static void write(JsonWriter w, PlanResult r) throws IOException {
        w.beginObject();
        w.name("target").value(r.target);
        w.name("target_name").value(r.targetName);
        w.name("pins_overruled").beginObject();
        for (Map.Entry<String, String> e : r.pinsOverruled.entrySet()) {
            w.name(e.getKey()).value(e.getValue());
        }
        w.endObject();
        w.name("qty").value(r.qty);
        w.name("tree");
        write(w, r.tree);
        w.name("shopping_list");
        writeEntries(w, r.shoppingList);
        w.name("used_from_stock");
        writeEntries(w, r.usedFromStock);
        w.name("from_sources");
        writeEntries(w, r.fromSources);
        w.name("tokens_needed");
        writeEntries(w, r.tokensNeeded);
        w.name("from_emc");
        writeEntries(w, r.fromEmc);
        w.name("machines_to_build").beginArray();
        for (PlanResult.MachineToBuild machine : r.machinesToBuild) {
            w.beginObject();
            w.name("category").value(machine.category);
            w.name("machine").value(machine.machine);
            w.name("state").value(machine.state);
            w.name("why").value(machine.why);
            w.endObject();
        }
        w.endArray();
        w.name("nodes").value(r.nodes);
        w.name("work").value(r.work);
        w.name("truncated").value(r.truncated);
        w.name("exhausted").value(r.exhausted);
        w.name("max_nodes").value(r.maxNodes);
        w.name("work_budget").value(r.workBudget);
        w.endObject();
    }

    private static void writeEntries(JsonWriter w, List<PlanEntry> entries) throws IOException {
        w.beginArray();
        if (entries != null) {
            for (PlanEntry entry : entries) {
                w.beginObject();
                w.name("key").value(entry.key);
                w.name("name").value(entry.name);
                w.name("kind").value(entry.kind);
                w.name("label").value(entry.label);
                w.name("qty").value(entry.qty);
                if (entry.why != null) {
                    w.name("why").value(entry.why);
                }
                if (entry.tokenKind != null) {
                    w.name("token_kind").value(entry.tokenKind);
                }
                if (entry.emc != null) {
                    w.name("emc").value(entry.emc);
                }
                if (entry.unsourced != null) {
                    w.name("unsourced").value(entry.unsourced.booleanValue());
                }
                w.endObject();
            }
        }
        w.endArray();
    }

    private static void write(JsonWriter w, PlanNode node) throws IOException {
        w.beginObject();
        w.name("key").value(node.key);
        w.name("name").value(node.name);
        w.name("kind").value(node.kind);
        w.name("label").value(node.label);
        w.name("need").value(node.need);
        if (node.status != null) {
            w.name("status").value(node.status);
        }
        if (node.fromStock != null) {
            w.name("from_stock").value(node.fromStock);
        }
        if (node.note != null) {
            w.name("note").value(node.note);
        }
        if (node.recipe != null) {
            w.name("recipe").value(node.recipe);
        }
        if (node.category != null) {
            w.name("category").value(node.category);
        }
        if (node.runs != null) {
            w.name("runs").value(node.runs.longValue());
        }
        if (node.perRun != null) {
            w.name("per_run").value(node.perRun.longValue());
        }
        if (node.alternatives != null) {
            w.name("alternatives").value(node.alternatives);
        }
        if (node.pinned != null) {
            w.name("pinned").value(node.pinned.booleanValue());
        }
        if (node.machine != null) {
            w.name("machine").value(node.machine);
        }
        if (node.machineState != null) {
            w.name("machine_state").value(node.machineState);
        }
        if (node.machineWhy != null) {
            w.name("machine_why").value(node.machineWhy);
        }
        if (node.resolvedTo != null) {
            w.name("resolved_to").value(node.resolvedTo);
        }
        if (node.altCount != null) {
            w.name("alt_count").value(node.altCount);
        }
        if (node.dimension != null) {
            w.name("dimension").value(node.dimension);
        }
        if (node.tokenKind != null) {
            w.name("token_kind").value(node.tokenKind);
        }
        if (node.unsourced != null) {
            w.name("unsourced").value(node.unsourced.booleanValue());
        }
        if (node.children != null) {
            w.name("children").beginArray();
            for (PlanNode child : node.children) {
                write(w, child);
            }
            w.endArray();
        }
        w.endObject();
    }
}
