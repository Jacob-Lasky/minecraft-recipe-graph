"""Diagram layout for a solved plan."""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import graphview  # noqa: E402


def node(key, status="craft", need=1, kind="item", label=None, children=(),
         machine=None, machine_state=None):
    d = {"key": key, "label": label or key.split(":")[-1], "kind": kind,
         "status": status, "need": need, "children": list(children)}
    if machine:
        d["machine"] = machine
    if machine_state:
        d["machine_state"] = machine_state
    return d


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
        svg, _legend = graphview.render_diagram(plan())
        self.assertTrue(svg.startswith("<svg"))
        self.assertEqual(svg.count('class="nd"'), 8)
        # No external asset may be referenced: the artifact CSP blocks every off-host request.
        for forbidden in ("http://", "https://", "<image", "<script", "@import"):
            self.assertNotIn(forbidden, svg, forbidden)

    def test_every_box_links_to_a_plan_for_that_item(self):
        svg, _legend = graphview.render_diagram(plan())
        self.assertIn('href="/plan?item=nc%3Aborax&amp;qty=1"', svg)

    def test_fluids_are_marked_and_carry_mB(self):
        svg, _legend = graphview.render_diagram(plan())
        self.assertIn("128,000 mB", svg)
        # The root is an item, so its quantity carries no unit.
        self.assertIn(">64<", svg)

    def test_a_long_label_is_shortened_so_it_cannot_run_under_the_quantity(self):
        nodes, _l, _r = graphview.layout(plan())
        longest = max(len(n["label"]) for n in nodes)
        self.assertLessEqual(longest, graphview.MAX_LABEL)
        self.assertIn("…", graphview.render_diagram(plan())[0])

    def test_status_colours_come_from_the_shared_tokens(self):
        # The same status must mean the same thing in the tree and the diagram.
        svg, _legend = graphview.render_diagram(plan())
        self.assertIn("var(--okbg)", svg)     # in stock / infinite source
        self.assertIn("var(--craftbg)", svg)  # crafted

    def test_the_legend_explains_the_colours_this_plan_actually_used(self):
        """#49: yellow and blue had no legend entry anywhere on the page."""
        _svg, legend = graphview.render_diagram(plan())
        self.assertIn("in stock, infinite", legend)   # both green, one row
        self.assertIn("craft", legend)
        self.assertIn("background:var(--okbg)", legend)
        self.assertIn("background:var(--craftbg)", legend)
        # This plan has no partial and no raw node, so their colours are not on the page
        # and must not be in its key.
        self.assertNotIn("part stock", legend)
        self.assertNotIn("NEED", legend)

    def test_the_legend_describes_the_boxes_that_were_drawn_not_the_whole_tree(self):
        """A truncated plan draws fewer statuses than the tree contains. The legend comes
        out of the same `layout` as the boxes so it cannot name a colour that is absent."""
        svg, legend = graphview.render_diagram(plan(), max_nodes=1)
        self.assertEqual(svg.count('class="nd"'), 1)
        # The root is a craft node; the green source nodes below it were never drawn.
        self.assertIn("craft", legend)
        self.assertNotIn("in stock", legend)

    def test_an_empty_plan_has_no_empty_legend_shell(self):
        _svg, legend = graphview.render_diagram({}, max_nodes=0)
        self.assertEqual(legend, "")

    def test_a_blocked_step_is_outlined_without_hovering_it(self):
        """#37: the machine was in the SVG `<title>` and nowhere else, so the one fact that
        says "this step is where you stop" needed a hover, which touch does not have and
        nobody can scan."""
        tree = node("mod:out", machine="Press", machine_state="buildable",
                    children=[node("mod:in", status="have")])
        svg, legend = graphview.render_diagram(tree)
        self.assertIn('stroke-dasharray="4 2.5"', svg)
        self.assertEqual(svg.count('stroke-dasharray'), 1, "only the blocked box")
        self.assertIn("needs a machine you do not have", legend)
        # The words stay in the title: the outline says THAT it is blocked, not how badly.
        self.assertIn("machine buildable", svg)

    def test_a_machine_you_have_is_not_marked(self):
        """"idc to see it if the machine exists already". Outlining every step would bury
        the four that matter under fifty that do not."""
        tree = node("mod:out", machine="Press", machine_state="have",
                    children=[node("mod:in", status="have")])
        svg, legend = graphview.render_diagram(tree)
        self.assertNotIn("stroke-dasharray", svg)
        self.assertNotIn("needs a machine", legend)

    def test_an_unidentified_machine_is_marked_but_says_it_is_unidentified(self):
        """It means "this tool could not work out which block this is", not "you lack it".
        Hiding it would put a tooling gap behind a clean plan; calling it missing would
        overstate. It is marked, and the title uses the honest word."""
        svg, _legend = graphview.render_diagram(
            node("mod:out", machine="Mystery", machine_state="unknown"))
        self.assertIn("stroke-dasharray", svg)
        self.assertIn("machine unidentified", svg)

    def test_the_outline_is_a_different_channel_from_the_fill(self):
        """Fill carries the plan status and the outline carries machine availability. If the
        outline were another colour the two facts would compete for one signal."""
        svg, _legend = graphview.render_diagram(
            node("mod:out", status="have", machine="Press", machine_state="buildable"))
        # Still filled by its STATUS, green, not recoloured by the machine problem.
        self.assertIn('fill="var(--okbg)"', svg)
        self.assertIn('stroke="var(--ok)"', svg)

    def test_the_outline_key_has_no_fill_of_its_own(self):
        # A background on that swatch would read as a fifth fill colour.
        self.assertIn(".legend .sw.dash{background:transparent", graphview.DIAGRAM_CSS)

    def test_layout_carries_the_machine_state_it_used_to_drop(self):
        nodes, _l, _r = graphview.layout(
            node("mod:out", machine="Press", machine_state="buildable"))
        self.assertEqual(nodes[0]["machine_state"], "buildable")
        # Absent rather than missing: every record has the key, so no consumer needs a get.
        nodes, _l, _r = graphview.layout(node("mod:x"))
        self.assertEqual(nodes[0]["machine_state"], "")

    def test_mod_hue_is_stable_and_shared_within_a_mod(self):
        self.assertEqual(graphview._hue("nuclearcraft:a"), graphview._hue("nuclearcraft:b"))
        self.assertNotEqual(graphview._hue("nuclearcraft:a"), graphview._hue("botania:a"))

    def test_an_empty_tree_does_not_raise(self):
        self.assertIn("Nothing to draw", graphview.render_diagram({}, max_nodes=0)[0])

    def test_markup_in_a_name_is_escaped(self):
        svg, _legend = graphview.render_diagram(node("mod:x", label='<script>"&'))
        self.assertNotIn("<script>", svg)
        self.assertIn("&lt;script&gt;", svg)


if __name__ == "__main__":
    unittest.main()
