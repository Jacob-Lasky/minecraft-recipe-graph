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


def _rows(entries, limit=200):
    if not entries:
        return '<tr><td class="meta">none</td></tr>'
    return "".join(
        '<tr><td class="n">%s</td><td>%s</td></tr>'
        % ("{:,}".format(e["qty"]), _esc(e["name"]))
        for e in entries[:limit]
    )


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
  <div class="eyebrow">Crafting plan &middot; MeatballCraft</div>
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
      </div>
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
        JS,
    )


def render_json(result):
    return json.dumps(result, indent=1)
