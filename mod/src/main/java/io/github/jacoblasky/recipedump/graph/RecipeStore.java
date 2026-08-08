package io.github.jacoblasky.recipedump.graph;

/**
 * Every recipe, as flat primitive arrays rather than an object per recipe and per ingredient.
 *
 * WHAT A RECIPE IS, mirroring `model.Recipe`: some outputs, each a key and a quantity; some
 * input SLOTS, each of which is satisfied by ANY ONE of its alternatives at a given
 * quantity; a JEI category; the extractor that produced it; a machine display name; and two
 * flags.
 *
 * WHY THE ALTERNATIVES LIST MUST SURVIVE, restated from `model.py` because it is the thing a
 * flattening would silently destroy: a recipe input is a LIST of alternatives (oredict
 * members, or a JSON ingredient array) and the solver needs the choice, so it can satisfy an
 * input from what the player actually has. DO NOT collapse alternatives to a first choice
 * at load time. That is the extractor making the solver's decision, and the right decision
 * depends on an inventory the extractor has never seen.
 *
 * THE LAYOUT. Two levels of CSR, because a recipe has slots and a slot has alternatives:
 *
 * <pre>
 *   recipe r  -&gt; slots      slotOffsets[r]     .. slotOffsets[r + 1]
 *   slot   s  -&gt; alts       altOffsets[s]      .. altOffsets[s + 1]
 *   recipe r  -&gt; outputs    outputOffsets[r]   .. outputOffsets[r + 1]
 * </pre>
 *
 * The reference pack has 117,681 recipes, ~450,000 slots and ~1.5 million alternatives. As
 * objects that is three allocations per slot; here it is 4 bytes per alternative, 13 per slot
 * (quantity, role, consume chance) and 12 per output (key, quantity, yield chance), with no
 * per-recipe object at all.
 *
 * Ids, not strings, throughout. {@link RecipeGraph} owns the tables that decode them.
 */
public final class RecipeStore {

    /** `index.mark_container_transfers` said this recipe MOVES a fluid, never creates one. */
    public static final byte FLAG_TRANSFER = 1;
    /** One arm of an expanded variant table: it RESHAPES a form of the thing, not obtains it. */
    public static final byte FLAG_VARIANT = 2;

    private final int count;
    private final int[] outputOffsets;
    private final int[] outputKey;
    private final int[] outputQty;
    /**
     * Per output slot, the probability a run actually YIELDS it: 1.0 for every output in
     * every graph built before #223, which is what an absent `q` means.
     *
     * A THIRD PARALLEL COLUMN AND NOT A WIDENED `outputQty`, mirroring
     * `model.Recipe.yield_chance`, which is a list aligned to `outputs` rather than a third
     * tuple element. The quantity is an integer count of items and the chance is a
     * probability; packing them into one number would have to pick a scale, and the pack
     * declares chances down to 0.001.
     *
     * `double[]`, NOT `float[]` AND NOT A QUANTISED `byte[]`, AND THE WIDTH IS THE PORT'S
     * CONTRACT RATHER THAN A RANGE ARGUMENT. `graph.json` carries `q` as a JSON number,
     * python parses it to a double, and `PlanFixtureTest` compares this port's output to
     * python's with a ZERO delta. Narrowing to a float and widening back does not round-trip:
     * `(double) 0.001f` is 0.0010000000474974513, so every cost and every `yield_chance`
     * derived from it disagrees with python in the 8th significant figure. That is not a
     * tolerance question, it is the golden gate going red on a correct port -- and the
     * natural response to a red gate is to weaken the comparison.
     *
     * A byte quantised by 1/255 is worse still and for a second reason: it cannot tell 0.001
     * from 0.0, and on THIS side that rounding turns a recipe yielding its product one run in
     * a thousand into one that never yields it at all, which {@link #expectedYield}'s callers
     * must then treat as no route at all. 834 of the pack's 835 `addItemOutput` chances are
     * fractional and they span 0.99 down to 0.001, so the bottom of that range is populated.
     *
     * DO NOT make this a per-recipe value. A recipe can yield one output certainly and
     * another one time in a hundred, and the pack's Modular Machinery recipes routinely do.
     */
    private final double[] outputChance;
    private final int[] slotOffsets;
    private final int[] altOffsets;
    private final int[] altKey;
    private final int[] slotQty;
    private final byte[] slotRole;
    /**
     * Per slot, the probability a run CONSUMES it: 1.0 for every slot in every graph built
     * before #175, which is what an absent `p` means.
     *
     * NOT A QUANTISED `byte[]`, WHICH WAS MEASURED RATHER THAN ASSUMED. A byte scaled by 1/255
     * cannot tell 0.001 from 0.0, and 0.001 is a real value: the reference pack's only
     * fractional input slots are one deliberate 8-tier ladder in `Trinitas.zs` running 0.95,
     * 0.8, 0.5, 0.3, 0.1, 0.05, 0.01, 0.001. Rounding its bottom rung to zero turns an input
     * consumed one run in a thousand into a PERMANENT one, which the cost model then charges
     * once instead of a thousand times. That is the expensive direction of the error, so the
     * width is bought deliberately. `slotQty` beside it is an `int[]` of the same length and
     * nobody has minded.
     *
     * AND NOT A `float[]` EITHER, WHICH IT WAS UNTIL #223 AND WHICH THE GOLDEN GATE COULD NOT
     * SEE. `graph.json` carries `p` as a JSON number and python holds it as a double, so a
     * float column silently rewrites the pack's data: `(double) 0.95f` is 0.949999988079071,
     * and `Cost` multiplies an ingredient price by it. Every fractional slot therefore priced a
     * few ulps away from the oracle, which `PlanFixtureTest` compares with a ZERO delta. It
     * stayed invisible because the fixtures' oracle is a schema-5 graph carrying no `p` at all,
     * so the only exercised value was 1.0, which every width represents exactly. #223 gave the
     * OUTPUT side the same column, where 834 of 835 declared chances are fractional and the
     * masking would not have held, and the two columns must not disagree about width.
     *
     * DO NOT make this a per-recipe flag. Consumption is a property of the SLOT: the same
     * recipe can hold one item permanently and spend another, which is exactly the Forge of
     * the Wyverns case #175 was filed about.
     */
    private final double[] slotConsume;
    private final int[] categoryId;
    private final int[] machineId;
    private final byte[] sourceId;
    private final byte[] flags;
    /**
     * The dump's own recipe id, one per recipe, NOT deduplicated into a lookup index.
     *
     * Kept because it is the only thing that identifies a specific recipe across a reload,
     * which is what pinning a chosen recipe rests on. Held as a blob rather than as
     * Strings because these are the longest strings in the graph -- a jar path plus an asset
     * path, ~80 bytes each -- and nothing reads one unless a human is looking at it.
     *
     * NO LOOKUP INDEX, deliberately: nothing asks "which recipe has this id" on a hot path,
     * and the index would cost an int per recipe for a question nobody asks. The heap report
     * prices this table on its own so dropping it stays a decision someone can cost.
     */
    private final StringTable rids;

    RecipeStore(int count, int[] outputOffsets, int[] outputKey, int[] outputQty,
                double[] outputChance,
                int[] slotOffsets, int[] altOffsets, int[] altKey, int[] slotQty,
                byte[] slotRole, double[] slotConsume,
                int[] categoryId, int[] machineId, byte[] sourceId,
                byte[] flags, StringTable rids) {
        this.count = count;
        this.outputOffsets = outputOffsets;
        this.outputKey = outputKey;
        this.outputQty = outputQty;
        this.outputChance = outputChance;
        this.slotOffsets = slotOffsets;
        this.altOffsets = altOffsets;
        this.altKey = altKey;
        this.slotQty = slotQty;
        this.slotRole = slotRole;
        this.slotConsume = slotConsume;
        this.categoryId = categoryId;
        this.machineId = machineId;
        this.sourceId = sourceId;
        this.flags = flags;
        this.rids = rids;
    }

    public int count() {
        return count;
    }

    public int slotCount() {
        return slotOffsets[count];
    }

    public int alternativeCount() {
        return altKey.length;
    }

    // -- outputs ---------------------------------------------------------------------

    public int outputStart(int recipe) {
        return outputOffsets[recipe];
    }

    public int outputEnd(int recipe) {
        return outputOffsets[recipe + 1];
    }

    public int outputKeyAt(int position) {
        return outputKey[position];
    }

    public int outputQtyAt(int position) {
        return outputQty[position];
    }

    /**
     * How often a run actually yields the output at `position`, 0.0 to 1.0. See #223.
     *
     * Mirrors `model.Recipe.yield_of`, which exists so that "no chances recorded" and "a
     * chance of 1.0" are ONE answer to every caller. Here the column is always present and
     * always 1.0-filled for a pre-#223 graph, so the two spellings already agree.
     */
    public double outputChanceAt(int position) {
        return outputChance[position];
    }

    /**
     * Units of `keyId` one run of `recipe` produces ON AVERAGE, across every slot making it.
     *
     * THE SUM IS OVER SLOTS AND THE CHANCE IS PER SLOT, which is why the chance is a column
     * rather than a per-recipe number. A recipe emitting one key from two slots at different
     * chances is one expectation, and reading a single chance for the key would be wrong for
     * both halves of it.
     *
     * Returns 0.0 for a key this recipe does not make, AND CALLERS MUST HANDLE THAT RATHER
     * THAN DIVIDING BY IT: a recipe whose only slot for `keyId` has a chance of 0.0 yields it
     * never, and no number of runs produces one. Mirrors `model.Recipe.expected_yield`.
     *
     * THE ONE SPELLING BOTH THE RANKER AND THE EXPANDER CALL. `Solver.offerShape` summed the
     * matching slots while `Solver.build` read only the FIRST of them, so the two disagreed
     * about what a run produces on the 618 reference-graph recipes that name one output key
     * more than once -- #29's ranker-versus-solver divergence in miniature. A second
     * implementation of this anywhere is that defect coming back.
     */
    public double expectedYield(int recipe, int keyId) {
        double total = 0.0;
        for (int p = outputOffsets[recipe]; p < outputOffsets[recipe + 1]; p++) {
            if (outputKey[p] == keyId) {
                // ACCUMULATED IN CSR ORDER, WHICH IS THE CONTRACT. a+b+c is not a+c+b in
                // IEEE754, `PlanFixtureTest` compares this against python with a ZERO delta,
                // and CSR order is dump order, which is the order python's
                // `enumerate(self.outputs)` walks. Do not reorder or parallelise this loop.
                total += outputQty[p] * outputChance[p];
            }
        }
        return total;
    }

    /**
     * Units of `keyId` one run of `recipe` SAYS it produces, ignoring every chance.
     *
     * THE COUNTERPART TO {@link #expectedYield}, AND THE PAIR IS THE POINT. Nominal is what
     * the recipe claims; expected is what it delivers on average; their ratio is the only
     * honest single number for "how often does this work", and `Solver` reports it as
     * `yield_chance`. Reading either where the other is meant is #223 undone: the nominal one
     * is the pre-#223 yield, and dividing a demand by it is exactly the defect.
     *
     * A `long` because `outputQty` is an `int` per slot and several slots can name one key --
     * the reference pack has a recipe yielding 60,466,176 at once, and a sum of those is one
     * multiplication away from wrapping an `int`.
     *
     * Spelled inline in `solve._build` on the python side, where it is one comprehension.
     * Extracted here so the two loops over the same CSR range sit together and cannot drift
     * apart under a later edit to one of them.
     */
    public long nominalYield(int recipe, int keyId) {
        long total = 0;
        for (int p = outputOffsets[recipe]; p < outputOffsets[recipe + 1]; p++) {
            if (outputKey[p] == keyId) {
                total += outputQty[p];
            }
        }
        return total;
    }

    // -- input slots and their alternatives -------------------------------------------

    public int slotStart(int recipe) {
        return slotOffsets[recipe];
    }

    public int slotEnd(int recipe) {
        return slotOffsets[recipe + 1];
    }

    public int altStart(int slot) {
        return altOffsets[slot];
    }

    public int altEnd(int slot) {
        return altOffsets[slot + 1];
    }

    public int altKeyAt(int position) {
        return altKey[position];
    }

    public int slotQty(int slot) {
        return slotQty[slot];
    }

    public int slotRoleId(int slot) {
        return slotRole[slot] & 0xff;
    }

    /** The probability a run consumes `slot`. 1.0 unless the dump said otherwise (#175). */
    public double slotConsumeChance(int slot) {
        return slotConsume[slot];
    }

    /** True when a run never spends `slot`, so owning one is the whole requirement (#175). */
    public boolean slotSurvivesRun(int slot) {
        return slotConsume[slot] == 0.0;
    }

    // -- recipe attributes -------------------------------------------------------------

    public int categoryId(int recipe) {
        return categoryId[recipe];
    }

    /** The machine's display name id, or -1 when the recipe names no machine. */
    public int machineId(int recipe) {
        return machineId[recipe];
    }

    public int sourceId(int recipe) {
        return sourceId[recipe] & 0xff;
    }

    public boolean isTransfer(int recipe) {
        return (flags[recipe] & FLAG_TRANSFER) != 0;
    }

    public boolean isVariant(int recipe) {
        return (flags[recipe] & FLAG_VARIANT) != 0;
    }

    public String rid(int recipe) {
        return rids.get(recipe);
    }

    public long ridBytes() {
        return rids.retainedBytes();
    }

    public long retainedBytes() {
        return Sizes.object(15 * Sizes.REFERENCE + 4)
                + Sizes.bytes(outputOffsets) + Sizes.bytes(outputKey) + Sizes.bytes(outputQty)
                + Sizes.bytes(outputChance)
                + Sizes.bytes(slotOffsets) + Sizes.bytes(altOffsets) + Sizes.bytes(altKey)
                + Sizes.bytes(slotQty) + Sizes.bytes(slotRole) + Sizes.bytes(slotConsume)
                + Sizes.bytes(categoryId) + Sizes.bytes(machineId)
                + Sizes.bytes(sourceId) + Sizes.bytes(flags)
                + rids.retainedBytes();
    }

    /**
     * Accumulates recipes in dump order.
     *
     * Single pass, growable primitive arrays, no intermediate recipe object. A recipe is
     * opened with {@link #beginRecipe}, filled, and closed with {@link #endRecipe}; the
     * offsets fall out of the order things are added, which is why nothing here needs to
     * know a recipe's shape in advance. Streaming the JSON means we never do.
     */
    public static final class Builder {

        private final IntArray outputOffsets = new IntArray();
        private final IntArray outputKey = new IntArray();
        private final IntArray outputQty = new IntArray();
        private final DoubleArray outputChance = new DoubleArray();
        private final IntArray slotOffsets = new IntArray();
        private final IntArray altOffsets = new IntArray();
        private final IntArray altKey = new IntArray();
        private final IntArray slotQty = new IntArray();
        private final IntArray slotRole = new IntArray();
        private final DoubleArray slotConsume = new DoubleArray();
        private final IntArray categoryId = new IntArray();
        private final IntArray machineId = new IntArray();
        private final IntArray sourceId = new IntArray();
        private final IntArray flags = new IntArray();
        private final StringTable.Builder rids;
        private int count;
        private boolean open;
        /**
         * Tracked because the failure is SILENT otherwise. Two `beginSlot` calls without an
         * `endSlot` between them push a second quantity while pushing no alternative offset,
         * so every slot after it is paired with the wrong alternatives -- a graph that loads
         * cleanly and plans the wrong ingredients.
         */
        private boolean slotOpen;

        public Builder(int expectedRecipes, int expectedRidBytes) {
            outputOffsets.add(0);
            slotOffsets.add(0);
            altOffsets.add(0);
            // Deduplicated but with the index dropped at build time: duplicate rids do occur
            // (two JEI categories can hand back the same wrapper id), so collapsing them is
            // free, while keeping the index would cost an int per recipe for a lookup
            // nothing performs.
            rids = StringTable.builder(expectedRecipes, expectedRidBytes, false, false);
        }

        public void beginRecipe() {
            if (open) {
                throw new IllegalStateException("beginRecipe() without endRecipe()");
            }
            open = true;
        }

        public void addOutput(int keyId, int qty) {
            addOutput(keyId, qty, 1.0);
        }

        /**
         * Adds an output, saying how often a run actually YIELDS it (#223).
         *
         * The two-argument form above is the DEFAULT SPELLED ONCE, on the same terms as
         * {@link #beginSlot}: every caller with no yield information means 1.0, which is what
         * an absent `q` means and what every graph built before #223 says about every output.
         * `GraphJsonReader` is the only caller that passes a chance, because `graph.json` is
         * the only input that carries one.
         */
        public void addOutput(int keyId, int qty, double yieldChance) {
            require();
            outputKey.add(keyId);
            outputQty.add(qty);
            outputChance.add(yieldChance);
        }

        /** Opens a slot. Its alternatives follow via {@link #addAlternative}. */
        public void beginSlot(int qty, int roleId) {
            beginSlot(qty, roleId, 1.0);
        }

        /**
         * Opens a slot, saying how likely a run is to CONSUME it (#175).
         *
         * The two-argument form above is not a convenience, it is the DEFAULT SPELLED ONCE:
         * every caller that has no consumption information means 1.0, and having them each
         * write `1.0` is four places for the default to drift. `GraphJsonReader` is the only
         * caller that passes a chance, because `graph.json` is the only input that carries one.
         */
        public void beginSlot(int qty, int roleId, double consumeChance) {
            require();
            if (slotOpen) {
                throw new IllegalStateException("beginSlot() without endSlot()");
            }
            slotOpen = true;
            slotQty.add(qty);
            slotRole.add(roleId);
            slotConsume.add(consumeChance);
        }

        public void addAlternative(int keyId) {
            if (!slotOpen) {
                throw new IllegalStateException("no slot is open");
            }
            altKey.add(keyId);
        }

        public void endSlot() {
            if (!slotOpen) {
                throw new IllegalStateException("endSlot() without beginSlot()");
            }
            slotOpen = false;
            altOffsets.add(altKey.size());
        }

        /** Closes the recipe. `machineNameId` is -1 when the recipe names no machine. */
        public void endRecipe(String rid, int categoryStringId, int machineNameId,
                              int sourceStringId, int flagBits) {
            require();
            if (slotOpen) {
                throw new IllegalStateException("a slot is still open");
            }
            rids.add(rid == null ? "" : rid);
            categoryId.add(categoryStringId);
            machineId.add(machineNameId);
            sourceId.add(sourceStringId);
            flags.add(flagBits);
            outputOffsets.add(outputKey.size());
            slotOffsets.add(slotQty.size());
            count++;
            open = false;
        }

        private void require() {
            if (!open) {
                throw new IllegalStateException("no recipe is open");
            }
        }

        public int count() {
            return count;
        }

        public RecipeStore build() {
            if (open) {
                throw new IllegalStateException("a recipe is still open");
            }
            return new RecipeStore(count, outputOffsets.trimmed(), outputKey.trimmed(),
                    outputQty.trimmed(), outputChance.trimmed(),
                    slotOffsets.trimmed(), altOffsets.trimmed(),
                    altKey.trimmed(), slotQty.trimmed(), slotRole.toBytes(),
                    slotConsume.trimmed(),
                    categoryId.trimmed(), machineId.trimmed(), sourceId.toBytes(),
                    flags.toBytes(), rids.build());
        }
    }
}
