"""A file written by `plan --html` or `explore --html` is a DOCUMENT a browser opens. #138

WHAT WENT WRONG. `render.render_html` returns a fragment beginning `<style>`, with no
`<head>` and so no `<meta name=viewport>`. A browser with no viewport meta lays out at its
default 980px, so a saved plan opened on a phone rendered the desktop layout zoomed out.
Measured with Playwright at a 390px device viewport, before: 980px of layout viewport.

WHY IT SURVIVED THE MANDATORY PHONE AUDIT. `tools/mobile-audit.js` drives the SERVER, and
`server._wrap_fragment` supplies a real head, so the served page was always correct and this
delivery path was never in the audit's scope. The audit grew a standalone leg with this fix;
these tests are the cheap half of the same guarantee, and the reason the flag cannot be
quietly dropped from a CLI writer again.

TWO CALLERS, TWO OPPOSITE NEEDS, which is why the fragment did not simply grow a head: the
CLI writes a whole document and the server embeds the same bytes inside one. So the tests
below pin both directions -- the metas present when asked for, ABSENT when not -- and the
absence matters as much: a fragment carrying its own viewport meta would put a second one
inside the server's page, and a doctype would nest inside the artifact publisher's.
"""

import os
import re
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import chart, render, server  # noqa: E402
from recipegraph.solve import STATUS_CRAFT, STATUS_HAVE  # noqa: E402

#: The prescan window an HTML parser uses to find an encoding declaration. A charset meta
#: past this is ignored, which is why `STANDALONE_HEAD` goes first and nothing may be
#: inserted ahead of it.
PRESCAN_BYTES = 1024

# Quote-agnostic: `server._shell` writes the attribute unquoted and `render.STANDALONE_HEAD`
# quotes it, and this has to count both or the duplicate check below silently passes.
VIEWPORT = re.compile(r"""<meta\s+name=["']?viewport""", re.I)
CHARSET = re.compile(r"""<meta\s+charset=["']?utf-8""", re.I)
#: The four things a standalone page must NOT grow. See `render.py`'s module docstring.
SHELL = re.compile(r"<!doctype|<html|<head\b|<body\b", re.I)


def _result():
    tree = {"key": "mod:out", "label": "Out", "kind": "item", "need": 1,
            "status": STATUS_CRAFT, "children": [
                {"key": "mod:in", "label": "In", "kind": "item", "need": 2,
                 "status": STATUS_HAVE}]}
    return {"target": "mod:out", "target_name": "Out", "qty": 1, "nodes": 2, "tree": tree,
            "shopping_list": [], "used_from_stock": [], "from_sources": [],
            "machines_to_build": [], "truncated": False}


def _payload():
    return {"query": "out", "searched": 2, "named": 2, "hidden": 0, "collapsed": 0,
            "results": [{"key": "mod:out", "name": "Out", "label": "Out", "kind": "item",
                         "stock": 0, "makes": [], "makes_total": 0, "used_in": [],
                         "used_in_total": 0, "oredicts": []}]}


def _chart_payload():
    return {"since": 0, "until": 60, "window_label": "1h", "range_label": "2 snapshots",
            "source": "save", "movers": [], "series": {}, "power": [],
            "storage": {"level_rows": 2}}


#: Every renderer whose output is written to a file by the CLI. `chart` is here because it
#: had the same defect and is named in neither #138 nor its refs: a page nothing serves is a
#: page nothing measures.
PAGES = (("plan", lambda **kw: render.render_html(_result(), **kw)),
         ("explore", lambda **kw: render.render_explore_html(_payload(), **kw)),
         ("chart", lambda **kw: chart.render_chart_html(_chart_payload(), **kw)))


class FragmentTest(unittest.TestCase):
    """The default, which is what the server embeds and what a Claude Artifact publishes."""

    def test_neither_page_carries_a_viewport_meta_by_default(self):
        for name, page in PAGES:
            html = page()
            self.assertFalse(VIEWPORT.search(html),
                             "%s: the served fragment must not carry a viewport meta, the "
                             "page wrapping it already has one" % name)

    def test_neither_page_carries_a_document_shell(self):
        for name, page in PAGES:
            self.assertFalse(SHELL.search(page()),
                             "%s: the fragment must stay publishable as an artifact "
                             "unchanged" % name)

    def test_the_served_plan_page_has_exactly_one_viewport_meta(self):
        """The other half of the same guarantee, at the surface that does the wrapping.

        A duplicate here would be silent: the second meta wins, so a wrong one would change
        the layout of every served page with nothing failing.
        """
        wrapped = server._wrap_fragment("Out x1", render.render_html(_result()))
        self.assertEqual(len(VIEWPORT.findall(wrapped)), 1, wrapped[:400])


class StandaloneTest(unittest.TestCase):
    """`standalone=True`, which is what the two CLI writers ask for."""

    def test_both_pages_declare_the_viewport(self):
        for name, page in PAGES:
            self.assertTrue(VIEWPORT.search(page(standalone=True)),
                            "%s: a file opened from disk lays out at 980px without this"
                            % name)

    def test_the_encoding_is_declared_inside_the_parser_prescan(self):
        """FIRST in the output, or it is not read at all.

        A page with no declaration is sniffed, and an all-ASCII prefix sniffs as
        windows-1252 in chromium, which mojibakes the ellipsis `graphview._shorten` writes
        into a truncated label and any accented item name.
        """
        for name, page in PAGES:
            html = page(standalone=True)
            found = CHARSET.search(html)
            self.assertTrue(found, "%s: no encoding declaration" % name)
            self.assertLess(found.end(), PRESCAN_BYTES,
                            "%s: the charset meta is past the %d-byte prescan window and "
                            "will be ignored" % (name, PRESCAN_BYTES))

    def test_a_standalone_page_is_still_not_a_document(self):
        """The fix is two metas, and growing it into a full document breaks both other
        callers: the server's head would nest, and so would the artifact publisher's."""
        for name, page in PAGES:
            self.assertFalse(SHELL.search(page(standalone=True)),
                             "%s: no doctype, html, head or body" % name)

    def test_the_page_itself_is_unchanged_apart_from_the_head(self):
        for name, page in PAGES:
            self.assertEqual(page(standalone=True),
                             render.STANDALONE_HEAD + page(),
                             "%s: standalone must add the head and nothing else" % name)


def _calls(source, func):
    """Every `func(...)` in `source`, whole, with brackets balanced.

    A regex cannot do this: the CLI's plan writer nests `iconset.resolver(...)` inside
    `os.path.dirname(os.path.abspath(...))`, three levels deep, and a regex that stops at the
    first `)` reports a call that does not carry the flag while the real one does.
    """
    out = []
    for match in re.finditer(re.escape(func) + r"\(", source):
        depth, i = 0, match.end() - 1
        while i < len(source):
            if source[i] == "(":
                depth += 1
            elif source[i] == ")":
                depth -= 1
                if depth == 0:
                    out.append(source[match.start():i + 1])
                    break
            i += 1
    return out


class WriterTest(unittest.TestCase):
    """Every CLI writer has to ASK for it, and all three had the defect. #138

    THREE, not the two the issue names. `chart --html` writes `chart.render_chart_html` the
    same way and is never served, so it carried the same bug with nobody to report it. A test
    that enumerated only the two reported writers would have let the third keep it.

    Read as text rather than driven, because these writers load a graph or a metrics database
    from disk before they reach the write. The assertion is still specific: the flag has to
    be inside the same call as the file it writes.
    """

    SOURCE = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                          "recipegraph", "cli.py")
    WRITERS = ("render_html", "render_explore_html", "render_chart_html")

    def _source(self):
        with open(self.SOURCE, encoding="utf-8") as fh:
            return fh.read()

    def test_every_writer_asks_for_a_standalone_document(self):
        source = self._source()
        for func in self.WRITERS:
            calls = _calls(source, func)
            self.assertTrue(calls, "cli.py no longer calls %s" % func)
            for call in calls:
                self.assertIn("standalone=True", call,
                              "cli.py writes %s output to a file, so it has to ask for the "
                              "document head: %s" % (func, call))

    def test_every_writer_opens_the_file_as_utf8(self):
        """What makes the `<meta charset>` those documents carry true.

        The default is the process locale, so a container with LANG unset writes ASCII and
        dies on the ellipsis in a truncated label.
        """
        opens = _calls(self._source(), "open")
        html = [c for c in opens if "args.html" in c]
        self.assertEqual(len(html), len(self.WRITERS),
                         "expected one file open per html writer, got %r" % (html,))
        for call in html:
            self.assertIn('encoding="utf-8"', call, call)


if __name__ == "__main__":
    unittest.main()
