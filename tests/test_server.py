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
from recipegraph import generators, graphview, index, machines, render, server  # noqa: E402
from recipegraph.model import Graph, Ingredient, Recipe  # noqa: E402
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
    return g


class ServerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dir = tempfile.mkdtemp()
        graph_path = os.path.join(cls.dir, "graph.json")
        build_graph().save(graph_path)
        have_path = os.path.join(cls.dir, "have.json")
        with open(have_path, "w") as fh:
            json.dump({"items": {"mod:part": 40},
                       "placed": {"nuclearcraft:water_source": 1}}, fh)
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


if __name__ == "__main__":
    unittest.main()


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
