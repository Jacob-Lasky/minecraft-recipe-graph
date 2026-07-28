"""The web UI, driven over a real socket.

The handler had no tests at all: every page was only ever verified by eye. That left the
routing table, the `/suggest` JSON contract the typeahead depends on, and the redirect
sanitisation on the machine-toggle form all uncovered, none of which a browser screenshot
would catch failing on an edge case.

A real server on a real port rather than a mocked handler, because the things worth testing
here ARE the HTTP behaviours: status codes, content types, and redirect targets.
"""

import html as html_mod
import inspect
import json
import os
import re
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import fixtures  # noqa: E402
from recipegraph import (generators, graphview, index, machines, pins,  # noqa: E402
                         render, server)
from recipegraph.htmlutil import esc as _esc_  # noqa: E402
from recipegraph.model import Graph, Ingredient, Recipe  # noqa: E402
from recipegraph.sources import dump_meta  # noqa: E402
from recipegraph.solve import Solver  # noqa: E402


def build_graph():
    g = Graph()
    g.names = {
        "mod:widget": "Widget", "mod:part": "Part",
        "nuclearcraft:water_source": "Infinite Water Source",
        "mod:press": "Press",
    }
    g.add(Recipe("make", "t", [("mod:widget", 2)],
                 [Ingredient(["mod:part"], 3), Ingredient(["fluid:water"], 500, "fluid")],
                 category="mod.press", machine="Press"))
    # Two ways to make one thing, so there is something to CHOOSE between. Both in
    # `mod.press` on purpose: a new category would move the machines page's counts and
    # make an unrelated test's failure look like this one's.
    g.names["mod:gizmo"] = "Gizmo"
    g.add(Recipe("gizmo_cheap", "t", [("mod:gizmo", 1)],
                 [Ingredient(["mod:part"], 2)], category="mod.press", machine="Press"))
    g.add(Recipe("gizmo_dear", "t", [("mod:gizmo", 1)],
                 [Ingredient(["mod:part"], 5)], category="mod.press", machine="Press"))
    return g


class LiveServerCase(unittest.TestCase):
    """A real server on a real port, holding whatever `graph()` returns.

    Extracted so a second suite can run against a DIFFERENT graph without copying the
    setup. It has to be a different graph rather than an addition to `build_graph`: the
    deep one below is 8 levels of 3-way branching, and dropping 24 recipes into the shared
    fixture would move the machines page's counts and make an unrelated test's failure
    look like this one's.
    """

    HAVE = {"items": {"mod:part": 40},
            "placed": {"nuclearcraft:water_source": 1}}

    @staticmethod
    def graph():
        return build_graph()

    @classmethod
    def setUpClass(cls):
        if cls is LiveServerCase:
            raise unittest.SkipTest("base class")
        cls.dir = tempfile.mkdtemp()
        graph_path = os.path.join(cls.dir, "graph.json")
        cls.graph().save(graph_path)
        have_path = os.path.join(cls.dir, "have.json")
        with open(have_path, "w") as fh:
            json.dump(cls.HAVE, fh)
        cls.machines_path = os.path.join(cls.dir, "machines.json")
        cls.httpd, cls.state = server.serve(
            graph_path, have_path, cls.machines_path, host="127.0.0.1", port=0,
            sources_path=os.path.join(cls.dir, "sources.json"))
        cls.port = cls.httpd.server_address[1]
        cls.thread = threading.Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()
        cls.thread.join(timeout=5)

    def get(self, path):
        url = "http://127.0.0.1:%d%s" % (self.port, path)
        try:
            with urllib.request.urlopen(url) as resp:
                return resp.status, resp.headers.get("Content-Type", ""), resp.read().decode()
        except urllib.error.HTTPError as err:
            with err:      # drain and close, or the suite warns about the open response
                return err.status, err.headers.get("Content-Type", ""), err.read().decode()


class ServerTest(LiveServerCase):

    # ---- routing ----

    def test_every_nav_destination_is_routed(self):
        # A nav link to a 404 is the easiest way to ship a broken page.
        for href, _label, _icon in server.NAV_ITEMS:
            status, ctype, _body = self.get(href)
            self.assertEqual(status, 200, href)
            self.assertIn("text/html", ctype, href)

    def test_every_machines_cell_carries_its_card_class(self):
        """The markup half of the phone-layout contract; the CSS half is in
        MobileLayoutTest. Rendered, not asserted against a constant, so a change to the
        row template cannot pass by updating the constant alongside it."""
        self._assert_cell_classes("/machines",
                                  ("c-state", "c-name", "c-recipes", "c-why", "c-acts"))

    def test_every_sources_cell_carries_its_card_class(self):
        self._assert_cell_classes("/sources", ("c-name", "c-why", "c-acts"))

    def _assert_cell_classes(self, path, classes):
        body = self.get(path)[2]
        # Match the token inside a class attribute, so `c-name` cannot be satisfied by the
        # word appearing in a comment or an id somewhere else on the page. Some cells carry
        # a second class (`n c-recipes`, `hint2 c-why`), hence the token scan.
        present = set()
        for attr in re.findall(r"""class=['"]([^'"]+)['"]""", body):
            present.update(attr.split())
        for cls in classes:
            self.assertIn(cls, present,
                          "%s is styled by the card layout but %s does not emit it"
                          % (cls, path))

    def test_unknown_path_is_a_404(self):
        self.assertEqual(self.get("/nope")[0], 404)

    def test_healthz_and_favicon(self):
        self.assertEqual(self.get("/healthz")[:2], (200, "text/plain; charset=utf-8"))
        status, ctype, body = self.get("/favicon.ico")
        self.assertEqual(status, 200)
        self.assertEqual(ctype, "image/svg+xml")
        self.assertIn("<svg", body)

    # ---- the typeahead's data contract ----

    def test_suggest_returns_the_fields_the_typeahead_reads(self):
        status, ctype, body = self.get("/suggest?q=widget")
        self.assertEqual(status, 200)
        self.assertIn("application/json", ctype)
        rows = json.loads(body)["results"]
        self.assertTrue(rows)
        # These exact names are read by HOME_JS; renaming one silently empties the row.
        for field in ("key", "name", "kind", "label", "stock", "makes", "uses"):
            self.assertIn(field, rows[0], field)

    def test_suggest_with_an_empty_query_is_an_empty_list_not_an_error(self):
        status, _c, body = self.get("/suggest?q=")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["results"], [])

    def test_suggest_reports_stock_from_the_have_file(self):
        rows = json.loads(self.get("/suggest?q=part")[2])["results"]
        self.assertEqual(next(r for r in rows if r["key"] == "mod:part")["stock"], 40)

    # ---- plan ----

    def test_a_plan_renders_for_a_real_item(self):
        status, _c, body = self.get("/plan?item=mod%3Awidget&qty=4")
        self.assertEqual(status, 200)
        self.assertIn("Widget", body)
        self.assertIn("Crafting plan", body)

    def test_an_unknown_item_is_a_404_not_an_empty_plan(self):
        # It used to render a confident empty plan titled with the raw id, which is
        # indistinguishable from "this item needs nothing".
        status, _c, body = self.get("/plan?item=mod%3Adoes_not_exist&qty=1")
        self.assertEqual(status, 404)
        self.assertIn("No item with that id", body)

    def test_the_unknown_item_page_prefills_the_name_not_the_meta(self):
        """It prefilled with `key.split(":")[-1]`, so a 404 on `mod:thing:3` offered to
        search for "3" -- the one page whose whole job is "search for it by name"."""
        status, _c, body = self.get("/plan?item=mod%3Adoes_not_exist%3A3&qty=1")
        self.assertEqual(status, 404)
        self.assertIn("value='does_not_exist'", body)
        self.assertNotIn("value='3'", body)

    def test_a_missing_item_parameter_does_not_500(self):
        self.assertEqual(self.get("/plan")[0], 400)

    def test_a_non_numeric_quantity_is_a_bad_request_not_a_crash(self):
        self.assertEqual(self.get("/plan?item=mod%3Awidget&qty=lots")[0], 400)

    def test_a_plan_carries_the_real_nav_not_a_bare_back_link(self):
        """#13: the plan was the one page you could not leave.

        Every other view renders `_nav`; the fragment shell hardcoded a single
        `back to search` link, so Machines, Sources and Coverage were unreachable.
        """
        body = self.get("/plan?item=mod%3Awidget&qty=4")[2]
        for href, label, _icon in server.NAV_ITEMS:
            self.assertIn(label, body, "plan page is missing the %s tab" % label)
            # EVERY tab, Search included. This used to exempt "/" because the Search tab
            # was rendered as an inert span here, which is the bug #53 reported.
            self.assertIn("href='%s'" % href, body, href)
        self.assertIn("&rsaquo;", body, "and a breadcrumb should name the item")

    def test_explore_carries_the_nav_too(self):
        body = self.get("/explore?q=widget")[2]
        self.assertIn("href='/machines'", body)

    # ---- nav: which tab is current, and whether it is clickable (#53) ----

    def test_the_section_tab_is_a_working_link_on_a_page_below_it(self):
        """#53: `/plan` and `/explore` passed the SECTION as the active path, so Search
        rendered as an inert span on a page that was not Search. The one tab a reader wants
        after finishing with a plan was the one turned off, and `aria-current='page'` told a
        screen reader it was already there, which is not recoverable by looking around."""
        for path in ("/plan?item=mod%3Awidget&qty=1", "/explore?q=widget",
                     # the unknown-item 404 lives under /plan too
                     "/plan?item=mod%3Anot_a_thing&qty=1"):
            body = self.get(path)[2]
            self.assertIn("<a class='cur' aria-current='true' href='/'", body, path)
            self.assertNotIn("aria-current='page'", body,
                             "%s is not the Search page and must not claim to be" % path)

    def test_machine_detail_marks_its_section_without_claiming_to_be_it(self):
        for path in ("/machine?uid=mod.press", "/machine?uid=nope.nope"):
            body = self.get(path)[2]
            self.assertIn("<a class='cur' aria-current='true' href='/machines'", body, path)
            self.assertNotIn("aria-current='page'", body, path)

    def test_the_tab_for_the_page_you_are_actually_on_stays_inert(self):
        """The other half: a real "you are here" is still a span with aria-current='page',
        because turning every tab into a link loses the distinction entirely."""
        for href, _label, _icon in server.NAV_ITEMS:
            body = self.get(href)[2]
            self.assertIn("<span class='cur' aria-current='page'>", body, href)
            self.assertNotIn("aria-current='true'", body, href)

    def test_the_active_tab_is_styled_by_class_not_by_element(self):
        """The CSS said `nav.top span.cur`, so the moment the current section became an <a>
        on a child page it would have lost the highlight and looked like any other tab."""
        body = self.get("/")[2]
        self.assertIn("nav.top .cur{", body)
        self.assertNotIn("nav.top span.cur", body)
        # and the one active tab that IS clickable must still answer to hover, which needs
        # its own rule to beat the equally-specific `.cur` colour written after `a:hover`
        self.assertIn("nav.top a.cur:hover{", body)

    def test_every_nav_path_the_server_passes_is_one_it_knows(self):
        """`_nav` highlights nothing at all for a path in neither table, silently. This
        scans the real call sites rather than a list kept beside them."""
        known = {h for h, _l, _i in server.NAV_ITEMS} | set(server.NAV_PARENT)
        passed = set(re.findall(r"""_nav\((["'])([^"']*)\1""",
                                inspect.getsource(server)))
        self.assertTrue(passed, "the call-site scan found nothing, so it proves nothing")
        for _quote, path in passed:
            if path:      # "" is the no-section shell, which is deliberate
                self.assertIn(path, known,
                              "_nav(%r) highlights no tab: add it to NAV_PARENT" % path)

    def test_the_breadcrumb_calls_the_root_tab_what_the_tab_calls_itself(self):
        body = self.get("/plan?item=mod%3Awidget&qty=1")[2]
        self.assertIn("<div class='crumb'><a href='/'>%s</a>" % server.NAV_LABELS["/"],
                      body)

    def test_nav_parent_points_at_real_tabs_and_never_at_itself(self):
        hrefs = {h for h, _l, _i in server.NAV_ITEMS}
        for child, parent in server.NAV_PARENT.items():
            self.assertIn(parent, hrefs, "%s is parented to a tab that does not exist" % child)
            self.assertNotIn(child, hrefs,
                             "%s is a tab, so it is its own page, not a child" % child)

    def test_a_plan_warns_when_its_data_went_stale(self):
        """The other half of the missing nav: a plan could not raise the banner either."""
        os.utime(self.state.graph_path, None)
        try:
            body = self.get("/plan?item=mod%3Awidget&qty=1")[2]
            self.assertIn("changed on disk", body)
        finally:
            self.state.load_all()

    def test_reloading_from_a_child_page_returns_to_the_section_not_the_child(self):
        """`_nav` takes the page's real path now, and the reload form's return target is
        the one place that must still be the SECTION: `/plan` and `/machine` stripped of
        their query are a 400 and a 404."""
        os.utime(self.state.graph_path, None)
        try:
            for path, back in (("/plan?item=mod%3Awidget&qty=1", "/"),
                               ("/explore?q=widget", "/"),
                               ("/machine?uid=mod.press", "/machines")):
                body = self.get(path)[2]
                self.assertIn("name='back' value='%s'" % back, body, path)
        finally:
            self.state.load_all()

    def test_every_page_kind_shows_that_a_plan_is_working(self):
        """#18: a slow plan blocks with the OLD page on screen and no sign of life.

        Asserted on both shells because they are separate wrappers, and the fragment one
        is exactly the page that links to further plans.
        """
        for path in ("/", "/machines", "/sources", "/plan?item=mod%3Awidget&qty=1"):
            body = self.get(path)[2]
            self.assertIn("a[href^=\"/plan?\"]", body, path)
            self.assertIn("el.className='working'", body, path)
            self.assertIn(".working{", body, "%s: the scrim has no styling" % path)

    # ---- which build drew this page (#38) ----

    # Every surface the handler can return, INCLUDING the error shells. Written out rather
    # than derived from NAV_ITEMS because the 400 and 404 pages are exactly the ones a
    # page-by-page reviewer forgets, and they are drawn by the same stale modules.
    ALL_SURFACES = (
        "/",
        "/plan?item=mod%3Awidget&qty=1",
        "/plan?item=mod%3Anot_a_real_item&qty=1",   # the unknown-item 404
        "/explore?q=widget",
        "/machines",
        "/machine?uid=mod.press",
        "/machine?uid=nope.nope",                   # the no-such-category 404
        "/recipes?item=mod%3Agizmo",
        "/recipes?item=",                           # the no-item-given 400
        "/sources",
        "/stats",
        "/nope",                                    # the routing 404
        # The 400 the ValueError/KeyError handler emits. A 13th surface, and it was missing
        # from this tuple, which is why nothing noticed it had no restart strip either.
        "/plan?item=mod%3Awidget&qty=not-a-number",
    )

    def test_every_surface_names_the_build_that_drew_it(self):
        """The property that makes the footer worth anything.

        A version line on eight pages out of twelve is worse than none: its absence on the
        ninth reads as "this page is fine". `_shell` is the one wrapper, so this passes by
        construction today and fails the moment someone hand-rolls a document.
        """
        for path in self.ALL_SURFACES:
            body = self.get(path)[2]
            self.assertIn("<footer class='ver'>", body, path)
            self.assertIn(server.version_mod.BUILD.version, body, path)

    def test_every_surface_names_the_dump_in_its_FOOTER(self):
        """Scoped to the footer element, because the word alone proves nothing.

        Asserting "dump" appears anywhere in the body passed on `/machines` from the page's
        own prose, so dropping `state` from just that one call site would have kept passing.
        Every surface passes state now, including the error shells, so every one of them
        must carry the sentence.
        """
        want = _esc_(dump_meta.describe(dump_meta.of_graph(self.state.graph)))
        for path in self.ALL_SURFACES:
            body = self.get(path)[2]
            footer = re.search(r"<footer class='ver'>.*?</footer>", body, re.S)
            self.assertIsNotNone(footer, path)
            self.assertIn(want, footer.group(0), path)

    def test_the_footer_does_not_claim_a_dump_the_graph_never_had(self):
        # The fixture graph is built in memory with no dump at all, so the honest line is
        # "unknown", not a fabricated schema.
        self.assertIn("provenance: unknown", self.get("/stats")[2])

    def test_stale_code_is_reported_on_every_surface_and_offers_no_button(self):
        """Re-reading a file fixes stale data; nothing re-imports a module in place.

        Checked on the fragment shell too (`/plan`), which is the page that carried
        neither a nav nor a stale-data warning until #53 and is the easiest to miss.
        """
        real = server.version_mod.BUILD.stamp
        server.version_mod.BUILD.stamp = (0.0, 0, 0)
        try:
            # ALL of them, not a hand-picked three. The strip lived in `_nav`, which the
            # routing 404 and the 400 shell never call, and a three-path loop that happened
            # to pick three nav-rendering pages is what let that ship.
            for path in self.ALL_SURFACES:
                body = self.get(path)[2]
                self.assertIn("Restart the server", body, path)
                self.assertIn("<div class='stale'>", body, path)
        finally:
            server.version_mod.BUILD.stamp = real
        # And it goes away again, so the banner cannot be a permanent fixture nobody reads.
        self.assertNotIn("Restart the server", self.get("/")[2])

    def test_the_two_stale_banners_are_distinguishable(self):
        """Both are up at once when a rebuild and a code change land together.

        The data one is a form with a button; the code one is not, and if they rendered
        identically the reader would click the button and believe both were handled.
        """
        real_stamp = server.version_mod.BUILD.stamp
        server.version_mod.BUILD.stamp = (0.0, 0, 0)
        stamps = dict(self.state.stamps)
        self.state.stamps = {k: None for k in stamps}
        try:
            body = self.get("/")[2]
            self.assertIn("<form method='post' action='/reload' class='stale'>", body)
            self.assertIn("<div class='stale'>", body)
            self.assertIn("Reload now", body)
            self.assertIn("Restart the server", body)
            # The restart note must come FIRST: a restart re-reads the data too, so acting
            # on the reload button leaves you back here.
            self.assertLess(body.index("Restart the server"), body.index("Reload now"))
        finally:
            server.version_mod.BUILD.stamp = real_stamp
            self.state.stamps = stamps

    # ---- going deeper from the page (#25) ----

    def test_the_cap_is_clamped_into_a_range_the_page_will_serve(self):
        """It arrives on an editable URL and `work_budget` derives from it.

        `work_budget` IS the solver's termination guarantee, so an unbounded value is a
        way to hang the server from the address bar. Below the default is refused too:
        nothing offers it, so a small number can only be a typo.
        """
        for raw, want in (("", server.DEFAULT_MAX_NODES),
                          ("banana", server.DEFAULT_MAX_NODES),
                          ("1", server.DEFAULT_MAX_NODES),
                          ("-5", server.DEFAULT_MAX_NODES),
                          ("0", server.DEFAULT_MAX_NODES),
                          (None, server.DEFAULT_MAX_NODES),
                          ("99999999999", server.MAX_NODES_CEILING),
                          (str(server.DEFAULT_MAX_NODES * 2),
                           server.DEFAULT_MAX_NODES * 2)):
            self.assertEqual(server._node_cap(raw), want, repr(raw))

    def test_the_control_doubles_until_the_ceiling_then_stops_offering(self):
        base = server.DEFAULT_MAX_NODES
        url, nxt = server._deeper("mod:widget", 3, base)
        self.assertEqual(nxt, base * 2)
        self.assertIn("max_nodes=%d" % (base * 2), html_mod.unescape(url))
        self.assertIn("qty=3", html_mod.unescape(url))
        # Never past the ceiling, even from a cap that would double straight over it.
        _url, nxt = server._deeper("mod:widget", 1, server.MAX_NODES_CEILING // 2 + 1)
        self.assertEqual(nxt, server.MAX_NODES_CEILING)
        # And at the ceiling there is no link at all: a control that cannot raise the cap
        # any further reads as broken.
        url, nxt = server._deeper("mod:widget", 1, server.MAX_NODES_CEILING)
        self.assertEqual((url, nxt), ("", server.MAX_NODES_CEILING))

    def test_the_cap_reaches_the_solver(self):
        # The whole point. A parameter the handler accepts and drops would present as the
        # button doing nothing, which is worse than the flag it replaced.
        body = self.get("/plan?item=mod%%3Awidget&qty=1&max_nodes=%d"
                        % (server.DEFAULT_MAX_NODES * 2))[2]
        self.assertEqual(self.state.solver(server.DEFAULT_MAX_NODES * 2).max_nodes,
                         server.DEFAULT_MAX_NODES * 2)
        self.assertEqual(self.state.solver().max_nodes, server.DEFAULT_MAX_NODES)
        self.assertEqual(200, self.get("/plan?item=mod%3Awidget&qty=1")[0])
        self.assertIn("Widget", body)

    def test_a_deep_plan_carries_its_cap_into_the_recipe_chooser_and_back(self):
        """The return path, which is where a raised cap is easiest to lose.

        Go deeper, pin a recipe, and you have to land back on the plan you were reading
        rather than on a shallower one that truncates again.
        """
        deep = server.DEFAULT_MAX_NODES * 2
        body = self.get("/plan?item=mod%%3Agizmo&qty=1&max_nodes=%d" % deep)[2]
        backs = re.findall(r"""/recipes\?item=[^'"]*back=([^'"&]+)""", body)
        self.assertTrue(backs, "the plan offers no chooser link to carry a cap on")
        for back in backs:
            self.assertIn("max_nodes%%3D%d" % deep, back)

    def test_a_slow_plan_does_not_freeze_every_other_page(self):
        """The solve runs OUTSIDE the state lock, holding references taken inside it.

        Measured on the reference pack, one plan can spend two minutes in the solver, and
        #25's control is an invitation to make that longer. Holding the lock across it
        meant the machines page, the sources page and every other plan waited behind it.
        """
        served = []

        def hog():
            with self.state.lock:
                served.append(self.get("/machines")[0])

        # The lock held by another thread must not stop an unrelated page from rendering.
        t = threading.Thread(target=hog)
        t.start()
        t.join(timeout=20)
        self.assertFalse(t.is_alive(), "a page blocked on the state lock")
        self.assertEqual(served, [200])

    def test_the_plan_handler_does_not_solve_under_the_lock(self):
        """Asserted on the INDENTATION, because the fixture solves instantly.

        The timing test above cannot fail on a three-recipe graph however the lock is
        held, and "the two lines are in this order" passes with the solve moved back
        inside the `with`. What actually distinguishes the two is that the solve sits one
        level shallower than the construction it follows.
        """
        lines = inspect.getsource(server.Handler.do_GET).splitlines()
        take = next(i for i, ln in enumerate(lines) if "solver = st.solver(cap)" in ln)
        solve = next(i for i, ln in enumerate(lines) if "result = solver.solve(" in ln)
        indent = lambda i: len(lines[i]) - len(lines[i].lstrip())
        self.assertGreater(solve, take)
        self.assertLess(indent(solve), indent(take),
                        "the solve is still inside the `with st.lock` block")

    def test_the_ceiling_is_a_multiple_someone_measured(self):
        """A ceiling nobody timed is a way to hang the server politely.

        `avaritia:resource:3` on the reference pack: 26s at 4,000 nodes, ~110s at 16,000,
        417s at 32,000. Cost is roughly linear in the work budget, so what the ceiling
        bounds is the MULTIPLE of a wait the reader has already sat through -- and the 64x
        this started at was over two hours for one click.
        """
        ratio = server.MAX_NODES_CEILING / server.DEFAULT_MAX_NODES
        self.assertGreater(ratio, 1, "the control must be able to do something")
        self.assertLessEqual(ratio, 4, "measured: past 4x one click runs into the hours")

    # ---- diagram orientation (#35) ----

    def test_a_plan_ships_both_orientations_and_shows_one(self):
        """No round trip, because a round trip re-solves and a plan can take two minutes.

        Both SVGs are in the DOM and CSS picks between them on `data-dir`, so the toggle
        is an attribute write.
        """
        body = self.get("/plan?item=mod%3Awidget&qty=1")[2]
        self.assertIn('data-dir="lr"', body)
        self.assertIn('data-dir="td"', body)
        self.assertIn('.diagwrap[data-dir="lr"] .diagram[data-dir="td"]', body)
        self.assertIn("Turn it top to bottom", body)

    def test_the_hidden_orientation_is_display_none_not_hidden(self):
        """An SVG with a width attribute keeps its box under the UA `[hidden]` rule.

        That is the same trap `tools/mobile-audit.js` exists to catch: the attribute is
        set, the count is right, and the thing is still on screen.
        """
        body = self.get("/plan?item=mod%3Awidget&qty=1")[2]
        flat = " ".join(body.split())
        self.assertIn('.diagwrap[data-dir="lr"] .diagram[data-dir="td"], '
                      '.diagwrap[data-dir="td"] .diagram[data-dir="lr"]{display:none}',
                      flat)
        self.assertNotIn('<svg class="diagram" data-dir="td" hidden', body)

    def test_the_orientation_words_are_injected_not_restated(self):
        """One source for the caption, the button and the aria-label.

        The JS carried its own `{lr:'left to right',td:'top to bottom'}` literal beside
        `graphview.ORIENTATION_LABEL`, which is three surfaces naming one thing and two
        places to change it.
        """
        body = self.get("/plan?item=mod%3Awidget&qty=1")[2]
        emitted = re.search(r"WORDS=(\{.*?\});", body).group(1)
        self.assertEqual(json.loads(emitted.replace("\\u003c", "<")),
                         graphview.ORIENTATION_LABEL)
        for word in graphview.ORIENTATION_LABEL.values():
            self.assertIn(word, body)
        self.assertNotIn("%%DIRS%%", body)

    def test_the_button_starts_by_offering_the_other_orientation(self):
        # Rendered in Python from ORIENTATION_LABEL[TD], so the server-rendered label and
        # the one the JS writes on the first click cannot disagree.
        body = self.get("/plan?item=mod%3Awidget&qty=1")[2]
        self.assertIn("Turn it %s" % graphview.ORIENTATION_LABEL[graphview.TD], body)

    def test_the_choice_is_remembered(self):
        # A reading preference. Having to set it again on every plan makes it useless.
        body = self.get("/plan?item=mod%3Awidget&qty=1")[2]
        self.assertIn("rg.diagdir", body)
        self.assertIn("localStorage.setItem", body)

    # ---- the machines cross-tab ships from the server (#32 follow-up) ----

    def test_the_page_ships_the_cross_tab_and_the_js_reads_it(self):
        body = self.get("/machines")[2]
        emitted = re.search(r"var MODS=(\{.*?\});", body).group(1)
        self.assertEqual(json.loads(emitted),
                         machines.mod_state_counts(self.state.machine_info))
        self.assertNotIn("%%MODS%%", body, "the placeholder was not substituted")

    def test_the_js_no_longer_counts_rows_itself(self):
        """The duty that moved. A tally in the browser is a domain fact this suite
        cannot reach, which is how #16 and #32 both reached a human first."""
        self.assertNotIn("byMod[r.dataset.mod]", self._code(server.MACHINES_JS))
        self.assertIn("Object.keys(MODS)", self._code(server.MACHINES_JS))

    @staticmethod
    def _code(text, marker="//"):
        """`text` with its line comments removed, so a source assertion tests the CODE.

        Every source-text assertion in this file has to go through here. THREE times now a
        comment saying why something is not used has satisfied a grep for it not being
        used -- the go-deeper link's wording, then `localeCompare`, then `summarise` -- and
        each time the tempting fix was to contort the prose. A comment that cannot name
        what it warns against is a worse comment, so the stripping lives here.

        `marker` because the same trap exists on both sides: `//` for the inline JS,
        `#` for the Python this file also greps.
        """
        return "\n".join(ln.split(marker)[0] for ln in text.splitlines())

    def test_the_client_never_compares_two_mod_names(self):
        """Collation is decided once, in `machines.mod_order`.

        Python's `sorted` and the browser's locale-aware comparison disagree at every one
        of the 77 mod names. The count term dominates, so it showed inside ties: filtering
        to `no route` leaves 74 of 77 mods at zero and the browser re-alphabetised all 74.
        The column sorter below still compares cell text, which is a different job.
        """
        filters = self._code(server.MACHINES_JS).split("th.sortable")[0]
        self.assertNotIn("localeCompare", filters)
        self.assertIn("dataset.order", filters)

    def test_every_option_carries_the_order_the_server_assigned(self):
        body = self.get("/machines")[2]
        order = [int(r) for r in re.findall(r"<option [^>]*data-order='(\d+)'", body)]
        self.assertTrue(order)
        self.assertEqual(order, sorted(order), "options are emitted out of order")
        self.assertEqual(order, list(range(len(order))), "positions are not dense from 0")

    def test_the_option_position_is_not_called_rank(self):
        """A table ROW's `data-rank` is its state's sort position, a different number.

        Two meanings behind one attribute name on one page is how `dataset.<x>` in the
        script stops being readable, and the script uses both.
        """
        body = self.get("/machines")[2]
        self.assertNotIn("data-rank", re.search(r"<select id=\"mmod\".*?</select>",
                                                body, re.S).group(0))
        self.assertIn("data-rank=", body, "rows still carry the state sort position")

    def test_the_option_counts_are_the_cross_tab_totals(self):
        body = self.get("/machines")[2]
        counts = machines.mod_state_counts(self.state.machine_info)
        for mod, shown in re.findall(r"<option value='([^']*)'[^>]*>[^(]*\((\d+)\)</option>",
                                     body):
            if not mod:
                continue
            self.assertEqual(int(shown), sum(counts[html_mod.unescape(mod)].values()), mod)

    def test_every_row_belongs_to_a_mod_the_cross_tab_knows(self):
        """THE producer/consumer contract, and nothing guarded it.

        `reconcile()` clears the mod selection when the cross-tab reports no matches for
        it. A row whose `data-mod` is absent from `MODS` would therefore be filterable in
        the table and invisible to the counts, so choosing that mod would clear itself and
        silently widen the result. The two are escaped differently on the way out -- the
        attribute through `esc`, the key through `script_json` -- so equality is a claim
        about the round trip, not about one function.
        """
        body = self.get("/machines")[2]
        known = set(json.loads(re.search(r"var MODS=(\{.*?\});",
                                         body).group(1).replace("\\u003c", "<")))
        seen = {html_mod.unescape(m)
                for m in re.findall(r"<tr [^>]*data-mod='([^']*)'", body)}
        self.assertTrue(seen)
        self.assertEqual(seen - known, set(),
                         "rows reference mods the cross-tab has no entry for")

    def test_the_cross_tab_totals_the_rows_that_were_rendered(self):
        # The cross-tab is built from `machine_info` and the rows are rendered from it, so
        # a filter that trusts one and a table that shows the other must agree on the
        # total. They are two walks of the same dict today; this is what says so.
        body = self.get("/machines")[2]
        tab = json.loads(re.search(r"var MODS=(\{.*?\});",
                                   body).group(1).replace("\\u003c", "<"))
        rendered = len(re.findall(r"<tr data-state='", body))
        self.assertEqual(sum(sum(v.values()) for v in tab.values()), rendered)

    def test_the_page_derives_its_state_counts_from_the_cross_tab(self):
        """Asserted on the SOURCE, because the numbers cannot tell you.

        `summarise` and `state_totals` agree on every consistent graph -- that is the point
        of pinning them in `test_machines.StateTotalsTest` -- so swapping one for the other
        changes no figure on the page and no page-level assertion can see it. What the
        change buys is one derivation instead of two, and only the source says which.
        """
        src = self._code(inspect.getsource(server.machines_page), "#")
        self.assertIn("machines_mod.state_totals(mod_counts)", src)
        self.assertNotIn("summarise", src)

    def test_the_chips_show_the_counts_the_cross_tab_holds(self):
        # Not a test of WHICH source (see above); a test that the rendered number matches
        # the table the browser will recompute against, so the figure does not jump on the
        # first click.
        body = self.get("/machines")[2]
        tab = json.loads(re.search(r"var MODS=(\{.*?\});",
                                   body).group(1).replace("\\u003c", "<"))
        totals = machines.state_totals(tab)
        for state, n in re.findall(
                r"data-state='([a-z]+)' aria-pressed='false'>[^<]*<span class='n'>(\d+)<",
                body):
            self.assertEqual(int(n), totals[state], state)

    def test_the_mods_placeholder_is_actually_in_the_template(self):
        # Mirror of the substitution test. Without this, asserting the placeholder is
        # ABSENT from the rendered page passes just as well when the line is deleted.
        self.assertIn("%%MODS%%", server.MACHINES_JS)

    def test_json_in_a_script_block_cannot_close_it(self):
        """`json.dumps` alone is not safe inside `<script>`.

        An HTML parser looks for the literal `</script` before any JS runs, so one mod
        display name containing it turns the rest of the page into markup. The mod names
        shipped here are whatever ~410 mod authors typed.
        """
        from recipegraph.htmlutil import script_json

        blob = script_json({"</script><b>x</b>": 1, "line\u2028break": 2})
        self.assertNotIn("</script", blob)
        self.assertNotIn("\u2028", blob)
        self.assertIn("\\u003c", blob)
        # And it is still the same data once parsed.
        self.assertEqual(json.loads(blob.replace("\\u003c", "<")),
                         {"</script><b>x</b>": 1, "line\u2028break": 2})

    def test_the_scrim_can_name_the_item_rather_than_the_link_text(self):
        # "Planning Go deeper" is a useless thing for the scrim to say back.
        self.assertIn("a.dataset.planLabel", self.get("/")[2])

    def test_the_infinite_source_reaches_the_rendered_plan(self):
        # End to end: a placed generator in the have file must make water free and be
        # reported on the page.
        body = self.get("/plan?item=mod%3Awidget&qty=4")[2]
        self.assertIn("Drawn from infinite sources", body)
        self.assertIn("nuclearcraft:water_source", body)

    # ---- machines ----

    def test_machine_detail_renders_and_unknown_uid_is_a_404(self):
        status, _c, body = self.get("/machine?uid=mod.press")
        self.assertEqual(status, 200)
        self.assertIn("Press", body)
        self.assertEqual(self.get("/machine?uid=nope.nope")[0], 404)

    def test_each_candidate_machine_shows_its_own_state(self):
        """#27: one category, several machines. The page used to show only the winner.

        Overrides are cleared first because a manual override short-circuits candidate
        judging, and the toggle tests in this class share the same machines.json.
        """
        with open(self.machines_path, "w") as fh:
            fh.write("{}")
        self.state.refresh_machines()
        cands = self.state.machine_info["mod.press"]["candidate_states"]
        self.assertEqual([c["key"] for c in cands], ["mod:press"])
        body = self.get("/machine?uid=mod.press")[2]
        for c in cands:
            self.assertIn(c["key"], body, c["key"])
            self.assertIn(c["why"], body, c["why"])

    def test_a_category_with_no_candidates_still_carries_the_key(self):
        for uid, info in self.state.machine_info.items():
            self.assertIn("candidate_states", info, uid)

    def test_machines_page_lists_every_category(self):
        body = self.get("/machines")[2]
        for uid in self.state.machine_info:
            self.assertIn(uid, body, uid)

    def test_the_filters_get_what_they_need_to_narrow_each_other(self):
        """#16: MACHINES_JS recounts both axes, and it reads these attributes to do it.

        The counting itself is exercised in a browser (the page has no server round-trip
        for it); this is the server-to-client contract that would silently empty the
        dropdown if a name changed.
        """
        body = self.get("/machines")[2]
        self.assertIn("data-label=", body, "option labels the client rewrites counts onto")
        self.assertIn("data-mod=", body, "the row attribute the mod tally groups by")
        self.assertIn("data-state=", body, "the row attribute the state tally groups by")
        self.assertIn("<span class='n'>", body, "the chip count the client rewrites")

    def test_the_unidentified_hint_reflects_whether_a_dump_was_read(self):
        # Telling someone who has already dumped to go and dump reads as the tool not
        # noticing what it is holding.
        self.assertIn("Run <code>/recipedump</code>", self.get("/machines")[2])
        self.state.graph.catalysts = {"mod.press": ["mod:press"]}
        try:
            self.assertIn("residue after reading JEI", self.get("/machines")[2])
        finally:
            self.state.graph.catalysts = {}

    # ---- the toggle form ----

    def post(self, fields, path="/machines"):
        data = urllib.parse.urlencode(fields).encode()
        req = urllib.request.Request("http://127.0.0.1:%d%s" % (self.port, path), data=data)

        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *_a, **_kw):
                return None

        opener = urllib.request.build_opener(NoRedirect)
        try:
            with opener.open(req) as resp:
                return resp.status, resp.headers.get("Location", "")
        except urllib.error.HTTPError as err:
            with err:
                return err.status, err.headers.get("Location", "")

    # ---- pinning a recipe choice (#30) ----

    def test_the_chooser_lists_every_recipe_with_a_way_to_pin_it(self):
        status, ctype, body = self.get("/recipes?item=mod%3Agizmo")
        self.assertEqual(status, 200)
        self.assertIn("text/html", ctype)
        self.assertEqual(body.count("Pin this"), 2, "one button per recipe")
        self.assertIn("Part", body, "the chooser has to say what each recipe consumes")

    def test_the_chooser_refuses_an_item_nothing_makes(self):
        self.assertEqual(self.get("/recipes?item=mod%3Apart")[0], 404)
        self.assertEqual(self.get("/recipes")[0], 400)

    def test_a_pin_persists_survives_into_the_plan_and_can_be_taken_off(self):
        dear = next(pins.fingerprint(r) for r in self.state.graph.real_producers("mod:gizmo")
                    if r.rid == "gizmo_dear")
        # Baseline first: without a pin the ranking takes the cheap route, so the
        # assertion below is about the pin rather than about agreeing with the ranking.
        self.assertEqual(self.plan_recipe("mod:gizmo"), "gizmo_cheap")

        status, loc = self.post({"item": "mod:gizmo", "fp": dear, "back": "/"}, "/pin")
        self.assertEqual(status, 303)
        self.assertTrue(loc.startswith("/?m="), loc)
        self.assertEqual(self.state.pinned["mod:gizmo"], frozenset(["gizmo_dear"]))
        self.assertEqual(pins.load(self.state.pins_path)["mod:gizmo"]["category"],
                         "mod.press")
        self.assertEqual(self.plan_recipe("mod:gizmo"), "gizmo_dear")
        self.assertIn("pinned", self.get("/plan?item=mod%3Agizmo")[2])

        self.post({"item": "mod:gizmo", "fp": "", "back": "/"}, "/pin")
        self.assertNotIn("mod:gizmo", self.state.pinned)
        self.assertEqual(self.plan_recipe("mod:gizmo"), "gizmo_cheap")

    def plan_recipe(self, key):
        with self.state.lock:
            return self.state.solver().solve(key, 1)["tree"]["recipe"]

    def test_a_fingerprint_for_no_recipe_here_is_refused_rather_than_stored(self):
        # A chooser left open across a graph reload. Writing the pin anyway would store
        # something that resolves to nothing and reads as the feature being broken.
        _status, loc = self.post({"item": "mod:gizmo", "fp": "0" * 12, "back": "/"},
                                 "/pin")
        self.assertIn("no%20longer%20in%20the%20graph", loc)
        self.assertNotIn("mod:gizmo", self.state.pins)

    def test_the_chooser_link_carries_the_quantity_back_intact(self):
        # `item_href` emits `&amp;` for an href attribute, and percent-encoding THAT as a
        # return path produced a parameter called `amp;qty`: the back link worked and the
        # quantity silently reverted to 1. Asserted on the rendered page rather than on
        # the helper, because the helper was right and the caller picked the wrong one.
        body = self.get("/plan?item=mod%3Agizmo&qty=7")[2]
        href = re.search(r'href="(/recipes\?[^"]+)"', body)
        self.assertIsNotNone(href, "no chooser link on a craft node")
        query = urllib.parse.parse_qs(
            urllib.parse.urlsplit(html_mod.unescape(href.group(1))).query)
        back = urllib.parse.parse_qs(urllib.parse.urlsplit(query["back"][0]).query)
        self.assertEqual(back.get("qty"), ["7"], back)
        self.assertEqual(back.get("item"), ["mod:gizmo"], back)

    def test_the_chooser_caps_a_huge_list_and_says_that_it_did(self):
        # `techreborn:dynamiccell` has 1,228 recipes on the real pack and 137 items have
        # more than 60. Uncapped, that page is a megabyte with a form per row.
        g = self.state.graph
        keep = list(g.recipes)
        try:
            for i in range(server.MAX_CHOICES + 5):
                g.add(Recipe("bulk%d" % i, "t", [("mod:gizmo", 1)],
                             [Ingredient(["mod:part"], 3 + i)], category="mod.press"))
            body = self.get("/recipes?item=mod%3Agizmo")[2]
            self.assertEqual(body.count("Pin this"), server.MAX_CHOICES)
            self.assertIn("Showing the 60 best-ranked of 67", body)
        finally:
            g.recipes = keep
            g._invalidate()

    def test_a_pin_below_the_cap_is_still_reachable_to_unpin(self):
        # Otherwise the one action you cannot reach any other way becomes unreachable
        # exactly when the list is long enough to want it.
        g = self.state.graph
        keep = list(g.recipes)
        try:
            # Its one ingredient is neither stocked nor craftable, so the ranking puts it
            # DEAD LAST -- 68th of 68, comfortably past the cap. Ranking it there by
            # sheer count would not work: with nothing to separate them the sort is
            # stable and a recipe added early stays near the top however many follow it.
            g.add(Recipe("gizmo_exotic", "t", [("mod:gizmo", 1)],
                         [Ingredient(["mod:unobtainium"], 4)], category="mod.press"))
            for i in range(server.MAX_CHOICES + 5):
                g.add(Recipe("bulk%d" % i, "t", [("mod:gizmo", 1)],
                             [Ingredient(["mod:part"], 1)], category="mod.press"))
            exotic = next(pins.fingerprint(r) for r in g.real_producers("mod:gizmo")
                          if r.rid == "gizmo_exotic")
            self.post({"item": "mod:gizmo", "fp": exotic, "back": "/"}, "/pin")
            body = self.get("/recipes?item=mod%3Agizmo")[2]
            self.assertEqual(body.count("Pin this"), server.MAX_CHOICES, "cap not applied")
            self.assertEqual(body.count("Unpin"), 1, "the pinned row was capped away")
        finally:
            self.post({"item": "mod:gizmo", "fp": "", "back": "/"}, "/pin")
            g.recipes = keep
            g._invalidate()
            with self.state.lock:
                self.state.pinned, self.state.pin_notes = pins.resolve(g, self.state.pins)

    def test_the_chooser_cannot_be_used_to_redirect_you_off_site(self):
        # `back` reaches /recipes through the QUERY STRING rather than a form, which is a
        # second door into the same open-redirect hole `_safe_path` exists to close.
        for hostile in ("https://evil.example/x", "//evil.example/x", "javascript:alert(1)"):
            body = self.get("/recipes?item=mod%3Agizmo&back="
                            + urllib.parse.quote(hostile))[2]
            self.assertNotIn("evil.example", body, hostile)
            self.assertNotIn("javascript:", body, hostile)
            _status, loc = self.post({"item": "mod:gizmo", "fp": "", "back": hostile},
                                     "/pin")
            self.assertTrue(loc.startswith("/"), loc)
            self.assertFalse(loc.startswith("//"), loc)

    def test_a_toggle_persists_and_changes_the_state(self):
        status, _loc = self.post({"uid": "mod.press", "state": machines.BUILDABLE})
        self.assertEqual(status, 303)
        self.assertEqual(self.state.states["mod.press"][0], machines.BUILDABLE)
        self.assertEqual(machines.load_overrides(self.machines_path)["mod.press"],
                         machines.BUILDABLE)

    def test_a_bogus_state_is_ignored_rather_than_stored(self):
        before = dict(self.state.overrides)
        self.post({"uid": "mod.press", "state": "wishful"})
        self.assertNotIn("wishful", self.state.overrides.values())
        self.assertEqual(set(self.state.overrides) - set(before), set())

    def test_the_toggle_returns_you_to_where_you_toggled_from(self):
        _status, loc = self.post({"uid": "mod.press", "state": machines.HAVE,
                                  "back": "/machine?uid=mod.press"})
        self.assertTrue(loc.startswith("/machine?uid=mod.press&m="), loc)

    def test_an_off_site_back_target_cannot_redirect_you_away(self):
        # `back` comes from a form field, so an absolute URL there would be an open
        # redirect. Anything not starting with `/` falls back to the machines page.
        # `//host/path` is the one that got through a startswith("/") check: it is a
        # valid protocol-relative URL and navigates off-site.
        for hostile in ("https://evil.example/x", "//evil.example/x", "http:/x",
                        "javascript:alert(1)"):
            _status, loc = self.post({"uid": "mod.press", "state": machines.HAVE,
                                      "back": hostile})
            self.assertTrue(loc.startswith("/machines?"), "%s -> %s" % (hostile, loc))

    # ---- editing infinite sources (#17) ----

    def source_post(self, fields):
        status, loc = self.post(fields, path="/sources")
        self.assertEqual(status, 303)
        return urllib.parse.unquote(loc.partition("m=")[2])

    def test_the_page_explains_a_source_before_telling_you_how_to_add_one(self):
        body = self.get("/sources")[2]
        self.assertIn("emits a resource from no inputs", body)
        self.assertNotIn("recipegraph sources --add", body,
                         "the CLI syntax with two unexplained placeholders is what "
                         "made this unusable")

    def test_a_source_can_be_added_and_removed_from_the_page(self):
        try:
            msg = self.source_post({"do": "add", "block": "mod:press",
                                    "key": "fluid:water"})
            self.assertIn("now makes", msg)
            self.assertEqual(
                self.state.source_overrides["generators"]["mod:press"], ["fluid:water"])
            self.assertIn("mod:press", self.get("/sources")[2])
            self.assertIn("removed", self.source_post({"do": "forget",
                                                       "block": "mod:press"}))
            self.assertNotIn("mod:press", self.state.source_overrides["generators"])
        finally:
            self.source_post({"do": "forget", "block": "mod:press"})

    def test_a_typo_is_refused_rather_than_silently_making_nothing_free(self):
        msg = self.source_post({"do": "add", "block": "mod:press", "key": "mod:nonsense"})
        self.assertIn("no item or fluid", msg)
        self.assertNotIn("mod:press", self.state.source_overrides["generators"])

    def test_disabling_a_source_removes_it_from_what_is_free(self):
        self.assertIn("fluid:water", self.state.free_sources)
        try:
            self.source_post({"do": "disable", "key": "fluid:water"})
            self.assertNotIn("fluid:water", self.state.free_sources)
            self.source_post({"do": "enable", "key": "fluid:water"})
            self.assertIn("fluid:water", self.state.free_sources)
        finally:
            self.source_post({"do": "enable", "key": "fluid:water"})

    def test_vanilla_water_is_a_visible_switch(self):
        try:
            self.assertIn("vanilla infinite water off",
                          self.source_post({"do": "vanilla"}))
            self.assertFalse(self.state.source_overrides["vanilla_water"])
            self.assertIn("vanilla infinite water on",
                          self.source_post({"do": "vanilla"}))
        finally:
            if not self.state.source_overrides["vanilla_water"]:
                self.source_post({"do": "vanilla"})

    def test_an_unknown_action_changes_nothing(self):
        before = dict(self.state.source_overrides["generators"])
        self.assertEqual(self.source_post({"do": "drop_table"}), "")
        self.assertEqual(self.state.source_overrides["generators"], before)

    def test_placed_blocks_are_offered_as_candidates(self):
        # Nobody knows the registry name of the block they built; the world scan does.
        self.assertEqual(
            generators.candidates({"mod:lava_source": 1, "minecraft:chest": 4}, {}),
            ["mod:lava_source"])
        self.assertEqual(
            generators.candidates({"nuclearcraft:water_source": 1}, {}), [],
            "a built-in generator is already handled, not a candidate")
        self.assertEqual(
            generators.candidates({"mod:lava_source": 1},
                                  {"generators": {"mod:lava_source": ["fluid:lava"]}}),
            [], "and neither is one you already added")

    def test_a_legacy_dotted_id_is_not_offered_for_a_generator_already_handled(self):
        """#27: the save records `minecraft:mod.water_source` for a colon-less TE id.

        That IS the `mod:water_source` already on the list, and `resolve` matches it, so
        offering it as something to add sends the user to configure a generator that is
        already working.
        """
        placed = {"minecraft:mod.water_source": 1}
        ov = {"generators": {"mod:water_source": ["fluid:water"]}}
        self.assertEqual(generators.candidates(placed, ov), [])
        self.assertEqual(generators.resolve(placed, {}, ov).get("fluid:water"),
                         "placed: minecraft:mod.water_source")

    def test_sightings_and_resolve_agree_about_what_is_present(self):
        """The CLI's "N of M matched" line is computed from `sightings`; if it used a
        literal `in placed` it would contradict the list printed above it."""
        placed = {"minecraft:mod.water_source": 1, "mod:other_idle": 2}
        seen = generators.sightings(["mod:water_source", "mod:other", "mod:absent"],
                                    placed, {})
        self.assertEqual(sorted(seen), ["mod:other", "mod:water_source"])

    # ---- staleness ----

    def test_no_banner_while_the_files_are_unchanged(self):
        self.assertEqual(self.state.stale(), [])
        self.assertNotIn("changed on disk", self.get("/machines")[2])

    def test_a_rebuilt_graph_raises_the_banner_on_every_page(self):
        """The whole workflow is dump, rebuild, look at the UI.

        Without this the server keeps serving the graph it loaded at startup and says
        nothing, which reads as "the rebuild did not work" rather than "reload me".
        """
        os.utime(self.state.graph_path, None)
        try:
            self.assertEqual(self.state.stale(), ["the recipe graph"])
            for href, _label, _icon in server.NAV_ITEMS:
                body = self.get(href)[2]
                self.assertIn("changed on disk", body, href)
                self.assertIn("the recipe graph", body, href)
        finally:
            self.state.load_all()

    def test_a_rescanned_stock_file_is_noticed_too(self):
        os.utime(self.state.have_path, None)
        try:
            self.assertEqual(self.state.stale(), ["your AE2 stock"])
            self.assertIn("your AE2 stock", self.get("/")[2])
        finally:
            self.state.load_all()

    def test_reload_clears_the_banner_and_returns_you_to_the_page(self):
        os.utime(self.state.graph_path, None)
        self.assertTrue(self.state.stale())
        status, loc = self.post({"back": "/machines"}, path="/reload")
        self.assertEqual(status, 303)
        self.assertEqual(loc, "/machines")
        self.assertEqual(self.state.stale(), [])

    def test_reload_cannot_be_used_as_an_open_redirect_either(self):
        # The second handler that needed this check reintroduced the weak
        # startswith("/") version; both now go through one helper.
        for hostile in ("//evil.example/x", "https://evil.example/x"):
            _status, loc = self.post({"back": hostile}, path="/reload")
            self.assertEqual(loc, "/", hostile)

    def test_a_missing_file_counts_as_changed_rather_than_crashing(self):
        moved = self.state.have_path + ".away"
        os.rename(self.state.have_path, moved)
        try:
            self.assertEqual(self.state.stale(), ["your AE2 stock"])
        finally:
            os.rename(moved, self.state.have_path)
            self.state.load_all()

    def test_posting_to_any_other_path_is_a_404(self):
        req = urllib.request.Request("http://127.0.0.1:%d/plan" % self.port, data=b"x=1")
        with self.assertRaises(urllib.error.HTTPError) as caught:
            urllib.request.urlopen(req)
        with caught.exception:
            self.assertEqual(caught.exception.status, 404)




class EnsureGraphTest(unittest.TestCase):
    """`serve` builds the graph itself rather than making the user run two commands."""

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.graph_path = os.path.join(self.dir, "graph.json")
        self.instance = os.path.join(self.dir, "instance")
        os.makedirs(os.path.join(self.instance, "mc-recipe-dump"))
        self.dump = os.path.join(self.instance, "mc-recipe-dump", "recipes.ndjson")
        open(self.dump, "w").close()

    def _write_graph(self, instance_dir=None):
        g = build_graph()
        g.instance_dir = instance_dir
        g.save(self.graph_path)

    def test_a_current_graph_is_left_alone(self):
        from recipegraph.cli import ensure_graph

        self._write_graph(self.instance)
        os.utime(self.graph_path, (10 ** 9, 10 ** 9))     # graph newer than the dump
        os.utime(self.dump, (10 ** 9 - 100, 10 ** 9 - 100))
        before = os.path.getmtime(self.graph_path)
        ensure_graph(self.graph_path, quiet=True)
        self.assertEqual(os.path.getmtime(self.graph_path), before)

    def test_a_newer_dump_is_detected_through_the_remembered_instance(self):
        # The instance is read back off the graph, so no --instance flag is needed.
        from recipegraph.cli import _dump_newer_than_graph

        self._write_graph(self.instance)
        os.utime(self.graph_path, (10 ** 9 - 100, 10 ** 9 - 100))
        os.utime(self.dump, (10 ** 9, 10 ** 9))
        self.assertTrue(_dump_newer_than_graph(self.graph_path, self.instance))

    def test_no_build_only_warns(self):
        from recipegraph.cli import ensure_graph

        self._write_graph(self.instance)
        os.utime(self.graph_path, (10 ** 9 - 100, 10 ** 9 - 100))
        os.utime(self.dump, (10 ** 9, 10 ** 9))
        before = os.path.getmtime(self.graph_path)
        ensure_graph(self.graph_path, quiet=True, allow_build=False)
        self.assertEqual(os.path.getmtime(self.graph_path), before)

    def test_a_graph_with_no_remembered_instance_is_not_touched(self):
        from recipegraph.cli import ensure_graph

        self._write_graph(None)
        before = os.path.getmtime(self.graph_path)
        ensure_graph(self.graph_path, quiet=True)
        self.assertEqual(os.path.getmtime(self.graph_path), before)

    def test_the_instance_survives_a_save_and_load(self):
        self._write_graph(self.instance)
        self.assertEqual(Graph.load(self.graph_path).instance_dir, self.instance)


class DiscriminatedLinkTest(unittest.TestCase):
    """Every link a renderer emits must survive the trip back to the server.

    #23: `graphview` percent-encoded the colon in an item key and left the `#` of an NBT
    discriminator alone, so the browser treated the discriminator as a URL fragment and
    never sent it. The server saw a key that does not exist, answered "No item with that
    id", and the `qty` inside the discarded fragment was lost too. 298,765 of 340,324
    named keys carry a discriminator, so that was most of the graph.

    This is written as a PROPERTY over every href on every page rather than as an
    assertion about the two call sites that were visible at the time. A new link builder
    that forgets to encode is caught here without anybody remembering to add a case.
    """

    @classmethod
    def setUpClass(cls):
        cls.dir = tempfile.mkdtemp()
        graph_path = os.path.join(cls.dir, "graph.json")
        g = fixtures.discriminated_graph()
        index.mark_container_transfers(g)
        g.save(graph_path)
        cls.graph = g
        have_path = os.path.join(cls.dir, "have.json")
        with open(have_path, "w") as fh:
            json.dump({"items": {}, "placed": {}}, fh)
        cls.httpd, cls.state = server.serve(
            graph_path, have_path, os.path.join(cls.dir, "machines.json"),
            host="127.0.0.1", port=0,
            sources_path=os.path.join(cls.dir, "sources.json"))
        cls.port = cls.httpd.server_address[1]
        cls.thread = threading.Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()
        cls.thread.join(timeout=5)

    def get(self, path):
        url = "http://127.0.0.1:%d%s" % (self.port, path)
        try:
            with urllib.request.urlopen(url) as resp:
                return resp.status, resp.read().decode()
        except urllib.error.HTTPError as err:
            with err:
                return err.status, err.read().decode()

    @staticmethod
    def _hrefs(markup):
        return re.findall(r"""href=["']([^"']+)["']""", markup)

    @staticmethod
    def _as_browser_sends(href):
        """The request a browser actually makes: entities decoded, fragment dropped.

        Dropping the fragment is the whole point. A test that parses the raw href sees
        the discriminator and passes while every real click 404s.
        """
        return html_mod.unescape(href).split("#", 1)[0]

    def _pages(self):
        """Every server-rendered surface that can emit an item link."""
        quoted = urllib.parse.quote(fixtures.NAMED_CAN, safe="")
        return [
            "/",
            "/plan?item=%s&qty=1" % quoted,
            "/explore?q=%s" % urllib.parse.quote("Can", safe=""),
            "/machines",
            "/machine?uid=%s" % urllib.parse.quote("mod.arc_furnace", safe=""),
            "/sources",
        ]

    def test_every_page_renders(self):
        for path in self._pages():
            status, _body = self.get(path)
            self.assertEqual(status, 200, path)

    def test_dropping_the_fragment_changes_no_link(self):
        """THE property. A correctly built href has no bare `#`, so what the browser
        sends is byte-identical to what the renderer wrote.

        Stated this way it needs no list of valid keys, which is what lets it cover the
        sources page (which links `fluid:water`, free by default and absent from any
        recipe) and every link builder added later.
        """
        checked = 0
        for path in self._pages():
            _status, body = self.get(path)
            for href in self._hrefs(body):
                raw = html_mod.unescape(href)
                checked += 1
                self.assertEqual(
                    raw, self._as_browser_sends(href),
                    "%s on %s carries an unencoded '#', so the browser truncates it "
                    "and the server never sees the rest" % (href, path))
        self.assertGreater(checked, 0, "no links were rendered, so nothing was proven")

    def test_every_item_link_keeps_its_key_and_qty(self):
        seen = 0
        for path in self._pages():
            _status, body = self.get(path)
            for href in self._hrefs(body):
                params = urllib.parse.parse_qs(
                    urllib.parse.urlparse(self._as_browser_sends(href)).query)
                if "item" not in params:
                    continue
                seen += 1
                self.assertEqual(params.get("qty", ["missing"])[0], "1",
                                 "%s on %s lost its qty" % (href, path))
        self.assertGreater(seen, 0, "no item links were rendered")

    def test_a_discriminated_key_actually_appears_in_a_link(self):
        # Guards the two properties above: if nothing rendered ever carried a
        # discriminator they would pass vacuously on bare keys.
        found = []
        for path in self._pages():
            _status, body = self.get(path)
            for href in self._hrefs(body):
                params = urllib.parse.parse_qs(
                    urllib.parse.urlparse(self._as_browser_sends(href)).query)
                found.extend(k for k in params.get("item", []) if "#" in k)
        self.assertTrue(found, "no rendered link carried an NBT discriminator")

    def test_a_plan_for_a_discriminated_key_resolves_to_that_variant(self):
        # The discriminator has to reach the solver, not just survive the URL. The base
        # key is a real item here too, so a truncated link would silently plan the wrong
        # thing rather than 404, which is the quieter half of #23.
        _s, variant = self.get("/plan?item=%s&qty=1"
                               % urllib.parse.quote(fixtures.NAMED_CAN, safe=""))
        _s, base = self.get("/plan?item=%s&qty=1"
                            % urllib.parse.quote(fixtures.CAN_BASE, safe=""))
        self.assertIn("Brine Can", variant)
        self.assertNotIn("Brine Can", base)

    def test_an_unknown_key_still_404s(self):
        status, body = self.get("/plan?item=%s&qty=1"
                                % urllib.parse.quote("mod:nope#deadbeefcafe", safe=""))
        self.assertEqual(status, 404)
        self.assertIn("No item with that id", body)

    def test_every_link_the_graph_diagram_emits_round_trips(self):
        # The diagram is SVG built in graphview, not by the server, and it was the
        # builder that broke. Drive it directly so the property covers it even if the
        # plan page stops embedding the diagram.
        result = Solver(self.graph).solve(fixtures.NAMED_CAN, 1)
        svg, _legend = graphview.render_diagram(result["tree"])
        hrefs = self._hrefs(svg)
        self.assertTrue(hrefs, "the diagram emitted no links")
        for href in hrefs:
            raw = html_mod.unescape(href)
            self.assertEqual(raw, self._as_browser_sends(href), href)
            params = urllib.parse.parse_qs(urllib.parse.urlparse(raw).query)
            self.assertIn(params["item"][0], self.graph.labels, href)
            self.assertEqual(params["qty"][0], "1", href)

    def test_the_client_side_builder_encodes_too(self):
        # HOME_JS builds hrefs in the browser and no server-side render can see them.
        # encodeURIComponent is the JS equivalent of quote(safe=""); a bare
        # concatenation would reintroduce #23 on the search page alone.
        self.assertIn("'/plan?item='+encodeURIComponent(", server.HOME_JS)


class MobileLayoutTest(unittest.TestCase):
    """The phone layout is a contract between markup and CSS, so test it as one.

    On a 390px viewport the machines table rendered 1,424px wide, so three of its five
    columns were off-screen and every page scrolled sideways. The fix turns each row into
    a card, which only works while the cells carry the `c-` classes the CSS orders them
    by. Drop one in the markup and the page silently goes back to being a table that does
    not fit, and nothing else in the suite would notice.

    Asserted against the CSS text rather than a rendered pixel because the rules only
    apply under a media query; what is checkable here is that both halves of the contract
    exist and agree. The widths themselves were measured in a real browser.
    """

    CELL_CLASSES = ("c-state", "c-name", "c-recipes", "c-why", "c-acts")

    def test_the_card_css_styles_every_class_the_markup_emits(self):
        # A class emitted but unstyled is a cell that falls back to source order, which is
        # how the state pill would end up above the machine name.
        for cls in self.CELL_CLASSES:
            self.assertIn("td.%s" % cls, server.HOME_CSS, cls)

    def test_both_stylesheets_carry_a_phone_block(self):
        # render.CSS covers the plan fragment, HOME_CSS the server-rendered pages. A phone
        # gets one of each, so both need narrow rules.
        self.assertIn("@media(max-width:640px)", render.CSS)
        self.assertIn("@media(max-width:700px)", server.HOME_CSS)

    def test_the_unbreakable_string_guards_are_present(self):
        # Registry ids and the #28 evidence string have no space to break at, and a flex
        # item defaults to min-width:auto. Without these the cards are as wide as the
        # longest id whatever flex-basis says: a 571px page on a 390px screen, and the
        # overflow is INSIDE the cell, so no element's bounding box looks wrong.
        self.assertIn("overflow-wrap:anywhere", render.CSS)
        self.assertIn("table.mach code,table.mach a.mname{overflow-wrap:anywhere}",
                      server.HOME_CSS)
        self.assertIn("td.c-why{flex:1 1 100%;order:4;font-size:12.5px;"
                      "overflow-wrap:anywhere}", server.HOME_CSS)

    def test_the_diagram_legend_wraps_instead_of_running_off_the_edge(self):
        # #49. Four to five entries do not fit across 360px of usable width. The diagram
        # next to it scrolls inside `.diagwrap`, which is right for a wide SVG and wrong
        # here: a legend entry parked off the right edge of a scroller is an unexplained
        # colour, which is the whole bug.
        self.assertIn("flex-wrap:wrap", graphview.DIAGRAM_CSS)
        self.assertNotIn("overflow:auto", graphview.DIAGRAM_CSS.split(".legend{")[1])

    def test_the_state_stripe_moves_to_the_card_edge(self):
        # On desktop the stripe is on the first cell, which is the row's left edge. In a
        # card the first cell is not an edge, and the stripe rendered as a coloured bar
        # floating in the middle of the row.
        self.assertIn("table.mach tr[data-state] td:first-child{border-left:0}",
                      server.HOME_CSS)
        self.assertIn("table.mach tr[data-state=have]{border-left-color:var(--ok)}",
                      server.HOME_CSS)

class TouchAffordanceTest(unittest.TestCase):
    """Rules that only make sense with a pointer, and the one that must beat them all.

    Both bugs here shipped in the phone-layout change and were found by using the site on
    a phone, not by reading it.
    """

    def test_hidden_beats_every_display_rule(self):
        """`hidden` works through `[hidden]{display:none}` in the UA sheet, which has
        almost no specificity, so any author `display` on the same element wins and the
        element stays on screen.

        `table.mach tr{display:flex}` did exactly that: filtering set the attribute on 499
        of 503 rows and the counter read "4" while all 503 stayed visible, and the
        "nothing matches" row (a `tr` too) was permanently on screen.
        """
        self.assertIn("[hidden]{display:none!important}", server.HOME_CSS)

    def test_no_hover_rule_escapes_the_hover_media_query(self):
        """A touch browser has no pointer to move away, so `:hover` sticks to the last
        thing tapped. `button:hover` sets the same accent border and text colour as
        `[aria-pressed=true]`, so unselecting a state chip cleared its background and left
        it looking selected.

        Written as a lint over the whole sheet rather than a check of the one rule that
        caused it, because the next `:hover` anyone adds has the same problem.
        """
        for name, sheet in (("render.CSS", render.CSS), ("HOME_CSS", server.HOME_CSS),
                            ("EXPLORE_CSS", render.EXPLORE_CSS),
                            ("DIAGRAM_CSS", graphview.DIAGRAM_CSS)):
            # Comments explain the rule and say ":hover" while doing it.
            for line in re.sub(r"/\*.*?\*/", "", sheet, flags=re.S).splitlines():
                if ":hover" not in line:
                    continue
                self.assertIn(
                    "@media(hover:hover)", line,
                    "%s has a :hover rule outside the hover media query, so it will "
                    "stick after a tap on a touch screen:\n    %s" % (name, line.strip()))

    def test_a_search_result_keeps_its_name(self):
        """Only the name could shrink in a result row; the pills and the id are
        `flex:0 0 auto`. At 390px the fixed content is wider than the row, so the name was
        squeezed to zero width and the id printed on top of the details link. Every
        result read as a nameless row of pills."""
        self.assertIn(".hits .nm2{flex:1 1 100%", server.HOME_CSS)
        self.assertIn(".hits a{flex-wrap:wrap", server.HOME_CSS)


def deep_graph(fanout=3, depth=8):
    """A tree big enough to truncate at the default node cap.

    3**8 is 6,561 expansions against a 4,000 cap, so the plan is genuinely cut off and
    genuinely completable by raising it -- which is what makes the go-deeper control
    testable rather than merely present in the markup.

    Every recipe is hand crafting, so machine availability plays no part and the only
    thing limiting the tree is the cap.
    """
    g = Graph()
    g.names = {"deep:leaf": "Leaf"}
    for level in range(depth):
        for i in range(fanout ** level):
            key = "deep:n%d_%d" % (level, i)
            g.names[key] = "Node %d.%d" % (level, i)
            kids = ["deep:n%d_%d" % (level + 1, i * fanout + k) for k in range(fanout)]
            if level == depth - 1:
                kids = ["deep:leaf"]
            for kid in kids:
                g.names.setdefault(kid, kid)
            g.add(Recipe("r%d_%d" % (level, i), "Crafting Table", [(key, 1)],
                         [Ingredient([k], 1) for k in kids],
                         category="minecraft.crafting"))
    # One node with a SECOND recipe, so the tree offers a "pick a recipe" link: the
    # chooser is only linked where there is a choice, and the return path it carries is
    # where a raised cap is easiest to lose.
    #
    # DEEP IN THE TREE, not at the root. A second route to the root is a SHORTCUT, and the
    # ranker would take it -- the plan collapses to two nodes and stops being the deep one
    # this fixture exists to be.
    g.add(Recipe("deepest_alt", "Crafting Table", [("deep:n%d_0" % (depth - 1), 1)],
                 [Ingredient(["deep:leaf"], 2)], category="minecraft.crafting"))
    return g


class DeepPlanTest(LiveServerCase):
    """Going deeper, end to end, on a plan that is really truncated. #25.

    The shared fixture has 3 recipes and can never hit a cap, so every assertion about the
    control there is about markup rather than behaviour. This one measures: the node count
    has to actually rise and the truncation has to actually clear.
    """

    HAVE = {"items": {}}

    @staticmethod
    def graph():
        return deep_graph()

    ROOT = "/plan?item=deep%3An0_0&qty=1"

    @staticmethod
    def _nodes(body):
        m = re.search(r"node cap \(([\d,]+)\)", body)
        return int(m.group(1).replace(",", "")) if m else None

    def test_the_default_plan_is_truncated_and_offers_the_control(self):
        body = self.get(self.ROOT)[2]
        self.assertIn("node cap", body)
        self.assertIn("Go deeper", body)
        self.assertNotIn("--max-nodes", body,
                         "the running server has no such argument to raise")
        self.assertGreaterEqual(self._nodes(body), server.DEFAULT_MAX_NODES)

    def test_following_the_control_actually_expands_the_tree(self):
        """The measurement that makes this a feature rather than a link.

        A handler that accepted `max_nodes` and dropped it would present as the button
        doing nothing, which is worse than the CLI flag it replaced.
        """
        body = self.get(self.ROOT)[2]
        href = re.search(r'href="(/plan\?[^"]+max_nodes[^"]*)"', body).group(1)
        deeper = self.get(html_mod.unescape(href))[2]
        # 3**8 = 6,561 expansions, so doubling 4,000 to 8,000 finishes the tree.
        self.assertNotIn("node cap", deeper)
        self.assertNotIn("Go deeper", deeper)

    def test_the_control_stops_at_the_ceiling_rather_than_going_forever(self):
        """Unconditional, because the `if` this used to hide behind was never true.

        `deep_graph()` at depth 8 finishes well inside the ceiling, so the guarded body
        never ran and the test asserted nothing. `DeeperThanTheCeilingTest` below owns this
        with a graph that really does truncate at 16,000; here we only check the shallow
        case is NOT wrongly told it has hit the end of the road.
        """
        body = self.get("%s&max_nodes=%d" % (self.ROOT, server.MAX_NODES_CEILING))[2]
        self.assertNotIn("node cap", body)
        self.assertNotIn("deepest this page goes", body)
        self.assertNotIn("Go deeper", body)

    def test_only_the_go_deeper_link_carries_a_cap(self):
        """One slow page must not make every later page slow with nothing saying why.

        Every OTHER link into a plan is a fresh question and starts at the default.
        """
        deep = server.DEFAULT_MAX_NODES * 2
        body = self.get("%s&max_nodes=%d" % (self.ROOT, deep))[2]
        carrying = [html_mod.unescape(h)
                    for h in re.findall(r"""href=['"](/plan\?[^'"]+)['"]""", body)
                    if "max_nodes" in html_mod.unescape(h)]
        self.assertLessEqual(len(carrying), 1,
                             "more than one link carries a cap: %s" % carrying)
        for raw in carrying:
            self.assertIn("max_nodes=%d" % (deep * 2), raw)

    def test_the_deep_plan_carries_its_cap_into_the_recipe_chooser(self):
        # The return path. Go deeper, pin a recipe, and you must land back on the plan you
        # were reading rather than on a shallower one that truncates again.
        deep = server.DEFAULT_MAX_NODES * 2
        body = self.get("%s&max_nodes=%d" % (self.ROOT, deep))[2]
        backs = re.findall(r"""/recipes\?item=[^'"]*back=([^'"&]+)""", body)
        self.assertTrue(backs, "the plan offers no chooser link to carry a cap on")
        for back in backs:
            self.assertIn("max_nodes%%3D%d" % deep, back)


class DeeperThanTheCeilingTest(LiveServerCase):
    """A plan that is STILL truncated at the ceiling, so the end-of-the-road branch runs.

    `DeepPlanTest`'s depth-8 graph completes at 8,000 nodes, which is why the ceiling test
    there could only ever assert the negative. 3**9 is ~29,500 expansions against a 16,000
    cap, so this one is cut off at the ceiling and the control has to disappear.
    """

    HAVE = {"items": {}}

    @staticmethod
    def graph():
        return deep_graph(3, 9)

    ROOT = "/plan?item=deep%3An0_0&qty=1"

    def test_at_the_ceiling_the_control_is_gone_and_the_page_says_why(self):
        body = self.get("%s&max_nodes=%d" % (self.ROOT, server.MAX_NODES_CEILING))[2]
        # Keyed off the ceiling sentence, NOT off "node cap": a plan that ran out of work
        # budget instead carries no such substring, so a guard on it can be false even on a
        # truncated page.
        self.assertIn("deepest this page goes", body)
        self.assertIn("{:,}".format(server.MAX_NODES_CEILING), body)
        self.assertNotIn("Go deeper", body)

    def test_above_the_ceiling_is_clamped_back_to_it(self):
        # An editable URL. `work_budget` derives from this, so an unclamped value is a way
        # to hang the server from the address bar.
        body = self.get("%s&max_nodes=99999999" % self.ROOT)[2]
        self.assertIn("deepest this page goes", body)
        self.assertIn("{:,}".format(server.MAX_NODES_CEILING), body)


if __name__ == "__main__":
    unittest.main()
