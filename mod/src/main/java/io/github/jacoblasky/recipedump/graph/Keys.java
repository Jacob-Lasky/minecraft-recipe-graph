package io.github.jacoblasky.recipedump.graph;

/**
 * What an item key MEANS, ported from `recipegraph/model.py`.
 *
 * These are the string-level rules the whole graph rests on, and both implementations have
 * to agree exactly or a Java plan and a Python plan describe different items. The Python
 * side is the ORACLE here (see #19): when the two disagree, this file is what moves.
 *
 * The rules, restated because they are load-bearing rather than obvious:
 *
 * <ul>
 * <li>An item key is `mod:name` when meta is 0 or absent, `mod:name:meta` otherwise, and
 *     `mod:name:&#42;` for the wildcard meta 32767.</li>
 * <li>Fluids, oredict groups and essentia live in their OWN namespaces (`fluid:`, `ore:`,
 *     `essentia:`), so they can share a key space with concrete items without colliding.
 *     Their `:` separates a PREFIX from a name, never a name from a meta.</li>
 * <li>An NBT-discriminated stack appends `#&lt;12 hex&gt;` LAST, and the digest never
 *     contains a `#` of its own, so one rsplit is exact rather than a guess.</li>
 * </ul>
 *
 * Everything here is static and allocation-light: the solver runs on int ids and only
 * reaches for these when a key has to be parsed or rendered.
 */
public final class Keys {

    public static final int WILDCARD_META = 32767;

    /**
     * Which namespace a key lives in, as a small int so a per-key column can be a byte.
     *
     * ITEM IS ZERO AND THE REST INDEX {@link #NON_ITEM_KINDS} OFFSET BY ONE. That is the
     * whole reason the numbering looks arbitrary, and it is what lets one array serve the
     * name, the prefix and the test.
     */
    public static final int KIND_ITEM = 0;
    public static final int KIND_FLUID = 1;
    public static final int KIND_ESSENTIA = 2;
    public static final int KIND_ORE = 3;

    /**
     * The namespaces that are NOT concrete items.
     *
     * ONE list, in one order, mirroring `model.NON_ITEM_KINDS`. Three routines used to carry
     * their own copy in Python, two of them in a different order, which is three places to
     * update when a fourth kind arrives and three chances to miss one. Everything below --
     * the prefixes, the kind ids, the display prefixes -- is derived from this array rather
     * than restated, so a fourth kind is one line here and nothing else.
     */
    public static final String[] NON_ITEM_KINDS = {"fluid", "essentia", "ore"};

    /** Unpacked rather than written out again, exactly as `model.py` unpacks them. */
    private static final String[] NON_ITEM_PREFIXES = new String[NON_ITEM_KINDS.length];

    static {
        for (int i = 0; i < NON_ITEM_KINDS.length; i++) {
            NON_ITEM_PREFIXES[i] = NON_ITEM_KINDS[i] + ":";
        }
    }

    public static final String FLUID_PREFIX = NON_ITEM_PREFIXES[KIND_FLUID - 1];
    public static final String ESSENTIA_PREFIX = NON_ITEM_PREFIXES[KIND_ESSENTIA - 1];
    public static final String ORE_PREFIX = NON_ITEM_PREFIXES[KIND_ORE - 1];

    /**
     * Forge's convention for "a block you find in the world and hit": `oreDiamond`,
     * `oreLapis`. ONE definition, because the world-ore set and any dimension gate have to
     * agree on it exactly. The `ore` prefix is load-bearing: `chisel:diamond` is a member of
     * `blockDiamond`, so accepting `block&#42;` too would readmit exactly the decorative
     * blocks this test exists to demote.
     */
    public static final String WORLD_ORE_GROUP_PREFIX = "ore";

    /** `split_key` found no meta suffix, or the key is not an item key. */
    public static final int META_NONE = -1;
    /** The key ends in `:&#42;`, the wildcard meta. */
    public static final int META_WILDCARD = -2;

    private Keys() {
    }

    /**
     * Canonical item key from a registry name plus a metadata value.
     *
     * Returns null for an empty or absent id rather than minting a key, because a phantom
     * `minecraft:` key is worse than a dropped ingredient: it looks real everywhere
     * downstream. An id with no `:` is assumed vanilla, which is Forge's own default.
     */
    public static String normKey(String itemId, int meta) {
        if (itemId == null) {
            return null;
        }
        String id = itemId.trim();
        if (id.isEmpty()) {
            return null;
        }
        if (id.indexOf(':') < 0) {
            id = "minecraft:" + id;
        }
        if (meta == WILDCARD_META) {
            return id + ":*";
        }
        if (meta == 0) {
            return id;
        }
        return id + ":" + meta;
    }

    public static String oreKey(String name) {
        return ORE_PREFIX + name;
    }

    public static String fluidKey(String name) {
        return FLUID_PREFIX + name;
    }

    public static String essentiaKey(String aspect) {
        return ESSENTIA_PREFIX + aspect.toLowerCase();
    }

    public static boolean isItemKey(String key) {
        return kindId(key) == KIND_ITEM;
    }

    /**
     * The {@code KIND_} constant for a key.
     *
     * THE ONLY PLACE A KEY'S NAMESPACE IS DECIDED. It used to be decided here, again while
     * interning, and a third time when rendering, which is the shape `model.py`'s own
     * comment warns about: three copies means a fourth namespace arrives correct in two
     * places and wrong in the third, and the wrong one reads as a display bug rather than as
     * a missing case.
     */
    public static int kindId(String key) {
        for (int i = 0; i < NON_ITEM_PREFIXES.length; i++) {
            if (key.startsWith(NON_ITEM_PREFIXES[i])) {
                return i + 1;
            }
        }
        return KIND_ITEM;
    }

    /** Which namespace a kind id names: `fluid`, `essentia`, `ore`, or `item`. */
    public static String kindName(int kindId) {
        return kindId == KIND_ITEM ? "item" : NON_ITEM_KINDS[kindId - 1];
    }

    /**
     * The bracketed text prefix a non-item key is printed with, or "" for an item.
     *
     * `ore` reads "oredict" rather than "ore" because it means "any member of", not "an ore".
     * DO NOT drop these to tidy a UI: this is what a shopping list prints, and without them
     * `water` and `Water Bucket` are indistinguishable. A caller with somewhere to put a type
     * chip should use the kind and the bare name separately instead.
     */
    public static String kindPrefix(int kindId) {
        switch (kindId) {
            case KIND_FLUID:
                return "[fluid] ";
            case KIND_ESSENTIA:
                return "[essentia] ";
            case KIND_ORE:
                return "[oredict] ";
            default:
                return "";
        }
    }

    /** Which namespace a key lives in: `fluid`, `essentia`, `ore`, or `item`. */
    public static String kind(String key) {
        return kindName(kindId(key));
    }

    public static boolean isWorldOreGroup(String oreName) {
        return oreName.startsWith(WORLD_ORE_GROUP_PREFIX);
    }

    /**
     * Forge's `&lt;form&gt;&lt;Material&gt;` oredict convention. Mirrors `model.split_ore_group`.
     *
     * `ore` IS ABSENT ON PURPOSE and its absence is load-bearing: an ore is the OBTAINABLE
     * end of a family, so including it would let a family be named by the very thing that is
     * out of reach.
     *
     * `block` IS ABSENT TOO, AND ITS REASON HOLDS IN ONE DIRECTION ONLY. `chisel:diamond` is in
     * `blockDiamond`, so a family this list could name a BLOCK for would point a reader at a
     * decorative panel, which is the cluster #61 demoted. That is about the ANSWER
     * {@code Unsourced.obtainableSibling} returns. Whether a producerless `blockMyrmitite` is a
     * shape of a material is a different question, and {@link #storageMaterialOfOreGroup}
     * answers it. See `model.storage_form_material` in python for the measurement.
     *
     * KEEP THIS LIST BYTE-EQUAL TO `model.PROCESSED_FORM_PREFIXES`. It decides which keys get
     * #136's "nothing makes this form" mark, that mark is a field on a plan node, and
     * `PlanFixtureTest` compares plan nodes field for field -- so a prefix in one language
     * and not the other is a failing golden gate with no behavioural change to point at.
     */
    public static final String[] PROCESSED_FORM_PREFIXES = {
        "nugget", "dust", "plate", "gear", "rod", "stick", "gem", "ingot", "wire", "foil",
        "casing", "coil", "screw", "bolt", "ring", "chunk", "crushed", "purified", "clump",
        "shard",
    };

    /**
     * The material half of `nuggetSednanite`, or null when the name is not that shape.
     *
     * LONGEST PREFIX WINS, so the split stays stable if the list grows to hold one prefix of
     * another.
     */
    public static String materialOfOreGroup(String oreName) {
        if (oreName == null) {
            return null;
        }
        String lowered = oreName.toLowerCase(java.util.Locale.ROOT);
        String best = null;
        for (String form : PROCESSED_FORM_PREFIXES) {
            if (lowered.startsWith(form) && oreName.length() > form.length()
                    && (best == null || form.length() > best.length())) {
                best = form;
            }
        }
        return best == null ? null : oreName.substring(best.length());
    }

    /**
     * Forge's `block&lt;Material&gt;`: nine ingots pressed into one block for storage.
     *
     * NOT AN ENTRY IN {@link #PROCESSED_FORM_PREFIXES}, because that list is also the naming
     * set -- see the `block` paragraph on it. This reads the same registration on the QUESTION
     * side only: a storage block nobody can press is as unobtainable as a nugget nobody can
     * split, which is where #136 finished. Mirrors `model.storage_form_material` in python and
     * carries its measurement.
     */
    public static final String STORAGE_FORM_PREFIX = "block";

    /** The material half of `blockMyrmitite`, or null when the name is not that shape. */
    public static String storageMaterialOfOreGroup(String oreName) {
        if (oreName == null || oreName.length() <= STORAGE_FORM_PREFIX.length()
                || !oreName.toLowerCase(java.util.Locale.ROOT).startsWith(STORAGE_FORM_PREFIX)) {
            return null;
        }
        return oreName.substring(STORAGE_FORM_PREFIX.length());
    }

    /** The index of the discriminator's `#`, or -1. */
    public static int discriminatorAt(String key) {
        return key.lastIndexOf('#');
    }

    /** The `#suffix` of a discriminated key, or null. */
    public static String discriminator(String key) {
        int at = discriminatorAt(key);
        return at < 0 ? null : key.substring(at + 1);
    }

    /**
     * The item key a discriminated stack is a variant of; unchanged for plain keys.
     *
     * `forestry:can:1#48a337d94489` -&gt; `forestry:can:1`. Use this wherever the question is
     * about the ITEM rather than about one particular NBT state of it.
     *
     * DO NOT also strip the meta. Meta separates genuinely different items, and collapsing
     * it would merge `tconstruct:ingots:0` with `tconstruct:ingots:3` into one pseudo-item
     * that appears to melt into every molten metal in the pack.
     */
    public static String baseKey(String key) {
        int at = discriminatorAt(key);
        return at < 0 ? key : key.substring(0, at);
    }

    /**
     * The metadata a key carries: 0 when none, {@link #META_WILDCARD}, or
     * {@link #META_NONE} for a fluid, oredict or essentia key.
     *
     * DELIBERATELY DOES NOT STRIP THE DISCRIMINATOR FIRST, matching `model.split_key`. A
     * discriminated key's last colon-separated part is `1#48a337d94489`, which is not a
     * digit run, so it reads as meta 0 -- and that is what makes a discriminated key skip
     * the wildcard fallback in `producers`. Stripping first here would quietly widen every
     * NBT variant onto its base's wildcard recipes.
     */
    public static int metaOf(String key) {
        if (!isItemKey(key)) {
            return META_NONE;
        }
        int last = key.lastIndexOf(':');
        if (last < 0 || key.indexOf(':') == last) {
            return 0;
        }
        String tail = key.substring(last + 1);
        if (tail.equals("*")) {
            return META_WILDCARD;
        }
        if (isDigits(tail)) {
            return Integer.parseInt(tail);
        }
        return 0;
    }

    /** The key with any meta suffix removed. Unchanged when {@link #metaOf} reads 0. */
    public static String withoutMeta(String key) {
        int meta = metaOf(key);
        if (meta == 0 || meta == META_NONE) {
            return key;
        }
        return key.substring(0, key.lastIndexOf(':'));
    }

    /**
     * The registry name behind an item key, with NBT and metadata both dropped.
     *
     * `forestry:can:1#48a337d94489` -&gt; `forestry:can`. Null for a non-item key, whose `:`
     * separates a prefix from a name: stemming `fluid:water` to `fluid` would make every
     * fluid a metadata sibling of every other.
     */
    public static String itemStem(String key) {
        if (!isItemKey(key)) {
            return null;
        }
        return withoutMeta(baseKey(key));
    }

    /**
     * What a key still says about an item once modid, meta and discriminator are gone.
     *
     * The only human-readable thing a key carries when no source named the item, so it is
     * the display fallback. `key.split(":")[-1]` is the wrong shape and was the original
     * bug: it hands `mod:thing:3` back as "3".
     */
    public static String pathOf(String key) {
        String base = withoutMeta(baseKey(key));
        int colon = base.indexOf(':');
        return colon < 0 || colon == base.length() - 1 ? base : base.substring(colon + 1);
    }

    /**
     * True when a key's `#suffix` is a dump discriminator rather than a readable word.
     *
     * The one place that shape is written down. Twelve lowercase hex digits.
     */
    public static boolean isDigest(String suffix) {
        if (suffix == null || suffix.length() != 12) {
            return false;
        }
        for (int i = 0; i < 12; i++) {
            char c = suffix.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    /**
     * How to read the `#suffix` when nothing named the variant.
     *
     * A digest reads as line noise, so it is labelled as what it is and shortened. NEVER
     * collapse two digests to one label: telling a Forest drone from a Meadows drone is the
     * entire point of discriminating at all.
     *
     * The word branch is for a suffix that is not a digest, which today means a have-file
     * written before the reader moved onto the dump's digest.
     */
    public static String variantLabel(String suffix) {
        if (isDigest(suffix)) {
            return "variant " + suffix.substring(0, 6);
        }
        if (suffix.isEmpty()) {
            return suffix;
        }
        // Python's str.capitalize() lowercases the tail as well as raising the head, and the
        // fixtures were recorded against that, so `PERDITIO` must come back as `Perditio`.
        return Character.toUpperCase(suffix.charAt(0)) + suffix.substring(1).toLowerCase();
    }

    /**
     * True when a label is Minecraft's unlocalized lang KEY rather than a name.
     *
     * `tile.null.name` is what the game renders for a block whose mod shipped no lang entry,
     * and `getDisplayName()` hands it back as if it were a name. Measured on the reference
     * pack: 1,429 labels have this shape and 268 of them are the identical string
     * `tile.null.name`, so 268 unrelated items render as the same three words.
     *
     * DO NOT tighten this to a `tile.`/`item.` prefix: the leading segment is also
     * `parttype`, `fluid` and a bare modid, and those are just as unusable. The no-space
     * test is load-bearing in the other direction -- `Spawn entity.blackfrost.name` is half
     * localized, and keeping it beats replacing it with a registry path.
     */
    public static boolean isUnlocalized(String label) {
        if (label == null || label.isEmpty()) {
            return false;
        }
        if (!label.endsWith(".name") || label.indexOf(' ') >= 0) {
            return false;
        }
        int dots = 0;
        for (int i = 0; i < label.length(); i++) {
            if (label.charAt(i) == '.') {
                dots++;
            }
        }
        return dots >= 2;
    }

    /**
     * `boric_acid` -&gt; `Boric Acid`, for keys with no localized name to fall back on.
     *
     * A word that already carries capitals is left alone, because title-casing would turn
     * `TBU` into `Tbu` and `NaOH` into `Naoh`.
     */
    public static String prettify(String registryName) {
        StringBuilder out = new StringBuilder(registryName.length());
        int i = 0;
        int length = registryName.length();
        while (i < length) {
            char c = registryName.charAt(i);
            if (c == '_' || Character.isWhitespace(c)) {
                i++;
                continue;
            }
            int start = i;
            while (i < length) {
                char w = registryName.charAt(i);
                if (w == '_' || Character.isWhitespace(w)) {
                    break;
                }
                i++;
            }
            String word = registryName.substring(start, i);
            if (out.length() > 0) {
                out.append(' ');
            }
            if (hasUpperCase(word)) {
                out.append(word);
            } else {
                out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return out.length() == 0 ? registryName : out.toString();
    }

    private static boolean hasUpperCase(String word) {
        for (int i = 0; i < word.length(); i++) {
            if (Character.isUpperCase(word.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
