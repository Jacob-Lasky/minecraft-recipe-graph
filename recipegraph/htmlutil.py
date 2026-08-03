"""HTML escaping and link building, in one place.

Three modules had their own identical `_esc`. One of them eventually escaping differently
from the others is the kind of bug that only shows up as broken markup on the one page that
happened to use the odd one out.

The link builders are here for the same reason, and were added after that exact bug
happened to a hand-rolled one. See `item_href`.
"""

import html
import json
import urllib.parse


def esc(value):
    """Escape for use in HTML text OR in a quoted attribute."""
    return html.escape(str(value), quote=True)


def script_json(value):
    """`value` as JSON safe to drop inside an inline `<script>` block.

    `json.dumps` alone is NOT safe there. An HTML parser looks for the literal `</script`
    before any JavaScript runs, so a single string containing it closes the block early and
    the rest of the page becomes markup. Nothing quotes its way out of that -- the escape
    has to happen at the JSON level.

    Not a threat model so much as a reason not to have a page whose correctness depends on
    nobody naming a mod oddly: the machines page ships JEI's own mod DISPLAY names, which
    are whatever 370-odd mod authors typed. Use this for anything going into a `<script>`,
    including payloads that happen to be ours today.

    U+2028 and U+2029 too: legal in JSON strings, and a line terminator in JavaScript
    source, so they end a statement early.
    """
    return (json.dumps(value, separators=(",", ":"), sort_keys=True)
            .replace("<", "\\u003c").replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029"))


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
    return _query("/plan", _plan_params(key, qty))


def _plan_params(key, qty, max_nodes=None):
    """The `/plan` query, in ONE place, in a fixed order.

    Three callers build this link -- an href, a plain return URL, and the go-deeper
    control -- and they differ only in the separator and whether the cap rides along. Left
    as three literals, `max_nodes` would be the parameter one of them forgets, which
    presents as a plan silently reverting to the default depth rather than as a broken
    link. The `qty` lost to `&amp;` encoding was exactly that bug once already.
    """
    params = [("item", key), ("qty", int(qty))]
    if max_nodes is not None:
        params.append(("max_nodes", int(max_nodes)))
    return tuple(params)


def deeper_href(key, qty, max_nodes):
    """Link to the same plan with a larger node cap. See #25.

    `max_nodes` is on the href rather than in a form, so the browser's back button walks
    back down through the caps a reader stepped up through, and a truncated plan can be
    bookmarked or shared at the depth it was read at.

    Absent from `item_href` on purpose: every OTHER link into a plan is a fresh question
    and should start at the default. Carrying a raised cap sideways into an unrelated item
    would make one slow page make every later page slow, with nothing on screen saying why.
    """
    return _query("/plan", _plan_params(key, qty, max_nodes))


def plan_url(key, qty=1, max_nodes=None):
    """`item_href` as a plain URL, for somewhere that is not an href attribute.

    The `&amp;` in an href is correct there and WRONG anywhere it will be encoded again.
    Handing `item_href` to `urllib.parse.quote` as a return path produced
    `...%26amp%3Bqty%3D64`, which the server then read as a parameter called `amp;qty`:
    the link worked, the quantity silently reverted to 1. Two functions rather than one
    with a flag, because the caller always knows which of the two places it is writing
    into and a wrong flag is invisible until someone checks a query string.

    Carries the cap when the caller has one, unlike `item_href`: this is the RETURN path,
    so a reader who went deeper and then pinned a recipe has to land back on the plan they
    were reading rather than on a shallower one that truncates again.
    """
    return _query("/plan", _plan_params(key, qty, max_nodes), sep="&")


def machine_href(uid):
    """Link to the machine detail page for a JEI category uid."""
    return _query("/machine", (("uid", uid),))
