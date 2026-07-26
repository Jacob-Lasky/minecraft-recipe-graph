"""Render a solved plan as a node-link diagram.

WHY A SECOND VIEW. The nested `<details>` tree is the right shape for reading a plan step
by step, and the wrong shape for seeing where a chain converges or how deep it runs. A
left-to-right dendrogram answers "how many stages is this" and "which intermediates feed
several branches" at a glance, which is the question the tree makes you reconstruct.

LAYOUT IS COMPUTED HERE, IN PYTHON, not in the browser. It is a pure function of the solved
tree, so the same plan always draws identically, the SVG is complete on arrival with no
layout flash, and there is no JS layout engine to ship under the artifact CSP. Depth gives
x; the order leaves are visited gives y; a parent centres on its children.

DO NOT reach for a force-directed layout. A plan is a tree with a meaningful root and a
meaningful direction, and a force layout would throw both away in exchange for wobbling.
"""

from .htmlutil import esc as _esc
from .present import STATUS_STYLE
from .solve import STATUS_CRAFT

# Geometry. Rows are tight because a real plan is tall and narrow: 100 nodes at 30px is
# already 3,000px of scroll, and anything looser stops being scannable.
ROW = 30
COL = 254
BOX_H = 24
BOX_W = 226
PAD_X = 18
PAD_Y = 20
# 20 characters at 12px leaves room for the right-aligned quantity inside BOX_W. A longer
# label does not wrap in SVG -- it runs straight under the number -- so this is a hard cap,
# not a preference.
MAX_LABEL = 20

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
    label = (node.get("label") or node.get("name") or node.get("key") or "?").strip()
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
            "label": _shorten(node.get("label") or node.get("name") or node.get("key")),
            "full": node.get("label") or node.get("name") or node.get("key"),
            "kind": node.get("kind", "item"),
            "status": node.get("status", STATUS_CRAFT),
            "need": node.get("need", 1),
            "machine": node.get("machine") or node.get("category") or "",
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


def render_svg(tree, max_nodes=400):
    """A self-contained <svg> for a solved plan tree. No script, no external assets."""
    nodes, links, rows = layout(tree, max_nodes)
    if not nodes:
        return '<div class="meta">Nothing to draw.</div>'

    depth_max = max(n["depth"] for n in nodes)
    width = PAD_X * 2 + depth_max * COL + BOX_W
    height = PAD_Y * 2 + rows * ROW

    def x(n):
        return PAD_X + n["depth"] * COL

    def y(n):
        return PAD_Y + n["row"] * ROW

    # Links first so boxes sit on top of them. Cubic beziers with horizontal control points
    # keep every edge readable where many share a parent; straight lines overlap into a fan.
    edges = []
    for parent, child in links:
        p, c = nodes[parent], nodes[child]
        x1, y1 = x(p) + BOX_W, y(p) + BOX_H / 2
        x2, y2 = x(c), y(c) + BOX_H / 2
        mid = (x1 + x2) / 2
        edges.append('<path d="M%.1f %.1f C%.1f %.1f %.1f %.1f %.1f %.1f"/>'
                     % (x1, y1, mid, y1, mid, y2, x2, y2))

    boxes = []
    for n in nodes:
        fill, ink = STATUS_STYLE.get(n["status"], STATUS_STYLE[STATUS_CRAFT])
        nx, ny = x(n), y(n)
        title = "%s × %s%s" % (n["full"], "{:,}".format(n["need"]),
                                    ("  · " + n["machine"]) if n["machine"] else "")
        qty = "{:,}".format(n["need"])
        if n["kind"] == "fluid":
            qty += " mB"
        boxes.append(
            '<g class="nd"><title>%s</title>'
            '<a href="/plan?item=%s&amp;qty=1">'
            '<rect x="%.1f" y="%.1f" width="%d" height="%d" rx="6" fill="%s"'
            ' stroke="%s" stroke-opacity=".35"/>'
            '<rect x="%.1f" y="%.1f" width="17" height="17" rx="4"'
            ' fill="hsl(%d 42%% 52%%)"/>'
            '<text x="%.1f" y="%.1f" class="mk">%s</text>'
            '<text x="%.1f" y="%.1f" class="lb" fill="%s">%s</text>'
            '<text x="%.1f" y="%.1f" class="qt" fill="%s">%s</text>'
            '</a></g>'
            % (_esc(title), _esc(n["key"]).replace(":", "%3A"),
               nx, ny, BOX_W, BOX_H, fill, ink,
               nx + 4, ny + 3.5, _hue(n["key"]),
               nx + 12.5, ny + 15.5, _esc(_mark(n)),
               nx + 26, ny + 16, ink, _esc(n["label"]),
               nx + BOX_W - 6, ny + 16, ink, qty))
        if n["cut"]:
            boxes.append('<text x="%.1f" y="%.1f" class="cut">+ more not drawn</text>'
                         % (nx + BOX_W + 8, ny + 16))

    return (
        '<svg class="diagram" viewBox="0 0 %d %d" width="%d" height="%d" '
        'role="img" aria-label="crafting plan diagram">'
        '<g class="lk">%s</g>%s</svg>'
        % (width, height, width, height, "".join(edges), "".join(boxes))
    )


DIAGRAM_CSS = """
.diagram{display:block}
.diagram .lk path{fill:none;stroke:var(--line);stroke-width:1.4}
.diagram .nd a{cursor:pointer}
.diagram .nd:hover rect:first-of-type{stroke-opacity:1}
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
"""
