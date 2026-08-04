"""Render a solved plan as a node-link diagram.

WHY A SECOND VIEW. The nested `<details>` tree is the right shape for reading a plan step
by step, and the wrong shape for seeing where a chain converges or how deep it runs. A
left-to-right dendrogram answers "how many stages is this" and "which intermediates feed
several branches" at a glance, which is the question the tree makes you reconstruct.

LAYOUT IS COMPUTED HERE, IN PYTHON, not in the browser. It is a pure function of the solved
tree, so the same plan always draws identically, the SVG is complete on arrival with no
layout flash, and there is no JS layout engine to ship under the artifact CSP. Depth gives
one axis; the order leaves are visited gives the other; a parent centres on its children.

TWO ORIENTATIONS, ONE LAYOUT. Jake: *"the tree should be able to go left to right OR top to
bottom."* `layout` is orientation-free -- it assigns every node a (depth, row) pair -- and
only the two coordinate functions and the edge curve differ. Both SVGs are rendered and
shipped together, with CSS choosing which is visible, because the alternative is a round
trip that RE-SOLVES the plan, and a plan can take two minutes (see defaults.MAX_NODES_CEILING).
Measured on the reference pack: a diagram is 5 to 16 KB of a 48 to 67 KB page, so the second
copy costs about a fifth of the page and the toggle is instant. See #35.

A BOX IS THE SAME SIZE IN BOTH, which is why this is not a true transpose. A label is
budgeted against BOX_W by `_label_limit`, and swapping the axes literally would give each
box `ROW` = 30px of width and truncate every label to two characters. Top-down keeps the
box and spaces siblings out sideways instead, so the label budget never moves.

DO NOT reach for a force-directed layout. A plan is a tree with a meaningful root and a
meaningful direction, and a force layout would throw both away in exchange for wobbling.
"""

from .htmlutil import esc as _esc
from .htmlutil import item_href
from .present import STATE_LABEL, STATUS_STYLE, is_roadblock, status_badge, status_legend
from .solve import STATUS_CRAFT, STATUS_TOKEN
from .tokens import KIND_LABEL

# Geometry. Rows are tight because a real plan is tall and narrow: 100 nodes at 30px is
# already 3,000px of scroll, and anything looser stops being scannable.
ROW = 30
COL = 254
BOX_H = 24
BOX_W = 226
# The corner every box gets. A pack placeholder gets `TOKEN_RX` instead, which is the one
# thing about a box that is not geometry; see it for why the shape carries that and not a
# colour.
BOX_RX = 6
PAD_X = 18
PAD_Y = 20
# A label does not wrap in SVG. It runs straight under the right-aligned quantity, so the
# cap is a hard limit and not a preference -- and it cannot be one NUMBER, because the
# quantity it has to stay clear of is anywhere from "64" to "768,000 mB".
#
# A flat 20 was wrong in both directions, measured in chromium against the reference pack:
# `Sodium Fluoride Sol...` overlapped `85,248 mB` by 6.8px, while `Boron Ore` next to `64`
# had 116px of empty box it could have used. `_label_limit` spends the interior instead.
#
# Advances measured from the rendered SVG rather than assumed: the label is 12px var(--sans)
# at 7.2px per character and the quantity 10.5px var(--mono) at 6.3px, both averaged over
# the 14 boxes of a real Borax plan. They are estimates by nature -- the exact width depends
# on which glyphs -- so GUTTER buys back the error.
LABEL_PX = 7.2
QTY_PX = 6.3
GUTTER = 6
# The span between where the label starts and where the quantity ends: see the box template.
LABEL_X = 26
QTY_RIGHT_PAD = 6
# The historic flat cap, kept only as the number `_shorten` falls back to when no budget is
# given. NOT what gets drawn: the box template shortens against `_label_limit`.
MAX_LABEL = 20

# "+ more not drawn" hangs to the RIGHT of a truncated node's box, outside it, so the
# canvas has to make room or the note is clipped away on the deepest column -- which is
# exactly where a cut node is. 8px offset plus the rendered width of the string at 10.5px.
CUT_NOTE_W = 96


def _label_limit(qty_text):
    """How many characters fit left of a right-aligned `qty_text` inside one box."""
    room = BOX_W - LABEL_X - QTY_RIGHT_PAD - GUTTER - len(qty_text) * QTY_PX
    return max(4, int(room // LABEL_PX))

# Which way the tree runs. `lr` is the original dendrogram: depth on x, leaf order on y.
LR = "lr"
TD = "td"
ORIENTATIONS = (LR, TD)
# What the toggle and the panel heading call each one. ONE map, so the button, the caption
# and the aria label cannot end up describing different things.
ORIENTATION_LABEL = {LR: "left to right", TD: "top to bottom"}

# Top-down spacing. The box keeps BOX_W so the label budget does not move, so siblings need
# a full box plus a gutter between them on the x axis, and depth is a short step down the y.
TD_COL = BOX_W + 12
TD_ROW = 78

KIND_MARK = {"fluid": "F", "essentia": "E", "ore": "*"}

# HOW A PACK PLACEHOLDER IS DRAWN, and the two constants are one decision. #174: a token was
# indistinguishable from an ingredient you have to go and get, in the same fill, with a
# quantity, on a page whose key calls that fill "NEED, go get".
#
# A THIRD CHANNEL, AND IT HAD TO BE SHAPE. Fill says what the plan does with the item and the
# dashed outline says whether a machine is in the way, so both existing channels are spoken
# for.
#
# NOT A COLOUR. `present.STATUS_STYLE` argues the shared `--need` fill for token and raw in a
# comment, `NodeStatus.badge` argues it again in Java, and `status_legend` groups them so the
# key reads as one red row; a ninth ink would have to defeat all three. NOT A WORD EITHER:
# the Java side measured a 15-character badge inside a 148px node and got a label of zero
# characters, and this box is 226px with the same competition. Shape and glyph cost none of
# the interior, and both survive being read by someone who cannot discriminate the fills --
# which the fill/outline pair does not, since a dashed red box and a dashed grey box differ
# only by hue.
#
# TWO CARRIERS BECAUSE ONE WAS NOT ENOUGH, measured at 1x on the reported Osiris Spinel plan:
# a stadium among 107 rounded rectangles is findable once you know to look for it and easy to
# scan past when you do not. The swatch is the diagram's other text slot.
#
# `!` and NOT `?`: `_mark` already returns "?" for a node with no label at all, so a question
# mark would mean two things in one slot. A placeholder is a note rather than a puzzle, which
# is the other half of the choice.
TOKEN_RX = BOX_H / 2.0
TOKEN_MARK = "!"


def _hue(key):
    """A stable hue per mod, so items from one mod read as a family.

    THE DIAGRAM KEEPS THE HUE EVEN THOUGH REAL ICONS NOW EXIST (#36 renders an atlas, and
    the tree, the tables and the search rows all draw it). The two answer different
    questions and the diagram wants this one: a flow diagram is scanned for STRUCTURE, and
    "these six boxes are all Thermal Expansion" is the thing a colour can say at a glance
    and a 16x16 sprite cannot. The initial stays for the same reason -- it is legible at the
    size a box label is drawn, where an icon would be one more small picture among fifty.

    DO NOT swap this for `iconset` on the grounds that the icons are available now. If a
    diagram ever wants both, they go side by side; the hue is not a placeholder waiting to
    be replaced, which is what it was before the atlas existed.
    """
    modid = str(key).split(":")[0]
    h = 0
    for ch in modid:
        h = (h * 31 + ord(ch)) % 360
    return h


def _mark(node):
    """One character for the swatch: a placeholder, then the type, then the name's initial.

    THE PLACEHOLDER WINS OVER THE TYPE, in that order deliberately. "This is not a thing you
    obtain" is the fact a reader is being misled about, and "it is a fluid" is a detail about
    a thing that does not exist. Every curated token is a `contenttweaker` item today, so the
    order costs nothing now and is the right way round the day one stands in for a fluid.
    See TOKEN_MARK for why the glyph is `!`.
    """
    if node.get("status") == STATUS_TOKEN:
        return TOKEN_MARK
    kind = node.get("kind", "item")
    if kind in KIND_MARK:
        return KIND_MARK[kind]
    # `full`, not the shortened `label`: only the first character is used, so truncating
    # first bought nothing, and the record no longer carries a shortened form at all.
    label = (node.get("full") or node.get("label") or node.get("name")
             or node.get("key") or "?").strip()
    return label[0].upper() if label else "?"


def _shorten(text, limit=MAX_LABEL):
    text = str(text)
    return text if len(text) <= limit else text[: limit - 1] + "…"


def layout(tree, max_nodes=400):
    """Assign (depth, row) to every node, breadth-limited.

    Returns (nodes, links, rows). `visit` returns each node's row as it unwinds, so a
    parent can centre itself on the rows its children took without a second pass.

    Truncation is BY SUBTREE, not by a flat node count: cutting off mid-level would draw a
    branch whose children silently vanish. A node that was cut says so.
    """
    nodes, links = [], []
    counter = {"row": 0, "made": 0}

    def visit(node, depth, parent_index):
        if counter["made"] >= max_nodes:
            return None
        index = len(nodes)
        counter["made"] += 1
        record = {
            "i": index,
            "depth": depth,
            "key": node.get("key", ""),
            # ONE label on the record, unshortened. There used to be a `label` shortened
            # to a flat MAX_LABEL as well, and nothing drew it: the box template shortens
            # `full` against `_label_limit`, and `_mark` reads one character. A second
            # truncated copy with no consumer is a thing a reader has to rule out.
            "full": node.get("label") or node.get("name") or node.get("key"),
            "kind": node.get("kind", "item"),
            "status": node.get("status", STATUS_CRAFT),
            # The two refinements `present.status_badge` needs to word a status. The record
            # dropped both, so the diagram was the one surface that could not tell a pack
            # placeholder from a missing ingredient, or a NEED from a NEED nothing can make.
            # Same defect as `machine_state` below, one field list later. See #174 and #136.
            "token_kind": node.get("token_kind") or "",
            "unsourced": bool(node.get("unsourced")),
            "need": node.get("need", 1),
            "machine": node.get("machine") or node.get("category") or "",
            # Carried so the diagram can SHOW a roadblock rather than only mention it in a
            # hover title. The solver has always written it and this record dropped it, which
            # is why the machine was hover-only here while the tree printed it inline. See
            # #37.
            "machine_state": node.get("machine_state") or "",
            "row": 0,
            "cut": False,
        }
        nodes.append(record)
        if parent_index is not None:
            links.append((parent_index, index))

        kids = node.get("children") or []
        child_rows = []
        for kid in kids:
            got = visit(kid, depth + 1, index)
            if got is None:
                record["cut"] = True
                break
            child_rows.append(got)

        if child_rows:
            # Centre on the children, so a converging chain reads as converging.
            record["row"] = (child_rows[0] + child_rows[-1]) / 2.0
        else:
            record["row"] = counter["row"]
            counter["row"] += 1
        return record["row"]

    visit(tree, 0, None)
    rows = max(counter["row"], 1)
    return nodes, links, rows


def _render_legend(nodes):
    """The key for a set of laid-out nodes, or "" when there is nothing to key.

    PRIVATE, and takes the LAID-OUT nodes rather than the tree, because a plan can be
    truncated at `max_nodes` and the key must describe the boxes actually on the page.
    Exposing it would invite a caller with a different node set and a key that quietly
    disagrees with the diagram beside it.

    Three encodings, because the diagram carries three independent facts. FILL is what the
    plan does with the item, from the same `present` tables the boxes use. SHAPE is whether
    the box is an item at all. OUTLINE is whether the step waits on a machine. Neither of the
    last two is a colour, precisely so they cannot compete with the fill.

    Fills arrive as `var(--token)`, so they go in a style attribute: the swatch has to be the
    exact colour of the box it explains, and that colour is only knowable from the token.

    EVERY CHANNEL ON THE PAGE IS KEYED HERE, including the two that are not fills. The
    design note on #174 argued against a fourth legend row on the grounds that the legend
    keys fills and a token is not a new fill. That argument holds against a new colour and
    not against this: the dashed-outline row beside it is already a non-fill entry, and a
    reader who meets a shape they were never told about has to guess. An unexplained
    encoding is the same defect as no encoding.
    """
    rows = ['<li><span class="sw" style="background:%s;border-color:%s"></span>%s</li>'
            % (fill, ink, _esc(labels))
            for fill, ink, labels in status_legend({n["status"] for n in nodes})]
    if any(n["status"] == STATUS_TOKEN for n in nodes):
        # Worded to stand on its own rather than pointing at the plan page's "Not crafted,
        # obtained" panel: `render_diagram` is public and a caller can draw the diagram
        # without that panel, and a key naming a card that is not on the page is worse than
        # a key that explains itself.
        rows.append('<li><span class="sw tok"></span>not an item: something to do, '
                    'find or unlock</li>')
    if any(is_roadblock(n["machine_state"]) for n in nodes):
        rows.append('<li><span class="sw dash"></span>needs a machine you do not have</li>')
    if not rows:
        return ""
    return '<ul class="legend">%s</ul>' % "".join(rows)


def _geometry(orientation, nodes, rows):
    """`(width, height, place, edge)` for one orientation over an already-laid-out tree.

    THE ONLY THING THAT DIFFERS between the two views. `layout` assigns (depth, row) and
    knows nothing about direction, so a second orientation is these four values and not a
    second layout pass -- which also guarantees both SVGs draw the same node set, and
    therefore that the one legend beside them is true of both.
    """
    depth_max = max(n["depth"] for n in nodes)
    row_max = max(n["row"] for n in nodes)
    # Room for a "+ more not drawn" note, and only when one is actually drawn.
    cut = CUT_NOTE_W if any(n["cut"] for n in nodes) else 0
    if orientation == TD:
        # `row_max`, not `rows`: `rows` is a COUNT and the last box starts at index
        # rows - 1, so using the count left a whole empty 238px column on the right. LR got
        # this right with `depth_max` and the two formulas were written apart.
        width = PAD_X * 2 + row_max * TD_COL + BOX_W + cut
        height = PAD_Y * 2 + depth_max * TD_ROW + BOX_H

        def place(n):
            return PAD_X + n["row"] * TD_COL, PAD_Y + n["depth"] * TD_ROW

        def edge(p, c):
            # Vertical control points, mirroring the horizontal ones below for the same
            # reason: a parent with many children fans out and straight lines overlap.
            x1, y1 = place(p)[0] + BOX_W / 2, place(p)[1] + BOX_H
            x2, y2 = place(c)[0] + BOX_W / 2, place(c)[1]
            mid = (y1 + y2) / 2
            return (x1, y1, x1, mid, x2, mid, x2, y2)

        return width, height, place, edge

    width = PAD_X * 2 + depth_max * COL + BOX_W + cut
    height = PAD_Y * 2 + rows * ROW

    def place(n):
        return PAD_X + n["depth"] * COL, PAD_Y + n["row"] * ROW

    def edge(p, c):
        x1, y1 = place(p)[0] + BOX_W, place(p)[1] + BOX_H / 2
        x2, y2 = place(c)[0], place(c)[1] + BOX_H / 2
        mid = (x1 + x2) / 2
        return (x1, y1, mid, y1, mid, y2, x2, y2)

    return width, height, place, edge


def render_diagrams(tree, max_nodes=400, orientations=ORIENTATIONS):
    """`({orientation: svg}, legend)` from ONE `layout` pass.

    `render_html` needs both orientations, and calling `render_diagram` twice laid the tree
    out twice and built the legend twice -- while three comments in this module and in
    `render.py` promised a single pass. The guarantee that both SVGs draw the same node set
    rested on `layout` being deterministic rather than on it being called once; now it is
    structural.
    """
    nodes, links, rows = layout(tree, max_nodes)
    if not nodes:
        return {o: '<div class="meta">Nothing to draw.</div>' for o in orientations}, ""
    return ({o: _render_svg(nodes, links, rows, o) for o in orientations},
            _render_legend(nodes))


def render_diagram(tree, max_nodes=400, orientation=LR):
    """`(svg, legend)` for a solved plan tree. No script, no external assets.

    ONE entry point returning both, deliberately. The legend has to describe the boxes that
    were drawn, and a plan gets truncated at `max_nodes`, so building it from a second
    independent walk of the tree could name a colour that is not on the page. Both come out
    of one `layout`.

    The single-orientation wrapper. `render_html` wants both and calls `render_diagrams`,
    which lays out once.
    """
    svgs, legend = render_diagrams(tree, max_nodes, (orientation,))
    return svgs[orientation], legend


def _render_svg(nodes, links, rows, orientation):
    """One orientation's SVG over an already-laid-out tree."""
    if orientation not in ORIENTATIONS:
        raise ValueError("unknown orientation %r" % (orientation,))
    width, height, place, edge = _geometry(orientation, nodes, rows)

    def x(n):
        return place(n)[0]

    def y(n):
        return place(n)[1]

    # Links first so boxes sit on top of them. Cubic beziers with control points along the
    # flow axis keep every edge readable where many share a parent; straight lines overlap
    # into a fan.
    edges = ['<path d="M%.1f %.1f C%.1f %.1f %.1f %.1f %.1f %.1f"/>'
             % edge(nodes[parent], nodes[child]) for parent, child in links]

    boxes = []
    for n in nodes:
        fill, ink = STATUS_STYLE.get(n["status"], STATUS_STYLE[STATUS_CRAFT])
        nx, ny = x(n), y(n)
        blocked = is_roadblock(n["machine_state"])
        token = n["status"] == STATUS_TOKEN
        word, _cls = status_badge(n["status"], n["token_kind"], n["unsourced"])
        # THE WORD, WHICH EVERY OTHER SURFACE HAS AND THIS ONE DID NOT. The box carries no
        # text but the label and the quantity, so the fill was the whole of what it said
        # about a node, and two statuses share a fill by an argued decision in
        # `present.STATUS_STYLE`. A reader hovering a red box could not find out which red it
        # was. `status_badge` is the same call the tree row makes, so the two cannot drift.
        title = "%s × %s  · %s%s" % (n["full"], "{:,}".format(n["need"]), word,
                                     ("  · " + n["machine"]) if n["machine"] else "")
        if token and n["token_kind"] in KIND_LABEL:
            # The kind's whole sentence, not the badge word again: "go get" says what to do
            # and "found by playing" says what the thing IS, which is the fact a reader who
            # mistook it for an item needs.
            title += "  · %s" % KIND_LABEL[n["token_kind"]]
        if blocked:
            # Still in the title, because the outline says THAT a step is blocked and only
            # the words say by what and how badly.
            title += "  · machine %s" % STATE_LABEL.get(n["machine_state"],
                                                        n["machine_state"])
        qty = "{:,}".format(n["need"])
        if n["kind"] == "fluid":
            qty += " mB"
        # Shortened HERE and not in `layout`, because the budget depends on the quantity
        # beside it and `layout` does not format one. The record carries only `full`; a
        # second flat-capped copy used to sit beside it with nothing reading it.
        shown = _shorten(n["full"], _label_limit(qty))
        # A DIFFERENT CHANNEL from the fill, deliberately. Fill already carries the plan's
        # status for the item and machine availability is a separate axis, so encoding it as
        # another colour would make two facts compete for one signal. A dashed, full-opacity
        # outline is legible at a glance, costs none of the box's 226px of interior, and
        # cannot be confused with a fill.
        box_stroke = ' stroke-opacity="1" stroke-width="1.6" stroke-dasharray="4 2.5"' \
            if blocked else ' stroke-opacity=".35"'
        inner = (
            '<rect x="%.1f" y="%.1f" width="%d" height="%d" rx="%.1f" fill="%s"'
            ' stroke="%s"%s/>'
            '<rect x="%.1f" y="%.1f" width="17" height="17" rx="4"'
            ' fill="hsl(%d 42%% 52%%)"/>'
            '<text x="%.1f" y="%.1f" class="mk">%s</text>'
            '<text x="%.1f" y="%.1f" class="lb" fill="%s">%s</text>'
            '<text x="%.1f" y="%.1f" class="qt" fill="%s">%s</text>'
            % (nx, ny, BOX_W, BOX_H, TOKEN_RX if token else BOX_RX, fill, ink, box_stroke,
               nx + 4, ny + 3.5, _hue(n["key"]),
               nx + 12.5, ny + 15.5, _esc(_mark(n)),
               nx + LABEL_X, ny + 16, ink, _esc(shown),
               nx + BOX_W - QTY_RIGHT_PAD, ny + 16, ink, qty))
        # NO LINK ON A TOKEN, and this is the reported half of #174. `/plan?item=` for a
        # placeholder answers with a one-node plan whose entire content is the placeholder,
        # which reads as the planner failing rather than as "this is not an item". The
        # capability it costs -- seeing what else the token gates -- was never on this link
        # anyway: a plan shows what is UNDER a node, and the token is a leaf.
        boxes.append('<g class="nd"><title>%s</title>%s</g>'
                     % (_esc(title), inner if token
                        else '<a href="%s">%s</a>' % (item_href(n["key"]), inner)))
        if n["cut"]:
            boxes.append('<text x="%.1f" y="%.1f" class="cut">+ more not drawn</text>'
                         % (nx + BOX_W + 8, ny + 16))

    return ('<svg class="diagram" data-dir="%s" viewBox="0 0 %d %d" width="%d" height="%d" '
            'role="img" aria-label="crafting plan diagram, %s">'
            '<g class="lk">%s</g>%s</svg>'
            % (orientation, width, height, width, height,
               _esc(ORIENTATION_LABEL[orientation]), "".join(edges), "".join(boxes)))


DIAGRAM_CSS = """
.diagram{display:block}
.diagram .lk path{fill:none;stroke:var(--line);stroke-width:1.4}
.diagram .nd a{cursor:pointer}
/* Gated like every other :hover in this project: a touch browser leaves the state
   applied to the last node tapped, so one box stays highlighted while you read.
   ON THE LINK, NOT ON THE GROUP: a token node has no `<a>` (see `_render_svg`), and
   highlighting a box that cannot be clicked is the same false promise the link was. */
@media(hover:hover){.diagram .nd a:hover rect:first-of-type{stroke-opacity:1}}
.diagram text{font-family:var(--sans);font-size:11.5px;dominant-baseline:auto}
.diagram .mk{font-family:var(--mono);font-size:10.5px;font-weight:700;fill:#fff;
text-anchor:middle}
.diagram .lb{font-size:12px}
.diagram .qt{font-family:var(--mono);font-size:10.5px;text-anchor:end;opacity:.75;
font-variant-numeric:tabular-nums}
.diagram .cut{font-size:10.5px;fill:var(--dim);font-style:italic}
.diagram a:focus-visible rect:first-of-type{stroke:var(--accent);stroke-opacity:1;
stroke-width:2}
/* The diagram is wide by nature, so it scrolls inside its own box and never makes the
   page scroll sideways. */
.diagwrap{overflow:auto;max-height:74vh;border:1px solid var(--line);border-radius:11px;
background:var(--card);padding:6px}
/* Both orientations are in the DOM and CSS picks one. `display:none` rather than the
   `hidden` attribute: an SVG carrying width/height keeps its box under the UA's
   `[hidden]{display:none}` if any author `display` rule wins against it, which is the
   exact trap tools/mobile-audit.js exists to catch. */
.diagwrap[data-dir="lr"] .diagram[data-dir="td"],
.diagwrap[data-dir="td"] .diagram[data-dir="lr"]{display:none}
/* Colour key. Sits ABOVE the diagram: it is what makes the fills readable, so a reader
   should meet it before the boxes, not after scrolling a tall plan. Wraps rather than
   scrolls, because it is short and losing an entry off the right edge would defeat it. */
.legend{display:flex;flex-wrap:wrap;gap:5px 15px;list-style:none;margin:0 0 11px;padding:0;
font-size:11.5px;color:var(--dim)}
.legend li{display:flex;align-items:center;gap:6px}
.legend .sw{width:11px;height:11px;border-radius:3px;border:1px solid;flex:0 0 auto}
/* The outline key. Transparent fill on purpose: this entry is about the BORDER, and giving
   it a background would read as one more fill colour, which is the confusion the separate
   channels exist to avoid. (It said "a fifth fill" and there are five already: the four
   semantic ones plus `--mutedbg`. The count was the wrong part of a right argument.) */
.legend .sw.dash{background:transparent;border:1.5px dashed var(--dim);border-radius:3px}
/* The shape key, transparent for the same reason as the outline key above. A token box is
   drawn as a stadium (`graphview.TOKEN_RX`) and the swatch is square, so at 11px this
   renders as a circle against the fill swatches' rounded squares. */
.legend .sw.tok{background:transparent;border:1.5px solid var(--dim);border-radius:99px}
"""
