"""Presentation is complete for every status and state the code can produce.

These are contract tests, not behaviour tests. Every renderer keys off bare strings that
originate as constants elsewhere, and a missing entry does not raise -- it silently falls
back, so a new solver status would draw as "craft" and a new machine state as an unlabelled
grey pill. These tests are the thing that turns that into a failure.
"""

import os
import re
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import (graphview, machines, pins, present, render, server,  # noqa: E402
                         solve)


def solver_status_constants():
    """Every STATUS_* the solver defines, discovered rather than restated.

    Restating the list here would defeat the point: a status added to solve.py has to show
    up in this test without anyone remembering to update it.
    """
    return {value for name, value in vars(solve).items()
            if name.startswith("STATUS_") and isinstance(value, str)}


class StatusCoverageTest(unittest.TestCase):
    def test_every_solver_status_has_a_tree_badge(self):
        for status in solver_status_constants():
            self.assertIn(status, present.STATUS_LABEL, status)

    def test_every_solver_status_has_a_diagram_style(self):
        for status in solver_status_constants():
            self.assertIn(status, present.STATUS_STYLE, status)

    def test_the_oredict_display_status_is_covered_too(self):
        # `resolve_ore` emits "oredict", which is a display distinction rather than a
        # resolution outcome, so it is not a STATUS_* constant and has to be listed.
        self.assertIn(present.STATUS_OREDICT, present.STATUS_LABEL)
        self.assertIn(present.STATUS_OREDICT, present.STATUS_STYLE)

    def test_all_statuses_matches_the_two_maps(self):
        self.assertEqual(set(present.ALL_STATUSES), set(present.STATUS_LABEL))
        self.assertEqual(set(present.ALL_STATUSES), set(present.STATUS_STYLE))

    def test_every_status_reaches_the_legend(self):
        """#49: the diagram coloured four states and explained none. A status with a fill
        and no legend row is a colour on the page that nothing accounts for."""
        listed = set()
        for _fill, _ink, labels in present.status_legend():
            listed.update(labels.split(", "))
        self.assertEqual(listed, {lab for lab, _cls in present.STATUS_LABEL.values()})

    def test_the_legend_says_what_the_tree_badge_says(self):
        """One vocabulary. A reader who learns "part stock" from the tree has to recognise
        it in the legend, so the legend takes its words from STATUS_LABEL rather than
        spelling its own."""
        for status in present.ALL_STATUSES:
            fill, _ink = present.STATUS_STYLE[status]
            label = present.STATUS_LABEL[status][0]
            row = [r for r in present.status_legend([status]) if r[0] == fill]
            self.assertEqual([(fill, label)], [(r[0], r[2]) for r in row], status)

    def test_statuses_that_share_a_fill_share_one_legend_row(self):
        """`have` and `source` are both green. Two identical swatches in one legend reads
        as a legend bug rather than as the truth, which is that green means either."""
        rows = present.status_legend()
        self.assertEqual(len(rows), len({fill for fill, _ink, _lab in rows}))
        green = [lab for fill, _ink, lab in rows if fill == "var(--okbg)"]
        self.assertEqual(green, ["in stock, infinite"])

    def test_the_legend_only_covers_the_statuses_asked_for(self):
        rows = present.status_legend(["craft"])
        self.assertEqual(rows, [("var(--craftbg)", "var(--craft)", "craft")])
        self.assertEqual(present.status_legend([]), [])

    def test_legend_order_does_not_depend_on_the_caller(self):
        """A set comes in, so without an explicit order the legend would reshuffle between
        two plans containing the same statuses."""
        self.assertEqual(present.status_legend({"raw", "have", "craft"}),
                         present.status_legend(["craft", "raw", "have"]))

    def test_the_muted_grey_is_defined_in_every_document_that_uses_it(self):
        """It was written out four times: `present._MUTED` plus three CSS rules, so changing
        the badge grey would have left the diagram box a different grey with nothing failing.
        It is `--mutedbg` now, defined once, in render.CSS.

        An undefined custom property does not fall back, it makes the declaration invalid at
        computed-value time, so a grey pill would render with NO background rather than
        visibly breaking. Every document kind is checked because they compose different
        stylesheets: server pages take render.CSS plus HOME_CSS, and a plan or explore
        fragment carries render.CSS inline so it still works published as an Artifact.
        """
        sheets = (render.CSS, server.HOME_CSS, graphview.DIAGRAM_CSS)
        self.assertEqual(sum(s.count("--mutedbg:") for s in sheets), 1,
                         "the token must be defined exactly once")
        # Rendered documents, not stylesheets, because the definition and the uses can come
        # from different sheets and only a document proves they met.
        for name, doc in (("plan page", render.render_html(self.MUTED_RESULT)),
                          ("server page", server._page("t", "<div class='pill mut'>x</div>"))):
            self.assertIn("var(--mutedbg)", doc, name)
            self.assertIn("--mutedbg:", doc, "%s uses --mutedbg and never defines it" % name)
        self.assertEqual(present.STATUS_STYLE[present.STATUS_OREDICT][0], "var(--mutedbg)")

    MUTED_RESULT = {
        "target": "mod:x", "target_name": "X", "qty": 1, "nodes": 1,
        "tree": {"key": "mod:x", "label": "X", "kind": "item", "need": 1,
                 "status": present.STATUS_OREDICT},
        "shopping_list": [], "used_from_stock": [], "from_sources": [],
        "machines_to_build": [], "truncated": False,
    }

    def test_the_badge_class_is_one_the_stylesheet_defines(self):
        # A class with no rule renders as unstyled text, which reads as a missing badge.
        for _label, cls in present.STATUS_LABEL.values():
            self.assertIn(".%s{" % cls, render.CSS, cls)


class StateCoverageTest(unittest.TestCase):
    def test_every_machine_state_has_a_label_a_pill_and_a_badge(self):
        for state in machines.STATES:
            self.assertIn(state, present.STATE_LABEL, state)
            self.assertIn(state, present.STATE_PILL, state)
            self.assertIn(state, present.STATE_BADGE, state)
            self.assertIn(state, present.STATE_RANK, state)

    def test_rank_order_follows_machines_STATES(self):
        self.assertEqual([present.STATE_RANK[s] for s in machines.STATES],
                         list(range(len(machines.STATES))))
        self.assertEqual(present.UNRANKED, len(machines.STATES))

    def test_pill_and_badge_classes_exist_in_the_stylesheets(self):
        for cls in present.STATE_PILL.values():
            self.assertIn(".pill.%s{" % cls, server.HOME_CSS, cls)
        for cls in present.STATE_BADGE.values():
            self.assertIn(".%s{" % cls, render.CSS, cls)

    def test_the_severity_stripe_covers_every_state(self):
        for state in machines.STATES:
            self.assertIn("tr[data-state=%s]" % state, server.HOME_CSS, state)


class PinStateCoverageTest(unittest.TestCase):
    """A pin outranks the tool's own judgement, so the reader has to be able to tell which
    of the three things it is currently doing. A state with no wording renders blank."""

    STATES = (pins.EXACT, pins.CATEGORY, pins.DEAD)

    def test_every_pin_state_has_a_note_and_a_class(self):
        for state in self.STATES:
            self.assertIn(state, present.PIN_NOTE, state)
            self.assertIn(state, present.PIN_CLASS, state)

    def test_every_pin_state_badges_to_words_and_a_class_the_sheet_defines(self):
        for state in self.STATES:
            text, cls = present.pin_badge(state)
            self.assertTrue(text.strip(), state)
            self.assertIn(".%s{" % cls, render.CSS, cls)

    def test_a_working_pin_reads_as_pinned_and_does_not_narrate(self):
        # `exact` is the common case and the only one with nothing to explain. A note
        # there would put a sentence on every pinned node in every tree.
        self.assertEqual(present.pin_badge(pins.EXACT)[0], "pinned")
        self.assertEqual(present.PIN_NOTE[pins.EXACT], "")

    def test_a_lapsed_pin_never_reads_as_a_working_one(self):
        words = {present.pin_badge(s)[0] for s in self.STATES}
        self.assertEqual(len(words), 3, words)


class KindChipTest(unittest.TestCase):
    def test_every_non_item_kind_has_a_chip(self):
        g = __import__("recipegraph.model", fromlist=["Graph"]).Graph()
        for key, kind in (("fluid:water", "fluid"), ("essentia:terra", "essentia"),
                          ("ore:ingotIron", "ore")):
            self.assertEqual(g.kind(key), kind)
            self.assertIn(kind, present.KIND_CHIP, kind)

    def test_a_plain_item_gets_no_chip(self):
        self.assertEqual(render.kind_chip("item"), "")
        self.assertEqual(render.kind_chip(None), "")

    def test_each_chip_class_is_styled(self):
        for kind in present.KIND_CHIP:
            self.assertIn(".t-%s{" % kind, render.CSS, kind)

    def test_the_browser_gets_the_same_map_as_the_server(self):
        # The typeahead renders rows client-side, so the map is injected rather than
        # restated. If the placeholder ever stops being substituted the JS would throw.
        js = server.HOME_JS.replace("%%CHIPS%%", present.kind_chip_json())
        self.assertNotIn("%%CHIPS%%", js)
        for kind, label in present.KIND_CHIP.items():
            self.assertIn('"%s": "%s"' % (kind, label), js)

    def test_the_placeholder_is_actually_substituted_on_the_page(self):
        self.assertIn("%%CHIPS%%", server.HOME_JS)


class HiddenNoteTest(unittest.TestCase):
    """One wording for suppressed dead matches, across three surfaces. See #26."""

    def test_nothing_hidden_says_nothing(self):
        self.assertEqual(present.hidden_note(0), "")

    def test_the_count_is_grouped_and_the_reason_is_given(self):
        # "174705 hidden" is both hard to read and an invitation to wonder what else is
        # being withheld. Both halves of that are the point of the sentence.
        note = present.hidden_note(174705)
        self.assertIn("174,705", note)
        self.assertIn("no recipe makes or uses them", note)

    def test_the_explore_page_prints_it(self):
        page = render.render_explore_html(
            {"query": "plut", "results": [], "hidden": 94, "searched": 12})
        self.assertIn(present.hidden_note(94), page)

    def test_the_explore_page_says_nothing_when_nothing_was_hidden(self):
        page = render.render_explore_html(
            {"query": "plut", "results": [], "hidden": 0, "searched": 12})
        self.assertNotIn("no recipe makes or uses them", page)

    def test_the_browser_is_handed_the_sentence_rather_than_writing_one(self):
        # The typeahead renders client-side, so the temptation is a second copy of the
        # wording in JS. It gets `note` from /suggest instead; a copy in the script is the
        # regression this catches.
        self.assertNotIn("no recipe makes or uses", server.HOME_JS)
        self.assertIn("d.note", server.HOME_JS)

    def test_only_one_module_writes_the_sentence(self):
        root = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                            "recipegraph")
        wrote = []
        for name in sorted(os.listdir(root)):
            if not name.endswith(".py"):
                continue
            with open(os.path.join(root, name)) as fh:
                if "no recipe makes or uses them" in fh.read():
                    wrote.append(name)
        self.assertEqual(wrote, ["present.py"])

    def test_only_one_module_words_a_lapsed_pin(self):
        # Same rule as above, for the sentence a pin shows when it stops matching. Three
        # surfaces say it -- the tree, the chooser and the CLI's `pins` listing -- and a
        # reader who learns it on one has to recognise it on the others.
        root = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                            "recipegraph")
        wrote = []
        for name in sorted(os.listdir(root)):
            if not name.endswith(".py"):
                continue
            with open(os.path.join(root, name)) as fh:
                if present.PIN_NOTE[pins.CATEGORY] in fh.read():
                    wrote.append(name)
        self.assertEqual(wrote, ["present.py"])


class SharedEscapeTest(unittest.TestCase):
    def test_only_one_escape_implementation_remains(self):
        # Three modules had their own identical `_esc`; one of them diverging is the sort of
        # bug that shows up as broken markup on exactly one page.
        root = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                            "recipegraph")
        defs = []
        for name in sorted(os.listdir(root)):
            if not name.endswith(".py"):
                continue
            with open(os.path.join(root, name)) as fh:
                if re.search(r"^def _?esc\(", fh.read(), re.M):
                    defs.append(name)
        self.assertEqual(defs, ["htmlutil.py"])

    def test_attributes_and_text_are_both_escaped(self):
        from recipegraph.htmlutil import esc

        self.assertEqual(esc('<a href="x">&'), "&lt;a href=&quot;x&quot;&gt;&amp;")


class DiagramWiringTest(unittest.TestCase):
    def test_the_plan_page_carries_the_diagram_and_its_styles(self):
        # render_html builds the diagram inline; a missing style block would render the SVG
        # unstyled rather than failing, so assert both are present.
        result = {
            "target": "mod:x", "target_name": "X", "qty": 1, "nodes": 1,
            "tree": {"key": "mod:x", "label": "X", "kind": "item", "need": 1,
                     "status": solve.STATUS_RAW},
            "shopping_list": [], "used_from_stock": [], "from_sources": [],
            "machines_to_build": [], "truncated": False,
        }
        page = render.render_html(result)
        svg, legend = graphview.render_diagram(result["tree"])
        self.assertIn(".diagram{", page)
        self.assertIn('id="diagbox"', page)
        self.assertIn('class="diagram"', page)
        self.assertIn(svg, page)
        # #49: the legend is the half that makes the fills readable, and an unstyled one
        # would render as a bullet list rather than as swatches, so assert both again.
        self.assertIn(legend, page)
        self.assertIn("NEED", legend)
        for rule in (".legend{", ".legend li{", ".legend .sw{"):
            self.assertIn(rule, page, rule)
        # It has to sit ABOVE the diagram: a reader meets the key before the boxes rather
        # than after scrolling a tall plan.
        self.assertLess(page.index(legend), page.index(svg))
        # The note below the diagram explains the per-mod SWATCH, which is a different axis
        # from the box fill. It used to be the only colour text on the page, so a reader
        # looking for what blue meant was pointed at the wrong thing; it now says which axis
        # it is talking about and defers to the key for the other.
        self.assertIn("groups items by mod", page)
        self.assertIn("per the key above", page)


class TreeRoadblockTest(unittest.TestCase):
    """#37: the solver has always written `machine_state` onto a craft node and the tree
    renderer ignored it, so a step whose machine does not exist looked exactly like a step
    whose machine is placed and running."""

    @staticmethod
    def _page(state, why="craftable: mod:press"):
        tree = {"key": "mod:out", "label": "Out", "kind": "item", "need": 1,
                "status": solve.STATUS_CRAFT, "category": "mod.press", "machine": "Press",
                "children": [{"key": "mod:in", "label": "In", "kind": "item", "need": 1,
                              "status": solve.STATUS_HAVE}]}
        if state:
            tree["machine_state"] = state
            if state != machines.HAVE:
                tree["machine_why"] = why
        return render.render_html({
            "target": "mod:out", "target_name": "Out", "qty": 1, "nodes": 2, "tree": tree,
            "shopping_list": [], "used_from_stock": [], "from_sources": [],
            "machines_to_build": [], "truncated": False,
        })

    def test_a_blocked_step_carries_its_state_and_the_reason(self):
        page = self._page(machines.BUILDABLE)
        self.assertIn('<span class="badge warn"', page)
        self.assertIn(present.STATE_LABEL[machines.BUILDABLE], page)
        self.assertIn('title="craftable: mod:press"', page,
                      "machines.resolve's own words for WHY, not a generic label")

    def test_a_machine_you_have_gets_no_badge(self):
        """The plan is mostly owned machines. Badging all of them is a wall of green that
        hides the few that matter."""
        page = self._page(machines.HAVE)
        self.assertIn("Press", page)
        self.assertNotIn('<span class="badge ok">have</span>', page)

    def test_the_machine_name_links_to_the_page_that_explains_it(self):
        self.assertIn('href="/machine?uid=mod.press"', self._page(machines.BUILDABLE))

    def test_a_blocked_branch_is_marked_all_the_way_up(self):
        """Hiding a branch whose roadblock is three levels down would hide the answer with
        the noise, which is the mistake `data-hasneed` already avoids."""
        page = self._page(machines.UNAVAILABLE)
        self.assertIn('data-blocked="1"', page)
        # the child has no machine of its own, so it is not itself a roadblock
        self.assertIn('data-blocked="0"', page)

    def test_the_filter_button_appears_only_when_something_is_blocked(self):
        """A button that empties the tree reads as a broken filter, not as "nothing is
        blocked"."""
        self.assertIn('id="blockedonly"', self._page(machines.BUILDABLE))
        self.assertNotIn('id="blockedonly"', self._page(machines.HAVE))
        self.assertNotIn('id="blockedonly"', self._page(None))

    def test_the_two_tree_filters_cannot_both_be_on(self):
        """Both narrow the same tree by setting `display`, so with both active, turning one
        off would leave nodes hidden while its own button read "Show every step"."""
        page = self._page(machines.BUILDABLE)
        self.assertIn("active=(active===id)?null:id", page)
        self.assertIn("Show every step", page)

    def test_every_state_the_tree_can_badge_has_a_class_the_sheet_defines(self):
        for state in machines.STATES:
            self.assertIn(".%s{" % present.STATE_BADGE[state], render.CSS, state)

    def test_the_machine_link_is_styled_for_the_prose_it_sits_in(self):
        """An unstyled <a> takes the browser default, blue and underlined, inside dim
        11.5px meta text, and there is one per craft node. It follows `.crumb a`."""
        self.assertIn(".mlink{color:inherit;text-decoration:none", render.CSS)
        # Gated like every other :hover in this sheet, per the standing rule up top.
        self.assertIn("@media(hover:hover){.mlink:hover", render.CSS)

    def test_the_machines_panel_links_and_explains_the_same_as_the_tree(self):
        """The panel is the SUMMARY of the roadblocks the tree marks, and it listed them as
        dead text with no reason, so the one place holding the whole list was the one place
        you could not click through from."""
        page = render.render_html({
            "target": "mod:x", "target_name": "X", "qty": 1, "nodes": 1,
            "tree": {"key": "mod:x", "label": "X", "kind": "item", "need": 1,
                     "status": solve.STATUS_RAW},
            "shopping_list": [], "used_from_stock": [], "from_sources": [],
            "machines_to_build": [{"category": "mod.press", "machine": "Press",
                                   "state": machines.BUILDABLE,
                                   "why": "craftable: mod:press"}],
            "truncated": False,
        })
        self.assertIn('<a class="mlink" href="/machine?uid=mod.press">Press</a>', page)
        self.assertIn('title="craftable: mod:press"', page)

    def test_a_machine_with_no_category_still_renders(self):
        # `machine_href` has nothing to point at, and a dead link is worse than plain text.
        # Asserted on the anchor, not on the word: `.mlink` is in the stylesheet regardless.
        page = render.render_html({
            "target": "mod:x", "target_name": "X", "qty": 1, "nodes": 1,
            "tree": {"key": "mod:x", "label": "X", "kind": "item", "need": 1,
                     "status": solve.STATUS_RAW},
            "shopping_list": [], "used_from_stock": [], "from_sources": [],
            "machines_to_build": [{"machine": "Press", "state": machines.BUILDABLE}],
            "truncated": False,
        })
        self.assertNotIn('<a class="mlink"', page)
        self.assertIn("<td>Press</td>", page)


class RoadblockPredicateTest(unittest.TestCase):
    def test_only_a_machine_you_do_not_have_counts(self):
        self.assertFalse(present.is_roadblock(machines.HAVE))
        for state in (machines.BUILDABLE, machines.UNKNOWN, machines.UNAVAILABLE):
            self.assertTrue(present.is_roadblock(state), state)

    def test_no_machine_at_all_is_not_a_roadblock(self):
        # Hand crafting, and any category the solver never resolved a machine for. Flagging
        # these would put a warning on most of a plan.
        for empty in ("", None):
            self.assertFalse(present.is_roadblock(empty), repr(empty))


if __name__ == "__main__":
    unittest.main()


class TruncationNoticeTest(unittest.TestCase):
    """What a cut-off plan tells you to do next, which depends on where you are reading it.

    #25. The notice said "Raise --max-nodes to see more" on every surface. On the web page
    that is not merely inconvenient, it is WRONG: the server is already running and
    `--max-nodes` is an argument to `plan`, not to `serve`. Jake: *"there should be a
    button to click in this UI that will let me go deeper on this particular recipe"*.
    """

    @staticmethod
    def _page(deeper, nodes=4001, exhausted=False, **extra):
        result = {
            "target": "mod:x", "target_name": "Widget", "qty": 1, "nodes": nodes,
            "tree": {"key": "mod:x", "label": "X", "kind": "item", "need": 1,
                     "status": solve.STATUS_RAW},
            "shopping_list": [], "used_from_stock": [], "from_sources": [],
            "machines_to_build": [], "truncated": True,
            "exhausted": exhausted, "max_nodes": 4000, "work": 80001,
        }
        result.update(extra)
        return render.render_html(result, deeper=deeper)

    def test_the_two_reasons_a_tree_stops_are_not_described_the_same_way(self):
        """`truncated` covers the node cap AND the work budget, and they are different news.

        The work budget goes first on a graph full of cycles: the search spends itself on
        routes it backtracks out of, so the node count lands far BELOW the cap. Real
        example, `avaritia:resource:6` on the reference pack -- "Tree hit the node cap
        (1,162)" on a plan capped at 4,000 reads as a bug in the tool.
        """
        capped = self._page(None)
        self.assertIn("node cap (4,000)", capped)
        self.assertNotIn("1,162", capped)

        spent = self._page(None, nodes=1162, exhausted=True, work=80001)
        self.assertNotIn("node cap", spent)
        self.assertIn("80,001 steps", spent)
        self.assertIn("backed out of", spent)

    def test_the_cap_is_quoted_not_the_count_it_stopped_at(self):
        # "hit the node cap (4,001)" names a limit that is not the limit.
        self.assertIn("node cap (4,000)", self._page(None, nodes=4001))

    def test_off_the_server_the_flag_is_still_the_answer(self):
        # The CLI writes an html file too, and there the flag is exactly right.
        page = self._page(None)
        self.assertIn("--max-nodes", page)
        self.assertNotIn("Go deeper", page)

    def test_on_the_page_it_offers_the_control_and_never_the_flag(self):
        page = self._page(("/plan?item=mod%3Ax&qty=1&max_nodes=8000", 8000))
        self.assertIn("Go deeper", page)
        self.assertIn("8,000 nodes", page)
        self.assertNotIn("--max-nodes", page,
                         "the running server has no such argument to raise")

    def test_at_the_ceiling_it_says_so_instead_of_offering_a_dead_button(self):
        page = self._page(("", 256000), nodes=256000)
        self.assertNotIn("Go deeper", page)
        self.assertIn("deepest this page goes", page)
        self.assertIn("256,000", page)

    def test_the_scrim_is_told_the_item_name(self):
        # `data-plan-label`, because "Planning Go deeper" is a useless thing to say back.
        page = self._page(("/plan?item=mod%3Ax&qty=1&max_nodes=8000", 8000))
        self.assertIn('data-plan-label="Widget"', page)

    def test_an_untruncated_plan_says_nothing_at_all(self):
        page = render.render_html({
            "target": "mod:x", "target_name": "Widget", "qty": 1, "nodes": 3,
            "tree": {"key": "mod:x", "label": "X", "kind": "item", "need": 1,
                     "status": solve.STATUS_RAW},
            "shopping_list": [], "used_from_stock": [], "from_sources": [],
            "machines_to_build": [], "truncated": False,
        }, deeper=("/plan?item=mod%3Ax&qty=1&max_nodes=8000", 8000))
        self.assertNotIn("Go deeper", page)
        self.assertNotIn("node cap", page)


class NodeCapConstantTest(unittest.TestCase):
    def test_one_default_for_the_cli_and_the_solver(self):
        """Two copies of 4000 is how `plan` and `serve` end up truncating at different
        depths while the warning quotes one of the numbers."""
        import inspect

        from recipegraph import defaults
        sig = inspect.signature(solve.Solver.__init__)
        self.assertEqual(sig.parameters["max_nodes"].default, defaults.DEFAULT_MAX_NODES)
        self.assertGreater(defaults.MAX_NODES_CEILING, defaults.DEFAULT_MAX_NODES)
