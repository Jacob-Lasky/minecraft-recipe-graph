"""A small graph carrying NBT-discriminated keys, shared by every suite that needs one.

WHY THIS EXISTS. #20 shipped the discriminator with a review pass and 230 green tests and
still broke three things within the hour (#23, #28, #34). Every unit test was sound in
isolation; none of them put a discriminated key through a whole pipeline, and all three
regressions lived in the seams between components:

    key -> rendered href            `#` not escaped, so most plan links 404
    catalyst -> graph.producers     a bare key finds none of its variants
    recipe -> container detection   both structural signals stopped matching

A schema change also cannot be reviewed against data from the old schema, and the
schema-3 dump needs the game to produce. This fixture is what stands in for it, so the
seams are exercised on a laptop in milliseconds.

DO NOT replace the digests with readable words. They are 12 hex characters because that
is what `DumpCommand.discriminator` emits, and `model._variant_label` renders anything
else as a capitalised aspect name instead of "variant a3f19c", which would quietly stop
testing the fallback path that most real keys take.
"""

from recipegraph.model import Graph, Ingredient, Recipe

# Eight is CONTAINER_FLUID_THRESHOLD. The fixture sits exactly ON the threshold rather
# than comfortably past it, so a change to either number has to be deliberate.
CANNED_FLUIDS = ("brine", "acid", "latex", "resin", "creosote", "seed_oil", "juice", "ink")

# One variant per fluid the can may hold. Digests are arbitrary but fixed: a test that
# asserts on a rendered label needs them stable.
CAN_DIGESTS = {
    "brine": "aaaaaaaaaaaa",
    "acid": "bbbbbbbbbbbb",
    "latex": "cccccccccccc",
    "resin": "dddddddddddd",
    "creosote": "eeeeeeeeeeee",
    "seed_oil": "111111111111",
    "juice": "222222222222",
    "ink": "333333333333",
}

CAN_BASE = "mod:can:1"
MACHINE_BASE = "mod:machine:1"
MACHINE_VARIANT = "mod:machine:1#f56885268ad5"
NAMED_CAN = "mod:can:1#aaaaaaaaaaaa"
UNNAMED_CAN = "mod:can:1#bbbbbbbbbbbb"


def discriminated_graph():
    """A container, a machine, and one honest recipe, all carrying NBT variants.

    Shapes deliberately mirrored from the real pack:

    * The can EMPTIES to its material plus a fluid, exactly as Forestry's squeezer does
      (`forestry:can:1#48a337d94489 -> forestry:ingot_tin + 1,000 mB borax_solution`).
      The empty can is NOT among the outputs, which is why a naive `X#d -> X` test misses
      it and why the detector compares base keys instead.
    * The machine's crafting recipe outputs a DISCRIMINATED key while the catalyst names
      the bare one, which is #28's shape.
    * `fluid:brine` has one real producer as well as the container route, so a test can
      tell "the transfer lost" from "the transfer was the only option".
    """
    g = Graph()
    g.dump_schema = 3
    g.names = {
        CAN_BASE: "Empty Can",
        # Named variant and unnamed variant, so both label paths render: this one comes
        # from the dump's names.json, the other falls back to "variant bbbbbb".
        NAMED_CAN: "Brine Can",
        MACHINE_BASE: "Arc Furnace",
        "mod:ingot_tin": "Tin Ingot",
        "mod:salt": "Salt",
        "mod:plate": "Machine Plate",
    }
    g.catalysts = {
        # Names the BARE key while the only recipe below makes the discriminated one.
        "mod.arc_furnace": [MACHINE_BASE],
    }

    for fluid in CANNED_FLUIDS:
        g.add(Recipe(
            "squeeze_%s" % fluid, "t",
            [("mod:ingot_tin", 1), ("fluid:%s" % fluid, 1000)],
            [Ingredient(["%s#%s" % (CAN_BASE, CAN_DIGESTS[fluid])], 1)],
            category="mod.squeezer", machine="Squeezer",
        ))

    # A real, non-container route to one of the same fluids.
    g.add(Recipe("boil", "t", [("fluid:brine", 1000)],
                 [Ingredient(["mod:salt"], 4)],
                 category="mod.boiler", machine="Boiler"))

    # The machine itself: craftable, and its recipe emits the discriminated key.
    g.add(Recipe("craft_machine", "t", [(MACHINE_VARIANT, 1)],
                 [Ingredient(["mod:plate"], 4)],
                 category="crafting"))

    # Something the arc furnace makes, so `mod.arc_furnace` is a real category with a
    # detail page rather than a catalyst pointing at nothing. Without this the machines
    # page has no row to render and #28's shape cannot be observed end to end.
    g.add(Recipe("arc_plate", "t", [("mod:plate", 2)],
                 [Ingredient(["mod:salt"], 1)],
                 category="mod.arc_furnace", machine="Arc Furnace"))

    return g
