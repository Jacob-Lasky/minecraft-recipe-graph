"""Recipe source: `assets/<modid>/recipes/*.json` inside every mod jar.

This is the offline bootstrap source. It covers crafting-table recipes only (the
1.12.2 Forge JSON recipe format), which in MeatballCraft is ~11k recipes -- a lot,
but it does NOT include machine recipes (NuclearCraft chemistry, Modular Machinery,
smelting-style processors). Those exist only in the running game's registries, which
is what the HEI dump source is for. Treat this source as a base layer, not complete.
"""

import json
import os
import zipfile

from ..model import Ingredient, Recipe, WILDCARD_META, norm_key, ore_key

SHAPED = {"minecraft:crafting_shaped", "forge:ore_shaped"}
SHAPELESS = {"minecraft:crafting_shapeless", "forge:ore_shapeless"}


def qualify(item_id, default_ns):
    """An id from a recipe JSON -> the id Forge would register it under. Issue #227.

    FORGE DEFAULTS AN UNQUALIFIED ID TO THE RECIPE FILE'S OWN NAMESPACE, NOT TO `minecraft`.
    `CraftingHelper.getItemStack` passes every id through `JsonContext.appendModId`, which
    prepends the domain of the file the recipe was read from, so
    `assets/tombstone/recipes/tablet_of_home.json` writing `"item": "tablet_of_home"` means
    `tombstone:tablet_of_home`. `model.norm_key` prepends `minecraft:` instead, which is right
    for every OTHER caller and wrong for this one -- see the comment on `norm_key` itself.

    Measured on the reference pack before this existed: 78 of 10,301 recipe docs carry at
    least one unqualified id, producing 64 phantom `minecraft:*` keys, ALL of which reached
    the shipped `data/graph.json` -- `minecraft:tablet_of_home`, `minecraft:voodoo_poppet`,
    `minecraft:colossal_star_ein`. The harm is not only cosmetic: 32 of the ids are
    INGREDIENTS, so the ProjectEx star chain and the tesslocator set became recipes demanding
    an item that cannot exist, and no AE2 stock can ever satisfy a key the registry has never
    heard of.

    TWO DIFFERENT CHECKS AGAINST THE DUMP, AND THEY GIVE DIFFERENT NUMBERS ON PURPOSE.
    **58 of the 64** corrected keys name an item the dump has SEEN (`names.json`), which is
    the question "is this the real id"; only **55** name an item the dump PRODUCES, which is
    the narrower question "does something craft it". A world-gen item nobody crafts is known
    and unproduced, so quoting the smaller number as evidence about identity understates the
    fix -- which is a mistake this issue's own measurement made once before it was caught.

    `default_ns` of None leaves the id alone and lets `norm_key` do what it always did. That
    path is for a caller with no namespace to give: `extract` always has one, so in practice
    it is the unit tests and any future reader of a loose recipe file.
    """
    if not isinstance(item_id, str) or ":" in item_id or not default_ns:
        return item_id
    return "%s:%s" % (default_ns, item_id)


def _ingredient_alts(node, constants=None, default_ns=None, _seen=None):
    """A JSON ingredient -> list of item keys. Handles ore dicts, arrays, nbt forms.

    `#name` resolves through Forge's `_constants.json` mechanism (38 jars in this
    pack use it). Unresolved `#name` yields nothing rather than a bogus
    `minecraft:#name` key -- a fake key would silently become a phantom raw item in
    every shopping list.

    `default_ns` is the recipe file's own namespace; see `qualify` for why an unqualified
    id must not fall back to `minecraft`.
    """
    if node is None:
        return []
    if isinstance(node, list):
        alts = []
        for sub in node:
            alts.extend(_ingredient_alts(sub, constants, default_ns, _seen))
        return alts
    if isinstance(node, str):
        node = {"item": node}
    if not isinstance(node, dict):
        return []

    itype = node.get("type")
    if itype in ("forge:ore_dict", "forge:ore_dictionary") and node.get("ore"):
        return [ore_key(node["ore"])]
    # forge:nbt / minecraft:item_nbt still carry an "item"
    if "item" in node:
        item = node["item"]
        if isinstance(item, list):
            alts = []
            for sub in item:
                alts.extend(_ingredient_alts(sub, constants, default_ns, _seen))
            return alts
        if isinstance(item, str) and item.startswith("#"):
            name = item[1:]
            seen = _seen or frozenset()
            if not constants or name in seen:
                return []
            target = constants.get(name)
            if target is None:
                return []
            return _ingredient_alts(target, constants, default_ns, seen | {name})
        if not isinstance(item, str):
            return []  # same guard, same reason, as `_result_outputs`
        data = node.get("data", node.get("meta", 0))
        return [norm_key(qualify(item, default_ns), data)]
    if "ore" in node:
        return [ore_key(node["ore"])]
    if "tag" in node and isinstance(node["tag"], str):
        return [ore_key(node["tag"])]
    return []


def _result_outputs(node, default_ns=None):
    if not isinstance(node, dict):
        return []
    item = node.get("item")
    # A NON-STRING id YIELDS NOTHING RATHER THAN A KEY BUILT OUT OF ITS repr(). `norm_key`
    # str()s whatever it is given, so a list result used to become the literal key
    # `minecraft:['a', 'b']` -- a phantom of exactly the kind #227 was opened about, arriving
    # by a different door. No document in the reference pack does this today; the guard is
    # here because "0 occurrences" is a fact about this pack and not about the format.
    if not item or not isinstance(item, str):
        return []
    data = node.get("data", node.get("meta", 0))
    count = node.get("count", 1)
    try:
        count = int(count)
    except (TypeError, ValueError):
        count = 1
    return [(norm_key(qualify(item, default_ns), data), max(1, count))]


def parse_recipe_json(doc, rid, constants=None, default_ns=None):
    """One recipe document -> Recipe, or None if unsupported/undecodable.

    `default_ns` is the namespace of the file this document was read from;
    `qualify` explains why an unqualified id must not fall back to `minecraft`.
    """
    if not isinstance(doc, dict):
        return None
    rtype = doc.get("type")
    outputs = _result_outputs(doc.get("result"), default_ns)
    if not outputs:
        return None

    slots = []
    if rtype in SHAPED or ("pattern" in doc and "key" in doc):
        key_map = doc.get("key") or {}
        counts = {}
        for row in doc.get("pattern") or []:
            if not isinstance(row, str):
                continue
            for ch in row:
                if ch != " ":
                    counts[ch] = counts.get(ch, 0) + 1
        for ch, n in counts.items():
            alts = _ingredient_alts(key_map.get(ch), constants, default_ns)
            if alts:
                slots.append(Ingredient(alts, n))
    elif rtype in SHAPELESS or "ingredients" in doc:
        # Collapse duplicate ingredients into one slot with a qty.
        agg = {}
        order = []
        for ing in doc.get("ingredients") or []:
            alts = _ingredient_alts(ing, constants, default_ns)
            if not alts:
                continue
            k = tuple(alts)
            if k not in agg:
                agg[k] = 0
                order.append(k)
            agg[k] += 1
        for k in order:
            slots.append(Ingredient(list(k), agg[k]))
    else:
        return None

    if not slots:
        return None
    shaped = rtype in SHAPED or "pattern" in doc
    cat = "crafting_shaped" if shaped else "crafting_shapeless"
    # A display title, so the UI does not have to show the raw uid where every other
    # category shows a machine name. Kept distinct from the JEI dump's "Crafting" on
    # purpose: these are the same crafting table read from a different source, and
    # collapsing the labels would make two rows look like duplicates of one thing.
    title = "Crafting (shaped)" if shaped else "Crafting (shapeless)"
    return Recipe(rid, "jar_json", outputs, slots, category=cat, machine=title)


def unread_subdir_jars(mods_dir):
    """`[(subdir, jars, recipe_json)]` for jars `extract` does NOT read, worst first.

    `extract` uses a flat `os.listdir`, but FORGE ALSO LOADS `mods/<mcversion>/` as a mod
    directory -- MeatballCraft keeps 13 scala and ChickenASM jars in `mods/1.12.2/`. Those
    carry no `assets/*/recipes/` between them, so nothing is lost TODAY and the flat read is
    not a bug. It is a SILENT gap: the day a recipe-bearing mod lands in a version
    subdirectory, its recipes vanish from the graph and no output says so, which is
    indistinguishable from the pack not having them.

    Reported rather than read, deliberately. Recursing would change which recipes the graph
    holds -- and therefore every plan fixture -- on the strength of a subdirectory nobody has
    put a recipe in yet; a build that ANNOUNCES what it skipped costs nothing and turns the
    silent case into a loud one. `recipe_json` is the number that decides which it is: 0 means
    the skip is free, non-zero means recurse and regenerate.
    """
    out = []
    for name in sorted(os.listdir(mods_dir)):
        sub = os.path.join(mods_dir, name)
        if not os.path.isdir(sub):
            continue
        jars = sorted(f for f in os.listdir(sub) if f.lower().endswith(".jar"))
        if not jars:
            continue
        n = 0
        for f in jars:
            try:
                with zipfile.ZipFile(os.path.join(sub, f)) as zf:
                    n += sum(1 for e in zf.namelist() if _is_recipe_entry(e))
            except (zipfile.BadZipFile, OSError):
                continue
        out.append((name, len(jars), n))
    out.sort(key=lambda r: (-r[2], r[0]))
    return out


def _is_recipe_entry(entry):
    """The one place the recipe-entry shape is spelled, read by `extract` and the subdir check.

    Two readers asking "is this a recipe" from two copies of this predicate is how one of them
    silently stops agreeing with the other.
    """
    return entry.endswith(".json") and entry.startswith("assets/") and "/recipes/" in entry


def asset_namespace(entry):
    """`assets/<ns>/...` -> `<ns>`, or None. The one place that shape is spelled.

    Sibling of `_is_recipe_entry` and here for the same reason it gives: `extract` asks this
    question in three places -- the constants pass, the recipe pass and the id it qualifies
    (#227) -- and three copies of `entry.split("/")[1]` is how two readers stop agreeing
    about what a namespace is.
    """
    if not entry.startswith("assets/"):
        return None
    parts = entry.split("/")
    return parts[1] if len(parts) > 2 and parts[1] else None


def extract(mods_dir, on_progress=None):
    """Walk every jar directly under mods_dir and yield Recipe objects.

    Immediate subdirectories are NOT read; `unread_subdir_jars` reports them and says why.

    """
    jars = sorted(
        os.path.join(mods_dir, f) for f in os.listdir(mods_dir) if f.lower().endswith(".jar")
    )
    stats = {"jars": 0, "files": 0, "recipes": 0, "skipped": 0}

    for jar in jars:
        stats["jars"] += 1
        if on_progress:
            on_progress(os.path.basename(jar), stats)
        try:
            zf = zipfile.ZipFile(jar)
        except zipfile.BadZipFile:
            continue
        with zf:
            entries = [e for e in zf.namelist() if _is_recipe_entry(e)]
            # Pass 1: per-namespace `_constants.json`, needed before any `#name`
            # reference can be resolved. Must precede recipe parsing.
            constants = {}
            for entry in entries:
                if not entry.endswith("/_constants.json"):
                    continue
                ns = asset_namespace(entry)
                try:
                    doc = json.loads(zf.read(entry).decode("utf-8", "replace"))
                except (ValueError, OSError):
                    continue
                for const in doc if isinstance(doc, list) else []:
                    if isinstance(const, dict) and const.get("name"):
                        constants.setdefault(ns, {})[const["name"]] = const.get("ingredient")

            for entry in entries:
                parts = entry.split("/")
                ns = asset_namespace(entry)
                if not ns or len(parts) < 4 or parts[2] != "recipes":
                    continue
                if parts[-1].startswith("_"):
                    continue  # _constants.json / _factories.json are not recipes
                stats["files"] += 1
                try:
                    doc = json.loads(zf.read(entry).decode("utf-8", "replace"))
                except (ValueError, OSError):
                    stats["skipped"] += 1
                    continue
                rid = "%s!%s" % (os.path.basename(jar), entry)
                recipe = parse_recipe_json(doc, rid, constants.get(ns), ns)
                if recipe is None:
                    stats["skipped"] += 1
                    continue
                stats["recipes"] += 1
                yield recipe
    if on_progress:
        on_progress("done", stats)
