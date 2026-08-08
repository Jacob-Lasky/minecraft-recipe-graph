package io.github.jacoblasky.recipedump.graph;

/**
 * Recipes plus the produced-by index and display names -- the Java form of `model.Graph`.
 *
 * THE SHAPE OF THE PORT, and why it is not a transliteration. `model.py` hangs objects off
 * dicts: a {@code Recipe} per recipe, an {@code Ingredient} per slot, a {@code str} per key
 * occurrence, and {@code dict} indexes over all of it. That is the right design for a
 * process with a machine's whole memory to itself. This one has to live in a Minecraft
 * 1.12.2 client already running 370-odd mods, under a 400 MB gate (#126), so every one of those
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
 *
 * <h2>ITERATION ORDER IS PART OF THE CONTRACT, NOT AN IMPLEMENTATION DETAIL</h2>
 *
 * Every collection this class hands back -- {@link #producers}, {@link #realProducers},
 * {@link #consumers}, {@link #byOutput}, {@link #byInput}, {@link #oreMembers},
 * {@link #oresOf} -- comes back in the order the graph was BUILT, which is the order the
 * dump listed things. That is the same order the python implementation produces, because
 * python dicts are insertion-ordered and its lists obviously are.
 *
 * IT IS LOAD-BEARING TWICE. `solve.py` resolves cost ties with `max`/`min` over these lists,
 * so the winner is whichever candidate iteration reached first -- measured across three
 * PYTHONHASHSEED values, a 90-node budget-exhausted plan is byte-identical, and that rests
 * entirely on `real_producers` returning an ordered list (#129). And `tests/fixtures/plan/`
 * freezes whole solver results so this port can be asserted against them, where a list with
 * the right members in the wrong order is a failing fixture with no behavioural change to
 * point at -- and the obvious reading of that failure, "the fixture is stale, regenerate
 * it", is the wrong one.
 *
 * DO NOT replace a {@link Csr} row with a {@code HashSet} to deduplicate, and DO NOT sort one
 * to make it "canonical". Both give the right multiset and the wrong order, neither fails
 * loudly, and the symptom is a plan choosing a different recipe with nothing pointing back at
 * the change. {@link RecipeGraphOrderTest} pins it.
 *
 * The three derived SETS -- world ores, live keys, reshaped-only -- are `set` in python and
 * therefore genuinely unordered there. They are bitsets here, so iterating one yields key-id
 * order, which is build order and is NOT python's. Nothing may depend on that until both
 * sides agree to impose an order deliberately; membership is all these answer.
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
    /** The inverse of {@link #oreGroupKeyId}: sorted `ore:` key ids, and their group. */
    private final KeyIndex oreKeys;
    private final int[] oreKeyGroup;
    private final long[] oreGuessed;
    private final long[] worldOres;
    private final long[] offworldOres;
    private final long[] liveKeys;
    private final long[] reshapedOnly;

    private final Csr catalysts;
    private final StringTable categoryMods;
    private final int[] categoryModId;

    private final KeyIndex dimensionOres;
    private final int[] dimensionOreDimId;
    private final int[] dimensionOreNameId;
    private final StringTable dimensionNames;

    // -- schema 5 per-item facts, all five of them optional -------------------------------
    //
    // EVERY ONE MEANS "THE FEATURE IS OFF" WHEN ABSENT, never "something is broken": no meta
    // collapse, no blueprint names, no EMC route, no icons. A graph built from an older dump
    // goes on working unchanged, which is why they are read permissively rather than
    // asserted present.

    /** Item STEM -&gt; registry maxDamage, for items whose meta is durability. */
    private final KeyIndex damageable;
    private final int[] maxDamage;
    /**
     * Item -&gt; ProjectE EMC.
     *
     * A `long`, NOT an `int`. Measured on the reference pack the largest EMC value is
     * 422,212,465,065,984, which is four orders of magnitude past what an int holds; a
     * narrower column would wrap it to something small and positive and make the most
     * expensive item in the pack look free. ProjectE's own type is a long.
     */
    private final KeyIndex emcKeys;
    private final long[] emcValue;

    private final Blueprints blueprints;
    private final IconAtlas icons;

    private final Multiblocks multiblocks;
    private final int dumpSchema;
    private final String dumpVersion;
    private final String instanceDir;

    private FluidNames fluidNames;

    /**
     * base key -&gt; the discriminated keys some recipe produces. Built on first use.
     *
     * LAZY, unlike every other index here, and the reason USED to be that planning never
     * needed it: machine identification did, once over ~500 categories, and so did the
     * machines page, so building it eagerly put its bytes on every graph load including the
     * ones that only ever plan, and the heap gate is measured on that path. #170 changed that
     * -- {@link #subsumedBareKey} is built from this index and both the cost model and the
     * solver read it on every plan -- so laziness now buys only the first-use deferral and
     * not the saving it was justified by. Kept lazy anyway: a graph loaded to answer a search
     * or a stock report still never builds it, and one plan builds it once.
     */
    private Csr variantIndex;
    /**
     * variant key -&gt; the bare key a demand for which it may satisfy, or -1. #170.
     *
     * ON THE GRAPH AND NOT PASSED IN, unlike {@link io.github.jacoblasky.recipedump.plan
     * .Unsourced}'s bitset, and the difference is what is being shared. That one refused a
     * field here because the predicate needs a mutable {@code IntArray} to receive producer
     * lists and {@code GraphService} hands one graph to concurrent off-thread solves, so the
     * scratch would be a data race. This is an immutable {@code int[]} written once and only
     * read afterwards, exactly like {@link #variantIndex} above, so two threads racing to
     * build it duplicate work and agree on the answer. DO NOT add mutable state to it.
     */
    private int[] subsumedBy;
    /** item stem -&gt; produced keys at any meta of it. Built on first use, same reason. */
    private Csr metaIndex;
    private KeyIndex metaStems;

    RecipeGraph(StringTable keys, StringTable displayNames, int[] nameId,
                long[] unlocalizedName, byte[] kindOf, StringTable categories,
                StringTable machineNames, StringTable sources, StringTable roles,
                RecipeStore recipes, Csr byOutput, Csr byInput, int[] wildcardSibling,
                StringTable oreNames, Csr oreMembers, Csr oreIndex, int[] oreGroupKeyId,
                KeyIndex oreKeys, int[] oreKeyGroup, long[] oreGuessed, long[] worldOres,
                long[] offworldOres, long[] liveKeys, long[] reshapedOnly,
                Csr catalysts, StringTable categoryMods, int[] categoryModId,
                KeyIndex dimensionOres, int[] dimensionOreDimId, int[] dimensionOreNameId,
                StringTable dimensionNames, KeyIndex damageable, int[] maxDamage,
                KeyIndex emcKeys, long[] emcValue, Blueprints blueprints, IconAtlas icons,
                Multiblocks multiblocks, int dumpSchema,
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
        this.oreKeys = oreKeys;
        this.oreKeyGroup = oreKeyGroup;
        this.oreGuessed = oreGuessed;
        this.worldOres = worldOres;
        this.offworldOres = offworldOres;
        this.liveKeys = liveKeys;
        this.reshapedOnly = reshapedOnly;
        this.catalysts = catalysts;
        this.categoryMods = categoryMods;
        this.categoryModId = categoryModId;
        this.dimensionOres = dimensionOres;
        this.dimensionOreDimId = dimensionOreDimId;
        this.dimensionOreNameId = dimensionOreNameId;
        this.dimensionNames = dimensionNames;
        this.damageable = damageable;
        this.maxDamage = maxDamage;
        this.emcKeys = emcKeys;
        this.emcValue = emcValue;
        this.blueprints = blueprints;
        this.icons = icons;
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

    public boolean isOre(int keyId) {
        return kindOf[keyId] == Keys.KIND_ORE;
    }

    /**
     * The oredict group an `ore:` key names, or -1.
     *
     * The inverse of the group-to-key mapping, and it exists because the cost model's
     * innermost loop needs it. Resolving a group by slicing the key string and hashing the
     * name would allocate a String on a path that runs roughly fifteen million times during
     * one relaxation; a binary search over 3,116 sorted ids does not allocate at all.
     */
    public int oreGroupOfKey(int keyId) {
        int slot = oreKeys.slotOf(keyId);
        return slot < 0 ? -1 : oreKeyGroup[slot];
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
     * The label as `graph.names` HOLDS it, which is not always what is rendered.
     *
     * Exactly what python's `names[key]` returns after `relabel_unlocalized`: the recorded
     * string when it is usable, the readable replacement when it was an unlocalized lang key,
     * and the prettified registry path when nothing named it at all.
     *
     * DIFFERENT FROM {@link #bareName}, and the difference matters wherever a label is
     * MATCHED rather than shown. An aspect-parameterised entry is recorded as the literal
     * "%s Vis Pod" and rendered as "Vis Pod"; a meta sibling is recorded under its base and
     * rendered as "Wool (3)". Machine identification matches a JEI category title against the
     * recorded form, so using the rendered one there silently changes which titles hit.
     */
    public String recordedName(int keyId) {
        String recorded = usableName(keyId);
        return recorded != null ? recorded : bareNameOfKey(keyId);
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
        // BEFORE the recorded label, because a blueprint IS named -- all 259 of them are
        // named "Machine Blueprint", which is genuinely what the game returns and what makes
        // a plan for any multiblock read "1 of 261 possibilities". #55
        String blueprint = blueprintName(keyId);
        if (blueprint != null) {
            return blueprint;
        }
        return bareNameOfKey(keyId);
    }

    /** {@link #bareName} minus the blueprint check, so {@link #blueprintName} can reuse it. */
    private String bareNameOfKey(int keyId) {
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
                label = stemId >= 0 ? bareNameOfKey(stemId) : Keys.prettify(Keys.pathOf(stem));
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
        if (meta == Keys.META_WILDCARD) {
            return label + " (*)";
        }
        // A bare "(187)" beside an item name reads as a variant number and is in fact a
        // durability reading, which is how 46 rows of one Iron Axe looked like 46 items.
        // Saying what the number MEANS costs one lookup and is the smallest honest fix. #118
        int wear = damage(keyId);
        if (wear >= 0) {
            return label + " (" + wear + "/" + maxDamage(keyId) + " damage)";
        }
        return label + " (" + meta + ")";
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
     * Whether anything produces this key, without materialising the list.
     *
     * Same widening as {@link #producers}, including the wildcard fallback, because a caller
     * asking "is there a route" must get the same answer as one asking "which routes".
     */
    public boolean hasProducers(int keyId) {
        if (byOutput.count(keyId) > 0) {
            return true;
        }
        int wild = wildcardSibling(keyId);
        return wild >= 0 && byOutput.count(wild) > 0;
    }

    /**
     * Whether `recipeId` counts as PRODUCING `keyId`, or is only moving it about.
     *
     * THE ONE SPELLING OF THIS EXCLUSION, mirroring `Graph.real_production`. {@link
     * #realProducers} and {@link #realOutput} below read it, and so does {@link
     * Cost#relax}, which carried a hand-rolled copy of it until #193 -- as did python's, and
     * a third spelling in the seed excluded nothing at all, which left 120 fluids priced at
     * infinity while the plan shopping-listed them. DO NOT re-inline it in a caller.
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
    public boolean realProduction(int recipeId, int keyId) {
        return !(recipes.isTransfer(recipeId) && kindOf[keyId] == Keys.KIND_FLUID);
    }

    /**
     * {@link #producers}, minus the recipes {@link #realProduction} says are not making it.
     *
     * THE NON-FLUID SHORT CIRCUIT IS AN OPTIMISATION THAT DEPENDS ON {@link #realProduction}
     * EXCLUDING FLUIDS AND NOTHING ELSE, exactly as in python. Widen the predicate past the
     * fluid case and this branch silently stops honouring it, which is why
     * `RecipeGraphTest.theTwoFormsOfRealProducersAgree` compares the two forms over every
     * recipe rather than trusting the reasoning here.
     */
    public int realProducers(int keyId, IntArray out) {
        if (kindOf[keyId] != Keys.KIND_FLUID) {
            return producers(keyId, out);
        }
        int appended = appendRealProducers(byOutput, keyId, keyId, out);
        int wild = wildcardSibling(keyId);
        if (wild >= 0) {
            // JUDGED ON `keyId`, NOT ON `wild`: the question is whether the recipe makes the
            // key the caller asked for. Passing `wild` would ask about a different key's kind
            // and would silently diverge from python, which filters one flat list.
            appended += appendRealProducers(byOutput, wild, keyId, out);
        }
        return appended;
    }

    /**
     * Whether some recipe's OWN output list names `keyId` and counts as making it.
     *
     * The question `Cost.seed` asks: is there anything for the relaxation to price this
     * key FROM, or is it a leaf at `BASE_RAW_COST`? Before #193 the seed asked
     * `byOutput().count(key) == 0` and so answered "produced" for keys whose only routes are
     * container empties, which nothing then priced.
     *
     * DELIBERATELY NOT {@link #realProducers}, AND NOT WIDENED TO THE WILDCARD SIBLING.
     * `Cost.relax` lowers a cost only for keys a recipe LITERALLY outputs, so a key
     * reachable only through a `mod:item:*` producer has nothing that will ever write it.
     * Measured on the reference graph: 478 input alternatives are in that position, every
     * damaged Electroblob wand and Arcane Essentials sword among them, and answering yes for
     * those would strand all 478 at infinity along with every route through them. This
     * predicate has to stay the exact complement of what the relaxation can reach. Mirrors
     * `Graph.real_output`, whose docstring carries the same measurement.
     */
    public boolean realOutput(int keyId) {
        for (int p = byOutput.start(keyId); p < byOutput.end(keyId); p++) {
            if (realProduction(byOutput.at(p), keyId)) {
                return true;
            }
        }
        return false;
    }

    private int appendRealProducers(Csr index, int rowKeyId, int judgedKeyId, IntArray out) {
        int appended = 0;
        for (int p = index.start(rowKeyId); p < index.end(rowKeyId); p++) {
            int recipe = index.at(p);
            if (realProduction(recipe, judgedKeyId)) {
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

    /**
     * Is this an ore no pack source ever places in the overworld, so that MINING it means
     * walking through a portal? #248. Says nothing about crafted routes, which compete
     * normally: the toll raises a floor, exactly as the gate does.
     *
     * <p>NOT THE SAME QUESTION AS {@link #dimensionOf}, and the two must not be collapsed.
     * That one asks whether the save has ever been to the dimension, and stops being true the
     * moment the player goes; this asks whether the ore generates anywhere in the overworld,
     * and never stops being true. An ore behind a portal you have used a thousand times is
     * still dearer than the identical ore you can walk to, which is the whole of #248.
     */
    public boolean isOffworldOre(int keyId) {
        return Bits.get(offworldOres, keyId);
    }

    public int offworldOreCount() {
        return Bits.cardinality(offworldOres);
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

    /**
     * The dimension id an ore is exclusive to, or -1.
     *
     * <p>DO NOT USE -1 AS THE MEMBERSHIP TEST. It is also the Nether's real dimension id, so
     * a gated Nether ore and an ungated one return the same number. That was harmless while
     * planetDefs was the only source and no gated dimension had a negative id; #248 unioned
     * in JEResources and 21 Nether-only ores are now gated, which makes the collision live.
     * Ask {@link #dimensionName} instead -- it returns null for a non-member and cannot
     * collide with anything. `ScenarioInputs.resolveGates` guards on the name for this
     * reason and must keep doing so.
     */
    public int dimensionOf(int keyId) {
        int slot = dimensionOres.slotOf(keyId);
        return slot < 0 ? -1 : dimensionOreDimId[slot];
    }

    public String dimensionName(int keyId) {
        int slot = dimensionOres.slotOf(keyId);
        return slot < 0 ? null : dimensionNames.get(dimensionOreNameId[slot]);
    }

    public int dimensionOreCount() {
        return dimensionOres.size();
    }

    // -- schema 5 per-item facts -----------------------------------------------------------

    /**
     * The registry maxDamage for a key's item stem, or -1 when the item is not damageable.
     *
     * PACK DATA READ BACK FROM THE ITEM REGISTRY, not a guess from the shape of the key.
     * Every structural rule anyone proposed for "is this meta a durability value" also
     * matched the 286-meta Spell Book and the nine genuinely distinct `chisel:lapis` blocks,
     * and getting those wrong is worse than the noise it removes. See #118.
     */
    public int maxDamage(int keyId) {
        int stem = keys.idOf(Keys.itemStem(keys.get(keyId)));
        int slot = damageable.slotOf(stem);
        return slot < 0 ? -1 : maxDamage[slot];
    }

    /** The meta read as a durability value, or -1 when this key is not a worn tool. */
    public int damage(int keyId) {
        int meta = Keys.metaOf(Keys.baseKey(keys.get(keyId)));
        if (meta <= 0 || maxDamage(keyId) < 0) {
            return -1;
        }
        return meta;
    }

    /**
     * The undamaged key a worn one is a state of; `key` unchanged for everything else.
     *
     * `minecraft:iron_axe:187` -&gt; `minecraft:iron_axe`. `chisel:lapis:3` -&gt; itself,
     * because chisel blocks are not damageable and their meta is a real subtype.
     *
     * NBT SURVIVES: a discriminated key keeps its digest, so a named or enchanted tool is
     * still its own item and only the durability tick collapses.
     *
     * RETURNS A KEY, IT DOES NOT MERGE ANYTHING. Producers, consumers and stock are
     * untouched -- `minecraft:iron_axe` really does hold the 2 in stock and really is the
     * key recipes ask for, which is why #118 is a display defect rather than a wrong plan.
     * The returned key MAY NOT BE INTERNED in this graph, for the same reason: nothing
     * guarantees the pack ever named the undamaged variant, so callers take a String here
     * rather than an id.
     */
    public String damageBase(String key) {
        String base = Keys.baseKey(key);
        String stem = Keys.withoutMeta(base);
        int meta = Keys.metaOf(base);
        if (meta == 0 || meta == Keys.META_NONE
                || damageable.slotOf(keys.idOf(stem)) < 0) {
            return key;
        }
        String discriminator = Keys.discriminator(key);
        return discriminator == null ? stem : stem + "#" + discriminator;
    }

    public int damageableCount() {
        return damageable.size();
    }

    /**
     * This item's ProjectE EMC value, or -1 when the pack assigns it none.
     *
     * -1 RATHER THAN 0, because ProjectE uses 0 for "explicitly worthless" and a caller has
     * to be able to tell that from "not in the table". PACK DATA; what the player has
     * LEARNED is world state and lives in the have file. See #50.
     */
    public long emc(int keyId) {
        int slot = emcKeys.slotOf(keyId);
        return slot < 0 ? -1L : emcValue[slot];
    }

    public int emcCount() {
        return emcKeys.size();
    }

    public Blueprints blueprints() {
        return blueprints;
    }

    public IconAtlas icons() {
        return icons;
    }

    /**
     * "Machine Blueprint (Dragonfire Crucible)", or null when `key` is not a blueprint.
     *
     * THE PARENTHETICAL FORM, not "Dragonfire Crucible Blueprint", and the deciding argument
     * is search: this exact string is what a label index holds, so the parenthetical is
     * findable under BOTH the name the game shows and the name in the player's JEI, while
     * the reworded form is findable under neither of the two words a player looking at a
     * blueprint in their hand would type. See #55.
     */
    public String blueprintName(int keyId) {
        String machine = blueprints.machineNameOf(keyId);
        if (machine == null) {
            return null;
        }
        // Reads the label WITHOUT going back through `bareName`, which calls this first and
        // would recurse forever. Mirrors python reading `self.names` directly.
        String label = usableName(keyId);
        if (label == null && hasName(keyId)) {
            label = bareNameOfKey(keyId);
        }
        return (label == null ? "Machine Blueprint" : label) + " (" + machine + ")";
    }

    // -- NBT and metadata siblings ----------------------------------------------------------
    //
    // Three widenings of "what can make this", each one step looser than the last: exact,
    // then any NBT variant, then any metadata sibling. Machine identification walks all
    // three in that order and the ORDER IS TESTED, because reordering to exact-then-meta
    // keeps a suite green while making `thermalexpansion.pulverizer` name a Redstone Furnace
    // as its route (#28).

    /** The discriminated keys some recipe produces under `baseKeyId`. */
    public int[] variantsOf(int baseKeyId) {
        Csr index = variantIndex();
        // Tolerates the -1 an unknown-key lookup returns, because `variantsOf(keyId(name))`
        // is the natural call and a name the graph never saw is an ordinary answer, not an
        // index error.
        if (baseKeyId < 0 || baseKeyId >= index.rows()) {
            return new int[0];
        }
        return index.row(baseKeyId);
    }

    /**
     * {@link #producers}, widened to every NBT variant of `keyId`.
     *
     * For questions about the ITEM rather than about one NBT state of it. A JEI catalyst
     * names `thermalexpansion:machine:1`, while every crafting recipe for a Pulverizer
     * outputs `thermalexpansion:machine:1#f56885268ad5` because the level and augments live
     * in NBT. Asking the narrow question finds nothing and concludes there is no route to a
     * machine that is plainly craftable -- 16 Thermal Expansion categories and 3 Botania
     * flowers on the reference pack.
     *
     * DELIBERATELY NOT WHAT {@link #producers} DOES. The solver asks "give me exactly this
     * stack", and a Pulverizer with different augments is not a substitute for the one a
     * recipe called for. Widening `producers` itself would let every plan satisfy an
     * NBT-bearing ingredient with the wrong variant.
     */
    public int producersAnyVariant(int keyId, IntArray out) {
        int appended = producers(keyId, out);
        if (Keys.discriminator(keys.get(keyId)) == null) {
            for (int variant : variantsOf(keyId)) {
                appended += producers(variant, out);
            }
        }
        return appended;
    }

    /**
     * The bare key a demand for which this produced VARIANT may satisfy, or -1. #170.
     *
     * ONE SPELLING OF THE RELATION, mirroring `Graph.variant_subsumption` in python, which
     * carries the argument for every clause and the measurements behind them. Read that before
     * changing anything here; the short version is that a recipe slot naming the bare item
     * carries no NBT to match on, so producing `animus:kama_bound#fd1adc426e12` satisfies a
     * demand for `animus:kama_bound` -- and the REVERSE is false, which is why
     * {@link #producers} stays narrow and why the keys of this relation are variants while its
     * values are bare.
     *
     * TWO CALLERS, AND THEY MUST NOT DISAGREE: {@code Cost.relax} lets a variant's production
     * price the bare key, and {@code Solver.expand} routes a bare demand through the variant's
     * recipe. Pricing a route the solver cannot take is #176's defect and pricing one it takes
     * differently is worse, so both read this.
     */
    public int subsumedBareKey(int variantKeyId) {
        int[] index = subsumption();
        return variantKeyId >= 0 && variantKeyId < index.length ? index[variantKeyId] : -1;
    }

    /**
     * Appends the produced variants a demand for bare `keyId` may be satisfied by.
     *
     * Appends rather than replaces, and returns the count, matching {@link #producers} and
     * every other list accessor here. Empty for almost every key.
     *
     * A VIEW OF {@link #subsumedBareKey} rather than a second spelling of its clauses, exactly
     * as `Graph.satisfying_variants` is in python. Ordered as {@link #variantsOf} is, which is
     * recipe-output order and therefore the order the dump saw, because plan fixtures freeze
     * whole solver results and a variant chosen by index order must not move between runs.
     *
     * @return how many were appended
     */
    public int satisfyingVariants(int keyId, IntArray out) {
        int appended = 0;
        for (int variant : variantsOf(keyId)) {
            if (subsumedBareKey(variant) == keyId) {
                out.add(variant);
                appended++;
            }
        }
        return appended;
    }

    private int[] subsumption() {
        if (subsumedBy == null) {
            int[] out = new int[keys.size()];
            java.util.Arrays.fill(out, -1);
            IntArray scratch = new IntArray();
            long[] family = Bits.ofSize(keys.size());
            Csr index = variantIndex();
            for (int bare = 0; bare < index.rows(); bare++) {
                // `count` before `row`, because `row` allocates and there is one row per
                // interned key: 2,631 of the reference graph's ~300,000 rows are non-empty,
                // so materialising them all would be 300,000 throwaway arrays to look at
                // 2,631 of them.
                if (index.count(bare) == 0) {
                    continue;
                }
                int[] variants = index.row(bare);
                scratch.clear();
                // CLAUSE 2: the bare key has no route of its own. This is what excludes the
                // control class, which is twenty times the size of the defect.
                if (realProducers(bare, scratch) > 0) {
                    continue;
                }
                // CLAUSES 3 AND 4, the second of which spans the whole family: a variant made
                // from the bare key or from a sibling variant is a container fill or an
                // upgrade, and pricing the bare key through it prices "get one" at what "get
                // one and change it" costs.
                Bits.set(family, bare);
                for (int variant : variants) {
                    Bits.set(family, variant);
                }
                for (int variant : variants) {
                    scratch.clear();
                    if (realProducers(variant, scratch) == 0) {
                        continue;
                    }
                    if (!consumesAnyOf(scratch, family)) {
                        out[variant] = bare;
                    }
                }
                // Cleared rather than reallocated per bare key: 2,631 of these on the
                // reference graph and the bitset is one bit per key in the whole graph.
                Bits.clear(family, bare);
                for (int variant : variants) {
                    Bits.clear(family, variant);
                }
            }
            subsumedBy = out;
        }
        return subsumedBy;
    }

    private boolean consumesAnyOf(IntArray recipeIds, long[] family) {
        for (int i = 0; i < recipeIds.size(); i++) {
            int recipe = recipeIds.get(i);
            for (int slot = recipes.slotStart(recipe); slot < recipes.slotEnd(recipe); slot++) {
                for (int p = recipes.altStart(slot); p < recipes.altEnd(slot); p++) {
                    if (Bits.get(family, recipes.altKeyAt(p))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * A produced key that is the same registered item at another meta, or -1.
     *
     * One step wider than {@link #producersAnyVariant} and the last one there is: NBT, then
     * meta, then nothing.
     *
     * DO NOT call this from the solver or the cost model. It answers only "does this BLOCK
     * exist in the pack at all", which is the machines page's question, and it is wrong for
     * every other one: a recipe asking for `tconstruct:ingots:3` will not accept `:0`.
     *
     * ONLY FOR AN UNNAMED META, and that gate is the whole safety argument. An unnamed meta
     * is the dump reporting a stack STATE nobody registered as an item; a meta the pack DID
     * name is its own item, and saying it is craftable "as" a sibling is a falsehood.
     * Measured without the gate: it fired on four categories and two were false --
     * `bloodmagic:ritual_diviner:2` ("[Dawn]") reported craftable via `:1` ("[Dusk]").
     */
    public int metaSiblingMade(int keyId) {
        String stem = Keys.itemStem(keys.get(keyId));
        if (stem == null || hasName(keyId)) {
            return -1;
        }
        int[] siblings = siblingsMade(stem, keyId);
        return siblings.length == 0 ? -1 : siblings[0];
    }

    /**
     * Produced keys under `stem`, plain base first, then ascending meta, NBT last.
     *
     * ORDERED, because {@link #metaSiblingMade} returns the head and adjacency order is dump
     * order: unsorted, a re-dump could silently change which sibling gets named. Plain base
     * first because it is the least surprising thing to call an item "as", and
     * NBT-discriminated keys last because a meta sibling should never be reported through an
     * NBT state when a plain one exists.
     */
    public int[] siblingsMade(String stem, int exclude) {
        Csr index = metaIndex();
        int slot = metaStems.slotOf(keys.idOf(stem));
        if (slot < 0) {
            return new int[0];
        }
        IntArray kept = new IntArray();
        for (int p = index.start(slot); p < index.end(slot); p++) {
            int key = index.at(p);
            if (key != exclude && hasProducers(key)) {
                kept.add(key);
            }
        }
        int[] out = kept.trimmed();
        sortSiblings(out);
        return out;
    }

    /**
     * Insertion sort on (has discriminator, meta, key) -- the python `rank` tuple.
     *
     * A wildcard meta ranks at 1 &lt;&lt; 20, above every real meta, exactly as python's
     * `meta if isinstance(meta, int) else 1 << 20` does. Insertion sort because these lists
     * are a handful of entries and a comparator would mean boxing every key.
     */
    private void sortSiblings(int[] keyIds) {
        for (int i = 1; i < keyIds.length; i++) {
            int key = keyIds[i];
            int j = i - 1;
            while (j >= 0 && compareSibling(keyIds[j], key) > 0) {
                keyIds[j + 1] = keyIds[j];
                j--;
            }
            keyIds[j + 1] = key;
        }
    }

    private int compareSibling(int leftId, int rightId) {
        String left = keys.get(leftId);
        String right = keys.get(rightId);
        int byDiscriminator = Boolean.compare(Keys.discriminator(left) != null,
                Keys.discriminator(right) != null);
        if (byDiscriminator != 0) {
            return byDiscriminator;
        }
        int byMeta = Integer.compare(siblingMeta(left), siblingMeta(right));
        return byMeta != 0 ? byMeta : left.compareTo(right);
    }

    private static int siblingMeta(String key) {
        int meta = Keys.metaOf(Keys.baseKey(key));
        return meta == Keys.META_WILDCARD || meta == Keys.META_NONE ? 1 << 20 : meta;
    }

    /**
     * base -&gt; discriminated produced keys, IN THE ORDER RECIPES FIRST OUTPUT THEM.
     *
     * NOT ascending key id, and the difference is observable. Python builds this by walking
     * `by_output`, whose insertion order is the order each key was first seen as some
     * recipe's output; interning order is the order keys were first seen ANYWHERE, including
     * as an ingredient or in the names section. Measured against the oracle on the real
     * graph, exactly three categories disagreed -- `botania.orechid`, `orechid_ignem` and
     * `pureDaisy` all named `botania:specialflower#025babb4d6cc` as their craftable variant
     * where python names `#cf734f9c96f6`. Same state, same build targets, same prices; only
     * the evidence sentence differed, which is the quietest possible way for two
     * implementations to drift.
     */
    private Csr variantIndex() {
        if (variantIndex == null) {
            Csr.Builder builder = new Csr.Builder(keys.size());
            long[] seen = Bits.ofSize(keys.size());
            for (int recipe = 0; recipe < recipes.count(); recipe++) {
                for (int p = recipes.outputStart(recipe); p < recipes.outputEnd(recipe); p++) {
                    int key = recipes.outputKeyAt(p);
                    if (Bits.get(seen, key)) {
                        continue;
                    }
                    Bits.set(seen, key);
                    int base = discriminatedBaseOf(key);
                    if (base >= 0) {
                        builder.count(base);
                    }
                }
            }
            builder.prepare();
            long[] placed = Bits.ofSize(keys.size());
            for (int recipe = 0; recipe < recipes.count(); recipe++) {
                for (int p = recipes.outputStart(recipe); p < recipes.outputEnd(recipe); p++) {
                    int key = recipes.outputKeyAt(p);
                    if (Bits.get(placed, key)) {
                        continue;
                    }
                    Bits.set(placed, key);
                    int base = discriminatedBaseOf(key);
                    if (base >= 0) {
                        builder.place(base, key);
                    }
                }
            }
            variantIndex = builder.build();
        }
        return variantIndex;
    }

    /** The interned id of a discriminated key's base, or -1 when it has none or is unknown. */
    private int discriminatedBaseOf(int keyId) {
        String key = keys.get(keyId);
        int at = Keys.discriminatorAt(key);
        return at < 0 ? -1 : keys.idOf(key.substring(0, at));
    }

    private Csr metaIndex() {
        if (metaIndex == null) {
            KeyIndex.Builder stems = new KeyIndex.Builder();
            IntArray stemOf = new IntArray();
            IntArray produced = new IntArray();
            // Walked in the same by-output insertion order as `variantIndex`, for the same
            // reason. `siblingsMade` sorts what it reads and its sort key ends with the key
            // string, so no tie survives to depend on this -- but two indexes over one
            // relation ordered by two different rules is a difference waiting to matter.
            long[] seenStem = Bits.ofSize(keys.size());
            IntArray producedKeys = new IntArray();
            for (int recipe = 0; recipe < recipes.count(); recipe++) {
                for (int p = recipes.outputStart(recipe); p < recipes.outputEnd(recipe); p++) {
                    int key = recipes.outputKeyAt(p);
                    if (!Bits.get(seenStem, key)) {
                        Bits.set(seenStem, key);
                        producedKeys.add(key);
                    }
                }
            }
            for (int i = 0; i < producedKeys.size(); i++) {
                int key = producedKeys.get(i);
                String stem = Keys.itemStem(keys.get(key));
                // Non-items are skipped rather than piling up under a null stem. The bucket
                // was inert in python, but it filed 1,198 fluid and oredict keys under a key
                // this index documents as holding registry names, waiting for a second caller.
                if (stem == null) {
                    continue;
                }
                int stemId = keys.idOf(stem);
                if (stemId < 0) {
                    continue;
                }
                stemOf.add(stemId);
                produced.add(key);
            }
            // One slot per DISTINCT stem, so the CSR rows are dense over the stems that
            // exist rather than over every key.
            IntArray distinct = new IntArray();
            long[] seen = Bits.ofSize(keys.size());
            for (int i = 0; i < stemOf.size(); i++) {
                if (!Bits.get(seen, stemOf.get(i))) {
                    Bits.set(seen, stemOf.get(i));
                    distinct.add(stemOf.get(i));
                }
            }
            int[] sortedStems = distinct.trimmed();
            java.util.Arrays.sort(sortedStems);
            for (int stem : sortedStems) {
                stems.add(stem);
            }
            KeyIndex index = stems.build(stems.permutation());
            Csr.Builder builder = new Csr.Builder(index.size());
            for (int i = 0; i < stemOf.size(); i++) {
                builder.count(index.slotOf(stemOf.get(i)));
            }
            builder.prepare();
            for (int i = 0; i < stemOf.size(); i++) {
                builder.place(index.slotOf(stemOf.get(i)), produced.get(i));
            }
            metaStems = index;
            metaIndex = builder.build();
        }
        return metaIndex;
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
                + Sizes.bytes(oreGroupKeyId) + oreKeys.retainedBytes()
                + Sizes.bytes(oreKeyGroup) + Sizes.bytes(worldOres) + Sizes.bytes(offworldOres)
                + Sizes.bytes(liveKeys) + Sizes.bytes(reshapedOnly);
        long itemFacts = damageable.retainedBytes() + Sizes.bytes(maxDamage)
                + emcKeys.retainedBytes() + Sizes.bytes(emcValue)
                + blueprints.retainedBytes() + icons.retainedBytes();
        long other = oreNames.retainedBytes() + Sizes.bytes(oreGuessed)
                + catalysts.retainedBytes() + categoryMods.retainedBytes()
                + Sizes.bytes(categoryModId)
                + dimensionOres.retainedBytes() + Sizes.bytes(dimensionOreDimId)
                + Sizes.bytes(dimensionOreNameId) + dimensionNames.retainedBytes()
                + multiblocks.retainedBytes()
                // Zero until something asks for a fluid's name. Counted rather than assumed
                // away, because a running GUI derives it on the first fluid it renders and a
                // heap figure taken before that would understate the steady state.
                + (fluidNames == null ? 0L : fluidNames.retainedBytes())
                // Same rule for the three lazy sibling indexes: zero until something asks,
                // counted honestly once it has. A report that showed them as free would
                // understate any process that resolves machines -- and since #170, any
                // process that PLANS, because `subsumedBy` is built on the first plan and
                // pulls `variantIndex` up with it.
                + (variantIndex == null ? 0L : variantIndex.retainedBytes())
                + Sizes.bytes(subsumedBy)
                + (metaIndex == null ? 0L
                        : metaIndex.retainedBytes() + metaStems.retainedBytes());
        return new GraphSizes(keyTable, recipeBytes, names, adjacency, itemFacts, other,
                recipes.ridBytes(), keys.indexBytes());
    }
}
