"""HTML escaping and link building, in one place.

Three modules had their own identical `_esc`. One of them eventually escaping differently
from the others is the kind of bug that only shows up as broken markup on the one page that
happened to use the odd one out.

The link builders are here for the same reason, and were added after that exact bug
happened to a hand-rolled one. See `item_href`.
"""

import html
import urllib.parse


def esc(value):
    """Escape for use in HTML text OR in a quoted attribute."""
    return html.escape(str(value), quote=True)


def _query(path, params, sep="&amp;"):
    """`path?a=1&amp;b=2`, every value percent-encoded, ready for an href attribute.

    Emits `&amp;` rather than a bare `&` because most callers drop the result straight
    into an HTML attribute, where a bare `&` opens an entity reference. The browser
    decodes it back before the request goes out, so the server sees ordinary separators.
    """
    parts = sep.join(
        "%s=%s" % (k, urllib.parse.quote(str(v), safe="")) for k, v in params)
    return "%s?%s" % (path, parts)


def item_href(key, qty=1):
    """Link to the plan for `key`.

    DO NOT hand-roll this as `esc(key).replace(":", "%3A")`. That was `graphview`'s
    previous implementation, and it encoded the colon while leaving the `#` of an
    NBT-discriminated key alone, so `forestry:can:1#d0ed7fc62e3c` produced

        /plan?item=forestry%3Acan%3A1#d0ed7fc62e3c&qty=1

    A browser reads everything from the `#` as a URL fragment and never sends it, so the
    server received `item=forestry:can:1`, a key that does not exist, and answered "No
    item with that id". The `qty` sat inside the discarded fragment too and was silently
    lost. 298,765 of 340,324 named keys carry a discriminator, so this broke a link to
    most of the graph, and it presented as missing data rather than as a broken link.
    See #23.

    `urllib.parse.quote` with `safe=""` encodes `#`, `:`, `&`, `/` and `?` alike, so no
    character in a key needs thinking about.
    """
    return _query("/plan", (("item", key), ("qty", int(qty))))


def plan_url(key, qty=1):
    """`item_href` as a plain URL, for somewhere that is not an href attribute.

    The `&amp;` in an href is correct there and WRONG anywhere it will be encoded again.
    Handing `item_href` to `urllib.parse.quote` as a return path produced
    `...%26amp%3Bqty%3D64`, which the server then read as a parameter called `amp;qty`:
    the link worked, the quantity silently reverted to 1. Two functions rather than one
    with a flag, because the caller always knows which of the two places it is writing
    into and a wrong flag is invisible until someone checks a query string.
    """
    return _query("/plan", (("item", key), ("qty", int(qty))), sep="&")


def machine_href(uid):
    """Link to the machine detail page for a JEI category uid."""
    return _query("/machine", (("uid", uid),))
