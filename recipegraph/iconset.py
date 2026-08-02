"""Turn an item key into a picture, for the two surfaces that need it differently. #36

TWO DELIVERY SHAPES, AND THE SPLIT IS FORCED BY A CONSTRAINT RATHER THAN CHOSEN:

  * THE LIVE SERVER serves the atlas pages as ordinary images and positions them with CSS.
    Nothing is decoded, nothing is copied per row, and one 2048x2048 page covers 16,384
    sprites in a single request the browser then caches. That is `css` below.
  * A STANDALONE DOCUMENT -- `plan --html`, anything published as a Claude Artifact -- runs
    under a CSP that blocks every off-host request, which is the rule `render.py`'s module
    docstring records and the reason there are no external assets anywhere in the
    renderers. So its icons have to be `data:` URIs cut out of the atlas one at a time.
    That is `data_uri` below, and it is what `png.py` exists for.

Inlining a whole page instead of cutting sprites was the obvious way to avoid a PNG decoder
and it does not fit: a 2048x2048 page is megabytes, base64 adds a third, and a plan
mentioning items from three pages would carry them all for the twenty pictures it draws.

KEYED BY `damage_base`, THEN BY `base_key`, IN THAT ORDER. The atlas is rendered per base
item key with damage variants already collapsed (see IconAtlas.addIconTarget), so a worn
tool has to lose its durability meta before an NBT digest is stripped -- do it the other way
and `minecraft:iron_axe:187#abc` looks up `minecraft:iron_axe:187`, which the atlas never
rendered. An item whose look is NBT-driven draws its default, which is the honest meaning of
"the icon for this item".
"""

import base64
import os
import struct
import zlib

from . import png
from .model import base_key

#: Where the server publishes the atlas pages. One place, because the CSS `background-image`
#: and the request handler have to agree and they live in different files.
URL_PREFIX = "/icons/"


def locate(graph, key):
    """`(page index, column, row)` for `key`, or None when the atlas has no sprite for it."""
    index = getattr(graph, "icons", None) or {}
    keys = index.get("keys") or {}
    if not keys:
        return None
    undamaged = graph.damage_base(key)
    for candidate in (key, undamaged, base_key(undamaged)):
        at = keys.get(candidate)
        if at:
            return tuple(at)
    return None


def css(graph, key):
    """An inline `style` value drawing `key`'s sprite from a served atlas page, or "".

    `background-position` is NEGATIVE offsets into the page, which is the standard sprite
    trick and the reason nothing has to be cut: the element is one sprite wide and tall, and
    the page slides underneath it.
    """
    at = locate(graph, key)
    if not at:
        return ""
    index = graph.icons
    size = index["icon"]
    page, col, row = at
    return ("background-image:url(%s%d.png);background-position:-%dpx -%dpx;"
            "width:%dpx;height:%dpx"
            % (URL_PREFIX, page, col * size, row * size, size, size))


#: The one CSS class every icon wears, so `render.CSS` sizes and aligns them in one rule
#: whichever delivery shape produced the element.
CSS_CLASS = "ico"


def resolver(graph, data_dir=None, inline=False):
    """`key -> an HTML snippet drawing it`, or "" when the atlas has no sprite.

    ONE CALLABLE HANDED TO THE RENDERERS, rather than the renderers choosing a delivery
    shape. `render_html` produces both the standalone `plan --html` document and the
    fragment the server wraps, so it cannot know which constraint it is under -- and the
    two are not interchangeable: a served page must NOT inline megabytes it could cache,
    and a published artifact must not reference a host it cannot reach. The caller knows,
    so the caller decides, and a renderer that forgets to ask gets no icons rather than
    broken ones.
    """
    if not (getattr(graph, "icons", None) or {}).get("keys"):
        return lambda key: ""
    if inline:
        inliner = Inliner(graph, data_dir or ".")
        size = graph.icons["icon"]

        def draw(key):
            uri = inliner.data_uri(key)
            if not uri:
                return ""
            # `alt=""` and aria-hidden: the name is already in the row beside it, so a
            # screen reader announcing the item twice is noise, not access.
            return ('<img class="%s" src="%s" alt="" aria-hidden="true" '
                    'width="%d" height="%d">' % (CSS_CLASS, uri, size, size))
        return draw

    def draw_css(key):
        style = css(graph, key)
        return ('<span class="%s" style="%s"></span>' % (CSS_CLASS, style)) if style else ""
    return draw_css


def page_bytes(graph, page, data_dir):
    """The raw PNG for atlas page `page`, or None. For the server's `/icons/N.png`.

    The page NAME comes from the index rather than from the URL, so a request cannot name a
    path: the number is used only to look up a filename this build wrote, and anything that
    does not index into `pages` is simply absent. A tool with no auth on a LAN port does not
    need to be the thing that serves arbitrary files out of `data/`.
    """
    index = getattr(graph, "icons", None) or {}
    pages = index.get("pages") or []
    if not isinstance(page, int) or page < 0 or page >= len(pages):
        return None
    path = os.path.join(data_dir, os.path.basename(pages[page]))
    try:
        with open(path, "rb") as fh:
            return fh.read()
    except (IOError, OSError):
        return None


class Inliner:
    """Cuts sprites out of the atlas as `data:` URIs, decoding each page at most once.

    STATEFUL BECAUSE DECODING IS THE EXPENSIVE PART. A page is 4.2 million pixels and this
    is a pure-python decoder; doing it per icon would make a twenty-item plan decode twenty
    times. One instance per document, so the cost is one decode per page the document
    actually touches -- usually one.

    A page that fails to decode is remembered as failed, so a broken file costs one attempt
    rather than one per row. Every failure yields no icon, never a wrong one.
    """

    def __init__(self, graph, data_dir):
        self.graph = graph
        self.data_dir = data_dir
        self._pages = {}
        self._sprites = {}

    def _page(self, page):
        if page not in self._pages:
            raw = page_bytes(self.graph, page, self.data_dir)
            try:
                self._pages[page] = png.decode(raw) if raw else None
            except (png.Unsupported, zlib.error, ValueError, IndexError, struct.error):
                # Every one of these means "this file is not a page we can read". A truncated
                # copy raises IndexError out of the unfilter, a corrupt one zlib.error, and a
                # short chunk header struct.error -- none of which is worth taking a plan
                # down for, because the fallback is a row with no picture.
                self._pages[page] = None
        return self._pages[page]

    def data_uri(self, key):
        """`data:image/png;base64,...` for `key`'s sprite, or "" when there is not one."""
        at = locate(self.graph, key)
        if not at:
            return ""
        if at in self._sprites:
            return self._sprites[at]
        self._sprites[at] = self._cut(at)
        return self._sprites[at]

    def _cut(self, at):
        page = self._page(at[0])
        if not page:
            return ""
        size = self.graph.icons["icon"]
        width, height, rgba = page
        try:
            sprite = png.crop(width, height, rgba, at[1] * size, at[2] * size, size)
        except png.Unsupported:
            return ""
        if png.is_blank(sprite):
            return ""
        return "data:image/png;base64," + base64.b64encode(
            png.encode(size, size, sprite)).decode("ascii")
