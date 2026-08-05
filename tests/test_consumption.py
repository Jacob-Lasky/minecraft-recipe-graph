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

    def test_a_malformed_p_reads_as_ABSENT_and_never_as_a_probability(self):
        # "Malformed" and "fully consumed" are different facts, and this maps the first onto the
        # second because 1.0 is the only landing spot that cannot invent work out of nothing.
        # Three of these were reachable before `_consume_chance` existed, and the first two are
        # the dangerous direction: they declared the slot a PERMANENT catalyst, charged once
        # instead of once per run, on a stack nobody marked.
        #
        #   p: false     `float(False)` is 0.0. `isinstance(True, int)` is True in Python, so a
        #                plain `(int, float)` check does not exclude a bool.
        #   p: "0.0"     `float` accepts strings.
        #   p: null      `float(None)` RAISED, aborting a 335,000-recipe build with a TypeError
        #                naming neither the line nor the field.
        for bad in (False, True, "0.0", "nonsense", None, [], {}):
            ing = hei_dump._slot_to_ingredient([{"i": "mod:a", "c": 1, "p": bad}])
            self.assertEqual(ing.consume_chance, 1.0, "p=%r must read as absent" % (bad,))
            self.assertFalse(ing.survives_run, "p=%r must not make the slot permanent" % (bad,))

    def test_an_out_of_range_p_is_refused_rather_than_clamped(self):
        # A negative `p` would scale an ingredient's cost NEGATIVE in `cost._relax`, which is not
        # a mispriced route but an arbitrage: the more of it a recipe demands, the cheaper the
        # recipe gets. Clamping to 0.0 would instead make it permanent. Neither is a reading of
        # the data, so the field is unusable and the default stands.
        for bad in (-1.0, -0.001, 1.5, 2, float("inf"), float("nan")):
            ing = hei_dump._slot_to_ingredient([{"i": "mod:a", "c": 1, "p": bad}])
            self.assertEqual(ing.consume_chance, 1.0, "p=%r must read as absent" % (bad,))

    def test_zero_survives_as_zero_and_is_distinct_from_missing(self):
        # The other half, and the reason the guard above cannot simply reject everything: 0.0 is
        # a REAL value at the opposite end of the range from the default. `if not p:` or
        # `p or 1.0` would map it onto 1.0 and silently price the one stack a mod declared
        # never-consumed as fully consumed.
        present = hei_dump._slot_to_ingredient([{"i": "mod:a", "c": 1, "p": 0.0}])
        missing = hei_dump._slot_to_ingredient([{"i": "mod:a", "c": 1}])
        self.assertEqual(present.consume_chance, 0.0)
        self.assertEqual(missing.consume_chance, 1.0)
        self.assertTrue(present.survives_run)
        self.assertFalse(missing.survives_run)
        self.assertNotEqual(present.consume_chance, missing.consume_chance)

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
    def _price(chance, batch, with_shard=True):
        g = Graph()
        g.names = {"mod:out": "Out", "mod:shard": "Shard", "mod:material": "Material"}
        slots = [Ingredient(["mod:material"], 1)]
        if with_shard:
            slots.insert(0, Ingredient(["mod:shard"], 1, "item", chance))
        g.add(Recipe("forge", "t", [("mod:out", batch)], slots,
                     category="minecraft.crafting"))
        return cost_mod.estimate(g, have={}, machine_states={},
                                 free_sources=set()).get("mod:out")

    def _contribution(self, chance, batch):
        """What the shard slot alone adds to the price, isolated by removing it.

        MEASURED AS A DIFFERENCE AND NOT AS A TOTAL, because the total is the wrong instrument
        and the first version of this test used it. The recipe also holds a CONSUMED input,
        which genuinely does amortise, so the total legitimately falls as the batch grows: at
        batch 1 it was 22.0 and at 4096 it was 21.000244. A tolerance wide enough to accept
        that drop is wide enough to accept the retained term vanishing too, which is exactly
        the shape of test this repository keeps having to fix. The Java mirror of this caught
        it; the Python one had been written to pass.
        """
        return self._price(chance, batch) - self._price(chance, batch, with_shard=False)

    def test_a_bigger_batch_does_not_make_a_retained_input_cheaper(self):
        # THE WHOLE POINT. `base` is what running the recipe costs at all and does not divide;
        # dividing a permanent requirement by the batch says a big enough output makes it free,
        # which is the identical error the amortisation comment in `_relax` was written about
        # for machines. The reference pack has a recipe yielding 60,466,176 fruit.
        one = self._contribution(0.0, 1)
        many = self._contribution(0.0, 4096)
        self.assertGreater(one, 0.0, "the shard has to cost something to begin with")
        self.assertAlmostEqual(many, one, places=9,
                               msg="a retained input's contribution must not shrink with the "
                                   "batch: %r at 1, %r at 4096" % (one, many))

    def test_a_consumed_input_does_amortise(self):
        # The mirror image, and it is what proves the test above is measuring something. The
        # same slot at the default chance must have its contribution collapse over a batch of
        # 4096, or "does not shrink" would be true of everything and assert nothing.
        one = self._contribution(1.0, 1)
        many = self._contribution(1.0, 4096)
        self.assertGreater(one, 0.0)
        self.assertLess(many, one / 100.0,
                        "a consumed input must amortise: %r at 1, %r at 4096" % (one, many))

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


class TheRankerAgreesWithTheRelaxationTest(unittest.TestCase):
    """`recipe_cost` is a SECOND pricing path and it has to charge what `_relax` charges.

    Found by the review pass, not by the change: `_relax` was taught about the consume chance
    and `recipe_cost` was not, so the solver's own ranking still charged a 30%-consumed input
    in full. A ranker that prices a route differently from the relaxation is the divergence
    #29 is about, where nothing is mispriced and the price is simply for a route nobody takes.

    One run, so there is no batch: a retained input is charged in FULL exactly once, and a
    fractional one at `p` of itself.
    """

    @staticmethod
    def _graph(chance):
        g = Graph()
        g.names = {"mod:out": "Out", "mod:shard": "Shard"}
        g.add(Recipe("forge", "t", [("mod:out", 1)],
                     [Ingredient(["mod:shard"], 1, "item", chance)],
                     category="minecraft.crafting"))
        return g

    def _run_cost(self, chance):
        g = self._graph(chance)
        prices = {"mod:shard": 100.0}
        return cost_mod.recipe_cost(prices, g.recipes[0], g.ore_members)

    def test_a_fractional_input_is_charged_at_its_fraction(self):
        full = self._run_cost(1.0)
        quarter = self._run_cost(0.25)
        self.assertAlmostEqual(full - quarter, 75.0, places=6,
                               msg="a quarter-consumed input must cost a quarter per run")

    def test_a_retained_input_is_charged_IN_FULL_and_not_scaled_to_zero(self):
        # The one place scaling by `p` would be wrong. You must own the shard before the forge
        # runs, so a recipe demanding an expensive permanent input has to rank worse for it.
        # Scaling to zero would tell the ranker this recipe is free and reintroduce #176's
        # defect: the cheapest route in the model being the one you cannot take.
        self.assertAlmostEqual(self._run_cost(0.0), self._run_cost(1.0), places=6)
        self.assertGreater(self._run_cost(0.0), 100.0 - 1e-6)

    def test_the_ranker_and_the_relaxation_agree_on_a_fractional_input(self):
        # The property that matters, rather than either number on its own. With one output and
        # one input the relaxation's per-unit price is `base + ingredients`, which is exactly
        # what `recipe_cost` computes for one run, so the two must come out equal.
        g = self._graph(0.25)
        relaxed = cost_mod.estimate(g, have={"mod:shard": 0}, machine_states={},
                                    free_sources=set())
        ranked = cost_mod.recipe_cost(
            {"mod:shard": relaxed["mod:shard"]}, g.recipes[0], g.ore_members)
        self.assertAlmostEqual(relaxed["mod:out"], ranked, places=6,
                               msg="the ranker and the relaxation priced the same recipe "
                                   "differently: %r vs %r" % (relaxed["mod:out"], ranked))


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
    # the retained-input term existed) and again with the term in place. Both produced
    # d89f2eb4 over 40 keys.
    #
    # REBASELINED AT #193, AND #175's CLAIM IS UNTOUCHED BY IT. The graph above ends with a
    # container `fill` transfer as the only producer of `fluid:goo`, which is exactly the shape
    # #193 is about: nothing seeded that key and `_relax` refused to price it from a transfer,
    # so it and everything downstream of it came out unreachable. It is now
    # `produced_in_name_only` and seeded at `UNSOURCED_COST`, which takes `fluid:goo`, `mod:mid`
    # and `mod:top` from absent to priced -- 40 keys to 43.
    #
    # WHAT DID NOT HAPPEN IS THE THING THIS CLASS GUARDS: of the 40 keys both runs price, ZERO
    # moved. So the retained-input term still moves no default-chance price, the digest still
    # fails on any edit that changes one, and the count is asserted beside it so a rebaseline
    # that quietly stopped pricing something cannot hide inside a new hash.
    UNCHANGED_DIGEST = "7f99e21fc1a3afaae98cadfd65405aa273d71ad01a0294807f4885f0d79cb32e"

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
        self.assertEqual(len(prices), 43, "the fixture graph stopped pricing what it priced")
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
