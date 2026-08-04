package io.github.jacoblasky.recipedump.graph;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Assembles a {@link RecipeGraph}, one streamed recipe at a time.
 *
 * WHY A BUILDER AND NOT A MUTABLE GRAPH. `model.Graph` is mutable and drops five derived
 * indexes whenever a recipe is added. Nothing on the mod's read path needs that, and giving
 * it up is what lets every finished index be an exactly-sized primitive array rather than a
 * growable one carrying slack -- which on a graph this size is tens of megabytes of
 * difference. Everything growable lives here and dies with the builder.
 *
 * DERIVED INDEXES ARE BUILT EAGERLY IN {@link #build}, not lazily on first use. In Python
 * they are lazy because a `Graph` is often loaded to answer one question; in the mod the
 * graph is loaded once and then serves a GUI, so a lazy index only moves the cost to the
 * first keystroke and adds a null check to every hot path. The cost is bounded and measured:
 * see the heap harness, which reports build time separately from parse time.
 *
 * The builder streams: it never holds a parsed recipe object, and it never holds the whole
 * file. That is what keeps the parse peak near the retained size instead of several times
 * it.
 */
public final class GraphBuilder {

    private final StringTable.Builder keys;
    private final StringTable.Builder displayNames;
    private final StringTable.Builder categories =
            StringTable.builder(1024, 32768, true, true);
    private final StringTable.Builder machineNames =
            StringTable.builder(1024, 32768, true, true);
    private final StringTable.Builder sources = StringTable.builder(16, 256, true, true);
    private final StringTable.Builder roles = StringTable.builder(8, 64, true, true);
    private final StringTable.Builder categoryMods =
            StringTable.builder(256, 8192, true, true);
    private final StringTable.Builder oreNames =
            StringTable.builder(4096, 131072, true, true);
    private final StringTable.Builder dimensionNames =
            StringTable.builder(64, 2048, true, true);

    private final IntArray kinds = new IntArray();
    private final IntArray nameId = new IntArray();
    /**
     * Per key: 1 when its CURRENT label is an unlocalized lang key, 0 otherwise.
     *
     * A flag rather than a list of ids, because a key can be named twice -- two sources both
     * claiming a label -- and a list can only ever add. Naming a key `tile.null.name` and
     * then properly would leave the junk flag set forever, and the item would render its
     * registry path while carrying a perfectly good name.
     */
    private final IntArray unlocalized = new IntArray();
    private final RecipeStore.Builder recipes;

    private final IntArray oreMemberOffsets = new IntArray();
    private final IntArray oreMemberKey = new IntArray();
    /**
     * Guessed group NAMES, resolved to ids only in {@link #build}.
     *
     * DO NOT resolve these on arrival. `graph.json` is written with sorted keys, so
     * `ore_guessed` is read BEFORE `ore_members`, and interning a name early would mint an
     * empty group that the real one then collides with. Deferring costs a set of a few
     * strings and makes the builder independent of section order, which is the property that
     * matters when the dump format is free to change shape.
     */
    private final Set<String> oreGuessedNames = new HashSet<String>();
    private boolean oreGroupOpen;

    private final IntArray catalystCategory = new IntArray();
    private final IntArray catalystKey = new IntArray();
    private int openCatalystCategory = -1;

    private final IntArray categoryModCategory = new IntArray();
    private final IntArray categoryModName = new IntArray();

    private final KeyIndex.Builder dimensionOreKey = new KeyIndex.Builder();
    private final IntArray dimensionOreDimId = new IntArray();
    private final IntArray dimensionOreNameId = new IntArray();

    private final KeyIndex.Builder damageableKey = new KeyIndex.Builder();
    private final IntArray maxDamage = new IntArray();
    private final KeyIndex.Builder emcKey = new KeyIndex.Builder();
    private final LongArray emcValue = new LongArray();
    private final Blueprints.Builder blueprints = new Blueprints.Builder();
    private final IconAtlas.Builder icons = new IconAtlas.Builder();

    private final Multiblocks.Builder multiblocks = new Multiblocks.Builder();

    /**
     * Bases that some key wildcards, as strings.
     *
     * Collected while interning rather than rediscovered later, because finding them
     * afterwards means decoding every key to test its meta, and the set is tiny -- a few
     * hundred against 300,000 keys. It is what lets the wildcard-sibling scan skip the big
     * table lookup for the overwhelming majority of keys.
     */
    private final Set<String> wildcardBases = new HashSet<String>();

    private int dumpSchema;
    private String dumpVersion;
    private String instanceDir;

    public GraphBuilder() {
        this(1 << 16, 1 << 20, 1 << 12, 1 << 18, 1 << 12, 1 << 16);
    }

    /**
     * Sizing hints only; every table grows if a hint is wrong.
     *
     * Worth passing honestly for a real graph: a table given a good hint never rehashes, and
     * a rehash re-walks the whole blob. The JSON reader derives its hints from the file size.
     */
    public GraphBuilder(int expectedKeys, int expectedKeyBytes, int expectedRecipes,
                        int expectedRidBytes, int expectedNames, int expectedNameBytes) {
        keys = StringTable.builder(expectedKeys, expectedKeyBytes, true, true);
        displayNames = StringTable.builder(expectedNames, expectedNameBytes, true, false);
        recipes = new RecipeStore.Builder(expectedRecipes, expectedRidBytes);
        oreMemberOffsets.add(0);
    }

    // -- keys and names -------------------------------------------------------------------

    /**
     * Interns a key and returns its id. Idempotent.
     *
     * The kind and the wildcard-base collection happen HERE, on first sight, because this is
     * the one moment the key is already a String. Recovering either afterwards means
     * decoding all 300,000 keys again.
     */
    public int key(String key) {
        int id = keys.add(key);
        if (id == kinds.size()) {
            kinds.add(Keys.kindId(key));
            if (Keys.metaOf(key) == Keys.META_WILDCARD) {
                wildcardBases.add(Keys.withoutMeta(key));
            }
        }
        return id;
    }

    public void name(String key, String label) {
        name(key(key), label);
    }

    /**
     * Records a display label for a key.
     *
     * A label that is only an unlocalized lang key is STORED AND FLAGGED rather than dropped.
     * Dropping is the tidier-looking fix and it is wrong: the key would fall out of the
     * search index entirely, which is worse than an ugly name. Flagging lets
     * {@link RecipeGraph#bareName} substitute a readable fallback while
     * {@link RecipeGraph#display} still knows the key was named.
     */
    public void name(int keyId, String label) {
        nameId.ensureSize(keyId + 1, -1);
        unlocalized.ensureSize(keyId + 1, 0);
        nameId.set(keyId, displayNames.add(label));
        unlocalized.set(keyId, Keys.isUnlocalized(label) ? 1 : 0);
    }

    // -- recipes ----------------------------------------------------------------------------

    public void beginRecipe() {
        recipes.beginRecipe();
    }

    public void output(int keyId, int qty) {
        recipes.addOutput(keyId, qty);
    }

    public void beginSlot(int qty, String role) {
        beginSlot(qty, role, 1.0f);
    }

    /**
     * Opens a slot, saying how likely a run is to CONSUME it (#175).
     *
     * The three-argument form exists because `graph.json` carries a `p` per slot and the
     * two-argument form must keep meaning "fully consumed", which is what every graph written
     * before that field means. See `model.Ingredient.consume_chance` for the Python half.
     */
    public void beginSlot(int qty, String role, float consumeChance) {
        recipes.beginSlot(qty, roles.add(role == null ? "item" : role), consumeChance);
    }

    public void alternative(int keyId) {
        recipes.addAlternative(keyId);
    }

    public void endSlot() {
        recipes.endSlot();
    }

    public void endRecipe(String rid, String category, String machine, String source,
                          boolean transfer, boolean variant) {
        int flags = (transfer ? RecipeStore.FLAG_TRANSFER : 0)
                | (variant ? RecipeStore.FLAG_VARIANT : 0);
        recipes.endRecipe(rid, categories.add(category == null ? "crafting" : category),
                machine == null ? -1 : machineNames.add(machine),
                sources.add(source == null ? "" : source), flags);
    }

    public int recipeCount() {
        return recipes.count();
    }

    // -- oredict -------------------------------------------------------------------------

    public void beginOreGroup(String name) {
        if (oreGroupOpen) {
            throw new IllegalStateException("beginOreGroup() without endOreGroup()");
        }
        int id = oreNames.add(name);
        if (id != oreMemberOffsets.size() - 1) {
            throw new IllegalStateException("oredict group added twice: " + name);
        }
        oreGroupOpen = true;
    }

    public void oreMember(int keyId) {
        oreMemberKey.add(keyId);
    }

    public void endOreGroup() {
        oreMemberOffsets.add(oreMemberKey.size());
        oreGroupOpen = false;
    }

    /** Marks a group whose membership was INFERRED from names rather than read from the pack. */
    public void markOreGuessed(String name) {
        oreGuessedNames.add(name);
    }

    // -- catalysts, category mods, dimensions ------------------------------------------------

    public void beginCatalyst(String category) {
        openCatalystCategory = categories.add(category);
    }

    public void catalystKey(int keyId) {
        if (openCatalystCategory < 0) {
            throw new IllegalStateException("no catalyst category is open");
        }
        catalystCategory.add(openCatalystCategory);
        catalystKey.add(keyId);
    }

    public void endCatalyst() {
        openCatalystCategory = -1;
    }

    public void categoryMod(String category, String modDisplayName) {
        categoryModCategory.add(categories.add(category));
        categoryModName.add(categoryMods.add(modDisplayName));
    }

    public void dimensionOre(int keyId, int dimensionId, String dimensionName) {
        dimensionOreKey.add(keyId);
        dimensionOreDimId.add(dimensionId);
        dimensionOreNameId.add(dimensionNames.add(dimensionName));
    }

    // -- schema 5 per-item facts ------------------------------------------------------------

    /** `stemKeyId` is the UNDAMAGED item key, which is what the registry reports against. */
    public void damageable(int stemKeyId, int registryMaxDamage) {
        damageableKey.add(stemKeyId);
        maxDamage.add(registryMaxDamage);
    }

    /** A `long` because ProjectE EMC exceeds an int by four orders of magnitude. */
    public void emc(int keyId, long value) {
        emcKey.add(keyId);
        emcValue.add(value);
    }

    public Blueprints.Builder blueprints() {
        return blueprints;
    }

    public IconAtlas.Builder icons() {
        return icons;
    }

    public Multiblocks.Builder multiblocks() {
        return multiblocks;
    }

    public void dumpSchema(int schema) {
        this.dumpSchema = schema;
    }

    public void dumpVersion(String version) {
        this.dumpVersion = version;
    }

    public void instanceDir(String dir) {
        this.instanceDir = dir;
    }

    // -- assembly ----------------------------------------------------------------------------

    public RecipeGraph build() {
        StringTable keyTable = keys.build();
        int keyCount = keyTable.size();
        RecipeStore store = recipes.build();

        byte[] kindOf = kinds.toBytes();

        nameId.ensureSize(keyCount, -1);
        int[] names = nameId.trimmed();
        long[] unlocalizedBits = Bits.ofSize(keyCount);
        for (int key = 0; key < unlocalized.size(); key++) {
            if (unlocalized.get(key) != 0) {
                Bits.set(unlocalizedBits, key);
            }
        }

        int[] wildcardSibling = buildWildcardSiblings(keyTable, keyCount);
        Csr byOutput = buildByOutput(store, keyCount);
        Csr byInput = buildByInput(store, keyCount);

        IntArray oreGuessedGroups = new IntArray();
        for (String guessed : oreGuessedNames) {
            int group = oreNames.add(guessed);
            if (group == oreMemberOffsets.size() - 1) {
                // A guessed name the oredict section never listed. Give it an empty group
                // rather than leaving the group table and the member index out of step by
                // one, which would silently shift every later group's membership.
                oreMemberOffsets.add(oreMemberKey.size());
            }
            oreGuessedGroups.add(group);
        }
        StringTable oreTable = oreNames.build();
        Csr oreMembers = new Csr(oreMemberOffsets.trimmed(), oreMemberKey.trimmed());
        Csr oreIndex = invert(oreMembers, keyCount);
        int[] oreGroupKeyId = new int[oreTable.size()];
        for (int group = 0; group < oreTable.size(); group++) {
            oreGroupKeyId[group] = keyTable.idOf(Keys.oreKey(oreTable.get(group)));
        }
        // The inverse index the cost model's inner loop needs, built here because this is
        // where both halves are already in hand. Only the groups whose `ore:` key was
        // actually interned can appear -- a group no recipe consumes has no key to invert.
        KeyIndex.Builder oreKeyBuilder = new KeyIndex.Builder();
        IntArray oreKeyGroup = new IntArray();
        for (int group = 0; group < oreTable.size(); group++) {
            if (oreGroupKeyId[group] >= 0) {
                oreKeyBuilder.add(oreGroupKeyId[group]);
                oreKeyGroup.add(group);
            }
        }
        int[] oreKeyOrder = oreKeyBuilder.permutation();

        long[] oreGuessed = Bits.ofSize(Math.max(1, oreTable.size()));
        for (int i = 0; i < oreGuessedGroups.size(); i++) {
            Bits.set(oreGuessed, oreGuessedGroups.get(i));
        }
        long[] worldOres = buildWorldOres(oreTable, oreMembers, keyCount);

        StringTable categoryTable = categories.build();
        Csr catalysts = pairsToCsr(catalystCategory, catalystKey, categoryTable.size());
        int[] categoryModId = new int[categoryTable.size()];
        Arrays.fill(categoryModId, -1);
        for (int i = 0; i < categoryModCategory.size(); i++) {
            categoryModId[categoryModCategory.get(i)] = categoryModName.get(i);
        }

        long[] liveKeys = buildLiveKeys(byOutput, byInput, oreMembers, oreGroupKeyId,
                catalysts, wildcardSibling, names, keyCount);
        long[] reshapedOnly = buildReshapedOnly(store, byOutput, keyCount);

        int[] dimensionOrder = dimensionOreKey.permutation();
        int[] damageOrder = damageableKey.permutation();
        int[] emcOrder = emcKey.permutation();

        return new RecipeGraph(keyTable, displayNames.build(), names, unlocalizedBits, kindOf,
                categoryTable, machineNames.build(), sources.build(), roles.build(), store,
                byOutput, byInput, wildcardSibling, oreTable, oreMembers, oreIndex,
                oreGroupKeyId, oreKeyBuilder.build(oreKeyOrder),
                permute(oreKeyGroup, oreKeyOrder), oreGuessed, worldOres, liveKeys,
                reshapedOnly, catalysts,
                categoryMods.build(), categoryModId,
                dimensionOreKey.build(dimensionOrder),
                permute(dimensionOreDimId, dimensionOrder),
                permute(dimensionOreNameId, dimensionOrder),
                dimensionNames.build(),
                damageableKey.build(damageOrder), permute(maxDamage, damageOrder),
                emcKey.build(emcOrder), permute(emcValue, emcOrder),
                blueprints.build(), icons.build(),
                multiblocks.build(), dumpSchema, dumpVersion, instanceDir);
    }

    /**
     * Reorders a value column to match the permutation its {@link KeyIndex} was sorted by.
     *
     * The keys and every parallel value column must move TOGETHER. Sorting the keys and
     * leaving a column behind pairs each key with someone else's value, which is a graph
     * that loads and answers confidently wrong -- the exact failure the CSR guards exist to
     * prevent, in a different place.
     */
    private static int[] permute(IntArray values, int[] order) {
        int[] out = new int[order.length];
        for (int slot = 0; slot < order.length; slot++) {
            out[slot] = values.get(order[slot]);
        }
        return out;
    }

    private static long[] permute(LongArray values, int[] order) {
        long[] out = new long[order.length];
        for (int slot = 0; slot < order.length; slot++) {
            out[slot] = values.get(order[slot]);
        }
        return out;
    }

    /**
     * key -&gt; the id of `&lt;its base&gt;:&#42;`, or -1.
     *
     * Returns null outright when no key wildcards anything, which saves a 4-byte-per-key
     * array on the many graphs that have none -- every hand-built fixture, for one.
     *
     * DOES NOT STRIP A DISCRIMINATOR before reading the meta, because `model.split_key` does
     * not either: a discriminated key's last colon-separated part is `1#48a337d94489`, which
     * is not a digit run, so it reads as meta 0 and looks up `mod:name:1#...:&#42;`, which
     * exists nowhere. That is the intended behaviour -- an NBT variant must NOT inherit its
     * base's wildcard recipes, since a Pulverizer with different augments is not a substitute
     * for the one a recipe called for.
     */
    private int[] buildWildcardSiblings(StringTable keyTable, int keyCount) {
        if (wildcardBases.isEmpty()) {
            return null;
        }
        int[] siblings = new int[keyCount];
        Arrays.fill(siblings, -1);
        for (int id = 0; id < keyCount; id++) {
            if (kinds.get(id) != Keys.KIND_ITEM) {
                continue;
            }
            String key = keyTable.get(id);
            int meta = Keys.metaOf(key);
            if (meta == Keys.META_WILDCARD) {
                continue;
            }
            String base = Keys.withoutMeta(key);
            if (wildcardBases.contains(base)) {
                siblings[id] = keyTable.idOf(base + ":*");
            }
        }
        return siblings;
    }

    /**
     * key -&gt; the recipes producing it.
     *
     * A recipe appears ONCE PER OUTPUT ENTRY, duplicates included, matching `Graph.by_output`.
     * A recipe listing the same key in two output stacks really does say so twice, and
     * collapsing that here would quietly change what a producer count means.
     */
    private static Csr buildByOutput(RecipeStore store, int keyCount) {
        Csr.Builder builder = new Csr.Builder(keyCount);
        for (int r = 0; r < store.count(); r++) {
            for (int p = store.outputStart(r); p < store.outputEnd(r); p++) {
                builder.count(store.outputKeyAt(p));
            }
        }
        builder.prepare();
        for (int r = 0; r < store.count(); r++) {
            for (int p = store.outputStart(r); p < store.outputEnd(r); p++) {
                builder.place(store.outputKeyAt(p), r);
            }
        }
        return builder.build();
    }

    /**
     * key -&gt; the recipes consuming it, each recipe listed at most once per key.
     *
     * The deduplication matters and mirrors `Graph.by_input`: a shaped recipe has one slot
     * per grid cell, so a 3x3 of one ingredient names that key nine times, and without this
     * the "used in" count for cobblestone would be nine times the truth.
     *
     * The `stamp` array is build scratch, not retained -- 4 bytes per key for the duration of
     * two passes, against a `HashSet` per recipe.
     */
    private static Csr buildByInput(RecipeStore store, int keyCount) {
        Csr.Builder builder = new Csr.Builder(keyCount);
        int[] stamp = new int[keyCount];
        Arrays.fill(stamp, -1);
        for (int r = 0; r < store.count(); r++) {
            for (int s = store.slotStart(r); s < store.slotEnd(r); s++) {
                for (int a = store.altStart(s); a < store.altEnd(s); a++) {
                    int key = store.altKeyAt(a);
                    if (stamp[key] != r) {
                        stamp[key] = r;
                        builder.count(key);
                    }
                }
            }
        }
        builder.prepare();
        Arrays.fill(stamp, -1);
        for (int r = 0; r < store.count(); r++) {
            for (int s = store.slotStart(r); s < store.slotEnd(r); s++) {
                for (int a = store.altStart(s); a < store.altEnd(s); a++) {
                    int key = store.altKeyAt(a);
                    if (stamp[key] != r) {
                        stamp[key] = r;
                        builder.place(key, r);
                    }
                }
            }
        }
        return builder.build();
    }

    /** Transposes a CSR: group -&gt; members becomes member -&gt; groups. */
    private static Csr invert(Csr forward, int keyCount) {
        Csr.Builder builder = new Csr.Builder(keyCount);
        for (int row = 0; row < forward.rows(); row++) {
            for (int p = forward.start(row); p < forward.end(row); p++) {
                builder.count(forward.at(p));
            }
        }
        builder.prepare();
        for (int row = 0; row < forward.rows(); row++) {
            for (int p = forward.start(row); p < forward.end(row); p++) {
                builder.place(forward.at(p), row);
            }
        }
        return builder.build();
    }

    private static long[] buildWorldOres(StringTable oreTable, Csr oreMembers, int keyCount) {
        long[] bits = Bits.ofSize(keyCount);
        for (int group = 0; group < oreTable.size(); group++) {
            if (!Keys.isWorldOreGroup(oreTable.get(group))) {
                continue;
            }
            for (int p = oreMembers.start(group); p < oreMembers.end(group); p++) {
                Bits.set(bits, oreMembers.at(p));
            }
        }
        return bits;
    }

    /**
     * Keys some recipe or catalyst actually touches, with all three widenings.
     *
     * MUST STAY IN STEP WITH `producers` AND `consumers`. A key hidden here while those two
     * would have found recipes for it is an item that exists, works when linked to, and
     * cannot be found -- so the widenings are not optional:
     *
     * <ul>
     * <li>oredict: `consumers` reaches an item through any group it belongs to, so a member
     *     of a group some recipe consumes is reachable even if nothing names it;</li>
     * <li>catalysts: some keys are named ONLY as a JEI catalyst, because their recipes output
     *     a discriminated variant instead. Hiding those makes the Pulverizer unsearchable;</li>
     * <li>wildcard meta: `producers`/`consumers` fall back to `base:&#42;`, so every meta of
     *     a base some recipe wildcards is reachable.</li>
     * </ul>
     *
     * DELIBERATELY NOT widened from a bare key to its produced variants. That would re-admit
     * the duplicate search rows this exists to remove, and the bare key stays reachable by
     * direct link and from the machines page.
     */
    private static long[] buildLiveKeys(Csr byOutput, Csr byInput, Csr oreMembers,
                                        int[] oreGroupKeyId, Csr catalysts,
                                        int[] wildcardSibling, int[] nameId, int keyCount) {
        long[] live = Bits.ofSize(keyCount);
        for (int key = 0; key < keyCount; key++) {
            if (byOutput.count(key) > 0 || byInput.count(key) > 0) {
                Bits.set(live, key);
            }
        }
        for (int group = 0; group < oreGroupKeyId.length; group++) {
            int oreKey = oreGroupKeyId[group];
            if (oreKey >= 0 && byInput.count(oreKey) > 0) {
                for (int p = oreMembers.start(group); p < oreMembers.end(group); p++) {
                    Bits.set(live, oreMembers.at(p));
                }
            }
        }
        for (int row = 0; row < catalysts.rows(); row++) {
            for (int p = catalysts.start(row); p < catalysts.end(row); p++) {
                Bits.set(live, catalysts.at(p));
            }
        }
        if (wildcardSibling != null) {
            // A named key joins the live set when the wildcard covering its base is itself
            // live. `wildcardSibling` already answers "which wildcard covers this key", so
            // this needs no second pass to collect the wildcard bases.
            for (int key = 0; key < keyCount; key++) {
                if (nameId[key] < 0 || Bits.get(live, key)) {
                    continue;
                }
                int wild = wildcardSibling[key];
                if (wild >= 0 && Bits.get(live, wild)) {
                    Bits.set(live, key);
                }
            }
        }
        return live;
    }

    private static long[] buildReshapedOnly(RecipeStore store, Csr byOutput, int keyCount) {
        long[] bits = Bits.ofSize(keyCount);
        for (int key = 0; key < keyCount; key++) {
            int start = byOutput.start(key);
            int end = byOutput.end(key);
            if (start == end) {
                continue;
            }
            boolean allVariant = true;
            for (int p = start; p < end && allVariant; p++) {
                allVariant = store.isVariant(byOutput.at(p));
            }
            if (allVariant) {
                Bits.set(bits, key);
            }
        }
        return bits;
    }

    private static Csr pairsToCsr(IntArray rows, IntArray values, int rowCount) {
        Csr.Builder builder = new Csr.Builder(rowCount);
        for (int i = 0; i < rows.size(); i++) {
            builder.count(rows.get(i));
        }
        builder.prepare();
        for (int i = 0; i < rows.size(); i++) {
            builder.place(rows.get(i), values.get(i));
        }
        return builder.build();
    }

}
