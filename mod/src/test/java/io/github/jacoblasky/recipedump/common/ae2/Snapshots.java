package io.github.jacoblasky.recipedump.common.ae2;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Snapshot fixtures shared by the tests on both sides of the wire.
 *
 * WHY A SHARED CLASS AND NOT A CONSTANT PER TEST FILE. {@link StockSnapshot#of} takes both
 * halves of a read since #320 and there is deliberately no one-argument form, so every test
 * that only cares about stock has to say something about machines. Four private copies of
 * `Collections.<String, Integer>emptyMap()` is the duplication that drifts one file at a time:
 * the day a fifth field joins the snapshot, four call sites need the same edit and the one that
 * is missed compiles.
 *
 * NAMED `NO_MACHINES` RATHER THAN `EMPTY`, because at a call site the assertion being made is
 * about what the grid carried and not about a map's size. `StockSnapshot.of(counts, NO_MACHINES)`
 * reads as "the read succeeded and carried no placed keys" -- a state a real grid walk cannot
 * produce (the map is node-keyed, so even cables appear), kept because deserialization of a
 * pre-placed payload produces it and because it is the honest fixture for "nothing owned". It
 * is a different statement from every refusal -- the distinction that type exists for.
 */
public final class Snapshots {

    /** An available read of a grid that carries no machines. NOT a refusal. */
    public static final Map<String, Integer> NO_MACHINES =
            Collections.unmodifiableMap(new LinkedHashMap<String, Integer>());

    private Snapshots() {
    }

    /**
     * A placed map from `key, count, key, count` pairs.
     *
     * Mirrors the `counts` helper the snapshot tests already use for the item half, so the two
     * halves of a fixture are written the same way rather than one being a literal map builder
     * and the other a helper.
     */
    public static Map<String, Integer> machines(Object... pairs) {
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], Integer.valueOf(((Number) pairs[i + 1]).intValue()));
        }
        return out;
    }
}
