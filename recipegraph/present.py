"""How a status or a state is shown, in one place, for every view.

WHY THIS EXISTS. The tree, the flow diagram, the machines table and the plan's
machines-to-build panel all render the same two vocabularies -- the solver's node statuses
and machine availability states -- and each had its own dict keyed by bare string literals.
Nothing tied those dicts to the constants they were keying, so adding a status to solve.py
would have silently drawn every new node as "craft" in the diagram and as an unlabelled grey
pill in the tree, with no test failing.

Everything here is keyed by the constants themselves, and `tests/test_present.py` asserts
that every constant has an entry in every map. A new status now breaks a test instead of
rendering wrongly.

DO NOT add a local status->colour dict to a renderer. Add the case here.
"""

import json

from .machines import BUILDABLE, HAVE, STATES, UNAVAILABLE, UNKNOWN
from .solve import (STATUS_CRAFT, STATUS_CYCLE, STATUS_DEPTH, STATUS_HAVE, STATUS_PARTIAL,
                    STATUS_RAW, STATUS_SOURCE)

# The oredict node the solver emits from `resolve_ore`, which is a string rather than a
# STATUS_* constant there because it is a display distinction, not a resolution outcome.
STATUS_OREDICT = "oredict"

ALL_STATUSES = (STATUS_HAVE, STATUS_PARTIAL, STATUS_CRAFT, STATUS_RAW, STATUS_SOURCE,
                STATUS_CYCLE, STATUS_DEPTH, STATUS_OREDICT)

# status -> (badge text, css class). The class names are the semantic tokens in render.CSS.
STATUS_LABEL = {
    STATUS_HAVE: ("in stock", "ok"),
    STATUS_PARTIAL: ("part stock", "warn"),
    STATUS_CRAFT: ("craft", "craft"),
    STATUS_RAW: ("NEED", "need"),
    STATUS_SOURCE: ("infinite", "ok"),
    STATUS_CYCLE: ("loop", "muted"),
    STATUS_DEPTH: ("cut off", "muted"),
    STATUS_OREDICT: ("any of", "muted"),
}

# status -> (fill, ink) for the diagram. Same meaning as the badge class above, expressed as
# the CSS custom properties an SVG attribute can take.
# The one place this grey is written is `--mutedbg` in render.CSS, so a diagram box
# and a tree badge for the same status cannot end up different greys.
_MUTED = "var(--mutedbg)"
STATUS_STYLE = {
    STATUS_HAVE: ("var(--okbg)", "var(--ok)"),
    STATUS_PARTIAL: ("var(--warnbg)", "var(--warn)"),
    STATUS_CRAFT: ("var(--craftbg)", "var(--craft)"),
    STATUS_RAW: ("var(--needbg)", "var(--need)"),
    STATUS_SOURCE: ("var(--okbg)", "var(--ok)"),
    STATUS_CYCLE: (_MUTED, "var(--dim)"),
    STATUS_DEPTH: (_MUTED, "var(--dim)"),
    STATUS_OREDICT: (_MUTED, "var(--dim)"),
}

def status_legend(statuses=ALL_STATUSES):
    """`[(fill, ink, labels)]` explaining the diagram's box colours, one row per FILL.

    Generated from STATUS_STYLE and STATUS_LABEL rather than written out beside them, so the
    legend cannot end up describing colours the diagram stopped using. It also means the
    legend and the tree badges say the same words for the same status, which they must: a
    reader who learns "part stock" from one should recognise it in the other.

    Grouped by fill because two statuses can SHARE one. `have` and `source` are both green;
    `cycle`, `depth` and `oredict` are all grey. Listing those as separate rows would put two
    identical swatches in one legend, which reads as a bug in the legend rather than as the
    truth about the diagram, which is that green means either of them.

    Order follows ALL_STATUSES, not the argument, so the legend does not reshuffle between
    two plans that happen to contain the same statuses in a different order.
    """
    wanted = set(statuses)
    by_fill = {}
    rows = []
    for status in ALL_STATUSES:
        if status not in wanted:
            continue
        fill, ink = STATUS_STYLE[status]
        labels = by_fill.get(fill)
        if labels is None:
            labels = by_fill[fill] = []
            rows.append((fill, ink, labels))
        labels.append(STATUS_LABEL[status][0])
    return [(fill, ink, ", ".join(labels)) for fill, ink, labels in rows]


# Machine availability. `unknown` reads "unidentified" to the player, because "unknown"
# invites reading it as "unknown whether you can use it" when it means "this tool could not
# work out which block this is".
STATE_LABEL = {
    HAVE: "have",
    BUILDABLE: "buildable",
    UNKNOWN: "unidentified",
    UNAVAILABLE: "no route",
}

# Pill class on the machines pages, and badge class in the plan panel. Two maps because the
# two components have different class vocabularies; both are complete and both are checked.
STATE_PILL = {HAVE: "ok", BUILDABLE: "warnp", UNKNOWN: "mut", UNAVAILABLE: "no"}
STATE_BADGE = {HAVE: "ok", BUILDABLE: "warn", UNKNOWN: "muted", UNAVAILABLE: "need"}


def is_roadblock(machine_state):
    """True when a plan step's machine is something to sort out before that step can run.

    The one definition, because the tree marks these nodes, the diagram outlines them and the
    filter button hides everything else, and three independent readings of "blocked" would
    drift.

    A machine you HAVE is not a roadblock, and neither is no machine at all: hand crafting
    and any recipe whose category the solver never resolved a machine for carry no state, and
    flagging those would put a warning on most of a plan. What Jake asked for is narrower:
    "idc to see it if the machine exists already, but if the machine needs to be built then
    that's important to know where the roadblock is". See #37.

    `unknown` DOES count, even though it means "this tool could not identify the machine"
    rather than "you do not have it". Silently treating unidentified as fine would hide a
    real wall behind a tooling gap. It carries the muted badge and reads "unidentified", so
    the render says which of the two it is rather than overstating.
    """
    return bool(machine_state) and machine_state != HAVE

# Sort order: most useful first. Derived from machines.STATES so the two cannot disagree
# about which states exist.
STATE_RANK = {state: i for i, state in enumerate(STATES)}
UNRANKED = len(STATES)

# Type chips. `ore` says ANY because that is what an oredict entry means to a player: any
# member satisfies the slot.
KIND_CHIP = {"fluid": "FLUID", "essentia": "ESSENTIA", "ore": "ANY"}


def kind_chip_json():
    """The chip map as JS, so the typeahead's client-side renderer cannot drift from it.

    The home page builds rows in the browser and so needs this mapping there too. Emitting
    it rather than restating it is what keeps one source of truth.
    """
    return json.dumps(KIND_CHIP)
