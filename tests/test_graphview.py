"""Diagram layout for a solved plan."""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import graphview  # noqa: E402


def node(key, status="craft", need=1, kind="item", label=None, children=()):
    return {"key": key, "label": label or key.split(":")[-1], "kind": kind,
            "status": status, "need": need, "children": list(children)}


def plan():
    """Borax's real shape: a root, a fluid, then two branches that each split again."""
    return node("nc:borax", label="Borax", need=64, children=[
        node("fluid:borax_solution", kind="fluid", need=42624, label="Borax Solution",
             children=[
                 node("fluid:sodium_fluoride_solution", kind="fluid", need=85248,
                      label="Sodium Fluoride Solution", children=[
                          node("nc:sodium_fluoride", status="have", need=128),
                          node("fluid:water", kind="fluid", status="source", need=128000),
                      ]),
                 node("fluid:boric_acid", kind="fluid", need=256000, children=[
                     node("fluid:diborane", kind="fluid", need=128000),
                     node("fluid:water", kind="fluid", status="source", need=768000),
                 ]),
             ]),
    ])


class LayoutTest(unittest.TestCase):
    def test_depth_becomes_the_column(self):
        nodes, _links, _rows = graphview.layout(plan())
        by_key = {n["key"]: n for n in nodes}
        self.assertEqual(by_key["nc:borax"]["depth"], 0)
        self.assertEqual(by_key["fluid:borax_solution"]["depth"], 1)
        self.assertEqual(by_key["fluid:boric_acid"]["depth"], 2)
        self.assertEqual(by_key["fluid:diborane"]["depth"], 3)

    def test_leaves_get_distinct_rows(self):
        nodes, _links, rows = graphview.layout(plan())
        leaves = [n for n in nodes if n["depth"] == 3]
        self.assertEqual(len(leaves), 4)
        self.assertEqual(len({n["row"] for n in leaves}), 4)
        self.assertEqual(rows, 4)

    def test_a_parent_is_centred_on_its_children(self):
        nodes, _links, _rows = graphview.layout(plan())
        by_key = {n["key"]: n for n in nodes}
        kids = [by_key["nc:sodium_fluoride"]["row"], by_key["fluid:diborane"]["row"]]
        parent = by_key["fluid:sodium_fluoride_solution"]["row"]
        self.assertGreater(parent, min(kids) - 1)

    def test_one_link_per_parent_child_edge(self):
        nodes, links, _rows = graphview.layout(plan())
        self.assertEqual(len(links), len(nodes) - 1)

    def test_truncation_marks_the_node_it_cut(self):
        # Stopping mid-level without saying so would draw a branch whose children silently
        # vanish, which reads as a complete plan that needs less than it does.
        nodes, _links, _rows = graphview.layout(plan(), max_nodes=3)
        self.assertEqual(len(nodes), 3)
        self.assertTrue(any(n["cut"] for n in nodes))

    def test_layout_is_deterministic(self):
        first = graphview.layout(plan())[0]
        second = graphview.layout(plan())[0]
        self.assertEqual([(n["depth"], n["row"]) for n in first],
                         [(n["depth"], n["row"]) for n in second])


class RenderTest(unittest.TestCase):
    def test_svg_is_self_contained_and_has_a_node_per_step(self):
        svg = graphview.render_svg(plan())
        self.assertTrue(svg.startswith("<svg"))
        self.assertEqual(svg.count('class="nd"'), 8)
        # No external asset may be referenced: the artifact CSP blocks every off-host request.
        for forbidden in ("http://", "https://", "<image", "<script", "@import"):
            self.assertNotIn(forbidden, svg, forbidden)

    def test_every_box_links_to_a_plan_for_that_item(self):
        svg = graphview.render_svg(plan())
        self.assertIn('href="/plan?item=nc%3Aborax&amp;qty=1"', svg)

    def test_fluids_are_marked_and_carry_mB(self):
        svg = graphview.render_svg(plan())
        self.assertIn("128,000 mB", svg)
        # The root is an item, so its quantity carries no unit.
        self.assertIn(">64<", svg)

    def test_a_long_label_is_shortened_so_it_cannot_run_under_the_quantity(self):
        nodes, _l, _r = graphview.layout(plan())
        longest = max(len(n["label"]) for n in nodes)
        self.assertLessEqual(longest, graphview.MAX_LABEL)
        self.assertIn("…", graphview.render_svg(plan()))

    def test_status_colours_come_from_the_shared_tokens(self):
        # The same status must mean the same thing in the tree and the diagram.
        svg = graphview.render_svg(plan())
        self.assertIn("var(--okbg)", svg)     # in stock / infinite source
        self.assertIn("var(--craftbg)", svg)  # crafted

    def test_mod_hue_is_stable_and_shared_within_a_mod(self):
        self.assertEqual(graphview._hue("nuclearcraft:a"), graphview._hue("nuclearcraft:b"))
        self.assertNotEqual(graphview._hue("nuclearcraft:a"), graphview._hue("botania:a"))

    def test_an_empty_tree_does_not_raise(self):
        self.assertIn("Nothing to draw", graphview.render_svg({}, max_nodes=0))

    def test_markup_in_a_name_is_escaped(self):
        svg = graphview.render_svg(node("mod:x", label='<script>"&'))
        self.assertNotIn("<script>", svg)
        self.assertIn("&lt;script&gt;", svg)


if __name__ == "__main__":
    unittest.main()
