"""Diagram layout for a solved plan."""

import os
import re
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import graphview, present, tokens  # noqa: E402
from recipegraph.solve import STATUS_RAW, STATUS_TOKEN  # noqa: E402


def node(key, status="craft", need=1, kind="item", label=None, children=(),
         machine=None, machine_state=None, token_kind=None, unsourced=False):
    d = {"key": key, "label": label or key.split(":")[-1], "kind": kind,
         "status": status, "need": need, "children": list(children)}
    if machine:
        d["machine"] = machine
    if machine_state:
        d["machine_state"] = machine_state
    if token_kind:
        d["token_kind"] = token_kind
    if unsourced:
        d["unsourced"] = True
    return d


def token(key="contenttweaker:dungeon_drop", kind=tokens.LOOT, label="Dungeon Drop", **kw):
    """A pack placeholder as the solver writes one: `status=token` plus its kind."""
    return node(key, status=STATUS_TOKEN, label=label, token_kind=kind, **kw)


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
        """Asserted on what is DRAWN, against the per-box budget.

        This used to measure the layout record's flat `MAX_LABEL` cap, which is not what
        gets drawn -- the box template shortens against `_label_limit(qty)`. So it could not
        fail if the label-budget wiring reverted, despite its name claiming that property.
        The record no longer carries a shortened copy at all.
        """
        svg = graphview.render_diagram(plan())[0]
        pairs = re.findall(r'class="lb"[^>]*>([^<]*)<.*?class="qt"[^>]*>([^<]*)<', svg)
        self.assertTrue(pairs)
        for shown, qty in pairs:
            self.assertLessEqual(len(shown), graphview._label_limit(qty),
                                 "%r does not fit beside %r" % (shown, qty))
        self.assertIn("…", svg)

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


class TokenNodeTest(unittest.TestCase):
    """A pack placeholder must not draw as an item you go and get. #174

    REPORTED ON AN OSIRIS SPINEL PLAN: "the osiris spinel shows it requires a Dungeon Drop
    which implies it is an item". The data was already right -- `status=token`, out of the
    shopping list, in its own panel -- and this renderer was the surface that disagreed: same
    fill as a real missing ingredient, a quantity, no word, and a link to a plan for it.

    The plan below is the reported one in miniature: a craft node over a LOOT placeholder and
    a genuine raw ingredient, which is the pair a reader could not tell apart.
    """

    def _plan(self):
        return node("ct:osiris_spinel", label="Osiris Spinel", children=[
            token(),
            node("mod:callstone", status=STATUS_RAW, label="Armorer Callstone"),
        ])

    def _boxes(self, svg):
        """`{label: box markup}`, one entry per node drawn."""
        out = {}
        for group in re.findall(r'<g class="nd">.*?</g>', svg):
            label = re.search(r'class="lb"[^>]*>([^<]*)<', group).group(1)
            out[label] = group
        return out

    def test_a_token_is_not_a_link_to_a_plan_for_itself(self):
        """The reported half. `/plan?item=contenttweaker:dungeon_drop` answers with a one-node
        plan whose entire content is the placeholder, which reads as the planner failing."""
        boxes = self._boxes(graphview.render_diagram(self._plan())[0])
        self.assertNotIn("<a href", boxes["Dungeon Drop"], boxes["Dungeon Drop"])
        self.assertIn("<a href", boxes["Armorer Callstone"],
                      "a real ingredient still links to its own plan")

    def test_the_shape_is_what_tells_them_apart(self):
        boxes = self._boxes(graphview.render_diagram(self._plan())[0])
        self.assertIn('rx="%.1f"' % graphview.TOKEN_RX, boxes["Dungeon Drop"])
        self.assertIn('rx="%.1f"' % graphview.BOX_RX, boxes["Armorer Callstone"])
        self.assertNotEqual(graphview.TOKEN_RX, graphview.BOX_RX)

    def test_the_swatch_carries_it_too(self):
        """TWO colour-independent carriers, because one was not enough.

        Measured at 1x on the reported Osiris Spinel plan: a stadium among 107 rounded
        rectangles is findable once you know to look and easy to scan past when you do not.
        The swatch is the diagram's other text slot and it costs none of the interior.
        """
        boxes = self._boxes(graphview.render_diagram(self._plan())[0])
        mark = re.compile(r'class="mk">([^<]*)<')
        self.assertEqual(mark.search(boxes["Dungeon Drop"]).group(1), graphview.TOKEN_MARK)
        self.assertEqual(mark.search(boxes["Armorer Callstone"]).group(1), "A",
                         "a real ingredient keeps its initial")

    def test_the_placeholder_mark_outranks_the_type_mark(self):
        """"It is a fluid" is a detail about a thing that does not exist."""
        svg = graphview.render_diagram(
            node("ct:goo", label="Goo", kind="fluid", status=STATUS_TOKEN,
                 token_kind=tokens.LOOT))[0]
        self.assertIn('class="mk">%s<' % graphview.TOKEN_MARK, svg)
        self.assertNotIn('class="mk">F<', svg)

    def test_the_colour_is_NOT_what_tells_them_apart(self):
        """Deliberate, and the reason the shape exists.

        `present.STATUS_STYLE` argues the shared `--need` fill in a comment, `NodeStatus.badge`
        argues it again in Java, and `status_legend` groups the two so the key reads as one red
        row. A ninth ink would have to defeat all three -- and it would leave a reader who
        cannot discriminate red from red with nothing at all, which is what the shape fixes.
        """
        boxes = self._boxes(graphview.render_diagram(self._plan())[0])
        fill = re.compile(r'fill="(var\(--\w+\))"')
        self.assertEqual(fill.search(boxes["Dungeon Drop"]).group(1),
                         fill.search(boxes["Armorer Callstone"]).group(1))

    def test_the_shape_does_not_collide_with_the_machine_outline(self):
        """The diagram's other non-colour channel. A blocked token has to show both."""
        svg = graphview.render_diagram(
            token(machine="Altar", machine_state="buildable"))[0]
        self.assertIn('rx="%.1f"' % graphview.TOKEN_RX, svg)
        self.assertIn('stroke-dasharray="4 2.5"', svg)

    def test_the_title_says_what_kind_of_placeholder_it_is(self):
        """The box carries no text but a label and a quantity, so the word goes in the title.

        Both halves: the badge word the tree row shows, and the kind's whole sentence, which
        is the fact a reader who mistook it for an item actually needs.
        """
        svg = graphview.render_diagram(self._plan())[0]
        title = [t for t in re.findall(r"<title>([^<]*)</title>", svg)
                 if "Dungeon Drop" in t]
        self.assertEqual(len(title), 1, svg)
        self.assertIn(tokens.KIND_BADGE[tokens.LOOT], title[0])
        self.assertIn(tokens.KIND_LABEL[tokens.LOOT], title[0])

    def test_the_word_comes_from_present_and_not_from_here(self):
        """Every kind, and through the same call the tree row makes, so the two cannot drift.

        A GATE badged "go get" would send someone hunting for an item that unlocks by playing
        the story, which is the whole reason `status_badge` refines the word per kind.
        """
        for kind in tokens.KINDS:
            svg = graphview.render_diagram(token(kind=kind))[0]
            word, _cls = present.status_badge(STATUS_TOKEN, kind)
            self.assertIn(word, svg, kind)
            self.assertIn(tokens.KIND_LABEL[kind], svg, kind)

    def test_the_legend_keys_the_shape(self):
        """An encoding the reader was never told about is as good as no encoding.

        The design note on #174 argued against a fourth legend row because the legend keys
        FILLS. That holds against a new colour; the dashed-outline row beside this one is
        already a non-fill entry.
        """
        _svg, legend = graphview.render_diagram(self._plan())
        self.assertIn("not an item", legend)
        self.assertIn('class="sw tok"', legend)

    def test_a_plan_with_no_placeholder_has_no_shape_row_in_its_key(self):
        _svg, legend = graphview.render_diagram(plan())
        self.assertNotIn("not an item", legend)
        self.assertNotIn("sw tok", legend)

    def test_the_shape_swatch_has_no_fill_of_its_own(self):
        # A background there would read as a ninth fill colour, exactly as it would on the
        # outline swatch beside it.
        self.assertIn(".legend .sw.tok{background:transparent", graphview.DIAGRAM_CSS)

    def test_hover_no_longer_highlights_a_box_that_cannot_be_clicked(self):
        self.assertIn(".diagram .nd a:hover", graphview.DIAGRAM_CSS)
        self.assertNotIn(".diagram .nd:hover", graphview.DIAGRAM_CSS)

    def test_layout_carries_the_two_fields_it_used_to_drop(self):
        """`layout`'s record is a fixed field list, and it copied neither of the refinements
        `present.status_badge` needs. Same defect `machine_state` had. #174, #136."""
        nodes, _l, _r = graphview.layout(self._plan())
        by_label = {n["full"]: n for n in nodes}
        self.assertEqual(by_label["Dungeon Drop"]["token_kind"], tokens.LOOT)
        self.assertFalse(by_label["Dungeon Drop"]["unsourced"])
        nodes, _l, _r = graphview.layout(node("mod:x", unsourced=True))
        self.assertTrue(nodes[0]["unsourced"])
        # Absent rather than missing, as with `machine_state`: every record has both keys.
        self.assertEqual(nodes[0]["token_kind"], "")

    def test_both_orientations_agree_about_all_of_it(self):
        svgs, _legend = graphview.render_diagrams(self._plan())
        for orientation, svg in svgs.items():
            boxes = self._boxes(svg)
            self.assertNotIn("<a href", boxes["Dungeon Drop"], orientation)
            self.assertIn('rx="%.1f"' % graphview.TOKEN_RX, boxes["Dungeon Drop"],
                          orientation)


class OrientationTest(unittest.TestCase):
    """The same plan drawn left-to-right and top-to-bottom. #35.

    Jake: *"the tree should be able to go left to right OR top to bottom."*
    """

    @staticmethod
    def _tree():
        return node("mod:root", label="Root", children=[
            node("mod:a", status="raw", need=2, label="A"),
            node("mod:b", status="have", need=3, label="B", children=[
                node("mod:c", status="raw", need=4, label="C")]),
        ])

    def _svg(self, orientation):
        return graphview.render_diagram(self._tree(), orientation=orientation)[0]

    def test_both_orientations_draw_the_same_nodes(self):
        """One `layout`, two coordinate functions.

        A second layout pass could drop or reorder a node, and then the single legend
        beside the two diagrams would be true of only one of them.
        """
        for orientation in graphview.ORIENTATIONS:
            svg = self._svg(orientation)
            self.assertEqual(svg.count("<g class=\"nd\">"), 4, orientation)
            for label in ("Root", "A", "B", "C"):
                self.assertIn(">%s<" % label, svg, orientation)

    def test_the_legend_is_the_same_for_both(self):
        # It is taken from the LR call and not recomputed, so this is the guard on that.
        self.assertEqual(graphview.render_diagram(self._tree(), orientation=graphview.LR)[1],
                         graphview.render_diagram(self._tree(), orientation=graphview.TD)[1])

    def test_the_axes_really_do_swap(self):
        """Depth runs across in one and down in the other. The whole feature.

        Compared on the ASPECT of the viewBox rather than on coordinates: this tree is
        3 deep and 2 wide, so left-to-right must come out wider than tall relative to
        top-to-bottom, whatever the spacing constants become.
        """
        def box(svg):
            m = re.search(r'viewBox="0 0 (\d+) (\d+)"', svg)
            return int(m.group(1)), int(m.group(2))
        lr_w, lr_h = box(self._svg(graphview.LR))
        td_w, td_h = box(self._svg(graphview.TD))
        self.assertGreater(lr_w / lr_h, td_w / td_h)

    def test_a_box_is_the_same_size_in_both(self):
        """Not a true transpose, and this is why.

        `MAX_LABEL` is 20 characters against BOX_W. Swapping the axes literally would give
        each box ROW = 30px of width and truncate every label to two characters.
        """
        for orientation in graphview.ORIENTATIONS:
            svg = self._svg(orientation)
            self.assertIn('width="%d" height="%d"' % (graphview.BOX_W, graphview.BOX_H),
                          svg, orientation)

    def test_each_svg_says_which_way_it_runs(self):
        # The CSS picks between them on this attribute, and a screen reader gets the words.
        for orientation in graphview.ORIENTATIONS:
            svg = self._svg(orientation)
            self.assertIn('data-dir="%s"' % orientation, svg)
            self.assertIn(graphview.ORIENTATION_LABEL[orientation], svg)

    def test_the_words_are_defined_for_every_orientation(self):
        # Same completeness contract as present.STATUS_LABEL: a new orientation must break
        # a test rather than render an empty caption.
        self.assertEqual(set(graphview.ORIENTATION_LABEL), set(graphview.ORIENTATIONS))

    def test_an_unknown_orientation_is_refused(self):
        # Rather than silently drawing the default, which would make a typo in a caller
        # look like the toggle not working.
        with self.assertRaises(ValueError):
            graphview.render_diagram(self._tree(), orientation="sideways")

    def test_a_tree_with_nothing_laid_out_still_answers_in_both(self):
        # `max_nodes=0` is the only way `layout` returns nothing, and both orientations
        # have to survive it: `_geometry` calls `max()` over the node list.
        for orientation in graphview.ORIENTATIONS:
            svg, legend = graphview.render_diagram(self._tree(), max_nodes=0,
                                                   orientation=orientation)
            self.assertIn("Nothing to draw", svg)
            self.assertEqual(legend, "")


class LabelBudgetTest(unittest.TestCase):
    """A label must not run under the quantity beside it, and must not stop short either.

    A flat `MAX_LABEL = 20` was wrong in both directions. Measured in chromium on a real
    Borax plan: `Sodium Fluoride Sol...` overlapped `85,248 mB` by 6.8px, while
    `Boron Ore` next to `64` left 116px of box unused.
    """

    def test_a_long_quantity_buys_a_shorter_label(self):
        self.assertLess(graphview._label_limit("768,000 mB"),
                        graphview._label_limit("64"))

    def test_the_pair_always_fits_inside_the_box(self):
        # The property, over every quantity shape the renderer can produce.
        for qty in ("1", "64", "128", "1,024", "18,432 mB", "85,248 mB", "768,000 mB",
                    "60,466,176", "1,000,000,000 mB"):
            limit = graphview._label_limit(qty)
            used = (graphview.LABEL_X + limit * graphview.LABEL_PX
                    + len(qty) * graphview.QTY_PX + graphview.QTY_RIGHT_PAD)
            self.assertLessEqual(used, graphview.BOX_W,
                                 "%r: label and quantity overflow the box" % qty)

    def test_an_absurd_quantity_still_leaves_a_readable_label(self):
        # Better a stub than nothing: a box with no label at all is unidentifiable, and
        # the full name is in the hover title either way.
        self.assertGreaterEqual(graphview._label_limit("9" * 60), 4)

    def test_the_rendered_label_respects_the_budget(self):
        """End to end through `render_diagram`, not just the arithmetic.

        The truncation moved out of `layout` and into the box template when it started
        depending on the quantity, so the wiring is the part that can silently revert.
        """
        tree = node("mod:thing", kind="fluid", need=768000,
                    label="Sodium Fluoride Solution Concentrate")
        svg = graphview.render_diagram(tree)[0]
        shown = re.search(r'class="lb"[^>]*>([^<]*)<', svg).group(1)
        self.assertLessEqual(len(shown), graphview._label_limit("768,000 mB"))
        self.assertTrue(shown.endswith("…"), shown)

    def test_a_short_quantity_gets_more_than_the_old_flat_cap(self):
        # The other half: the fix is not "truncate harder".
        tree = node("mod:thing", need=64, label="Crushed Villiaumite Ore Chunk")
        svg = graphview.render_diagram(tree)[0]
        shown = re.search(r'class="lb"[^>]*>([^<]*)<', svg).group(1)
        self.assertGreater(len(shown), graphview.MAX_LABEL)

    def test_a_label_that_fits_is_left_alone(self):
        svg = graphview.render_diagram(node("mod:thing", need=1, label="Granite"))[0]
        self.assertIn(">Granite<", svg)


if __name__ == "__main__":
    unittest.main()


class CanvasFitsItsContentTest(unittest.TestCase):
    """The viewBox has to contain every box it draws, in both orientations.

    The top-down width used the row COUNT where left-to-right used the max depth INDEX, so
    top-down carried a whole empty 238px column; and the "+ more not drawn" note hangs
    outside its box, so on the deepest column it was clipped away entirely -- which is
    exactly where a cut node is.
    """

    @staticmethod
    def _wide():
        return node("mod:root", label="Root", children=[
            node("mod:a", label="A", children=[node("mod:a1", label="A1"),
                                               node("mod:a2", label="A2")]),
            node("mod:b", label="B", children=[node("mod:b1", label="B1"),
                                               node("mod:b2", label="B2")]),
        ])

    @staticmethod
    def _extent(svg):
        box = re.search(r'viewBox="0 0 (\d+) (\d+)"', svg)
        rects = [(float(x), float(y)) for x, y in
                 re.findall(r'<rect x="([\d.]+)" y="([\d.]+)" width="226"', svg)]
        texts = [(float(x), float(y)) for x, y in
                 re.findall(r'<text x="([\d.]+)" y="([\d.]+)" class="cut"', svg)]
        return int(box.group(1)), int(box.group(2)), rects, texts

    def test_every_box_is_inside_the_canvas_in_both_orientations(self):
        for orientation in graphview.ORIENTATIONS:
            svg = graphview.render_diagram(self._wide(), orientation=orientation)[0]
            w, h, rects, _cut = self._extent(svg)
            self.assertTrue(rects, orientation)
            right = max(x for x, _y in rects) + graphview.BOX_W
            bottom = max(y for _x, y in rects) + graphview.BOX_H
            self.assertLessEqual(right, w, orientation)
            self.assertLessEqual(bottom, h, orientation)

    def test_the_canvas_is_not_padded_by_a_whole_empty_column(self):
        # The bug: TD width was PAD*2 + rows*TD_COL + BOX_W, where the last box starts at
        # index rows - 1. Allow the padding, not a column.
        for orientation in graphview.ORIENTATIONS:
            svg = graphview.render_diagram(self._wide(), orientation=orientation)[0]
            w, h, rects, _cut = self._extent(svg)
            slack = w - (max(x for x, _y in rects) + graphview.BOX_W)
            self.assertLess(slack, graphview.TD_COL,
                            "%s wastes %dpx, about a whole column" % (orientation, slack))
            self.assertGreaterEqual(slack, 0, orientation)

    def test_the_cut_note_is_inside_the_canvas_in_both_orientations(self):
        for orientation in graphview.ORIENTATIONS:
            svg = graphview.render_diagram(self._wide(), max_nodes=3,
                                           orientation=orientation)[0]
            w, _h, _rects, cut = self._extent(svg)
            self.assertTrue(cut,
                            "%s: nothing was cut, so this proves nothing" % orientation)
            for x, _y in cut:
                self.assertLessEqual(x + graphview.CUT_NOTE_W, w,
                                     "%s clips the cut note" % orientation)

    def test_the_cut_allowance_is_not_reserved_when_nothing_is_cut(self):
        # Otherwise every untruncated diagram carries 96px of dead canvas.
        full = graphview.render_diagram(self._wide())[0]
        trimmed = graphview.render_diagram(self._wide(), max_nodes=3)[0]
        self.assertLess(int(re.search(r'viewBox="0 0 (\d+)', full).group(1)),
                        int(re.search(r'viewBox="0 0 (\d+)', trimmed).group(1)))


class OneLayoutPassTest(unittest.TestCase):
    """`render_html` needs both orientations and must lay the tree out ONCE.

    It called `render_diagram` twice, so `layout` and `_render_legend` both ran twice, while
    three comments promised a single pass. The equality of the two node sets rested on
    `layout` being deterministic rather than on it being called once.
    """

    @staticmethod
    def _tree():
        return node("mod:root", label="Root", children=[
            node("mod:a", status="raw", label="A"),
            node("mod:b", status="have", label="B", children=[node("mod:c", label="C")]),
        ])

    @staticmethod
    def _count_layouts(fn):
        calls = []
        real = graphview.layout
        graphview.layout = lambda *a, **k: (calls.append(1), real(*a, **k))[1]
        try:
            fn()
        finally:
            graphview.layout = real
        return len(calls)

    def test_both_svgs_come_out_of_one_layout(self):
        svgs = {}
        n = self._count_layouts(
            lambda: svgs.update(graphview.render_diagrams(self._tree())[0]))
        self.assertEqual(n, 1, "layout ran %d times" % n)
        self.assertEqual(set(svgs), set(graphview.ORIENTATIONS))

    def test_the_PAGE_lays_the_tree_out_once(self):
        """Through `render_html`, not through the helper.

        Testing `render_diagrams` alone pins nothing: `render_html` called
        `render_diagram` twice and this class stayed green.
        """
        from recipegraph import render

        result = {
            "target": "mod:root", "target_name": "Root", "qty": 1, "nodes": 4,
            "tree": self._tree(), "shopping_list": [], "used_from_stock": [],
            "from_sources": [], "machines_to_build": [], "truncated": False,
        }
        n = self._count_layouts(lambda: render.render_html(result))
        self.assertEqual(n, 1, "the page laid the tree out %d times" % n)

    def test_the_single_orientation_wrapper_still_works(self):
        svg, legend = graphview.render_diagram(self._tree(), orientation=graphview.TD)
        self.assertIn('data-dir="td"', svg)
        self.assertEqual(legend, graphview.render_diagrams(self._tree())[1])

    def test_an_unknown_orientation_is_still_refused(self):
        with self.assertRaises(ValueError):
            graphview.render_diagram(self._tree(), orientation="sideways")

    def test_nothing_to_draw_answers_for_every_orientation_asked_for(self):
        svgs, legend = graphview.render_diagrams(self._tree(), max_nodes=0)
        self.assertEqual(set(svgs), set(graphview.ORIENTATIONS))
        for svg in svgs.values():
            self.assertIn("Nothing to draw", svg)
        self.assertEqual(legend, "")
