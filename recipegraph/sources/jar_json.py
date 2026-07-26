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


def _ingredient_alts(node, constants=None, _seen=None):
    """A JSON ingredient -> list of item keys. Handles ore dicts, arrays, nbt forms.

    `#name` resolves through Forge's `_constants.json` mechanism (38 jars in this
    pack use it). Unresolved `#name` yields nothing rather than a bogus
    `minecraft:#name` key -- a fake key would silently become a phantom raw item in
    every shopping list.
    """
    if node is None:
        return []
    if isinstance(node, list):
        alts = []
        for sub in node:
            alts.extend(_ingredient_alts(sub, constants, _seen))
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
                alts.extend(_ingredient_alts(sub, constants, _seen))
            return alts
        if isinstance(item, str) and item.startswith("#"):
            name = item[1:]
            seen = _seen or frozenset()
            if not constants or name in seen:
                return []
            target = constants.get(name)
            if target is None:
                return []
            return _ingredient_alts(target, constants, seen | {name})
        data = node.get("data", node.get("meta", 0))
        return [norm_key(item, data)]
    if "ore" in node:
        return [ore_key(node["ore"])]
    if "tag" in node and isinstance(node["tag"], str):
        return [ore_key(node["tag"])]
    return []


def _result_outputs(node):
    if not isinstance(node, dict):
        return []
    item = node.get("item")
    if not item:
        return []
    data = node.get("data", node.get("meta", 0))
    count = node.get("count", 1)
    try:
        count = int(count)
    except (TypeError, ValueError):
        count = 1
    return [(norm_key(item, data), max(1, count))]


def parse_recipe_json(doc, rid, constants=None):
    """One recipe document -> Recipe, or None if unsupported/undecodable."""
    if not isinstance(doc, dict):
        return None
    rtype = doc.get("type")
    outputs = _result_outputs(doc.get("result"))
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
            alts = _ingredient_alts(key_map.get(ch), constants)
            if alts:
                slots.append(Ingredient(alts, n))
    elif rtype in SHAPELESS or "ingredients" in doc:
        # Collapse duplicate ingredients into one slot with a qty.
        agg = {}
        order = []
        for ing in doc.get("ingredients") or []:
            alts = _ingredient_alts(ing, constants)
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


def extract(mods_dir, on_progress=None):
    """Walk every jar under mods_dir and yield Recipe objects."""
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
            entries = [
                e for e in zf.namelist()
                if e.endswith(".json") and e.startswith("assets/") and "/recipes/" in e
            ]
            # Pass 1: per-namespace `_constants.json`, needed before any `#name`
            # reference can be resolved. Must precede recipe parsing.
            constants = {}
            for entry in entries:
                if not entry.endswith("/_constants.json"):
                    continue
                ns = entry.split("/")[1]
                try:
                    doc = json.loads(zf.read(entry).decode("utf-8", "replace"))
                except (ValueError, OSError):
                    continue
                for const in doc if isinstance(doc, list) else []:
                    if isinstance(const, dict) and const.get("name"):
                        constants.setdefault(ns, {})[const["name"]] = const.get("ingredient")

            for entry in entries:
                parts = entry.split("/")
                if len(parts) < 4 or parts[2] != "recipes":
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
                recipe = parse_recipe_json(doc, rid, constants.get(parts[1]))
                if recipe is None:
                    stats["skipped"] += 1
                    continue
                stats["recipes"] += 1
                yield recipe
    if on_progress:
        on_progress("done", stats)
