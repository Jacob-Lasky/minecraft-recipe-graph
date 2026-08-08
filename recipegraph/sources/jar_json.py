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

    DELIBERATELY DOES NOT LOOK AT `doc["conditions"]`; ask `condition_verdict` FIRST. A
    recipe whose conditions are unmet is one Forge never registers, so parsing it yields a
    recipe the game does not have -- but the verdict needs the pack's modid set, which is a
    property of the whole `mods/` directory and not of one document, and folding it in here
    would collapse "Forge refused this" and "this parser could not read it" into one None.
    `extract` keeps them apart because it counts them separately. See #227.
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


MET, UNMET, UNEVALUABLE = "met", "unmet", "unevaluable"

# The ONLY condition type this evaluates on its own. `forge:and`, `forge:or` and `forge:not`
# are handled too, but they decide nothing by themselves -- they are combinators over this one
# -- and `forge:false` is a constant. Everything else is counted and dropped; `condition_verdict`
# says why the line is drawn here.
MOD_LOADED = "forge:mod_loaded"


def _leaf_verdict(node, modids):
    """One condition node -> True / False / None, where None means "cannot tell offline"."""
    if not isinstance(node, dict):
        return None
    ctype = node.get("type")
    if ctype == MOD_LOADED:
        modid = node.get("modid")
        # A `forge:mod_loaded` with no modid is FALSE, not unreadable, and that is a decision
        # rather than a fallthrough: Forge fails to deserialise the condition and skips the
        # whole recipe file, so "the game does not have this recipe" is the fact, which is
        # exactly what UNMET means here. Returning None would file a real outcome under
        # "could not tell" and quietly inflate the number that invites someone to look.
        return bool(modid) and modid in modids
    if ctype == "forge:false":
        return False
    if ctype == "forge:not":
        # THE BRANCH THAT CHANGES AN OUTCOME. Botania's cocoon recipe is `forge:not` over an
        # absent `gardenofglass`, so it resolves TRUE and Forge really does register it: a
        # reader that dropped anything carrying a `conditions` block would delete a live recipe.
        inner = _leaf_verdict(node.get("value"), modids)
        return None if inner is None else not inner
    if ctype in ("forge:and", "forge:or"):
        values = node.get("values")
        if not isinstance(values, list) or not values:
            return None
        parts = [_leaf_verdict(v, modids) for v in values]
        # SHORT-CIRCUIT BEFORE GIVING UP: `and` with one false leaf is false whatever the
        # unreadable leaves say, and `or` with one true leaf is true.
        #
        # ON THIS PACK THIS BUYS ACCURACY, NOT RECIPES, AND THE COMMENT SAYS SO RATHER THAN
        # OVERSELLING IT. Measured over its 520 `forge:and` and 58 `forge:or`: short-circuiting
        # changes the verdict on 54 `forge:and` and on ZERO `forge:or`. Every one of those 54
        # is `unevaluable` -> `unmet`, so the recipe is dropped either way and what moves is
        # which counter it lands in -- 54 outcomes reported as facts instead of as mysteries,
        # which is the whole point of keeping the two counts apart. The `or` arm is the one
        # that could keep a recipe, and nothing in this pack exercises it yet; it is here
        # because the day something does, the alternative silently deletes a live recipe.
        if ctype == "forge:and":
            if any(p is False for p in parts):
                return False
            return None if any(p is None for p in parts) else True
        if any(p is True for p in parts):
            return True
        return None if any(p is None for p in parts) else False
    return None


def condition_verdict(doc, modids):
    """Would Forge register this recipe? `met` / `unmet` / `unevaluable`. Issue #227.

    ONLY `forge:mod_loaded` IS EVALUATED, AND THAT LINE IS DELIBERATE. It is the one condition
    whose meaning is fixed by Forge and whose answer is fully determined by the jars on disk,
    which is all this source can see. The pack's other types are not: `minecraft:item_exists`
    asks whether a mod registered an item under a config it read at runtime,
    `tconstruct:is_option_enabled` and `tconstruct:is_pulse_loaded` read Tinkers' own config,
    `teslacorelib:ore_dict` reads an ore dictionary that CraftTweaker rewrites during load.
    Guessing any of them means inventing a recipe the player does not have, which is the exact
    harm #227 measured.

    `zerocore:modloaded` is NOT treated as an alias of `forge:mod_loaded` even though it looks
    like one (2 uses, both `modid=tesla`). A third-party type that happens to share a shape is
    not the same contract, and mapping it by eye is how a reader starts asserting things about
    mods nobody checked. It falls to `unevaluable` and is counted like the rest.

    UNEVALUABLE IS RETURNED, NOT FOLDED INTO `unmet`. The caller drops both, but only one of
    them is a fact. On the reference pack that is 73 unmet against 2,012 unevaluable, and a
    build that says "2,012 I could not evaluate" invites someone to look where "2,085 unmet"
    closes the question falsely.
    """
    conds = doc.get("conditions") if isinstance(doc, dict) else None
    if not conds:
        return MET
    if isinstance(conds, dict):
        conds = [conds]
    if not isinstance(conds, list):
        return UNEVALUABLE
    # Forge ANDs the top-level list, so one false entry settles it however unreadable the rest.
    verdicts = [_leaf_verdict(c, modids) for c in conds]
    if any(v is False for v in verdicts):
        return UNMET
    return UNEVALUABLE if any(v is None for v in verdicts) else MET


def loaded_modids(mods_dir):
    """Every modid the pack plausibly loads, for `forge:mod_loaded`. Issue #227.

    TWO SIGNALS, UNIONED, BECAUSE NEITHER IS COMPLETE ALONE. `mcmod.info` is the declared
    answer, but #208 counted 22 jars with none usable out of the 367 in the reference
    CLIENT instance -- they declare through their MANIFEST instead -- so a mcmod.info-only
    set would call a loaded mod absent. The `assets/<ns>/` directory names cover those, and
    over-cover slightly, since a jar may ship compat assets under another mod's namespace.
    (Name the denominator whenever you quote that 22: the server pack this is usually pointed
    at holds 364 jars, not 367.)

    THE UNION IS THE SAFE DIRECTION AND THAT IS WHY IT IS A UNION. Over-counting loaded mods
    can only make `forge:mod_loaded` answer true where it should answer false, which KEEPS a
    recipe; under-counting DELETES one that the game has. Given the choice, this source would
    rather leave a recipe the dump will overrule than remove one nothing else supplies.
    """
    modids = set()
    for name in sorted(os.listdir(mods_dir)):
        if not name.lower().endswith(".jar"):
            continue
        try:
            zf = zipfile.ZipFile(os.path.join(mods_dir, name))
        except (zipfile.BadZipFile, OSError):
            continue
        with zf:
            for entry in zf.namelist():
                ns = asset_namespace(entry)
                if ns:
                    modids.add(ns)
            try:
                doc = json.loads(zf.read("mcmod.info").decode("utf-8", "replace"))
            except (KeyError, ValueError, OSError):
                continue
            entries = doc.get("modList") if isinstance(doc, dict) else doc
            for mod in entries if isinstance(entries, list) else []:
                if isinstance(mod, dict) and mod.get("modid"):
                    modids.add(str(mod["modid"]))
    return modids


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

    Sibling of `_is_recipe_entry` and here for the same reason it gives: `extract` needs the
    namespace to qualify an unqualified id (#227), `loaded_modids` needs it to know which mods
    the pack ships, and a third copy of `entry.split("/")[1]` is how two readers stop agreeing
    about what a namespace is.
    """
    if not entry.startswith("assets/"):
        return None
    parts = entry.split("/")
    return parts[1] if len(parts) > 2 and parts[1] else None


def extract(mods_dir, on_progress=None):
    """Walk every jar directly under mods_dir and yield Recipe objects.

    Immediate subdirectories are NOT read; `unread_subdir_jars` reports them and says why.

    `stats` carries THREE reasons a document produced nothing, not one, because they mean
    different things to whoever reads the build output (#227): `skipped` is this parser
    failing to read a document, `cond_unmet` is Forge declining to register it, and
    `cond_unevaluable` is a condition this source cannot decide offline. Collapsing the last
    two would report a guess as a fact. `modids` is not a drop reason -- it is the
    denominator `cond_unmet` was judged against, carried so the build can name it.
    """
    jars = sorted(
        os.path.join(mods_dir, f) for f in os.listdir(mods_dir) if f.lower().endswith(".jar")
    )
    # A whole-directory pass before the first recipe, because `forge:mod_loaded` asks about the
    # pack and not about the jar the question was found in.
    modids = loaded_modids(mods_dir)
    stats = {"jars": 0, "files": 0, "recipes": 0, "skipped": 0,
             "modids": len(modids), "cond_unmet": 0, "cond_unevaluable": 0}
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
                # BEFORE PARSING, because an unmet condition means Forge never registered this
                # and the parse would only produce a recipe the game does not have.
                verdict = condition_verdict(doc, modids)
                if verdict == UNMET:
                    stats["cond_unmet"] += 1
                    continue
                if verdict == UNEVALUABLE:
                    stats["cond_unevaluable"] += 1
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
