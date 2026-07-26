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


def split_key(key):
    """Return (base_key_without_meta, meta_or_None) for concrete item keys."""
    if key.startswith(("ore:", "fluid:", "essentia:")):
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
        self._by_output = None
        self._by_input = None
        self._producer_cache = {}

    def add(self, recipe):
        self.recipes.append(recipe)
        self._by_output = None
        self._by_input = None
        self._producer_cache = {}

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

    def consumers(self, key):
        out = list(self.by_input.get(key, ()))
        base, meta = split_key(key)
        if meta not in (None, "*"):
            out.extend(self.by_input.get("%s:*" % base, ()))
        # an item is also reachable through any oredict it belongs to
        for ore, members in self.ore_members.items():
            if key in members:
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
            if meta == 0:
                pass
            elif base in self.by_output:
                pass
        self._producer_cache[key] = out
        return out

    def display(self, key):
        if key in self.names:
            label = self.names[key]
            # items.csv stores aspect-parameterised names as format strings ("%s Vis
            # Pod"). With no aspect to fill in, drop the placeholder rather than showing
            # a raw %s.
            if "%s" in label:
                return label.replace("%s ", "").replace("%s", "").strip() or key
            return label
        if key.startswith("ore:"):
            return "[oredict] %s" % key[4:]
        if key.startswith("fluid:"):
            return "[fluid] %s" % key[6:]
        if key.startswith("essentia:"):
            return "[essentia] %s" % key[9:].capitalize()
        # `mod:item#aspect` -- an NBT-discriminated stack. Names for these are format
        # strings in items.csv ("%s Vis Pod"), so fill the placeholder with the aspect
        # rather than showing a raw %s to the user.
        if "#" in key:
            stem, aspect = key.rsplit("#", 1)
            label = self.names.get(stem) or self.display(stem)
            pretty = aspect.capitalize()
            if "%s" in label:
                return label % pretty
            return "%s (%s)" % (label, pretty)
        base, meta = split_key(key)
        if base in self.names:
            return self.names[base] if meta in (0, None) else "%s (%s)" % (self.names[base], meta)
        return key

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
        return g
