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

from recipegraph import graphview, machines, present, render, server, solve  # noqa: E402


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


if __name__ == "__main__":
    unittest.main()
