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
        self.assertIn(".diagram{", page)
        self.assertIn('id="diagbox"', page)
        self.assertIn('class="diagram"', page)
        self.assertIn(graphview.render_svg(result["tree"]), page)


if __name__ == "__main__":
    unittest.main()
