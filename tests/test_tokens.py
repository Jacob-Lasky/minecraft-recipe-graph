"""Pack placeholders: what counts as one, and what a plan does with it.

The reported symptom (#51): a plan for an Exoskeleton Plate asked for a "Dungeon Drop" and
a "From Battle Tower Loot" as though they were two materials to gather, when they are one
instruction with two sources.
"""

import io
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import present, render, tokens  # noqa: E402
from recipegraph.model import Graph, Ingredient, Recipe  # noqa: E402
from recipegraph.solve import STATUS_TOKEN, Solver  # noqa: E402


def drop_graph():
    """One item whose only recipe needs two different loot placeholders."""
    g = Graph()
    g.names = {"mod:plate": "Plate",
               "contenttweaker:dungeon_drop": "Dungeon Drop",
               "contenttweaker:battle_tower": "From Battle Tower Loot",
               "contenttweaker:chapter_1": "Chapter 1",
               "mod:screw": "Screw"}
    g.add(Recipe("r", "t", [("mod:plate", 1)],
                 [Ingredient(["contenttweaker:dungeon_drop"], 1),
                  Ingredient(["contenttweaker:battle_tower"], 1),
                  Ingredient(["mod:screw"], 4)]))
    return g


class CuratedListTest(unittest.TestCase):
    def test_every_curated_entry_has_a_kind_the_module_knows(self):
        for key, kind in tokens.DEFAULT_TOKENS.items():
            self.assertIn(kind, tokens.KINDS, key)

    def test_every_kind_has_a_heading_and_a_badge(self):
        """A kind with no label renders as a blank group heading, which reads as a bug in
        the plan rather than a missing entry here."""
        for kind in tokens.KINDS:
            self.assertIn(kind, tokens.KIND_LABEL, kind)
            self.assertIn(kind, tokens.KIND_BADGE, kind)

    def test_the_curated_namespace_is_one_it_scans(self):
        # `candidates` only looks inside TOKEN_NAMESPACES, so an entry outside them could
        # never have been offered and is a sign the two have drifted apart.
        prefixes = tuple("%s:" % ns for ns in tokens.TOKEN_NAMESPACES)
        for key in tokens.DEFAULT_TOKENS:
            self.assertTrue(key.startswith(prefixes), key)

    def test_armour_is_not_swept_in_by_a_name_that_looks_like_loot(self):
        """The reason this is a curated list and not a substring test.
        `contenttweaker:vibranium_chest` is a CHESTPLATE, one of four armour pieces, and
        `vox_ponds_token_legs` is armour too. Matching "chest" or "token" would rewrite
        real craftable gear into "go find it in a chest"."""
        for key in ("contenttweaker:vibranium_chest", "contenttweaker:vibranium_legs",
                    "contenttweaker:vox_ponds_token_legs", "contenttweaker:tier1_token",
                    "contenttweaker:champion_token"):
            self.assertNotIn(key, tokens.DEFAULT_TOKENS, key)


class ResolveTest(unittest.TestCase):
    def test_defaults_come_through(self):
        self.assertEqual(tokens.resolve()["contenttweaker:dungeon_drop"], tokens.LOOT)

    def test_a_user_addition_wins_and_a_removal_sticks(self):
        got = tokens.resolve({"tokens": {"mod:x": tokens.GATE},
                              "disabled": ["contenttweaker:dungeon_drop"]})
        self.assertEqual(got["mod:x"], tokens.GATE)
        self.assertNotIn("contenttweaker:dungeon_drop", got)

    def test_an_unknown_kind_is_ignored_rather_than_stored(self):
        # It would reach `group`, match no KINDS entry, and silently drop the row from the
        # panel: present but invisible is the worst of both.
        self.assertNotIn("mod:x", tokens.resolve({"tokens": {"mod:x": "nonsense"}}))

    def test_resolve_does_not_mutate_the_curated_map(self):
        before = dict(tokens.DEFAULT_TOKENS)
        tokens.resolve({"tokens": {"mod:x": tokens.LOOT}, "disabled": list(before)})
        self.assertEqual(tokens.DEFAULT_TOKENS, before)

    def test_a_missing_or_broken_overrides_file_reads_as_empty(self):
        self.assertEqual(tokens.load_overrides(None), {})
        self.assertEqual(tokens.load_overrides("/nope/nope.json"), {})
        path = os.path.join(tempfile.mkdtemp(), "tokens.json")
        with open(path, "w") as fh:
            fh.write("{not json")
        self.assertEqual(tokens.load_overrides(path), {})

    def test_overrides_round_trip_without_freezing_the_defaults(self):
        """Writing the merged map would bake today's defaults into the user's file, so a
        later correction here would be overridden by a stale copy of itself."""
        path = os.path.join(tempfile.mkdtemp(), "tokens.json")
        tokens.save_overrides(path, {"mod:x": tokens.LOOT}, ["contenttweaker:boss_drop"])
        with open(path) as fh:
            doc = json.load(fh)
        self.assertEqual(doc["tokens"], {"mod:x": tokens.LOOT})
        self.assertEqual(doc["disabled"], ["contenttweaker:boss_drop"])
        self.assertNotIn("contenttweaker:dungeon_drop", doc["tokens"])


class CompleteFamiliesTest(unittest.TestCase):
    """The curated list has drifted, and the fix derives the gap rather than listing it. #171.

    `DEFAULT_TOKENS` declares `hunter_level_1/20/30` and `chapter_1/2/4/5/6/8`; the reference
    pack also uses `hunter_level_3/5/9/12/13/17/19/40/50` and `chapter_9`. Ten GATE ids, all
    consumed by real recipes, none curated. Pasting them in fixes today and guarantees
    tomorrow, which is the failure the list is already demonstrating.
    """

    def family_graph(self, *keys):
        """A graph where every named key is pack-authored, consumed and produced by nothing."""
        g = Graph()
        g.names = {"mod:plate": "Plate"}
        for i, key in enumerate(keys):
            g.names[key] = key.split(":")[1]
            g.add(Recipe("r%d" % i, "t", [("mod:plate", 1)], [Ingredient([key], 1)]))
        return g

    def test_it_completes_a_family_a_human_already_verified(self):
        g = self.family_graph("contenttweaker:hunter_level_40")
        got = tokens.complete_families(dict(tokens.DEFAULT_TOKENS), g)
        self.assertEqual(tokens.GATE, got["contenttweaker:hunter_level_40"])

    def test_it_never_invents_a_family_of_its_own(self):
        # THE CLAUSE THAT KEEPS IT NARROW. The pack is full of numbered ids that are real
        # markers -- `1_in_200`, `level15` -- and guessing at them is `candidates`' job to
        # OFFER a human, not this function's to assert. The stem has to come from a curated id.
        g = self.family_graph("contenttweaker:some_new_thing_7")
        got = tokens.complete_families(dict(tokens.DEFAULT_TOKENS), g)
        self.assertNotIn("contenttweaker:some_new_thing_7", got)

    def test_a_different_separator_is_a_different_family(self):
        # `chapter_9` and `chapter9` are not the same claim, and treating them as one would
        # let a stem match ids the curator never looked at.
        g = self.family_graph("contenttweaker:chapter9")
        self.assertNotIn("contenttweaker:chapter9",
                         tokens.complete_families(dict(tokens.DEFAULT_TOKENS), g))

    def test_a_family_that_disagrees_with_itself_is_left_alone(self):
        # A stem whose curated members carry two kinds is a list saying it does not know.
        # Resolving that by majority or by first-seen would invent an answer.
        g = self.family_graph("contenttweaker:mixed_3")
        known = {"contenttweaker:mixed_1": tokens.GATE, "contenttweaker:mixed_2": tokens.LOOT}
        self.assertNotIn("contenttweaker:mixed_3", tokens.complete_families(known, g))

    def test_a_key_the_graph_can_explain_is_not_swept_in(self):
        # THE #117/#168 GUARD. Calling a produced item a placeholder hides a real crafting
        # route, which costs more than the drift it fixes. On the reference pack no member
        # needs this clause; it is here for the pack with a craftable `chapter_10`.
        g = self.family_graph("contenttweaker:hunter_level_40")
        g.add(Recipe("makes", "t", [("contenttweaker:hunter_level_40", 1)],
                     [Ingredient(["mod:screw"], 1)]))
        self.assertNotIn("contenttweaker:hunter_level_40",
                         tokens.complete_families(dict(tokens.DEFAULT_TOKENS), g))

    def test_a_key_no_recipe_touches_is_not_swept_in_either(self):
        # `DEFAULT_TOKENS` makes this call by hand -- `chapter_3` and `chapter_7` are
        # "deliberately ABSENT: the pack defines the item but no recipe references it". Both
        # are named and neither is live, so this reaches the same answer without being told.
        g = self.family_graph("contenttweaker:hunter_level_40")
        g.names["contenttweaker:chapter_3"] = "Chapter 3"
        self.assertNotIn("contenttweaker:chapter_3",
                         tokens.complete_families(dict(tokens.DEFAULT_TOKENS), g))

    def test_no_graph_means_no_widening_rather_than_a_guess(self):
        self.assertEqual(tokens.DEFAULT_TOKENS,
                         tokens.complete_families(dict(tokens.DEFAULT_TOKENS), None))

    def test_resolve_completes_a_family_the_user_declared(self):
        # Completion runs AFTER the user's additions, so a family they declare behaves
        # exactly like a curated one.
        g = self.family_graph("contenttweaker:custom_2")
        got = tokens.resolve({"tokens": {"contenttweaker:custom_1": tokens.LOOT}}, g)
        self.assertEqual(tokens.LOOT, got["contenttweaker:custom_2"])

    def test_a_user_can_disable_a_derived_id(self):
        # And BEFORE the removals, so a curated list the user cannot edit does not appear by
        # the back door. Someone who has looked at `hunter_level_9` and decided it is not a
        # gate has to be able to say so.
        g = self.family_graph("contenttweaker:hunter_level_40")
        got = tokens.resolve({"disabled": ["contenttweaker:hunter_level_40"]}, g)
        self.assertNotIn("contenttweaker:hunter_level_40", got)

    def test_it_does_not_mutate_the_map_it_was_given(self):
        g = self.family_graph("contenttweaker:hunter_level_40")
        known = dict(tokens.DEFAULT_TOKENS)
        tokens.complete_families(known, g)
        self.assertEqual(tokens.DEFAULT_TOKENS, known)

    def test_candidates_stops_offering_what_completion_already_claimed(self):
        # Re-offering a derived id asks a human to curate an answer the tool already has.
        g = self.family_graph("contenttweaker:hunter_level_40", "contenttweaker:novel_thing")
        offered = {key for key, _n, _c in tokens.candidates(g)}
        self.assertNotIn("contenttweaker:hunter_level_40", offered)
        self.assertIn("contenttweaker:novel_thing", offered)


class GroupTest(unittest.TestCase):
    def test_kinds_group_and_keep_their_members_named(self):
        """The rollup Jake asked for, and the reason it stops short of one "drop" line:
        Battle Tower and fishing are different afternoons."""
        rows = tokens.group([
            {"key": "a", "token_kind": tokens.LOOT},
            {"key": "b", "token_kind": tokens.GATE},
            {"key": "c", "token_kind": tokens.LOOT},
        ])
        self.assertEqual([(k, [e["key"] for e in es]) for k, _l, es in rows],
                         [(tokens.LOOT, ["a", "c"]), (tokens.GATE, ["b"])])

    def test_order_follows_KINDS_not_the_input(self):
        rows = tokens.group([{"key": "a", "token_kind": tokens.METHOD},
                             {"key": "b", "token_kind": tokens.LOOT}])
        self.assertEqual([k for k, _l, _e in rows], [tokens.LOOT, tokens.METHOD])

    def test_nothing_in_nothing_out(self):
        self.assertEqual(tokens.group([]), [])


class CandidatesTest(unittest.TestCase):
    def test_it_offers_the_unrecognised_and_skips_what_is_known(self):
        g = drop_graph()
        g.add(Recipe("novel", "t", [("mod:plate", 2)],
                     [Ingredient(["contenttweaker:not_curated_yet"], 1)]))
        offered = {key for key, _n, _c in tokens.candidates(g)}
        self.assertIn("contenttweaker:not_curated_yet", offered)
        # Both of these are in DEFAULT_TOKENS already, so offering them would be noise.
        self.assertNotIn("contenttweaker:dungeon_drop", offered)
        self.assertNotIn("contenttweaker:chapter_1", offered)

    def test_something_a_recipe_produces_is_never_offered(self):
        """The structural test is 'needed by one recipe, made by none'. An item with a
        recipe is an item, whatever its name looks like."""
        g = drop_graph()
        g.add(Recipe("makes", "t", [("contenttweaker:unknown_thing", 1)],
                     [Ingredient(["mod:screw"], 1)]))
        g.add(Recipe("uses", "t", [("mod:plate", 2)],
                     [Ingredient(["contenttweaker:unknown_thing"], 1)]))
        self.assertNotIn("contenttweaker:unknown_thing",
                         {key for key, _n, _c in tokens.candidates(g)})

    def test_only_the_pack_script_namespace_is_scanned(self):
        g = drop_graph()
        self.assertNotIn("mod:screw", {key for key, _n, _c in tokens.candidates(g)})

    def test_offers_are_ordered_by_how_much_they_would_change(self):
        g = drop_graph()
        for rid in ("x", "y"):
            g.add(Recipe(rid, "t", [("mod:plate", 3)],
                         [Ingredient(["contenttweaker:often"], 1)]))
        g.add(Recipe("z", "t", [("mod:plate", 5)],
                     [Ingredient(["contenttweaker:rarely"], 1)]))
        offered = [(key, n) for key, _name, n in tokens.candidates(g)]
        keys = [key for key, _n in offered]
        self.assertEqual(dict(offered)["contenttweaker:often"], 2)
        self.assertLess(keys.index("contenttweaker:often"),
                        keys.index("contenttweaker:rarely"))


class PlanningTest(unittest.TestCase):
    KINDS = {"contenttweaker:dungeon_drop": tokens.LOOT,
             "contenttweaker:battle_tower": tokens.LOOT}

    def test_a_placeholder_leaves_the_shopping_list_for_its_own_report(self):
        """"1 Dungeon Drop" beside "128 Granite" reads as three materials where there are
        two materials and one instruction."""
        result = Solver(drop_graph(), token_kinds=self.KINDS).solve("mod:plate", 1)
        self.assertEqual([r["key"] for r in result["shopping_list"]], ["mod:screw"])
        self.assertEqual({r["key"] for r in result["tokens_needed"]}, set(self.KINDS))

    def test_the_node_says_which_kind_it_is(self):
        result = Solver(drop_graph(), token_kinds=self.KINDS).solve("mod:plate", 1)
        node = next(c for c in result["tree"]["children"]
                    if c["key"] == "contenttweaker:dungeon_drop")
        self.assertEqual(node["status"], STATUS_TOKEN)
        self.assertEqual(node["token_kind"], tokens.LOOT)

    def test_an_uncurated_placeholder_is_still_an_ordinary_shopping_line(self):
        """Nothing is guessed. A key the curated map does not name behaves exactly as it
        did before, which is what makes a wrong entry the only way to break a plan."""
        result = Solver(drop_graph()).solve("mod:plate", 1)
        self.assertIn("contenttweaker:dungeon_drop",
                      [r["key"] for r in result["shopping_list"]])
        self.assertEqual(result["tokens_needed"], [])

    def test_quantity_is_carried_not_flattened_to_one(self):
        result = Solver(drop_graph(), token_kinds=self.KINDS).solve("mod:plate", 7)
        qty = {r["key"]: r["qty"] for r in result["tokens_needed"]}
        self.assertEqual(qty["contenttweaker:dungeon_drop"], 7)

    def test_a_placeholder_is_not_double_counted_across_backtracks(self):
        """`tokens_needed` has to be in the solver's snapshot/restore alongside the other
        accumulators, exactly as `from_sources` does. A discarded branch that left its
        tally behind would report twice the drops."""
        g = Graph()
        g.names = {"mod:target": "Target", "mod:loop": "Loop",
                   "contenttweaker:boss_drop": "Boss Drop"}
        g.add(Recipe("cyclic", "t", [("mod:target", 1)],
                     [Ingredient(["mod:loop"], 1),
                      Ingredient(["contenttweaker:boss_drop"], 1)], category="c1"))
        g.add(Recipe("clean", "t", [("mod:target", 1)],
                     [Ingredient(["contenttweaker:boss_drop"], 1)], category="c2"))
        g.add(Recipe("loopback", "t", [("mod:loop", 1)],
                     [Ingredient(["mod:target"], 1)], category="c3"))
        result = Solver(g, token_kinds={"contenttweaker:boss_drop": tokens.LOOT}
                        ).solve("mod:target", 1)
        got = {r["key"]: r["qty"] for r in result["tokens_needed"]}
        self.assertEqual(got.get("contenttweaker:boss_drop"), 1)


class PresentationTest(unittest.TestCase):
    def test_the_badge_word_comes_from_the_kind(self):
        """One status covers every placeholder because they behave identically to the
        solver. They do not read identically: "go get" on a quest gate would send someone
        hunting for an item that unlocks by playing the story."""
        self.assertEqual(present.status_badge(STATUS_TOKEN, tokens.LOOT)[0], "go get")
        self.assertEqual(present.status_badge(STATUS_TOKEN, tokens.GATE)[0], "locked")
        # and an unknown kind falls back rather than rendering an empty badge
        self.assertEqual(present.status_badge(STATUS_TOKEN, "nonsense")[0], "go get")
        self.assertEqual(present.status_badge("have")[0], "in stock")

    def test_the_key_folds_it_into_the_need_row_rather_than_inventing_a_colour(self):
        """It shares the need fill: both mean "not coming out of a crafting step". The key
        groups statuses that share a fill, so one red row reads "NEED, go get" instead of
        two reds inviting a hunt for a difference that is not there."""
        rows = present.status_legend(["raw", STATUS_TOKEN])
        self.assertEqual(rows, [("var(--needbg)", "var(--need)", "NEED, go get")])

    def test_the_plan_page_groups_them_under_one_heading(self):
        result = Solver(drop_graph(), token_kinds=PlanningTest.KINDS).solve("mod:plate", 1)
        page = render.render_html(result)
        self.assertIn("<span>Not crafted, obtained</span>", page)
        self.assertIn(tokens.KIND_LABEL[tokens.LOOT], page)
        # The individual sources stay named under the heading.
        self.assertIn("Dungeon Drop", page)
        self.assertIn("From Battle Tower Loot", page)
        # One heading, not one per source.
        self.assertEqual(page.count('class="tgroup"'), 1)

    def test_a_plan_with_no_placeholders_has_no_panel(self):
        page = render.render_html(Solver(drop_graph()).solve("mod:plate", 1))
        # Asserted on the markup: the phrase also appears in a CSS comment, so a bare
        # substring check passes whether or not the panel is there.
        self.assertNotIn("<span>Not crafted, obtained</span>", page)
        self.assertNotIn('class="tgroup"', page)

    def test_the_group_heading_is_styled(self):
        self.assertIn(".tgroup{", render.CSS)

    def test_show_only_what_i_need_still_shows_the_placeholders(self):
        """They left the shopping list, not the plan. The button says "what I need" and a
        Dungeon Drop is emphatically something you need, so filtering it out would answer
        "4 Screws" to a plan that also wants two afternoons of loot."""
        result = Solver(drop_graph(), token_kinds=PlanningTest.KINDS).solve("mod:plate", 1)
        page = render.render_html(result)
        for name in ("Dungeon Drop", "From Battle Tower Loot"):
            self.assertIn(
                'data-hasneed="1" data-blocked="0"><span class="qty">1&times;</span>'
                '<span class="nm">%s</span>' % name, page,
                "%s would be hidden by the need filter" % name)


class TokensCommandTest(unittest.TestCase):
    """The listing is how a curated list stays honest, so its guards are worth testing."""

    class Args(object):
        def __init__(self, **kw):
            self.graph = kw.get("graph")
            self.file = kw.get("file")
            self.limit = kw.get("limit", 5)
            self.add = kw.get("add")
            self.disable = kw.get("disable")

    def setUp(self):
        self.path = os.path.join(tempfile.mkdtemp(), "graph.json")
        drop_graph().save(self.path)
        self.ov = os.path.join(tempfile.mkdtemp(), "tokens.json")

    def _run(self, **kw):
        """Stdout captured: the listing is the command's whole point and printing it into
        the suite output buries every other test's failure message."""
        from recipegraph import cli
        buf, err = io.StringIO(), io.StringIO()
        stdout, stderr = sys.stdout, sys.stderr
        sys.stdout, sys.stderr = buf, err
        try:
            self.out = buf
            return cli.cmd_tokens(self.Args(graph=self.path, file=self.ov, **kw))
        finally:
            sys.stdout, sys.stderr = stdout, stderr
            self.printed = buf.getvalue()

    def test_a_bad_kind_is_refused_rather_than_written(self):
        # Writing it would put a key in the file that `resolve` then silently drops, so the
        # user's edit would appear to work and do nothing.
        self.assertEqual(self._run(add=["mod:x=nonsense"]), 2)
        self.assertFalse(os.path.exists(self.ov))

    def test_a_malformed_pair_is_refused(self):
        self.assertEqual(self._run(add=["mod:x"]), 2)
        self.assertFalse(os.path.exists(self.ov))

    def test_a_good_addition_is_written_and_then_recognised(self):
        self.assertEqual(self._run(add=["contenttweaker:mine=loot"]), 0)
        self.assertEqual(tokens.for_path(self.ov)["contenttweaker:mine"], tokens.LOOT)

    def test_disabling_removes_a_curated_default(self):
        self.assertEqual(self._run(disable=["contenttweaker:dungeon_drop"]), 0)
        self.assertNotIn("contenttweaker:dungeon_drop", tokens.for_path(self.ov))

    def test_the_listing_shows_the_families_the_plan_prices(self):
        """The listing resolves WITH the graph, or it disagrees with every other surface.

        #171. This command is the one place a person audits the curated list, so a listing
        that printed 37 while `plan` priced 47 would hide the very drift the completion rule
        exists to close -- and it would hide it on the screen someone opened specifically to
        look for drift. Caught by running the real command against the reference pack.
        """
        graph = drop_graph()
        graph.names["contenttweaker:hunter_level_40"] = "Hunter Level 40"
        graph.add(Recipe("gate", "t", [("mod:plate", 1)],
                         [Ingredient(["contenttweaker:hunter_level_40"], 1)]))
        graph.save(self.path)
        self.assertEqual(self._run(), 0)
        self.assertIn("contenttweaker:hunter_level_40", self.printed)
        # And it is listed as RECOGNISED rather than merely offered as a candidate, which is
        # the difference between "the tool prices this as a gate" and "somebody should look".
        recognised = self.printed.split("not recognised")[0]
        self.assertIn("contenttweaker:hunter_level_40", recognised)


if __name__ == "__main__":
    unittest.main()
