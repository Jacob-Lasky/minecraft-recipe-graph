"""Pricing a trip: an ore that only generates somewhere you have never been.

Issue #112, and the half of #105 that could not ship then. #105 priced the pack's gate
ITEMS and could not price a PLACE, because travelling is not a recipe. So
`contenttweaker:sednanite_ore` -- registered `oreSednanite`, therefore priced by #106 as
something you go and mine -- cost exactly what a cobblestone costs. You mine it on Sedna.
The reference save has no DIM147 directory at all.

TWO SOURCES, KEPT APART, and the split is the design:

    what only grows there   pack data      graph.dimension_ores   (planetDefs.xml)
    whether you have been   world state    the have file          (region directories)

so the gate lifts by itself once the save has terrain for the place.

THE ISSUE'S OWN FRAMING WAS WRONG IN TWO WAYS AND BOTH ARE PINNED BELOW. It treated
planets as a category distinct from dimensions -- Advanced Rocketry registers them as
ordinary dimensions with ordinary ids -- and it recorded that the Nether could not be
detected because a vanilla portal has no tile entity. True about the portal, irrelevant
about the dimension: `DIM-1` holds 42 region files on the reference save, because entering
a dimension generates it.
"""

import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import cost, dimensions  # noqa: E402
from recipegraph.model import Graph, Ingredient, Recipe  # noqa: E402

# Trimmed from the reference pack's config/advRocketry/planetDefs.xml, shapes preserved:
# a gas giant with no DIMID that only groups moons, a nested moon that is its own
# dimension, an ore declared by two dimensions, and a block the overworld obviously also
# has (`minecraft:iron_block` really is on Osiris in the pack).
PLANET_XML = """<?xml version="1.0" encoding="UTF-8"?>
<galaxy>
  <star name="Sol">
    <planet name="Vulcan" customIcon="GasGiantRed">
      <GasGiant>true</GasGiant>
      <planet name="Sedna" DIMID="147" customIcon="IceWorld">
        <OreGen>
          <ore block="contenttweaker:sednanite_ore" minHeight="10" maxHeight="40"/>
        </OreGen>
        <artifact>contenttweaker:sedna_artifact</artifact>
      </planet>
    </planet>
    <planet name="Osiris" DIMID="148">
      <OreGen>
        <ore block="minecraft:iron_block"/>
        <ore block="thermalfoundation:storage" meta="5"/>
        <ore block="mod:shared_ore"/>
      </OreGen>
    </planet>
    <planet name="Rhenia" DIMID="163">
      <OreGen>
        <ore block="contenttweaker:rhenium_ore"/>
        <ore block="mod:shared_ore"/>
      </OreGen>
    </planet>
  </star>
</galaxy>
"""

SEDNANITE = "contenttweaker:sednanite_ore"
RHENIUM = "contenttweaker:rhenium_ore"

STATES = {"minecraft.crafting": ("have", ""), "mod.condenser": ("unavailable", "")}


def parsed():
    return dimensions.parse_planet_defs(PLANET_XML)


class ReadingThePackTest(unittest.TestCase):
    def test_every_dimension_with_an_id_is_found(self):
        self.assertEqual(sorted(parsed()), [147, 148, 163])

    def test_a_gas_giant_is_not_a_dimension(self):
        """Vulcan groups its moons and cannot be landed on, so nothing can be locked to it."""
        self.assertNotIn("Vulcan", [name for name, _ores in parsed().values()])

    def test_a_moon_is_a_dimension_in_its_own_right(self):
        name, ores = parsed()[147]
        self.assertEqual(name, "Sedna")
        self.assertEqual(ores, [SEDNANITE])

    def test_meta_becomes_part_of_the_key(self):
        _name, ores = parsed()[148]
        self.assertIn("thermalfoundation:storage:5", ores)

    def test_an_ore_two_dimensions_share_is_locked_to_neither(self):
        self.assertNotIn("mod:shared_ore", dimensions.exclusive_keys(parsed()))

    def test_the_exclusive_ones_are_kept(self):
        self.assertEqual(dimensions.exclusive_keys(parsed()).get(SEDNANITE), "Sedna")
        self.assertEqual(dimensions.exclusive_keys(parsed()).get(RHENIUM), "Rhenia")

    def test_a_missing_config_is_not_an_error(self):
        self.assertEqual(dimensions.load_planet_defs("/nonexistent"), {})

    def test_junk_parses_to_nothing_rather_than_raising(self):
        """A hand-edited pack config must not be able to take the build down."""
        self.assertEqual(dimensions.parse_planet_defs("<galaxy><broken"), {})


class ReadingTheSaveTest(unittest.TestCase):
    """Visited-ness, which is the claim #112 said could not be made about the Nether."""

    def _save(self, spec):
        d = tempfile.mkdtemp()
        for folder, regions in spec.items():
            region = os.path.join(d, folder, "region") if folder != "." \
                else os.path.join(d, "region")
            os.makedirs(region)
            for i in range(regions):
                open(os.path.join(region, "r.0.%d.mca" % i), "w").close()
        return d

    def test_the_nether_is_visible(self):
        """#112 said it was not. A portal has no tile entity; the terrain is still there."""
        seen = dimensions.visited(self._save({".": 2, "DIM-1": 42}))
        self.assertEqual(seen.get("DIM-1"), 42)
        self.assertTrue(dimensions.is_visited(-1, seen))

    def test_a_registered_but_unvisited_dimension_does_not_count(self):
        """Mods create the folder at load, so presence alone reports the whole pack."""
        seen = dimensions.visited(self._save({".": 2, "DIM147": 0}))
        self.assertNotIn("DIM147", seen)
        self.assertFalse(dimensions.is_visited(147, seen))

    def test_a_word_named_dimension_is_recorded(self):
        """`Dim-Aether` and `Iceika` are real folders in the reference save."""
        seen = dimensions.visited(self._save({".": 1, "Dim-Aether": 63}))
        self.assertEqual(seen.get("Dim-Aether"), 63)

    def test_the_overworld_is_always_visited(self):
        self.assertTrue(dimensions.is_visited(dimensions.HOME_DIMENSION, {}))

    def test_a_missing_save_is_not_an_error(self):
        self.assertEqual(dimensions.visited("/nonexistent"), {})


def gated_graph(condenser=True):
    """Sednanite: an `oreSednanite` world ore that only Sedna generates.

    Shaped from the real thing, including the Plasmatic Condenser route #106 documents --
    two recipes that "produce" the ore and want 160,000 mB of Dense Plasma. It is here
    because it is what makes the min-not-max claim testable: the ore has another way in,
    and the gate must not be able to hide it.
    """
    g = Graph()
    g.names = {SEDNANITE: "Sednanite Ore", "mod:plasma": "Dense Plasma"}
    g.ore_members = {"oreSednanite": [SEDNANITE]}
    g.dimension_ores = {SEDNANITE: [147, "Sedna"]}
    if condenser:
        g.add(Recipe("condense", "t", [(SEDNANITE, 1)],
                     [Ingredient(["mod:plasma"], 160000)], category="mod.condenser"))
    g.add(Recipe("smelt", "t", [("mod:ingot", 1)],
                 [Ingredient([SEDNANITE], 1)], category="minecraft.crafting"))
    return g


class PricingTheTripTest(unittest.TestCase):
    def _costs(self, visited=None, **kw):
        g = gated_graph(**kw)
        gates = dimensions.gates_for(g, visited or {})
        return g, cost.estimate(g, machine_states=STATES, dimension_gates=gates)

    def test_unvisited_costs_the_trip(self):
        _g, costs = self._costs(visited={"DIM-1": 42})
        self.assertAlmostEqual(costs[SEDNANITE],
                               cost.BASE_RAW_COST + cost.DIMENSION_COST, places=6)

    def test_visiting_it_lifts_the_gate_with_no_edit_to_any_list(self):
        _g, costs = self._costs(visited={"DIM147": 3})
        self.assertAlmostEqual(costs[SEDNANITE], cost.BASE_RAW_COST, places=6)

    def test_the_surcharge_reaches_what_is_made_from_it(self):
        _g, costs = self._costs(visited={"DIM-1": 42})
        self.assertGreater(costs["mod:ingot"], cost.DIMENSION_COST)

    def test_an_ungated_ore_is_untouched(self):
        g = gated_graph()
        g.dimension_ores = {}
        costs = cost.estimate(g, machine_states=STATES, dimension_gates={})
        self.assertAlmostEqual(costs[SEDNANITE], cost.BASE_RAW_COST, places=6)

    def test_stock_still_beats_the_gate(self):
        """`min` is still `min`: an ore in the network costs nothing at the margin."""
        g = gated_graph()
        gates = dimensions.gates_for(g, {"DIM-1": 42})
        costs = cost.estimate(g, have={SEDNANITE: 64}, machine_states=STATES,
                              dimension_gates=gates)
        self.assertEqual(costs[SEDNANITE], 0.0)

    def test_an_ore_nothing_produces_is_still_gated(self):
        """The strongest gate case, and the one the seed order nearly dropped.

        `_seed` prices a leaf -- no recipe outputs it -- before it prices world ores, and
        the world-ore rule is a `min`. So a gated ore with no producer got BASE_RAW_COST
        from the leaf pass and kept it: the gate computed, the plan's note appeared, and
        the price did not move. Found by running the real pack through it.
        """
        g = gated_graph(condenser=False)
        self.assertEqual(g.producers(SEDNANITE), [])
        gates = dimensions.gates_for(g, {"DIM-1": 42})
        costs = cost.estimate(g, machine_states=STATES, dimension_gates=gates)
        self.assertAlmostEqual(costs[SEDNANITE],
                               cost.BASE_RAW_COST + cost.DIMENSION_COST, places=6)

    def test_a_cheaper_crafted_route_still_wins(self):
        """The gate raises a FLOOR. It must not be able to hide a route that beats it.

        This is what bounds the damage of a misclassification: every ore the reference
        pack gates has between 1 and 6 producers, so a wrongly gated terrestrial ore keeps
        whatever its recipes cost.
        """
        g = gated_graph()
        g.add(Recipe("cheap", "t", [(SEDNANITE, 1)],
                     [Ingredient(["mod:dirt"], 1)], category="minecraft.crafting"))
        gates = dimensions.gates_for(g, {"DIM-1": 42})
        costs = cost.estimate(g, machine_states=STATES, dimension_gates=gates)
        self.assertAlmostEqual(costs[SEDNANITE],
                               cost.MACHINE_COST["have"] + cost.BASE_RAW_COST, places=6)


class TheOrderingIsTheClaimTest(unittest.TestCase):
    """#105's four properties, extended by the one #112 adds. Asserted, not left to prose.

    `DIMENSION_COST` sits between LOOT and GATE: a trip is a construction project you can
    decide to do this afternoon, so it is a smaller ask than a chapter that unlocks only by
    playing the story, and a bigger one than farming a boss you can already reach.
    """

    def test_a_trip_costs_more_than_a_boss(self):
        self.assertGreater(cost.DIMENSION_COST, cost.LOOT_COST)

    def test_a_trip_costs_less_than_a_locked_chapter(self):
        self.assertLess(cost.DIMENSION_COST, cost.GATE_COST)

    def test_a_trip_costs_more_than_going_outside(self):
        self.assertGreater(cost.DIMENSION_COST, cost.BASE_RAW_COST)

    def test_a_trip_is_possible_so_it_stays_finite(self):
        self.assertLess(cost.BASE_RAW_COST + cost.DIMENSION_COST,
                        cost.MACHINE_COST["unavailable"])

    def test_it_is_its_own_number(self):
        """#95's lesson: one figure for two unrelated statements destroys both orderings."""
        self.assertNotEqual(cost.DIMENSION_COST, cost.GATE_COST)
        self.assertNotEqual(cost.DIMENSION_COST, cost.LOOT_COST)

    def test_the_formula_version_moved(self):
        """`_seed` changed, and the cache is keyed on inputs that a formula change misses."""
        self.assertGreaterEqual(cost.FORMULA_VERSION, 8)

    def test_the_gates_are_in_the_fingerprint(self):
        """A gate moves when the player flies somewhere, with no other input changing."""
        with tempfile.NamedTemporaryFile(suffix=".json") as fh:
            a = cost.fingerprint(fh.name, {}, {}, {}, dimension_gates={})
            b = cost.fingerprint(fh.name, {}, {}, {}, dimension_gates={SEDNANITE: "Sedna"})
        self.assertNotEqual(a, b)


class NoRecordGatesNothingTest(unittest.TestCase):
    """A stock file written before #112 must not silently reprice the pack."""

    def test_an_empty_visited_map_gates_nothing(self):
        self.assertEqual(dimensions.gates_for(gated_graph(), {}), {})

    def test_a_graph_with_no_dimension_map_gates_nothing(self):
        g = gated_graph()
        g.dimension_ores = {}
        self.assertEqual(dimensions.gates_for(g, {"DIM-1": 1}), {})

    def test_the_gate_survives_a_graph_round_trip(self):
        """`dimension_ores` is built once and read by every later serve."""
        g = gated_graph()
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "graph.json")
            g.save(path)
            back = Graph.load(path)
        self.assertEqual(dimensions.gates_for(back, {"DIM-1": 42}), {SEDNANITE: "Sedna"})


class ThePlanSaysWhereTest(unittest.TestCase):
    """A route that got dearer without saying why is worse than one that never mentioned it."""

    def _node(self, visited):
        from recipegraph.solve import Solver
        g = gated_graph()
        gates = dimensions.gates_for(g, visited)
        costs = cost.estimate(g, machine_states=STATES, dimension_gates=gates)
        solver = Solver(g, machine_states=STATES, costs=costs, dimension_gates=gates)
        return solver.solve(SEDNANITE, 1)["tree"]

    def test_the_note_names_the_dimension(self):
        node = self._node({"DIM-1": 42})
        self.assertIn("Sedna", node["note"])
        self.assertEqual(node["dimension"], "Sedna")

    def test_a_visited_dimension_reads_as_an_ordinary_ore(self):
        node = self._node({"DIM147": 3})
        self.assertEqual(node["note"], "mined, not crafted")
        self.assertNotIn("dimension", node)


# The pack's OTHER id for the same rock. ContentTweaker's MaterialSystem generates an `ore`
# part for every material declared to it and packs those into shared holder blocks, so
# `<materialpart:sednanite:ore>` is a metadata slot of `sub_block_holder_1`. The block that
# actually generates on Sedna is the hand-made `VanillaFactory.createBlock("sednanite_ore")`.
# Same display name, same `oreSednanite` registration, two keys. See #117.
HOLDER = "contenttweaker:sub_block_holder_1:2"
# `oreUranium` really does hold this, because planetDefs declares ordinary uranium on Oi as
# ADDITIONAL generation. It is a Trionic Power Cell, not an ore, and it is why spreading a
# gate across the whole oredict group was built and thrown away.
POWER_CELL = "tardis:power_cell"


def shadow_graph(power_cell=False, same_name=True, same_group=True):
    """`gated_graph` plus the duplicate registration, and the recipe that only knows it.

    The literal input is the point. 26 of the reference pack's recipes consume the holder
    key directly rather than through `ore:oreSednanite`, so `Solver.pick_alternative` is
    never offered the properly priced block and no amount of ranking can rescue the plan.
    """
    g = gated_graph()
    g.names[HOLDER] = "Sednanite Ore" if same_name else "Sednanite Ore Chunk"
    g.ore_members["oreSednanite"] = [SEDNANITE] + ([HOLDER] if same_group else [])
    if not same_group:
        g.ore_members["blockSednanite"] = [HOLDER]
    if power_cell:
        g.names[POWER_CELL] = "Trionic Power Cell"
        g.ore_members["oreSednanite"].append(POWER_CELL)
    g.add(Recipe("melt", "t", [("fluid:sednanite", 100)],
                 [Ingredient([HOLDER], 1)], category="minecraft.crafting"))
    return g


class TheOtherIdForTheSameOreTest(unittest.TestCase):
    """#117: the gate priced the key planetDefs names, and the recipes consume the other one.

    The symptom was a plan whose node said "mined on Sedna, and you have not been there"
    above a number that had not moved, because the branch under it walked a different key.
    """

    def _costs(self, g, visited=None):
        gates = dimensions.gates_for(g, visited or {"DIM-1": 42})
        return cost.estimate(g, machine_states=STATES, dimension_gates=gates)

    def test_the_duplicate_registration_is_found(self):
        g = shadow_graph()
        self.assertEqual(dimensions.shadow_ores(g, g.dimension_ores),
                         {HOLDER: [147, "Sedna"]})

    def test_the_index_pass_puts_it_in_the_graph(self):
        g = shadow_graph()
        g.dimension_ores.update(dimensions.shadow_ores(g, g.dimension_ores))
        self.assertEqual(dimensions.gates_for(g, {"DIM-1": 42}),
                         {SEDNANITE: "Sedna", HOLDER: "Sedna"})

    def test_the_holder_key_is_charged_for_the_trip(self):
        """The defect itself: 1.0 before, because nothing produces it and it is a leaf."""
        g = shadow_graph()
        g.dimension_ores.update(dimensions.shadow_ores(g, g.dimension_ores))
        self.assertAlmostEqual(self._costs(g)[HOLDER],
                               cost.BASE_RAW_COST + cost.DIMENSION_COST, places=6)

    def test_what_the_recipes_actually_consume_gets_dearer(self):
        """The consequence the player sees: the fluid route stops looking like cobblestone."""
        g = shadow_graph()
        g.dimension_ores.update(dimensions.shadow_ores(g, g.dimension_ores))
        self.assertGreater(self._costs(g)["fluid:sednanite"], cost.DIMENSION_COST)

    def test_a_trionic_power_cell_is_not_an_ore_you_mine_on_oi(self):
        """The 1-in-5 that killed the group-only rule. The display name declines it."""
        g = shadow_graph(power_cell=True)
        self.assertNotIn(POWER_CELL, dimensions.shadow_ores(g, g.dimension_ores))

    def test_a_shared_name_alone_is_not_enough(self):
        """`chisel:lapis:0..8` are nine distinct blocks all called "Lapis Lazuli Block"."""
        g = shadow_graph(same_group=False)
        self.assertEqual(dimensions.shadow_ores(g, g.dimension_ores), {})

    def test_a_shared_ore_group_alone_is_not_enough(self):
        g = shadow_graph(same_name=False)
        self.assertEqual(dimensions.shadow_ores(g, g.dimension_ores), {})

    def test_a_block_group_is_not_ore_evidence(self):
        """`blockDiamond` holds `chisel:diamond`, which is the readmission #61 refused."""
        g = shadow_graph(same_group=False)
        g.ore_members["blockSednanite"] = [SEDNANITE, HOLDER]
        self.assertEqual(dimensions.shadow_ores(g, g.dimension_ores), {})

    def test_visiting_sedna_lifts_the_gate_on_both_keys_together(self):
        g = shadow_graph()
        g.dimension_ores.update(dimensions.shadow_ores(g, g.dimension_ores))
        self.assertEqual(dimensions.gates_for(g, {"DIM147": 3}), {})

    def test_nothing_gated_means_no_shadows(self):
        g = shadow_graph()
        self.assertEqual(dimensions.shadow_ores(g, {}), {})

    def test_the_two_keys_stay_separate_nodes(self):
        """Priced alike, NOT merged. Merging is a change to everything that walks a key."""
        g = shadow_graph()
        g.dimension_ores.update(dimensions.shadow_ores(g, g.dimension_ores))
        self.assertNotEqual(g.by_output.get(SEDNANITE), g.by_output.get(HOLDER))
        self.assertIn(HOLDER, g.names)
        self.assertIn(SEDNANITE, g.names)

    def test_a_shadow_with_its_own_cheap_route_keeps_it(self):
        """Still a FLOOR under `min`, exactly as the gate on the declared key is."""
        g = shadow_graph()
        g.dimension_ores.update(dimensions.shadow_ores(g, g.dimension_ores))
        g.add(Recipe("cheap", "t", [(HOLDER, 1)],
                     [Ingredient(["mod:dirt"], 1)], category="minecraft.crafting"))
        self.assertAlmostEqual(self._costs(g)[HOLDER],
                               cost.MACHINE_COST["have"] + cost.BASE_RAW_COST, places=6)

    def test_an_unnamed_key_cannot_shadow_anything(self):
        """`names` is incomplete for anything items.csv and the dump both missed."""
        g = shadow_graph()
        del g.names[HOLDER]
        self.assertEqual(dimensions.shadow_ores(g, g.dimension_ores), {})

    def test_the_plan_tells_you_where_the_holder_key_is_mined(self):
        """The note has to reach the key the branch actually walks, not just its twin."""
        from recipegraph.solve import Solver
        g = shadow_graph()
        g.dimension_ores.update(dimensions.shadow_ores(g, g.dimension_ores))
        gates = dimensions.gates_for(g, {"DIM-1": 42})
        costs = cost.estimate(g, machine_states=STATES, dimension_gates=gates)
        node = Solver(g, machine_states=STATES, costs=costs,
                      dimension_gates=gates).solve(HOLDER, 1)["tree"]
        self.assertEqual(node["dimension"], "Sedna")
        self.assertIn("Sedna", node["note"])


# What `oredict.removals_from_crafttweaker_log` returns for the reference pack's
# `scripts/OreDictionary.zs:63`, in the Sednanite spelling the fixtures above use. The pair
# is (display name, ore group) and NOT (key, group), because that is all CraftTweaker logs.
SEDNANITE_REMOVED = {("Sednanite Ore", "oreSednanite")}
# `scripts/TheGreatOreCleanse.zs:649`, the 134-line cross-mod unification pass. Same record
# shape, and the reason the mod clause exists.
IE_URANIUM = "immersiveengineering:ore:5"


class AGroupThePackDeletedIsStillEvidenceTest(unittest.TestCase):
    """#168: the 4th twin is in no `ore*` group, because the pack took it back out.

    `contenttweaker:sub_block_holder_1:8` is Rhenium Ore, `contenttweaker:rhenium_ore` is
    Rhenium Ore, and `scripts/OreDictionary.zs:63` registers the first into `oreRhenium` and
    then removes it. #117's test needs a SHARED group and the finished registry has none to
    share, so the twin kept a bare 1.0 against the real block's 2.0 and a plan for
    `fluid:rhenium` put the block that does not generate on the shopping list.

    The fixture is `shadow_graph(same_group=False)` -- the exact graph the two tests above
    require to yield nothing -- and the ONLY thing added is the pack's removal record. That
    pairing is the claim: this is not a looser rule over the same evidence, it is the same
    rule over a source that had not been read.
    """

    def test_the_removal_record_finds_the_twin(self):
        g = shadow_graph(same_group=False)
        self.assertEqual(dimensions.shadow_ores(g, g.dimension_ores, SEDNANITE_REMOVED),
                         {HOLDER: [147, "Sedna"]})

    def test_without_the_record_the_same_graph_yields_nothing(self):
        """The control for the test above, and #118's constraint restated as a pair."""
        g = shadow_graph(same_group=False)
        self.assertEqual(dimensions.shadow_ores(g, g.dimension_ores), {})

    def test_a_block_group_is_still_not_ore_evidence_even_with_a_record(self):
        """#61. A record for `blockSednanite` cannot gate, because it is not an `ore*` group.

        The removal reader returns every group the pack deleted from -- 134 on the reference
        pack, of which 131 are ingots, dusts and plates -- so the `ore*` filter is the only
        thing standing between the unification pass and a gate.
        """
        g = shadow_graph(same_group=False)
        self.assertEqual(
            dimensions.shadow_ores(g, g.dimension_ores,
                                   {("Sednanite Ore", "blockSednanite")}), {})

    def test_a_record_naming_another_group_does_not_fire(self):
        g = shadow_graph(same_group=False)
        self.assertEqual(
            dimensions.shadow_ores(g, g.dimension_ores,
                                   {("Sednanite Ore", "oreRhenium")}), {})

    def test_a_record_naming_another_label_does_not_fire(self):
        """The record is keyed by label, so the label has to be the anchor's."""
        g = shadow_graph(same_group=False)
        self.assertEqual(
            dimensions.shadow_ores(g, g.dimension_ores,
                                   {("Rhenium Ore", "oreSednanite")}), {})

    def test_a_differently_named_key_is_still_declined(self):
        """#117's display-name clause, unchanged: a record cannot substitute for the name."""
        g = shadow_graph(same_name=False, same_group=False)
        self.assertEqual(dimensions.shadow_ores(g, g.dimension_ores, SEDNANITE_REMOVED), {})

    def test_another_mods_ore_of_the_same_name_is_declined(self):
        """The measured false positive, and why removal alone is not enough.

        `immersiveengineering:ore:5` is a real Uranium Ore whose removal from `oreUranium`
        is part of the pack's cross-mod unification. Gating it would price a trip to Oi into
        an ore that has nothing to do with Oi -- a decoy left visible costs one confused
        search, an ore given a false provenance costs a player a thing that exists.
        """
        g = shadow_graph(same_group=False)
        g.names[IE_URANIUM] = "Sednanite Ore"
        self.assertNotIn(IE_URANIUM,
                         dimensions.shadow_ores(g, g.dimension_ores, SEDNANITE_REMOVED))

    def test_the_same_mods_twin_is_still_found_alongside_it(self):
        """The mod clause declines the stranger without declining the twin beside it."""
        g = shadow_graph(same_group=False)
        g.names[IE_URANIUM] = "Sednanite Ore"
        self.assertEqual(dimensions.shadow_ores(g, g.dimension_ores, SEDNANITE_REMOVED),
                         {HOLDER: [147, "Sedna"]})

    def test_the_twin_is_charged_for_the_trip(self):
        """The defect itself, priced: 1.0 before, because nothing produces it."""
        g = shadow_graph(same_group=False)
        g.dimension_ores.update(
            dimensions.shadow_ores(g, g.dimension_ores, SEDNANITE_REMOVED))
        gates = dimensions.gates_for(g, {"DIM-1": 42})
        costs = cost.estimate(g, machine_states=STATES, dimension_gates=gates)
        self.assertAlmostEqual(costs[HOLDER], cost.BASE_RAW_COST + cost.DIMENSION_COST,
                               places=6)

    def test_the_plan_stops_naming_the_block_that_does_not_generate(self):
        """End to end, on the shape the live repro has: the fluid routes through the twin.

        Before the fix the melt recipe's only input is the twin at 1.0 and the plan names
        it. After, the twin costs the trip and the real block -- which the oredict slot also
        satisfies -- is what the solver picks.
        """
        from recipegraph.solve import Solver
        g = shadow_graph(same_group=False)
        # The pack's own shape: the recipe asks for the GROUP, so both ids satisfy it, and
        # only the price decides. `shadow_graph` adds a literal-input recipe as well, which
        # is the case no ranking can rescue and which the gate above handles.
        g.add(Recipe("melt-group", "t", [("fluid:rhenium", 100)],
                     [Ingredient([HOLDER, SEDNANITE], 1)], category="minecraft.crafting"))
        g.dimension_ores.update(
            dimensions.shadow_ores(g, g.dimension_ores, SEDNANITE_REMOVED))
        gates = dimensions.gates_for(g, {"DIM-1": 42})
        costs = cost.estimate(g, machine_states=STATES, dimension_gates=gates)
        node = Solver(g, machine_states=STATES, costs=costs,
                      dimension_gates=gates).solve("fluid:rhenium", 1)["tree"]
        self.assertEqual([c["key"] for c in node["children"]], [SEDNANITE])

    def test_the_graph_keeps_the_set_apart_from_the_gates(self):
        """`shadow_ores` is not recoverable from `dimension_ores`, so #168 persists it."""
        g = shadow_graph(same_group=False)
        shadows = dimensions.shadow_ores(g, g.dimension_ores, SEDNANITE_REMOVED)
        g.shadow_ores = shadows
        g.dimension_ores.update(shadows)
        self.assertEqual(sorted(g.shadow_ores), [HOLDER])
        self.assertEqual(sorted(g.dimension_ores), sorted([SEDNANITE, HOLDER]))

    def test_the_set_survives_a_save_and_load(self):
        """It has to reach Java through graph.json, which is the whole point of persisting."""
        g = shadow_graph(same_group=False)
        g.shadow_ores = dimensions.shadow_ores(g, g.dimension_ores, SEDNANITE_REMOVED)
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "graph.json")
            g.save(path)
            self.assertEqual(Graph.load(path).shadow_ores, {HOLDER: [147, "Sedna"]})

    def test_a_graph_built_before_the_field_existed_loads_empty(self):
        g = shadow_graph()
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "graph.json")
            g.save(path)
            with open(path) as fh:
                doc = json.load(fh)
            del doc["shadow_ores"]
            with open(path, "w") as fh:
                json.dump(doc, fh)
            self.assertEqual(Graph.load(path).shadow_ores, {})

    def test_load_does_not_recompute_the_set(self):
        """DO NOT make this recompute. `tools/make-java-fixtures.py` keys every golden
        fixture to `len(dimension_ores)` on the oracle FILE, so a `load` that re-derived
        shadows would move that count for a graph nobody rebuilt and stale the whole set."""
        g = shadow_graph(same_group=False)
        g.shadow_ores = {}
        g.dimension_ores = {SEDNANITE: [147, "Sedna"]}
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "graph.json")
            g.save(path)
            loaded = Graph.load(path)
            self.assertEqual(loaded.shadow_ores, {})
            self.assertEqual(sorted(loaded.dimension_ores), [SEDNANITE])


class TheSearchRowSaysWhichOneIsRealTest(unittest.TestCase):
    """The reported symptom: two "Sednanite Ore" rows and nothing on screen telling them apart.

    NOT BY HIDING, and that is a constraint rather than a preference. `explore.rank_matches`
    reports `hidden` instead of dropping, on the stated rule that anything you actually hold
    stays findable however dead the graph thinks it is -- and three recipes consume the
    Rhenium twin literally, so a player can be holding one. A golden fixture also PLANS a
    twin as its target. Both rows stay; one of them now says what it is.
    """

    def _rows(self, g):
        from recipegraph import explore
        return {r["key"]: r for r in explore.suggest(g, "Sednanite Ore", {}).results}

    def test_both_rows_are_still_returned(self):
        g = shadow_graph()
        g.shadow_ores = dimensions.shadow_ores(g, g.dimension_ores)
        self.assertEqual(sorted(self._rows(g)), sorted([SEDNANITE, HOLDER]))

    def test_the_twin_is_marked_and_the_real_block_is_not(self):
        g = shadow_graph()
        g.shadow_ores = dimensions.shadow_ores(g, g.dimension_ores)
        rows = self._rows(g)
        self.assertTrue(rows[HOLDER].get("shadow"))
        self.assertNotIn("shadow", rows[SEDNANITE])

    def test_an_ordinary_row_carries_no_flag_at_all(self):
        """Absent rather than False: 4 keys in 266,728 have it, and a False on every other
        row would rewrite every stored plan fixture over a field none of them is about."""
        g = shadow_graph()
        self.assertNotIn("shadow", self._rows(g)[HOLDER])

    def test_the_real_block_still_ranks_first(self):
        """#101 is a ranking problem and this is not; `_canonical_first` already sorts by
        producers and consumers, and the twin loses on both. Pinned because a badge that
        arrived with a reordering would be a new bug wearing a fix's clothes."""
        g = shadow_graph()
        g.shadow_ores = dimensions.shadow_ores(g, g.dimension_ores)
        from recipegraph import explore
        self.assertEqual(explore.rank_matches(g, "Sednanite Ore", {}, 10).results[0],
                         SEDNANITE)


class BuildWiresTheShadowsInTest(unittest.TestCase):
    """One line in `index.build`, and dropping it is silent.

    Nothing raises and no other test fails: the gate simply goes back to naming a key the
    recipes do not use, which is #117 exactly. Same shape as the `index.build` wiring test
    `tests/test_multiblocks.py` keeps for the multiblock line.
    """

    def _instance(self, holder_in_oredict=True, ct_log=None):
        d = tempfile.mkdtemp()
        cfg = os.path.join(d, "config", "advRocketry")
        os.makedirs(cfg)
        with open(os.path.join(cfg, "planetDefs.xml"), "w") as fh:
            fh.write(PLANET_XML)
        if ct_log is not None:
            with open(os.path.join(d, "crafttweaker.log"), "w") as fh:
                fh.write(ct_log)
        dump = os.path.join(d, "mc-recipe-dump")
        os.makedirs(dump)
        with open(os.path.join(dump, "recipes.ndjson"), "w") as fh:
            fh.write(json.dumps({"cat": "minecraft.crafting",
                                 "in": [[{"i": HOLDER, "c": 1}]],
                                 "out": [{"i": "mod:ingot", "c": 1}]}) + "\n")
        with open(os.path.join(dump, "oredict.json"), "w") as fh:
            json.dump({"oreSednanite": [SEDNANITE] + ([HOLDER] if holder_in_oredict else [])},
                      fh)
        with open(os.path.join(dump, "names.json"), "w") as fh:
            json.dump({SEDNANITE: "Sednanite Ore", HOLDER: "Sednanite Ore"}, fh)
        return d

    def test_a_built_graph_gates_both_ids(self):
        from recipegraph import index
        g = index.build(self._instance(), quiet=True)
        self.assertEqual(sorted(g.dimension_ores), sorted([SEDNANITE, HOLDER]))
        self.assertEqual(g.dimension_ores[HOLDER], g.dimension_ores[SEDNANITE])

    def test_a_built_graph_records_which_id_is_the_duplicate(self):
        """#168 persists the set as well as folding it in, and `build` is the only writer."""
        from recipegraph import index
        g = index.build(self._instance(), quiet=True)
        self.assertEqual(sorted(g.shadow_ores), [HOLDER])

    def test_build_reads_the_removals_and_reaches_the_rhenium_shape(self):
        """The wiring #168 adds, end to end from pack files.

        Dropping the `removals` argument in `index.build` is silent in exactly the way the
        class docstring describes: every `shadow_ores` unit test above passes its own set
        directly, so none of them would notice. The oredict here does NOT hold the twin --
        this is the Rhenium shape -- and the log is the only thing that can find it.
        """
        from recipegraph import index
        g = index.build(
            self._instance(holder_in_oredict=False,
                           ct_log="[INITIALIZATION][SERVER][INFO] Removing Sednanite Ore "
                                  "from ore dictionary entry oreSednanite\n"),
            quiet=True)
        self.assertEqual(sorted(g.shadow_ores), [HOLDER])
        self.assertEqual(sorted(g.dimension_ores), sorted([SEDNANITE, HOLDER]))

    def test_the_same_pack_without_the_log_line_finds_nothing(self):
        """The control: the twin is invisible without the removal record, which is #168."""
        from recipegraph import index
        g = index.build(self._instance(holder_in_oredict=False, ct_log=""), quiet=True)
        self.assertEqual(g.shadow_ores, {})
        self.assertEqual(sorted(g.dimension_ores), [SEDNANITE])

    def test_a_pack_with_no_crafttweaker_log_still_builds(self):
        """The reader degrades to "no removals", the way a missing planetDefs already does."""
        from recipegraph import index
        g = index.build(self._instance(holder_in_oredict=False), quiet=True)
        self.assertEqual(g.shadow_ores, {})


class ReadingTheRemovalsTest(unittest.TestCase):
    """`oredict.removals_from_crafttweaker_log`, against the log lines the pack really writes.

    Its own class because the tests above hand `shadow_ores` a removal set directly, so every
    one of them would stay green if this regex matched nothing at all -- and "matched nothing"
    is precisely how a log-format assumption fails. The samples below are copied from the
    reference pack's `crafttweaker.log`, lines 6483-6484 and 2629.
    """

    LOG = (
        "[INITIALIZATION][SERVER][INFO] Adding tile.contenttweaker.rhenium_ore.name to ore"
        " dictionary entry oreRhenium\n"
        "[INITIALIZATION][SERVER][INFO] Removing Rhenium Ore from ore dictionary entry"
        " oreRhenium\n"
        "[INITIALIZATION][SERVER][INFO] Removing Uranium Ore from ore dictionary entry"
        " oreUranium\n"
        "[INITIALIZATION][SERVER][INFO] Removing Tin Ingot from ore dictionary entry"
        " ingotTin\n"
        "[INITIALIZATION][SERVER][INFO] Removing item.projectred.core.itemResource."
        "tin_ingot.name from ore dictionary entry ingotTin\n"
    )

    def _read(self, text):
        from recipegraph.sources import oredict
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "crafttweaker.log")
            with open(path, "w") as fh:
                fh.write(text)
            return oredict.removals_from_crafttweaker_log(path)

    def test_it_finds_the_removal_that_matters(self):
        self.assertIn(("Rhenium Ore", "oreRhenium"), self._read(self.LOG))

    def test_an_addition_is_not_a_removal(self):
        """The line immediately above the one that matters is an Adding, and the two differ
        only in a verb. A regex anchored loosely enough to take both would gate every ore."""
        self.assertNotIn(("tile.contenttweaker.rhenium_ore.name", "oreRhenium"),
                         self._read(self.LOG))

    def test_the_whole_pass_is_read_not_just_the_ore_groups(self):
        """Filtering is the caller's question. Reading only `ore*` here would hide from the
        next reader that 131 of the pack's 134 removals are ingots, dusts and plates."""
        self.assertEqual(self._read(self.LOG),
                         {("Rhenium Ore", "oreRhenium"),
                          ("Uranium Ore", "oreUranium"),
                          ("Tin Ingot", "ingotTin"),
                          ("item.projectred.core.itemResource.tin_ingot.name", "ingotTin")})

    def test_a_label_with_spaces_survives_intact(self):
        """The subject is a DISPLAY name, so it has spaces in it and the group is the last
        token. A greedy first group would swallow "from ore dictionary entry" as well."""
        self.assertIn(("Coralium Infused Stone", "oreCoraliumStone"),
                      self._read("Removing Coralium Infused Stone from ore dictionary"
                                 " entry oreCoraliumStone\n"))

    def test_an_unrelated_log_yields_nothing(self):
        self.assertEqual(self._read("[INFO] Ore entries for <ore:oreTin>: [<mod:tin>]\n"),
                         set())

    def test_a_missing_file_is_not_an_error(self):
        from recipegraph.sources import oredict
        self.assertEqual(
            oredict.removals_from_crafttweaker_log("/nonexistent/crafttweaker.log"), set())

    def test_the_reference_pack_yields_exactly_three_ore_removals(self):
        """The measurement the discriminator's precision rests on, pinned against the real
        file when it is present. Three `ore*` removals in 134: oreRhenium is #168's twin,
        oreUranium is the cross-mod false positive the mod clause declines, oreTartarite has
        no gated anchor. Skipped rather than failed off the build machine."""
        from recipegraph.model import is_world_ore_group
        from recipegraph.sources import oredict
        path = "/coding/.recipegraph-build/pack/crafttweaker.log"
        if not os.path.exists(path):
            self.skipTest("reference pack not present")
        got = oredict.removals_from_crafttweaker_log(path)
        self.assertEqual(sorted(p for p in got if is_world_ore_group(p[1])),
                         [("Rhenium Ore", "oreRhenium"),
                          ("Tartarite Ore", "oreTartarite"),
                          ("Uranium Ore", "oreUranium")])


class TheModOfAKeyTest(unittest.TestCase):
    """`model.mod_of`, which #168 made the shadow test depend on and `api.FIELDS` already did.

    One spelling now; it was two, and the second was an inline lambda in `api.FIELDS` -- the
    same shape as the `reachable_form` copy that drifted for two releases.
    """

    def test_an_item_key_answers_its_namespace(self):
        from recipegraph.model import mod_of
        self.assertEqual(mod_of("contenttweaker:sub_block_holder_1:8"), "contenttweaker")

    def test_a_plain_key_with_no_meta_still_answers(self):
        from recipegraph.model import mod_of
        self.assertEqual(mod_of("minecraft:stone"), "minecraft")

    def test_a_fluid_answers_empty_not_fluid(self):
        """`fluid:water` must not report a mod called "fluid" -- that is its KIND wearing
        the shape of an answer, and a sweep grouping by mod would show it as a mod."""
        from recipegraph.model import mod_of
        self.assertEqual(mod_of("fluid:water"), "")

    def test_an_oredict_and_an_essentia_key_answer_empty_too(self):
        from recipegraph.model import mod_of
        self.assertEqual(mod_of("ore:oreRhenium"), "")
        self.assertEqual(mod_of("essentia:terra"), "")

    def test_the_sweep_field_reads_this_one(self):
        """`api.FIELDS["mod"]` must not grow a second copy back."""
        import inspect
        from recipegraph import api
        self.assertIn("mod_of", inspect.getsource(api.FIELDS["mod"][0]))


if __name__ == "__main__":
    unittest.main()
