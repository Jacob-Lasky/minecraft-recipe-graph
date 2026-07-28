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
from .present import STATE_LABEL, STATUS_STYLE, is_roadblock, status_legend
from .solve import STATUS_CRAFT

# Geometry. Rows are tight because a real plan is tall and narrow: 100 nodes at 30px is
# already 3,000px of scroll, and anything looser stops being scannable.
ROW = 30
COL = 254
BOX_H = 24
BOX_W = 226
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


def _hue(key):
    """A stable hue per mod, so items from one mod read as a family.

    This is the honest substitute for real item textures. A registry id does not map to a
    texture path by any convention -- the mapping lives in each mod's models and its
    blockstate JSON -- so drawing the actual icon needs a sprite sheet rendered by the dump
    mod. Until then a per-mod colour plus an initial is a real signal rather than a
    decorative placeholder pretending to be an icon.
    """
    modid = str(key).split(":")[0]
    h = 0
    for ch in modid:
        h = (h * 31 + ord(ch)) % 360
    return h


def _mark(node):
    """One character for the swatch: the type for non-items, else the name's initial."""
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

    Two encodings, because the diagram carries two independent facts. FILL is what the plan
    does with the item, from the same `present` tables the boxes use. OUTLINE is whether the
    step waits on a machine, which is not a colour precisely so it cannot compete with the
    fill.

    Fills arrive as `var(--token)`, so they go in a style attribute: the swatch has to be the
    exact colour of the box it explains, and that colour is only knowable from the token.
    """
    rows = ['<li><span class="sw" style="background:%s;border-color:%s"></span>%s</li>'
            % (fill, ink, _esc(labels))
            for fill, ink, labels in status_legend({n["status"] for n in nodes})]
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
        title = "%s × %s%s" % (n["full"], "{:,}".format(n["need"]),
                                    ("  · " + n["machine"]) if n["machine"] else "")
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
        boxes.append(
            '<g class="nd"><title>%s</title>'
            '<a href="%s">'
            '<rect x="%.1f" y="%.1f" width="%d" height="%d" rx="6" fill="%s"'
            ' stroke="%s"%s/>'
            '<rect x="%.1f" y="%.1f" width="17" height="17" rx="4"'
            ' fill="hsl(%d 42%% 52%%)"/>'
            '<text x="%.1f" y="%.1f" class="mk">%s</text>'
            '<text x="%.1f" y="%.1f" class="lb" fill="%s">%s</text>'
            '<text x="%.1f" y="%.1f" class="qt" fill="%s">%s</text>'
            '</a></g>'
            % (_esc(title), item_href(n["key"]),
               nx, ny, BOX_W, BOX_H, fill, ink, box_stroke,
               nx + 4, ny + 3.5, _hue(n["key"]),
               nx + 12.5, ny + 15.5, _esc(_mark(n)),
               nx + LABEL_X, ny + 16, ink, _esc(shown),
               nx + BOX_W - QTY_RIGHT_PAD, ny + 16, ink, qty))
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
   applied to the last node tapped, so one box stays highlighted while you read. */
@media(hover:hover){.diagram .nd:hover rect:first-of-type{stroke-opacity:1}}
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
   it a background would read as a fifth fill colour, which is the confusion the two
   channels exist to avoid. */
.legend .sw.dash{background:transparent;border:1.5px dashed var(--dim);border-radius:3px}
"""
