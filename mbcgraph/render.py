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
:root{--bg:#fbfbfa;--fg:#1d1c1a;--dim:#6b6862;--line:#e3e0da;--card:#fff;
--ok:#1a7f4b;--okbg:#e7f6ed;--warn:#8a6100;--warnbg:#fdf3dc;
--craft:#2a5aa8;--craftbg:#e8effa;--need:#a3272b;--needbg:#fbe9e9;}
@media (prefers-color-scheme:dark){:root{--bg:#16161a;--fg:#e9e7e2;--dim:#9b968d;
--line:#2e2e34;--card:#1e1e23;--ok:#6ede9f;--okbg:#12301f;--warn:#e8c06a;--warnbg:#332608;
--craft:#8fb6f0;--craftbg:#15243d;--need:#f09090;--needbg:#3a1516;}}
:root[data-theme=light]{--bg:#fbfbfa;--fg:#1d1c1a;--dim:#6b6862;--line:#e3e0da;--card:#fff;
--ok:#1a7f4b;--okbg:#e7f6ed;--warn:#8a6100;--warnbg:#fdf3dc;
--craft:#2a5aa8;--craftbg:#e8effa;--need:#a3272b;--needbg:#fbe9e9;}
:root[data-theme=dark]{--bg:#16161a;--fg:#e9e7e2;--dim:#9b968d;--line:#2e2e34;--card:#1e1e23;
--ok:#6ede9f;--okbg:#12301f;--warn:#e8c06a;--warnbg:#332608;--craft:#8fb6f0;--craftbg:#15243d;
--need:#f09090;--needbg:#3a1516;}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);
font:15px/1.5 ui-sans-serif,system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
.wrap{max-width:1100px;margin:0 auto;padding:28px 20px 80px}
h1{font-size:22px;margin:0 0 4px;letter-spacing:-.01em}
.sub{color:var(--dim);font-size:13px;margin-bottom:20px}
.warnbar{background:var(--warnbg);color:var(--warn);border:1px solid currentColor;
border-radius:8px;padding:10px 12px;font-size:13px;margin-bottom:18px}
.cols{display:grid;grid-template-columns:1fr;gap:18px}
@media(min-width:860px){.cols{grid-template-columns:1.35fr .95fr}}
.card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:14px 16px}
.card h2{font-size:13px;text-transform:uppercase;letter-spacing:.06em;color:var(--dim);
margin:0 0 10px;font-weight:600}
table{width:100%;border-collapse:collapse;font-size:13.5px}
td{padding:4px 0;vertical-align:top}
td.n{text-align:right;font-variant-numeric:tabular-nums;padding-right:10px;
white-space:nowrap;color:var(--dim)}
.scroll{max-height:420px;overflow:auto}
details{margin:1px 0}
summary{cursor:pointer;padding:3px 6px;border-radius:6px;list-style:none;
display:flex;gap:8px;align-items:baseline}
summary::-webkit-details-marker{display:none}
summary:hover{background:rgba(127,127,127,.09)}
.tw{color:var(--dim);width:12px;flex:0 0 12px;font-size:11px}
.qty{font-variant-numeric:tabular-nums;color:var(--dim);flex:0 0 auto;font-size:12.5px}
.nm{flex:1 1 auto;min-width:0}
.badge{flex:0 0 auto;font-size:11px;padding:1px 7px;border-radius:99px;font-weight:600}
.ok{background:var(--okbg);color:var(--ok)}.warn{background:var(--warnbg);color:var(--warn)}
.craft{background:var(--craftbg);color:var(--craft)}.need{background:var(--needbg);color:var(--need)}
.muted{background:rgba(127,127,127,.14);color:var(--dim)}
.kids{margin-left:16px;border-left:1px solid var(--line);padding-left:8px}
.meta{color:var(--dim);font-size:11.5px}
.bar{display:flex;gap:8px;margin-bottom:12px;flex-wrap:wrap}
button{font:inherit;font-size:12.5px;padding:5px 11px;border:1px solid var(--line);
background:var(--card);color:var(--fg);border-radius:7px;cursor:pointer}
button:hover{border-color:var(--dim)}
.leaf{padding:3px 6px 3px 26px;display:flex;gap:8px;align-items:baseline}
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

    return """<style>%s</style>
<div class="wrap">
  <h1>%s &times;%s</h1>
  <div class="sub">%s &middot; %s nodes resolved</div>
  %s
  <div class="bar">
    <button id="exp">Expand all</button>
    <button id="col">Collapse all</button>
    <button id="needonly" data-on="0">Show only what I need</button>
  </div>
  <div class="cols">
    <div class="card">
      <h2>Crafting tree</h2>
      <div class="tree scroll">%s</div>
    </div>
    <div>
      <div class="card" style="margin-bottom:18px">
        <h2>You still need (%d)</h2>
        <div class="scroll"><table>%s</table></div>
      </div>
      <div class="card">
        <h2>Drawn from AE2 stock (%d)</h2>
        <div class="scroll"><table>%s</table></div>
      </div>
    </div>
  </div>
</div>
<script>%s</script>""" % (
        CSS,
        _esc(result["target_name"]),
        "{:,}".format(result["qty"]),
        _esc(result["target"]),
        "{:,}".format(result["nodes"]),
        warnbar,
        _node_html(tree),
        len(need),
        _rows(need),
        len(used),
        _rows(used),
        JS,
    )


def render_json(result):
    return json.dumps(result, indent=1)
