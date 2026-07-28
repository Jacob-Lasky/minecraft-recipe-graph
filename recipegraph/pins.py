"""A human's recipe choice, and how it survives the next dump.

WHY THIS EXISTS. Jake: *"i can't reroute a tree's path even though it certainly exists
within the graph. but I want to be able to redirect and pick the path and SAVE that path
so that it doesn't get overwritten (i'm fine with suggestions)."* Suggestions are welcome,
silent overwrites are not: the ranking keeps proposing, and a pin outranks the proposal
until it is withdrawn. See #30.

THE HARD PART IS THE IDENTITY, NOT THE UI. A recipe id is `hei:<category>:<line number>`
or `<jar>!assets/.../file.json`. Neither survives what Jake does often: a redump renumbers
every hei line, and a mod update renames the jar. A pin stored by id would silently stop
applying, which is the exact failure mode the feature exists to prevent, so a pin is
stored by a FINGERPRINT of what the recipe IS.

The fingerprint deliberately changes when the pack changes the recipe, because that is
when you want the pin to lapse and say so rather than to keep pointing at something that
now makes a different thing. And it deliberately does NOT cover the machine name (a
localised display string) or the source (which extractor found it), because neither
changes what the recipe does.

Three outcomes, and the middle one is why the category is stored alongside:

  `exact`     the fingerprinted recipe is still here. Use it.
  `category`  it is gone, but the pack still makes this item that way. "Make iron by
              smelting" is usually what a pin MEANT, so fall back to the category and
              say so, rather than silently reverting to the ranking.
  `dead`      nothing here makes it that way any more. Report it; change nothing.

A fingerprint is not unique and is not meant to be: 437 of the pack's bee mutations are
byte-identical recipes, and pinning one of them means any of them will do.
"""

import hashlib
import json
import os

# Field, slot and alternative separators. Control characters because an item key is
# `mod:name:meta#digest`, `ore:name` or `fluid:name` and a category uid is dotted: none of
# them can contain these, so no value can imitate the structure around it.
_FIELD = "\x1f"
_SLOT = "\x1e"
_ALT = "\x1d"

FINGERPRINT_DIGITS = 12

EXACT = "exact"
CATEGORY = "category"
DEAD = "dead"


def fingerprint(recipe):
    """A short id for WHAT a recipe is, stable across dumps.

    blake2b rather than `nbt_digest.fnv1a`, which is a cross-language contract with the
    mod: tying pin identity to it would mean a change to the dump's NBT format silently
    lapsed every pin. These two hashes have nothing to do with each other and should not
    share an implementation.
    """
    parts = [recipe.category or ""]
    for key, qty in sorted(recipe.outputs):
        parts.append("o%s%s%s" % (key, _SLOT, qty))
    # Sorted, because slot order is an artefact of how the extractor walked the recipe and
    # two dumps of one recipe must not fingerprint differently over it.
    # Alternatives sorted too. Their order carries a meaning elsewhere (the first is the
    # canonical one) but not here: a slot that accepts any of three ores is the same slot
    # whichever one an extractor happened to list first, and letting that reorder lapse a
    # pin would make pins feel arbitrary.
    slots = sorted((_ALT.join(sorted(ing.alternatives)), ing.qty, ing.role or "")
                   for ing in recipe.inputs)
    for alts, qty, role in slots:
        parts.append("i%s%s%s%s%s" % (alts, _SLOT, qty, _SLOT, role))
    digest = hashlib.blake2b(_FIELD.join(parts).encode("utf-8"),
                             digest_size=FINGERPRINT_DIGITS // 2)
    return digest.hexdigest()


def label(graph, recipe):
    """One line naming a recipe, for a pin file a human has to be able to read.

    Stored with the pin rather than looked up when it is shown, so a pin whose recipe has
    vanished can still say what it used to point at. A dead pin that can only report a
    hex string is a pin nobody can decide what to do about.
    """
    ins = ", ".join(graph.bare_name(ing.alternatives[0])
                    for ing in recipe.inputs[:4] if ing.alternatives)
    if len(recipe.inputs) > 4:
        ins += ", ..."
    out = graph.bare_name(recipe.outputs[0][0]) if recipe.outputs else "?"
    return "%s from %s" % (out, ins or "nothing")


def load(path):
    """`{item key: pin}` from disk. Never raises: a broken file must not break planning."""
    if not path or not os.path.exists(path):
        return {}
    try:
        with open(path) as fh:
            doc = json.load(fh)
    except (ValueError, OSError):
        return {}
    raw = doc.get("pins") if isinstance(doc, dict) else None
    if not isinstance(raw, dict):
        return {}
    out = {}
    for key, pin in raw.items():
        if isinstance(pin, dict) and isinstance(pin.get("fingerprint"), str):
            out[key] = {"fingerprint": pin["fingerprint"],
                        "category": str(pin.get("category") or ""),
                        "label": str(pin.get("label") or "")}
    return out


def save(path, pins):
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "w") as fh:
        json.dump({
            "_comment": "Recipe choices you made by hand. Keyed by item; `fingerprint` "
                        "identifies the recipe by its category, outputs and inputs, so a "
                        "pin survives a redump renumbering every recipe id. If the pack "
                        "changes the recipe the fingerprint stops matching and the pin "
                        "falls back to `category`, which the UI reports.",
            "pins": pins,
        }, fh, indent=1, sort_keys=True)


def make(graph, recipe):
    """The stored form of a pin on `recipe`."""
    return {"fingerprint": fingerprint(recipe),
            "category": recipe.category or "",
            "label": label(graph, recipe)}


def resolve(graph, pins):
    """`({item key: frozenset of acceptable recipe ids}, {item key: (state, note)})`.

    A SET rather than one id, so the category fallback and an exact hit are the same thing
    to the solver: it keeps its own ranking among whatever is acceptable instead of being
    handed a recipe picked here by dump order. That also means a fingerprint matching
    several identical recipes needs no special case.
    """
    accepted, notes = {}, {}
    for key, pin in pins.items():
        candidates = graph.real_producers(key)
        want = pin.get("fingerprint")
        exact = frozenset(r.rid for r in candidates if fingerprint(r) == want)
        if exact:
            accepted[key] = exact
            notes[key] = (EXACT, "")
            continue
        category = pin.get("category") or ""
        same = frozenset(r.rid for r in candidates if r.category == category)
        if same:
            accepted[key] = same
            notes[key] = (CATEGORY,
                          "the pinned recipe is gone; using another %s recipe" % category)
        else:
            notes[key] = (DEAD, "nothing here makes this %s any more"
                          % (category or "way"))
    return accepted, notes
