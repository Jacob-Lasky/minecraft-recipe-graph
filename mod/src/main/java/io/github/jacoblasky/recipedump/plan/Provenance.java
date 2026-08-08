package io.github.jacoblasky.recipedump.plan;

/**
 * How the PACK says you get an item, when the JEI dump cannot say it at all. #171/#262.
 *
 * Ports the kind vocabulary of `recipegraph/provenance.py`, exactly as {@link Tokens} ports
 * `recipegraph/tokens.py`'s. The READER half of that module -- the ZenScript and
 * BetterQuesting scanners -- has no Java side and must not grow one: the pack's `scripts/`
 * and `config/betterquesting/` are read once by `index.build`, and the answer travels to the
 * mod inside `graph.json` as `declared_provenance`, on exactly the footing `dimension_ores`
 * travels on. A second scanner here would be a second answer to a question the build already
 * settled, against files a Minecraft client has no reason to open.
 *
 * THREE VALUES RATHER THAN ONE "declared" FLAG, because {@link Cost#provenanceCost} prices
 * them differently and #95 is the standing lesson: one figure carrying two unrelated
 * statements destroys the ordering among both.
 *
 * THE STRINGS ARE THE WIRE FORMAT. `Graph.declared_provenance` writes these words into
 * graph.json and `solve.py` writes them onto a plan node's `provenance` field, so they are
 * spelled here the way python spells them and are compared, never enumerated -- an unknown
 * kind is a pack the reader has grown past, not a crash. See {@link #noteFor}.
 */
public final class Provenance {

    /** `recipes.addHiddenShapeless` behind a gamestage: a REAL recipe JEI never publishes. */
    public static final String PUZZLE = "puzzle";
    /** `chest.addItemEntry`: the item is in a table you farm. */
    public static final String LOOT_TABLE = "loot_table";
    /** A BetterQuesting reward, which is progress you make rather than a thing you fetch. */
    public static final String QUEST = "quest";

    /**
     * Every kind this port knows, in `provenance.KINDS` order.
     *
     * FOR TESTS AND LEGENDS, NOT FOR DISPATCH. Every lookup below compares rather than
     * switching on an index, so a pack declaring a kind absent from this array degrades to
     * the fallback wording and {@code UNSOURCED_COST} -- the pre-#171 answer -- rather than
     * throwing inside a plan. That is deliberate; see `provenance.note_for` in python.
     */
    public static final String[] KINDS = {PUZZLE, LOOT_TABLE, QUEST};

    private Provenance() {
    }

    /**
     * What this kind tells a reader they have to go and do, byte-equal to
     * `provenance.KIND_NOTE`.
     *
     * Phrased as the answer to "where does this come from", because that is the question
     * {@link Solver} puts on a plan node. THE GOLDEN FIXTURES HOLD THESE SENTENCES EQUAL
     * ACROSS THE TWO LANGUAGES, so a reworded sentence here is a red gate rather than two
     * tools describing one item differently.
     */
    public static String noteFor(String kind) {
        if (PUZZLE.equals(kind)) {
            return "the pack makes this with a hidden recipe JEI never publishes, unlocked "
                    + "by solving its puzzle";
        }
        if (LOOT_TABLE.equals(kind)) {
            return "the pack puts this in a loot table, so it is found by playing";
        }
        if (QUEST.equals(kind)) {
            return "the pack hands this out as a quest reward";
        }
        // THE ONE PLACE THE FALLBACK LIVES, matching `provenance.note_for`. A `?:` at each
        // call site is two spellings of it and, worse, two places where an unrecognised kind
        // reads as deliberate.
        return "the pack declares where this comes from";
    }

    /**
     * The badge word for a declared kind, byte-equal to `provenance.KIND_BADGE`.
     *
     * Short, because it sits at the end of a row that already carries a quantity and a name
     * -- the same constraint `NodeStatus.tokenBadge` is written to, and deliberately NOT the
     * same table: a token is a placeholder standing in for an instruction, and these are real
     * items whose route the dump could not carry.
     */
    public static String badgeFor(String kind) {
        if (PUZZLE.equals(kind)) {
            return "puzzle";
        }
        if (LOOT_TABLE.equals(kind)) {
            return "go get";
        }
        if (QUEST.equals(kind)) {
            return "quest reward";
        }
        return "declared";
    }
}
