"""The web UI, driven over a real socket.

The handler had no tests at all: every page was only ever verified by eye. That left the
routing table, the `/suggest` JSON contract the typeahead depends on, and the redirect
sanitisation on the machine-toggle form all uncovered, none of which a browser screenshot
would catch failing on an edge case.

A real server on a real port rather than a mocked handler, because the things worth testing
here ARE the HTTP behaviours: status codes, content types, and redirect targets.
"""

import json
import os
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import machines, server  # noqa: E402
from recipegraph.model import Graph, Ingredient, Recipe  # noqa: E402


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

    def test_a_missing_item_parameter_does_not_500(self):
        self.assertEqual(self.get("/plan")[0], 400)

    def test_a_non_numeric_quantity_is_a_bad_request_not_a_crash(self):
        self.assertEqual(self.get("/plan?item=mod%3Awidget&qty=lots")[0], 400)

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

    def test_machines_page_lists_every_category(self):
        body = self.get("/machines")[2]
        for uid in self.state.machine_info:
            self.assertIn(uid, body, uid)

    # ---- the toggle form ----

    def post(self, fields):
        data = urllib.parse.urlencode(fields).encode()
        req = urllib.request.Request("http://127.0.0.1:%d/machines" % self.port, data=data)

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

    def test_posting_to_any_other_path_is_a_404(self):
        req = urllib.request.Request("http://127.0.0.1:%d/plan" % self.port, data=b"x=1")
        with self.assertRaises(urllib.error.HTTPError) as caught:
            urllib.request.urlopen(req)
        with caught.exception:
            self.assertEqual(caught.exception.status, 404)


if __name__ == "__main__":
    unittest.main()
