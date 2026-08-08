"""Recipe source: the NDJSON dump produced by the `mc-recipe-dump` mod.

This is the only COMPLETE source. It reads HEI/JEI's own recipe registry, so it
contains every recipe type any of the ~366 mods registered -- NuclearCraft
chemistry, Modular Machinery, inscribers, centrifuges, everything the player sees
in the recipe viewer. The jar_json source cannot see any of that.

Format: one JSON object per line (NDJSON, so a 100k-recipe dump streams instead of
loading whole). Written by RecipeDumpMod.java; keep the two in sync.

  {"cat":"nuclearcraft.chemical_reactor","title":"Chemical Reactor",
   "in":[[{"i":"minecraft:water_bucket","m":0,"c":1}], ...],
   "out":[{"i":"nuclearcraft:compound","m":7,"c":1}],
   "fin":[[{"f":"water","a":1000}]], "fout":[]}

`in` is a list of SLOTS, each slot a list of interchangeable stacks. That nesting
is deliberate and must be preserved -- it is where oredict/multi-input choice
lives, and flattening it would destroy the solver's ability to pick what you own.

An item stack may carry `"n"`, a digest of the NBT that decides what it IS (schema 3
and up). It becomes a `#suffix` on the key, so a Forest drone and a Meadows drone are
different ingredients -- see DumpCommand.discriminator. Absent on older dumps, which
then behave exactly as before.

An INPUT stack may carry `"p"`, the probability a run CONSUMES it, 0.0 to 1.0 (#175).
**Absent means 1.0**, so every dump written before that field existed reads exactly as it
did before, which is the property that let the reader land ahead of the mod change. 0.0 is
an input the run never spends: the pack declares 1,078 with `setChance(0.0)` and another 502
with CraftTweaker's `.reuse()`. It sits on the STACK and not on the slot because `in` is a
list of arrays with no slot object to hang a field on, and changing that shape would break
every reader.

An OUTPUT stack may carry `"q"`, the probability a run YIELDS it, 0.0 to 1.0 (schema 8, #223).
**Absent means 1.0.** It is a DIFFERENT FIELD FROM `p` and must stay one: `p` is how much of an
input a run spends, `q` is how often a run produces an output, and a consumer of `p` reading a
yield would treat the output as a catalyst. The pack makes 835 `setChance` calls against
`addItemOutput` and 834 of them are fractional, spanning 0.99 down to 0.001, so the yield a
plan divides by was up to 1000x too high.
"""

import json

from ..model import Ingredient, Recipe, fluid_key, norm_key
from ..names import clean_label


def _stack_key(entry):
    if "f" in entry:
        return fluid_key(entry["f"])
    if "i" not in entry:
        return None
    key = norm_key(entry["i"], entry.get("m", 0))
    # `#suffix` rather than a separate field: it is the convention the AE2 reader already
    # uses for vis pods, `model.bare_name` already renders it, and it keeps a
    # discriminated stack from ever unifying with the bare item by accident.
    nbt = entry.get("n")
    return "%s#%s" % (key, nbt) if nbt else key


def _chance(entry, field):
    """A probability field on a stack, or 1.0 when it is absent OR unusable. Never raises.

    ONE VALIDATOR FOR BOTH `p` (#175) AND `q` (#223), because every trap below is a property
    of "a float arrived from a JSON line written by a mod", not of which question the float
    answers. The two callers keep their own docstrings, since what 1.0 MEANS differs between
    them and that is the part a reader needs.

    MALFORMED AND "CERTAIN" ARE DIFFERENT FACTS, AND THIS DELIBERATELY MAPS THE FIRST ONTO
    THE SECOND. 1.0 is the value that changes nothing: it is what every dump written before
    the field existed already means, so a garbled value degrades to the previous reading
    rather than to a new and confidently wrong one. Every other landing spot is worse, and
    three of them were reachable before this validation existed:

      `false`   `float(False)` is 0.0, which on the input side declares the slot a PERMANENT
                catalyst and on the output side declares the recipe unable to make its own
                product. `isinstance(True, int)` is True in Python, so a plain `(int, float)`
                check does NOT exclude it; `dump_meta._count` excludes `bool` for the same
                reason.
      `"0.0"`   a quoted number did the same thing, because `float` accepts strings.
      `null`    `float(None)` RAISES, so one malformed line aborted a 335,000-recipe build
                with a TypeError naming neither the line nor the field.

    OUT OF RANGE IS ALSO REFUSED, not clamped, and the harm differs by field. A negative `p`
    scales an ingredient's cost NEGATIVE in `cost._relax`, which is not a mispriced route but
    an arbitrage: the more of it a recipe demands the cheaper the recipe gets. A `q` above 1.0
    manufactures yield out of nothing. Clamping either would be a reading of data that is not
    there, so the field is treated as unusable and the default stands.
    """
    if field not in entry:
        return 1.0
    raw = entry[field]
    # `bool` first: it is a subclass of `int`, so the isinstance check below would accept it.
    if isinstance(raw, bool) or not isinstance(raw, (int, float)):
        return 1.0
    value = float(raw)
    # `value != value` is the NaN test that needs no import, and NaN fails every comparison
    # below, so it would otherwise slip through as "in range".
    if value != value or value < 0.0 or value > 1.0:
        return 1.0
    return value


def _yield_chance(entry):
    """The output stack's `q`, or 1.0 when absent or unusable. See #223.

    **Absent means 1.0**, which is what every dump before schema 8 means, so the reader
    could land ahead of the mod change exactly as #175's did.

    A SEPARATE FIELD FROM `p` ON PURPOSE, and `DumpCommand.stack` says so from the emitting
    end. `p` answers "how much of this input does a run spend"; `q` answers "how often does a
    run yield this output". They are not the same question and a consumer of `p` reading a
    yield would treat the output as a catalyst.

    THE DEFAULT IS THE ONE DIRECTION THAT IS NOT CONSERVATIVE, and that is deliberate. A
    missing or garbled `q` leaves a chance output reading as guaranteed, which is the defect
    #223 exists to fix, so the fallback preserves the bug rather than curing it. The
    alternative is worse: inventing a fractional yield from a typo multiplies the planned
    runs, and every input those runs consume, by up to 1000x on a value nobody wrote.
    """
    return _chance(entry, "q")


def _consume_chance(entry):
    """The input stack's `p`, or 1.0 when it is absent OR unusable. See #175.

    **Absent means 1.0**, so every dump written before that field existed reads exactly as it
    did before, which is the property that let the reader land ahead of the mod change. 0.0 is
    an input the run never spends: the pack declares 1,078 with `setChance(0.0)` and another
    502 with CraftTweaker's `.reuse()`.

    HERE 1.0 IS ALSO THE CONSERVATIVE DIRECTION, which is what distinguishes this field from
    `q`. It says "assume a run spends this", so a malformed value can only ever overstate a
    price. `_yield_chance` has no such luck and says so.

    The validation itself, and the three malformed shapes that reached this before it existed,
    live in `_chance`.
    """
    return _chance(entry, "p")


def _slot_to_ingredient(slot, role="item"):
    if isinstance(slot, dict):
        slot = [slot]
    alts, qty, chance = [], 1, 0.0
    for entry in slot or []:
        key = _stack_key(entry)
        if not key:
            continue
        alts.append(key)
        qty = max(qty, int(entry.get("c", entry.get("a", 1)) or 1))
        # `max`, THE SAME WAY AND FOR A STRONGER REASON THAN `qty` ABOVE. `p` lives on the
        # STACK because `in` is a list of arrays with no slot object to hang it on, so a slot
        # of interchangeable stacks carries one `p` per alternative. In practice they agree:
        # Modular Machinery's chance is a property of the REQUIREMENT, not of the item that
        # satisfies it. Should they ever disagree, taking the highest is the arm that cannot
        # invent a free route -- `min` would let one reusable alternative in a slot make the
        # whole slot retained, which is exactly the "cheapest thing is the thing you cannot
        # obtain" defect #176 fixed. Overstating consumption only ever overstates a price.
        chance = max(chance, _consume_chance(entry))
    if not alts:
        return None
    return Ingredient(alts, qty, role, chance)


def extract(path, on_progress=None):
    stats = {"lines": 0, "recipes": 0, "skipped": 0}
    with open(path, encoding="utf-8", errors="replace") as fh:
        for lineno, line in enumerate(fh, 1):
            line = line.strip()
            if not line or line.startswith("//"):
                continue
            stats["lines"] += 1
            try:
                doc = json.loads(line)
            except ValueError:
                stats["skipped"] += 1
                continue

            outputs, yields = [], []
            for entry in doc.get("out") or []:
                key = _stack_key(entry)
                if key:
                    outputs.append((key, max(1, int(entry.get("c", 1) or 1))))
                    yields.append(_yield_chance(entry))
            for entry in doc.get("fout") or []:
                key = _stack_key(entry)
                if key:
                    outputs.append((key, max(1, int(entry.get("a", 1) or 1))))
                    # READ FROM THE FLUID STACK TOO, even though the emitter does not write
                    # it there yet. `fout` entries are the same object shape as `out` ones,
                    # the two lists become one `outputs` list here, and an emitter that later
                    # learns to mark a chance fluid output must not need a reader change to
                    # be believed. Absent is 1.0, so this is inert until then.
                    yields.append(_yield_chance(entry))
            if not outputs:
                stats["skipped"] += 1
                continue

            slots = []
            for slot in doc.get("in") or []:
                ing = _slot_to_ingredient(slot, "item")
                if ing:
                    slots.append(ing)
            for slot in doc.get("fin") or []:
                ing = _slot_to_ingredient(slot, "fluid")
                if ing:
                    slots.append(ing)
            if not slots:
                stats["skipped"] += 1
                continue

            cat = doc.get("cat", "unknown")
            stats["recipes"] += 1
            yield Recipe(
                "hei:%s:%d" % (cat, lineno), "hei_dump", outputs, slots,
                yield_chance=None if all(c == 1.0 for c in yields) else yields,
                # Titles carry colour/format codes verbatim ("Wire Mill§r"), and 22
                # categories in the reference pack do. Strip them HERE so no downstream
                # consumer has to: an uncleaned title fails the machine name lookup
                # silently, which reads as "you do not own this machine".
                category=cat, machine=clean_label(doc.get("title")),
            )
            if on_progress and stats["recipes"] % 5000 == 0:
                on_progress(cat, stats)
    if on_progress:
        on_progress("done", stats)
