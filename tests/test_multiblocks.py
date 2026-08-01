"""Modular Machinery structures: parsed from pack config, priced as what they really cost.

Every number quoted here is measured on the reference pack rather than invented, because the
whole of #93 is that a plausible-looking price was three orders of magnitude wrong and nothing
caught it. The shapes that bite are all real: `elements` is sometimes a bare string, `@meta` is
blockstate metadata rather than item metadata, MM's abstract port names are not items at all,
and one of the pack's own files is malformed JSON.
"""

import json
import math
import os
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from recipegraph import cost, multiblocks  # noqa: E402
from recipegraph.model import Graph  # noqa: E402

INF = float("inf")


def machine(registry, parts, name=None, modifiers=None):
    doc = {"registryname": registry, "localizedname": name or registry.title(),
           "parts": [{"x": i, "y": 0, "z": 0, "elements": e} for i, e in enumerate(parts)]}
    if modifiers:
        doc["modifiers"] = modifiers
    return doc


class Instance(object):
    """A throwaway pack instance holding only what the parser reads."""

    def __init__(self):
        self.dir = tempfile.mkdtemp()
        self.machinery = os.path.join(self.dir, multiblocks.CONFIG_DIR)
        os.makedirs(self.machinery)

    def write(self, filename, doc):
        path = os.path.join(self.machinery, filename)
        with open(path, "w") as fh:
            if isinstance(doc, str):
                fh.write(doc)
            else:
                json.dump(doc, fh)
        return path

    def aliases(self, text):
        with open(os.path.join(self.dir, multiblocks.ALIAS_FILE), "w") as fh:
            fh.write(text)


class ElementKeyTest(unittest.TestCase):
    def test_a_blockstate_meta_falls_back_to_the_base_item(self):
        # `minecraft:stone_slab@9` is a TOP-SIDE slab. The item is meta 1, and meta 9 is not an
        # item key at all. 421 slots in the reference pack are this one element.
        got = multiblocks._element_key("minecraft:stone_slab@9", {}, {"minecraft:stone_slab"})
        self.assertEqual(got, "minecraft:stone_slab")

    def test_an_exact_meta_wins_over_the_base(self):
        known = {"mod:block:3", "mod:block"}
        self.assertEqual(multiblocks._element_key("mod:block@3", {}, known), "mod:block:3")

    def test_without_a_known_set_the_exact_key_is_kept(self):
        # `parse` is usable without a graph; it just cannot second-guess a meta.
        self.assertEqual(multiblocks._element_key("mod:block@3", {}, None), "mod:block:3")

    def test_meta_zero_normalises_away(self):
        self.assertEqual(multiblocks._element_key("mod:block@0", {}, None), "mod:block")

    def test_an_unresolved_alias_is_dropped_rather_than_made_into_an_item(self):
        # THE #16 GHOST. `norm_key` prefixes a bare word with "minecraft:", so letting
        # `generalized_input_item` through would mint a phantom key that no recipe can satisfy
        # and nobody can trace back to here.
        self.assertIsNone(multiblocks._element_key("generalized_input_item", {}, None))

    def test_a_known_alias_becomes_its_real_block(self):
        aliases = {"generalized_input_item": "modularmachinery:blockinputbus"}
        self.assertEqual(multiblocks._element_key("generalized_input_item", aliases, None),
                         "modularmachinery:blockinputbus")


class AliasFileTest(unittest.TestCase):
    def setUp(self):
        self.inst = Instance()

    def test_the_alias_is_the_second_column(self):
        self.inst.aliases("modularmachinery:blockinputbus\t\tgeneralized_input_item\n")
        got = multiblocks.load_aliases(self.inst.dir)
        self.assertEqual(got, {"generalized_input_item": "modularmachinery:blockinputbus"})

    def test_crlf_with_no_trailing_newline_still_parses(self):
        # Exactly how the reference pack ships it, and splitting on "\t" alone leaves the
        # alias as "generalized_input_item\r", which then matches nothing.
        self.inst.aliases("a:b\t\tone\r\nc:d\t\ttwo")
        self.assertEqual(multiblocks.load_aliases(self.inst.dir), {"one": "a:b", "two": "c:d"})

    def test_a_missing_file_is_not_an_error(self):
        self.assertEqual(multiblocks.load_aliases(self.inst.dir), {})


class ParseTest(unittest.TestCase):
    def setUp(self):
        self.inst = Instance()

    def test_identical_positions_are_grouped_with_a_count(self):
        self.inst.write("m.json", machine("m", [["mod:brick"], ["mod:brick"], ["mod:glass"]]))
        got = multiblocks.parse(self.inst.dir)
        self.assertEqual(got["m"]["slots"], 3)
        self.assertEqual(sorted(got["m"]["parts"]),
                         [[1, ["mod:glass"]], [2, ["mod:brick"]]])

    def test_a_bare_string_elements_is_one_block_not_a_set_of_characters(self):
        # 32 positions in the reference pack are a bare string. Iterating it would produce
        # "m", "o", "d", ":" ... and price the machine as a pile of nonsense keys.
        self.inst.write("m.json", machine("m", ["mod:brick"]))
        got = multiblocks.parse(self.inst.dir)
        self.assertEqual(got["m"]["parts"], [[1, ["mod:brick"]]])

    def test_alternatives_are_kept_together(self):
        self.inst.write("m.json", machine("m", [["mod:a", "mod:b"]]))
        self.assertEqual(multiblocks.parse(self.inst.dir)["m"]["parts"],
                         [[1, ["mod:a", "mod:b"]]])

    def test_a_position_with_no_resolvable_element_is_counted_as_blind(self):
        self.inst.write("m.json", machine("m", [["mod:a"], ["some_unknown_alias"]]))
        got = multiblocks.parse(self.inst.dir)
        self.assertEqual(got["m"]["blind"], 1)
        self.assertEqual(got["m"]["slots"], 2)
        self.assertEqual(got["m"]["parts"], [[1, ["mod:a"]]])

    def test_one_malformed_file_does_not_cost_the_others(self):
        # The reference pack really does ship a broken induction_electrolyzer.json. Dying here
        # would take the whole graph build with it.
        self.inst.write("good.json", machine("good", [["mod:a"]]))
        self.inst.write("bad.json", '{"registryname": "bad", "parts": [')
        said = []
        got = multiblocks.parse(self.inst.dir, say=said.append)
        self.assertEqual(sorted(got), ["good"])
        self.assertTrue(any("bad.json" in m and "malformed" in m for m in said),
                        "a skipped file must be reported, not swallowed: %r" % said)

    def test_a_file_with_no_registryname_is_skipped(self):
        self.inst.write("m.json", {"localizedname": "Nameless", "parts": []})
        self.assertEqual(multiblocks.parse(self.inst.dir), {})

    def test_modifiers_are_not_requirements(self):
        # An optional "5X Speed" upgrade block must not be charged to a player who never
        # places it.
        self.inst.write("m.json", machine("m", [["mod:a"]],
                                          modifiers=[{"elements": "mod:expensive_upgrade"}]))
        parts = multiblocks.parse(self.inst.dir)["m"]["parts"]
        self.assertEqual(parts, [[1, ["mod:a"]]])

    def test_a_pack_without_modular_machinery_parses_to_nothing(self):
        empty = tempfile.mkdtemp()
        said = []
        self.assertEqual(multiblocks.parse(empty, say=said.append), {})
        self.assertTrue(said, "a pack with no MM config should say so")

    def test_the_output_is_stable_across_runs(self):
        # graph.json is written sorted; unstable group order would churn 115 MB of file on
        # every rebuild of an unchanged pack.
        self.inst.write("m.json", machine("m", [["mod:z"], ["mod:a"], ["mod:z"], ["mod:m"]]))
        first = multiblocks.parse(self.inst.dir)
        second = multiblocks.parse(self.inst.dir)
        self.assertEqual(json.dumps(first, sort_keys=True), json.dumps(second, sort_keys=True))
        self.assertEqual(first["m"]["parts"], sorted(first["m"]["parts"], key=lambda p: p[1]))

    def test_the_controller_and_category_are_derived_from_the_registry_name(self):
        self.inst.write("m.json", machine("altar_to_the_name_of_names", [["mod:a"]]))
        got = multiblocks.parse(self.inst.dir)["altar_to_the_name_of_names"]
        self.assertEqual(got["controller"],
                         "modularmachinery:altar_to_the_name_of_names_controller")
        self.assertEqual(multiblocks.category_for("altar_to_the_name_of_names"),
                         "modularmachinery.recipes.altar_to_the_name_of_names")


class StructureCostTest(unittest.TestCase):
    def entry(self, parts):
        return {"parts": parts}

    def test_each_position_is_charged_its_cheapest_alternative(self):
        got = multiblocks.structure_cost(self.entry([[2, ["mod:dear", "mod:cheap"]]]),
                                         {"mod:dear": 100.0, "mod:cheap": 3.0})
        self.assertEqual(got, 6.0)

    def test_the_count_multiplies(self):
        self.assertEqual(
            multiblocks.structure_cost(self.entry([[8813, ["mod:a"]]]), {"mod:a": 2.0}),
            17626.0)

    def test_one_unreachable_component_makes_the_whole_structure_unreachable(self):
        # NOT the sum of the parts that happen to price. 6,456 of the Dyson Extruder's blocks
        # are galaxy conduits with no obtainable recipe, and reporting it as merely expensive
        # is the same underpricing this module exists to fix.
        got = multiblocks.structure_cost(self.entry([[1, ["mod:a"]], [1, ["mod:nope"]]]),
                                         {"mod:a": 1.0})
        self.assertEqual(got, INF)

    def test_an_unreachable_alternative_does_not_poison_a_reachable_one(self):
        got = multiblocks.structure_cost(self.entry([[1, ["mod:nope", "mod:fine"]]]),
                                         {"mod:fine": 5.0})
        self.assertEqual(got, 5.0)

    def test_an_empty_structure_costs_nothing(self):
        self.assertEqual(multiblocks.structure_cost({"parts": []}, {}), 0.0)

    def test_the_blocking_component_can_be_reported(self):
        blocked = []
        multiblocks.structure_cost(self.entry([[1, ["mod:nope"]]]), {}, blocked)
        self.assertEqual(blocked, ["mod:nope"])


class TheGraphCarriesTheStructuresTest(unittest.TestCase):
    """The deployment ships graph.json alone, so the server never sees the pack config."""

    def test_they_survive_a_save_and_load(self):
        g = Graph()
        g.multiblocks = {"m": {"name": "M", "controller": "mod:m_controller", "slots": 3,
                               "blind": 0, "parts": [[3, ["mod:a"]]]}}
        path = os.path.join(tempfile.mkdtemp(), "g.json")
        g.save(path)
        self.assertEqual(Graph.load(path).multiblocks, g.multiblocks)

    def test_a_graph_built_before_this_still_loads(self):
        # Every graph.json on disk today has no `multiblocks` key, including the 115 MB one the
        # deployed server is holding. An empty map is the pre-#93 behaviour, not a failure.
        path = os.path.join(tempfile.mkdtemp(), "g.json")
        with open(path, "w") as fh:
            json.dump({"recipes": [], "names": {}}, fh)
        self.assertEqual(Graph.load(path).multiblocks, {})


class AMultiblockIsPricedByItsStructureTest(unittest.TestCase):
    CTRL = "modularmachinery:big_controller"
    CAT = "modularmachinery.recipes.big"

    def setUp(self):
        self.mb = {"big": {"name": "Big", "controller": self.CTRL, "slots": 100, "blind": 0,
                           "parts": [[100, ["mod:brick"]]]}}
        # The controller is two cheap items; the structure is a hundred bricks.
        self.costs = {self.CTRL: 2.0, "mod:brick": 5.0, "mod:other_machine": 2.0}

    def test_the_structure_is_added_to_the_controller_recipe(self):
        got = cost.machine_entry_costs({self.CAT: (self.CTRL,)}, self.costs, self.mb)
        self.assertAlmostEqual(got[self.CAT], cost.build_entry_cost(2.0 + 500.0))

    def test_without_the_structures_it_is_priced_as_two_items(self):
        # The #93 defect, kept as a test so the difference is visible rather than asserted.
        got = cost.machine_entry_costs({self.CAT: (self.CTRL,)}, self.costs, None)
        self.assertAlmostEqual(got[self.CAT], cost.build_entry_cost(2.0))
        self.assertLess(got[self.CAT],
                        cost.machine_entry_costs({self.CAT: (self.CTRL,)},
                                                 self.costs, self.mb)[self.CAT])

    def test_a_machine_that_is_not_a_controller_is_untouched(self):
        other = "some.category"
        got = cost.machine_entry_costs({other: ("mod:other_machine",)}, self.costs, self.mb)
        self.assertAlmostEqual(got[other], cost.build_entry_cost(2.0))

    def test_an_unbuildable_structure_charges_the_blocked_slice(self):
        self.mb["big"]["parts"] = [[1, ["mod:unobtainable"]]]
        got = cost.machine_entry_costs({self.CAT: (self.CTRL,)}, self.costs, self.mb)
        # Every position is blocked, so this one sits at the top of the slice.
        self.assertAlmostEqual(got[self.CAT], cost.BLOCKED_CEILING)
        self.assertGreater(got[self.CAT], cost.PRICED_CEILING)
        self.assertLess(got[self.CAT], cost.MACHINE_COST["unknown"])

    def test_an_unreachable_controller_is_not_made_reachable_by_a_cheap_structure(self):
        got = cost.machine_entry_costs({self.CAT: (self.CTRL,)},
                                      {"mod:brick": 5.0}, self.mb)
        # The CONTROLLER is what failed to price here, not the structure, so this is the
        # pricing gap rather than an evidence-based impossibility (#95).
        self.assertAlmostEqual(got[self.CAT], cost.UNPRICED_MACHINE_COST)

    def test_the_cheapest_candidate_still_wins_even_when_one_is_a_multiblock(self):
        # A category openable by either a multiblock or an ordinary block should be priced by
        # the ordinary block, because that is what a player would build.
        got = cost.machine_entry_costs({self.CAT: (self.CTRL, "mod:other_machine")},
                                       self.costs, self.mb)
        self.assertAlmostEqual(got[self.CAT], cost.build_entry_cost(2.0))


class TheBlueprintIsNotWhatAMachineIsPricedFromTest(unittest.TestCase):
    """A cheap non-machine candidate would set the price for all 188 MM categories.

    `machine_entry_costs` takes the CHEAPEST candidate, and JEI lists the generic blueprint as
    a catalyst for every Modular Machinery category. On the reference pack this is currently
    hidden: the bare `modularmachinery:itemblueprint` has no producer and prices at infinity,
    because the real blueprints are NBT-discriminated variants. So the whole of #93 rests on an
    accident of pricing unless the exclusion is explicit, and no other test fails when it goes.
    """

    def rec(self, *keys):
        return {"uid": "modularmachinery.recipes.big", "state": "buildable",
                "candidate_states": [{"key": k, "state": "buildable"} for k in keys]}

    def targets(self, *keys):
        from recipegraph import machines
        return machines.build_targets({"modularmachinery.recipes.big": self.rec(*keys)})

    def test_the_blueprint_is_dropped_when_a_real_machine_is_also_a_candidate(self):
        got = self.targets("modularmachinery:big_controller", "modularmachinery:itemblueprint")
        self.assertEqual(got["modularmachinery.recipes.big"],
                         ("modularmachinery:big_controller",))

    def test_a_priced_blueprint_can_no_longer_undercut_the_structure(self):
        # The regression this guards, made to happen: give the blueprint a price and check the
        # category is still charged for the multiblock.
        from recipegraph import machines
        mb = {"big": {"name": "Big", "controller": "modularmachinery:big_controller",
                      "slots": 100, "blind": 0, "parts": [[100, ["mod:brick"]]]}}
        costs = {"modularmachinery:big_controller": 2.0, "mod:brick": 5.0,
                 "modularmachinery:itemblueprint": 1.0}
        targets = machines.build_targets({"modularmachinery.recipes.big":
                                         self.rec("modularmachinery:big_controller",
                                                  "modularmachinery:itemblueprint")})
        got = cost.machine_entry_costs(targets, costs, mb)
        self.assertAlmostEqual(got["modularmachinery.recipes.big"],
                              cost.build_entry_cost(502.0))

    def test_a_blueprint_only_category_keeps_its_candidate(self):
        # Dropping the last candidate would charge the top of the band on this module's silence
        # rather than on evidence.
        got = self.targets("modularmachinery:itemblueprint")
        self.assertEqual(got["modularmachinery.recipes.big"],
                         ("modularmachinery:itemblueprint",))

    def test_an_ordinary_machine_is_not_affected(self):
        self.assertEqual(self.targets("mod:press")["modularmachinery.recipes.big"],
                         ("mod:press",))


class TheEntryCurveKeepsItsLowEndTest(unittest.TestCase):
    """The low end must not move, through #93's recalibration or #95's.

    Raising it would relitigate the Crystallizer case in cost.py's header from the other side:
    an enormous chain through machines the player owns beating a two-step route through a
    machine they merely have to build.

    CHECKED AGAINST #86's CURVE, NOT AGAINST HAND-ROUNDED LITERALS. This used to assert
    `build_entry_cost(1.0) == 41.21` to two places, which passed at 41.2061 and failed at
    41.2038 -- a 0.002 move, 20x inside the tolerance cost.py documents, so the literal was a
    tripwire on the rounding rather than on the calibration. Two rescalings have now come
    through here and a third will. The invariant is agreement with `40 + 79 * b / (b + 64)`
    to within 0.05, which is what #93 measured and what BUILD_SLOPE now pins.
    """

    def reference(self, b):
        """#86's original curve: the calibration every later shape has had to preserve."""
        return cost.MACHINE_COST["buildable"] + cost.BUILD_SLOPE * b / (b + cost.BUILD_SCALE)

    def test_it_tracks_eighty_sixs_curve_over_the_cheaper_half_of_the_pack(self):
        # Measured build costs run min 1.0, median 2.0, so agreement up to 2.0 covers half
        # the buildable categories -- "the mass of the pack" in cost.py's comment.
        for b in (0.0, 0.5, 1.0, 2.0):
            self.assertLess(abs(cost.build_entry_cost(b) - self.reference(b)), 0.05,
                            "build cost %r drifted off #86's curve" % b)

    def test_the_low_end_gradient_is_the_calibrated_one(self):
        # Near zero both curves are BUILD_SLOPE * b / BUILD_SCALE, which is the whole reason
        # BUILD_SPREAD can move and the low end cannot. Checked as a limit, so it holds for
        # any future spread.
        b = 1e-6
        expected = cost.BUILD_SLOPE * b / cost.BUILD_SCALE
        got = cost.build_entry_cost(b) - cost.MACHINE_COST["buildable"]
        self.assertAlmostEqual(got / expected, 1.0, places=4)

    def test_a_free_machine_still_sits_exactly_on_the_floor(self):
        self.assertEqual(cost.build_entry_cost(0.0), cost.MACHINE_COST["buildable"])

    def test_the_top_of_the_range_no_longer_saturates(self):
        # The point of #93's recalibration. Under the pre-#93 curve these two were 118.95 and
        # 118.98, i.e. indistinguishable; 20 of the 71 fully-priced multiblocks landed within
        # 1.0 of the ceiling and 9 of them shared a value with another machine.
        a = cost.build_entry_cost(112219.0)
        b = cost.build_entry_cost(279861.0)
        self.assertGreater(b - a, 0.5)
        self.assertLess(b, cost.PRICED_CEILING - 1.0)

    def test_it_stays_monotonic_across_six_decades(self):
        vals = [cost.build_entry_cost(b) for b in
                (0.0, 1.0, 2.0, 10.0, 100.0, 1e3, 1e4, 1e5, 1e6, 1e7)]
        self.assertEqual(vals, sorted(vals))
        self.assertEqual(len(set(vals)), len(vals))

    def test_the_band_still_holds_at_the_extremes(self):
        self.assertLess(cost.PRICED_CEILING, cost.MACHINE_COST["unknown"])
        for b in (0.0, 1.0, 1e9, 1e300):
            got = cost.build_entry_cost(b)
            self.assertGreaterEqual(got, cost.MACHINE_COST["buildable"])
            self.assertLess(got, cost.PRICED_CEILING)
        self.assertEqual(cost.build_entry_cost(INF), cost.UNPRICED_MACHINE_COST)


class TheCeilingHoldsOneClaimEachTest(unittest.TestCase):
    """#95. Everything unpriceable used to land on one number, so 140 of the reference pack's
    403 buildable categories charged exactly 119.000 and no two of them were distinguishable.

    Two unrelated statements were stacked there: 117 Modular Machinery structures needing a
    block nothing makes, and 23 machine ITEMS this model simply failed to price. The observed
    consequence was `aoa3:holly_top_petals`, where a blocked multiblock beat a Phytogenic
    Insolator -- both at 119.000 -- by 0.037 of an ingredient point.
    """

    def parts(self, *groups):
        return {"name": "M", "controller": "mod:ctrl", "slots": 1, "blind": 0,
                "parts": [list(g) for g in groups]}

    def test_the_four_regions_are_strictly_ordered(self):
        self.assertLess(cost.MACHINE_COST["have"], cost.MACHINE_COST["buildable"])
        self.assertLess(cost.build_entry_cost(1e300), cost.PRICED_CEILING)
        self.assertLess(cost.PRICED_CEILING, cost.UNPRICED_MACHINE_COST)
        self.assertLess(cost.UNPRICED_MACHINE_COST, cost.BLOCKED_FLOOR)
        self.assertLess(cost.BLOCKED_FLOOR, cost.BLOCKED_CEILING)
        self.assertLess(cost.BLOCKED_CEILING, cost.MACHINE_COST["unknown"])
        self.assertLess(cost.MACHINE_COST["unknown"], cost.MACHINE_COST["unavailable"])

    def test_a_failure_to_price_is_cheaper_than_a_proven_blockage(self):
        """The holly_top_petals fix, and the one ordering here not to swap.

        "This model could not work out a number" is a weaker claim than "the pack says this
        needs a block nothing makes", so it must be the more optimistic of the two.
        """
        self.assertLess(cost.build_entry_cost(INF), cost.blocked_entry_cost(0.0))

    def test_a_barely_blocked_structure_outranks_a_hopeless_one(self):
        # `the_cube` misses 3 of 2,125 positions; `mythic_excavation_lattice` misses 135 of
        # 135. Both charged 119.000 before this.
        nearly = cost.blocked_entry_cost(3 / 2125.0)
        hopeless = cost.blocked_entry_cost(1.0)
        self.assertLess(nearly, hopeless)
        self.assertGreater(hopeless - nearly, 5.0)

    def test_even_the_least_blocked_structure_loses_to_every_priced_machine(self):
        """Ordering inside the slice must not leak into the buildable band.

        `structure_cost` returns inf rather than a partial sum precisely so that a machine
        that cannot be placed never reads as merely expensive. Ordering the failures must not
        undo that.
        """
        self.assertGreater(cost.blocked_entry_cost(0.0), cost.build_entry_cost(1e300))

    def test_the_fraction_counts_positions_not_part_groups(self):
        # `parts` collapses identical positions into groups, so counting groups would weigh
        # one missing conduit the same as 6,456 of them.
        entry = self.parts([6456, ["mod:nope"]], [1, ["mod:brick"]])
        self.assertAlmostEqual(multiblocks.blocked_fraction(entry, {"mod:brick": 1.0}),
                               6456 / 6457.0)

    def test_a_fully_reachable_structure_is_not_blocked_at_all(self):
        entry = self.parts([10, ["mod:brick"]])
        self.assertEqual(multiblocks.blocked_fraction(entry, {"mod:brick": 1.0}), 0.0)

    def test_an_empty_structure_is_not_blocked(self):
        # Agrees with structure_cost, which prices an empty structure at 0.0 not inf.
        self.assertEqual(multiblocks.blocked_fraction({"parts": []}, {}), 0.0)

    def test_one_reachable_alternative_unblocks_the_position(self):
        entry = self.parts([4, ["mod:nope", "mod:brick"]])
        self.assertEqual(multiblocks.blocked_fraction(entry, {"mod:brick": 1.0}), 0.0)

    def test_the_sum_and_the_fraction_never_disagree_about_a_position(self):
        """Both read `position_cost`, and this is why that is one function.

        The pair is used together on one structure -- the sum decides `inf`, the fraction
        ranks it -- so a position the sum thinks is affordable and the fraction thinks is
        missing is a contradiction with no symptom. Asserted as a property over a spread of
        shapes rather than on one fixture, so it still holds for a position rule taught
        anything new later.
        """
        prices = {"mod:cheap": 1.0, "mod:dear": 900.0}
        shapes = ([[1, ["mod:cheap"]]],
                  [[3, ["mod:nope"]]],
                  [[2, ["mod:nope", "mod:cheap"]]],
                  [[5, ["mod:dear"]], [1, ["mod:nope"]]],
                  [[7, ["mod:cheap"]], [2, ["mod:dear"]]],
                  [[1, []]],
                  [])
        for shape in shapes:
            entry = {"parts": [list(g) for g in shape]}
            placed = multiblocks.structure_cost(entry, prices)
            fraction = multiblocks.blocked_fraction(entry, prices)
            self.assertEqual(math.isinf(placed), fraction > 0.0,
                             "sum and fraction disagree on %r" % (shape,))

    def test_an_out_of_range_fraction_clamps_rather_than_escaping_the_slice(self):
        for f in (-1.0, 0.0, 0.5, 1.0, 2.0, None, float("nan")):
            got = cost.blocked_entry_cost(f)
            self.assertGreaterEqual(got, cost.BLOCKED_FLOOR)
            self.assertLessEqual(got, cost.BLOCKED_CEILING)

    def test_the_slice_is_monotonic(self):
        vals = [cost.blocked_entry_cost(f) for f in (0.0, .1, .25, .5, .75, .9, 1.0)]
        self.assertEqual(vals, sorted(vals))
        self.assertEqual(len(set(vals)), len(vals))

    def test_two_different_failures_no_longer_price_the_same(self):
        """The reported symptom, in the API that predates the fix so it can be run against it.

        On the broken code both categories come back 119.000 and this fails on the assertion
        below -- which is `aoa3:holly_top_petals` reduced to two dictionaries: a blocked
        Modular Machinery route and a Phytogenic Insolator whose machine item never priced,
        tied at the ceiling and separated by 0.037 of an ingredient point.
        """
        mb = {"m": self.parts([1, ["mod:unobtainable"]])}
        got = cost.machine_entry_costs(
            {"blocked": ("mod:ctrl",), "unpriced": ("mod:never_priced",)},
            {"mod:ctrl": 2.0}, mb)
        self.assertNotEqual(got["blocked"], got["unpriced"])
        self.assertLess(got["unpriced"], got["blocked"])

    def test_two_structures_blocked_to_different_degrees_no_longer_price_the_same(self):
        """The other half of the symptom, also in the pre-existing API.

        `the_cube` misses 3 of 2,125 positions and `mythic_excavation_lattice` misses all 135.
        On the broken code both come back 119.000.
        """
        costs = {"mod:ctrl": 2.0, "mod:brick": 1.0}
        nearly = {"m": {"name": "M", "controller": "mod:ctrl", "slots": 1, "blind": 0,
                        "parts": [[2122, ["mod:brick"]], [3, ["mod:nope"]]]}}
        hopeless = {"m": {"name": "M", "controller": "mod:ctrl", "slots": 1, "blind": 0,
                          "parts": [[135, ["mod:nope"]]]}}
        a = cost.machine_entry_costs({"c": ("mod:ctrl",)}, costs, nearly)["c"]
        b = cost.machine_entry_costs({"c": ("mod:ctrl",)}, costs, hopeless)["c"]
        self.assertLess(a, b)

    def test_a_blocked_multiblock_loses_to_an_ordinary_candidate_for_the_same_category(self):
        # The minimum is over entry costs now, so a category openable by a plain block is
        # still priced by that block even when its multiblock candidate is unbuildable.
        mb = {"m": self.parts([1, ["mod:unobtainable"]])}
        got = cost.machine_entry_costs({"cat": ("mod:ctrl", "mod:press")},
                                       {"mod:ctrl": 2.0, "mod:press": 3.0}, mb)
        self.assertAlmostEqual(got["cat"], cost.build_entry_cost(3.0))


class BuildAttachesThemToTheGraphTest(unittest.TestCase):
    """One line in `index.build`, and dropping it is silent: prices simply revert to #93.

    The same shape as the server wiring #86 needed a test for. Nothing else fails, no error is
    raised, and every MM machine quietly goes back to costing two items.
    """

    def setUp(self):
        self.inst = Instance()
        dump = os.path.join(self.inst.dir, "mc-recipe-dump")
        os.makedirs(dump)
        with open(os.path.join(dump, "recipes.ndjson"), "w") as fh:
            fh.write(json.dumps({"cat": "modularmachinery.recipes.big",
                                 "in": [[{"i": "mod:in", "c": 1}]],
                                 "out": [{"i": "mod:out", "c": 1}]}) + "\n")

    def test_a_built_graph_carries_the_structures(self):
        from recipegraph import index
        self.inst.write("big.json", machine("big", [["mod:brick"], ["mod:brick"]]))
        g = index.build(self.inst.dir, quiet=True)
        self.assertIn("big", g.multiblocks)
        self.assertEqual(g.multiblocks["big"]["slots"], 2)

    def test_the_blockstate_fallback_uses_this_graph_s_own_labels(self):
        # `known` has to be the labels of the FINISHED graph. Resolving earlier, before the
        # non-recipe drop pass, checks metas against a label set the graph no longer has.
        self.inst.write("big.json", machine("big", [["mod:out@7"]]))
        from recipegraph import index
        g = index.build(self.inst.dir, quiet=True)
        self.assertEqual(g.multiblocks["big"]["parts"], [[1, ["mod:out"]]])


class TheCacheSeesTheStructuresTest(unittest.TestCase):
    def test_editing_a_structure_invalidates_the_fingerprint(self):
        # A caller that supplies graph.multiblocks in process moves no other input, which is
        # the shape of the trap #86 hit when patching a function changed no fingerprint.
        args = ("nonexistent-graph.json", {}, {}, ())
        a = cost.fingerprint(*args, multiblocks={"m": {"parts": [[1, ["mod:a"]]]}})
        b = cost.fingerprint(*args, multiblocks={"m": {"parts": [[2, ["mod:a"]]]}})
        self.assertNotEqual(a, b)

    def test_no_structures_is_not_the_same_as_empty_ones(self):
        args = ("nonexistent-graph.json", {}, {}, ())
        self.assertEqual(cost.fingerprint(*args, multiblocks=None),
                         cost.fingerprint(*args, multiblocks={}))

    def test_the_formula_version_moved(self):
        # The deployed server holds a .cost-cache.json full of prices from the old curve, and
        # the curve is code rather than a hashed constant on its own.
        self.assertGreaterEqual(cost.FORMULA_VERSION, 4)

    def test_every_band_constant_is_actually_hashed(self):
        """`fingerprint`'s comment claims it lists the #95 boundaries "anyway". Prove it.

        They are derived from BUILD_SPREAD today, so hashing that alone would cover them --
        which means an omission here is INVISIBLE until someone gives one a literal value, and
        then the deployed server serves prices from the old layout on a cache HIT only. Moving
        each constant in turn is the only check that does not rely on how they are defined.
        """
        args = ("nonexistent-graph.json", {}, {}, ())
        base = cost.fingerprint(*args)
        for name in ("BUILD_SPREAD", "BUILD_SCALE", "BUILD_KNEE", "BUILD_SLOPE",
                     "UNPRICED_MACHINE_COST", "BLOCKED_FLOOR", "BLOCKED_CEILING",
                     "UNGATED_MACHINE_COST", "BASE_RAW_COST", "TRANSFER_PENALTY"):
            was = getattr(cost, name)
            setattr(cost, name, was + 1.0)
            try:
                self.assertNotEqual(cost.fingerprint(*args), base,
                                    "moving cost.%s does not invalidate the cache" % name)
            finally:
                setattr(cost, name, was)


if __name__ == "__main__":
    unittest.main()
