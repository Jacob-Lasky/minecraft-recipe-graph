"""Render a solved plan as a self-contained HTML page.

Emits a fragment (inline <style>, content, inline <script>) with no <html>/<head>/
<body> wrapper. That is deliberate: browsers render it fine as a standalone file,
AND it can be published as a Claude Artifact unchanged. DO NOT add a doctype or
<html> wrapper -- the artifact publisher supplies those and would nest them.

No external assets of any kind (fonts, CDN scripts, images): artifacts run under a
strict CSP that blocks every off-host request.
"""

import html
import json

STATUS_LABEL = {
    "have": ("in stock", "ok"),
    "partial": ("part stock", "warn"),
    "craft": ("craft", "craft"),
    "raw": ("NEED", "need"),
    "source": ("infinite", "ok"),
    "cycle": ("loop", "muted"),
    "depth": ("cut off", "muted"),
    "oredict": ("any of", "muted"),
}

CSS = """
/* Palette: warm-paper / slate ground with a certus-quartz teal accent taken from
   AE2's terminal glow. The accent marks the target and structure ONLY; state is
   carried by the separate semantic ok/warn/need tokens so a status never has to
   compete with branding. Tokens are redefined for the media query AND for both
   data-theme values, because the viewer's theme toggle must win either direction. */
:root{
--bg:#faf9f7;--card:#ffffff;--fg:#1b1d20;--dim:#6f6d68;--line:#e4e1db;
--accent:#2c8a8f;--accent-soft:#e6f1f1;
--ok:#1a7f4b;--okbg:#e7f5ec;--warn:#8a6100;--warnbg:#fcf2da;
--craft:#2f5f96;--craftbg:#e8eff7;--need:#a3272b;--needbg:#fbe8e8;}
@media (prefers-color-scheme:dark){:root{
--bg:#15171a;--card:#1d2024;--fg:#e8e6e1;--dim:#989691;--line:#2e3238;
--accent:#5cc3c7;--accent-soft:#123033;
--ok:#63d998;--okbg:#122e20;--warn:#e5bd6a;--warnbg:#31250a;
--craft:#8db4e6;--craftbg:#15243a;--need:#ef8f8f;--needbg:#391618;}}
:root[data-theme=light]{
--bg:#faf9f7;--card:#ffffff;--fg:#1b1d20;--dim:#6f6d68;--line:#e4e1db;
--accent:#2c8a8f;--accent-soft:#e6f1f1;
--ok:#1a7f4b;--okbg:#e7f5ec;--warn:#8a6100;--warnbg:#fcf2da;
--craft:#2f5f96;--craftbg:#e8eff7;--need:#a3272b;--needbg:#fbe8e8;}
:root[data-theme=dark]{
--bg:#15171a;--card:#1d2024;--fg:#e8e6e1;--dim:#989691;--line:#2e3238;
--accent:#5cc3c7;--accent-soft:#123033;
--ok:#63d998;--okbg:#122e20;--warn:#e5bd6a;--warnbg:#31250a;
--craft:#8db4e6;--craftbg:#15243a;--need:#ef8f8f;--needbg:#391618;}

*{box-sizing:border-box}
/* Mono is the utility face and carries every quantity and registry id: those
   genuinely are code, and tabular digits are what make the ledgers scannable. */
:root{--sans:ui-sans-serif,system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;
--mono:ui-monospace,SFMono-Regular,"SF Mono",Menlo,Consolas,monospace}
body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.55 var(--sans);
-webkit-font-smoothing:antialiased}
.wrap{max-width:1180px;margin:0 auto;padding:32px 22px 88px}

.eyebrow{font:600 11px/1 var(--mono);letter-spacing:.14em;text-transform:uppercase;
color:var(--accent);margin-bottom:10px}
h1{font-size:27px;line-height:1.15;margin:0;letter-spacing:-.022em;font-weight:640;
text-wrap:balance}
h1 .x{font-family:var(--mono);font-size:19px;font-weight:500;color:var(--dim);
letter-spacing:0;margin-left:8px}
.id{font:12.5px/1 var(--mono);color:var(--dim);margin-top:8px;word-break:break-all}

/* Summary before detail: the four numbers that decide whether to read further. */
.stats{display:grid;grid-template-columns:repeat(2,1fr);gap:1px;background:var(--line);
border:1px solid var(--line);border-radius:11px;overflow:hidden;margin:22px 0 20px}
@media(min-width:640px){.stats{grid-template-columns:repeat(4,1fr)}}
.stat{background:var(--card);padding:13px 15px}
.stat .k{font:600 10.5px/1 var(--mono);letter-spacing:.1em;text-transform:uppercase;
color:var(--dim)}
.stat .v{font:500 21px/1.25 var(--mono);margin-top:7px;font-variant-numeric:tabular-nums}
.stat.need .v{color:var(--need)}.stat.ok .v{color:var(--ok)}

.warnbar{background:var(--warnbg);color:var(--warn);border:1px solid currentColor;
border-radius:9px;padding:11px 13px;font-size:13.5px;margin-bottom:20px}
.cols{display:grid;grid-template-columns:1fr;gap:20px;align-items:start}
@media(min-width:900px){.cols{grid-template-columns:1.45fr .95fr}}
.card{background:var(--card);border:1px solid var(--line);border-radius:11px;
padding:15px 17px}
.card+.card{margin-top:20px}
.card h2{font:600 10.5px/1 var(--mono);letter-spacing:.11em;text-transform:uppercase;
color:var(--dim);margin:0 0 12px;display:flex;justify-content:space-between;gap:10px}
.card h2 .c{color:var(--accent)}

table{width:100%;border-collapse:collapse;font-size:13.5px}
tr+tr td{border-top:1px solid var(--line)}
td{padding:6px 0;vertical-align:baseline}
td.n{text-align:right;font-family:var(--mono);font-variant-numeric:tabular-nums;
padding-right:12px;white-space:nowrap;width:1%}
.scroll{max-height:460px;overflow:auto}
.tree{overflow-x:auto}

details{margin:0}
summary{cursor:pointer;padding:4px 6px;border-radius:7px;list-style:none;
display:flex;gap:9px;align-items:baseline}
summary::-webkit-details-marker{display:none}
summary:hover{background:var(--accent-soft)}
summary:focus-visible{outline:2px solid var(--accent);outline-offset:1px}
.tw{color:var(--dim);flex:0 0 11px;font-size:10px;transition:transform .12s ease}
details[open]>summary .tw{transform:rotate(90deg)}
@media(prefers-reduced-motion:reduce){.tw{transition:none}}
.qty{font-family:var(--mono);font-variant-numeric:tabular-nums;color:var(--dim);
flex:0 0 auto;font-size:12.5px}
.nm{flex:1 1 auto;min-width:0}
.badge{flex:0 0 auto;font:600 10.5px/1.7 var(--mono);padding:1px 8px;border-radius:99px;
letter-spacing:.03em;white-space:nowrap}
.ok{background:var(--okbg);color:var(--ok)}.warn{background:var(--warnbg);color:var(--warn)}
.craft{background:var(--craftbg);color:var(--craft)}
.need{background:var(--needbg);color:var(--need)}
.muted{background:rgba(127,127,127,.15);color:var(--dim)}
.kids{margin-left:15px;border-left:1px solid var(--line);padding-left:9px}
.meta{color:var(--dim);font-size:11.5px}
.bar{display:flex;gap:9px;margin-bottom:16px;flex-wrap:wrap}
button{font:500 12.5px var(--sans);padding:6px 12px;border:1px solid var(--line);
background:var(--card);color:var(--fg);border-radius:8px;cursor:pointer}
button:hover{border-color:var(--accent);color:var(--accent)}
button:focus-visible{outline:2px solid var(--accent);outline-offset:1px}
.leaf{padding:4px 6px 4px 26px;display:flex;gap:9px;align-items:baseline}
.foot{margin-top:26px;font-size:12.5px;color:var(--dim);border-top:1px solid var(--line);
padding-top:14px}
"""

JS = """
function setAll(open){document.querySelectorAll('.tree details')
  .forEach(function(d){d.open=open});}
document.getElementById('exp').onclick=function(){setAll(true)};
document.getElementById('col').onclick=function(){setAll(false)};
document.getElementById('needonly').onclick=function(){
  var on=this.dataset.on==='1';this.dataset.on=on?'0':'1';
  this.textContent=on?'Show only what I need':'Show everything';
  document.querySelectorAll('.tree [data-hasneed=0]').forEach(function(e){
    e.style.display=on?'':'none';});
};
"""


def _esc(s):
    return html.escape(str(s), quote=True)


def _has_need(node):
    if node.get("status") == "raw":
        return True
    return any(_has_need(c) for c in node.get("children") or ())


def _node_html(node, depth=0):
    status = node.get("status", "craft")
    label, cls = STATUS_LABEL.get(status, (status, "muted"))
    kids = node.get("children") or []
    need_flag = 1 if _has_need(node) else 0

    bits = [
        '<span class="qty">%s&times;</span>' % "{:,}".format(node.get("need", 1)),
        '<span class="nm">%s' % _esc(node.get("name") or node.get("key")),
    ]
    extra = []
    if node.get("from_stock"):
        extra.append("%s from stock" % "{:,}".format(node["from_stock"]))
    if node.get("machine") and node.get("machine") != node.get("category"):
        extra.append(_esc(node["machine"]))
    elif node.get("category") and not str(node["category"]).startswith("crafting"):
        extra.append(_esc(node["category"]))
    if node.get("alternatives", 0) > 1:
        extra.append("%d recipes" % node["alternatives"])
    if node.get("note"):
        extra.append(_esc(node["note"]))
    if node.get("resolved_to"):
        extra.append("&rarr; %s" % _esc(node["resolved_to"]))
    if extra:
        bits.append(' <span class="meta">%s</span>' % " &middot; ".join(extra))
    bits.append("</span>")
    bits.append('<span class="badge %s">%s</span>' % (cls, label))
    inner = "".join(bits)

    if not kids:
        return '<div class="leaf" data-hasneed="%d">%s</div>' % (need_flag, inner)

    open_attr = " open" if depth < 2 else ""
    return (
        '<details data-hasneed="%d"%s><summary><span class="tw">&#9656;</span>%s</summary>'
        '<div class="kids">%s</div></details>'
        % (need_flag, open_attr, inner, "".join(_node_html(k, depth + 1) for k in kids))
    )


MACHINE_STATE_CLASS = {"buildable": "warn", "unknown": "muted", "unavailable": "need"}


def _machines_html(machines):
    """Machines the plan routes through that the player does not have yet.

    Shown as its own panel rather than folded into the shopping list: a machine is a
    one-off prerequisite, not a consumed quantity, and conflating them made plans read as
    though you needed 4,000 of something you actually build once.

    `unknown` is rendered muted, not red: it means this tool could not work out which block
    the category corresponds to, so it is a caveat about the plan, not a task for the
    player. Colouring it like `unavailable` would send someone hunting for a machine they
    may well already own.
    """
    if not machines:
        return ""
    rows = "".join(
        '<tr><td>%s</td><td><span class="badge %s">%s</span></td></tr>'
        % (_esc(m.get("machine") or m.get("category")),
           MACHINE_STATE_CLASS.get(m.get("state"), "need"),
           _esc("unidentified" if m.get("state") == "unknown" else m.get("state", "?")))
        for m in machines)
    unknowns = sum(1 for m in machines if m.get("state") == "unknown")
    note = ('<div class="meta" style="margin-top:9px">%d of these could not be matched to '
            'a block, so availability is a guess. You may already have them.</div>'
            % unknowns) if unknowns else ""
    return ('<div class="card"><h2><span>Machines to build first</span>'
            '<span class="c">%d</span></h2><div class="scroll"><table>%s</table></div>%s'
            '</div>' % (len(machines) - unknowns, rows, note))


def _rows(entries, limit=200):
    if not entries:
        return '<tr><td class="meta">none</td></tr>'
    return "".join(
        '<tr><td class="n">%s</td><td>%s%s</td></tr>'
        % ("{:,}".format(e["qty"]), _esc(e["name"]),
           (' <span class="meta">%s</span>' % _esc(e["why"])) if e.get("why") else "")
        for e in entries[:limit]
    )


def _sources_html(entries):
    """Draw from infinite generators.

    Its own panel, and always shown when non-empty, because "free" must not mean
    "invisible": a plan that quietly consumed 64,000 buckets of water would read as though
    it needed nothing. The quantity is the useful signal even when the cost is zero.
    """
    if not entries:
        return ""
    total = sum(e["qty"] for e in entries)
    return ('<div class="card"><h2><span>Drawn from infinite sources</span>'
            '<span class="c">%s</span></h2><div class="scroll"><table>%s</table></div>'
            '</div>' % ("{:,}".format(total), _rows(entries)))


def render_html(result, graph=None, coverage_note=None):
    tree = result["tree"]
    need = result.get("shopping_list") or []
    used = result.get("used_from_stock") or []

    warn = []
    if result.get("truncated"):
        warn.append(
            "Tree hit the node cap (%d) and was cut off; deeper branches are "
            "incomplete. Raise --max-nodes to see more." % result["nodes"]
        )
    if coverage_note:
        warn.append(coverage_note)
    warnbar = (
        '<div class="warnbar">%s</div>' % "<br>".join(warn) if warn else ""
    )

    total_need = sum(e["qty"] for e in need)
    total_used = sum(e["qty"] for e in used)

    return """<style>%s</style>
<div class="wrap">
  <div class="eyebrow">Crafting plan</div>
  <h1>%s<span class="x">&times;%s</span></h1>
  <div class="id">%s</div>

  <div class="stats">
    <div class="stat"><div class="k">Steps resolved</div><div class="v">%s</div></div>
    <div class="stat need"><div class="k">Still needed</div><div class="v">%s</div></div>
    <div class="stat ok"><div class="k">From AE2 stock</div><div class="v">%s</div></div>
    <div class="stat"><div class="k">Distinct inputs</div><div class="v">%s</div></div>
  </div>
  %s
  <div class="bar">
    <button id="exp">Expand all</button>
    <button id="col">Collapse all</button>
    <button id="needonly" data-on="0">Show only what I need</button>
  </div>
  <div class="cols">
    <div class="card">
      <h2><span>Crafting tree</span><span class="c">%s steps</span></h2>
      <div class="tree scroll">%s</div>
    </div>
    <div>
      <div class="card">
        <h2><span>You still need</span><span class="c">%d</span></h2>
        <div class="scroll"><table>%s</table></div>
      </div>
      <div class="card">
        <h2><span>Drawn from AE2 stock</span><span class="c">%d</span></h2>
        <div class="scroll"><table>%s</table></div>
      </div>%s%s
    </div>
  </div>
  <div class="foot">Recipe chain resolved offline from the installed pack; stock read
  from the AE2 network in the world save. Items marked <b>NEED</b> have no known
  recipe in the current graph &mdash; which may mean the recipe is a machine recipe
  not yet dumped, rather than that none exists.</div>
</div>
<script>%s</script>""" % (
        CSS,
        _esc(result["target_name"]),
        "{:,}".format(result["qty"]),
        _esc(result["target"]),
        "{:,}".format(result["nodes"]),
        "{:,}".format(total_need),
        "{:,}".format(total_used),
        "{:,}".format(len(need) + len(used)),
        warnbar,
        "{:,}".format(result["nodes"]),
        _node_html(tree),
        len(need),
        _rows(need),
        len(used),
        _rows(used),
        _sources_html(result.get("from_sources")),
        _machines_html(result.get("machines_to_build")),
        JS,
    )


def render_json(result):
    return json.dumps(result, indent=1)


EXPLORE_CSS = """
.res{border:1px solid var(--line);border-radius:11px;background:var(--card);
padding:15px 17px;margin-bottom:14px}
.res>header{display:flex;gap:12px;align-items:baseline;flex-wrap:wrap;
padding-bottom:11px;border-bottom:1px solid var(--line);margin-bottom:12px}
.res h3{margin:0;font-size:17px;letter-spacing:-.012em;font-weight:620}
.rid{font:11.5px/1 var(--mono);color:var(--dim);word-break:break-all}
.chips{display:flex;gap:6px;flex-wrap:wrap;margin-left:auto}
.chip{font:600 10.5px/1.7 var(--mono);padding:1px 8px;border-radius:99px;
background:rgba(127,127,127,.15);color:var(--dim);white-space:nowrap}
.chip.stock{background:var(--okbg);color:var(--ok)}
.chip.none{background:var(--needbg);color:var(--need)}
.chip.ore{background:var(--accent-soft);color:var(--accent)}
.two{display:grid;grid-template-columns:1fr;gap:16px}
@media(min-width:800px){.two{grid-template-columns:1fr 1fr}}
.sect h4{font:600 10.5px/1 var(--mono);letter-spacing:.11em;text-transform:uppercase;
color:var(--dim);margin:0 0 9px;display:flex;justify-content:space-between}
.rec{border-left:2px solid var(--line);padding:2px 0 2px 10px;margin-bottom:9px}
.rec .via{font:11.5px/1.5 var(--mono);color:var(--accent)}
.ing{display:flex;gap:8px;align-items:baseline;font-size:13.5px;padding:1px 0}
.ing .q{font-family:var(--mono);font-variant-numeric:tabular-nums;color:var(--dim);
font-size:12.5px;flex:0 0 auto;min-width:34px;text-align:right}
.ing .s{font:10.5px/1.7 var(--mono);padding:0 6px;border-radius:99px;flex:0 0 auto}
.ing .s.y{background:var(--okbg);color:var(--ok)}
.ing .s.n{background:var(--needbg);color:var(--need)}
.alt{color:var(--dim);font-size:11.5px}
.empty{color:var(--dim);font-size:13px;font-style:italic}
.filter{width:100%;font:15px var(--sans);padding:10px 13px;border:1px solid var(--line);
border-radius:9px;background:var(--card);color:var(--fg);margin-bottom:18px}
.filter:focus{outline:2px solid var(--accent);outline-offset:-1px;border-color:var(--accent)}
"""

EXPLORE_JS = """
var box=document.getElementById('filter');
box.addEventListener('input',function(){
  var q=this.value.toLowerCase().trim();
  var shown=0;
  document.querySelectorAll('.res').forEach(function(el){
    var hit=!q||el.dataset.hay.indexOf(q)>=0;
    el.style.display=hit?'':'none';
    if(hit)shown++;
  });
  document.getElementById('count').textContent=shown;
});
"""


def _ing_html(ing):
    alts = ing["alts"]
    first = alts[0]
    have = first.get("stock", 0)
    pill = ('<span class="s y">%s</span>' % "{:,}".format(have)) if have else (
        '<span class="s n">0</span>')
    extra = ""
    if ing.get("alt_total", 1) > 1:
        others = ", ".join(_esc(a["name"]) for a in alts[1:4])
        extra = ' <span class="alt">or %d more%s</span>' % (
            ing["alt_total"] - 1, (": " + others) if others else "")
    unit = " mB" if ing.get("role") == "fluid" else ""
    return '<div class="ing"><span class="q">%s%s</span>%s<span>%s%s</span></div>' % (
        "{:,}".format(ing["qty"]), unit, pill, _esc(first["name"]), extra)


def _makes_html(item):
    if not item["makes"]:
        return '<div class="empty">No known recipe in this graph.</div>'
    out = []
    for rec in item["makes"]:
        via = rec.get("machine") or rec["category"]
        yields = ", ".join(
            "%s&times; %s" % ("{:,}".format(o["qty"]), _esc(o["name"]))
            for o in rec["outputs"][:3])
        out.append('<div class="rec"><div class="via">%s &rarr; %s</div>%s</div>'
                   % (_esc(via), yields, "".join(_ing_html(i) for i in rec["inputs"])))
    if item["makes_total"] > len(item["makes"]):
        out.append('<div class="alt">+%d more recipes not shown</div>'
                   % (item["makes_total"] - len(item["makes"])))
    return "".join(out)


def _used_html(item):
    if not item["used_in"]:
        return '<div class="empty">Not an input to anything in this graph.</div>'
    out = []
    for rec in item["used_in"]:
        via = rec.get("machine") or rec["category"]
        outs = ", ".join(_esc(o["name"]) for o in rec["outputs"][:2]) or "?"
        out.append('<div class="ing"><span class="q">&rarr;</span>'
                   '<span>%s <span class="alt">via %s</span></span></div>'
                   % (outs, _esc(via)))
    if item["used_in_total"] > len(item["used_in"]):
        out.append('<div class="alt">+%d more uses not shown</div>'
                   % (item["used_in_total"] - len(item["used_in"])))
    return "".join(out)


def _res_html(item):
    stock = item.get("stock", 0)
    chips = []
    chips.append('<span class="chip %s">%s in stock</span>'
                 % ("stock" if stock else "none", "{:,}".format(stock)))
    chips.append('<span class="chip">%d recipe%s</span>'
                 % (item["makes_total"], "" if item["makes_total"] == 1 else "s"))
    chips.append('<span class="chip">%d use%s</span>'
                 % (item["used_in_total"], "" if item["used_in_total"] == 1 else "s"))
    for ore in item.get("oredicts", [])[:3]:
        mark = "?" if ore in item.get("oredict_guessed", []) else ""
        chips.append('<span class="chip ore">%s%s</span>' % (_esc(ore), mark))

    hay = (item["name"] + " " + item["key"] + " " + " ".join(item.get("oredicts", []))).lower()
    return """<div class="res" data-hay="%s">
  <header><h3>%s</h3><span class="rid">%s</span><span class="chips">%s</span></header>
  <div class="two">
    <div class="sect"><h4><span>Made by</span><span>%d</span></h4>%s</div>
    <div class="sect"><h4><span>Used in</span><span>%d</span></h4>%s</div>
  </div>
</div>""" % (
        _esc(hay), _esc(item["name"]), _esc(item["key"]), "".join(chips),
        item["makes_total"], _makes_html(item),
        item["used_in_total"], _used_html(item),
    )


def render_explore_html(payload, coverage_note=None):
    results = payload["results"]
    warn = ('<div class="warnbar">%s</div>' % _esc(coverage_note)) if coverage_note else ""
    return """<style>%s%s</style>
<div class="wrap">
  <div class="eyebrow">Item explorer</div>
  <h1>%s<span class="x">%d match%s</span></h1>
  <div class="id">Searched %s item names &middot; ? on an oredict chip means membership was
  inferred from display names, not read from the game</div>
  %s
  <input id="filter" class="filter" type="search"
         placeholder="Narrow these results&hellip;" autocomplete="off">
  <div class="stats">
    <div class="stat"><div class="k">Matches</div><div class="v" id="count">%d</div></div>
    <div class="stat ok"><div class="k">You have some of</div><div class="v">%d</div></div>
    <div class="stat"><div class="k">Craftable</div><div class="v">%d</div></div>
    <div class="stat need"><div class="k">No known recipe</div><div class="v">%d</div></div>
  </div>
  %s
  <div class="foot">"No known recipe" usually means a machine recipe that has not been
  dumped yet, not that the item is uncraftable. Run <code>/recipedump</code> to add machine
  and furnace recipes to the graph.</div>
</div>
<script>%s</script>""" % (
        CSS, EXPLORE_CSS,
        _esc(payload["query"]),
        len(results), "" if len(results) == 1 else "es",
        "{:,}".format(payload.get("searched", 0)),
        warn,
        len(results),
        sum(1 for r in results if r.get("stock")),
        sum(1 for r in results if r["makes_total"]),
        sum(1 for r in results if not r["makes_total"]),
        "".join(_res_html(r) for r in results),
        EXPLORE_JS,
    )
