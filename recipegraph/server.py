"""A local web UI, so the tool is usable without a terminal.

Runs on the stdlib http.server -- no Flask, no pip, consistent with the rest of the
project. Binds to 127.0.0.1 by default: the graph reveals the contents of a live base and
this has no authentication, so it must not be exposed to a network by accident. Pass
--host 0.0.0.0 deliberately if you want that.

Pages are SERVER-RENDERED, reusing the same renderers the CLI's --html flag uses, so there
is exactly one implementation of each view. No client-side framework and no API to keep in
sync.

The graph is loaded once at startup and held in memory. On a 121k-recipe graph that is
several seconds and a few hundred MB, which is why this is a long-running server rather
than a CGI script that reloads per request.
"""

import html
import json
import os
import re
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import cost as cost_mod
from . import explore as explore_mod
from . import generators as generators_mod
from . import machines as machines_mod
from .model import Graph
from .names import build_reverse
from .render import CSS, render_explore_html, render_html
from .solve import Solver

HOME_CSS = """
form.search{display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin-bottom:8px}
form.search input[type=search]{flex:1 1 320px;font:16px var(--sans);padding:11px 14px;
border:1px solid var(--line);border-radius:9px;background:var(--card);color:var(--fg)}
form.search input[type=number]{width:96px;font:16px var(--mono);padding:11px 12px;
border:1px solid var(--line);border-radius:9px;background:var(--card);color:var(--fg)}
form.search button{font:600 14px var(--sans);padding:11px 18px;border-radius:9px;
border:1px solid var(--accent);background:var(--accent);color:#fff;cursor:pointer}
form.search button.ghost{background:var(--card);color:var(--fg);border-color:var(--line)}
form.search input:focus{outline:2px solid var(--accent);outline-offset:-1px}
.hint2{color:var(--dim);font-size:13px;margin-bottom:22px}
.hits{list-style:none;padding:0;margin:0}
.hits li{border-bottom:1px solid var(--line)}
.hits a{display:flex;gap:12px;align-items:baseline;padding:10px 4px;text-decoration:none;
color:inherit}
.hits a:hover{background:var(--accent-soft)}
.hits .nm2{flex:1 1 auto;font-size:15px}
.hits .id2{font:11.5px var(--mono);color:var(--dim);flex:0 0 auto}
.pill{font:600 10.5px/1.7 var(--mono);padding:1px 8px;border-radius:99px;flex:0 0 auto}
.pill.ok{background:var(--okbg);color:var(--ok)}
.pill.no{background:var(--needbg);color:var(--need)}
.pill.mut{background:rgba(127,127,127,.15);color:var(--dim)}
nav.top{display:flex;gap:16px;font-size:13.5px;margin-bottom:20px}
nav.top a{color:var(--accent);text-decoration:none}
nav.top a:hover{text-decoration:underline}
table.mach{width:100%;border-collapse:collapse;font-size:13.5px}
table.mach td{padding:5px 8px 5px 0;border-top:1px solid var(--line);vertical-align:baseline}
table.mach form{display:inline}
table.mach button{font:11.5px var(--sans);padding:2px 8px;border:1px solid var(--line);
border-radius:6px;background:var(--card);color:var(--fg);cursor:pointer}
table.mach button:hover{border-color:var(--accent);color:var(--accent)}
"""


def _esc(s):
    return html.escape(str(s), quote=True)


def _wrap_fragment(title, fragment):
    """Give an artifact-style fragment a real HTML document shell."""
    return ("<!doctype html><html><head><meta charset=utf-8>"
            "<meta name=viewport content='width=device-width,initial-scale=1'>"
            "<title>%s</title></head><body>%s<div class='wrap' style='padding-top:0'>"
            "<nav class='top'><a href='/'>&larr; back to search</a></nav></div>"
            "<style>%s</style></body></html>"
            % (_esc(title), fragment, HOME_CSS))


def _page(title, body):
    return ("<!doctype html><html><head><meta charset=utf-8>"
            "<meta name=viewport content='width=device-width,initial-scale=1'>"
            "<title>%s</title><style>%s%s</style></head><body>%s</body></html>"
            % (_esc(title), CSS, HOME_CSS, body))


def _nav(active=""):
    items = [("/", "Search"), ("/machines", "Machines"), ("/stats", "Coverage")]
    return "<nav class='top'>%s</nav>" % " ".join(
        ("<b>%s</b>" if href == active else "<a href='%s'>%%s</a>" % href) % _esc(label)
        for href, label in items)


class State:
    """Loaded once; requests read it. Rebuilt only when overrides change."""

    def __init__(self, graph_path, have_path, machines_path, sources_path=None):
        self.graph_path = graph_path
        self.have_path = have_path
        self.machines_path = machines_path
        self.sources_path = sources_path or "data/sources.json"
        self.lock = threading.Lock()
        self.graph = Graph.load(graph_path)
        self.reverse = build_reverse(self.graph.names)
        self.have = {}
        self.craftables = set()
        self.placed = {}
        if have_path and os.path.exists(have_path):
            with open(have_path) as fh:
                doc = json.load(fh)
            self.have = dict(doc.get("items") or {})
            for name, amount in (doc.get("fluids") or {}).items():
                self.have["fluid:%s" % name] = amount
            for aspect, amount in (doc.get("essentia") or {}).items():
                self.have["essentia:%s" % str(aspect).lower()] = amount
            self.craftables = set(doc.get("craftables") or ())
            self.placed = doc.get("placed") or {}
        self.refresh_machines()

    def refresh_machines(self):
        """Recompute machine states and the cost table (cost depends on machine state)."""
        overrides = machines_mod.load_overrides(self.machines_path)
        self.overrides = overrides
        self.machine_info = machines_mod.describe(self.graph, self.placed, self.have,
                                                  overrides=overrides)
        self.states = {uid: (i["state"], i["why"])
                       for uid, i in self.machine_info.items()}
        self.free_sources = generators_mod.resolve(
            self.placed, self.have, generators_mod.load_overrides(self.sources_path))
        self.costs = cost_mod.estimate_cached(
            self.graph, self.graph_path, have=self.have, machine_states=self.states,
            free_sources=self.free_sources)

    def solver(self):
        return Solver(self.graph, have=self.have, craftables=self.craftables,
                      machine_states=self.states, costs=self.costs,
                      free_sources=self.free_sources)


def home_page(state, query="", qty=1):
    hits = []
    if query:
        for key in explore_mod.search(state.graph, query, have=state.have, limit=40):
            hits.append(key)

    rows = []
    for item in hits:
        stock = item["stock"]
        pills = []
        pills.append("<span class='pill %s'>%s</span>"
                     % ("ok" if stock else "mut",
                        ("%s in stock" % "{:,}".format(stock)) if stock else "none"))
        pills.append("<span class='pill %s'>%s</span>"
                     % ("mut" if item["makes_total"] else "no",
                        "%d recipe%s" % (item["makes_total"],
                                         "" if item["makes_total"] == 1 else "s")
                        if item["makes_total"] else "no recipe"))
        rows.append(
            "<li><a href='/plan?item=%s&qty=%d'><span class='nm2'>%s</span>"
            "%s<span class='id2'>%s</span></a></li>"
            % (urllib.parse.quote(item["key"]), qty, _esc(item["name"]),
               "".join(pills), _esc(item["key"])))

    body = """<div class="wrap">%s
  <div class="eyebrow">Recipe graph</div>
  <h1>What do you want to make?</h1>
  <div class="hint2">%s recipes &middot; %s items in your network &middot;
    %d machine categories on hand</div>
  <form class="search" method="get" action="/">
    <input type="search" name="q" value="%s" placeholder="Borax, Ultimate Component&hellip;"
           autofocus autocomplete="off">
    <input type="number" name="qty" value="%d" min="1" title="quantity">
    <button type="submit">Search</button>
    <button class="ghost" type="submit" formaction="/explore">Explore</button>
  </form>
  %s
</div>""" % (
        _nav("/"),
        "{:,}".format(len(state.graph.recipes)),
        "{:,}".format(len(state.have)),
        sum(1 for s, _w in state.states.values() if s == machines_mod.HAVE),
        _esc(query), qty,
        ("<ul class='hits'>%s</ul>" % "".join(rows)) if rows else
        ("<p class='hint2'>No item matched &ldquo;%s&rdquo;.</p>" % _esc(query)
         if query else ""),
    )
    return _page("Recipe graph", body)


def machines_page(state, message=""):
    rows = []
    order = {machines_mod.HAVE: 0, machines_mod.BUILDABLE: 1, machines_mod.UNAVAILABLE: 2}
    for uid, (st, why) in sorted(state.states.items(),
                                 key=lambda kv: (order.get(kv[1][0], 3), kv[0])):
        cls = {"have": "ok", "buildable": "mut", "unavailable": "no"}.get(st, "mut")
        buttons = " ".join(
            "<form method='post' action='/machines'>"
            "<input type='hidden' name='uid' value='%s'>"
            "<input type='hidden' name='state' value='%s'>"
            "<button type='submit'>%s</button></form>" % (_esc(uid), target, label)
            for target, label in (("have", "have"), ("buildable", "buildable"),
                                  ("unavailable", "none"))
            if target != st)
        rows.append("<tr><td><span class='pill %s'>%s</span></td>"
                    "<td><code>%s</code></td><td class='hint2'>%s%s</td><td>%s</td></tr>"
                    % (cls, st, _esc(uid), _esc(why),
                       " <b>(manual)</b>" if uid in state.overrides else "", buttons))

    counts = machines_mod.summarise(state.states)
    body = """<div class="wrap">%s
  <div class="eyebrow">Machines</div>
  <h1>What can you actually build with?</h1>
  <div class="hint2">%d on hand &middot; %d buildable &middot; %d unavailable.
   Availability decides which recipes plans prefer. Manual choices always win.%s</div>
  <div class="card"><table class="mach">%s</table></div>
</div>""" % (
        _nav("/machines"),
        counts[machines_mod.HAVE], counts[machines_mod.BUILDABLE],
        counts[machines_mod.UNAVAILABLE],
        (" <b>%s</b>" % _esc(message)) if message else "",
        "".join(rows),
    )
    return _page("Machines", body)


def stats_page(state):
    from . import index as index_mod

    cov = index_mod.coverage(state.graph)
    rows = "".join("<tr><td><code>%s</code></td><td class='n'>%s</td></tr>"
                   % (_esc(k), "{:,}".format(v))
                   for k, v in list(cov["by_category"].items())[:25])
    body = """<div class="wrap">%s
  <div class="eyebrow">Coverage</div>
  <h1>What the graph knows</h1>
  <div class="stats">
    <div class="stat"><div class="k">Recipes</div><div class="v">%s</div></div>
    <div class="stat"><div class="k">Craftable items</div><div class="v">%s</div></div>
    <div class="stat"><div class="k">Named items</div><div class="v">%s</div></div>
    <div class="stat"><div class="k">Oredict</div><div class="v">%s</div></div>
  </div>
  <div class="card"><h2>By source</h2><table>%s</table></div>
  <div class="card"><h2>Biggest categories</h2><table>%s</table></div>
</div>""" % (
        _nav("/stats"),
        "{:,}".format(cov["recipes"]), "{:,}".format(cov["produced_keys"]),
        "{:,}".format(cov["named_items"]), "{:,}".format(cov["oredict_entries"]),
        "".join("<tr><td><code>%s</code></td><td class='n'>%s</td></tr>"
                % (_esc(k), "{:,}".format(v)) for k, v in cov["by_source"].items()),
        rows,
    )
    return _page("Coverage", body)


class Handler(BaseHTTPRequestHandler):
    state = None
    server_version = "recipegraph"

    def log_message(self, fmt, *args):
        pass  # the access log is noise for a single-user local tool

    def _send(self, body, status=200, ctype="text/html; charset=utf-8"):
        raw = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        parts = urllib.parse.urlparse(self.path)
        q = urllib.parse.parse_qs(parts.query)
        st = self.state

        def one(name, default=""):
            return (q.get(name) or [default])[0]

        try:
            if parts.path == "/":
                qty = max(1, int(one("qty", "1") or 1))
                return self._send(home_page(st, one("q"), qty))
            if parts.path == "/explore":
                query = one("q")
                results = explore_mod.search(st.graph, query, have=st.have, limit=40)
                payload = {"query": query, "results": results,
                           "searched": len(st.graph.names)}
                return self._send(_wrap_fragment(
                    "Explore: %s" % query, render_explore_html(payload)))
            if parts.path == "/plan":
                key = one("item")
                qty = max(1, int(one("qty", "1") or 1))
                if not key:
                    return self._send(home_page(st), 400)
                with st.lock:
                    result = st.solver().solve(key, qty)
                return self._send(_wrap_fragment(
                    "%s x%d" % (result["target_name"], qty),
                    render_html(result, st.graph)))
            if parts.path == "/machines":
                return self._send(machines_page(st))
            if parts.path == "/stats":
                return self._send(stats_page(st))
            if parts.path == "/healthz":
                return self._send("ok", ctype="text/plain; charset=utf-8")
        except (ValueError, KeyError) as exc:
            return self._send(_page("Error", "<div class='wrap'><h1>Bad request</h1>"
                                    "<p class='hint2'>%s</p></div>" % _esc(exc)), 400)
        self._send(_page("Not found", "<div class='wrap'><h1>Not found</h1>"
                         "<p><a href='/'>Back to search</a></p></div>"), 404)

    def do_POST(self):
        parts = urllib.parse.urlparse(self.path)
        if parts.path != "/machines":
            return self._send("", 404)
        length = int(self.headers.get("Content-Length") or 0)
        form = urllib.parse.parse_qs(self.rfile.read(length).decode("utf-8"))
        uid = (form.get("uid") or [""])[0]
        target = (form.get("state") or [""])[0]
        st = self.state
        msg = ""
        if uid and target in (machines_mod.HAVE, machines_mod.BUILDABLE,
                              machines_mod.UNAVAILABLE):
            with st.lock:
                overrides = dict(st.overrides)
                overrides[uid] = target
                machines_mod.save_overrides(st.machines_path, overrides)
                # Costs depend on machine state, so both must be recomputed together or
                # plans would rank against stale availability.
                st.refresh_machines()
            msg = "%s set to %s" % (uid, target)
        self.send_response(303)
        self.send_header("Location", "/machines?m=%s" % urllib.parse.quote(msg))
        self.end_headers()


def serve(graph_path, have_path, machines_path, host="127.0.0.1", port=8765,
          sources_path=None):
    state = State(graph_path, have_path, machines_path, sources_path)
    Handler.state = state
    httpd = ThreadingHTTPServer((host, port), Handler)
    return httpd, state
