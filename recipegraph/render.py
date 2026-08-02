"""Render a solved plan as a self-contained HTML page.

Emits a fragment (inline <style>, content, inline <script>) with no <html>/<head>/
<body> wrapper. That is deliberate: browsers render it fine as a standalone file,
AND it can be published as a Claude Artifact unchanged. DO NOT add a doctype or
<html> wrapper -- the artifact publisher supplies those and would nest them.

No external assets of any kind (fonts, CDN scripts, images): artifacts run under a
strict CSP that blocks every off-host request.
"""

import json
import urllib.parse

from .graphview import (DIAGRAM_CSS, LR, ORIENTATION_LABEL, TD,
                        render_diagrams)
from .htmlutil import esc as _esc
from .htmlutil import script_json
from .htmlutil import machine_href
from . import tokens as tokens_mod
from .solve import STATUS_RAW, STATUS_TOKEN
from .pins import EXACT as PIN_EXACT
from .present import (KIND_CHIP, STATE_BADGE, STATE_LABEL, STATUS_LABEL, hidden_note,
                      is_roadblock, pin_badge, status_badge)

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
/* The "no state worth colouring" grey, for loops, cut-off branches and oredict stand-ins.
   NOT in the four theme blocks above, and that is the point: grey at 15% alpha reads the
   same on paper and on slate, so one definition serves both and there is no pair of values
   to keep in step. It lives here because `present.STATUS_STYLE` names this token for the
   diagram while `.muted`, `.chip` and `.pill.mut` use it for badges, and the value was
   written out four times before, so changing the badge grey left the diagram box a
   different grey with nothing failing. */
:root{--mutedbg:rgba(127,127,127,.15)}
body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.55 var(--sans);
-webkit-font-smoothing:antialiased}
.wrap{max-width:1180px;margin:0 auto;padding:32px 22px 88px}

.eyebrow{font:600 11px/1 var(--mono);letter-spacing:.14em;text-transform:uppercase;
color:var(--accent);margin-bottom:10px}
h1{font-size:27px;line-height:1.15;margin:0;letter-spacing:-.022em;font-weight:640;
text-wrap:balance}
h1 .x{font-family:var(--mono);font-size:19px;font-weight:500;color:var(--dim);
letter-spacing:0;margin-left:8px}
/* `overflow-wrap:anywhere`, NOT `word-break:break-all`. Both break an unbreakable registry
   id, which is what this rule is for -- but `break-all` breaks EVERYTHING, and this element
   also carries the search page's prose note, which came out as "47 hidd/en" and "read fro/m
   display names" at 390px. `anywhere` breaks only when there is no other opportunity, so a
   long id still breaks and a sentence still breaks at its spaces. Same property `.nm` uses
   for the same reason. */
.id{font:12.5px/1 var(--mono);color:var(--dim);margin-top:8px;overflow-wrap:anywhere}

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
/* A grid item defaults to min-width:auto, so wide content inside it (the plan diagram's
   SVG carries its natural width) pushes the track out and the PAGE scrolls sideways
   instead of the container. This one line is what keeps the overflow where it belongs. */
.cols>*{min-width:0}
@media(min-width:900px){.cols.single{grid-template-columns:1fr}}
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
/* HOVER IS GATED ON `hover:hover` THROUGHOUT THIS SHEET, AND MUST STAY THAT WAY.
   A touch browser has no pointer to move away, so it leaves `:hover` applied to the last
   thing tapped until you tap something else. `button:hover` sets exactly the accent
   border and text colour that `[aria-pressed=true]` does, so unselecting a state chip
   cleared its background and left it looking selected. Reported as "the background turns
   off but it still remains highlighted". Any new `:hover` rule needs the same wrapper. */
@media(hover:hover){summary:hover{background:var(--accent-soft)}}
summary:focus-visible{outline:2px solid var(--accent);outline-offset:1px}
.tw{color:var(--dim);flex:0 0 11px;font-size:10px;transition:transform .12s ease}
details[open]>summary .tw{transform:rotate(90deg)}
@media(prefers-reduced-motion:reduce){.tw{transition:none}}
.qty{font-family:var(--mono);font-variant-numeric:tabular-nums;color:var(--dim);
flex:0 0 auto;font-size:12.5px}
/* `min-width:0` lets the flex item shrink; `overflow-wrap` is what makes it actually
   break. Registry ids and machine names have no spaces to break at, so without this a
   single long one pushes the row past the viewport however narrow the column gets. */
.nm{flex:1 1 auto;min-width:0;overflow-wrap:anywhere}
/* The item icon (#36), in both delivery shapes: a <span> whose background is a served
   atlas page, or an inlined <img>. ONE rule for both, so a plan looks the same on the
   server and in a published artifact.
   `image-rendering:pixelated` is load-bearing rather than a nicety: these are 16x16
   sprites and a browser's default smoothing turns Minecraft pixel art into mush at any
   scale, including 1:1 on a fractional-DPI display.
   `flex:0 0 auto` because the row is a flex container and the icon must never be the
   thing that shrinks; `vertical-align:-3px` sits it on the text baseline in the table
   rows, which are not flex. */
.ico{display:inline-block;flex:0 0 auto;margin-right:6px;vertical-align:-3px;
image-rendering:pixelated;background-repeat:no-repeat}
.badge{flex:0 0 auto;font:600 10.5px/1.7 var(--mono);padding:1px 8px;border-radius:99px;
letter-spacing:.03em;white-space:nowrap}
.ok{background:var(--okbg);color:var(--ok)}.warn{background:var(--warnbg);color:var(--warn)}
.craft{background:var(--craftbg);color:var(--craft)}
.need{background:var(--needbg);color:var(--need)}
.muted{background:var(--mutedbg);color:var(--dim)}
/* Grouped rows in the "Not crafted, obtained" panel. The group heading is a row rather
   than a nested table so the quantity column still lines up across every group. */
.tgroup{font:600 10px/1 var(--mono);letter-spacing:.09em;text-transform:uppercase;
color:var(--dim);padding-top:11px}
tr:first-child>.tgroup{padding-top:0}
.tname{padding-left:2px}
.kids{margin-left:15px;border-left:1px solid var(--line);padding-left:9px}
.meta{color:var(--dim);font-size:11.5px}
/* Machine links, in the tree's meta line and in the machines panel. `color:inherit` and no
   underline, matching `.crumb a`: a default blue underlined link inside dim 11.5px prose
   outshouts the item name it sits behind, and there are one of these per craft node. The
   dotted rule is what still says it is clickable. */
.mlink{color:inherit;text-decoration:none;border-bottom:1px dotted currentColor}
@media(hover:hover){.mlink:hover{color:var(--accent);border-bottom-style:solid}}
.mlink:focus-visible{outline:2px solid var(--accent);outline-offset:1px}
.bar{display:flex;gap:9px;margin-bottom:16px;flex-wrap:wrap}
button{font:500 12.5px var(--sans);padding:6px 12px;border:1px solid var(--line);
background:var(--card);color:var(--fg);border-radius:8px;cursor:pointer}
@media(hover:hover){button:hover{border-color:var(--accent);color:var(--accent)}}
button:focus-visible{outline:2px solid var(--accent);outline-offset:1px}
.leaf{padding:4px 6px 4px 26px;display:flex;gap:9px;align-items:baseline}
.foot{margin-top:26px;font-size:12.5px;color:var(--dim);border-top:1px solid var(--line);
padding-top:14px}

/* Type chips replace the "[fluid]" text prefix. Reading the same bracketed word down a
   hundred rows is fatiguing, and a chip is scannable at a glance. These hues are separate
   from the semantic ok/warn/need tokens on purpose: a fluid is not a *status*, so it must
   not compete with one, and it must not be the accent either, which marks structure. */
:root{--fluidbg:#dceaf5;--fluidfg:#1d5c86;--essbg:#ece0f4;--essfg:#6b3f92;
--orebg:#e9e6dc;--orefg:#6d6146}
@media(prefers-color-scheme:dark){:root{
--fluidbg:#12293c;--fluidfg:#7cc0ea;--essbg:#291a38;--essfg:#c39ce8;
--orebg:#2b2823;--orefg:#c0b394}}
:root[data-theme=light]{--fluidbg:#dceaf5;--fluidfg:#1d5c86;--essbg:#ece0f4;
--essfg:#6b3f92;--orebg:#e9e6dc;--orefg:#6d6146}
:root[data-theme=dark]{--fluidbg:#12293c;--fluidfg:#7cc0ea;--essbg:#291a38;
--essfg:#c39ce8;--orebg:#2b2823;--orefg:#c0b394}
.t{font:600 9.5px/1.7 var(--mono);letter-spacing:.06em;text-transform:uppercase;
padding:1px 6px;border-radius:5px;flex:0 0 auto;vertical-align:2px;margin-right:6px;
display:inline-block}
.t-fluid{background:var(--fluidbg);color:var(--fluidfg)}
.t-essentia{background:var(--essbg);color:var(--essfg)}
.t-ore{background:var(--orebg);color:var(--orefg)}

/* PHONE. Every rule here answers something measured on a 390px viewport, not something
   imagined, and the numbers in the comments are from that measurement.

   LAST IN THE SHEET ON PURPOSE. These are overrides of the rules above and they win by
   cascade order, so moving this block earlier silently disables half of it. */
@media(max-width:640px){
/* 32px of side padding on a 390px screen is a sixth of the width, and the header stack
   (eyebrow, h1, id, four stat tiles, four buttons) pushed the first tree node 1,030px
   down: past the fold before a single answer was visible. */
.wrap{padding:18px 15px 64px}
h1{font-size:22px}
h1 .x{font-size:16px;margin-left:6px}
.id{margin-top:6px}
.stats{margin:15px 0 14px}
.stat{padding:10px 12px}
.stat .v{font-size:18px;margin-top:5px}
.stat .k{font-size:9.5px}

/* The tree indented 24px per level, so a depth-5 node had 270px left to hold a quantity,
   a name, a machine and a badge, and nearly every node wrapped to three or four lines.
   14px still reads as a step without spending the screen on it. */
.kids{margin-left:6px;padding-left:8px}
.leaf{padding-left:16px}

/* The machine name and recipe count flowed inline after the item name, so "Borax
   Solution" and "Recursive Processor: Chemical Reactor - 5 recipes" wrapped together as
   one paragraph. On its own line each is a separate thing to scan. */
.meta{display:block;margin-top:1px;line-height:1.45}

/* These rows were 24px high. 44px is the tap-target floor, and a plan is mostly a list
   of things you are trying to tap. */
summary,.leaf{padding-top:9px;padding-bottom:9px;gap:8px}

/* THE ROW WRAPS, AND `.nm` KEEPS A FLOOR, because a flex item with `min-width:0` will
   shrink below its own ATOMIC children and then they overflow it silently. Measured on the
   live plan at 390px: a deep node carrying an item icon and a wide badge squeezed `.nm` to
   a 6px content box around a 16px sprite -- `scrollWidth` 22 against `clientWidth` 6. Every
   bounding rect looked fine and the page did not scroll, which is exactly the failure mode
   `scrollWidth` is measured for.
   `min-width:0` stays: it is what lets a long registry id break at all (see `.nm` above).
   The floor is on the phone block only, because the crush needs a narrow row to happen. */
summary,.leaf{flex-wrap:wrap}
.nm{min-width:9em}
.tw{flex:0 0 14px;font-size:12px}
button{padding:9px 13px;font-size:13px;min-height:40px}
.filter{min-height:44px}
.bar{gap:7px;margin-bottom:14px}
}
"""


def kind_chip(kind):
    label = KIND_CHIP.get(kind)
    if not label:
        return ""
    return '<span class="t t-%s">%s</span>' % (kind, label)


def named(entry, icon=None):
    """Escaped display name with a type chip in front, and an item icon when there is one.

    Reads the `kind` and `label` the solver and explorer now put on every node, so no
    renderer needs a Graph in hand. On an older payload that has only `name`, it degrades
    to the bracketed text form rather than losing the type entirely.

    THE ICON IS A CALLABLE PASSED IN, NOT A MODULE-LEVEL SETTING, and the server is why: it
    is threaded, and a "current delivery shape" global would let one request's plan borrow
    another's. It is also what keeps `render_html` usable for both the standalone document
    and the served fragment, which have incompatible constraints. See iconset.resolver.

    The icon goes BEFORE the type chip, so a column of rows has its pictures aligned on one
    edge; and it never replaces the chip, because "this is a fluid" is a distinction the
    picture cannot draw and the chip exists for. `kind_chip` returns "" for a plain item, so
    on the common row the two are simply adjacent.
    """
    label = entry.get("label")
    art = icon(entry.get("key") or "") if (icon and entry.get("key")) else ""
    if label is None:
        return art + _esc(entry.get("name") or entry.get("key") or "")
    return art + kind_chip(entry.get("kind")) + _esc(label)

JS = """
function setAll(open){document.querySelectorAll('.tree details')
  .forEach(function(d){d.open=open});}
document.getElementById('exp').onclick=function(){setAll(true)};
document.getElementById('col').onclick=function(){setAll(false)};
// The two tree filters are MUTUALLY EXCLUSIVE on purpose. Both narrow the same tree by
// setting `display`, so with both switched on, turning one off would leave nodes hidden
// while its own button reads "Show every step". One at a time keeps every label honest and
// keeps the hide decision in a single pass.
(function(){
  var FILTERS={needonly:{attr:'hasneed',off:'Show only what I need'},
               blockedonly:{attr:'blocked',off:'Show only blocked steps'}};
  var active=null;
  function apply(){
    var attr=active?FILTERS[active].attr:null;
    document.querySelectorAll('.tree [data-hasneed]').forEach(function(e){
      e.style.display=(attr&&e.dataset[attr]==='0')?'none':'';});
    Object.keys(FILTERS).forEach(function(id){
      var b=document.getElementById(id);
      if(!b)return;
      var on=id===active;
      b.dataset.on=on?'1':'0';
      b.setAttribute('aria-pressed',String(on));
      b.textContent=on?'Show every step':FILTERS[id].off;});
  }
  Object.keys(FILTERS).forEach(function(id){
    var b=document.getElementById(id);
    if(b)b.onclick=function(){active=(active===id)?null:id;apply();};});
})();
// Orientation. Jake: "the tree should be able to go left to right OR top to bottom."
// Both SVGs are already in the DOM, so this sets one attribute -- no re-render, no round
// trip, and nothing to re-solve. Remembered next to the favourites, because an orientation
// is a reading preference and having to set it again on every plan would make it useless.
(function(){
  var btn=document.getElementById('dir'), wrap=document.getElementById('diagwrap'),
      name=document.getElementById('dirname'), KEY='rg.diagdir',
      // Injected from graphview.ORIENTATION_LABEL, not restated. The caption, the button
      // and the SVG's aria-label all name the orientation, and a second copy here is how
      // three surfaces end up calling one thing two things.
      WORDS=%%DIRS%%;
  if(!btn||!wrap)return;
  function set(dir){
    if(dir!=='td')dir='lr';                        // a hand-edited localStorage value
    wrap.dataset.dir=dir;
    if(name)name.textContent=WORDS[dir];
    btn.textContent='Turn it '+WORDS[dir==='lr'?'td':'lr'];
    btn.setAttribute('aria-pressed',String(dir==='td'));
    try{localStorage.setItem(KEY,dir);}catch(e){}
  }
  var saved=null;
  try{saved=localStorage.getItem(KEY);}catch(e){}
  // Only when it differs, so the server-rendered default costs no work on load.
  if(saved==='td')set('td');
  btn.onclick=function(){set(wrap.dataset.dir==='lr'?'td':'lr');};
})();
(function(){
  var btn=document.getElementById('diag'), tree=document.getElementById('treebox'),
      diag=document.getElementById('diagbox'), cols=document.getElementById('cols');
  if(!btn||!diag)return;
  btn.onclick=function(){
    var showing=diag.hidden;
    diag.hidden=!showing; tree.hidden=showing;
    // The flow view needs the full width to be worth looking at, so the two-column grid
    // collapses to one while it is open and the ledgers sit underneath it.
    cols.classList.toggle('single',showing);
    btn.textContent=showing?'Show as list':'Show as diagram';
    btn.setAttribute('aria-pressed',String(showing));
  };
})();
"""


def _has_need(node):
    """Whether this node or anything below it is still on the player to obtain.

    STATUS_TOKEN counts as well as STATUS_RAW. A placeholder leaves the shopping list, but
    the button says "show only what I need" and a Dungeon Drop is emphatically something
    you need: filtering it out would answer "4 Screws" to a plan that also wants two
    afternoons of loot.
    """
    if node.get("status") in (STATUS_RAW, STATUS_TOKEN):
        return True
    return any(_has_need(c) for c in node.get("children") or ())


def _has_roadblock(node):
    """Whether this node or anything below it waits on a machine you do not have."""
    if is_roadblock(node.get("machine_state")):
        return True
    return any(_has_roadblock(c) for c in node.get("children") or ())


def _machine_link(uid, name):
    """A machine's name, linked to the page explaining it when there is a uid to link to.

    Shared by the tree node and the "machines to build first" panel. The panel is the
    SUMMARY of the same roadblocks the tree marks, and it listed them as dead text while the
    tree linked them, so the one place with the whole list was the one place you could not
    click through from.
    """
    if not uid:
        return _esc(name)
    return '<a class="mlink" href="%s">%s</a>' % (machine_href(uid), _esc(name))


def _machine_bit(node, name):
    """The machine on a tree node: its name, linked, plus a state badge when it blocks.

    The name was plain grey text at the same weight as the recipe count, so the one fact that
    decides whether a step can run at all read as trailing prose. Two changes: it links to
    `/machine?uid=`, which already explains the whole category, and it carries the state
    badge when `is_roadblock`.

    NO badge when the machine is on hand. The plan is mostly owned machines, so badging all
    of them would be a wall of green that hides the four that matter. That is the ask: only
    the roadblock is worth marking.
    """
    label = _machine_link(node.get("category"), name)
    state = node.get("machine_state")
    if not is_roadblock(state):
        return label
    # `title` carries machines.resolve's own words for WHY, which is the difference between
    # "craft the controller" and "no recipe makes this at all".
    why = node.get("machine_why") or ""
    return '%s <span class="badge %s"%s>%s</span>' % (
        label, STATE_BADGE.get(state, "need"),
        (' title="%s"' % _esc(why)) if why else "",
        _esc(STATE_LABEL.get(state, state)))


def _recipes_bit(node, back):
    """`N recipes`, as a link to the chooser when there is a server to click back to."""
    text = "%d recipes" % node["alternatives"]
    if not back:
        return text
    return ('<a class="mlink" href="/recipes?item=%s&amp;back=%s">%s</a>'
            % (urllib.parse.quote(node["key"]), urllib.parse.quote(back), text))


def _node_html(node, depth=0, back="", icon=None):
    """One tree row. `back` is where a pin control should return to, empty for a static
    render: `recipegraph plan --html` produces a file that outlives the server, and a
    button posting to a server that is not there is worse than no button."""
    status = node.get("status", "craft")
    label, cls = status_badge(status, node.get("token_kind"), node.get("unsourced"))
    kids = node.get("children") or []
    need_flag = 1 if _has_need(node) else 0

    bits = [
        '<span class="qty">%s&times;</span>' % "{:,}".format(node.get("need", 1)),
        '<span class="nm">%s' % named(node, icon),
    ]
    blocked = is_roadblock(node.get("machine_state"))
    extra = []
    if node.get("from_stock"):
        extra.append("%s from stock" % "{:,}".format(node["from_stock"]))
    if node.get("machine") and node.get("machine") != node.get("category"):
        extra.append(_machine_bit(node, node["machine"]))
    elif node.get("category") and not str(node["category"]).startswith("crafting"):
        extra.append(_machine_bit(node, node["category"]))
    if node.get("alternatives", 0) > 1:
        extra.append(_recipes_bit(node, back))
    # How many things the SLOT would have accepted, as opposed to how many recipes make
    # what is in it. The solver has always written this and nothing rendered it, so a
    # node standing in for an oredict slot looked like the only option it ever had.
    if node.get("alt_count", 0) > 1:
        extra.append("any of %d" % node["alt_count"])
    if node.get("note"):
        extra.append(_esc(node["note"]))
    if node.get("resolved_to"):
        extra.append("&rarr; %s" % _esc(node["resolved_to"]))
    if node.get("pinned"):
        text, cls = pin_badge(PIN_EXACT)
        extra.append('<span class="badge %s">%s</span>' % (cls, _esc(text)))
    if extra:
        bits.append(' <span class="meta">%s</span>' % " &middot; ".join(extra))
    bits.append("</span>")
    bits.append('<span class="badge %s">%s</span>' % (cls, label))
    inner = "".join(bits)

    # Two independent filters, so two attributes. `data-blocked` is 1 when this node OR
    # anything under it needs a machine you do not have: hiding a branch whose roadblock is
    # three levels down would hide the answer along with the noise, which is exactly the
    # mistake `data-hasneed` already avoids.
    block_flag = 1 if _has_roadblock(node) else 0
    if not kids:
        return ('<div class="leaf" data-hasneed="%d" data-blocked="%d">%s</div>'
                % (need_flag, block_flag, inner))

    open_attr = " open" if depth < 2 else ""
    return (
        '<details data-hasneed="%d" data-blocked="%d"%s>'
        '<summary><span class="tw">&#9656;</span>%s</summary>'
        '<div class="kids">%s</div></details>'
        % (need_flag, block_flag, open_attr, inner,
           "".join(_node_html(k, depth + 1, back, icon) for k in kids))
    )


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
        '<tr><td>%s</td><td><span class="badge %s"%s>%s</span></td></tr>'
        % (_machine_link(m.get("category"), m.get("machine") or m.get("category")),
           STATE_BADGE.get(m.get("state"), "need"),
           (' title="%s"' % _esc(m["why"])) if m.get("why") else "",
           _esc(STATE_LABEL.get(m.get("state"), m.get("state", "?"))))
        for m in machines)
    unknowns = sum(1 for m in machines if m.get("state") == "unknown")
    note = ('<div class="meta" style="margin-top:9px">%d of these could not be matched to '
            'a block, so availability is a guess. You may already have them.</div>'
            % unknowns) if unknowns else ""
    # The heading counts what is listed. Showing the confirmed-only figure beside a longer
    # list just reads as an off-by-one; the unidentified share belongs in the note.
    return ('<div class="card"><h2><span>Machines to build first</span>'
            '<span class="c">%d</span></h2><div class="scroll"><table>%s</table></div>%s'
            '</div>' % (len(machines), rows, note))


def _unsourced_badge():
    """The #136 badge for a shopping-list row, worded AND classed by `present`.

    Goes through `status_badge` rather than spelling `class="badge need"` here, so the row
    and the tree node cannot end up different colours for the same claim -- the exact split
    `present`'s module docstring exists to prevent.
    """
    text, cls = status_badge(STATUS_RAW, unsourced=True)
    return ' <span class="badge %s">%s</span>' % (cls, _esc(text))


def _rows(entries, limit=200, icon=None):
    if not entries:
        return '<tr><td class="meta">none</td></tr>'
    return "".join(
        '<tr><td class="n">%s%s</td><td>%s%s</td></tr>'
        % ("{:,}".format(e["qty"]),
           # mB, stated on the number rather than in the name, because the unit belongs to
           # the quantity. Never converted to buckets: recipes are authored in mB and
           # rounding would misreport a partial-bucket step.
           " mB" if e.get("kind") == "fluid" else "",
           named(e, icon),
           # `why` and the unsourced mark share the meta slot and cannot both apply: `why`
           # is set only on the infinite-sources list, whose rows are by definition sourced.
           (' <span class="meta">%s</span>' % _esc(e["why"])) if e.get("why") else
           _unsourced_badge() if e.get("unsourced") else "")
        for e in entries[:limit]
    )


def _sources_html(entries, icon=None):
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
            '</div>' % ("{:,}".format(total), _rows(entries, icon=icon)))


def _emc_html(entries, icon=None):
    """Items the ProjectE network transmutes rather than crafts. #50

    ITS OWN PANEL, beside the infinite sources and for the same reason: these are not things
    to go and get, so putting them in "You still need" would tell a player to farm a dungeon
    for an item their network already makes -- which is the exact report #50 was opened on.
    Kept apart from the sources panel too, because the two claims differ: a generator is
    infinite and free, EMC is finite and fungible.

    The EMC VALUE IS SHOWN, not just the fact of it, so the claim stays checkable. "From EMC"
    on its own is something a reader has to trust; "EMC 2,048" is something they can look up
    in their own transmutation table.
    """
    if not entries:
        return ""
    total = sum(e["qty"] for e in entries)
    rows = []
    for e in entries:
        rows.append('<tr><td class="q">%s</td><td>%s</td><td class="c">EMC %s</td></tr>'
                    % ("{:,}".format(e["qty"]), named(e, icon),
                       "{:,}".format(e.get("emc", 0))))
    return ('<div class="card"><h2><span>Made by transmutation</span>'
            '<span class="c">%s</span></h2><div class="scroll"><table>%s</table></div>'
            '</div>' % ("{:,}".format(total), "".join(rows)))


def _tokens_html(entries):
    """Pack placeholders, grouped by what they actually ask of the player.

    Its own panel rather than lines in "You still need", because a shopping list is a list
    of things to acquire and these are instructions. A plan that says "1 Dungeon Drop, 1
    From Battle Tower Loot" alongside "128 Granite" invites reading three materials where
    there are one material and one instruction.

    Grouped by kind, with the individual placeholders still named underneath. That is the
    rollup Jake asked for and the reason it stops short of a single "drop" line: "Battle
    Tower" and "go fishing" are genuinely different afternoons, and collapsing them would
    trade one kind of uselessness for another.
    """
    if not entries:
        return ""
    blocks = []
    for _kind, label, rows in tokens_mod.group(entries):
        blocks.append(
            '<tr><td colspan="2" class="tgroup">%s</td></tr>%s'
            % (_esc(label),
               "".join('<tr><td class="tname">%s</td><td class="n">%s</td></tr>'
                       % (named(e), "{:,}".format(e["qty"])) for e in rows)))
    return ('<div class="card"><h2><span>Not crafted, obtained</span>'
            '<span class="c">%d</span></h2><div class="scroll"><table>%s</table></div>'
            '</div>' % (len(entries), "".join(blocks)))


def _truncation_note(result, deeper):
    """Why the tree stops, and the one thing worth doing about it from where you stand.

    `deeper` is `(url, next cap)` when there is somewhere to go, `("", cap)` when the
    ceiling has been reached, and None off the server entirely.

    THE ADVICE HAS TO MATCH THE SURFACE. This used to read "Raise --max-nodes to see more"
    on every plan, which on the web page is not merely inconvenient but WRONG: the server
    is already running, and `--max-nodes` is an argument to `plan`, not to `serve`. Jake:
    *"I fully believe that there should be a button to click in this UI that will let me
    go deeper on this particular recipe if I want."* See #25.

    Doubling rather than a number box, because the reader does not know what 8,000 means;
    the honest interaction is "more" until it stops saying truncated.
    """
    if result.get("exhausted"):
        # NOT the node cap. The search spent its WORK budget on branches it backtracked
        # out of, so the node count is far below the cap, and "hit the node cap (1,162)"
        # on a plan capped at 4,000 reads as a bug in the tool. Both budgets derive from
        # `max_nodes`, so the control below is still the right thing to offer.
        # NOT "steps". The page's own "Steps resolved" tile is `nodes`, and `work` is the
        # monotonic counter that includes every branch the solver backed out of, so the two
        # are guaranteed to differ in this branch -- on the reference pack's
        # `avaritia:resource:6`, 1,162 against 80,000. One word for two numbers on one page
        # is worse than no number.
        #
        # No `or result["nodes"]` fallback either: a producer rename should raise here
        # rather than quietly print the wrong figure under the right label.
        head = ("The search used up its budget after %s attempts and was cut off, so "
                "deeper branches are incomplete. Most of that went on routes it tried "
                "and backed out of." % "{:,}".format(result["work"]))
    else:
        head = ("Tree hit the node cap (%s) and was cut off, so deeper branches are "
                "incomplete." % "{:,}".format(result["max_nodes"]))
    if deeper is None:
        return head + " Raise --max-nodes to see more."
    url, cap = deeper
    if not url:
        # No control at the ceiling. A button that cannot raise the cap any further reads
        # as broken, and the reader deserves to know they are at the end of the road
        # rather than clicking one more time.
        return (head + " This is the deepest this page goes (%s nodes); plan it from the "
                "terminal with a larger --max-nodes if you need more."
                % "{:,}".format(cap))
    return (head + ' <a class="mlink" data-plan-label="%s" href="%s">Go deeper</a>'
            ' to try again with %s nodes.'
            % (_esc(result.get("target_name") or ""), url, "{:,}".format(cap)))


def render_html(result, graph=None, coverage_note=None, back="", deeper=None,
                icon=None):
    tree = result["tree"]
    # BOTH orientations, shipped together. The alternative is a round trip, and a round
    # trip re-solves: a plan can take two minutes (defaults.MAX_NODES_CEILING), which is an
    # absurd price for turning a picture sideways. Measured, the second SVG is 5 to 16 KB
    # on a 48 to 67 KB page. ONE `layout` for both, and one legend, which is what makes the
    # legend true of both by construction rather than by `layout` happening to be
    # deterministic. See #35.
    diagrams, diagram_legend = render_diagrams(tree)
    diagram_svg, diagram_svg_td = diagrams[LR], diagrams[TD]
    # Only offered when there is something to filter TO. A button that empties the tree is
    # worse than no button: it reads as a broken filter rather than as "nothing is blocked".
    blocked_button = ('\n    <button id="blockedonly" data-on="0" aria-pressed="false">'
                      'Show only blocked steps</button>') if _has_roadblock(tree) else ""
    need = result.get("shopping_list") or []
    used = result.get("used_from_stock") or []

    warn = []
    if result.get("truncated"):
        warn.append(_truncation_note(result, deeper))
    # A pin the cycle guard had to ignore. The chooser already badged this choice as
    # taken, so leaving it unsaid would be the silent overwrite the feature exists to
    # prevent, just wearing a badge that says otherwise.
    for why in sorted((result.get("pins_overruled") or {}).values()):
        warn.append(_esc(why))
    if coverage_note:
        warn.append(coverage_note)
    warnbar = (
        '<div class="warnbar">%s</div>' % "<br>".join(warn) if warn else ""
    )

    total_need = sum(e["qty"] for e in need)
    total_used = sum(e["qty"] for e in used)

    return """<style>%s%s</style>
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
    <button id="diag" aria-pressed="false">Show as diagram</button>
    <button id="exp">Expand all</button>
    <button id="col">Collapse all</button>
    <button id="needonly" data-on="0" aria-pressed="false">Show only what I need</button>%s
  </div>
  <div class="card" id="diagbox" hidden>
    <h2><span>Flow</span><span class="c" id="dirname">%s</span></h2>
    %s
    <div class="diagwrap" id="diagwrap" data-dir="lr">%s%s</div>
    <div class="meta" style="margin-top:9px"><button id="dir" type="button"
      aria-pressed="false">Turn it %s</button></div>
    <div class="meta" style="margin-top:9px">A box is filled by what the plan does with
      that item, per the key above. The small SWATCH inside each box is a different axis:
      its colour groups items by mod and the letter is the item's initial. The diagram keeps
      those rather than the item icons the rows carry, because it is scanned for structure
      and a hue says &ldquo;these six are all one mod&rdquo; at a glance. Click any box to
      plan that item on its own.</div>
  </div>
  <div class="cols" id="cols">
    <div class="card" id="treebox">
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
      </div>%s%s%s%s
    </div>
  </div>
  <div class="foot">Recipe chain resolved offline from the installed pack; stock read
  from the AE2 network in the world save. Items marked <b>NEED</b> have no known
  recipe in the current graph &mdash; which may mean the recipe is a machine recipe
  not yet dumped, rather than that none exists. <b>no known source</b> is the stronger
  case: the graph can make the plain item but nothing reaches the state asked for, which
  usually means the pack gets there by a mechanic rather than a recipe (levelling, charging,
  a kill counter) and no recipe dump can see it.</div>
</div>
<script>%s</script>""" % (
        CSS, DIAGRAM_CSS,
        _esc(result["target_name"]),
        "{:,}".format(result["qty"]),
        _esc(result["target"]),
        "{:,}".format(result["nodes"]),
        "{:,}".format(total_need),
        "{:,}".format(total_used),
        "{:,}".format(len(need) + len(used)),
        warnbar,
        blocked_button,
        _esc(ORIENTATION_LABEL[LR]),
        diagram_legend,
        diagram_svg,
        diagram_svg_td,
        _esc(ORIENTATION_LABEL[TD]),
        "{:,}".format(result["nodes"]),
        _node_html(tree, back=back, icon=icon),
        len(need),
        _rows(need, icon=icon),
        len(used),
        _rows(used, icon=icon),
        _sources_html(result.get("from_sources"), icon),
        _emc_html(result.get("from_emc"), icon),
        _tokens_html(result.get("tokens_needed")),
        _machines_html(result.get("machines_to_build")),
        JS.replace("%%DIRS%%", script_json(ORIENTATION_LABEL)),
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
background:var(--mutedbg);color:var(--dim);white-space:nowrap}
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


def _ing_html(ing, icon=None):
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
    unit = " mB" if ing.get("role") == "fluid" or first.get("kind") == "fluid" else ""
    return '<div class="ing"><span class="q">%s%s</span>%s<span>%s%s</span></div>' % (
        "{:,}".format(ing["qty"]), unit, pill, named(first, icon), extra)


def _makes_html(item, icon=None):
    if not item["makes"]:
        return '<div class="empty">No known recipe in this graph.</div>'
    out = []
    for rec in item["makes"]:
        via = rec.get("machine") or rec["category"]
        yields = ", ".join(
            "%s&times; %s" % ("{:,}".format(o["qty"]), named(o, icon))
            for o in rec["outputs"][:3])
        out.append('<div class="rec"><div class="via">%s &rarr; %s</div>%s</div>'
                   % (_esc(via), yields,
                      "".join(_ing_html(i, icon) for i in rec["inputs"])))
    if item["makes_total"] > len(item["makes"]):
        out.append('<div class="alt">+%d more recipes not shown</div>'
                   % (item["makes_total"] - len(item["makes"])))
    return "".join(out)


def _used_html(item, icon=None):
    if not item["used_in"]:
        return '<div class="empty">Not an input to anything in this graph.</div>'
    out = []
    for rec in item["used_in"]:
        via = rec.get("machine") or rec["category"]
        outs = ", ".join(named(o, icon) for o in rec["outputs"][:2]) or "?"
        out.append('<div class="ing"><span class="q">&rarr;</span>'
                   '<span>%s <span class="alt">via %s</span></span></div>'
                   % (outs, _esc(via)))
    if item["used_in_total"] > len(item["used_in"]):
        out.append('<div class="alt">+%d more uses not shown</div>'
                   % (item["used_in_total"] - len(item["used_in"])))
    return "".join(out)


def _res_html(item, icon=None):
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
        _esc(hay), named(item, icon), _esc(item["key"]), "".join(chips),
        item["makes_total"], _makes_html(item, icon),
        item["used_in_total"], _used_html(item, icon),
    )


def render_explore_html(payload, coverage_note=None, icon=None):
    results = payload["results"]
    warn = ('<div class="warnbar">%s</div>' % _esc(coverage_note)) if coverage_note else ""
    dead = hidden_note(payload.get("hidden", 0), payload.get("collapsed", 0))
    return """<style>%s%s</style>
<div class="wrap">
  <div class="eyebrow">Item explorer</div>
  <h1>%s<span class="x">%d match%s</span></h1>
  <div class="id">Searched %s of %s item names%s &middot; ? on an oredict chip means
  membership was inferred from display names, not read from the game</div>
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
        # One exact hit means the caller followed a "details" link with a raw key; showing
        # the key back as the heading reads like an error. Use the item's name.
        (named(results[0]) if len(results) == 1 else _esc(payload["query"])),
        len(results), "" if len(results) == 1 else "es",
        "{:,}".format(payload.get("searched", 0)),
        "{:,}".format(payload.get("named", payload.get("searched", 0))),
        (" &middot; %s" % _esc(dead)) if dead else "",
        warn,
        len(results),
        sum(1 for r in results if r.get("stock")),
        sum(1 for r in results if r["makes_total"]),
        sum(1 for r in results if not r["makes_total"]),
        "".join(_res_html(r, icon) for r in results),
        EXPLORE_JS,
    )
