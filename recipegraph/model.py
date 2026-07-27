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

WILDCARD_META = 32767


def norm_key(item_id, meta=0):
    """Canonical item key. Accepts meta as int, str, None, or the 32767 wildcard."""
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
    return "fluid:%s" % name


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


_DIGEST = re.compile(r"^[0-9a-f]{12}$")


def _variant_label(suffix):
    """How to read the `#suffix` on a discriminated key when nothing named it.

    Two kinds live here. An aspect (`#perditio`) is a word and reads as one. A dump
    discriminator is a 12-hex digest of the stack's NBT, which reads as line noise, so it
    is labelled as what it is and shortened. Never collapse two digests to one label:
    telling a Forest drone from a Meadows drone is the entire point.
    """
    if _DIGEST.match(suffix):
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


def is_item_key(key):
    """True for a concrete item key, False for a fluid, oredict or essentia key."""
    return not key.startswith(NON_ITEM_PREFIXES)


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


class Ingredient:
    """One input slot: any of `alternatives` satisfies it, `qty` of them."""

    __slots__ = ("alternatives", "qty", "role")

    def __init__(self, alternatives, qty=1, role="item"):
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

    def to_json(self):
        d = {"alt": self.alternatives, "qty": self.qty}
        if self.role != "item":
            d["role"] = self.role
        return d

    @staticmethod
    def from_json(d):
        return Ingredient(d["alt"], d.get("qty", 1), d.get("role", "item"))


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
    """
    merged = {}
    for ing in inputs:
        key = key_of(ing)
        row = merged.get(key)
        if row is None:
            merged[key] = [key, ing, ing.qty, len(ing.alternatives)]
        else:
            row[2] += ing.qty
            row[3] = max(row[3], len(ing.alternatives))
    return [tuple(row) for row in merged.values()]


class Recipe:
    __slots__ = ("rid", "source", "category", "outputs", "inputs", "machine",
                 "transfer")

    def __init__(self, rid, source, outputs, inputs, category="crafting", machine=None,
                 transfer=False):
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

    def to_json(self):
        return {
            "id": self.rid,
            "src": self.source,
            "cat": self.category,
            "out": [{"key": k, "qty": q} for k, q in self.outputs],
            "in": [i.to_json() for i in self.inputs],
            **({"machine": self.machine} if self.machine else {}),
            **({"xf": 1} if self.transfer else {}),
        }

    @staticmethod
    def from_json(d):
        return Recipe(
            d["id"], d["src"],
            [(o["key"], o["qty"]) for o in d["out"]],
            [Ingredient.from_json(i) for i in d["in"]],
            d.get("cat", "crafting"), d.get("machine"), bool(d.get("xf")),
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
        # Which dump schema produced this graph, 0 for none. Recorded because some
        # judgements are only SAFE once the data supports them: see
        # machines.SPECIES_SCHEMA, where "bee breeding needs no machine" has to wait for
        # a dump that can tell one bee from another.
        self.dump_schema = 0
        # The instance this graph was built from. Persisted so `serve` can find the dump
        # directory and rebuild itself without the user passing --instance again; a tool
        # that already knows the answer should not ask.
        self.instance_dir = None
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
        self._ore_index = None
        self._labels = None
        self._variant_index = None
        self._producer_cache = {}

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
                    if key.startswith(("fluid:", "essentia:", "ore:")) and key not in out:
                        out[key] = self.bare_name(key)
            self._labels = out
        return self._labels

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

    def real_producers(self, key):
        """`producers`, minus container transfers asked to CREATE a fluid.

        Emptying a container is not production of its contents: to hold a water-filled can
        you must already have had the water, so `Water Can -> 1,000 mB water` is circular.
        Left in, it is worse than circular, because the dump drops the NBT that tells one
        filled can from another -- every filled Forestry can collapses to `forestry:can:1`,
        so the graph believes squeezing a can of WATER yields uranium fluoride. That exact
        edge put a Fluid Transposer and a bogus uranium chain in a Borax plan.

        Filling a container IS real work and stays: only the fluid direction is fake, so a
        transfer may still produce an ITEM. A fluid whose only route is a container empty
        correctly comes out as NEED, which is the honest answer.

        Not memoised on purpose -- it is a cheap filter over an already-memoised list, and
        a second cache keyed the same way is how the two drift apart.
        """
        if not key.startswith("fluid:"):
            return self.producers(key)
        return [r for r in self.producers(key) if not r.transfer]

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
        if key in self.names:
            label = self.names[key]
            # items.csv stores aspect-parameterised names as format strings ("%s Vis
            # Pod"). With no aspect to fill in, drop the placeholder rather than showing
            # a raw %s.
            if "%s" in label:
                return label.replace("%s ", "").replace("%s", "").strip() or key
            return label
        if key.startswith("ore:"):
            return key[4:]
        if key.startswith("fluid:"):
            # `boric_acid` -> `Boric Acid`. A fluid has no items.csv entry, so the registry
            # name is all there is; presented raw it looks like a variable next to
            # properly-cased item names. Search still finds the raw form because the key is
            # matched too.
            return _prettify(key[6:])
        if key.startswith("essentia:"):
            return key[9:].capitalize()
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
        if base in self.names:
            return (self.names[base] if meta in (0, None)
                    else "%s (%s)" % (self.names[base], meta))
        return key

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
            "instance_dir": self.instance_dir,
        }

    def save(self, path):
        with open(path, "w") as fh:
            json.dump(self.to_json(), fh, separators=(",", ":"), sort_keys=True)

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
        g.instance_dir = d.get("instance_dir")
        return g
