"""HTML escaping, in one place.

Three modules had their own identical `_esc`. One of them eventually escaping differently
from the others is the kind of bug that only shows up as broken markup on the one page that
happened to use the odd one out.
"""

import html


def esc(value):
    """Escape for use in HTML text OR in a quoted attribute."""
    return html.escape(str(value), quote=True)
