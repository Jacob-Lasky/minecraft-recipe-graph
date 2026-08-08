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
                if (entry.provenance != null) {
                    w.name("provenance").value(entry.provenance);
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
            writePerRun(w, node);
        }
        if (node.yieldChance != null) {
            w.name("yield_chance").value(node.yieldChance.doubleValue());
        }
        if (node.alternatives != null) {
            w.name("alternatives").value(node.alternatives);
        }
        if (node.interchangeable != null) {
            w.name("interchangeable").value(node.interchangeable);
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
        if (node.notConsumed != null) {
            w.name("not_consumed").value(node.notConsumed.booleanValue());
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
        if (node.provenance != null) {
            w.name("provenance").value(node.provenance);
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
    /**
     * `per_run`, as the token python writes: an INTEGER when the yield is certain, a DOUBLE
     * when it is an expectation. #252 owns this rule and #223 carries it.
     *
     * WHY THE RULE EXISTS AT ALL. An always-double emitter and this one compare EQUAL under
     * `JsonCompare`, which parses before comparing, so no golden gate can tell them apart --
     * and that is exactly why it had to be decided rather than discovered. Measured on the
     * python side before its own guard landed: 17 of 24 fixtures changed, 1,407 insertions
     * and 1,407 deletions, every line `"per_run": 1` becoming `"per_run": 1.0`, and not one
     * plan different. That churn buries the handful of real movements and makes the diff
     * unreviewable at the moment a reviewer most needs to see what moved.
     *
     * CERTAINTY, NOT MERE INTEGRALITY, AND THE TWO ARE NOT THE SAME PREDICATE. `Recipe.
     * expected_yield` returns an int only when every contributing slot is certain, and its
     * comment refuses `float(total).is_integer()` in as many words: two slots of 4 at a chance
     * of 0.5 sum to exactly 4.0, and that is still an EXPECTATION that must not masquerade as
     * a guaranteed count. Asking `isWhole` alone would write `4` there where python writes
     * `4.0`. So the integrality test is ANDed with the wire's own certainty signal.
     *
     * `yieldChance == null` IS THAT SIGNAL, and it is exact rather than convenient. `Solver.
     * build` writes the field precisely when `perRun < nominal`, which holds for an uncertain
     * key and fails for a certain one, so its absence means "every slot making this key was
     * certain" -- the same condition python tracks with its `certain` flag.
     *
     * AND `isWhole` STILL GUARDS THE CAST, because absence of a chance is not proof of a whole
     * number on hand-written or malformed JSON. Without it a `per_run` of 0.5 with no
     * `yield_chance` would be cast to a long and written as `0`, turning a display defect into
     * a silent data loss. See {@link Quantities#isWhole} for why the range check is part of
     * the question.
     */
    private static void writePerRun(JsonWriter w, PlanNode node) throws IOException {
        double perRun = node.perRun.doubleValue();
        if (node.yieldChance == null && Quantities.isWhole(perRun)) {
            w.name("per_run").value((long) perRun);
        } else {
            w.name("per_run").value(perRun);
        }
    }


}
