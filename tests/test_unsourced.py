"""A NEED leaf the graph has no route to must SAY so. #136

THE REPORTED SHAPE, in miniature. A plan for Strong Mythic Essence bottomed out on

    Strong Mythic Essence > Mildly Recursive Goo > Life Essence > Blaze Data Model (Superior)

listed as an ordinary shopping-list line beside "128 Granite". It is not ordinary: Deep Mob
Learning's tiers are reached by killing mobs in a Simulation Chamber, which is a kill counter
rather than a recipe, so no dump can carry it and the graph has no route to that tier at all.
Measured on the reference graph: 374 data-model keys, 125 with any producer, and those 125
are exactly two tiers -- the craftable fresh model and Self-Aware. The four middle tiers are
248 keys the graph cannot reach.

WHAT THIS DOES **NOT** CLAIM, because #136 measured both alternatives and rejected them:

  * It does not say "nothing produces this", which is true of cobblestone too and would badge
    most of a shopping list.
  * It does not change any price. The cost model's seeding of unreachable leaves at
    BASE_RAW_COST is the underlying defect and it stays open on #136; a display badge that
    quietly moved a number would be the worst of both.

It claims exactly one thing: THE GRAPH CAN MAKE THIS ITEM, BUT NOT IN THE STATE BEING ASKED
FOR. That is checkable, non-obvious, and it is the sentence a reader needs in order to
distrust the line.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import present  # noqa: E402
from recipegraph.model import Graph, Ingredient, Recipe  # noqa: E402
from recipegraph.solve import Solver  # noqa: E402

BASE = "deepmoblearning:data_model_blaze"
SUPERIOR = BASE + "#71bcc2df6d33"
GOO = "contenttweaker:mildly_recursive_goo"


def _graph():
    """The Blaze Data Model shape: a craftable base, and a tier nothing reaches."""
    g = Graph()
    g.names = {BASE: "Data Model Blaze", SUPERIOR: "Blaze Data Model (Superior)",
               GOO: "Mildly Recursive Goo", "minecraft:cobblestone": "Cobblestone",
               "mod:blank": "Blank Data Model"}
    # The fresh model is craftable; the Superior tier is not, because levelling is a kill
    # counter rather than a recipe.
    g.add(Recipe("r-base", "test", [(BASE, 1)], [Ingredient(["mod:blank"], 1)]))
    # The thing a plan wants, resting on the tier AND on an ordinary raw leaf.
    g.add(Recipe("r-goo", "test", [(GOO, 1)],
                 [Ingredient([SUPERIOR], 1), Ingredient(["minecraft:cobblestone"], 4)]))
    return g


class TheReportedFailureTest(unittest.TestCase):
    def setUp(self):
        self.tree = Solver(_graph()).solve(GOO, 1)["tree"]
        self.leaves = {}

        def walk(n):
            self.leaves[n["key"]] = n
            for c in n.get("children") or ():
                walk(c)
        walk(self.tree)

    def test_the_unreachable_tier_is_marked(self):
        """The assertion that fails before the fix and passes after."""
        node = self.leaves[SUPERIOR]
        self.assertEqual(node["status"], "raw")
        self.assertTrue(node.get("unsourced"),
                        "the Superior tier is a state no recipe reaches, and the plan "
                        "presents it as an ordinary thing to go and get")

    def test_an_ordinary_raw_leaf_is_NOT_marked(self):
        """The half that keeps the badge worth reading.

        Cobblestone has no producer either. Marking it would badge most of a shopping list
        and the mark would stop carrying information -- which is the failure mode #136
        measured for every rule that keyed on "producers == 0" alone.
        """
        self.assertFalse(self.leaves["minecraft:cobblestone"].get("unsourced"))

    def test_the_note_names_the_form_that_IS_reachable(self):
        # "no known source" alone leaves the reader nowhere. Naming the base is what makes
        # it actionable: the fresh model is craftable, the tier is not.
        self.assertIn("Data Model Blaze", self.leaves[SUPERIOR].get("note", ""))

    def test_the_shopping_list_row_carries_it_too(self):
        # The tree is the diagnosis and the shopping list is what gets acted on. A mark on
        # only one of them is a mark on the surface nobody reads while gathering.
        row = [r for r in Solver(_graph()).solve(GOO, 1)["shopping_list"]
               if r["key"] == SUPERIOR]
        self.assertEqual(len(row), 1)
        self.assertTrue(row[0].get("unsourced"))


class ScopeTest(unittest.TestCase):
    """The mark fires on a STATE of a producible item, and on nothing else."""

    def test_a_plain_key_with_no_producer_is_not_marked(self):
        # The Sednanite Nugget case. It has no NBT, so there is no "other form" to point at,
        # and #136's measurement found no signal separating it from a mob drop. Out of scope
        # here ON PURPOSE rather than by oversight; the seed defect stays open.
        g = Graph()
        g.names = {"mod:nugget": "Sednanite Nugget", "mod:thing": "Thing"}
        g.add(Recipe("r", "test", [("mod:thing", 1)], [Ingredient(["mod:nugget"], 9)]))
        tree = Solver(g).solve("mod:thing", 1)["tree"]
        leaf = tree["children"][0]
        self.assertEqual(leaf["key"], "mod:nugget")
        self.assertFalse(leaf.get("unsourced"))

    def test_a_variant_whose_base_is_also_unreachable_is_not_marked(self):
        # Nothing to point the reader at, so the mark would be noise: "we cannot make this,
        # and we cannot make the plain one either" is just "no recipe", which NEED says.
        g = Graph()
        g.names = {"mod:x": "X", "mod:x#aa": "X (odd)", "mod:out": "Out"}
        g.add(Recipe("r", "test", [("mod:out", 1)], [Ingredient(["mod:x#aa"], 1)]))
        tree = Solver(g).solve("mod:out", 1)["tree"]
        self.assertFalse(tree["children"][0].get("unsourced"))

    def test_stock_wins_over_the_mark(self):
        # Holding one is decisive evidence you can have it, whatever the graph knows about
        # recipes. `take` returns before the raw branch is ever reached.
        g = _graph()
        tree = Solver(g, have={SUPERIOR: 5}).solve(GOO, 1)["tree"]
        node = [c for c in tree["children"] if c["key"] == SUPERIOR][0]
        self.assertEqual(node["status"], "have")
        self.assertFalse(node.get("unsourced"))

    def test_a_token_wins_over_the_mark(self):
        # A pack placeholder is already an instruction with its own badge and its own list.
        # Two marks on one row would be two answers to "what do I do with this".
        g = _graph()
        tree = Solver(g, token_kinds={SUPERIOR: "loot"}).solve(GOO, 1)["tree"]
        node = [c for c in tree["children"] if c["key"] == SUPERIOR][0]
        self.assertEqual(node["status"], "token")
        self.assertFalse(node.get("unsourced"))


class PresentationTest(unittest.TestCase):
    def test_the_badge_is_distinct_from_a_plain_need(self):
        plain = present.status_badge("raw")
        marked = present.status_badge("raw", unsourced=True)
        self.assertNotEqual(plain[0], marked[0])
        self.assertIn("no known source", marked[0].lower())

    def test_it_keeps_the_need_colour(self):
        # It is still something you have to obtain; what changed is that the tool cannot say
        # how. A new colour would imply a new KIND of row.
        self.assertEqual(present.status_badge("raw")[1],
                         present.status_badge("raw", unsourced=True)[1])

    def test_a_token_kind_still_wins_the_word(self):
        # `unsourced` never reaches a token node (ScopeTest pins that), but the refinement
        # order has to be unambiguous where both are passed.
        text, _cls = present.status_badge("token", token_kind="loot", unsourced=True)
        self.assertNotIn("no known source", text.lower())


if __name__ == "__main__":
    unittest.main()


class ItMarksOnlyTheShoppingListTest(unittest.TestCase):
    """`_entry` builds FIVE lists, and the mark means something on exactly one of them.

    Decorating it inside `_entry` put "no known source" on rows in "Drawn from AE2 stock" --
    a row that exists precisely BECAUSE you are holding the item. Same for an infinite
    source and for a token, each of which already carries its own, contradictory, answer to
    "how do I get this".
    """

    def _result(self):
        g = _graph()
        # Holding some of the tier, so it lands on used_from_stock as well as being short.
        return Solver(g, have={SUPERIOR: 1}).solve(GOO, 3)

    def test_the_shopping_list_row_is_marked(self):
        rows = [r for r in self._result()["shopping_list"] if r["key"] == SUPERIOR]
        self.assertEqual(len(rows), 1)
        self.assertTrue(rows[0].get("unsourced"))

    def test_the_stock_row_for_the_same_key_is_NOT_marked(self):
        rows = [r for r in self._result()["used_from_stock"] if r["key"] == SUPERIOR]
        self.assertEqual(len(rows), 1, "expected the held one to be drawn from stock")
        self.assertFalse(rows[0].get("unsourced"),
                         "a row that exists because you are HOLDING the item must not also "
                         "say the tool cannot find you one")

    def test_a_token_row_is_not_marked(self):
        g = _graph()
        result = Solver(g, token_kinds={SUPERIOR: "loot"}).solve(GOO, 1)
        for row in result["tokens_needed"]:
            self.assertFalse(row.get("unsourced"), row)


class RenderedSurfacesTest(unittest.TestCase):
    """The badge has to survive into the HTML, on both surfaces a reader looks at."""

    def _html(self):
        from recipegraph.render import render_html
        return render_html(Solver(_graph()).solve(GOO, 2), _graph())

    def test_the_tree_and_the_shopping_list_both_carry_it(self):
        # Counted BEFORE the footer, which documents the badge and would otherwise inflate
        # the count to 3 and make this pass for the wrong reason.
        body = self._html().split('class="foot"')[0]
        self.assertEqual(body.count(present.UNSOURCED_BADGE), 2,
                         "expected the badge on the tree node and on the shopping-list row")

    def test_the_note_explains_it_in_the_tree(self):
        self.assertIn("no recipe reaches this state", self._html())

    def test_an_ordinary_need_row_is_untouched(self):
        # Cobblestone is in this plan and must still read as a plain NEED.
        html = self._html()
        self.assertIn(">NEED<", html)

    def test_the_footer_documents_the_badge(self):
        # The plan explains its own badges; a badge the footer does not mention is one the
        # reader has to guess at.
        self.assertIn(present.UNSOURCED_BADGE, self._html().split('class="foot"')[1])


class TheTwoPredicatesAgreeTest(unittest.TestCase):
    """`api._reachable_form` is `Solver.reachable_form` without a Solver, and must stay so.

    `api` deliberately does not import `solve`: a sweep answers questions about a GRAPH,
    while a Solver carries an inventory, pins and a cost table it has no business needing.
    That leaves two spellings of one predicate, which is exactly how a page and a sweep come
    to disagree about the same key -- so they are compared here rather than trusted.
    """

    def _cases(self):
        g = _graph()
        # A variant nothing reaches whose base IS made; the base itself; an ordinary leaf;
        # a variant whose base is also unreachable; and a key that is simply produced.
        g.names["mod:orphan#zz"] = "Orphan (odd)"
        return g, [SUPERIOR, BASE, "minecraft:cobblestone", "mod:orphan#zz", GOO,
                   "mod:blank"]

    def test_they_answer_identically_on_every_shape(self):
        from recipegraph import api
        g, keys = self._cases()
        solver = Solver(g)
        for key in keys:
            self.assertEqual(bool(api._reachable_form(g, key)),
                             bool(solver.reachable_form(key)), key)

    def test_the_sweep_field_is_wired_to_it(self):
        from recipegraph import api
        g, _keys = self._cases()

        class _Ctx:
            graph = g
            have = {}
            costs = {}

        self.assertTrue(api.FIELDS["unsourced"][0](_Ctx(), SUPERIOR))
        self.assertFalse(api.FIELDS["unsourced"][0](_Ctx(), "minecraft:cobblestone"))
