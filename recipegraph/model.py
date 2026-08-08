"""Core data model for the recipe graph.

Design notes that are load-bearing:

* An item KEY is `mod:name` when meta is 0/absent, `mod:name:meta` otherwise, and
  `mod:name:*` for the wildcard meta (32767). Oredict entries get an `ore:` prefix
  so they can live in the same namespace as concrete items without colliding.
* A recipe input is a LIST of alternatives (oredict members, or a JSON ingredient
  array). Never flatten alternatives to a single item -- the solver needs the
  choice to satisfy inputs from what the player actually has.
* Recipes are many-per-item on purpose. Picking one is the solver's job, not the
  extractor's, because the right pick depends on the player's inventory.
"""

import json
import re

# The ONLY package import this module has, and it is one constant rather than a layering
# mistake. `pack_authored_unsourced` needs to know which namespaces a pack writes its script
# items into, and `tokens.TOKEN_NAMESPACES` is the declared home of that answer. Restating
# the tuple here would be a second spelling of one fact, which is the drift `reachable_form`
# spent a PR removing -- so importing it is the cheaper of the two mistakes available.
# SAFE BECAUSE `tokens` IMPORTS NOTHING FROM THIS PACKAGE (json and os only); if that ever
# stops being true this becomes a cycle, so check before adding an import there.
from . import tokens

WILDCARD_META = 32767


def norm_key(item_id, meta=0):
    """Canonical item key. Accepts meta as int, str, None, or the 32767 wildcard.

    THE `minecraft:` DEFAULT BELOW IS FOR IDS THAT COME FROM THE RUNNING GAME, AND IT IS THE
    WRONG DEFAULT FOR A RECIPE JSON. DO NOT teach this function the recipe file's namespace,
    and DO NOT delete the fallback as "dead" -- both were considered and both are wrong. See
    #227.

    Every caller but one hands this a FULLY QUALIFIED registry id, because every one of them
    is reading a name the RUNNING GAME wrote down: `sources/hei_dump` off JEI's stacks,
    `sources/catalysts`, `sources/damageable` and `sources/oredict` off the same dump,
    `names` off the AE2 `items.csv`, and `multiblocks` and `explore` off ids already in the
    graph. For those an unqualified id really is a vanilla one, so the fallback is right and
    is what keeps a bare `stone` from becoming a keyless string.

    The one caller that does NOT is `sources/jar_json`, because Forge resolves an unqualified
    id in a recipe file against THE FILE'S OWN DOMAIN (`JsonContext.appendModId`), not against
    `minecraft`. That caller qualifies the id itself in `jar_json.qualify` before it gets here.
    Moving that logic into this function would need a namespace argument on every call site
    that has no namespace to give, and would silently re-point the dump readers the day one of
    them saw a bare id.
    """
    if item_id is None:
        return None
    item_id = str(item_id).strip()
    if not item_id:
        return None
    if ":" not in item_id:
        item_id = "minecraft:" + item_id
    if meta is None or meta == "":
        meta = 0
    try:
        meta = int(meta)
    except (TypeError, ValueError):
        meta = 0
    if meta == WILDCARD_META:
        return "%s:*" % item_id
    if meta == 0:
        return item_id
    return "%s:%d" % (item_id, meta)


def ore_key(name):
    return "ore:%s" % name


def fluid_key(name):
    return FLUID_PREFIX + name


def essentia_key(aspect):
    """Thaumcraft essentia as a plannable node.

    Essentia is an intermediate, not a terminal resource -- multiblocks convert items
    (vis pods, for one) into aspects -- so it must be able to appear on BOTH sides of a
    recipe, exactly like an item or a fluid. Hence its own namespace rather than a
    report-only side channel.
    """
    return "essentia:%s" % str(aspect).lower()


def _prettify(registry_name):
    """`boric_acid` -> `Boric Acid`, for keys with no localized name to fall back on."""
    words = str(registry_name).replace("_", " ").split()
    # Leave a word alone if it already carries capitals: `.title()` would turn `TBU` into
    # `Tbu` and `NaOH` into `Naoh`.
    return " ".join(w if any(c.isupper() for c in w) else w.capitalize()
                    for w in words) or registry_name


def path_of(key):
    """The registry path of a key: `mod:name`, `mod:name:3`, `mod:name#a3f19c` -> `name`.

    What a key still says about an item once the modid, the meta and any NBT discriminator
    are stripped, which is the ONLY human-readable thing it carries when no source named
    the item. Shared by the display fallback in `Graph.bare_name` and by the unknown-item
    page, which prefills its search box with this. That page used `key.split(":")[-1]`,
    which handed `mod:thing:3` back as "3" and searched for the meta.
    """
    stem, _disc = split_discriminator(key)
    base, _meta = split_key(stem)
    return base.partition(":")[2] or base


def is_unlocalized(label):
    """True when a label is Minecraft's unlocalized lang KEY rather than a name.

    `tile.null.name` is what the game renders for a block whose mod shipped no lang entry,
    and `getDisplayName()` hands it back as if it were a name. It is not one. Measured on
    the reference pack: 1,429 of 340,324 labels have this shape, and 268 of them are the
    identical string `tile.null.name`, so 268 unrelated items -- including all eight
    Modular Machinery fission controllers -- render as the same three words. See #52.

    Shape: dot-separated, no spaces, ending in `.name`. DO NOT tighten this to a
    `tile.`/`item.` prefix: the leading segment is also `parttype` (39 labels),
    `fluid` (3) and a bare modid, and those are just as unusable. The no-space test is
    load-bearing in the other direction -- `Spawn entity.blackfrost.name` is the one label
    that ends in `.name` and contains a space, it is half localized, and keeping it beats
    replacing it with a registry path.

    Sibling of `names.clean_label`, which drops a label that was ONLY colour codes. Same
    job, opposite module, and they cannot merge: `names` imports `model`. If you add a
    third "is this label real" rule, put it in one of these two, not a new place.
    """
    if not label:
        return False
    return label.endswith(".name") and " " not in label and label.count(".") >= 2


_DIGEST = re.compile(r"^[0-9a-f]{12}$")


def is_digest(suffix):
    """True when a key's `#suffix` is a dump discriminator rather than a readable word.

    The one place that shape is written down. `_variant_label` needs it to decide how to
    render an unnamed variant, and `gaps.stock_coverage` needs it to tell "this stock key
    was written by a reader older than #21" from "this digest is not in this dump", which
    are different problems with different fixes.
    """
    return bool(_DIGEST.match(suffix))


def _variant_label(suffix):
    """How to read the `#suffix` on a discriminated key when nothing named it.

    A dump discriminator is a 12-hex digest of the stack's NBT, which reads as line noise,
    so it is labelled as what it is and shortened. Never collapse two digests to one
    label: telling a Forest drone from a Meadows drone is the entire point.

    The word branch is for a suffix that is not a digest, which today means a HAVE-FILE
    WRITTEN BEFORE #21: the world-save reader used to decode `Aspect` into the key and
    emit `thaumadditions:vis_pod#perditio`. It emits the digest now, because that is what
    the dump speaks, but an `ae2_have.json` on disk still carries the old shape until it
    is rescanned. Keep this readable rather than rendering "variant perdit".
    """
    if is_digest(suffix):
        return "variant %s" % suffix[:6]
    return suffix.capitalize()


# The namespaces that are NOT concrete items. Fluids, oredict names and essentia aspects
# carry neither a meta nor an NBT discriminator, so every routine that picks items out of
# a mixed key list has to know them.
#
# ONE list, in one order. `split_key`, `kind` and the container detector each carried
# their own copy, two of them in a different order, which is three places to update when
# `essentia:` arrived and three chances to miss one. `present.KIND_CHIP` is deliberately
# separate: it is the presentation layer and has its own completeness tests.
NON_ITEM_KINDS = ("fluid", "essentia", "ore")
NON_ITEM_PREFIXES = tuple("%s:" % k for k in NON_ITEM_KINDS)
# Unpacked rather than written out again, so a fourth kind cannot arrive with one of
# these left spelled as a literal. Every module that tests or strips a namespace uses
# these; the bare strings were in six files and `labels` re-listed the whole tuple.
FLUID_PREFIX, ESSENTIA_PREFIX, ORE_PREFIX = NON_ITEM_PREFIXES


def is_item_key(key):
    """True for a concrete item key, False for a fluid, oredict or essentia key."""
    return not key.startswith(NON_ITEM_PREFIXES)


# Forge's convention for "a block you find in the world and hit": `oreDiamond`, `oreLapis`.
# ONE definition of the test, because `Graph.world_ores` and `dimensions.shadow_ores` have
# to agree on it exactly -- see `world_ores` for why the prefix is load-bearing and what
# accepting `block*` too would readmit. A shadow key matched through `blockDiamond` would
# pull a decorative block into a dimension gate, which is the same failure in a new place.
WORLD_ORE_GROUP_PREFIX = "ore"


def is_world_ore_group(ore):
    """True for an oredict group name that means "mined", not "made of"."""
    return ore.startswith(WORLD_ORE_GROUP_PREFIX)


# Forge's oredict convention is `<form><Material>` -- `nuggetSednanite`, `ingotIron`,
# `dustRedstone`. These are the forms that mean "a PROCESSED shape of a material", the
# mirror of `ore*` meaning "a thing you dig up".
#
# READ BACK FROM THE PACK'S OWN REGISTRATION, not guessed from a registry name, which is the
# same standing rule `world_ores` follows. A prefix list is only as good as the convention
# behind it, and this convention is one Forge enforces on every mod that wants its ingots to
# interoperate -- which is why `ore*` was trustworthy enough to hang #61 and #106 on.
#
# `ore` IS ABSENT ON PURPOSE and its absence is load-bearing: an ore is the obtainable end of
# a family, so including it here would let a family be "named" by the very thing that is out
# of reach.
#
# `block` IS ABSENT TOO, AND ITS REASON HOLDS IN ONE DIRECTION ONLY. `chisel:diamond` is a
# member of `blockDiamond`, so a family this list could name a BLOCK for would point a reader
# at a decorative panel as the shape the pack makes, which is what #61 spent its measurement
# demoting. That argument is about the ANSWER `obtainable_sibling` returns, and it stands.
# It says nothing about the QUESTION -- whether a key the pack registered `blockMyrmitite`
# and nothing makes is a shape of a material rather than a thing in the world -- and there
# the answer is plainly yes. So `block` stays out of this list, which is the naming set, and
# `storage_form_material` reads it on the question side. That function carries the census.
PROCESSED_FORM_PREFIXES = ("nugget", "dust", "plate", "gear", "rod", "stick", "gem",
                           "ingot", "wire", "foil", "casing", "coil", "screw", "bolt",
                           "ring", "chunk", "crushed", "purified", "clump", "shard")

# Forge's `block<Material>`: nine ingots pressed into one block for storage. NOT in the list
# above and not a second entry in it -- see the `block` paragraph there for why the two uses
# of that list pull in opposite directions.
STORAGE_FORM_PREFIX = "block"


def split_ore_group(ore):
    """`(form, material)` for `nuggetSednanite`, or None when the name is not that shape.

    LONGEST PREFIX WINS, because the list overlaps: `stick` is a prefix of nothing here but
    `gem` is a prefix of nothing while `ingot` and `ing` would collide if `ing` were ever
    added. Taking the longest match keeps the split stable if the list grows.
    """
    lowered = ore.lower()
    best = None
    for form in PROCESSED_FORM_PREFIXES:
        if lowered.startswith(form) and len(ore) > len(form):
            if best is None or len(form) > len(best):
                best = form
    return (best, ore[len(best):]) if best else None


def storage_form_material(ore):
    """The material of a `block<Material>` group, or None when the name is not that shape.

    THE QUESTION SIDE OF #136, AND ONLY THAT. `Graph.obtainable_sibling` uses it to decide
    whether a key is a shape of a material at all; the sibling it goes on to NAME still comes
    from `PROCESSED_FORM_PREFIXES`, so a decorative block can never be the answer.

    WHY A STORAGE BLOCK IS THE SAME DEFECT AS A NUGGET. #176 priced the processed forms
    nothing makes, which killed the nugget ladder #136 reported, and the planner fell one
    denomination the other way: measured on the reference graph, a plan for Myrmitite Ingot
    asked for `1 Block of Myrmitite`, a key with no producer, no stock and no world
    generation, priced at `BASE_RAW_COST` because nothing makes it. Pressing nine ingots into
    a block is production, so a block nobody can press is exactly as unobtainable as a nugget
    nobody can split.

    IT IS SAFE BECAUSE THE SECOND CLAUSE DOES THE WORK, and that is measured rather than
    argued. On the reference graph 50 producerless keys priced at a raw leaf carry a `block*`
    group and no processed one. Every one is accounted for:

        19  a material with processed forms but nothing producible among them: Quicksoil
            Glass and 18 more AoA3 and DivineRPG world glasses, whose `Glass` family has a
            `shardGlass` group that nothing in the pack makes. Blocks you mine, not badged.
        15  a wildcard meta, refused earlier by `reachable_form`: `minecraft:clay:*`,
            `minecraft:slime:*`, `minecraft:cactus:*`, `minecraft:mossy_cobblestone:*`.
         2  a material registered in no processed shape at all: a railcraft coke block and
            `tconstruct:slime`.
        14  BADGED AND REPRICED, which is the whole of what this changes: eleven planet-metal
            storage blocks behind #112's gates -- Myrmitite, Ogerite, Dreadite, Polonium,
            Vityte, Vibranium, Chalcedony, Lunastone, Palladium, Brightsteel, Candyte -- plus
            two duplicate-name Iridium blocks and a railcraft Block of Zinc.

    THE 19 REST ON "NOTHING PRODUCIBLE TO NAME" RATHER THAN ON THE FAMILY BEING ABSENT, so
    they are the ones to re-measure if a dump ever gives `shardGlass` a producer. That is the
    honest limit of this rule and `StorageBlockTest` pins the refusal it depends on.

    DO NOT REPLACE THIS WITH A `block` ENTRY IN `PROCESSED_FORM_PREFIXES`. That list is also
    the naming set, and adding `block` to it would give the Glass family producible members
    (`erebus:amber_glass`, eight DivineRPG stained glasses) -- so all 19 world glasses above
    would be badged and repriced, each consumed by ~320 recipes. Measured, and it is the
    failure this split avoids.
    """
    if ore.lower().startswith(STORAGE_FORM_PREFIX) and len(ore) > len(STORAGE_FORM_PREFIX):
        return ore[len(STORAGE_FORM_PREFIX):]
    return None


def canonical_item_key(raw):
    """A raw `mod:name[:meta][#digest]` id from a NAME source -> the key this graph uses.

    THE ONE SPELLING OF "PARSE A META OFF AN ID AND CANONICALISE IT", shared by the two
    sources that name items: `names.load_items_csv` for the pack's `items.csv` and
    `sources/dump_names` for the mod's `names.json`. Issue #253.

    IT EXISTS BECAUSE THE TWO DISAGREED AND THE DISAGREEMENT WAS INVISIBLE. `load_items_csv`
    split the meta and ran it through `norm_key`, so a 32767 row became `mod:thing:*`;
    `dump_names` used the mod's string verbatim, so the SAME item arrived as the literal
    `mod:thing:32767`. Every recipe spells it `:*`, so the second spelling named a key
    nothing produces and nothing consumes. Measured on the reference dump: **2,672 keys**,
    every one of them meta 32767, every one existing only as a label -- 0 produced, 0
    consumed. 172 of them looked like a pricing defect because `Graph.producers` widens
    `:meta` to `:*` and therefore found them a producer they had no price for (#253).

    ZERO COLLISIONS when they are merged, which is why this is a straight fix and not a
    policy question: in all 2,672 cases the canonical `:*` target was ABSENT from the name
    map, so every label is GAINED by the key that actually has the recipe and none is
    overwritten or lost.

    NEGATIVE METAS ARE ACCEPTED because `items.csv` carries them and this must not narrow
    what that reader already took; `split_key` deliberately does not, since a key is not a
    raw id and nothing writes a negative one.

    A `#digest` SUFFIX IS PRESERVED AND NOT PARSED. Only `names.json` carries them, the
    digest is appended last (see `split_discriminator`), and the meta sits before it.
    """
    if not raw:
        return raw
    stem, disc = split_discriminator(str(raw))
    parts = stem.split(":")
    meta = 0
    if len(parts) >= 3 and parts[-1].lstrip("-").isdigit():
        meta = int(parts[-1])
        stem = ":".join(parts[:-1])
    key = norm_key(stem, meta)
    if key and disc:
        return "%s#%s" % (key, disc)
    return key


def split_discriminator(key):
    """Return (key_without_discriminator, discriminator_or_None).

    An NBT-discriminated key is `mod:name[:meta]#suffix`, where the suffix is appended
    last and never contains a `#` of its own (see sources/hei_dump._stack_key), so one
    rsplit is exact rather than a guess.
    """
    if "#" in key:
        base, disc = key.rsplit("#", 1)
        return base, disc
    return key, None


def base_key(key):
    """The item key a discriminated stack is a variant of; unchanged for plain keys.

    `forestry:can:1#48a337d94489` -> `forestry:can:1`. Use this wherever a question is
    about the ITEM rather than about one particular NBT state of it: "is this a
    container", "can this machine be built".

    DO NOT also strip the meta. Meta distinguishes genuinely different items, and
    collapsing it would merge `tconstruct:ingots:0` with `tconstruct:ingots:3` into one
    pseudo-item that appears to melt into every molten metal in the pack.
    """
    return split_discriminator(key)[0]


def item_stem(key):
    """The registry name behind an item key, with NBT and metadata both dropped.

    `forestry:can:1#48a337d94489` -> `forestry:can`. None for a fluid, oredict or essentia
    key, whose `:` separates a PREFIX from a name rather than a name from a meta: stemming
    `fluid:water` to `fluid` would make every fluid a metadata sibling of every other.

    Answers exactly one question -- "is this the same registered object" -- and `base_key`
    records why that is a narrower licence than it looks. See `Graph.meta_sibling_made`.
    """
    if not is_item_key(key):
        return None
    return split_key(base_key(key))[0]


def split_key(key):
    """Return (base_key_without_meta, meta_or_None) for concrete item keys."""
    if not is_item_key(key):
        return key, None
    parts = key.split(":")
    if len(parts) >= 3:
        tail = parts[-1]
        if tail == "*":
            return ":".join(parts[:-1]), "*"
        if tail.isdigit():
            return ":".join(parts[:-1]), int(tail)
    return key, 0


def mod_of(key):
    """The mod that registered `key`, or "" for a fluid, oredict or essentia key.

    The namespace before the first colon. Empty rather than the prefix for a non-item key,
    because `fluid:nethengeic_fluid` would otherwise answer "fluid" -- its KIND wearing the
    shape of an answer, and a sweep grouping by mod would report a mod called fluid.

    ONE SPELLING, because there were two: `api.FIELDS["mod"]` computed this inline and
    `dimensions.shadow_ores` needed the same answer, which is how `reachable_form` acquired
    the copy that drifted for two releases. Both read this.
    """
    return key.split(":")[0] if is_item_key(key) else ""


class Ingredient:
    """One input slot: any of `alternatives` satisfies it, `qty` of them.

    `consume_chance` is the probability the slot is SPENT by a run, 0.0 to 1.0, and it
    defaults to 1.0 because that is what every dump before schema 6 means. See #175: the
    dump had no field for this at all, so a Deep Mob Learning data model that sits in the
    machine forever was bought once per run and a Spinel Ring plan asked for 64 of them.

    0.0 IS NOT "FREE", IT IS "OWN ONE". An input the run never spends is economically a
    machine: you must have it before the recipe runs at all, and it survives. `cost._relax` puts
    it beside `category_entry_cost` rather than in the amortising ingredient term, and
    `Solver._build` expands it once per plan rather than once per run. Pricing it at zero
    instead would repeat the defect #176 fixed, where the cheapest thing in the model was
    the thing the solver could not actually obtain.
    """

    __slots__ = ("alternatives", "qty", "role", "consume_chance")

    def __init__(self, alternatives, qty=1, role="item", consume_chance=1.0):
        # dedupe but keep order so the first alternative stays the "canonical" one
        seen = set()
        alts = []
        for a in alternatives:
            if a and a not in seen:
                seen.add(a)
                alts.append(a)
        self.alternatives = alts
        self.qty = qty
        self.role = role
        self.consume_chance = consume_chance

    @property
    def survives_run(self):
        """True when a run never spends this slot, so owning one is the whole requirement.

        DO NOT RENAME THIS TO `is_catalyst`, AND DO NOT CALL THIS CONCEPT A CATALYST IN CODE.
        This repository already owns that word for a different thing: a JEI recipe catalyst is
        the machine BLOCK a recipe is displayed against, which is what `catalysts.json`,
        `sources/catalysts.py`, `multiblocks.py` and ten files in the Java port mean by it. A
        non-consumed INPUT is unrelated to that, and one word for two concepts in one codebase
        is how a reader of a plan node concludes the row is naming a machine. #175 uses the
        word in its prose because that is the mod-community term; the code says what happens.
        """
        return self.consume_chance == 0.0

    def to_json(self):
        d = {"alt": self.alternatives, "qty": self.qty}
        if self.role != "item":
            d["role"] = self.role
        # OMITTED WHEN 1.0, so a graph built from a pre-#175 dump serialises byte for byte as
        # it did before and the Java reader's default is exercised by the common case rather
        # than by a rarity. `graph.json` is 121 MB; a field on all 335k slots is not free.
        if self.consume_chance != 1.0:
            d["p"] = self.consume_chance
        return d

    @staticmethod
    def from_json(d):
        return Ingredient(d["alt"], d.get("qty", 1), d.get("role", "item"),
                          d.get("p", 1.0))


def merge_slots(inputs, key_of):
    """Slots that are the same ingredient, collapsed: [(key, first slot, qty, options)].

    A shaped recipe has one input SLOT per grid cell, so a 3x3 of one ingredient is nine
    `Ingredient` objects naming one key. Anything that shows or expands them per slot
    repeats itself nine times -- 21,417 of the reference pack's 117,685 recipes have at
    least two slots that collapse. See #24.

    `key_of` is the caller's idea of "the same": the solver merges on the alternative it
    PICKED, so slots offering different choices that land on one item become one node,
    while the item page merges on the alternatives as AUTHORED, having no inventory to
    pick with. Insertion order is preserved so both stay deterministic.

    `options` is the WIDEST slot's alternative count, not the first's: a merged row that
    reported 1 option while standing in for a slot that accepted 3 would misstate the
    choice that was made.

    SLOTS ARE BUCKETED BY KEY *AND* `consume_chance`, SO THE SAME ITEM CAN YIELD TWO ROWS,
    and callers must not assume the returned keys are distinct. #175: a recipe may hold one
    item as a RETAINED input and the same item as a consumed ingredient, and those are
    two different requirements -- own one, and spend N per run. Bucketing on the key alone
    fused them and SUMMED their quantities, so the retained one became stock you spend.
    Merging
    still collapses the 3x3-of-one-ingredient case this function exists for, because those
    nine slots share a chance. Inert on every dump written before `p` existed, where every
    slot is 1.0 and this bucket is exactly the old one.
    """
    merged = {}
    for ing in inputs:
        key = key_of(ing)
        bucket = (key, ing.consume_chance)
        row = merged.get(bucket)
        if row is None:
            merged[bucket] = [key, ing, ing.qty, len(ing.alternatives)]
        else:
            row[2] += ing.qty
            row[3] = max(row[3], len(ing.alternatives))
    return [tuple(row) for row in merged.values()]


class Recipe:
    __slots__ = ("rid", "source", "category", "outputs", "inputs", "machine",
                 "transfer", "variant", "not_production")

    def __init__(self, rid, source, outputs, inputs, category="crafting", machine=None,
                 transfer=False, variant=False):
        self.rid = rid
        self.source = source          # which extractor produced this
        self.category = category      # JEI category / recipe kind
        self.outputs = outputs        # [(key, qty)]
        self.inputs = inputs          # [Ingredient]
        self.machine = machine        # display name of the machine, if any
        # True for container fill/empty pseudo-recipes: they MOVE a fluid rather than
        # create it. Treating them as production makes every fluid free to anyone who
        # owns a tank. Set by index.mark_container_transfers, never by an extractor.
        self.transfer = transfer
        # True for one arm of an expanded variant table: chiselling RESHAPES a block you
        # already have rather than obtaining one. A real edge -- the solver may walk it and
        # the ranker must price it -- but not evidence that the material can be got, which
        # is a distinction `cost` needs and nothing else does. Set by
        # index.expand_interconversion, never by an extractor. See #110.
        self.variant = variant
        # The ground on which this recipe is not a production route, or None: a random loot
        # table, or a JEI card explaining how to automate something. The graph may WALK it and
        # the ranker must price it -- the JEI card is real information -- but it is never
        # evidence that its outputs can be obtained this way.
        #
        # DERIVED, NOT PERSISTED, and it is the one flag on this class that is. `to_json`
        # deliberately omits it: the rule reads the per-world token map, so a value written
        # into the graph would be a stale answer to a question the reader can settle in under
        # a second. Set by `notproduction.mark`, never by an extractor and never by a loader.
        # See that module's header for the three reasons, and DO NOT add it to `to_json`
        # without answering them.
        self.not_production = None

    def to_json(self):
        return {
            "id": self.rid,
            "src": self.source,
            "cat": self.category,
            "out": [{"key": k, "qty": q} for k, q in self.outputs],
            "in": [i.to_json() for i in self.inputs],
            **({"machine": self.machine} if self.machine else {}),
            **({"xf": 1} if self.transfer else {}),
            **({"var": 1} if self.variant else {}),
        }

    @staticmethod
    def from_json(d):
        return Recipe(
            d["id"], d["src"],
            [(o["key"], o["qty"]) for o in d["out"]],
            [Ingredient.from_json(i) for i in d["in"]],
            d.get("cat", "crafting"), d.get("machine"), bool(d.get("xf")),
            bool(d.get("var")),
        )


class Graph:
    """Recipes plus the produced-by index and display names."""

    def __init__(self):
        self.recipes = []
        self.names = {}              # key -> localized name
        self.ore_members = {}        # ore name -> [item keys]
        self.ore_guessed = set()     # subset of ore_members inferred, not authoritative
        # category uid -> [machine item keys], from JEI's own "made in" list. The
        # authoritative category->machine mapping; without it machine availability has to
        # guess from the category's display title, which is often the recipe type rather
        # than the machine. Empty on a graph built before the dump mod emitted catalysts.
        self.catalysts = {}
        # category uid -> the mod's DISPLAY name, from JEI's IRecipeCategory.getModName().
        # Grouping and display only. NOT a registry modid: "Industrial Foregoing" will
        # never match `industrialforegoing:plant_gatherer`, and machine identification must
        # keep using `machines.same_mod` on the uid. See sources/dump_meta.category_mods.
        self.category_mods = {}
        # item key -> [dimension id, dimension name] for an ore exactly one dimension
        # generates. SOURCED FROM planetDefs.xml ALONE, with JEResources' world-gen.json
        # allowed only to WITHDRAW an entry it saw in the overworld (#248; #112 built the
        # field). The observational source may never ADD one, because that would rest on an
        # absence in it -- see `dimensions.overworld_keys`. PACK DATA, not a name guess, and
        # the static half of #112: what only grows THERE cannot change
        # without the pack changing. Whether you have BEEN there is world state and lives in
        # the have file, so the two never have to be rebuilt together. Empty for a pack that
        # ships neither file, which behaves exactly as before.
        self.dimension_ores = {}
        # item key -> [dimension id, dimension name] for an ore NO pack source ever places
        # in the overworld. #248. Read from the UNION of both sources, which `dimension_ores`
        # deliberately is not: a wrong toll costs `cost.OVERWORLD_TOLL` and a wrong gate costs
        # `cost.DIMENSION_COST`, so the two are held to different evidence standards and are
        # NOT two views of one map. A different claim, and wider in practice: that
        # one says "you cannot get there yet", this one says "there is a portal on the route
        # and there always will be". The gate lifts on first visit; this never does, which is
        # why an ore behind a portal must keep losing to the identical ore you can walk to
        # after you have been to the Nether a thousand times.
        #
        # THE DIMENSION IN THE VALUE IS FOR THE READER ONLY. The toll is a flat per-ore term
        # (`cost.OVERWORLD_TOLL`) and does not vary by destination, and when an ore generates
        # in several off-world dimensions the one named here is arbitrary. DO NOT start
        # varying the toll by it without first fixing that -- see `dimensions.offworld_keys`.
        #
        # Empty on any graph built before #248, which behaves exactly as before: no toll,
        # and every iron ore in the pack ties at BASE_RAW_COST.
        self.offworld_ores = {}
        # The subset of `dimension_ores` that is a SECOND id for an ore already in it, from
        # `dimensions.shadow_ores`. Kept as well as folded in, because the two answer
        # different questions and #168 needs both: `dimension_ores` says "a trip is priced
        # into this key", and this says "this key is not the block that generates -- its
        # twin is". Only the first prices a plan; only the second lets a search tell a decoy
        # from the 19 barren ores in this pack that are perfectly real.
        #
        # PERSISTED RATHER THAN RECOMPUTED, and that is the point rather than an
        # optimisation. It is derived from pack files (planetDefs.xml, world-gen.json,
        # crafttweaker.log) that only `index.build` has in hand, so a consumer that re-derived
        # it would need
        # the instance directory and would be a second spelling besides. #19 Phase 6 deletes
        # the Python search page and the in-game planner has no search surface yet; carrying
        # the answer in graph.json is what stops that search being born with this bug.
        # Empty on any graph built before #168, which behaves exactly as before.
        self.shadow_ores = {}
        # item key -> maxDamage, for items whose META IS DURABILITY rather than a subtype.
        # From the item registry via the dump, because nothing structural can tell 46 damage
        # values of one Iron Axe from 9 genuinely distinct `chisel:lapis` blocks -- see
        # sources/damageable and #118. Empty on a pre-schema-5 graph, which behaves exactly
        # as before: every damage value stays its own row.
        self.max_damage = {}
        # Modular Machinery's own {machine registry name: localized name}, and
        # {blueprint key: machine registry name}. Two maps because the blueprint key holds
        # an NBT digest and churns with every dump while the registry name does not. See
        # sources/machine_names and #55.
        self.machine_names = {}
        self.blueprint_machines = {}
        # item key -> ProjectE EMC value. PACK DATA; what the player has LEARNED is world
        # state and lives in the have file. See sources/emc and #50.
        self.emc = {}
        # The item icon atlas index: {"icon", "cols", "pages", "keys"}, where `keys` maps a
        # base item key to [page, column, row]. The PNG pages themselves travel BESIDE
        # graph.json rather than inside it, because base64 in a JSON document would inflate
        # them by a third and make every graph load pay for pictures it is not going to
        # draw. See sources/icons and #36.
        self.icons = {}
        # Which dump schema produced this graph, 0 for none. Recorded because some
        # judgements are only SAFE once the data supports them: see
        # machines.SPECIES_SCHEMA, where "bee breeding needs no machine" has to wait for
        # a dump that can tell one bee from another.
        self.dump_schema = 0
        # The mod version that wrote the dump, None for a graph built without one (or by
        # a mod older than 0.4.2, which did not stamp itself). Kept ALONGSIDE the schema
        # rather than derived from it: the schema says what shape the files were, the
        # version says which build produced them, and a bug fixed in the mod moves one
        # and not the other. Shown in the UI footer so "which mod wrote the graph I am
        # looking at" is answerable without a terminal. See #38.
        self.dump_version = None
        # How many item keys the dump could not read a display name for, None for a graph
        # built from a dump older than schema 6 that never counted. NONE IS NOT ZERO here:
        # zero is a dump that measured and lost nothing, None is a dump that cannot say, and
        # collapsing them would let every pre-#194 graph claim a clean bill. Carried on the
        # graph so `serve` can say it too -- the dump directory is long gone by then, and a
        # loss reported only by the build that noticed it is a loss nobody sees twice.
        self.dump_names_failed = None
        # WHICH JARS THE DUMP SAW, as a count and as a digest of the sorted modid set. #194
        #
        # The one thing a dump could not previously say about itself, and the reason #119's
        # parity gap ran for months on quoted numbers: a graph built from five jars printed a
        # provenance line identical IN FORM to one built from all 367, and the contents could
        # not settle it either -- a client-only mod registering no JEI category is invisible
        # in the output. #208 settled that argument at 0 keys, and it had to do it by walking
        # the two jar sets on the desktop, because no artifact in the pipeline could be asked.
        # This is what makes the next such question answerable from the artifact. Both None
        # for a graph built from a dump older than schema 6, which is every graph on disk.
        self.dump_mod_count = None
        self.dump_mod_digest = None
        # The instance this graph was built from. Persisted so `serve` can find the dump
        # directory and rebuild itself without the user passing --instance again; a tool
        # that already knows the answer should not ask.
        self.instance_dir = None
        # Modular Machinery structures, {registryname: entry}, from the pack's own config.
        # CARRIED IN THE GRAPH RATHER THAN READ WHERE IT IS USED, because the deployment ships
        # graph.json alone: the server that answers plans has no pack instance to read
        # config/modularmachinery/ from. See multiblocks.parse and #93.
        self.multiblocks = {}
        self._invalidate()

    def _invalidate(self):
        """Drop every derived index. The ONLY place that list is written down.

        It used to be spelled out at each call site, and `index.build` reset three of the
        five after dropping ~226k non-recipes, leaving `_ore_index` and `_labels` holding
        entries for recipes that no longer existed. Adding an index meant finding every
        site and the fourth one got missed.
        """
        self._by_output = None
        self._by_input = None
        self._by_rid = None
        self._ore_index = None
        self._world_ores = None
        self._labels = None
        self._live_keys = None
        self._unsourced_keys = None
        self._pack_authored_unsourced = None
        self._produced_in_name_only = None
        self._variant_index = None
        self._variant_subsumption = None
        self._reshaped_only = None
        self._material_forms = None
        self._meta_index = None
        self._fluid_names = None
        self._producer_cache = {}
        # The token map `mark_non_production` last ran against, or None for "never". Cleared
        # here with the rest because the rule reads `by_output`: a graph whose recipes changed
        # has to be re-marked, not left holding a verdict about a producer set that is gone.
        self._non_production_signature = None
        self._non_production_counts = None

    def mark_non_production(self, token_kinds):
        """Set `Recipe.not_production` for this token map, once, and report the counts.

        MEMOISED ON THE TOKEN MAP, so the two callers that need it -- the cost relaxation and
        the solver -- can each ask without either having to know whether the other ran first.
        A DIFFERENT map re-marks rather than short-circuiting, which is what makes a server
        that reloads `data/tokens.json` mid-session give the new answer instead of the union
        of two.

        THE COUNTS ARE MEMOISED WITH THE MARKS, so a caller that only wants the report -- the
        `stats` command -- gets the same numbers as the plan that ran before it rather than
        None on the second call.

        `token_kinds` only. A caller overriding the declared loot categories -- which is
        tests, and nothing else -- calls `notproduction.mark` directly, because memoising on
        a signature that did not include them would serve one pack's verdict for another's.
        """
        from . import notproduction

        signature = tuple(sorted((token_kinds or {}).items()))
        if self._non_production_signature != signature:
            self._non_production_counts = notproduction.mark(self, token_kinds)
            self._non_production_signature = signature
        return self._non_production_counts

    def add(self, recipe):
        self.recipes.append(recipe)
        self._invalidate()

    @property
    def by_output(self):
        if self._by_output is None:
            idx = {}
            for r in self.recipes:
                for key, _qty in r.outputs:
                    idx.setdefault(key, []).append(r)
            self._by_output = idx
        return self._by_output

    @property
    def by_input(self):
        """key -> recipes that consume it. Built lazily; 'used in' is the reverse
        direction of the same edges, so it must be invalidated with by_output."""
        if self._by_input is None:
            idx = {}
            for r in self.recipes:
                seen = set()
                for ing in r.inputs:
                    for alt in ing.alternatives:
                        if alt not in seen:
                            seen.add(alt)
                            idx.setdefault(alt, []).append(r)
            self._by_input = idx
        return self._by_input

    @property
    def by_rid(self):
        """rid -> [recipes carrying it]. The index a "show me that recipe" lookup needs.

        A LIST RATHER THAN A RECIPE, because the rid is the dump's own id and nothing
        enforces that it is unique: two JEI categories can hand back the same wrapper id, and
        a dict keyed rid->recipe would silently serve whichever was added last while looking
        authoritative. Returning both is the honest shape, and `api` reports the count.
        """
        if self._by_rid is None:
            idx = {}
            for r in self.recipes:
                idx.setdefault(r.rid, []).append(r)
            self._by_rid = idx
        return self._by_rid

    @property
    def ore_index(self):
        """item key -> [ore names it belongs to].

        Built once. `consumers` previously scanned all 3,114 oredict entries per call,
        which is fine for one lookup and hopeless for search-as-you-type across 40 results
        per keystroke. Invalidated with the other indexes, since ore membership is loaded
        alongside recipes.
        """
        if self._ore_index is None:
            idx = {}
            for ore, members in self.ore_members.items():
                for member in members:
                    idx.setdefault(member, []).append(ore)
            self._ore_index = idx
        return self._ore_index

    def ores_of(self, key):
        return self.ore_index.get(key, ())

    @property
    def world_ores(self):
        """Every key the pack itself registered under an `ore*` oredict group.

        The one signal in today's graph that separates "you go and mine this" from "the
        dump listed a decorative block", which is issue #61. Forge convention is that a
        block you find in the world is registered as `oreDiamond`, `oreLapis`,
        `oreQuartz`; nothing registers a microblock panel or a loot token that way. So
        this is PACK-DECLARED DATA, not a guess at what a registry name looks like: the
        mods called these ores, we are only reading it back.

        The `ore` prefix is load-bearing and the rest of the oredict is deliberately
        excluded. `chisel:diamond` is a member of `blockDiamond`, so accepting every
        group would readmit exactly the decorative blocks this exists to demote, and
        measured on the reference graph it is the difference between 39 corrected routes
        and none.

        Two consumers, and they ask different questions of the same set: `Solver.ore_backed`
        ranks with it, and `cost._seed` prices an ore at `BASE_RAW_COST` whether or not
        something claims to produce it (#106).
        """
        if self._world_ores is None:
            self._world_ores = {
                member
                for ore, members in self.ore_members.items() if is_world_ore_group(ore)
                for member in members
            }
        return self._world_ores

    @property
    def reshaped_only(self):
        """Keys nothing can make except by reshaping another form of themselves.

        Every producer is a `variant` recipe -- one arm of an expanded chisel table -- so
        the graph knows how to CONVERT this key but not how to obtain any of it. #110's
        second half depends on the distinction: expanding a table gives its members
        producers, and `cost._seed` hands `BASE_RAW_COST` only to keys nothing produces, so
        without this a group whose members are all leaves becomes a closed cycle with no
        base case and every member prices at infinity. Measured on the reference dump: 327
        keys went from a finite price to unreachable, `abyssalcraft:abybrick` among them.

        A key with even one ordinary producer is NOT here, however unreachable that
        producer turns out to be: its unreachability is a real statement about the pack
        rather than an artifact of this expansion, so it gets no fallback of its own. It
        can still end up priced THROUGH the group, one chisel from a sibling that did get
        one, and that is intended. A chisel group is an equivalence class, so assuming any
        member obtainable assumes all of them are; what the distinction buys is that the
        assumption enters at exactly one place, on the keys that have no other story.
        """
        if self._reshaped_only is None:
            self._reshaped_only = {
                key for key, made_by in self.by_output.items()
                if all(r.variant for r in made_by)
            }
        return self._reshaped_only

    @property
    def material_forms(self):
        """`{material: {form: [keys]}}` for every `<form><Material>` oredict group.

        WHAT THIS IS FOR. #136 reported a plan asking for 18 Sednanite Nuggets, a key nothing
        in the pack makes, because `cost._seed` prices anything with no producer at
        `BASE_RAW_COST` on the rule "nothing makes it, so assume you can go and get one".
        That rule is wrong for a PROCESSED form: a nugget is a shape of a material, not a
        thing in the world.

        BOTH HALVES NOW READ THIS. `reachable_form` below names the form the graph CAN make,
        so the shopping list says "the graph can only make Sednanite Ingot" instead of listing
        a nugget beside 128 Granite as though it were an ordinary thing to fetch. #176 then
        priced that same set at `UNSOURCED_COST`, so it steers routing as well as reporting.
        The earlier note here saying the price was "deliberately not" this signal described
        the three repricing rules #136 measured and rejected, all of which keyed on something
        else -- see the rejection list on `cost.BASE_RAW_COST`.

        THE FAMILY LINK IS PACK DATA, the same class of signal as `world_ores`. The pack
        chose to register `nuggetSednanite` and `ingotSednanite`, and Forge's convention is
        what makes those two the same material. It is not an inference from the registry id,
        which is the thing #61 rejected four separate heuristics for attempting.
        """
        if self._material_forms is None:
            out = {}
            for group, members in self.ore_members.items():
                split = split_ore_group(group)
                if not split:
                    continue
                form, material = split
                out.setdefault(material, {}).setdefault(form, []).extend(members)
            self._material_forms = out
        return self._material_forms

    def obtainable_sibling(self, key):
        """Another form of `key`'s material that the graph CAN make, or None.

        DETERMINISTIC, because this reaches a plan tree and `tests/fixtures/plan/*.json`
        freezes those for the Java port: ordered by producer count descending then by key,
        so the answer is "the form the pack actually makes" and never depends on dict order.

        Returns None when the material has no makeable form, which is the honest answer --
        a family where nothing is obtainable gives the reader nothing to be pointed at, and
        that is exactly the case `reachable_form`'s docstring refuses to badge.

        A `block<Material>` KEY ASKS THE SAME QUESTION and gets the same answer, which is the
        second half of #136: an unpressable storage block is as unobtainable as a nugget that
        cannot be split. Only the question widens. The material is looked up in
        `material_forms`, which still excludes `block`, so the form NAMED is never a block and
        the decorative-block argument on `PROCESSED_FORM_PREFIXES` is untouched. See
        `storage_form_material`.

        A PROCESSED GROUP WINS OVER A STORAGE ONE when a key carries both, so nothing that
        resolved before this resolves differently now.
        """
        material = None
        for group in self.ores_of(key):
            split = split_ore_group(group)
            if split:
                material = split[1]
                break
        if material is None:
            for group in self.ores_of(key):
                material = storage_form_material(group)
                if material:
                    break
        if material is None:
            return None
        best = None
        for _form, members in sorted((self.material_forms.get(material) or {}).items()):
            for member in members:
                if member == key:
                    continue
                made = len(self.real_producers(member))
                if made and (best is None or (-made, member) < best[0]):
                    best = ((-made, member), member)
        return best[1] if best else None

    def reachable_form(self, key):
        """The other form of `key` the graph CAN make, when `key` itself is unsourced.

        None otherwise, which is the common case and the whole reason this is narrow.

        ON `Graph` AND NOWHERE ELSE, BECAUSE TWO SPELLINGS OF IT ALREADY DRIFTED. This lived
        on `Solver` with a hand-kept copy in `api._reachable_form`, whose docstring said the
        copy existed because "a sweep answers questions about a GRAPH, and a solver carries
        an inventory, pins and a cost table it has no business needing" -- correct about the
        dependency and wrong about the remedy, because the right conclusion from "this is a
        question about a graph" is to put it ON the graph. #136 and #170 each added a branch
        to the solver's copy and neither touched api's, so `/api/sweep` under-reported
        `unsourced` on two of the four shapes below while a test named
        `TheTwoPredicatesAgreeTest` passed -- it compared them on NBT variants only, which is
        the one branch nobody changed. DO NOT reintroduce a second spelling; a caller that
        must not import `solve` can import this.

        WHY NOT "NOTHING PRODUCES IT", WHICH IS THE OBVIOUS RULE. Cobblestone has no producer
        either. Marking every producerless leaf would badge most of a shopping list, and a
        mark that fires on almost everything carries no information -- which is the failure
        #136 measured for every rule keying on `producers == 0` alone.

        WHY NOT `base_key(key) != key` ON ITS OWN. That matches 47,417 keys on the reference
        graph, and the top of that list is every Forestry bee species -- `bee_drone_ge#...`
        Forest Drone, consumed by 247 recipes. A Forest Drone with no producer is CORRECT and
        unremarkable: you get one out of a hive. What makes the data-model tier different is
        the second clause, that the graph demonstrably CAN make the plain item -- so the plan
        is resting on a state it has no route to, and there is a specific other form to point
        the reader at. Without something to name, the mark would just be "no recipe", which
        the NEED badge already says.

        A PLAIN KEY NOTHING MAKES IS COVERED TOO, on the same terms and no looser. The rule
        used to stop at NBT states because "there is no other form to name" -- correct at the
        time, and #136's measurement supplied the missing name. The pack registers
        `nuggetSednanite` and `ingotSednanite`, so Forge's own convention says those are one
        material, and the ingot has 27 producers. There IS a specific other form to point at,
        so the second clause is satisfied exactly as the NBT case satisfies it.

        It stays narrow for the same reason. Cobblestone is in no `<form><Material>` group
        and is not badged. A mob drop is not badged. A material whose every form is
        unobtainable is not badged, because then there is nothing to name and the mark would
        collapse to "no recipe", which the NEED badge already says.

        A STORAGE BLOCK IS ONE MORE FACE OF THE SAME RULE, and it is where #136 finished. With
        the nuggets priced, a plan for Myrmitite Ingot asked for `1 Block of Myrmitite` --
        producerless, unstocked, generated nowhere in the world, and priced at `BASE_RAW_COST`
        for the same reason the nugget was. `obtainable_sibling` answers for it through
        `storage_form_material`; the second clause and the wildcard guard between them keep 36
        of the 50 candidates out, world glasses included, and that census is on that function.

        NOT DISPLAY-ONLY ANY MORE, AND THAT IS #176. This docstring used to say the mark was
        display-only "and that is the whole bargain", on the grounds that every repricing rule
        #136 measured made the plan worse. Those three rules keyed on the material's world ore
        or on dropping the seed; #176 priced THIS set -- the one with positive evidence the
        graph cannot explain the route -- at `UNSOURCED_COST`, measured clean, and it is what
        made the reported plan stop asking for a nugget. So a key arriving here both gets the
        badge and gets that price.

        AND #170 CLOSED THE ROUTING QUESTION THIS USED TO LEAVE OPEN. The sentence that stood
        here said `Solver`'s refusal to route a bare demand through a produced variant was still
        open; it is not, and leaving that would send the next reader to look for a refusal that
        no longer exists. `variant_subsumption` routes it, one direction only and behind four
        clauses, and `cost._relax` prices the bare key at what making the variant costs.

        SO THIS IS A REPORT AND A PRICE FLOOR, WHICH IS TWO JOBS AND THE SECOND IS EASY TO
        BREAK. `unsourced_keys` seeds every key this names at `UNSOURCED_COST`, and #170 lowers
        only the variant face of it. The floor is therefore what stands underneath every key
        #170 cannot route -- a bare key whose only variant is unreachable, and the processed
        forms and storage blocks above, which nothing routes at all. DO NOT narrow this
        predicate to exclude keys the solver can now route: they would stop being seeded, fall
        back through `_seed`'s leaf rule to `BASE_RAW_COST`, and any the relaxation failed to
        reach would be priced 1.0 again, which is the whole of #170's original report.
        """
        if self.real_producers(key):
            return None
        # A KEY THE PACK CALLS AN ORE IS OBTAINABLE, WHICH OUTRANKS ANYTHING THIS PREDICATE
        # CAN SAY. `world_ores` is the pack's own `ore*` registration and `cost._seed` reads
        # it as a CEILING -- "mining is what this costs at worst". The unsourced seed runs
        # after that loop and `max`es prices up, so a badged world ore would be priced ABOVE
        # the ceiling its own rule promises, and `Solver.expand` would never show it: the
        # world-ore branch there returns "mined, not crafted" before this mark is ever
        # consulted. So the badge would appear only in `/api/sweep` and `/api/cost`, which is
        # exactly the surface-to-surface divergence #178 spent a PR removing.
        #
        # A GUARD RATHER THAN A FIX TODAY, measured: 0 world ores reach this on the reference
        # graph, and 0 carry a `block*` group at all. It is here because the storage-form
        # widening above is what makes co-registration reachable, and because a rule stated
        # once is cheaper than the same measurement done again.
        if key in self.world_ores:
            return None
        # A WILDCARD META HAS NO PRODUCERS BY CONSTRUCTION, so its count is not evidence of
        # anything. `Graph.producers` gathers `base:*` for a concrete meta and never the
        # other way round, so `natura:sticks:*` comes back empty while `natura:sticks:0` is
        # perfectly craftable. Measured: without this the first regeneration badged
        # "nothing makes this form" on Maple Sticks and pointed the reader at Sawdust. The
        # same inflation `material_forms` flags for `ore:` group keys, which are
        # producerless for the same structural reason.
        if split_key(key)[1] == "*":
            return None
        stem = base_key(key)
        if stem != key:
            # A STATE of a producible item: #139's half.
            return stem if self.real_producers(stem) else None
        # A BARE key nothing makes, while a VARIANT of it IS made: #170's half, and the
        # third face of one subsumption rule. `animus:kama_bound` is consumed by four
        # recipes and produced by none, while the Alchemy Array makes
        # `animus:kama_bound#fd1adc426e12`. When this branch was written `cost._seed` priced
        # the bare key at BASE_RAW_COST and the plan told the player they already had it,
        # 99 keys on the reference graph and the worst underpriced by a factor of 7,277.
        #
        # IT IS ALSO ROUTED NOW, AND THIS IS STILL WIDER THAN THE ROUTE. #170 shipped
        # `variant_subsumption`, so `Solver.expand` plans a bare demand through the variant's
        # recipe and `cost._relax` prices the bare key at what making the variant costs. What
        # reaches here is therefore either a key the route could not be taken for -- every
        # variant made FROM the bare key, or none of them reachable -- or a caller that is not
        # the planner, `/api/sweep` and `/api/cost` being the two. Both still need the answer,
        # and `unsourced_keys` above records why the floor must not be narrowed to match the
        # route. `producers` itself remains un-widened: the direction #28 refused, a demand
        # for `X#d` satisfied by bare `X`, is the reverse of the one that shipped.
        made = [v for v in self.variant_index.get(key, ())
                if self.real_producers(v)]
        if made:
            # Deterministic and meaningful: `variant_index` is insertion-ordered off
            # `by_output`, and the cheapest-to-name answer would need prices the reporter
            # does not have. First produced variant, which is the first one the dump saw.
            return made[0]
        return self.obtainable_sibling(key)

    @property
    def labels(self):
        """Every searchable key -> its display label.

        items.csv covers items only, so a fluid that no recipe outputs into a container was
        invisible to search: "Boric Acid" found `nuclearcraft:fluid_boric_acid` (the placed
        block, no recipes) while `fluid:boric_acid` -- the thing the chemistry chain
        actually needs -- could not be found at all. Fluids, essentia and oredict names are
        therefore collected from the recipes themselves.

        Holds the BARE name, not `display`. Indexing the bracketed form made `fluid:water`
        match the query "water" only as a substring of "[fluid] water", so it ranked below
        every item merely containing the word and "Water Egg" came first.

        Built once and invalidated with the other indexes.
        """
        if self._labels is None:
            out = dict(self.names)
            for index in (self.by_output, self.by_input):
                for key in index:
                    if key.startswith(NON_ITEM_PREFIXES) and key not in out:
                        out[key] = self.bare_name(key)
            self._labels = out
        return self._labels

    @property
    def fluid_names(self):
        """`{fluid_key: display_name}` recovered from the containers each fluid is sold in.

        DERIVED AT RUNTIME RATHER THAN BAKED INTO graph.json, unlike `multiblocks`, and the
        difference is where the inputs live. Multiblock structures come from the pack's
        config, which the deployment does not ship, so they have to travel inside the graph.
        These come from recipes the graph already holds, so baking them would buy nothing and
        cost a rebuild. A derivation that needs no rebuild reaches the running container on a
        redeploy.

        THE REASON THIS USED TO GIVE WAS FALSE, and it is recorded rather than deleted because
        it was cited elsewhere: it claimed a rebuild "has to happen on the desktop, whose
        instance has ~410 jars against the server's 364". Measured 2026-08-03: the client has
        367 jars, the server 364, and the 364 shared ones are byte-identical -- the 3 extras
        are two client cosmetics with no `assets/*/recipes/` entries at all plus our own dump
        mod, so `jar_json` yields the same 10,301 recipes and 8,784 produced keys from either
        set. A rebuild is pinned to the desktop by the DUMP (`recipes.ndjson` needs a running
        game), never by jar parity. See #119.

        Costs 0.10s over 117,681 recipes, against a 4.4s graph load. See fluidnames and #103.
        """
        if self._fluid_names is None:
            # Seeded BEFORE the derivation, not after. `fluidnames.derive` calls back into
            # `bare_name`, and `bare_name` reads this property: a container base that ever
            # named a `fluid:` key would recurse until the stack died. The curated list makes
            # that structurally impossible today, and one assignment makes it impossible
            # regardless -- a re-entrant read sees {} and falls through to `_prettify`.
            # Imported here, not at module scope: `fluidnames` reads FLUID_PREFIX from
            # this module, and a top-level import each way is a cycle.
            from . import fluidnames

            self._fluid_names = {}
            self._fluid_names = fluidnames.derive(self.recipes, self.bare_name)
        return self._fluid_names

    @property
    def unsourced_keys(self):
        """Every live key `reachable_form` names another form for. 47,674 on the reference.

        THE COST MODEL'S HALF OF #139/#136/#170, and the reason it is a set rather than a
        predicate call inside `cost._seed`: the seed loop would otherwise run the whole
        enumeration once per key instead of once per table. Cached on the graph the way
        `reshaped_only` and `variant_index` are, because `estimate` is not the only caller --
        `/api/sweep` and `/api/cost` ask the same question of the same graph, and a graph
        outlives any one of them.

        NOT "BECAUSE `estimate` SEEDS TWICE", which an earlier version of this docstring said
        and `Unsourced.keys` repeated. `estimate` calls `_seed` ONCE and hands `dict(seed)` to
        each of the two relaxations, so the second pass re-uses the first pass's seed rather
        than recomputing it. The justification above does not depend on that being wrong or
        right, which is why it is stated separately.

        WHAT THESE KEYS ARE. `reachable_form` returns non-None only when nothing makes this
        exact key AND the graph demonstrably makes another form of it -- another NBT state, a
        processed form of the same material, or the same item under an NBT tag. So the graph
        has positive evidence it cannot explain this route, which is a stronger and much
        narrower claim than "no producer". See `reachable_form` for why the second clause is
        what keeps it worth reading.

        OVER `live_keys`, NOT `by_input`, AND THE DIFFERENCE IS 39 KEYS. Only a consumed key
        can change a recipe's price, so restricting to `by_input` would price every route
        identically and cost the same 0.4 seconds. The 39 are keys nothing consumes -- they
        reach no plan, and they DO reach `/api/sweep` and `/api/cost`. Pricing a key one way
        in the table and another way in the sweep is the drift #178 spent a PR removing.
        """
        if self._unsourced_keys is None:
            self._unsourced_keys = frozenset(
                key for key in self.live_keys
                if not self.by_output.get(key) and self.reachable_form(key))
        return self._unsourced_keys

    @property
    def pack_authored_unsourced(self):
        """Pack-authored keys nothing makes and `reachable_form` can name no other form for.

        A THIRD POPULATION UNDER THE CLAIM THE OTHER TWO SHARE, and a third set for the
        reason `produced_in_name_only` is a second one: the claim is "the graph has POSITIVE
        EVIDENCE it cannot explain where this comes from", and the three cannot merge because
        their clauses contradict. `unsourced_keys` needs `by_output` EMPTY and
        `reachable_form` NON-None; `produced_in_name_only` needs `by_output` NON-empty; this
        needs `by_output` empty and `reachable_form` None. All three intersections are empty
        by construction, so `cost._seed` can walk them in any order.

        WHAT MAKES THE EVIDENCE POSITIVE HERE, since "nothing produces it" is the rule
        `reachable_form` refuses by name. Cobblestone has no producer either, and marking
        every producerless leaf carries no information. The evidence is that the PACK ITSELF
        authored the item, in its CraftTweaker namespace, and then wrote no way to obtain it
        while writing recipes that consume it. A mod shipping an ore does that because the ore
        is in the ground; a pack script doing it has written an annotation, a quest gate or a
        reward it hands out by some mechanic the dump cannot see. `#171` is the umbrella and
        this is its failure mode 1: 46 recipes routed through a JEI tooltip priced at
        `BASE_RAW_COST`, the cheapest value in the model.

        THE TWO EXCLUSIONS ARE THE WHOLE DIFFICULTY, and both are pack-declared data read
        back rather than guesses at the shape of a key. Measured on the reference graph over
        the `contenttweaker` keys with no producer and at least one consumer:

          * `damage_base(key) != key` drops 837 of 1,120 (75%). They are armour durability
            variants -- `bloodmaster_metal_chest:28400` and its siblings -- whose UNDAMAGED
            key is produced perfectly well. Nothing about them is unsourced.
          * `ores_of(key)` non-empty drops a further 47, and this is the exclusion that has
            to be ANY oredict group rather than `world_ores`. Only 11 of the 47 are ores.
            The other 36 are 21 nuggets, 13 storage blocks and 5 foods, and among the nuggets
            is `contenttweaker:material_part:53`, the Sednanite Nugget -- the item #136 was
            filed about. A rule keyed on `world_ores` demotes it and 35 other real materials
            to fix the markers, which is the #117/#168 lesson repeating: an over-broad rule
            costs more real items than it fixes fake ones.

        WHAT SURVIVES BOTH IS 283 KEYS, and the retention test is what makes the filter
        trustworthy rather than merely narrow: all 37 hand-verified `DEFAULT_TOKENS` are
        inside it and none of the controls are. It is still not a marker DETECTOR -- 246 of
        the 283 are uncurated and some are genuine puzzle rewards -- which is exactly why
        the price is `UNSOURCED_COST` and not a token kind. "The tool cannot explain how you
        get this" is true of every one of them; "this is a loot drop" is not.

        SO CURATION STILL DECIDES, AND DETECTION ONLY NARROWS. `tokens.candidates` offers
        this same population for a human to classify, and a curated token overrides this
        price with its more specific kind -- see `cost._seed`, where the token seed runs last
        precisely so it wins.
        """
        if self._pack_authored_unsourced is None:
            packs = tuple("%s:" % ns for ns in tokens.TOKEN_NAMESPACES)
            self._pack_authored_unsourced = frozenset(
                key for key in self.live_keys
                if str(key).startswith(packs)
                # `producers`, NOT `by_output`, AND THE INVERTED CLAUSE BELOW IS WHY.
                # `unsourced_keys` tests `not by_output.get(key)` and gets away with it
                # because its SECOND clause calls `reachable_form`, which widens through
                # `real_producers`, finds a wildcard-meta producer and returns None -- so a
                # key made only by a `mod:name:*` output is excluded there anyway. This set
                # requires `reachable_form` to be None, so that rescue runs backwards: such a
                # key would satisfy every clause and be called unsourced while a recipe
                # plainly makes it. `producers` widens, and `not producers(key)` implies
                # `not by_output.get(key)`, so this is the strictly safer spelling.
                # Java's `hasProducers` already widens, so this is also what keeps the two
                # ports agreeing rather than relying on the fixtures to notice.
                and not self.producers(key)
                and not self.reachable_form(key)
                and not self.ores_of(key)
                and self.damage_base(key) == key)
        return self._pack_authored_unsourced

    @property
    def produced_in_name_only(self):
        """Keys some recipe OUTPUTS while `real_production` says none of them makes it.

        120 on the reference graph, every one a fluid, `forestry.squeezer` in nearly all of
        them: `fluid:sewage`, `fluid:liquid_uu_matter`, `fluid:fluidoil`. The graph lists
        producers and every one of them is a container empty, which is circular -- see
        `real_production` for the uranium-can edge that argument was paid for with.

        THE SAME CLAIM AS `unsourced_keys` AND A DIFFERENT POPULATION, which is why it is a
        second set rather than a widening of that one. Both mean "the graph has POSITIVE
        EVIDENCE it cannot explain where this comes from", so `cost._seed` charges both
        `UNSOURCED_COST`. They cannot be one set: `unsourced_keys` requires `by_output` EMPTY
        and this requires it non-empty, so the intersection is zero by construction, and its
        second clause needs `reachable_form` to name another form the graph CAN make. Measured:
        `reachable_form` is None for all 120, because a fluid has no meta sibling, no NBT
        variant and no `<form><Material>` group. There is nothing to name, so #139's badge
        correctly stays off and these keys cannot enter that set.

        WHY THE PRICE IS NOT `BASE_RAW_COST`. `Solver.expand` reports these raw and
        shopping-lists them, so a raw leaf is the arithmetic that agrees with that verdict most
        literally -- and it is also the CHEAPEST value in the model, so it makes a route through
        a fluid the tool cannot source more attractive than any route it can account for. That
        is verbatim #176's defect in the one population #176's set cannot reach. What agreement
        with the plan requires is a FINITE price, which is the whole of the infinity #193
        reported; it does not require the cheapest one.

        THE ORDERING IS THE CLAIM, NOT THE MAGNITUDE, as for `UNSOURCED_COST` itself. Both arms
        were built and solved end to end on the reference oracle and they differ by very
        little: `fluid:nethengeic_fluid` plans 141 nodes and 41 shopping rows at `BASE_RAW_COST`
        against 152 and 43 here, and both shopping-list `fluid:liquid_uu_matter` and
        `fluid:meat`, because on that target there is no alternative to prefer.

        WHAT DOES MOVE THAT PLAN IS THE INFINITY GOING AWAY, under either arm, and the fixture
        set predicted it: `plan-fluid-chain`'s own note says #176 took its `work` from 400 to
        28,012 of an 80,000 budget "because the search no longer stops at 40 unsourced dead ends
        and instead explores the routes behind them", and warned that the headroom was thin. 553
        more keys stop being infinite here, the search behind them is explored, and the budget
        goes. See that target's `why` for how it was re-chosen.
        """
        if self._produced_in_name_only is None:
            self._produced_in_name_only = frozenset(
                key for key in self.by_output if not self.real_output(key))
        return self._produced_in_name_only

    @property
    def live_keys(self):
        """Keys some recipe or catalyst actually touches: 167,134 on the reference graph.

        Only 164,345 of them carry a label; the other 2,789 are keys a recipe names that
        items.csv never did, which is why `api.universe` unions this with `labels` rather
        than treating either as the whole key set.

        Of the 262,841 LABELS, 98,496 are DEAD: no recipe makes them, no recipe uses them,
        nothing names them as a machine. They cannot be planned or explored and can only
        push a real result down a search page -- six identical "Pluton Scythe" NBT variants
        buried Plutonium-238 through -242 on the first page of a search for `plut`. See #26.

        MUST STAY IN STEP WITH `producers` AND `consumers`, because a key hidden here while
        those two would have found recipes for it is an item that exists, works when linked
        to, and cannot be found. That is why the widenings below are not optional:

          * wildcard meta -- `producers`/`consumers` fall back to `base:*`, so every meta
            of a base some recipe wildcards is reachable;
          * oredict -- `consumers` reaches an item through any ore it belongs to, so a
            member of an ore some recipe consumes is reachable even if nothing names it;
          * catalysts -- 51 keys, `thermalexpansion:machine:1` among them, are named ONLY
            as a JEI catalyst. Their recipes output a discriminated variant instead (see
            `producers_any_variant` and #28), so the bare key has no edge of its own and
            hiding it would make the Pulverizer unsearchable.

        Deliberately NOT widened from a bare key to its produced variants. That would
        re-admit the duplicate rows this exists to remove, and the bare key stays reachable
        by direct link and from the machines page.

        Stock is not a graph fact and is applied by the caller: see `explore.rank_matches`,
        where an item you hold is never hidden however dead the graph thinks it is.
        """
        if self._live_keys is None:
            live = set(self.by_output) | set(self.by_input)
            for key in self.by_input:
                if key.startswith("ore:"):
                    live.update(self.ore_members.get(key[4:]) or ())
            for keys in self.catalysts.values():
                live.update(keys)
            wild_bases = {split_key(k)[0] for k in live if split_key(k)[1] == "*"}
            if wild_bases:
                for key in self.labels:
                    if key not in live and split_key(key)[0] in wild_bases:
                        live.add(key)
            self._live_keys = live
        return self._live_keys

    def consumers(self, key):
        out = list(self.by_input.get(key, ()))
        base, meta = split_key(key)
        if meta not in (None, "*"):
            out.extend(self.by_input.get("%s:*" % base, ()))
        # an item is also reachable through any oredict it belongs to
        for ore in self.ores_of(key):
            out.extend(self.by_input.get("ore:%s" % ore, ()))
        return out

    def producers(self, key):
        """Recipes producing `key`, including via a wildcard-meta output.

        Memoised: the solver scores every candidate of every node, so rebuilding this
        list per call dominated the search on a 340k-recipe graph.
        """
        cached = self._producer_cache.get(key)
        if cached is not None:
            return cached
        out = list(self.by_output.get(key, ()))
        base, meta = split_key(key)
        if meta not in (None, "*"):
            out.extend(self.by_output.get("%s:*" % base, ()))
        self._producer_cache[key] = out
        return out

    @property
    def variant_index(self):
        """base key -> [discriminated keys some recipe produces].

        Only produced keys, because the question this answers is "can this item be made
        in ANY NBT state", and an ingredient nobody makes cannot help answer it.
        """
        if self._variant_index is None:
            idx = {}
            for key in self.by_output:
                base, disc = split_discriminator(key)
                if disc is not None:
                    idx.setdefault(base, []).append(key)
            self._variant_index = idx
        return self._variant_index

    def variants_of(self, key):
        return self.variant_index.get(key, ())

    def producers_any_variant(self, key):
        """`producers`, widened to every NBT variant of `key`.

        For questions about the ITEM rather than about one NBT state of it. A JEI catalyst
        names `thermalexpansion:machine:1`, while every crafting recipe for a Pulverizer
        outputs `thermalexpansion:machine:1#f56885268ad5` because the level and augments
        live in NBT. Asking `producers` for the bare key finds nothing and the machines
        page concludes there is no route to a machine that is plainly craftable, which is
        16 Thermal Expansion categories and 3 Botania flowers on the reference pack. See
        #28.

        DELIBERATELY NOT what `producers` does. The solver asks a different question:
        "give me exactly this stack", and a Pulverizer with different augments is not a
        substitute for the one a recipe called for. Widening `producers` itself would let
        every plan satisfy any NBT-bearing ingredient with the wrong variant.
        """
        out = list(self.producers(key))
        if split_discriminator(key)[1] is None:
            for variant in self.variants_of(key):
                out.extend(self.producers(variant))
        return out

    @property
    def variant_subsumption(self):
        """`{produced variant key: the bare key a demand for it may be satisfied by}`. #170.

        THE ONE SPELLING OF THE BARE-TO-VARIANT RELATION, and `satisfying_variants` below is
        a view of this dict rather than a second implementation of its clauses. Two callers
        need it -- `cost._relax`, which lets a variant's production price the bare key, and
        `Solver.expand`, which routes a bare demand through the variant's recipe -- and this
        repository has already paid for that pair being written twice: `reachable_form` lived
        on `Solver` with a hand-kept copy in `api._reachable_form`, the two drifted by 210
        keys, and a test comparing them passed the whole time because it only ever compared
        the branch nobody had changed. See `reachable_form` and #178. DO NOT write a second
        copy of the clauses below, in this module or any other.

        THE RELATION IS DIRECTIONAL, AND THE DIRECTION IS THE WHOLE CLAIM. A recipe slot
        naming the BARE item carries no NBT to match on, so producing `animus:kama_bound#fd1a`
        satisfies a demand for `animus:kama_bound`; that is ordinary 1.12 ingredient matching.
        The REVERSE is false and stays refused: a slot naming `X#d` is matched by that stack
        and not by bare `X`, which is why `producers` is not widened (#28) and why the keys of
        this dict are variants while its values are bare. `reachable_form`'s first branch,
        #139's "no recipe reaches this state", is the same asymmetry seen from the other end.

        FOUR CLAUSES, AND THE MIDDLE TWO ARE WHAT KEEP IT NARROW:

          1. the key is a produced variant of a bare key, which `variant_index` already is;
          2. the BARE key has no producer of its own. This is the clause that excludes the
             control class, and the class is twenty times larger than the defect: 2,117 bare
             keys on the reference graph carry this relation with an honest route and an
             honest price of their own, and 82 of them have a produced variant CHEAPER than
             themselves -- `simplyjetpacks:itemjetpack:14` at 1,491.72 against a 2.5 variant.
             A rule keyed on the relation alone rather than on the absence of a route
             cheapens that by 597x and hands the solver a fake route to an endgame item;
          3. the VARIANT is produced, because an ingredient nobody makes cannot satisfy
             anything;
          4. no recipe producing the variant consumes the bare key OR ANY OTHER VARIANT OF
             IT. That is the `X -> X#d -> X` shape and its one-hop-longer form, and both are
             live: an Ender IO Soul Binder turns a Broken Spawner into a Broken Spawner with
             a mob in its NBT, and a Fluid Transposer fills a Creative Reservoir variant from
             another variant which was itself filled from the bare key. Attributing either to
             the bare key prices "get one" at what "get one and upgrade it" costs, which is a
             number about nothing. Those keys keep `reachable_form`'s report and its
             `UNSOURCED_COST` price, which is the honest answer for them.

        THE FAMILY HALF OF CLAUSE 4 IS THE CONTAINER GATE. `thermalexpansion:reservoir:32000`
        is the case that shows the harm: one recipe demands the bare key directly, and both of
        its produced variants are made by Fluid Transposer recipes that consume the family, so
        without this half it prices at 521.0 against the 2,000 floor -- a 4x understatement of a
        Creative Reservoir, delivered to a slot that really does ask for one. That is the shape
        `real_producers`' docstring records paying for once already, where the dump dropped the
        NBT telling one filled can from another and the graph believed squeezing a can of water
        yielded uranium fluoride. Excluding a variant made from its own family costs nothing,
        because the sibling it was made FROM is either subsumable itself and cheaper, or
        excluded for the same reason.

        `extratrees:drink` IS THE CLEAREST SHAPE AND THE WEAKEST HARM, and the distinction is
        worth keeping because an earlier version of this comment got it wrong. A Beer Mug,
        nothing makes the bare key, and all 30 produced variants are FILLED mugs and glasses a
        Transposer makes from an EMPTY mug variant -- so it teaches the rule better than the
        reservoir does. But it has ZERO direct consumers: its 4 are reached only through
        `ore:listAllFood`, a 564-member group, and even priced through its variants at 27.15 it
        is the 319th cheapest member, so `resolve_ore` would never pick it and no plan changes.
        The harm there is a wrong price on a key `/api/sweep` and `/api/cost` report and nothing
        demands, which is real and is not a routing failure. 10 of the 11 keys clause 4 refuses
        DO have a direct demand; this is the one that does not.

        MEASURED, NOT ASSUMED, on the reference graph: 2,631 bare keys carry the relation,
        514 have no producer, and 99 of those are consumed by some recipe -- the issue's 96.
        The clauses admit 88 of the 99: 9 are refused by the one-hop half of clause 4 and 2 by
        the family half.

        WHAT NO MEASUREMENT CAN SETTLE, so the design has to survive being wrong about it:
        whether a bare slot in a MOD MACHINE really accepts any variant. The dump reads
        recipes through JEI's `IIngredients`, which carries `ItemStack`s only, so the
        `Ingredient` object that IS the matcher never crosses the dump boundary, and exactly
        6 of 654,678 ingredient slots list both a bare key and a variant of it. For a vanilla
        crafting grid the answer is provable -- an NBT-sensitive slot is a Forge
        `NBTIngredient`, whose matching stack carries a tag and would therefore have dumped as
        `X#d`, so a bare demand there implies NBT-insensitivity -- which covers 167 of the 242
        sole-alternative demands. The other 75 are unfalsifiable. That is why `Solver.expand`
        offers these as ranked candidates a pin can overrule and names the variant it took,
        rather than treating the two keys as one item.
        """
        if self._variant_subsumption is None:
            out = {}
            for bare, variants in self.variant_index.items():
                if self.real_producers(bare):
                    continue
                family = {bare}
                family.update(variants)
                for variant in variants:
                    made = self.real_producers(variant)
                    if not made:
                        continue
                    if any(alt in family for r in made for ing in r.inputs
                           for alt in ing.alternatives):
                        continue
                    out[variant] = bare
            self._variant_subsumption = out
        return self._variant_subsumption

    def satisfying_variants(self, key):
        """The produced variants a demand for bare `key` may be satisfied by; [] normally.

        A VIEW OF `variant_subsumption` AND NOT A SECOND SPELLING OF ITS CLAUSES -- see the
        DO NOT there. Ordered as `variant_index` is, which is insertion order off `by_output`
        and therefore the order the dump saw, so a caller that ranks these by price gets a
        deterministic answer even when the prices tie. `tests/fixtures/plan/*.json` freeze
        whole solver results for the Java port, so an order that varied between processes
        would make those fixtures flip between runs.
        """
        subsumption = self.variant_subsumption
        return [v for v in self.variants_of(key) if subsumption.get(v) == key]

    @property
    def meta_index(self):
        """item stem -> [produced keys at any metadata value of it].

        NOT a general-purpose index, and `base_key` says why: metadata usually separates
        genuinely different items, and `tconstruct:ingots:0` melting into every molten
        metal `tconstruct:ingots:3` does would be a fabricated route. The one question it
        may answer is `meta_sibling_made`'s; see the DO NOT there.
        """
        if self._meta_index is None:
            idx = {}
            for key in self.by_output:
                stem = item_stem(key)
                # Non-items are skipped rather than piling up under a None stem, matching
                # `variant_index`. `meta_sibling_made` early-returns on None so the bucket
                # was inert, but it was 1,198 fluid and oredict keys filed under a key this
                # docstring says holds registry names, waiting for a second caller.
                if stem is not None:
                    idx.setdefault(stem, []).append(key)
            self._meta_index = idx
        return self._meta_index

    def meta_sibling_made(self, key):
        """A produced key that is the same registered item at another meta, or None.

        One step wider than `producers_any_variant` and the last one there is: NBT, then
        meta, then nothing.

        DO NOT call this from the solver or the cost model. It answers only "does this
        BLOCK exist in the pack at all", which is the machines page's question, and it is
        wrong for every other one: a recipe asking for `tconstruct:ingots:3` will not
        accept `:0`.

        ONLY FOR AN UNNAMED META, and that gate is the whole safety argument. JEI records
        the Sign Toolbox catalyst for `moarsigns.exchange` as `moarsigns:sign_toolbox:4`
        because the toolbox picks its mode with the damage value, and the pack registers no
        name for that value -- an unnamed meta is the dump reporting a stack STATE nobody
        registered as an item. A meta the pack DID name is its own item, and saying it is
        craftable "as" a sibling is a falsehood of exactly the shape `base_key`'s DO NOT
        forbids. Measured on the reference pack, without the gate this fired on four
        categories and two were false: `bloodmagic:ritual_diviner:2` ("Ritual Diviner
        [Dawn]") reported craftable via `:1` ("[Dusk]"), and `genetics:geneticdatabase:1`
        ("Master Gene Database") via the plain "Gene Database". With it, exactly one
        category changes and it is the intended one, which is what the old docstring
        claimed before anyone counted.

        The caller still quotes the variant it found (`craftable: X (as Y)`) rather than
        widening silently, so a reader can judge the claim. Same bargain as #55: report the
        thing you can see rather than assert a classification you cannot check.
        """
        stem = item_stem(key)
        if stem is None or self.names.get(key):
            return None
        for sibling in self.siblings_made(stem, key):
            return sibling
        return None

    def siblings_made(self, stem, exclude=None):
        """Produced keys under `stem`, plain base first, then ascending meta, NBT last.

        ORDERED, because `meta_sibling_made` returns the head and `by_output` insertion
        order is the order recipes came out of the dump: unsorted, a re-dump could silently
        change which sibling the machines page names. Plain base first because it is the
        least surprising thing to call an item "as", and NBT-discriminated keys last
        because a meta sibling should never be reported through an NBT state when a plain
        one exists.
        """
        def rank(k):
            base, disc = split_discriminator(k)
            _stem, meta = split_key(base)
            return (disc is not None, meta if isinstance(meta, int) else 1 << 20, k)

        return sorted((k for k in self.meta_index.get(stem, ())
                       if k != exclude and self.producers(k)), key=rank)

    def damage_base(self, key):
        """The undamaged key a worn one is a state of; `key` unchanged for everything else.

        `minecraft:iron_axe:187` -> `minecraft:iron_axe`. `chisel:lapis:3` -> itself,
        because chisel blocks are not damageable and their meta is a real subtype.

        THE GATE IS `max_damage`, WHICH IS PACK DATA READ BACK FROM THE ITEM REGISTRY, not
        a guess from the shape of the key. Every structural rule anyone proposed for this
        also matched the 286-meta Spell Book and the nine `chisel:lapis` blocks; see
        sources/damageable for the measurement and #110 for why getting those wrong is
        worse than the noise it would remove.

        NBT SURVIVES. A discriminated key keeps its digest, so a named or enchanted tool is
        still its own item -- only the durability tick collapses. The `#digest` is split off
        and re-attached rather than being allowed to reach `split_key`, which does not know
        about it.

        RETURNS A KEY, IT DOES NOT MERGE ANYTHING. `producers`, `consumers` and stock are
        untouched: `minecraft:iron_axe` really does hold the 2 in stock and really is the
        key recipes ask for, which is why #118 is a display defect rather than a wrong plan.
        Collapsing the GRAPH would touch the solver, `gaps.stock_coverage` and every
        key-walking surface to fix something none of them get wrong.
        """
        base, disc = split_discriminator(key)
        stem, meta = split_key(base)
        if meta in (0, None) or stem not in self.max_damage:
            return key
        return stem if disc is None else "%s#%s" % (stem, disc)

    def damage_of(self, key):
        """`(damage, maxDamage)` for a worn tool, or None when the key is not one.

        So a UI can say "Iron Axe (187/250 damage)" instead of "Iron Axe (187)", which is
        the difference between a durability reading and an apparently meaningless number.
        """
        stem, meta = split_key(base_key(key))
        max_damage = self.max_damage.get(stem)
        if max_damage is None or not isinstance(meta, int) or meta <= 0:
            return None
        return meta, max_damage

    @staticmethod
    def real_production(recipe, key):
        """Whether `recipe` counts as PRODUCING `key`, or is only moving it about.

        THE ONE SPELLING OF THIS EXCLUSION. `real_producers` below, `cost._seed` and
        `cost._relax` all read it, and until #193 all three spelled it themselves: the
        relaxation carried a hand-rolled copy whose own comment said "mirrors
        `Graph.real_producers`", and the seed carried a third version that did not exclude
        anything at all. DO NOT re-inline it in a caller. That is the defect
        `api._reachable_form` was, where a copy drifted for two releases while an agreement
        test compared five keys that all exercised the one branch nobody had changed;
        `tests/test_unsourced.py` now pins the single definition for both predicates.

        A CONTAINER TRANSFER ASKED TO CREATE A FLUID is the whole of what it excludes. Emptying
        a container is not production of its contents: to hold a water-filled can you must
        already have had the water, so `Water Can -> 1,000 mB water` is circular. Left in, it is
        worse than circular, because the dump drops the NBT that tells one filled can from
        another -- every filled Forestry can collapses to `forestry:can:1`, so the graph
        believes squeezing a can of WATER yields uranium fluoride. That exact edge put a Fluid
        Transposer and a bogus uranium chain in a Borax plan.

        Filling a container IS real work and stays: only the fluid direction is fake, so a
        transfer may still produce an ITEM. A fluid whose only route is a container empty
        correctly comes out as NEED, which is the honest answer.
        """
        return not (recipe.transfer and key.startswith(FLUID_PREFIX))

    def real_producers(self, key):
        """`producers`, minus the entries that are not production of `key`. TWO EXCLUSIONS.

        THE CONTAINER TRANSFER, through `real_production` above rather than spelled again here.

        AND A RECIPE `notproduction` DEMOTED: a random loot table, or a JEI card explaining
        how to automate something. #211 and #169. Same predicate as the first exclusion and
        the same reason -- these entries do not turn their inputs into their outputs -- so
        this is the one place that answers it rather than a second spelling beside it. #211
        asked for exactly this: "a recipe KIND that the solver refuses to route through but
        the UI can still show". Nothing is removed from the graph, `by_output`, `used_in`,
        `explore`'s `makes` rows or `/api/recipe`, so the JEI card stays readable; it simply
        stops being an answer to "how do I get this".

        THE ALTERNATIVE-PRODUCER GUARD IS WHAT MAKES THIS SAFE. `notproduction.demoted` never
        demotes a recipe unless every one of its outputs has another producer that is not
        itself demoted, so this filter cannot empty the list and cannot move a key into
        `unsourced_keys` -- which reads this method, and whose price is #176's 2,000.

        `cost._relax` MIRRORS THE FIRST EXCLUSION AND CHARGES FOR THE SECOND, which is a
        deliberate asymmetry and not drift. Skipping demoted recipes in the relaxation as well
        was built and measured: it strands 26 keys at infinity, because their only route that
        is not documentation is itself unreachable. `NON_PRODUCTION_PENALTY` sits above the
        `unavailable` machine wall, so the ranker still never prefers a route this method
        withholds. The reason is written out at the call site in `_relax`; read it before
        making the two identical.

        Not memoised on purpose -- it is a cheap filter over an already-memoised list, and
        a second cache keyed the same way is how the two drift apart.

        NO FAST PATH THAT READS ANY FLAG BUT THE RECIPES' OWN. The non-fluid case used to
        return the memoised list unfiltered, and reinstating that on "has this graph been
        marked" would be wrong for a caller that marked through `notproduction.mark` directly
        rather than through `mark_non_production` -- which is what the tests do. The filter is
        one comprehension over a list that is almost always shorter than five.

        TWO RULES, AND ONLY ONE OF THEM IS `real_production`. The container exclusion is shared
        with `cost._relax` and `real_output`, so it lives in one place and is called here. The
        documentation-card exclusion is NOT shared: `_relax` prices a marked recipe at
        `NON_PRODUCTION_PENALTY` rather than refusing it, deliberately, so folding
        `not_production` into `real_production` would make the relaxation skip routes #211
        wants ranked-but-priced. `AllThreeReadersAgreeTest` compares this composition against
        the predicate over every recipe of every fixture graph rather than trusting that
        sentence.
        """
        return [r for r in self.producers(key)
                if not r.not_production and self.real_production(r, key)]

    def real_output(self, key):
        """Whether some recipe's OWN output list names `key` and counts as making it.

        The question `cost._seed` asks: is there anything for the relaxation to price this
        key FROM, or must the seed give it a price itself? Before #193 the seed asked
        `key in by_output` and so answered yes for 120 fluids whose only routes are container
        empties -- nothing seeded them and `cost._relax` then refused to price them, leaving
        the cost model at infinity while the plan called them raw and shopping-listed them.
        Cost said impossible, plan said go and buy it.

        WHAT the seed then charges them is a separate question with its own answer: they are
        keys the graph has proven it cannot explain, so they end at `UNSOURCED_COST` rather
        than at the leaf price. See `produced_in_name_only` above.

        DELIBERATELY NOT `real_producers(key)`, AND NOT WIDENED TO THE WILDCARD SIBLING.
        `cost._relax` lowers `cost[k]` only for keys a recipe LITERALLY outputs, so a key
        reachable only through a `mod:item:*` producer has nothing that will ever write it.
        Measured on the reference graph: 478 input alternatives are absent from `by_output`
        while `real_producers` finds a wildcard producer for them -- every damaged
        Electroblob wand and Arcane Essentials sword -- and answering yes for those would
        strand all 478 at infinity along with every route through them, which is the
        finite-to-infinite failure `BASE_RAW_COST`'s comment in `cost.py` already measured
        and rejected once. Fixing that gap means teaching the relaxation to write a damaged
        sibling, which is a different change; this predicate must stay the exact complement
        of what the relaxation can reach.

        AND NOT WIDENED TO `not_production` EITHER, for that same reason rather than by
        oversight. `real_producers` excludes a demoted loot table or JEI card because the solver
        refuses to route through one, but `cost._relax` PRICES it at `NON_PRODUCTION_PENALTY`
        instead of skipping it -- #211 measured that skipping strands 26 keys at infinity. So a
        key whose only producer is demoted still has something that writes it, and calling it a
        leaf here would seed a price the relaxation then immediately competes with.
        """
        return any(self.real_production(r, key) for r in self.by_output.get(key, ()))

    @staticmethod
    def kind(key):
        """Which namespace a key lives in: fluid, essentia, ore, or plain item.

        Exists so a UI can render the type as a coloured chip. Reading "[fluid]" a hundred
        times down a plan is fatiguing, and the bracket text is only there because plain
        terminal output has no other way to say it.
        """
        for name, prefix in zip(NON_ITEM_KINDS, NON_ITEM_PREFIXES):
            if key.startswith(prefix):
                return name
        return "item"

    def bare_name(self, key):
        """`display` without the type prefix, for callers that show the type separately."""
        # BEFORE the `names` lookup, because the blueprint IS named -- all 261 of them are
        # named "Machine Blueprint", which is what the game genuinely returns and what makes
        # a plan for any multiblock read "1 of 261 possibilities". The dump's own machine
        # registry is the only thing that can say which. #55
        machine = self.blueprint_name(key)
        if machine:
            return machine
        if key in self.names:
            label = self.names[key]
            # items.csv stores aspect-parameterised names as format strings ("%s Vis
            # Pod"). With no aspect to fill in, drop the placeholder rather than showing
            # a raw %s.
            if "%s" in label:
                return label.replace("%s ", "").replace("%s", "").strip() or key
            return label
        if key.startswith(ORE_PREFIX):
            return key[len(ORE_PREFIX):]
        if key.startswith(FLUID_PREFIX):
            # The containers the fluid is bottled in FIRST, because they are the only place
            # a localized fluid name survives: the dump records `{"f":"nethengeic_fluid"}`
            # and nothing else, so prettifying the registry name called the pack's Strong
            # Mythic Essence "Nethengeic Fluid" and put it beyond reach of any search for
            # what it is called on screen. 789 of 1,198 were wrong that way. See #103.
            #
            # `_prettify` stays as the fallback -- `boric_acid` -> `Boric Acid` -- for a
            # fluid no container recipe ever touches. Presented raw it looks like a variable
            # next to properly-cased item names. Search still finds the raw form either way,
            # because the key is matched too.
            return self.fluid_names.get(key) or _prettify(key[len(FLUID_PREFIX):])
        if key.startswith(ESSENTIA_PREFIX):
            return key[len(ESSENTIA_PREFIX):].capitalize()
        # `mod:item#aspect` -- an NBT-discriminated stack. Names for these are format
        # strings in items.csv ("%s Vis Pod"), so fill the placeholder with the aspect
        # rather than showing a raw %s to the user.
        stem, aspect = split_discriminator(key)
        if aspect is not None:
            label = self.names.get(stem) or self.bare_name(stem)
            pretty = _variant_label(aspect)
            if "%s" in label:
                return label % pretty
            return "%s (%s)" % (label, pretty)
        base, meta = split_key(key)
        label = self.names.get(base)
        if label is None:
            # Nothing named this item, so its registry path is the only thing left that
            # identifies it -- the same treatment a fluid gets above, for the same reason:
            # `minecraft:scroll_buff` sitting raw next to properly-cased names reads as a
            # variable, not an item. 174 recipe-referenced keys land here. The full key is
            # still rendered beside the name in search rows and on the item page, so the
            # modid is not lost by prettifying.
            label = _prettify(path_of(base))
        if meta in (0, None):
            return label
        # A bare "(187)" beside an item name reads as a variant number and is in fact a
        # durability reading, which is how 46 rows of one axe looked like 46 items. Saying
        # what the number MEANS costs one lookup and is the smallest honest fix. #118
        wear = self.damage_of(key)
        if wear:
            return "%s (%d/%d damage)" % (label, wear[0], wear[1])
        return "%s (%s)" % (label, meta)

    def blueprint_name(self, key):
        """"Machine Blueprint (Dragonfire Crucible)", or None when `key` is not one.

        THE PARENTHETICAL FORM, not "Dragonfire Crucible Blueprint", and #55 left the choice
        open. The deciding argument is search: `labels` indexes exactly this string, so the
        parenthetical is findable under BOTH the name the game shows and the name in the
        player's JEI, while the reworded form is findable under neither of the two words a
        player looking at a blueprint in their hand would type. It reads marginally worse in
        a shopping list and is reachable by two more queries, which is the better trade for
        an item whose whole problem is that nobody can tell which one they are looking at.

        A blueprint whose machine the registry does not name falls through to None rather
        than to "Machine Blueprint ()" -- the same policy as every other optional field
        here: no data means the old behaviour, not a decorated absence.
        """
        machine = self.blueprint_machines.get(key)
        if not machine:
            return None
        name = self.machine_names.get(machine)
        if not name:
            return None
        return "%s (%s)" % (self.names.get(key) or "Machine Blueprint", name)

    # Text prefixes, for the CLI and anything else without colour. `ore` reads "oredict"
    # rather than "ore" because it means "any member of", not "an ore".
    KIND_PREFIX = {"fluid": "[fluid] ", "essentia": "[essentia] ", "ore": "[oredict] "}

    def display(self, key):
        """Human label, with a bracketed type prefix for non-item keys.

        DO NOT drop the prefix here to tidy the web UI. This is what the CLI and the JSON
        output print, and without it `water` and `Water Bucket` are indistinguishable in a
        shopping list. HTML callers should use `kind` + `bare_name` instead.
        """
        name = self.bare_name(key)
        if key in self.names:
            return name
        return self.KIND_PREFIX.get(self.kind(key), "") + name

    def relabel_unlocalized(self):
        """Replace every label that is only an unlocalized lang key. Returns how many.

        Rewrites `names` itself rather than filtering at display time, because ONE pass
        then fixes every consumer at once: the search index (`labels` is built from
        `names`), name -> key lookup (`names.build_reverse`, which otherwise resolves the
        string "tile.null.name" to 268 unrelated items), the CLI, and every HTML surface.
        Handling it inside `bare_name` alone would leave all of those still reading the
        junk. See #52.

        The keys are KEPT, only relabelled. Deleting them -- which is the tidier-looking
        fix, and the one #52 proposed -- would drop each item out of `labels` and so out of
        search entirely, which is worse than an ugly name.

        TWO passes, and they must stay two: a discriminated key takes its stem's name, so
        every unlocalized label has to be gone before any replacement is computed, or the
        result would depend on dict iteration order.

        Callers: `Graph.load` and `index.build`, which are the only two ways `names` gets
        populated. A third one has to call this too.
        """
        junk = [k for k, v in self.names.items() if is_unlocalized(v)]
        if not junk:
            return 0
        for key in junk:
            del self.names[key]
        for key in junk:
            self.names[key] = self.bare_name(key)
        # Only `_labels` is actually stale, but `_invalidate` is the one sanctioned place
        # that knows the list of derived indexes, and the rest rebuild lazily.
        self._invalidate()
        return len(junk)

    def referenced_ores(self):
        """Ore names actually used as an input by some recipe."""
        out = set()
        for r in self.recipes:
            for ing in r.inputs:
                for alt in ing.alternatives:
                    if alt.startswith("ore:"):
                        out.add(alt[4:])
        return out

    def to_json(self):
        return {
            "recipes": [r.to_json() for r in self.recipes],
            "names": self.names,
            "ore_members": self.ore_members,
            "ore_guessed": sorted(self.ore_guessed),
            "catalysts": self.catalysts,
            "category_mods": self.category_mods,
            "dump_schema": self.dump_schema,
            "dump_version": self.dump_version,
            "dump_names_failed": self.dump_names_failed,
            "dump_mod_count": self.dump_mod_count,
            "dump_mod_digest": self.dump_mod_digest,
            "instance_dir": self.instance_dir,
            "multiblocks": self.multiblocks,
            "dimension_ores": self.dimension_ores,
            "offworld_ores": self.offworld_ores,
            "shadow_ores": self.shadow_ores,
            "max_damage": self.max_damage,
            "machine_names": self.machine_names,
            "blueprint_machines": self.blueprint_machines,
            "emc": self.emc,
            "icons": self.icons,
        }

    def save(self, path):
        with open(path, "w") as fh:
            json.dump(self.to_json(), fh, separators=(",", ":"), sort_keys=True)

    #: How much of a graph.json `recorded_mod_set` reads before giving up and parsing it all.
    #:
    #: MEASURED ON THE REFERENCE GRAPH, not guessed: `save` writes `sort_keys=True`, and on
    #: the 121 MB oracle the `dump_*` block lands 127 KB in, after `catalysts`,
    #: `category_mods` and `dimension_ores` and before `emc` (126,980) and `names` (959,134).
    #: A megabyte is an eightfold margin on that and small enough that the fast path is free.
    HEADER_BYTES = 1 << 20

    #: The first key `save` writes that sorts AFTER `dump_mod_digest`, and therefore the
    #: proof that a prefix has passed the point where the pair would be. See
    #: `recorded_mod_set`; `tests/test_schema_six.py` pins the ordering this relies on.
    _PAST_MOD_SET = '"dump_schema"'

    @staticmethod
    def recorded_mod_set(path):
        """`(dump_mod_count, dump_mod_digest)` from a graph.json, without loading it.

        WHY NOT `Graph.load`. The caller is `index.build` deciding whether it is about to
        replace a graph built from a different pack, and the reference graph is 121 MB.
        MEASURED on it: `json.load` is 3.4 s and 614 MB of peak RSS, this is 0.05 s and
        10 MB. Two scalars are not worth the difference on every build, and a check that
        makes the common path slower is a check someone eventually routes around.

        THREE OUTCOMES, AND THE MIDDLE ONE IS THE POINT. Finding the pair answers directly.
        Not finding it but finding a key that sorts AFTER it proves the pair is absent from
        the document -- which is every graph built before #194, i.e. every graph on disk the
        day this lands. Without that case the fallback would fire on all of them and pay a
        full 121 MB parse on every build until the graph is rebuilt, which is precisely when
        someone starts wondering why `build` got slower.

        CORRECTNESS STILL DOES NOT DEPEND ON THE KEY ORDER. The absence conclusion is
        licensed by `sort_keys=True`, and if that ever goes away the sentinel simply stops
        appearing before the pair -- so a graph with neither the pair nor the sentinel in its
        prefix falls through to a full parse rather than reporting an absence that would
        silently switch the guard off. Getting quieter when something unexpected happens is
        how a guard stops guarding.
        """
        with open(path, "rb") as fh:
            head = fh.read(Graph.HEADER_BYTES)
        text = head.decode("utf-8", errors="replace")
        count = re.search(r'[{,]"dump_mod_count":(null|\d+)[,}]', text)
        digest = re.search(r'[{,]"dump_mod_digest":(null|"[^"]*")[,}]', text)
        if count and digest:
            return json.loads(count.group(1)), json.loads(digest.group(1))
        if Graph._PAST_MOD_SET in text:
            return None, None
        with open(path) as fh:
            d = json.load(fh)
        return d.get("dump_mod_count"), d.get("dump_mod_digest")

    @staticmethod
    def load(path):
        with open(path) as fh:
            d = json.load(fh)
        g = Graph()
        g.recipes = [Recipe.from_json(r) for r in d.get("recipes", [])]
        g.names = d.get("names", {})
        g.ore_members = d.get("ore_members", {})
        g.ore_guessed = set(d.get("ore_guessed", ()))
        g.catalysts = d.get("catalysts") or {}
        g.category_mods = d.get("category_mods") or {}
        g.dump_schema = d.get("dump_schema") or 0
        g.dump_version = d.get("dump_version") or None
        # `.get`, NOT `or None`: 0 is a real answer here -- "the dump measured and lost
        # nothing" -- and `or None` would turn every clean graph back into "cannot say".
        g.dump_names_failed = d.get("dump_names_failed")
        g.dump_mod_count = d.get("dump_mod_count")
        g.dump_mod_digest = d.get("dump_mod_digest")
        g.instance_dir = d.get("instance_dir")
        # Absent from every graph built before #93, and absent from any pack without Modular
        # Machinery. An empty map means "priced by the controller recipe alone", which is the
        # pre-#93 behaviour, so an old graph.json keeps working rather than failing to load.
        g.multiblocks = d.get("multiblocks") or {}
        # Absent before #112; empty means "no dimension is priced", the pre-#112 behaviour.
        g.dimension_ores = d.get("dimension_ores") or {}
        # Absent before #248; empty means "no ore pays a toll", the pre-#248 behaviour, and
        # NOT "every ore is in the overworld". Same reason as `shadow_ores` below for not
        # deriving it here: it comes from pack files rather than from the graph.
        g.offworld_ores = d.get("offworld_ores") or {}
        # Absent before #168; empty means "no key is known to be a duplicate registration",
        # which is the pre-#168 behaviour and NOT "every key is genuine". DO NOT recompute it
        # here the way `relabel_unlocalized` is recomputed below: this is derived from pack
        # files rather than from the graph, so a `load` that re-derived it would silently
        # change `dimension_ores` for an oracle on disk -- and `tools/make-java-fixtures.py`
        # keys every fixture to that count, so the whole golden set would go stale without a
        # rebuild having happened.
        g.shadow_ores = d.get("shadow_ores") or {}
        # All five absent before schema 5, and every one of them means "the feature is off"
        # rather than "something is broken": no meta collapse, no blueprint names, no EMC
        # route, no icons. A graph built from an older dump goes on working unchanged, which
        # is the point of reading them with `or {}` rather than asserting them present.
        g.max_damage = d.get("max_damage") or {}
        g.machine_names = d.get("machine_names") or {}
        g.blueprint_machines = d.get("blueprint_machines") or {}
        g.emc = d.get("emc") or {}
        g.icons = d.get("icons") or {}
        # Here rather than only in `index.build`, so an ALREADY BUILT graph.json is fixed
        # without a rebuild. Rebuilding needs the game running and a fresh dump; the 115 MB
        # file on disk is what every surface reads today.
        g.relabel_unlocalized()
        return g
