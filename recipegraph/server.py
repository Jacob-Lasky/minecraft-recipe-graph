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

import json
import os
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import cost as cost_mod
from . import explore as explore_mod
from . import generators as generators_mod
from . import machines as machines_mod
from . import tokens as tokens_mod
from .defaults import DEFAULT_HOST, DEFAULT_PORT, DEFAULT_SOURCES, DEFAULT_TOKENS
from .htmlutil import esc as _esc
from .htmlutil import item_href, machine_href
from .model import Graph, path_of
from .names import build_reverse
from .present import (STATE_LABEL, STATE_PILL, STATE_RANK, UNRANKED, kind_chip_json)
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
form.search input:focus{outline:2px solid var(--accent);outline-offset:-1px}
.hint2{color:var(--dim);font-size:13px;margin-bottom:22px}
/* Stale-data strip. Uses the warn tokens, not the accent: this is a state of the data, not
   navigation, and it has to survive being scrolled past on a long page. */
form.stale{display:flex;gap:14px;align-items:center;flex-wrap:wrap;
background:var(--warnbg);color:var(--warn);border:1px solid currentColor;
border-radius:9px;padding:11px 14px;font-size:13.5px;margin-bottom:20px}
form.stale span{flex:1 1 260px}
form.stale button{font:600 12.5px var(--sans);padding:7px 14px;border-radius:8px;
border:1px solid currentColor;background:transparent;color:inherit;cursor:pointer}
/* HOVER IS GATED ON `hover:hover` THROUGHOUT THIS SHEET, AND MUST STAY THAT WAY.
   A touch browser has no pointer to move away, so it leaves `:hover` applied to the last
   thing tapped until you tap something else. `button:hover` sets exactly the accent
   border and text colour that `[aria-pressed=true]` does, so unselecting a state chip
   cleared its background and left it looking selected. Reported as "the background turns
   off but it still remains highlighted". Any new `:hover` rule needs the same wrapper. */
@media(hover:hover){form.stale button:hover{background:var(--warn);color:var(--card)}}
.hits{list-style:none;padding:0;margin:0}
.hits li{border-bottom:1px solid var(--line);display:flex;align-items:stretch;gap:8px}
.hits a{display:flex;gap:12px;align-items:baseline;padding:10px 4px;text-decoration:none;
color:inherit;flex:1 1 auto;min-width:0;border-radius:8px}
.hits a.on{background:var(--accent-soft)}
@media(hover:hover){.hits a:hover{background:var(--accent-soft)}}
/* Keyboard selection must be visible even when the pointer is elsewhere, so `.on` gets a
   ring rather than only the hover tint. */
.hits a.on{box-shadow:inset 0 0 0 2px var(--accent)}
.hits a:focus-visible{outline:2px solid var(--accent);outline-offset:-2px}
.hits .nm2{flex:1 1 auto;font-size:15px;min-width:0;overflow:hidden;
text-overflow:ellipsis;white-space:nowrap}
.hits .id2{font:11.5px var(--mono);color:var(--dim);flex:0 0 auto}
.hits a.det{flex:0 0 auto;font:11.5px var(--sans);color:var(--dim);align-self:center;
padding:6px 10px}
@media(hover:hover){.hits a.det:hover{color:var(--accent);background:none;text-decoration:underline}}
.hits button.star{flex:0 0 auto;align-self:center;background:none;border:0;cursor:pointer;
font-size:16px;line-height:1;color:var(--dim);padding:6px 4px}
@media(hover:hover){.hits button.star:hover{color:var(--warn)}}
.hits button.star.on{color:var(--warn)}
.hits button.star:focus-visible{outline:2px solid var(--accent);outline-offset:2px}

/* Favourites and recents. Pinned chips rather than another list: they are shortcuts, and
   sizing them like results would suggest they are results. */
.shelf{margin-top:30px}
.shelf h2{font:600 10.5px/1 var(--mono);letter-spacing:.11em;text-transform:uppercase;
color:var(--dim);margin:0 0 10px}
.pins{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:22px}
.pin{display:inline-flex;align-items:center;border:1px solid var(--line);
border-radius:99px;background:var(--card);overflow:hidden}
.pin a{display:flex;align-items:center;gap:2px;padding:6px 13px;text-decoration:none;
color:inherit;font-size:13.5px}
@media(hover:hover){.pin a:hover{background:var(--accent-soft);color:var(--accent)}}
.pin button{background:none;border:0;border-left:1px solid var(--line);cursor:pointer;
color:var(--dim);padding:7px 10px;font-size:13px;line-height:1}
@media(hover:hover){.pin button:hover{color:var(--need)}}
.pin button:focus-visible,.pin a:focus-visible{outline:2px solid var(--accent);
outline-offset:-2px}
.pill{font:600 10.5px/1.7 var(--mono);padding:1px 8px;border-radius:99px;flex:0 0 auto}
.pill.ok{background:var(--okbg);color:var(--ok)}
.pill.no{background:var(--needbg);color:var(--need)}
.pill.mut{background:var(--mutedbg);color:var(--dim)}
/* Nav items are links AND targets, so they get an icon, a hit area and a visible
   current-page state. Text-only links at 13.5px did not read as clickable. */
nav.top{display:flex;gap:4px;font-size:13.5px;margin-bottom:22px;flex-wrap:wrap;
border-bottom:1px solid var(--line);padding-bottom:2px}
nav.top a,nav.top .cur{display:flex;align-items:center;gap:7px;padding:8px 13px;
text-decoration:none;border-radius:8px 8px 0 0;color:var(--dim);
border-bottom:2px solid transparent;margin-bottom:-3px}
@media(hover:hover){nav.top a:hover{background:var(--accent-soft);color:var(--accent)}}
/* `.cur`, not `span.cur`: the current SECTION is an <a> when the page sits below the tab
   rather than on it, and it has to look identically active either way. */
nav.top .cur{color:var(--fg);font-weight:600;border-bottom-color:var(--accent)}
/* Needed because `nav.top .cur` and `nav.top a:hover` have equal specificity and .cur is
   written later, so without this the one active tab that IS clickable is the only tab with
   no hover feedback. */
@media(hover:hover){nav.top a.cur:hover{background:var(--accent-soft);color:var(--accent)}}
nav.top svg{width:15px;height:15px;flex:0 0 auto;stroke:currentColor;fill:none;
stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round}
nav.top a:focus-visible,nav.top .cur:focus-visible{outline:2px solid var(--accent);
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
/* A filter that can no longer match anything reads as an honest zero rather than
   disappearing, so the list never jumps under the cursor. */
.chip-btn:disabled,.toolbar select option:disabled{opacity:.42;cursor:not-allowed}

table.mach{width:100%;border-collapse:collapse;font-size:13.5px}
table.mach th{text-align:left;font:600 10.5px var(--mono);letter-spacing:.1em;
text-transform:uppercase;color:var(--dim);padding:0 10px 8px 0;white-space:nowrap;
border-bottom:1px solid var(--line)}
table.mach th.sortable{cursor:pointer;user-select:none}
@media(hover:hover){table.mach th.sortable:hover{color:var(--accent)}}
table.mach th .ar{opacity:0;font-size:9px}
table.mach th[aria-sort] .ar{opacity:1;color:var(--accent)}
table.mach td{padding:7px 10px 7px 0;border-top:1px solid var(--line);
vertical-align:baseline}
@media(hover:hover){table.mach tbody tr:hover{background:var(--accent-soft)}}
table.mach td.n{text-align:right;font-family:var(--mono);
font-variant-numeric:tabular-nums;white-space:nowrap;width:1%}
table.mach a.mname{color:inherit;text-decoration:none;font-weight:550}
@media(hover:hover){table.mach a.mname:hover{color:var(--accent);text-decoration:underline}}
table.mach code{font:11.5px var(--mono);color:var(--dim)}
table.mach form{display:inline}
table.mach .acts{display:flex;gap:4px;justify-content:flex-end}
table.mach button{font:11.5px var(--sans);padding:2px 8px;border:1px solid var(--line);
border-radius:6px;background:var(--card);color:var(--fg);cursor:pointer;white-space:nowrap}
@media(hover:hover){table.mach button:hover{border-color:var(--accent);color:var(--accent)}}
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
@media(hover:hover){.klist a:hover{color:var(--accent);text-decoration:underline}}
.klist .grow{flex:1 1 auto}
.klist code{font:11.5px var(--mono);color:var(--dim);word-break:break-all}

/* Breadcrumb for the fragment pages. A plan is not a tab, so the nav marks Search as
   current and this says which item you are under. */
.crumb{font-size:13px;color:var(--dim);margin:-12px 0 0}
.crumb b{color:var(--fg);font-weight:600}
.crumb a{color:inherit;text-decoration:none;border-bottom:1px solid var(--line)}
@media(hover:hover){.crumb a:hover{color:var(--accent);border-bottom-color:currentColor}}

/* "Working" scrim. A plan is solved server-side, so the browser keeps showing the OLD
   page until it lands and a slow one reads as a dead click. */
.working{position:fixed;inset:0;display:none;align-items:flex-start;justify-content:center;
background:rgba(20,22,25,.42);z-index:50}
.working.on{display:flex}
.workbox{margin-top:22vh;display:flex;gap:15px;align-items:center;background:var(--card);
border:1px solid var(--line);border-radius:12px;padding:16px 20px;
max-width:min(92vw,430px);box-shadow:0 14px 44px rgba(0,0,0,.24)}
.workbox b{display:block;font-size:15px;margin-bottom:3px;overflow:hidden;
text-overflow:ellipsis;white-space:nowrap}
.workbox span{display:block;font-size:13px;color:var(--dim)}
.spin{width:20px;height:20px;flex:0 0 auto;border-radius:50%;border:2.5px solid var(--line);
border-top-color:var(--accent);animation:sp .8s linear infinite}
@keyframes sp{to{transform:rotate(360deg)}}
/* No substitute animation: the label already says what is happening, so a reader who has
   asked for stillness gets a plain ring rather than a slower spin. */
@media (prefers-reduced-motion:reduce){.spin{animation:none;border-color:var(--accent)}}

/* THE `hidden` ATTRIBUTE MUST BEAT EVERY `display` RULE IN THIS SHEET.

   `hidden` works through the UA rule `[hidden]{display:none}`, which carries almost no
   specificity, so ANY author rule that sets `display` on the same element silently wins
   and the element stays on screen. The machines page hides rows with `r.hidden=!ok`, and
   the phone card layout below sets `table.mach tr{display:flex}` (specificity 0,1,2
   against 0,1,0). Result: filtering set the attribute on 499 of 503 rows, the counter
   read "4", and all 503 stayed visible. The "nothing matches" row is a `tr` too, so it
   was permanently on screen as well.

   `!important` rather than a more specific selector on purpose: the bug is a whole CLASS
   of mistake, reintroduced by the next `display` rule anyone adds, and "hidden means
   hidden" is not a rule any layout should get to negotiate with. */
[hidden]{display:none!important}

/* PHONE. Same rule as the block at the end of render.CSS, and it must stay last here for
   the same reason: these override the desktop rules above by cascade order.

   700px HERE, 640px THERE, AND THAT IS NOT AN OVERSIGHT. render.CSS switches at 640
   because that is where its own `.stats` grid goes from two columns to four, and moving
   it would split a component across two breakpoints. A five-column table runs out of room
   well before a two-column stat grid does, so it has to break earlier. Unifying the two
   numbers means picking one component to render badly. */
@media(max-width:700px){
/* A FIVE-COLUMN TABLE CANNOT BE 390px WIDE. Measured on a 390px viewport, the machines
   table rendered 1,424px, so the recipe count, the reason and the buttons were all off
   the right-hand edge and the whole page scrolled sideways to reach them. Sorting by a
   column you cannot see is not a feature.

   Each row becomes a card instead. The cells are ORDERED, not just stacked, so the
   machine name leads and the state pill and count sit together under it. That is why
   they carry `c-` classes rather than being selected with nth-child: the sources table
   has three of these cells and the machines table five, and nth-child would silently
   assign the wrong rules to one of them. */
table.mach thead{display:none}
table.mach,table.mach tbody{display:block}
table.mach tr{display:flex;flex-wrap:wrap;align-items:center;gap:5px 9px;
border:1px solid var(--line);border-radius:10px;padding:10px 12px;margin:0 0 8px}
/* `min-width:0` is load-bearing, not tidying. A flex item defaults to `min-width:auto`,
   which means it refuses to shrink below its longest unbreakable word, and a registry id
   like `modularmachinery:mythic_processor_melter_controller` has no break opportunity in
   it. Without these two rules the card is as wide as its longest id, whatever
   `flex-basis` says, and the page still scrolls sideways: 571px of it, measured. */
table.mach td{display:block;border-top:0;padding:0;min-width:0}
table.mach code,table.mach a.mname{overflow-wrap:anywhere}
table.mach td.c-name{flex:1 1 100%;order:1}
table.mach td.c-state{order:2}
table.mach td.c-recipes{order:3;width:auto;text-align:left;font-size:12px;
color:var(--dim)}
/* The column header carried this word on desktop, and the header is gone here. */
table.mach td.c-recipes::after{content:" recipes"}
/* The evidence string is the other unbreakable one, and it got longer with #28:
   "craftable: thermalexpansion:machine:1 (as thermalexpansion:machine:1#f56885268ad5)".
   It overflowed INSIDE its own box, so nothing's bounding rect exceeded the viewport and
   the page still scrolled 571px. Internal overflow of an inline is invisible to a
   "does any element stick out" check, which is why the measurement has to be
   scrollWidth, not geometry. */
table.mach td.c-why{flex:1 1 100%;order:4;font-size:12.5px;overflow-wrap:anywhere}
table.mach td.c-acts{flex:1 1 100%;order:5}
table.mach .acts{justify-content:flex-start;flex-wrap:wrap;gap:6px}
/* 40px is the tap-target floor. These were 26px, two per row, 503 rows. `inline-flex`
   rather than padding alone so the min-height actually centres the label. */
table.mach .acts button{padding:8px 12px;min-height:40px;display:inline-flex;
align-items:center}
/* The machine name is the primary target of the card, so give it a real one. */
table.mach td.c-name a.mname{display:inline-flex;align-items:center;min-height:40px}

/* SEARCH RESULTS. A result row is a name, three pills, a registry id, a details link and
   a star. Only the name was allowed to shrink (`flex:1 1 auto`); everything else is
   `flex:0 0 auto`. At 390px the fixed content alone is wider than the row, so the name
   was squeezed to ZERO WIDTH and the id printed on top of the details link. Every result
   read as a nameless row of pills, which is why search looked broken rather than narrow.

   This predates the card layout; it was missed because the phone audit only ever loaded
   pages, never typed into one. Interactive state needs auditing too.

   The name gets its own full-width line and the rest wraps under it. */
.hits a{flex-wrap:wrap;gap:5px 8px;align-items:center;padding:9px 4px}
.hits .nm2{flex:1 1 100%;white-space:normal;overflow:visible;text-overflow:clip;
line-height:1.35}
.hits .id2{flex:0 1 auto;min-width:0;overflow-wrap:anywhere}
.hits a.det{padding:8px 10px}
.hits button.star{padding:8px 6px}

/* The mod dropdown had no width bound, so a long mod name made it wider than the screen
   and it was the second thing spilling out of the machines page. */
.toolbar{gap:7px}
.toolbar select,.toolbar input[type=search]{flex:1 1 100%;max-width:100%;min-height:44px}
.chip-btn{padding:8px 12px;min-height:40px}

/* The state stripe lives on the first CELL, which is the row's left edge in a table and
   is NOT the left edge of a card, where it rendered as a green bar floating in the
   middle of the row. Same signal, moved to the edge the card actually has. */
table.mach tr[data-state] td:first-child{border-left:0}
table.mach tr[data-state]{border-left-width:3px}
table.mach tr[data-state=have]{border-left-color:var(--ok)}
table.mach tr[data-state=buildable]{border-left-color:var(--warn)}
table.mach tr[data-state=unknown]{border-left-color:var(--dim)}
table.mach tr[data-state=unavailable]{border-left-color:var(--need)}

/* Four tabs at 13.5px with 13px of side padding wrapped onto two rows, spending a whole
   line of a phone screen on one tab that is always visible anyway. */
nav.top{gap:0;font-size:12px;margin-bottom:16px}
nav.top a,nav.top .cur{padding:8px 7px;gap:4px;min-height:40px}
.crumb{font-size:12.5px}
}
"""


# Shared client helpers, in the shell so every page has them and no page ships a second
# copy. `rgEsc` in particular: the search rows and the sources typeahead both build markup
# from item names, and the second one started life stripping `<` and `&` instead of
# escaping them, which quietly corrupts any name containing an ampersand.
SHELL_JS = """
window.rgEsc=function(s){
  return String(s).replace(/[&<>"']/g,function(c){
    return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];});
};
"""


# Every page links to /plan, and a plan is the one thing here that can block for seconds.
# ONE delegated listener in the shell rather than markup per template, so no page can link
# to a plan and forget the feedback.
PENDING_JS = """
(function(){
 var el=null, timer=null;
 function show(label){
   if(!el){
     el=document.createElement('div');
     el.className='working';
     el.setAttribute('role','status'); el.setAttribute('aria-live','polite');
     el.innerHTML='<div class="workbox"><div class="spin"></div><div>'
       +'<b class="wl"></b><span>Solving the recipe tree. A deep item takes a moment.'
       +'</span></div></div>';
     document.body.appendChild(el);
   }
   el.querySelector('.wl').textContent=label;
   el.classList.add('on');
 }
 function hide(){clearTimeout(timer); if(el)el.classList.remove('on');}
 // A plan link's text is not just the item name: search rows carry stock and recipe-count
 // pills and the raw id, which ran together into "Planning Widgetnone2 recipes". Strip the
 // decorations rather than special-casing each page's markup.
 function label(a){
   var c=a.cloneNode(true);
   c.querySelectorAll('.pill,.id2,.t,code').forEach(function(x){x.remove();});
   return (c.textContent||'').replace(/\\s+/g,' ').trim().slice(0,58);
 }
 document.addEventListener('click',function(e){
   var t=e.target, a=t&&t.closest?t.closest('a[href^="/plan?"]'):null;
   // Modified clicks open a new tab, so THIS page is not going anywhere and a scrim over
   // it would be a lie that only the back button clears.
   if(!a||e.button!==0||e.metaKey||e.ctrlKey||e.shiftKey||e.altKey)return;
   var name=label(a);
   // Held back briefly: most plans land in tens of milliseconds and a flash of "working"
   // is worse feedback than none.
   clearTimeout(timer);
   timer=setTimeout(function(){show(name?'Planning '+name+'\\u2026':'Planning\\u2026');},150);
 });
 // Returning here restores the page from the bfcache with the scrim still up.
 window.addEventListener('pageshow',hide);
 window.addEventListener('pagehide',hide);
})();
"""


def _item(graph, key):
    """Type chip plus escaped name, matching how the plan and explore views render keys."""
    return kind_chip(graph.kind(key)) + _esc(graph.bare_name(key))


def _shell(title, body, css):
    """The one document wrapper. Both page kinds go through it so neither can drift.

    `css` differs by caller: a fragment already carries the base CSS inline (that is what
    makes it publishable as an Artifact), a server-rendered page does not.
    """
    return ("<!doctype html><html><head><meta charset=utf-8>"
            "<meta name=viewport content='width=device-width,initial-scale=1'>"
            # SHELL_JS goes in the HEAD, not alongside PENDING_JS at the end of the body:
            # the page scripts are inline and run the moment they are parsed, so a helper
            # defined after them would not exist yet when they first call it.
            "<title>%s</title><style>%s</style><script>%s</script></head><body>%s"
            "<script>%s</script></body></html>"
            % (_esc(title), css, SHELL_JS, body, PENDING_JS))


def _wrap_fragment(title, fragment, state=None, crumb="", path=""):
    """Give an artifact-style fragment a real HTML document shell, with the real nav.

    The nav is `_nav`, not a hand-rolled back-link: a plan used to be the one page you
    could not leave, and the one page that would not tell you its data was stale. `path` is
    the wrapped page's own path, which is what lets the Search tab stay a working link on a
    page that lives under Search but is not Search.

    DO NOT move this into the renderer. The fragment has no document shell precisely so it
    can be published as a Claude Artifact unchanged, where none of these links resolve.
    """
    nav = ("<div class='wrap' style='padding-bottom:0'>%s%s</div>"
           % (_nav(path, state), crumb)) if state is not None else ""
    return _shell(title, nav + fragment, HOME_CSS)


def _crumb(label):
    """`Search > this thing`, naming which search result you descended into.

    The Search TAB is a working link now (#53), so this no longer exists to compensate for
    an inert one. It still earns its place by naming the page's parent in words.

    The link text comes from NAV_ITEMS rather than being spelled here, so renaming the tab
    cannot leave the crumb calling it something else.
    """
    return ("<div class='crumb'><a href='/'>%s</a> &rsaquo; <b>%s</b></div>"
            % (_esc(NAV_LABELS["/"]), _esc(label)))


def _page(title, body):
    return _shell(title, body, CSS + HOME_CSS)


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

# So a breadcrumb pointing at a tab cannot call it something the tab does not.
NAV_LABELS = {href: label for href, label, _icon in NAV_ITEMS}


# The files whose contents are held in memory, as (attribute holding the path, prose for the
# banner). ONE table: the keys were previously spread across the stamps dict, a
# `getattr(self, "%s_path" % name)` convention and a separate label map, so adding a watched
# file meant editing three places and getting the attribute name right by hand or silently
# watching nothing.
WATCHED_FILES = (
    ("graph_path", "the recipe graph"),
    ("have_path", "your AE2 stock"),
)


def _safe_back(form, default="/machines"):
    """The `back` form field, or `default` if it could navigate off this server.

    ONE implementation on purpose. `back` is attacker-controllable in the general case, and
    `startswith("/")` alone is NOT sufficient -- `//evil.example/x` satisfies it and is a
    valid protocol-relative URL. The second handler that needed this check reintroduced the
    weak version within minutes of the first being fixed.
    """
    back = (form.get("back") or [default])[0]
    parsed = urllib.parse.urlsplit(back)
    if not back.startswith("/") or parsed.scheme or parsed.netloc:
        return default
    return back


def _stale_banner(state, back="/"):
    """Tell the user their in-memory data is behind the files on disk.

    Shown rather than auto-reloaded because loading a 72 MB graph takes tens of seconds and
    would stall whichever unlucky request triggered it. An explicit button is predictable.

    `back` is the nav's active path, NOT the live request path: the State is shared across
    request threads, so stashing the current URL on it would race between concurrent
    requests and could bounce one user to another's page.
    """
    changed = state.stale()
    if not changed:
        return ""
    what = " and ".join(changed)
    return ("<form method='post' action='/reload' class='stale'>"
            "<input type='hidden' name='back' value='%s'>"
            "<span><b>%s</b> changed on disk since this server started, so what you are "
            "looking at is out of date.</span>"
            "<button type='submit'>Reload now</button></form>"
            % (_esc(back), _esc(what)))


# Pages that live UNDER a tab, mapped to the tab they live under. Every path a page can
# render itself at is either a key here or a NAV_ITEMS href; `tests/test_server.py` asserts
# both directions, because a path missing from both silently highlights nothing.
NAV_PARENT = {
    "/plan": "/",
    "/explore": "/",
    "/machine": "/machines",
}


def _nav(path="", state=None):
    """The tab bar, plus a stale-data warning when one is due.

    `path` is the page's OWN path, not its section. The section's tab is highlighted either
    way; what differs is that the section's own page renders an inert span and a page BELOW
    it renders a working link.

    DO NOT collapse those two back into one span. The call sites used to pass the section as
    the active path, so `/plan` and `/explore` claimed to BE the search page: the one tab a
    reader wants after finishing with a plan was the one turned off, and
    `aria-current='page'` told a screen reader it was already on Search, which is not
    recoverable by looking around. `aria-current='true'` on the child says "this is the
    section you are in" without asserting the page. See #53.

    The banner rides along here because every page renders a nav and none of them should be
    able to forget it. Pass the state; omit it only where there is none (the 404 shell).
    """
    section = NAV_PARENT.get(path, path)
    out = []
    for href, label, icon in NAV_ITEMS:
        inner = _icon(icon) + _esc(label)
        if href != section:
            out.append("<a href='%s'>%s</a>" % (href, inner))
        elif href == path:
            out.append("<span class='cur' aria-current='page'>%s</span>" % inner)
        else:
            out.append("<a class='cur' aria-current='true' href='%s'>%s</a>" % (href, inner))
    # `section`, not `path`: the reload form posts this back as where to return to, and
    # `/plan` stripped of its item query is a 400.
    banner = _stale_banner(state, section or "/") if state is not None else ""
    return "<nav class='top'>%s</nav>%s" % ("".join(out), banner)


def _stamp(path):
    """(mtime, size) for a file, or None if it is absent.

    Content is not hashed: graph.json is 72 MB and this runs on every request. A rebuild
    always changes both fields, and a rewrite with byte-identical content is not a change
    worth reloading for.
    """
    try:
        st = os.stat(path)
    except OSError:
        return None
    return (st.st_mtime_ns, st.st_size)


class State:
    """Loaded once; requests read it. Rebuilt when overrides change or the files do."""

    def __init__(self, graph_path, have_path, machines_path, sources_path=None,
                 tokens_path=None):
        self.graph_path = graph_path
        self.have_path = have_path
        self.machines_path = machines_path
        self.sources_path = sources_path or DEFAULT_SOURCES
        self.tokens_path = tokens_path or DEFAULT_TOKENS
        self.lock = threading.Lock()
        self.load_all()

    def load_all(self):
        """Read the graph and the stock file from disk, then recompute everything.

        Stamps are taken BEFORE reading, so a rebuild that lands mid-read is noticed on the
        next request rather than being recorded as already loaded.
        """
        self.stamps = {attr: _stamp(getattr(self, attr)) for attr, _label in WATCHED_FILES}
        self.graph = Graph.load(self.graph_path)
        self.reverse = build_reverse(self.graph.names)
        self.have = {}
        self.craftables = set()
        self.placed = {}
        if self.have_path and os.path.exists(self.have_path):
            with open(self.have_path) as fh:
                doc = json.load(fh)
            self.have = dict(doc.get("items") or {})
            for name, amount in (doc.get("fluids") or {}).items():
                self.have["fluid:%s" % name] = amount
            for aspect, amount in (doc.get("essentia") or {}).items():
                self.have["essentia:%s" % str(aspect).lower()] = amount
            self.craftables = set(doc.get("craftables") or ())
            self.placed = doc.get("placed") or {}
        self.refresh_machines()

    def stale(self):
        """Which of the loaded files have changed on disk since they were read.

        The whole point of the tool is a `/recipedump` then a rebuild, and the server holds
        the graph in memory for the session. Without this it silently serves the old graph
        afterwards, which reads as "the fix did not work" rather than "reload me".
        """
        return [label for attr, label in WATCHED_FILES
                if _stamp(getattr(self, attr)) != self.stamps.get(attr)]

    def refresh_machines(self):
        """Recompute machine states and the cost table (cost depends on machine state)."""
        overrides = machines_mod.load_overrides(self.machines_path)
        self.overrides = overrides
        self.machine_info = machines_mod.describe(
            self.graph, self.placed, self.have, overrides=overrides,
            no_machine=machines_mod.load_no_machine(self.machines_path))
        self.states = {uid: (i["state"], i["why"])
                       for uid, i in self.machine_info.items()}
        # Kept on the State, not resolved inline: /sources renders the raw overrides too
        # (what you disabled, whether vanilla water is on), and re-reading the file per
        # request would let the page disagree with the costs computed from it.
        self.source_overrides = generators_mod.load_overrides(self.sources_path)
        self.free_sources = generators_mod.resolve(
            self.placed, self.have, self.source_overrides)
        # Resolved once per load for the same reason the source overrides are: re-reading
        # per request would let two plans in one session disagree about what a token is.
        self.token_kinds = tokens_mod.resolve(
            tokens_mod.load_overrides(self.tokens_path))
        self.costs = cost_mod.estimate_cached(
            self.graph, self.graph_path, have=self.have, machine_states=self.states,
            free_sources=self.free_sources)

    def solver(self):
        return Solver(self.graph, have=self.have, craftables=self.craftables,
                      machine_states=self.states, costs=self.costs,
                      free_sources=self.free_sources, token_kinds=self.token_kinds)


HOME_JS = """
(function(){
 var box=document.getElementById('q'), qty=document.getElementById('qty'),
     list=document.getElementById('hits'), note=document.getElementById('note'),
     shelf=document.getElementById('shelf'),
     seq=0, sel=-1, rows=[];

 // Favourites and recents live in localStorage: this is a single-user local tool with no
 // account, and putting them server-side would mean a write path and a file to corrupt for
 // something a browser already stores well.
 var FAV='rg.favs', HIST='rg.hist', HIST_MAX=8;
 function load(k){try{return JSON.parse(localStorage.getItem(k))||[];}catch(e){return [];}}
 function save(k,v){try{localStorage.setItem(k,JSON.stringify(v));}catch(e){}}
 function isFav(key){return load(FAV).some(function(f){return f.key===key;});}
 function toggleFav(item){
   var favs=load(FAV), i=favs.findIndex(function(f){return f.key===item.key;});
   if(i>=0){favs.splice(i,1);}
   else{favs.unshift({key:item.key,label:item.label||item.name,kind:item.kind});}
   save(FAV,favs); shelves(); return i<0;
 }
 function remember(item){
   var h=load(HIST).filter(function(x){return x.key!==item.key;});
   h.unshift({key:item.key,label:item.label||item.name,kind:item.kind});
   save(HIST,h.slice(0,HIST_MAX)); shelves();
 }

 function plan(key){
   return '/plan?item='+encodeURIComponent(key)+'&qty='+(parseInt(qty.value,10)||1);
 }
 // Injected from present.KIND_CHIP rather than restated, so the client-rendered rows and
 // the server-rendered pages cannot disagree about what a type is called.
 var CHIPS=%%CHIPS%%;
 function chip(kind){
   var l=CHIPS[kind];
   return l?'<span class="t t-'+kind+'">'+l+'</span>':'';
 }
 var esc=window.rgEsc;
 function n(x){return x.toLocaleString();}

 function render(items,q){
   rows=[]; sel=-1;
   if(!q){list.innerHTML=''; note.textContent=''; shelf.hidden=false; return;}
   shelf.hidden=true;
   if(!items.length){
     list.innerHTML='';
     note.textContent='Nothing matches \\u201c'+q+'\\u201d.';
     return;
   }
   note.textContent='';
   list.innerHTML=items.map(function(it,i){
     // "made by / used by" in place: where an item sits in the graph is often the whole
     // answer, so it should not need a second page load to see.
     var made=it.makes?n(it.makes)+' recipe'+(it.makes===1?'':'s'):'no recipe';
     var fav=isFav(it.key);
     return '<li><a href="'+plan(it.key)+'" data-i="'+i+'" data-key="'+esc(it.key)+'">'
       +'<span class="nm2">'+chip(it.kind)+esc(it.label||it.name)+'</span>'
       +'<span class="pill '+(it.stock?'ok':'mut')+'">'
       +(it.stock?n(it.stock)+' in stock':'none')+'</span>'
       +'<span class="pill '+(it.makes?'mut':'no')+'">'+made+'</span>'
       +'<span class="pill mut">'+n(it.uses)+' use'+(it.uses===1?'':'s')+'</span>'
       +'<span class="id2">'+esc(it.key)+'</span></a>'
       +'<button class="star'+(fav?' on':'')+'" type="button" data-star="'+i+'"'
       +' aria-pressed="'+fav+'" title="keep this in Favourites">'
       +(fav?'\\u2605':'\\u2606')+'</button>'
       +'<a class="det" href="/explore?q='+encodeURIComponent(it.key)+'"'
       +' title="every recipe that makes and uses this">details</a></li>';
   }).join('');
   rows=Array.prototype.slice.call(list.querySelectorAll('a[data-i]'));
   list.querySelectorAll('button[data-star]').forEach(function(b){
     b.addEventListener('click',function(){
       var on=toggleFav(items[+b.dataset.star]);
       b.classList.toggle('on',on);
       b.setAttribute('aria-pressed',String(on));
       b.textContent=on?'\\u2605':'\\u2606';
     });
   });
   rows.forEach(function(a,i){
     a.addEventListener('click',function(){remember(items[i]);});
   });
 }

 function shelf_html(title, entries, removable){
   if(!entries.length)return '';
   return '<div class="shelf"><h2>'+title+'</h2><div class="pins">'+entries.map(function(e){
     return '<span class="pin"><a href="'+plan(e.key)+'">'+chip(e.kind)
       +esc(e.label||e.key)+'</a>'
       +(removable?'<button type="button" data-drop="'+esc(e.key)
         +'" title="remove" aria-label="remove '+esc(e.label||e.key)+'">\\u00d7</button>':'')
       +'</span>';
   }).join('')+'</div></div>';
 }

 function shelves(){
   var favs=load(FAV), hist=load(HIST);
   shelf.innerHTML=shelf_html('Favourites',favs,true)+shelf_html('Recent',hist,false);
   shelf.querySelectorAll('button[data-drop]').forEach(function(b){
     b.addEventListener('click',function(){
       save(FAV,load(FAV).filter(function(f){return f.key!==b.dataset.drop;}));
       shelves();
     });
   });
 }

 function highlight(){
   rows.forEach(function(a,i){
     a.classList.toggle('on',i===sel);
     if(i===sel)a.scrollIntoView({block:'nearest'});
   });
 }

 var timer=null;
 function go(){
   var q=box.value.trim();
   // Reflect the query in the URL without a navigation, so a search survives reload and
   // can be shared, and the back button still works.
   history.replaceState(null,'',q?'/?q='+encodeURIComponent(q):'/');
   if(!q){render([],'');return;}
   var mine=++seq;
   fetch('/suggest?q='+encodeURIComponent(q))
     .then(function(r){return r.json();})
     .then(function(d){
       // Drop a slow response that a later keystroke has already superseded, or results
       // flicker back to a stale query.
       if(mine!==seq)return;
       render(d.results||[],q);
     })
     .catch(function(){if(mine===seq)note.textContent='Search failed.';});
 }
 box.addEventListener('input',function(){clearTimeout(timer);timer=setTimeout(go,110);});
 // Changing the quantity must not re-search; only the links need updating. The key comes
 // from a data attribute rather than being parsed back out of the href.
 qty.addEventListener('input',function(){
   rows.forEach(function(a){a.href=plan(a.dataset.key);});
 });
 box.addEventListener('keydown',function(e){
   if(e.key==='ArrowDown'||e.key==='ArrowUp'){
     if(!rows.length)return;
     e.preventDefault();
     var down=e.key==='ArrowDown';
     if(sel<0){sel=down?0:rows.length-1;}
     else{sel=(sel+(down?1:-1)+rows.length)%rows.length;}
     highlight();
   }else if(e.key==='Enter'){
     e.preventDefault();
     if(rows.length)rows[sel<0?0:sel].click();
   }else if(e.key==='Escape'){box.value='';go();}
 });
 shelves();
 if(box.value.trim())go();
})();
"""


def home_page(state, query="", qty=1):
    body = """<div class="wrap">%s
  <div class="eyebrow">Recipe graph</div>
  <h1>What do you want to make?</h1>
  <div class="hint2">%s recipes &middot; %s items in your network &middot;
    %d machine categories on hand</div>
  <form class="search" onsubmit="return false">
    <input id="q" type="search" name="q" value="%s"
           placeholder="Start typing&hellip; Borax, Ultimate Component"
           autofocus autocomplete="off" spellcheck="false">
    <input id="qty" type="number" name="qty" value="%d" min="1" title="quantity"
           aria-label="quantity">
  </form>
  <div class="hint2" id="note"></div>
  <ul class="hits" id="hits"></ul>
  <div id="shelf"></div>
  <noscript><p class="hint2">Search needs JavaScript. Without it, use
    <code>recipegraph plan &lt;item&gt;</code> from the terminal.</p></noscript>
</div>
<script>%s</script>""" % (
        _nav("/", state),
        "{:,}".format(len(state.graph.recipes)),
        "{:,}".format(len(state.have)),
        sum(1 for s, _w in state.states.values() if s == machines_mod.HAVE),
        _esc(query), qty, HOME_JS.replace("%%CHIPS%%", kind_chip_json()),
    )
    return _page("Recipe graph", body)


MACHINES_JS = """
(function(){
 var q=document.getElementById('mq'), sel=document.getElementById('mmod'),
     rows=Array.prototype.slice.call(document.querySelectorAll('#mbody tr[data-state]')),
     body=document.getElementById('mbody'), shown=document.getElementById('mshown'),
     none=document.getElementById('mnone'), active={},
     opts=Array.prototype.slice.call(sel.options);
 document.querySelectorAll('.chip-btn[data-state]').forEach(function(b){
   b.addEventListener('click',function(){
     var on=b.getAttribute('aria-pressed')==='true';
     b.setAttribute('aria-pressed',on?'false':'true');
     if(on){delete active[b.dataset.state];}else{active[b.dataset.state]=1;}
     apply();
   });
 });
 // The two filters narrow EACH OTHER. Picking a state used to leave the mod dropdown
 // listing every mod at its full count, including mods with nothing in that state, so
 // choosing one produced an empty table with no hint why.
 //
 // Counts come from the OTHER axis only and deliberately ignore the text box: counting
 // against everything is more truthful but makes every number move while you type, and a
 // moving target is harder to read than a slightly generous one.
 function tally(){
   var mod=sel.value, states=Object.keys(active), byMod={}, byState={};
   rows.forEach(function(r){
     if(!states.length||active[r.dataset.state]){
       byMod[r.dataset.mod]=(byMod[r.dataset.mod]||0)+1;
     }
     if(!mod||r.dataset.mod===mod){
       byState[r.dataset.state]=(byState[r.dataset.state]||0)+1;
     }
   });
   return {mod:byMod, state:byState};
 }
 function reconcile(){
   // A selection that can no longer match anything is CLEARED rather than left to show an
   // empty table. Dropping a selection only ever widens the result, so one pass converges.
   var t=tally(), changed=false;
   if(sel.value && !t.mod[sel.value]){sel.value=''; changed=true;}
   Object.keys(active).forEach(function(s){
     if(!t.state[s]){delete active[s]; changed=true;}
   });
   return changed ? tally() : t;
 }
 function apply(){
   var t=reconcile(), mod=sel.value, states=Object.keys(active),
       text=(q.value||'').toLowerCase().trim(), n=0;
   opts.forEach(function(o){
     if(!o.value)return;                       // the "every mod" entry has no count
     var c=t.mod[o.value]||0;
     o.textContent=o.dataset.label+' ('+c+')';
     // Disabled rather than removed: removing makes the list jump under the cursor,
     // and a visible zero is an answer.
     o.disabled=!c;
   });
   document.querySelectorAll('.chip-btn[data-state]').forEach(function(b){
     var c=t.state[b.dataset.state]||0;
     b.querySelector('.n').textContent=c;
     b.disabled=!c && !active[b.dataset.state];
     b.setAttribute('aria-pressed', active[b.dataset.state]?'true':'false');
   });
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


def machines_page(state, message="", query=""):
    counts = machines_mod.summarise(state.states)
    recipes_by_state = dict.fromkeys(machines_mod.STATES, 0)
    mods = {}
    rows = []
    for uid, info in sorted(state.machine_info.items(),
                            key=lambda kv: (STATE_RANK.get(kv[1]["state"], UNRANKED),
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
        # The squashed mod name as well as the display one, so typing the registry form
        # you know ("industrialforegoing") finds a group JEI calls "Industrial Foregoing".
        hay = " ".join((name, uid, info["mod"], info["mod"].replace(" ", ""),
                        evidence, " ".join(cands))).lower()
        rows.append(
            "<tr data-state='%s' data-rank='%d' data-name='%s' data-uid='%s' "
            "data-mod='%s' data-recipes='%d' data-hay='%s'>"
            "<td class='c-state'><span class='pill %s'>%s</span></td>"
            "<td class='c-name'><a class='mname' href='%s'>%s</a>"
            "%s<br><code>%s</code></td>"
            "<td class='n c-recipes'>%s</td>"
            "<td class='hint2 c-why' style='margin:0'>%s</td>"
            "<td class='c-acts'><div class='acts'>%s</div></td></tr>"
            % (st, STATE_RANK.get(st, UNRANKED), _esc(name.lower()), _esc(uid.lower()),
               _esc(info["mod"]), info["recipes"], _esc(hay),
               STATE_PILL.get(st, "mut"), STATE_LABEL.get(st, st),
               machine_href(uid), _esc(name),
               " <b>(manual)</b>" if info["manual"] else "",
               _esc(uid), "{:,}".format(info["recipes"]), _esc(evidence),
               _toggles(uid, st)))

    chips = "".join(
        "<button class='chip-btn' type='button' data-state='%s' aria-pressed='false'>"
        "%s<span class='n'>%d</span></button>"
        % (s, STATE_LABEL.get(s, s), counts.get(s, 0))
        for s in machines_mod.STATES)
    # `data-label` so the client can rewrite the count as the state filter narrows without
    # having to parse the existing "(28)" back out of the text.
    options = "".join(
        "<option value='%s' data-label='%s'>%s (%d)</option>"
        % (_esc(m), _esc(m or "?"), _esc(m or "?"), n)
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
      category corresponds to, not that you cannot use it. %s</div>
  </div>
</div>
<script>%s</script>""" % (
        _nav("/machines", state),
        (" <b>%s</b>" % _esc(message)) if message else "",
        counts.get("have", 0), "{:,}".format(recipes_by_state.get("have", 0)),
        counts.get("buildable", 0), "{:,}".format(recipes_by_state.get("buildable", 0)),
        counts.get("unknown", 0), "{:,}".format(recipes_by_state.get("unknown", 0)),
        counts.get("unavailable", 0),
        "{:,}".format(recipes_by_state.get("unavailable", 0)),
        _esc(query), options, chips, "".join(rows), len(rows), len(rows),
        # Telling someone who has already dumped to go and dump reads as the tool not
        # noticing what it is holding, so say which case they are in.
        ("These are the residue after reading JEI's own &ldquo;made in&rdquo; list, and "
         "mostly have no machine at all."
         if getattr(state.graph, "catalysts", None) else
         "Run <code>/recipedump</code> with the current mod and rebuild to fix most of "
         "these: JEI knows the exact mapping and this graph was built without it."),
        MACHINES_JS,
    )
    return _page("Machines", body)


def machine_page(state, uid):
    """One category: what it makes, what it consumes, and why it is rated as it is."""
    info = state.machine_info.get(uid)
    if not info:
        return _page("Not found", "<div class='wrap'>%s<h1>No such category</h1>"
                     "<p class='hint2'><code>%s</code> is not in this graph.</p></div>"
                     % (_nav("/machine", state), _esc(uid))), 404

    detail = machines_mod.responsibilities(state.graph, uid)
    st = info["state"]

    def klist(pairs, total, verb):
        if not pairs:
            return "<div class='hint2' style='margin:0'>Nothing.</div>"
        items = "".join(
            "<li><span class='c'>%s</span>"
            "<a class='grow' href='%s'>%s</a>"
            "%s</li>"
            % ("{:,}".format(n), item_href(k), _item(state.graph, k),
               ("<span class='pill ok'>%s</span>" % "{:,}".format(state.have[k]))
               if state.have.get(k) else "")
            for k, n in pairs)
        more = ("<div class='hint2' style='margin:10px 0 0'>+%d more %s</div>"
                % (total - len(pairs), verb)) if total > len(pairs) else ""
        return "<ul class='klist'>%s</ul>%s" % (items, more)

    cands = info["candidates"]
    if cands:
        # Each candidate carries ITS OWN verdict. Smelting really is done in more than
        # the controller, and a page that showed only the winning candidate hid the other
        # blocks that would also do -- and hid the fact that one of them is placed while
        # the named one is not. See #27.
        cand_html = "<ul class='klist'>%s</ul>" % "".join(
            "<li><a href='%s'>%s</a><code>%s</code>"
            "<span class='grow'></span>"
            "<span class='pill %s' title='%s'>%s</span></li>"
            % (item_href(c["key"]), _item(state.graph, c["key"]), _esc(c["key"]),
               STATE_PILL.get(c["state"], "mut"), _esc(c["why"]),
               STATE_LABEL.get(c["state"], c["state"]))
            for c in info.get("candidate_states", [])[:8])
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
        _nav("/machine", state),
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
        _toggles(uid, st, back=machine_href(uid)),
        "{:,}".format(detail["makes_total"]),
        klist(detail["makes"], detail["makes_total"], "made here"),
        "{:,}".format(detail["uses_total"]),
        klist(detail["uses"], detail["uses_total"], "consumed here"),
    )
    return _page(info["title"] or uid, body), 200


def _source_button(action, label, title, **fields):
    """One POST button on /sources. Same shape as the machine toggle, one implementation."""
    hidden = "".join("<input type='hidden' name='%s' value='%s'>" % (k, _esc(v))
                     for k, v in fields.items())
    return ("<form method='post' action='/sources'>"
            "<input type='hidden' name='do' value='%s'>%s"
            "<button type='submit' title='%s'>%s</button></form>"
            % (action, hidden, _esc(title), _esc(label)))


SOURCES_JS = """
(function(){
 // Typeahead into a datalist rather than a custom popup: the browser already does the
 // filtering, the keyboard handling and the accessibility, and this field is used once
 // in a while rather than constantly like the main search.
 var box=document.getElementById('okey'), list=document.getElementById('okeys'), t=null;
 if(box&&list){
   box.addEventListener('input',function(){
     var q=box.value.trim();
     clearTimeout(t);
     if(q.length<2)return;
     t=setTimeout(function(){
       fetch('/suggest?q='+encodeURIComponent(q))
         .then(function(r){return r.json();})
         .then(function(d){
           list.innerHTML=(d.results||[]).map(function(it){
             return '<option value="'+window.rgEsc(it.key)+'">'
               +window.rgEsc(it.name)+'</option>';
           }).join('');
         }).catch(function(){});
     },140);
   });
 }
 // A candidate chip fills the block field and moves you to the part only you can answer.
 document.querySelectorAll('button[data-block]').forEach(function(b){
   b.addEventListener('click',function(){
     document.getElementById('oblock').value=b.dataset.block;
     if(box)box.focus();
   });
 });
})();
"""


def sources_page(state, message=""):
    """What the planner treats as free, on what evidence, and how to change it."""
    ov = state.source_overrides
    rows = "".join(
        "<tr><td class='c-name'><a class='mname' href='%s'>%s</a><br><code>%s</code></td>"
        "<td class='hint2 c-why' style='margin:0'>%s</td>"
        "<td class='c-acts'><div class='acts'>%s</div></td></tr>"
        % (item_href(key), _item(state.graph, key), _esc(key), _esc(why),
           _source_button("disable", "not free", "stop treating this as free", key=key))
        for key, why in sorted(state.free_sources.items()))

    disabled = "".join(
        "<li><span class='grow'>%s</span><code>%s</code>%s</li>"
        % (_item(state.graph, key), _esc(key),
           _source_button("enable", "restore", "treat this as free again", key=key))
        for key in sorted(ov.get("disabled") or ()))

    mine = ov.get("generators") or {}
    added = "".join(
        "<li><span class='grow'><code>%s</code> &rarr; %s</span>%s</li>"
        % (_esc(block), ", ".join(_item(state.graph, k) for k in outs),
           _source_button("forget", "remove", "remove this generator", block=block))
        for block, outs in sorted(mine.items()))

    cands = generators_mod.candidates(state.placed, ov)
    chips = "".join(
        "<button class='chip-btn' type='button' data-block='%s'>%s</button>" % (_esc(b), _esc(b))
        for b in cands[:24])

    body = """<div class="wrap">%s
  <div class="eyebrow">Infinite sources</div>
  <h1>What costs you nothing<span class="x">%d</span></h1>
  <div class="hint2">An infinite source is a block that emits a resource from no inputs
   &mdash; a water source, a cobblestone generator. Because it has no recipe, a recipe
   graph cannot find it, so this is a list rather than a search. Anything on it is priced
   as effectively free, which is what stops a plan rebuilding water out of 71 snowballs.
   Draw is still counted and reported on every plan: free does not mean invisible.%s</div>
  <div class="card"><h2><span>Free right now</span></h2>
    <table class="mach">%s</table></div>

  <div class="card"><h2><span>Add a source</span></h2>
    <div class="hint2" style="margin:0 0 12px">Name the block you built and the resource
     it makes. The output has to be stated by hand for the same reason the list exists:
     nothing in the graph says what an input-free block emits.</div>
    <form class="search" method="post" action="/sources">
      <input type="hidden" name="do" value="add">
      <input id="oblock" name="block" type="search" placeholder="Block id, e.g. nuclearcraft:water_source"
             list="pblocks" autocomplete="off" spellcheck="false" required>
      <datalist id="pblocks">%s</datalist>
      <input id="okey" name="key" type="search" placeholder="Makes&hellip; water, cobblestone"
             list="okeys" autocomplete="off" spellcheck="false" required>
      <datalist id="okeys"></datalist>
      <button type="submit">Add</button>
    </form>
    %s
  </div>

  <div class="card"><h2><span>Yours</span><span class="c">%d</span></h2>
    <ul class="klist">%s</ul></div>
  <div class="card"><h2><span>Switched off</span><span class="c">%d</span></h2>
    <div class="hint2" style="margin:0 0 10px">These stay off whatever the world says, and
     survive a rebuild.</div>
    <ul class="klist">%s</ul></div>
  <div class="card"><h2><span>Vanilla infinite water</span></h2>
    <div class="hint2" style="margin:0 0 10px">Two source blocks and a bucket give
     unlimited water in a default 1.12.2 world. It is a claim about this pack rather than
     a sighting, so it is a switch: currently <b>%s</b>.</div>
    %s</div>
</div>
<script>%s</script>""" % (
        _nav("/sources", state), len(state.free_sources),
        (" <b>%s</b>" % _esc(message)) if message else "",
        rows or "<tr class='empty-row'><td colspan='3'>Nothing detected.</td></tr>",
        "".join("<option value='%s'>" % _esc(b) for b in sorted(state.placed)),
        ("<div class='hint2' style='margin:12px 0 0'>Placed in your world and not on the "
         "list yet:</div><div class='chips' style='margin:8px 0 0'>%s</div>" % chips)
        if chips else "",
        len(mine), added or "<li class='hint2'>Nothing added by hand yet.</li>",
        len(ov.get("disabled") or ()),
        disabled or "<li class='hint2'>Nothing switched off.</li>",
        "on" if ov.get("vanilla_water", True) else "off",
        _source_button("vanilla", "switch off" if ov.get("vanilla_water", True) else "switch on",
                       "this pack does or does not have infinite water"),
        SOURCES_JS,
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
        _nav("/stats", state),
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
            if parts.path == "/suggest":
                # The only JSON endpoint. Everything else is server-rendered; this exists
                # because a keystroke must not re-render a page.
                results = explore_mod.suggest(st.graph, one("q"), have=st.have, limit=25)
                return self._send(json.dumps({"results": results}),
                                  ctype="application/json; charset=utf-8")
            if parts.path == "/explore":
                query = one("q")
                results = explore_mod.search(st.graph, query, have=st.have, limit=40)
                payload = {"query": query, "results": results,
                           "searched": len(st.graph.names)}
                return self._send(_wrap_fragment(
                    "Explore: %s" % query, render_explore_html(payload), st,
                    _crumb("Explore “%s”" % query), path="/explore"))
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
                        # `st`, and the real path: this 404 sits under /plan exactly as its
                        # sibling below sits under /machines, and it was the one page with
                        # neither a highlighted section nor a stale-data warning.
                        % (_nav("/plan", st), _esc(key), _esc(path_of(key)))), 404)
                with st.lock:
                    result = st.solver().solve(key, qty)
                title = "%s x%d" % (result["target_name"], qty)
                return self._send(_wrap_fragment(
                    title, render_html(result, st.graph), st, _crumb(title),
                    path="/plan"))
            if parts.path == "/machines":
                return self._send(machines_page(st, one("m"), one("q")))
            if parts.path == "/machine":
                page, status = machine_page(st, one("uid"))
                return self._send(page, status)
            if parts.path == "/sources":
                return self._send(sources_page(st, one("m")))
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

    def _redirect(self, back, msg=""):
        """303 back to where the form was submitted from, carrying a one-line result."""
        sep = "&" if "?" in back else "?"
        self.send_response(303)
        self.send_header("Location", "%s%sm=%s" % (back, sep, urllib.parse.quote(msg)))
        self.end_headers()

    def do_POST(self):
        parts = urllib.parse.urlparse(self.path)
        if parts.path not in ("/machines", "/reload", "/sources"):
            return self._send("", 404)
        length = int(self.headers.get("Content-Length") or 0)
        form = urllib.parse.parse_qs(self.rfile.read(length).decode("utf-8"))
        st = self.state
        if parts.path == "/sources":
            return self._redirect("/sources", self._edit_sources(form))
        if parts.path == "/reload":
            # Blocking on purpose: loading a 72 MB graph takes tens of seconds, and doing it
            # in the background while still serving the old one would mean the page you land
            # on can still be stale. The user asked for it and gets to wait for it.
            with st.lock:
                st.load_all()
            self.send_response(303)
            self.send_header("Location", _safe_back(form, "/"))
            self.end_headers()
            return

        uid = (form.get("uid") or [""])[0]
        target = (form.get("state") or [""])[0]
        # Return the user to wherever they toggled from, so a change made on a detail page
        # does not silently bounce them to the list.
        #
        # `back` arrives from a form field, so it is an open-redirect vector. Checking only
        # `startswith("/")` is NOT enough: `//evil.example/x` passes that and is a valid
        # protocol-relative URL that navigates off-site. Require a parse with no scheme and
        # no netloc, which is the only form that cannot leave this server.
        back = _safe_back(form)
        msg = ""
        if uid and target in machines_mod.STATES:
            # Read, modify and write all inside the lock: two toggles in flight together
            # would otherwise both start from the old map and the second save would drop
            # the first one's change.
            with st.lock:
                overrides = dict(st.overrides)
                overrides[uid] = target
                machines_mod.save_overrides(st.machines_path, overrides)
                # Costs depend on machine state, so both must be recomputed together or
                # plans would rank against stale availability.
                st.refresh_machines()
            msg = "%s set to %s" % (uid, target)
        self._redirect(back, msg)

    def _edit_sources(self, form):
        """Apply one /sources form action and return the line to show. Never raises.

        Same POST-then-refresh-then-303 shape as the machine toggle, because it is the
        same kind of thing: a user judgement the world scan cannot make. Costs are
        recomputed inside the lock -- an infinite source changes what every plan prefers,
        so leaving the cost table behind would rank against the old answer.
        """
        st = self.state
        action = (form.get("do") or [""])[0]
        key = (form.get("key") or [""])[0].strip()
        block = (form.get("block") or [""])[0].strip()
        # The whole read-modify-write is inside the lock. This is a ThreadingHTTPServer,
        # so two clicks in flight together would otherwise both read the old overrides
        # and the second save would drop the first one's change.
        with st.lock:
            return self._apply_source_edit(action, key, block)

    def _apply_source_edit(self, action, key, block):
        """The body of a /sources edit. Caller holds the lock."""
        st = self.state
        ov = st.source_overrides
        gens = dict(ov.get("generators") or {})
        off = set(ov.get("disabled") or ())
        water = ov.get("vanilla_water", True)

        if action == "add":
            if not block or not key:
                return "give both a block id and what it makes"
            if key not in st.graph.names and not st.graph.producers(key) \
                    and not key.startswith("fluid:"):
                # A typo would silently make nothing free, which looks identical to the
                # feature not working.
                return "no item or fluid called %s -- pick one from the list" % key
            gens.setdefault(block, [])
            if key in gens[block]:
                return "%s already makes %s" % (block, key)
            gens[block] = gens[block] + [key]
            msg = "%s now makes %s" % (block, key)
        elif action == "forget" and block:
            if block not in gens:
                return "%s was not added by hand" % block
            del gens[block]
            msg = "removed %s" % block
        elif action == "disable" and key:
            off.add(key)
            msg = "%s is no longer treated as free" % key
        elif action == "enable" and key:
            off.discard(key)
            msg = "%s can be free again" % key
        elif action == "vanilla":
            water = not water
            msg = "vanilla infinite water %s" % ("on" if water else "off")
        else:
            return ""

        generators_mod.save_overrides(st.sources_path, gens, off, water)
        st.refresh_machines()
        return msg


def serve(graph_path, have_path, machines_path, host=DEFAULT_HOST,
          port=DEFAULT_PORT,
          sources_path=None, tokens_path=None):
    state = State(graph_path, have_path, machines_path, sources_path, tokens_path)
    Handler.state = state
    httpd = ThreadingHTTPServer((host, port), Handler)
    return httpd, state
