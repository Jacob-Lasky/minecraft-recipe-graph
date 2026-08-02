package io.github.jacoblasky.recipedump.graph;

/**
 * Recipes plus the produced-by index and display names -- the Java form of `model.Graph`.
 *
 * THE SHAPE OF THE PORT, and why it is not a transliteration. `model.py` hangs objects off
 * dicts: a {@code Recipe} per recipe, an {@code Ingredient} per slot, a {@code str} per key
 * occurrence, and {@code dict} indexes over all of it. That is the right design for a
 * process with a machine's whole memory to itself. This one has to live in a Minecraft
 * 1.12.2 client already running 410 mods, under a 400 MB gate (#126), so every one of those
 * becomes an int into a flat table:
 *
 * <ul>
 * <li>every key is interned ONCE into {@link StringTable} and passed as an {@code int};</li>
 * <li>recipes, adjacency and oredict membership are {@link Csr} offset+data pairs;</li>
 * <li>display names are a deduplicated side table decoded only when something is shown.</li>
 * </ul>
 *
 * WHAT IS NOT MEMOISED, and why that is a saving rather than an omission. `Graph.producers`
 * in Python caches its result per key, because it rebuilds a list on every call and the
 * solver scores every candidate of every node. Here {@link #producers} appends CSR rows into
 * a caller-supplied buffer and allocates nothing, so a cache would only add heap and a
 * second thing to invalidate. DO NOT add one back without measuring that it helps.
 *
 * THIS CLASS IS IMMUTABLE ONCE BUILT. `model.Graph` can be mutated with `add()` and drops
 * five derived indexes when it is; nothing in the mod's read path needs that, and dropping
 * mutability is what lets every index be a right-sized primitive array instead of a
 * growable one. Rebuild through {@link GraphBuilder} instead.
 *
 * NO MINECRAFT AND NO FORGE IMPORTS ANYWHERE IN THIS PACKAGE. That is deliberate and
 * load-bearing twice over: it is what lets the whole model be unit-tested in a plain JUnit
 * run with no game, and it is what lets the heap harness measure it in a bare JVM whose
 * baseline is not polluted by a loaded game. DO NOT reach for `ItemStack` here; convert at
 * the boundary.
 */
public final class RecipeGraph {

    private final StringTable keys;
    /**
     * Distinct display strings. NOT one per key: 261,095 keys carry a name on the reference
     * pack and the distinct strings among them are a fraction of that, because 5,095 labels
     * are shared by two or more keys and 286 items are called "Spell Book".
     */
    private final StringTable displayNames;
    private final int[] nameId;
    /**
     * Keys whose recorded label is an unlocalized lang key rather than a name.
     *
     * `Graph.relabel_unlocalized` REWRITES those labels in Python, one pass at load, so that
     * search, reverse lookup, the CLI and every HTML surface see the replacement at once.
     * Here the replacement is computed lazily instead and this bitset is what triggers it --
     * equivalent because every consumer goes through {@link #bareName}, and cheaper because
     * nothing has to hold 261,095 mutable label strings at load time to rewrite 1,429 of
     * them.
     *
     * The two flags stay SEPARATE from `nameId` on purpose. `display` suppresses the
     * bracketed type prefix for any key that HAS a name, and after the Python relabel a junk
     * key still has one -- so "was named" and "the name is usable" are different questions
     * and one flag cannot answer both.
     */
    private final long[] unlocalizedName;
    /** One of the KIND_ constants per key, so the hot path never decodes a key to ask. */
    private final byte[] kindOf;

    private final StringTable categories;
    private final StringTable machineNames;
    private final StringTable sources;
    private final StringTable roles;
    private final RecipeStore recipes;

    private final Csr byOutput;
    private final Csr byInput;
    /**
     * key -&gt; the id of the same base at the wildcard meta, or -1.
     *
     * Precomputed because `producers` and `consumers` both fall back to `base:&#42;`, and
     * doing that by building the string and hashing it would allocate on the solver's
     * hottest path. Null when the graph holds no wildcard key at all, which is the common
     * case for a hand-built fixture and saves the array entirely.
     */
    private final int[] wildcardSibling;

    private final StringTable oreNames;
    private final Csr oreMembers;
    private final Csr oreIndex;
    private final int[] oreGroupKeyId;
    private final long[] oreGuessed;
    private final long[] worldOres;
    private final long[] liveKeys;
    private final long[] reshapedOnly;

    private final Csr catalysts;
    private final StringTable categoryMods;
    private final int[] categoryModId;

    private final int[] dimensionOreKey;
    private final int[] dimensionOreDimId;
    private final int[] dimensionOreNameId;
    private final StringTable dimensionNames;

    private final Multiblocks multiblocks;
    private final int dumpSchema;
    private final String dumpVersion;
    private final String instanceDir;

    private FluidNames fluidNames;

    RecipeGraph(StringTable keys, StringTable displayNames, int[] nameId,
                long[] unlocalizedName, byte[] kindOf, StringTable categories,
                StringTable machineNames, StringTable sources, StringTable roles,
                RecipeStore recipes, Csr byOutput, Csr byInput, int[] wildcardSibling,
                StringTable oreNames, Csr oreMembers, Csr oreIndex, int[] oreGroupKeyId,
                long[] oreGuessed, long[] worldOres, long[] liveKeys, long[] reshapedOnly,
                Csr catalysts, StringTable categoryMods, int[] categoryModId,
                int[] dimensionOreKey, int[] dimensionOreDimId, int[] dimensionOreNameId,
                StringTable dimensionNames, Multiblocks multiblocks, int dumpSchema,
                String dumpVersion, String instanceDir) {
        this.keys = keys;
        this.displayNames = displayNames;
        this.nameId = nameId;
        this.unlocalizedName = unlocalizedName;
        this.kindOf = kindOf;
        this.categories = categories;
        this.machineNames = machineNames;
        this.sources = sources;
        this.roles = roles;
        this.recipes = recipes;
        this.byOutput = byOutput;
        this.byInput = byInput;
        this.wildcardSibling = wildcardSibling;
        this.oreNames = oreNames;
        this.oreMembers = oreMembers;
        this.oreIndex = oreIndex;
        this.oreGroupKeyId = oreGroupKeyId;
        this.oreGuessed = oreGuessed;
        this.worldOres = worldOres;
        this.liveKeys = liveKeys;
        this.reshapedOnly = reshapedOnly;
        this.catalysts = catalysts;
        this.categoryMods = categoryMods;
        this.categoryModId = categoryModId;
        this.dimensionOreKey = dimensionOreKey;
        this.dimensionOreDimId = dimensionOreDimId;
        this.dimensionOreNameId = dimensionOreNameId;
        this.dimensionNames = dimensionNames;
        this.multiblocks = multiblocks;
        this.dumpSchema = dumpSchema;
        this.dumpVersion = dumpVersion;
        this.instanceDir = instanceDir;
    }

    // -- keys ---------------------------------------------------------------------------

    public int keyCount() {
        return keys.size();
    }

    /** The id of `key`, or -1 when this graph has never seen it. */
    public int keyId(String key) {
        return keys.idOf(key);
    }

    public String key(int keyId) {
        return keys.get(keyId);
    }

    /** Which namespace a key lives in: `fluid`, `essentia`, `ore`, or `item`. */
    public String kind(int keyId) {
        return Keys.kindName(kindOf[keyId]);
    }

    public boolean isFluid(int keyId) {
        return kindOf[keyId] == Keys.KIND_FLUID;
    }

    // -- names --------------------------------------------------------------------------

    /** True when some source recorded a label for this key, usable or not. */
    public boolean hasName(int keyId) {
        return nameId[keyId] >= 0;
    }

    /** The recorded label if it is usable, else null. Mirrors `names.get` AFTER a relabel. */
    private String usableName(int keyId) {
        if (nameId[keyId] < 0 || Bits.get(unlocalizedName, keyId)) {
            return null;
        }
        return displayNames.get(nameId[keyId]);
    }

    /**
     * The display label without a type prefix, for callers that show the type separately.
     *
     * Ported clause for clause from `Graph.bare_name`, including the fallbacks that look
     * like polish and are not:
     *
     * <ul>
     * <li>A `%s` in a label is an aspect-parameterised name out of items.csv ("%s Vis Pod").
     *     With no aspect to fill in, the placeholder is dropped rather than shown raw.</li>
     * <li>A fluid takes the name of the CONTAINER it is bottled in before it takes its
     *     prettified registry name, because the pack renames fluids in lang constantly and
     *     the registry name is then the PRE-RENAME identity of a different substance. 789 of
     *     1,198 fluid labels were wrong that way. See #103.</li>
     * <li>An unnamed key falls back to its registry PATH, prettified, because a raw key
     *     sitting next to properly-cased names reads as a variable rather than an item.</li>
     * </ul>
     */
    public String bareName(int keyId) {
        String recorded = usableName(keyId);
        if (recorded != null) {
            if (recorded.indexOf("%s") >= 0) {
                String stripped = recorded.replace("%s ", "").replace("%s", "").trim();
                return stripped.isEmpty() ? keys.get(keyId) : stripped;
            }
            return recorded;
        }
        String key = keys.get(keyId);
        switch (kindOf[keyId]) {
            case Keys.KIND_ORE:
                return key.substring(Keys.ORE_PREFIX.length());
            case Keys.KIND_FLUID: {
                String derived = fluidNames().nameOf(keyId);
                return derived != null ? derived
                        : Keys.prettify(key.substring(Keys.FLUID_PREFIX.length()));
            }
            case Keys.KIND_ESSENTIA: {
                String aspect = key.substring(Keys.ESSENTIA_PREFIX.length());
                return aspect.isEmpty() ? aspect
                        : Character.toUpperCase(aspect.charAt(0))
                          + aspect.substring(1).toLowerCase();
            }
            default:
                break;
        }
        String discriminator = Keys.discriminator(key);
        if (discriminator != null) {
            String stem = Keys.baseKey(key);
            int stemId = keys.idOf(stem);
            String label = stemId >= 0 ? usableName(stemId) : null;
            if (label == null) {
                label = stemId >= 0 ? bareName(stemId) : Keys.prettify(Keys.pathOf(stem));
            }
            String pretty = Keys.variantLabel(discriminator);
            if (label.indexOf("%s") >= 0) {
                return label.replace("%s", pretty);
            }
            return label + " (" + pretty + ")";
        }
        int meta = Keys.metaOf(key);
        String base = Keys.withoutMeta(key);
        int baseId = base.equals(key) ? keyId : keys.idOf(base);
        String label = baseId >= 0 ? usableName(baseId) : null;
        if (label == null) {
            label = Keys.prettify(Keys.pathOf(base));
        }
        if (meta == 0 || meta == Keys.META_NONE) {
            return label;
        }
        return label + " (" + (meta == Keys.META_WILDCARD ? "*" : Integer.toString(meta)) + ")";
    }

    /**
     * The human label, with a bracketed type prefix for non-item keys.
     *
     * A key that some source NAMED never takes a prefix, even after its unlocalized label
     * was replaced -- that is what `relabel_unlocalized` leaves behind in Python and it is
     * why "was named" is tracked apart from "the name is usable".
     */
    public String display(int keyId) {
        String name = bareName(keyId);
        if (hasName(keyId)) {
            return name;
        }
        return Keys.kindPrefix(kindOf[keyId]) + name;
    }

    /** How many keys carry a recorded label, however usable. */
    public int namedKeyCount() {
        int total = 0;
        for (int id : nameId) {
            if (id >= 0) {
                total++;
            }
        }
        return total;
    }

    public int distinctNameCount() {
        return displayNames.size();
    }

    public int unlocalizedNameCount() {
        return Bits.cardinality(unlocalizedName);
    }

    // -- recipes and adjacency ------------------------------------------------------------

    public RecipeStore recipes() {
        return recipes;
    }

    public Csr byOutput() {
        return byOutput;
    }

    public Csr byInput() {
        return byInput;
    }

    public String categoryName(int categoryId) {
        return categories.get(categoryId);
    }

    public int categoryId(String category) {
        return categories.idOf(category);
    }

    public int categoryCount() {
        return categories.size();
    }

    /**
     * The mod's DISPLAY name for a category, or null. NOT a registry modid.
     *
     * Tolerates the -1 that {@link #categoryId} returns for an unknown category, because the
     * natural call is `categoryMod(categoryId(name))` and an index error there would report
     * a typo as a crash.
     */
    public String categoryMod(int categoryId) {
        if (categoryId < 0 || categoryId >= categoryModId.length) {
            return null;
        }
        int id = categoryModId[categoryId];
        return id < 0 ? null : categoryMods.get(id);
    }

    public String machineName(int machineNameId) {
        return machineNameId < 0 ? null : machineNames.get(machineNameId);
    }

    public String sourceName(int sourceId) {
        return sources.get(sourceId);
    }

    public String roleName(int roleId) {
        return roles.get(roleId);
    }

    public int roleId(String role) {
        return roles.idOf(role);
    }

    /** The id of the same base at the wildcard meta, or -1. */
    public int wildcardSibling(int keyId) {
        return wildcardSibling == null ? -1 : wildcardSibling[keyId];
    }

    /**
     * Appends the recipes producing `keyId`, including via a wildcard-meta output.
     *
     * @return how many were appended
     */
    public int producers(int keyId, IntArray out) {
        int appended = byOutput.appendRow(keyId, out);
        int wild = wildcardSibling(keyId);
        if (wild >= 0) {
            appended += byOutput.appendRow(wild, out);
        }
        return appended;
    }

    /**
     * {@link #producers}, minus container transfers asked to CREATE a fluid.
     *
     * Emptying a container is not production of its contents: to hold a water-filled can you
     * must already have had the water. Left in, it is worse than circular, because the dump
     * drops the NBT saying WHICH fluid a filled can holds -- every filled Forestry can
     * collapses to `forestry:can:1`, so the graph believes squeezing a can of water yields
     * uranium fluoride, and that exact edge once put a bogus uranium chain in a Borax plan.
     *
     * FILLING a container is real work and stays. Only the fluid direction is suppressed, so
     * a transfer may still produce an ITEM, and a fluid whose only route is emptying a
     * container correctly comes out as NEED, which is the honest answer.
     */
    public int realProducers(int keyId, IntArray out) {
        if (kindOf[keyId] != Keys.KIND_FLUID) {
            return producers(keyId, out);
        }
        int appended = appendNonTransfers(byOutput, keyId, out);
        int wild = wildcardSibling(keyId);
        if (wild >= 0) {
            appended += appendNonTransfers(byOutput, wild, out);
        }
        return appended;
    }

    private int appendNonTransfers(Csr index, int keyId, IntArray out) {
        int appended = 0;
        for (int p = index.start(keyId); p < index.end(keyId); p++) {
            int recipe = index.at(p);
            if (!recipes.isTransfer(recipe)) {
                out.add(recipe);
                appended++;
            }
        }
        return appended;
    }

    /**
     * Appends the recipes consuming `keyId`, widened exactly as `Graph.consumers` widens.
     *
     * An item is reachable through a wildcard-meta input AND through any oredict group it
     * belongs to. Both widenings must stay in step with {@link #isLive}: a key hidden from
     * search while these would have found recipes for it is an item that exists, works when
     * linked to, and cannot be found.
     */
    public int consumers(int keyId, IntArray out) {
        int appended = byInput.appendRow(keyId, out);
        int wild = wildcardSibling(keyId);
        if (wild >= 0) {
            appended += byInput.appendRow(wild, out);
        }
        for (int p = oreIndex.start(keyId); p < oreIndex.end(keyId); p++) {
            int oreKey = oreGroupKeyId[oreIndex.at(p)];
            if (oreKey >= 0) {
                appended += byInput.appendRow(oreKey, out);
            }
        }
        return appended;
    }

    // -- oredict -------------------------------------------------------------------------

    public int oreGroupCount() {
        return oreNames.size();
    }

    public String oreGroupName(int groupId) {
        return oreNames.get(groupId);
    }

    public int oreGroupId(String name) {
        return oreNames.idOf(name);
    }

    /** group -&gt; member key ids. */
    public Csr oreMembers() {
        return oreMembers;
    }

    /** key -&gt; the oredict groups it belongs to. */
    public Csr oresOf() {
        return oreIndex;
    }

    /** True for a group whose membership was INFERRED rather than read from the pack. */
    public boolean isOreGuessed(int groupId) {
        return Bits.get(oreGuessed, groupId);
    }

    /**
     * True for a key the pack itself registered under an `ore&#42;` oredict group.
     *
     * The one signal in this graph that separates "you go and mine this" from "the dump
     * listed a decorative block". PACK-DECLARED DATA, not a guess at what a registry name
     * looks like: the mods called these ores and we are only reading it back.
     */
    public boolean isWorldOre(int keyId) {
        return Bits.get(worldOres, keyId);
    }

    public int worldOreCount() {
        return Bits.cardinality(worldOres);
    }

    // -- derived key sets --------------------------------------------------------------

    /**
     * True for a key some recipe or catalyst actually touches.
     *
     * Of the labelled keys, a large majority are DEAD: nothing makes them, nothing uses
     * them, nothing names them as a machine. They cannot be planned or explored and can only
     * push a real result down a search page -- six identical "Pluton Scythe" NBT variants
     * once buried Plutonium-238 through -242 on the first page of a search for `plut`.
     *
     * Stock is not a graph fact, so it is NOT applied here: an item the player holds must
     * never be hidden however dead the graph thinks it is, and that is the caller's join.
     */
    public boolean isLive(int keyId) {
        return Bits.get(liveKeys, keyId);
    }

    public int liveKeyCount() {
        return Bits.cardinality(liveKeys);
    }

    /**
     * True for a key nothing can make except by reshaping another form of itself.
     *
     * Every producer is a `variant` recipe -- one arm of an expanded chisel table -- so the
     * graph knows how to CONVERT this key but not how to obtain any of it. The cost model
     * needs the distinction: expanding a table gives its members producers, and a leaf price
     * goes only to keys nothing produces, so without this a group whose members are all
     * leaves becomes a closed cycle with no base case and every member prices at infinity.
     */
    public boolean isReshapedOnly(int keyId) {
        return Bits.get(reshapedOnly, keyId);
    }

    public int reshapedOnlyCount() {
        return Bits.cardinality(reshapedOnly);
    }

    // -- catalysts, dimensions, multiblocks ------------------------------------------------

    /** category -&gt; the machine item keys JEI lists it as "made in". */
    public Csr catalysts() {
        return catalysts;
    }

    /** The dimension id an ore is exclusive to, or -1. */
    public int dimensionOf(int keyId) {
        int at = indexOfDimensionOre(keyId);
        return at < 0 ? -1 : dimensionOreDimId[at];
    }

    public String dimensionName(int keyId) {
        int at = indexOfDimensionOre(keyId);
        return at < 0 ? null : dimensionNames.get(dimensionOreNameId[at]);
    }

    public int dimensionOreCount() {
        return dimensionOreKey.length;
    }

    /**
     * Binary search rather than a map, because there are 8 of these on the reference pack.
     * A HashMap here would cost more in its own header than the whole table.
     */
    private int indexOfDimensionOre(int keyId) {
        int low = 0;
        int high = dimensionOreKey.length - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int at = dimensionOreKey[mid];
            if (at < keyId) {
                low = mid + 1;
            } else if (at > keyId) {
                high = mid - 1;
            } else {
                return mid;
            }
        }
        return -1;
    }

    public Multiblocks multiblocks() {
        return multiblocks;
    }

    public int dumpSchema() {
        return dumpSchema;
    }

    public String dumpVersion() {
        return dumpVersion;
    }

    public String instanceDir() {
        return instanceDir;
    }

    /**
     * Fluid display names recovered from the containers each fluid is bottled in.
     *
     * DERIVED AT RUNTIME rather than baked into the graph file, matching `Graph.fluid_names`:
     * the inputs are recipes this graph already holds, so baking them would buy nothing and
     * cost a rebuild. Costs 0.10s in Python against a 4.4s load, and rather less here.
     */
    public FluidNames fluidNames() {
        if (fluidNames == null) {
            // Seeded BEFORE the derivation, not after. `FluidNames.derive` calls back into
            // `bareName`, which reads this field: a container base that ever named a fluid
            // key would recurse until the stack died. One assignment makes that structurally
            // impossible -- a re-entrant read sees the empty table and falls through to the
            // prettified registry name.
            fluidNames = FluidNames.EMPTY;
            fluidNames = FluidNames.derive(this);
        }
        return fluidNames;
    }

    // -- accounting -----------------------------------------------------------------------

    /**
     * What each component of this graph retains, in bytes.
     *
     * Computed from the actual array lengths, so it is exact for the structures this package
     * owns rather than an estimate. The heap harness cross-checks the total against a
     * measured post-GC heap delta; a gap between the two means this accounting has missed a
     * field, and the harness is required to report the gap rather than pick a side.
     */
    public GraphSizes sizes() {
        long keyTable = keys.retainedBytes() + Sizes.bytes(kindOf);
        long names = displayNames.retainedBytes() + Sizes.bytes(nameId)
                + Sizes.bytes(unlocalizedName);
        long recipeBytes = recipes.retainedBytes()
                + categories.retainedBytes() + machineNames.retainedBytes()
                + sources.retainedBytes() + roles.retainedBytes();
        long adjacency = byOutput.retainedBytes() + byInput.retainedBytes()
                + Sizes.bytes(wildcardSibling)
                + oreMembers.retainedBytes() + oreIndex.retainedBytes()
                + Sizes.bytes(oreGroupKeyId) + Sizes.bytes(worldOres)
                + Sizes.bytes(liveKeys) + Sizes.bytes(reshapedOnly);
        long other = oreNames.retainedBytes() + Sizes.bytes(oreGuessed)
                + catalysts.retainedBytes() + categoryMods.retainedBytes()
                + Sizes.bytes(categoryModId)
                + Sizes.bytes(dimensionOreKey) + Sizes.bytes(dimensionOreDimId)
                + Sizes.bytes(dimensionOreNameId) + dimensionNames.retainedBytes()
                + multiblocks.retainedBytes()
                // Zero until something asks for a fluid's name. Counted rather than assumed
                // away, because a running GUI derives it on the first fluid it renders and a
                // heap figure taken before that would understate the steady state.
                + (fluidNames == null ? 0L : fluidNames.retainedBytes());
        return new GraphSizes(keyTable, recipeBytes, names, adjacency, other,
                recipes.ridBytes(), keys.indexBytes());
    }
}
