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
from .render import CSS, kind_chip, render_explore_html, render_html
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
/* Nav items are links AND targets, so they get an icon, a hit area and a visible
   current-page state. Text-only links at 13.5px did not read as clickable. */
nav.top{display:flex;gap:4px;font-size:13.5px;margin-bottom:22px;flex-wrap:wrap;
border-bottom:1px solid var(--line);padding-bottom:2px}
nav.top a,nav.top span.cur{display:flex;align-items:center;gap:7px;padding:8px 13px;
text-decoration:none;border-radius:8px 8px 0 0;color:var(--dim);
border-bottom:2px solid transparent;margin-bottom:-3px}
nav.top a:hover{background:var(--accent-soft);color:var(--accent)}
nav.top span.cur{color:var(--fg);font-weight:600;border-bottom-color:var(--accent)}
nav.top svg{width:15px;height:15px;flex:0 0 auto;stroke:currentColor;fill:none;
stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round}
nav.top a:focus-visible,nav.top span.cur:focus-visible{outline:2px solid var(--accent);
outline-offset:2px}

/* Machines page: an operable table, so filters sit above and state is a shape as well
   as a word. */
.toolbar{display:flex;gap:9px;flex-wrap:wrap;align-items:center;margin-bottom:14px}
.toolbar input[type=search]{flex:1 1 240px;font:14px var(--sans);padding:9px 12px;
border:1px solid var(--line);border-radius:9px;background:var(--card);color:var(--fg)}
.toolbar select{font:13px var(--sans);padding:9px 10px;border:1px solid var(--line);
border-radius:9px;background:var(--card);color:var(--fg)}
.toolbar input:focus,.toolbar select:focus{outline:2px solid var(--accent);
outline-offset:-1px}
.chips{display:flex;gap:6px;flex-wrap:wrap;margin-bottom:16px}
.chip-btn{font:600 11.5px var(--mono);padding:5px 11px;border-radius:99px;cursor:pointer;
border:1px solid var(--line);background:var(--card);color:var(--dim);letter-spacing:.02em}
.chip-btn[aria-pressed=true]{border-color:var(--accent);background:var(--accent-soft);
color:var(--accent)}
.chip-btn:focus-visible{outline:2px solid var(--accent);outline-offset:2px}
.chip-btn .n{font-variant-numeric:tabular-nums;opacity:.7;margin-left:5px}

table.mach{width:100%;border-collapse:collapse;font-size:13.5px}
table.mach th{text-align:left;font:600 10.5px var(--mono);letter-spacing:.1em;
text-transform:uppercase;color:var(--dim);padding:0 10px 8px 0;white-space:nowrap;
border-bottom:1px solid var(--line)}
table.mach th.sortable{cursor:pointer;user-select:none}
table.mach th.sortable:hover{color:var(--accent)}
table.mach th .ar{opacity:0;font-size:9px}
table.mach th[aria-sort] .ar{opacity:1;color:var(--accent)}
table.mach td{padding:7px 10px 7px 0;border-top:1px solid var(--line);
vertical-align:baseline}
table.mach tbody tr:hover{background:var(--accent-soft)}
table.mach td.n{text-align:right;font-family:var(--mono);
font-variant-numeric:tabular-nums;white-space:nowrap;width:1%}
table.mach a.mname{color:inherit;text-decoration:none;font-weight:550}
table.mach a.mname:hover{color:var(--accent);text-decoration:underline}
table.mach code{font:11.5px var(--mono);color:var(--dim)}
table.mach form{display:inline}
table.mach .acts{display:flex;gap:4px;justify-content:flex-end}
table.mach button{font:11.5px var(--sans);padding:2px 8px;border:1px solid var(--line);
border-radius:6px;background:var(--card);color:var(--fg);cursor:pointer;white-space:nowrap}
table.mach button:hover{border-color:var(--accent);color:var(--accent)}
/* A severity stripe so state reads before any text is parsed. */
table.mach tr[data-state] td:first-child{border-left:3px solid transparent}
table.mach tr[data-state=have] td:first-child{border-left-color:var(--ok)}
table.mach tr[data-state=buildable] td:first-child{border-left-color:var(--warn)}
table.mach tr[data-state=unknown] td:first-child{border-left-color:var(--dim)}
table.mach tr[data-state=unavailable] td:first-child{border-left-color:var(--need)}
.pill.warnp{background:var(--warnbg);color:var(--warn)}
.empty-row td{color:var(--dim);font-style:italic;padding:18px 0}
.twocol{display:grid;grid-template-columns:1fr;gap:20px;align-items:start}
@media(min-width:820px){.twocol{grid-template-columns:1fr 1fr}}
.klist{list-style:none;margin:0;padding:0;font-size:13.5px}
.klist li{display:flex;gap:10px;align-items:baseline;padding:4px 0;
border-top:1px solid var(--line)}
.klist li:first-child{border-top:0}
.klist .c{font:11.5px var(--mono);color:var(--dim);flex:0 0 auto;
font-variant-numeric:tabular-nums;min-width:38px;text-align:right}
.klist a{color:inherit;text-decoration:none;min-width:0}
.klist a:hover{color:var(--accent);text-decoration:underline}
.klist .grow{flex:1 1 auto}
.klist code{font:11.5px var(--mono);color:var(--dim);word-break:break-all}
"""


def _esc(s):
    return html.escape(str(s), quote=True)


def _item(graph, key):
    """Type chip plus escaped name, matching how the plan and explore views render keys."""
    return kind_chip(graph.kind(key)) + _esc(graph.bare_name(key))


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


# Inline SVG, because the artifact CSP blocks every off-host request and an icon font
# would be one. Single-path, 16x16, stroked with currentColor so both themes work.
NAV_ICONS = {
    "search": "<circle cx='7' cy='7' r='4.6'/><path d='M10.5 10.5 14 14'/>",
    "machines": ("<path d='M2 13h12M4 13V8l3-2v7M9 13V4l4 2v7'/>"
                 "<path d='M6 10.5h.01M11 8.5h.01'/>"),
    "coverage": "<path d='M2.5 13V6M6.5 13V3M10.5 13V8M14 13V5'/>",
    "sources": "<path d='M8 2c2.5 3 4 5 4 7a4 4 0 0 1-8 0c0-2 1.5-4 4-7z'/>",
}


def _icon(name):
    return ("<svg viewBox='0 0 16 16' aria-hidden='true'>%s</svg>"
            % NAV_ICONS.get(name, ""))


NAV_ITEMS = (
    ("/", "Search", "search"),
    ("/machines", "Machines", "machines"),
    ("/sources", "Sources", "sources"),
    ("/stats", "Coverage", "coverage"),
)


def _nav(active=""):
    out = []
    for href, label, icon in NAV_ITEMS:
        if href == active:
            out.append("<span class='cur' aria-current='page'>%s%s</span>"
                       % (_icon(icon), _esc(label)))
        else:
            out.append("<a href='%s'>%s%s</a>" % (href, _icon(icon), _esc(label)))
    return "<nav class='top'>%s</nav>" % "".join(out)


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
            % (urllib.parse.quote(item["key"]), qty,
               kind_chip(item.get("kind")) + _esc(item.get("label") or item["name"]),
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


STATE_PILL = {"have": "ok", "buildable": "warnp", "unknown": "mut", "unavailable": "no"}
STATE_LABEL = {"have": "have", "buildable": "buildable", "unknown": "unidentified",
               "unavailable": "no route"}

MACHINES_JS = """
(function(){
 var q=document.getElementById('mq'), sel=document.getElementById('mmod'),
     rows=Array.prototype.slice.call(document.querySelectorAll('#mbody tr[data-state]')),
     body=document.getElementById('mbody'), shown=document.getElementById('mshown'),
     none=document.getElementById('mnone'), active={};
 document.querySelectorAll('.chip-btn[data-state]').forEach(function(b){
   b.addEventListener('click',function(){
     var on=b.getAttribute('aria-pressed')==='true';
     b.setAttribute('aria-pressed',on?'false':'true');
     if(on){delete active[b.dataset.state];}else{active[b.dataset.state]=1;}
     apply();
   });
 });
 function apply(){
   var text=(q.value||'').toLowerCase().trim(), mod=sel.value,
       states=Object.keys(active), n=0;
   rows.forEach(function(r){
     var ok=(!text||r.dataset.hay.indexOf(text)>=0)
         && (!mod||r.dataset.mod===mod)
         && (!states.length||active[r.dataset.state]);
     r.hidden=!ok; if(ok)n++;
   });
   shown.textContent=n.toLocaleString();
   none.hidden=n>0;
 }
 q.addEventListener('input',apply); sel.addEventListener('change',apply);
 // Sort in place. Comparators come from data attributes rather than cell text so a
 // formatted "1,441" sorts as a number and the state column sorts by usefulness
 // (have first) instead of alphabetically.
 var dir={};
 document.querySelectorAll('th.sortable').forEach(function(th){
   th.addEventListener('click',function(){
     var k=th.dataset.sort, desc=!dir[k]; dir={}; dir[k]=desc;
     document.querySelectorAll('th.sortable').forEach(function(o){
       o.removeAttribute('aria-sort');});
     th.setAttribute('aria-sort',desc?'descending':'ascending');
     th.querySelector('.ar').textContent=desc?'\\u25bc':'\\u25b2';
     var num=k==='recipes'||k==='rank';
     rows.sort(function(a,b){
       var x=a.dataset[k], y=b.dataset[k];
       if(num){x=+x;y=+y;return desc?y-x:x-y;}
       return desc?String(y).localeCompare(x):String(x).localeCompare(y);
     });
     rows.forEach(function(r){body.appendChild(r);});
   });
 });
 apply();
})();
"""

_STATE_RANK = {"have": 0, "buildable": 1, "unknown": 2, "unavailable": 3}


def _toggle_form(uid, target, label, back):
    return ("<form method='post' action='/machines'>"
            "<input type='hidden' name='uid' value='%s'>"
            "<input type='hidden' name='state' value='%s'>"
            "<input type='hidden' name='back' value='%s'>"
            "<button type='submit'>%s</button></form>"
            % (_esc(uid), target, _esc(back), _esc(label)))


def _toggles(uid, current, back="/machines"):
    return "".join(_toggle_form(uid, t, l, back) for t, l in
                   (("have", "have"), ("buildable", "buildable"),
                    ("unavailable", "none")) if t != current)


def machines_page(state, message="", query="", mod=""):
    counts = machines_mod.summarise(state.states)
    recipes_by_state = dict.fromkeys(machines_mod.STATES, 0)
    mods = {}
    rows = []
    for uid, info in sorted(state.machine_info.items(),
                            key=lambda kv: (_STATE_RANK.get(kv[1]["state"], 4),
                                            -kv[1]["recipes"], kv[0])):
        st = info["state"]
        recipes_by_state[st] = recipes_by_state.get(st, 0) + info["recipes"]
        name = info["title"] or uid
        mods[info["mod"]] = mods.get(info["mod"], 0) + 1
        cands = info["candidates"]
        # The evidence column answers "why does it think that", which is the only reason
        # anyone opens this page after the first time.
        evidence = info["why"]
        if info.get("from_catalyst"):
            evidence += " (from JEI)"
        hay = " ".join((name, uid, info["mod"], evidence, " ".join(cands))).lower()
        rows.append(
            "<tr data-state='%s' data-rank='%d' data-name='%s' data-uid='%s' "
            "data-mod='%s' data-recipes='%d' data-hay='%s'>"
            "<td><span class='pill %s'>%s</span></td>"
            "<td><a class='mname' href='/machine?uid=%s'>%s</a>"
            "%s<br><code>%s</code></td>"
            "<td class='n'>%s</td>"
            "<td class='hint2' style='margin:0'>%s</td>"
            "<td><div class='acts'>%s</div></td></tr>"
            % (st, _STATE_RANK.get(st, 4), _esc(name.lower()), _esc(uid.lower()),
               _esc(info["mod"]), info["recipes"], _esc(hay),
               STATE_PILL.get(st, "mut"), STATE_LABEL.get(st, st),
               urllib.parse.quote(uid), _esc(name),
               " <b>(manual)</b>" if info["manual"] else "",
               _esc(uid), "{:,}".format(info["recipes"]), _esc(evidence),
               _toggles(uid, st)))

    chips = "".join(
        "<button class='chip-btn' type='button' data-state='%s' aria-pressed='false'>"
        "%s<span class='n'>%d</span></button>"
        % (s, STATE_LABEL.get(s, s), counts.get(s, 0))
        for s in machines_mod.STATES)
    options = "".join("<option value='%s'>%s (%d)</option>" % (_esc(m), _esc(m or "?"), n)
                      for m, n in sorted(mods.items(), key=lambda kv: (-kv[1], kv[0])))

    body = """<div class="wrap">%s
  <div class="eyebrow">Machines</div>
  <h1>What can you actually build with?</h1>
  <div class="hint2">Availability decides which recipes a plan prefers, so a machine
   listed wrongly here changes your plans. Manual choices always win.%s</div>
  <div class="stats">
    <div class="stat ok"><div class="k">On hand</div><div class="v">%d</div>
      <div class="k" style="margin-top:4px">%s recipes</div></div>
    <div class="stat"><div class="k">Buildable</div><div class="v">%d</div>
      <div class="k" style="margin-top:4px">%s recipes</div></div>
    <div class="stat"><div class="k">Unidentified</div><div class="v">%d</div>
      <div class="k" style="margin-top:4px">%s recipes</div></div>
    <div class="stat need"><div class="k">No route</div><div class="v">%d</div>
      <div class="k" style="margin-top:4px">%s recipes</div></div>
  </div>
  <div class="toolbar">
    <input id="mq" type="search" value="%s" placeholder="Filter by name, id, mod or reason&hellip;"
           autocomplete="off">
    <select id="mmod" aria-label="Filter by mod"><option value="">every mod</option>%s</select>
  </div>
  <div class="chips">%s</div>
  <div class="card">
    <table class="mach">
      <thead><tr>
        <th class="sortable" data-sort="rank">State <span class="ar"></span></th>
        <th class="sortable" data-sort="name">Machine <span class="ar"></span></th>
        <th class="sortable n" data-sort="recipes">Recipes <span class="ar"></span></th>
        <th class="sortable" data-sort="mod">Why <span class="ar"></span></th>
        <th></th>
      </tr></thead>
      <tbody id="mbody">%s
        <tr id="mnone" class="empty-row" hidden><td colspan="5">Nothing matches those
          filters.</td></tr>
      </tbody>
    </table>
    <div class="hint2" style="margin:12px 0 0"><b id="mshown">%d</b> of %d categories
      shown. <b>Unidentified</b> means this tool could not work out which block the
      category corresponds to, not that you cannot use it &mdash; run
      <code>/recipedump</code> with mod v0.4.0 to fix most of these.</div>
  </div>
</div>
<script>%s</script>""" % (
        _nav("/machines"),
        (" <b>%s</b>" % _esc(message)) if message else "",
        counts.get("have", 0), "{:,}".format(recipes_by_state.get("have", 0)),
        counts.get("buildable", 0), "{:,}".format(recipes_by_state.get("buildable", 0)),
        counts.get("unknown", 0), "{:,}".format(recipes_by_state.get("unknown", 0)),
        counts.get("unavailable", 0),
        "{:,}".format(recipes_by_state.get("unavailable", 0)),
        _esc(query), options, chips, "".join(rows), len(rows), len(rows),
        MACHINES_JS,
    )
    return _page("Machines", body)


def machine_page(state, uid):
    """One category: what it makes, what it consumes, and why it is rated as it is."""
    info = state.machine_info.get(uid)
    if not info:
        return _page("Not found", "<div class='wrap'>%s<h1>No such category</h1>"
                     "<p class='hint2'><code>%s</code> is not in this graph.</p></div>"
                     % (_nav("/machines"), _esc(uid))), 404

    detail = machines_mod.responsibilities(state.graph, uid)
    st = info["state"]

    def klist(pairs, total, verb):
        if not pairs:
            return "<div class='hint2' style='margin:0'>Nothing.</div>"
        items = "".join(
            "<li><span class='c'>%s</span>"
            "<a class='grow' href='/plan?item=%s&qty=1'>%s</a>"
            "%s</li>"
            % ("{:,}".format(n), urllib.parse.quote(k), _item(state.graph, k),
               ("<span class='pill ok'>%s</span>" % "{:,}".format(state.have[k]))
               if state.have.get(k) else "")
            for k, n in pairs)
        more = ("<div class='hint2' style='margin:10px 0 0'>+%d more %s</div>"
                % (total - len(pairs), verb)) if total > len(pairs) else ""
        return "<ul class='klist'>%s</ul>%s" % (items, more)

    cands = info["candidates"]
    if cands:
        cand_html = "<ul class='klist'>%s</ul>" % "".join(
            "<li><a href='/plan?item=%s&qty=1'>%s</a><code>%s</code>"
            "<span class='grow'></span></li>"
            % (urllib.parse.quote(c), _item(state.graph, c), _esc(c))
            for c in cands[:8])
    else:
        cand_html = ("<div class='hint2' style='margin:0'>None found. The category title "
                     "did not match any item name and the id could not be guessed from "
                     "the uid, so availability is unknown rather than negative.</div>")

    body = """<div class="wrap">%s
  <div class="eyebrow">Machine</div>
  <h1>%s<span class="x">%s recipes</span></h1>
  <div class="id">%s</div>
  <div class="stats">
    <div class="stat"><div class="k">Availability</div>
      <div class="v" style="font-size:17px"><span class="pill %s">%s</span></div></div>
    <div class="stat"><div class="k">Mod</div>
      <div class="v" style="font-size:17px">%s</div></div>
    <div class="stat"><div class="k">Makes</div><div class="v">%s</div></div>
    <div class="stat"><div class="k">Consumes</div><div class="v">%s</div></div>
  </div>
  <div class="card">
    <h2><span>Why it is rated that way</span></h2>
    <p class="hint2" style="margin:0 0 12px">%s%s</p>
    <h2><span>Candidate machine items</span></h2>
    %s
    <div class="bar" style="margin:16px 0 0">%s</div>
  </div>
  <div class="twocol" style="margin-top:20px">
    <div class="card"><h2><span>Makes</span><span class="c">%s</span></h2>%s</div>
    <div class="card"><h2><span>Consumes</span><span class="c">%s</span></h2>%s</div>
  </div>
</div>""" % (
        _nav("/machines"),
        _esc(info["title"] or uid), "{:,}".format(detail["recipes"]), _esc(uid),
        STATE_PILL.get(st, "mut"), STATE_LABEL.get(st, st),
        _esc(info["mod"] or "?"),
        "{:,}".format(detail["makes_total"]), "{:,}".format(detail["uses_total"]),
        _esc(info["why"]),
        " &mdash; read from JEI's own &ldquo;made in&rdquo; list, so this is exact."
        if info.get("from_catalyst") else
        " &mdash; matched by name, which is a guess. JEI's exact mapping needs a "
        "<code>/recipedump</code> with mod v0.4.0.",
        cand_html,
        _toggles(uid, st, back="/machine?uid=%s" % urllib.parse.quote(uid)),
        "{:,}".format(detail["makes_total"]),
        klist(detail["makes"], detail["makes_total"], "made here"),
        "{:,}".format(detail["uses_total"]),
        klist(detail["uses"], detail["uses_total"], "consumed here"),
    )
    return _page(info["title"] or uid, body), 200


def sources_page(state):
    """What the planner treats as free, and on what evidence."""
    rows = "".join(
        "<tr><td><a class='mname' href='/plan?item=%s&qty=1'>%s</a><br><code>%s</code></td>"
        "<td class='hint2' style='margin:0'>%s</td></tr>"
        % (urllib.parse.quote(key), _item(state.graph, key), _esc(key), _esc(why))
        for key, why in sorted(state.free_sources.items()))
    known = sorted(generators_mod.DEFAULT_GENERATORS)
    unmatched = "".join(
        "<li><span class='c'></span><code>%s</code></li>" % _esc(b)
        for b in known if b not in state.placed and b not in state.have)

    body = """<div class="wrap">%s
  <div class="eyebrow">Infinite sources</div>
  <h1>What costs you nothing<span class="x">%d</span></h1>
  <div class="hint2">A resource here is treated as effectively free, so plans stop
   reconstructing it from exotic chains. Draw is still counted and reported on every plan
   &mdash; free does not mean invisible.</div>
  <div class="card"><h2><span>Free right now</span></h2>
    <table class="mach">%s</table></div>
  <div class="card"><h2><span>Known generators not in this world</span>
    <span class="c">%d</span></h2>
    <div class="hint2" style="margin:0 0 10px">Detection is a curated list, not a search:
     an input-free block has no recipe, so a recipe graph cannot find it. Add yours with
     <code>recipegraph sources --add &lt;block id&gt;=&lt;item or fluid key&gt;</code>.</div>
    <ul class="klist">%s</ul></div>
</div>""" % (
        _nav("/sources"), len(state.free_sources),
        rows or "<tr class='empty-row'><td colspan='2'>Nothing detected.</td></tr>",
        sum(1 for b in known if b not in state.placed and b not in state.have),
        unmatched or "<li class='hint2'>All known generators are present.</li>",
    )
    return _page("Infinite sources", body)


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
                # An unknown key otherwise produces a confident-looking empty plan titled
                # with the raw id, which is indistinguishable from "this item needs
                # nothing". Stale links and hand-typed ids both hit this.
                if not (key in st.graph.names or st.graph.producers(key)
                        or st.have.get(key)):
                    return self._send(_page(
                        "Unknown item",
                        "<div class='wrap'>%s<div class='eyebrow'>Not in this graph</div>"
                        "<h1>No item with that id</h1><div class='id'>%s</div>"
                        "<p class='hint2' style='margin-top:18px'>Nothing in the graph is "
                        "registered under that key, so there is no plan to draw. Search "
                        "for it by name instead.</p>"
                        "<form class='search' method='get' action='/'>"
                        "<input type='search' name='q' value='%s' autofocus>"
                        "<button type='submit'>Search</button></form></div>"
                        % (_nav(), _esc(key), _esc(key.split(":")[-1]))), 404)
                with st.lock:
                    result = st.solver().solve(key, qty)
                return self._send(_wrap_fragment(
                    "%s x%d" % (result["target_name"], qty),
                    render_html(result, st.graph)))
            if parts.path == "/machines":
                return self._send(machines_page(st, one("m"), one("q"), one("mod")))
            if parts.path == "/machine":
                page, status = machine_page(st, one("uid"))
                return self._send(page, status)
            if parts.path == "/sources":
                return self._send(sources_page(st))
            if parts.path == "/stats":
                return self._send(stats_page(st))
            if parts.path == "/favicon.ico":
                # Inline SVG, so there is no asset to serve and no console 404 that looks
                # like a real error while debugging a page.
                return self._send(
                    "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16'>"
                    "<rect width='16' height='16' rx='4' fill='#2c8a8f'/>"
                    "<path d='M4 11.5h8M5.5 11.5V6.5l2.5-1.6v6.6M9 11.5V4.9l3 1.6v5'"
                    " stroke='#fff' fill='none' stroke-width='1.4'"
                    " stroke-linecap='round' stroke-linejoin='round'/></svg>",
                    ctype="image/svg+xml")
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
        # Return the user to wherever they toggled from, so a change made on a detail page
        # does not silently bounce them to the list.
        back = (form.get("back") or ["/machines"])[0]
        if not back.startswith("/"):
            back = "/machines"        # never redirect off-site from a form field
        st = self.state
        msg = ""
        if uid and target in machines_mod.STATES:
            with st.lock:
                overrides = dict(st.overrides)
                overrides[uid] = target
                machines_mod.save_overrides(st.machines_path, overrides)
                # Costs depend on machine state, so both must be recomputed together or
                # plans would rank against stale availability.
                st.refresh_machines()
            msg = "%s set to %s" % (uid, target)
        sep = "&" if "?" in back else "?"
        self.send_response(303)
        self.send_header("Location", "%s%sm=%s" % (back, sep, urllib.parse.quote(msg)))
        self.end_headers()


def serve(graph_path, have_path, machines_path, host="127.0.0.1", port=8765,
          sources_path=None):
    state = State(graph_path, have_path, machines_path, sources_path)
    Handler.state = state
    httpd = ThreadingHTTPServer((host, port), Handler)
    return httpd, state
