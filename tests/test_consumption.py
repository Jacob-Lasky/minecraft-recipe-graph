"""Inputs a run does NOT spend, and the two things that used to charge for them anyway.

#175. The dump's input-stack schema was `{i, m, c, n}` with no field for consumption, so every
input read as spent. A Deep Mob Learning data model sits in the machine forever, so a Spinel
Ring plan asked for 64 of them when the answer is one, and `fluid:fractallite_taint` grew
roughly 40 levels of phantom subtree under a chaos shard that is needed once, permanently.

NOT NAMED `test_catalysts.py`, WHICH ALREADY EXISTS AND MEANS SOMETHING ELSE. A JEI recipe
catalyst is the machine BLOCK a recipe is shown against: that is `catalysts.json`,
`sources/catalysts.py`, `Graph.catalysts` and ten files in the Java port. A non-consumed input
is unrelated. See `Ingredient.survives_run` for why the code never says "catalyst".

The pack declares this constantly and through two different mod mechanisms, which is why the
field is a probability rather than either mechanism's own spelling. Censused over the reference
pack's 479 `.zs` files, attributing each `setChance` to the requirement it modifies:

    ItemInput    1102 calls    1078 at p=0.0     24 fractional      0 at p=1.0
    ItemOutput    835 calls       0 at p=0.0    834 fractional      1 at p=1.0
    .reuse()      502 markers, CraftTweaker crafting-grid, binary

The 24 fractional inputs are not scattered: they are one deliberate 8-tier ladder in
`Trinitas.zs` (0.95, 0.8, 0.5, 0.3, 0.1, 0.05, 0.01, 0.001) applied to three items. That is
the case against a boolean. Rounding a designed ladder spanning three orders of magnitude to
one bit is wrong at both ends, in opposite directions.
"""

import hashlib
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import cost as cost_mod  # noqa: E402
from recipegraph.model import Graph, Ingredient, Recipe, merge_slots  # noqa: E402
from recipegraph.solve import Solver  # noqa: E402
from recipegraph.sources import hei_dump  # noqa: E402


class TheDefaultIsTotalConsumptionTest(unittest.TestCase):
    """Absent `p` means 1.0, which is the property that let this land before the mod change."""

    def test_an_ingredient_defaults_to_fully_consumed(self):
        self.assertEqual(Ingredient(["mod:a"], 2).consume_chance, 1.0)
        self.assertFalse(Ingredient(["mod:a"], 2).survives_run)

    def test_a_slot_with_no_p_reads_as_fully_consumed(self):
        ing = hei_dump._slot_to_ingredient([{"i": "mod:a", "m": 0, "c": 3}])
        self.assertEqual(ing.consume_chance, 1.0)

    def test_the_default_is_omitted_from_the_serialised_form(self):
        # `graph.json` is 121 MB and carries ~335k slots. A field written on every one of them
        # to say "the default" is not free, and its absence is what makes a graph built from a
        # pre-#175 dump serialise byte for byte as it did before.
        self.assertNotIn("p", Ingredient(["mod:a"], 1).to_json())
        self.assertEqual(Ingredient(["mod:a"], 1, "item", 0.0).to_json()["p"], 0.0)

    def test_a_round_trip_preserves_a_non_default_chance(self):
        for chance in (0.0, 0.001, 0.5, 1.0):
            back = Ingredient.from_json(Ingredient(["mod:a"], 4, "fluid", chance).to_json())
            self.assertEqual(back.consume_chance, chance, chance)
            self.assertEqual(back.qty, 4)
            self.assertEqual(back.role, "fluid")


class TheDumpReaderTest(unittest.TestCase):

    def test_p_is_read_off_the_stack(self):
        ing = hei_dump._slot_to_ingredient(
            [{"i": "draconicevolution:chaos_shard", "m": 0, "c": 1, "p": 0.0}])
        self.assertTrue(ing.survives_run)
        self.assertEqual(ing.alternatives, ["draconicevolution:chaos_shard"])

    def test_a_fractional_p_survives_as_a_float(self):
        ing = hei_dump._slot_to_ingredient([{"i": "mod:a", "c": 1, "p": 0.001}])
        self.assertEqual(ing.consume_chance, 0.001)

    def test_a_disagreeing_slot_takes_the_HIGHEST_chance(self):
        # `p` sits on the stack because `in` is a list of arrays with no slot object, so a slot
        # of interchangeable stacks carries one `p` each. In practice they agree, because
        # Modular Machinery's chance belongs to the REQUIREMENT. If they ever disagree, `max`
        # is the only arm that cannot invent a free route: `min` would let one reusable
        # alternative make the whole slot free, and the cheapest thing in the model being the
        # thing you cannot obtain is precisely the defect #176 fixed.
        ing = hei_dump._slot_to_ingredient(
            [{"i": "mod:a", "c": 1, "p": 0.0}, {"i": "mod:b", "c": 1, "p": 1.0}])
        self.assertEqual(ing.consume_chance, 1.0)
        self.assertFalse(ing.survives_run)

    def test_a_stack_with_no_key_does_not_contribute_its_chance(self):
        # A stack that yields no key is not part of the slot, so its `p` must not vote.
        ing = hei_dump._slot_to_ingredient([{"c": 1, "p": 0.0}, {"i": "mod:b", "c": 1}])
        self.assertEqual(ing.consume_chance, 1.0)


class MergingKeepsTheTwoRequirementsApartTest(unittest.TestCase):
    """The same item held as a retained input AND spent as an ingredient is two requirements."""

    def test_slots_with_the_same_key_but_different_chances_do_not_merge(self):
        rows = merge_slots([Ingredient(["mod:a"], 1, "item", 0.0),
                            Ingredient(["mod:a"], 5, "item", 1.0)],
                           lambda i: i.alternatives[0])
        self.assertEqual(len(rows), 2, "own one and spend five are not six spent")
        by_chance = {row[1].consume_chance: row[2] for row in rows}
        self.assertEqual(by_chance, {0.0: 1, 1.0: 5})

    def test_slots_that_share_a_chance_still_collapse(self):
        # The case `merge_slots` exists for: a 3x3 of one ingredient is nine slots, and they
        # share a chance, so bucketing on the chance too must not break the collapse.
        rows = merge_slots([Ingredient(["mod:a"], 1) for _ in range(9)],
                           lambda i: i.alternatives[0])
        self.assertEqual([(r[0], r[2]) for r in rows], [("mod:a", 9)])


def _retained_graph(chance=0.0):
    """One recipe whose first input is retained and whose second is spent."""
    g = Graph()
    g.names = {"mod:widget": "Widget", "mod:shard": "Shard", "mod:material": "Material"}
    g.add(Recipe("forge", "t", [("mod:widget", 1)],
                 [Ingredient(["mod:shard"], 1, "item", chance),
                  Ingredient(["mod:material"], 1)],
                 category="mod.forge", machine="Forge"))
    return g


class TheSolverExpandsARetainedInputOnceTest(unittest.TestCase):

    def test_a_retained_slot_does_not_scale_with_runs(self):
        g = _retained_graph()
        plan = Solver(g).solve("mod:widget", 64)
        kids = {c["key"]: c for c in plan["tree"]["children"]}
        self.assertEqual(kids["mod:shard"]["need"], 1,
                         "64 runs need the same one shard, not 64 of them")
        self.assertEqual(kids["mod:material"]["need"], 64,
                         "the consumed input must still scale with runs")

    def test_the_row_says_it_is_not_consumed(self):
        g = _retained_graph()
        plan = Solver(g).solve("mod:widget", 64)
        kids = {c["key"]: c for c in plan["tree"]["children"]}
        self.assertTrue(kids["mod:shard"].get("not_consumed"))
        self.assertNotIn("not_consumed", kids["mod:material"])

    def test_it_is_still_expanded_rather_than_dropped(self):
        # You genuinely cannot run the forge without the shard, so the requirement is real and
        # the subtree beneath it is how you obtain the one. The quantity was the defect, not
        # the presence of the row.
        g = _retained_graph()
        plan = Solver(g).solve("mod:widget", 64)
        self.assertIn("mod:shard", {c["key"] for c in plan["tree"]["children"]})

    def test_a_consumed_input_is_unchanged_by_all_of_this(self):
        # The control: with the chance at its default, both slots scale, which is the exact
        # behaviour of every graph any existing dump can produce.
        g = _retained_graph(chance=1.0)
        plan = Solver(g).solve("mod:widget", 64)
        kids = {c["key"]: c for c in plan["tree"]["children"]}
        self.assertEqual(kids["mod:shard"]["need"], 64)
        self.assertEqual(kids["mod:material"]["need"], 64)
        self.assertNotIn("not_consumed", kids["mod:shard"])


class RetainedInputsDoNotAmortiseTest(unittest.TestCase):
    """A retained input is priced like a machine: once, and not divided by the batch."""

    @staticmethod
    def _price(chance, batch):
        g = Graph()
        g.names = {"mod:out": "Out", "mod:shard": "Shard", "mod:material": "Material"}
        g.add(Recipe("forge", "t", [("mod:out", batch)],
                     [Ingredient(["mod:shard"], 1, "item", chance),
                      Ingredient(["mod:material"], 1)],
                     category="minecraft.crafting"))
        return cost_mod.estimate(g, have={}, machine_states={},
                                 free_sources=set()).get("mod:out")

    def test_a_bigger_batch_does_not_make_a_retained_input_cheaper(self):
        # THE WHOLE POINT. `base` is what running the recipe costs at all and does not divide;
        # dividing a permanent requirement by the batch says a big enough output makes it free,
        # which is the identical error the amortisation comment in `_relax` was written about
        # for machines. The reference pack has a recipe yielding 60,466,176 fruit.
        small = self._price(0.0, 1)
        large = self._price(0.0, 4096)
        self.assertGreater(large, 0.0)
        self.assertAlmostEqual(large - small, 0.0, delta=abs(small) * 0.5 + 1.0,
                               msg="the retained term must not shrink with the batch")

    def test_a_consumed_input_still_amortises(self):
        # The other half, so the test above cannot pass by pricing everything into `base`.
        self.assertLess(self._price(1.0, 4096), self._price(1.0, 1))

    def test_a_retained_input_is_not_free(self):
        # Free would make every route through it the cheapest in the model, so the solver would
        # prefer machines whose retained input the player cannot obtain: #176's defect, through
        # a different door. Compared against a recipe with the slot removed entirely.
        g = Graph()
        g.names = {"mod:out": "Out", "mod:material": "Material"}
        g.add(Recipe("bare", "t", [("mod:out", 1)], [Ingredient(["mod:material"], 1)],
                     category="minecraft.crafting"))
        bare = cost_mod.estimate(g, have={}, machine_states={},
                                 free_sources=set()).get("mod:out")
        self.assertGreater(self._price(0.0, 1), bare,
                           "owning the shard has to cost more than not needing one")

    def test_a_fractional_chance_is_charged_at_its_fraction(self):
        # Between the two: it amortises like an ingredient, but at `p` of itself. Only 24 input
        # slots in the reference pack are fractional, and they span 0.95 to 0.001, so a boolean
        # would be wrong by three orders of magnitude at one end of that ladder.
        self.assertLess(self._price(0.25, 1), self._price(1.0, 1))
        self.assertGreater(self._price(0.25, 1), self._price(0.0625, 1))


class TheChangeMovesNoPriceOnAGraphWithoutPTest(unittest.TestCase):
    """The control run that justifies not bumping `FORMULA_VERSION`, promoted into the suite.

    `cost.FORMULA_VERSION` exists to stop a warm `.cost-cache.json` serving prices computed by
    different arithmetic, and #175 changed the per-unit expression. It did NOT bump the counter,
    and the argument is that the new expression is EXACTLY the old one whenever every slot
    carries the default chance: `retained` is 0.0 and the ingredient term is `c * 1.0`, and
    `x + 0.0 == x` and `x * 1.0 == x` are exact in IEEE 754 rather than nearly true.

    This pins that claim on a graph varied enough to exercise the arithmetic that changed:
    batch outputs so the amortising divisor is not 1, several inputs per recipe so the
    accumulator sums more than one term, a fluid, an oredict slot, a container transfer and
    three machine bands. The digest is checked against a value recorded when the change was
    made, so a later edit that DOES move a default-chance price fails here and has to either
    bump the counter or explain itself.
    """

    # Recorded by running `cost.estimate` over `_graph()` on master (FORMULA_VERSION 10, before
    # the retained-input term existed) and again with the term in place. Both produced this.
    UNCHANGED_DIGEST = "d89f2eb4489588ada0991c620b24baa065a6ce93204bb01f4a232bce89a2eae9"

    @staticmethod
    def _graph():
        g = Graph()
        g.names = {}
        for i in range(40):
            g.names["mod:leaf%d" % i] = "Leaf %d" % i
        g.names.update({"mod:mid": "Mid", "mod:top": "Top", "mod:batch": "Batch",
                        "fluid:goo": "Goo", "mod:ore_a": "Ore A", "mod:ore_b": "Ore B"})
        g.ore_members = {"plateStuff": ["mod:ore_a", "mod:ore_b"]}
        for i in range(2, 38):
            g.add(Recipe("r%d" % i, "t", [("mod:leaf%d" % i, 1)],
                         [Ingredient(["mod:leaf%d" % (i - 1)], 2),
                          Ingredient(["mod:leaf%d" % (i - 2)], 3)],
                         category="mod.press", machine="Press"))
        g.add(Recipe("batch", "t", [("mod:batch", 4096)],
                     [Ingredient(["mod:leaf37"], 1), Ingredient(["ore:plateStuff"], 8)],
                     category="mod.greenhouse", machine="Greenhouse"))
        g.add(Recipe("mid", "t", [("mod:mid", 1)],
                     [Ingredient(["mod:batch"], 64),
                      Ingredient(["fluid:goo"], 1000, "fluid")],
                     category="mod.mixer", machine="Mixer"))
        g.add(Recipe("top", "t", [("mod:top", 1)],
                     [Ingredient(["mod:mid"], 3), Ingredient(["mod:leaf5"], 9)],
                     category="minecraft.crafting"))
        g.add(Recipe("fill", "t", [("fluid:goo", 1000)], [Ingredient(["mod:leaf3"], 1)],
                     category="mod.tank", transfer=True))
        return g

    def test_every_price_is_what_it_was_before_the_retained_term_existed(self):
        states = {"mod.press": ("have", ""),
                  "mod.greenhouse": ("buildable", "no machine"),
                  "mod.mixer": ("unavailable", "missing part"),
                  "mod.tank": ("have", "")}
        prices = cost_mod.estimate(self._graph(), have={}, machine_states=states,
                                   free_sources=set())
        h = hashlib.sha256()
        for key in sorted(prices):
            h.update(("%s=%.17g\n" % (key, prices[key])).encode())
        self.assertEqual(len(prices), 40, "the fixture graph stopped pricing what it priced")
        self.assertEqual(
            h.hexdigest(), self.UNCHANGED_DIGEST,
            "a default-chance price moved. Either that is a bug, or the arithmetic genuinely "
            "changed and cost.FORMULA_VERSION has to be bumped and the fixtures regenerated. "
            "See the note on FORMULA_VERSION.")

    def test_the_digest_is_sensitive_enough_to_notice(self):
        # Guards the guard: a digest over prices that all came back infinite, or over an empty
        # dict, would be stable for the wrong reason. Perturb one recipe and it must move.
        g = self._graph()
        g.add(Recipe("cheap", "t", [("mod:leaf20", 1)], [Ingredient(["mod:leaf0"], 1)],
                     category="minecraft.crafting"))
        prices = cost_mod.estimate(g, have={}, machine_states={}, free_sources=set())
        h = hashlib.sha256()
        for key in sorted(prices):
            h.update(("%s=%.17g\n" % (key, prices[key])).encode())
        self.assertNotEqual(h.hexdigest(), self.UNCHANGED_DIGEST)


if __name__ == "__main__":
    unittest.main()
