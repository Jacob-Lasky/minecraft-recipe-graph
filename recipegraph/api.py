"""A read-only JSON surface over the graph the server already holds in memory.

WHY THIS EXISTS. `Graph.load` reads a 115 MB file off a FUSE mount and costs 4.4 seconds;
the running container answers `/plan` in 0.048. #108 counted one session that asked the graph
about twenty questions, every one of them as a fresh `python3` script paying that 4.4 seconds
again, while the warm graph sat two hundred metres away on port 8765. The habit is half the
fix. The other half is that five of the six question shapes had no endpoint to ask, so a
script was the only way to ask them at all.

Everything here WRAPS a function that already existed -- `explore.describe`, the cost table,
the solver's own result dict -- rather than computing a second opinion. A sweep that counted
producers its own way would eventually disagree with the page describing the same key, and
the disagreement would be discovered by trusting the wrong one.

    GET /api                       this index, including the field and function vocabulary
    GET /api/key?key=K             everything about one key
    GET /api/keys?match=&kind=     unranked, uncapped name match
    GET /api/recipe?rid=R          one recipe, inputs with their alternatives
    GET /api/cost?key=&category=   the cost table slice, warm
    GET /api/sweep?where=EXPR      every key satisfying a predicate. See query.py.
    GET /plan?item=...&fmt=json    the plan as data, the parameter the server already took

READ-ONLY IS A PROPERTY OF THIS MODULE, NOT A CONVENTION. Nothing here writes a file, mutates
the graph, or touches `State`; the sweep evaluator has no attribute access and no callable
surface beyond `query.FUNCTIONS`. The deployment is LAN-only with no auth, which means the
network is the only access control there is, so "it is read-only" has to be structurally true
rather than merely intended.
"""

import json
import math

from . import cost as cost_mod
from . import explore
from . import iconset, query
from .model import base_key

JSON_CTYPE = "application/json; charset=utf-8"

# How many keys a listing returns before it truncates, and the count is reported alongside so
# a truncated answer cannot be mistaken for a complete one. `limit=0` lifts it: this is a
# single-user tool on a LAN and the caller is usually piping into `jq`.
DEFAULT_LIMIT = 200

# How many of a slot's alternatives `/api/recipe` resolves to names. The pack's generic
# "Fluid Transposer - Empty" lists 1,198 in one slot, so resolving every alternative of every
# slot by default makes the common case pay for the pathological one. Raise with `?alts=N`,
# and `alt_total` always tells the truth about what was cut.
DEFAULT_ALTS = 64


def _finite(value):
    """`value`, with a non-finite float flattened to None.

    `json.dumps` writes `Infinity` for `float("inf")`, which is Python-specific and NOT valid
    JSON: `jq` rejects it outright. An unpriced key really is infinite in the cost table, so
    this arises on the first `/api/cost` of anything the relaxation never reached.
    """
    if isinstance(value, float) and not math.isfinite(value):
        return None
    return value


def jsonable(payload):
    """`payload` with every non-finite float flattened, ready for `json.dumps`.

    Public and separate from `dumps` because a caller can legitimately want the SAME
    flattening with different formatting: `tools/make-java-fixtures.py` writes indented
    fixtures and must flatten identically, or a fixture and the `/api/cost` response
    disagree about how an unpriced key is spelled. Two spellings of "unpriced" is the sort
    of difference a cross-language test reports as a behaviour change.
    """
    if isinstance(payload, dict):
        return {k: jsonable(v) for k, v in payload.items()}
    if isinstance(payload, (list, tuple)):
        return [jsonable(v) for v in payload]
    return _finite(payload)


def dumps(payload):
    """JSON that `jq` will actually parse. Use this rather than `json.dumps` directly."""
    return json.dumps(jsonable(payload), sort_keys=True)


# ---------------------------------------------------------------------------
# The field vocabulary: one definition, read by `select`, by `order`, and by the
# expression language. Adding a fact here makes it filterable and selectable at once, which
# is the property that stops the two lists drifting.
# ---------------------------------------------------------------------------

FIELDS = {
    "key": (lambda c, k: k,
            "the registry key itself"),
    "label": (lambda c, k: c.graph.bare_name(k),
              "the on-screen name, with no type prefix"),
    "name": (lambda c, k: c.graph.display(k),
             "the on-screen name, with the [fluid]/[ore] prefix"),
    # `mod` below is deliberately blank for the non-item kinds rather than reporting the
    # namespace: `fluid:nethengeic_fluid` would answer "fluid", which is its KIND wearing the
    # shape of an answer, and a sweep grouping by mod would report a mod called fluid.
    "kind": (lambda c, k: c.graph.kind(k),
             "item, fluid, essentia or ore"),
    "mod": (lambda c, k: k.split(":")[0] if c.graph.kind(k) == "item" else "",
            "the owning mod, empty for a fluid, ore or essentia key"),
    "stock": (lambda c, k: explore.stock_of(k, c.have),
              "how much the AE2 network holds"),
    "producers": (lambda c, k: len(c.graph.real_producers(k)),
                  "recipes that MAKE it, container-emptying excluded"),
    "all_producers": (lambda c, k: len(c.graph.producers(k)),
                      "recipes that output it, container-emptying included"),
    "consumers": (lambda c, k: len(c.graph.consumers(k)),
                  "recipes that use it, reached through oredicts too"),
    "cost": (lambda c, k: c.costs.get(k, float("inf")),
             "the relaxation's estimate; inf for a key it never reached"),
    "live": (lambda c, k: k in c.graph.live_keys,
             "whether any recipe touches it at all"),
    "ores": (lambda c, k: " ".join(sorted(c.graph.ores_of(k))),
             "its oredict groups, space-joined so text functions work on them"),
    # The #50 measurement IS a sweep -- `emc > 0 and producers == 0` names every item whose
    # only route the graph knows is a drop and which the transmutation network could make
    # instead -- so it belongs in the vocabulary rather than in a script. That is this
    # module's whole argument: a question the server cannot answer is a missing FIELD, not a
    # reason to reach for python.
    "emc": (lambda c, k: c.graph.emc.get(k, 0),
            "its ProjectE EMC value, 0 for an item the pack gives none"),
    # Whether #36's atlas has a picture for it, so "what is missing art" is answerable
    # without opening the PNGs. False on every key of a pre-schema-5 graph.
    "icon": (lambda c, k: iconset.locate(c.graph, k) is not None,
             "whether the icon atlas holds a sprite for it"),
    # `damaged` separates a worn tool from an item whose meta is a subtype, which is the
    # distinction #118 turns on and the one no shape of the key can express.
    "damaged": (lambda c, k: c.graph.damage_base(k) != k,
                "whether this key is a durability variant of another item"),
    # "how many keys would the #136 badge fire on" is a sweep question, and answering it
    # with an ad-hoc script is what this module exists to stop. Mirrors
    # `Solver.reachable_form`; a second spelling of the predicate is how the page and the
    # sweep come to disagree about the same key.
    "unsourced": (lambda c, k: bool(_reachable_form(c.graph, k)),
                  "the graph can make the plain item but nothing reaches this state"),
}


def _reachable_form(graph, key):
    """`Solver.reachable_form` without a Solver. See there for why the rule is this narrow.

    Duplicated in shape rather than imported because `api` must not depend on `solve` -- the
    sweep answers questions about a GRAPH, and a solver carries an inventory, pins and a cost
    table it has no business needing. `tests/test_unsourced.py` pins the two against each
    other so the copy cannot drift.
    """
    if graph.real_producers(key):
        return None
    stem = base_key(key)
    if stem == key:
        return None
    return stem if graph.real_producers(stem) else None


class Context:
    """What a field needs to answer, gathered off `State` once per request.

    Bound rather than passed as four arguments so `FIELDS` entries stay one-liners, and read
    off `State` at the top of a request rather than per key so a reload landing mid-sweep
    cannot make the first half of an answer describe a different graph from the second.
    """

    __slots__ = ("graph", "have", "costs")

    def __init__(self, state):
        self.graph = state.graph
        self.have = state.have
        self.costs = state.costs


class Facts:
    """One key's fields, each computed on first read and then remembered.

    LAZY IS THE WHOLE PERFORMANCE STORY OF THE SWEEP. `consumers` walks the oredict groups
    and `cost` is a dict hit, but multiplied by 266,703 keys the difference between computing
    every field and computing the ones a predicate actually reaches is the difference between
    a sweep being a tool and being another reason to write a script. `query` compiles `and`
    and `or` to Python's own short-circuiting operators, so a predicate that opens with a
    cheap label test only pays for `consumers` on the handful of keys that pass it.
    """

    __slots__ = ("_ctx", "_key", "_seen")

    def __init__(self, ctx, key):
        self._ctx = ctx
        self._key = key
        self._seen = {}

    def __getitem__(self, name):
        try:
            return self._seen[name]
        except KeyError:
            pass
        # Not a KeyError path in practice: `query.compile_query` rejects an unknown field at
        # parse time and `_select` validates before scanning. Kept correct anyway, because a
        # bare KeyError here would surface as a 500 on a caller's typo.
        try:
            fn = FIELDS[name][0]
        except KeyError:
            raise query.QueryError("no such field %r" % name)
        value = fn(self._ctx, self._key)
        self._seen[name] = value
        return value

    def row(self, select):
        return {name: _finite(self[name]) for name in select}


# ---------------------------------------------------------------------------
# Scanning
# ---------------------------------------------------------------------------

def universe(ctx):
    """Every key the graph knows anything about: labelled, touched by a recipe, or held.

    ALL THREE SOURCES, because each contributes keys the others do not, and a sweep is a
    census -- a key missing from it reads as a fact about the pack rather than about this
    function.

    * `labels` is names plus the fluid, ore and essentia keys collected off the recipes.
    * `live_keys` adds the ones some recipe or catalyst touches that items.csv never named.
      Measured on the reference graph: 2,788 keys are live and unlabelled, and they are
      exactly the "what IS this thing nothing will tell me about" cases worth sweeping for.
    * `have` adds NBT-discriminated stacks the dump never saw. Same correction
      `explore.rank_matches` makes at the bottom of its scan: a stack in the AE2 network is
      a fact about the world, and the dump does not get to overrule it.

    Ordered rather than a set, so a sweep that does not sort still comes back the same way
    twice. `dict.fromkeys` because it deduplicates and preserves first-seen order.
    """
    keys = dict.fromkeys(ctx.graph.labels)
    keys.update(dict.fromkeys(ctx.graph.live_keys))
    keys.update(dict.fromkeys(ctx.have))
    return list(keys)


def _select(raw, default):
    """Parse a `select=` list, rejecting an unknown field rather than dropping it silently."""
    if not raw:
        return list(default)
    names = [n.strip() for n in raw.split(",") if n.strip()]
    unknown = [n for n in names if n not in FIELDS]
    if unknown:
        raise query.QueryError("no such field %s. Known fields: %s"
                               % (", ".join(repr(n) for n in unknown),
                                  ", ".join(sorted(FIELDS))))
    return names or list(default)


def _sortable(value, descending):
    """A total order over one column's values, tolerating None and mixed types.

    `cost` is why None has to be handled: `_finite` turns an unreachable key's infinity into
    None before rows are sorted, and `None < 1.0` is a TypeError. UNSET SORTS LAST IN BOTH
    DIRECTIONS on purpose -- "no answer" is not a small answer, and reversing the order should
    reorder the rows that have values rather than parade the ones that do not.

    The leading discriminator also keeps a column that holds both text and numbers from
    raising: nothing in `FIELDS` does that today, but a sort is a poor place to find out.
    """
    if value is None:
        return (2, 0.0, "")
    if isinstance(value, bool):
        value = int(value)
    if isinstance(value, (int, float)):
        return (0, -value if descending else value, "")
    text = str(value)
    return (1, 0.0, _Reversed(text) if descending else text)


class _Reversed:
    """Sorts a string the other way, since `-` is not defined on text.

    Needed because `order=-label` has to mean something: negating the numeric branch above
    is enough for counts and costs, and a descending name sort would otherwise silently come
    back ascending.

    Only ever compared against another `_Reversed`, and structurally so rather than by
    convention: the tuple `_sortable` returns leads with a type discriminator, and a run of
    rows can only reach this third element when both sides took the same branch of the same
    call. The `NotImplemented` is there for the day that stops being true.
    """

    __slots__ = ("text",)

    def __init__(self, text):
        self.text = text

    def __eq__(self, other):
        if not isinstance(other, _Reversed):
            return NotImplemented
        return self.text == other.text

    def __lt__(self, other):
        if not isinstance(other, _Reversed):
            return NotImplemented
        return other.text < self.text


def parse_order(order):
    """`(field, descending)` from an `order=` value. A leading `-` reverses."""
    descending = bool(order) and order.startswith("-")
    field = (order or "key").lstrip("-")
    if field not in FIELDS:
        raise query.QueryError("cannot order by %r. Known fields: %s"
                               % (field, ", ".join(sorted(FIELDS))))
    return field, descending


def scan(ctx, predicate, select, order=None, limit=DEFAULT_LIMIT):
    """`{matched, returned, truncated, results}` for every key `predicate` accepts.

    `matched` is counted over the WHOLE universe before `limit` is applied, so a capped
    answer still says how much it is hiding. Silently truncating a sweep is how a scope
    measurement comes back wrong, and that is the failure this endpoint exists to prevent.

    SORTED BEFORE TRUNCATION, so `limit` returns the first N of a defined order rather than
    the first N the universe happened to yield. Ordering by a column that was not selected is
    legal and useful ("the keys, cheapest first"), so the sort value is taken from the `Facts`
    and kept beside the row even when the row does not carry that column.

    THE `Facts` IS DROPPED AS SOON AS ITS ROW IS BUILT, which is why the sort key is computed
    in the loop rather than in the `sort` call. Holding one per match looks tidier and means
    `where=true&limit=0` keeps 266,703 objects and their memo dicts alive at once, inside a
    container capped at 4 GB that is already holding the graph.
    """
    field, descending = parse_order(order)
    rows = []
    for key in universe(ctx):
        facts = Facts(ctx, key)
        if not predicate(facts):
            continue
        rows.append((_sortable(_finite(facts[field]), descending), facts.row(select)))

    # `key=` extracts only the sort tuple, so two rows that tie never compare their dicts
    # (which would raise) and keep the order the scan found them in.
    rows.sort(key=lambda pair: pair[0])
    shown = rows if not limit else rows[:limit]
    return {"matched": len(rows), "returned": len(shown),
            "truncated": len(shown) < len(rows),
            "results": [row for _sort, row in shown]}


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------

def _example(where, *extra):
    """A worked sweep URL that can be pasted without being fixed up first.

    ONLY THE SPACES ARE ENCODED. A predicate reads as `endswith(label, "Nugget")` and an
    example that arrived as `endswith%28label%2C%20%22Nugget%22%29` would teach nobody the
    grammar -- but a literal space is the one character an HTTP client refuses outright
    (`http.client` raises InvalidURL before the request leaves), so an example carrying one
    is a documented URL that cannot be used. Parentheses, quotes, commas and `=` are all
    legal in a query string and stay readable.
    """
    parts = ["where=" + where.replace(" ", "%20")] + list(extra)
    return "/api/sweep?" + "&".join(parts)


def index_payload():
    """The self-describing root, so the vocabulary is discoverable from `curl` alone."""
    return {
        "endpoints": {
            "/api": "this index",
            "/api/key?key=K": "everything about one key, plus every field below",
            "/api/keys?match=&kind=&mod=&select=&order=&limit=":
                "unranked substring match over labels and keys",
            "/api/recipe?rid=R&alts=N": "one recipe, inputs with their alternatives",
            "/api/cost?key=K&category=C": "the warm cost table, sliced",
            "/api/sweep?where=EXPR&select=&order=&limit=":
                "every key satisfying a predicate",
            "/plan?item=K&qty=N&fmt=json": "the solver result as data",
        },
        "fields": {name: doc for name, (_fn, doc) in sorted(FIELDS.items())},
        "functions": {name: arity for name, (arity, _fn) in sorted(query.FUNCTIONS.items())},
        "constants": sorted(query.CONSTANTS),
        "operators": ["and", "or", "not", "==", "!=", "<", "<=", ">", ">=", "( )"],
        "examples": [_example('endswith(label, "Nugget") and producers == 0'),
                     _example('kind == "fluid" and cost > 500', "order=-cost"),
                     _example("not live and stock > 0", "select=key,label,stock")],
        "limits": {"default_limit": DEFAULT_LIMIT, "limit_0_means_uncapped": True,
                   "default_alts": DEFAULT_ALTS},
    }


def known(ctx, key):
    """Whether the graph has anything to say about `key` at all.

    Broader than `/plan`'s check, which asks `graph.names`: a fluid is never in names (see
    fluidnames), and a key that is only ever an INGREDIENT still has a describable page.
    """
    return bool(key in ctx.graph.labels or key in ctx.have
                or ctx.graph.producers(key) or ctx.graph.consumers(key))


def key_payload(state, key):
    ctx = Context(state)
    if not known(ctx, key):
        return {"error": "no such key", "key": key}, 404
    facts = Facts(ctx, key)
    payload = explore.describe(ctx.graph, key, have=ctx.have)
    # Alongside `describe`, not merged into it: `describe` is what the HTML page renders and
    # adding cost to it would change that page as a side effect of building an API.
    payload["facts"] = facts.row(list(FIELDS))
    return payload, 200


def keys_payload(state, match="", kind="", mod="", select="", order="", limit=DEFAULT_LIMIT):
    """Substring match with no ranking and no cap, which is what `/suggest` cannot be.

    `/suggest` exists for a keystroke: it ranks, it breaks name ties by how connected an item
    is, and it stops at 60 rows. Every one of those is right for a typeahead and wrong for a
    measurement, and #106's false "Iron Nugget has no producers" came from taking a ranked,
    capped, display-name-keyed answer as a census.
    """
    ctx = Context(state)
    needle = match.strip().lower()

    def predicate(facts):
        if kind and facts["kind"] != kind:
            return False
        if mod and facts["mod"] != mod:
            return False
        if not needle:
            return True
        return needle in facts["label"].lower() or needle in facts["key"].lower()

    return scan(ctx, predicate, _select(select, ("key", "label", "kind", "stock",
                                                 "producers", "consumers")),
                order, limit), 200


def sweep_payload(state, where, select="", order="", limit=DEFAULT_LIMIT):
    ctx = Context(state)
    predicate = query.compile_query(where, FIELDS)
    payload = scan(ctx, predicate,
                   _select(select, ("key", "label", "producers", "consumers", "stock")),
                   order, limit)
    # Echoed back so a saved response says which question it answered. A file of sweep output
    # with no predicate in it is a measurement nobody can re-run.
    payload["where"] = where
    return payload, 200


def _alternative(ctx, key):
    row = explore.stack(ctx.graph, key, ctx.have)
    # The COUNT, where the explore page's `makeable` is a boolean: "three ways to make it"
    # and "makeable" answer different questions, and the one a sweep follows up on is the
    # count. Everything before this line is the shared row shape.
    row["producers"] = len(ctx.graph.real_producers(key))
    return row


def _slot(ctx, ingredient, alts):
    # `alts=0` means all of them, matching `limit=0` on the listings.
    resolved = ingredient.alternatives if not alts else ingredient.alternatives[:alts]
    return {
        "qty": ingredient.qty,
        "role": ingredient.role,
        "alt_total": len(ingredient.alternatives),
        "alts": [_alternative(ctx, a) for a in resolved],
    }


def _recipe_row(ctx, recipe, alts):
    return {
        "id": recipe.rid,
        "source": recipe.source,
        "category": recipe.category,
        "machine": recipe.machine,
        "transfer": recipe.transfer,
        "inputs": [_slot(ctx, i, alts) for i in recipe.inputs],
        "outputs": explore.output_rows(ctx.graph, recipe.outputs),
    }


def recipe_payload(state, rid, alts=DEFAULT_ALTS):
    ctx = Context(state)
    found = ctx.graph.by_rid.get(rid) or []
    if not found:
        return {"error": "no such recipe", "rid": rid}, 404
    return {"rid": rid, "count": len(found),
            "results": [_recipe_row(ctx, r, alts) for r in found]}, 200


def cost_payload(state, keys=(), categories=()):
    """The warm cost table, sliced by key or by category, or summarised when given neither.

    The category number is `cost.category_entry_cost`, not the raw `machine_entry` value: the
    entry cost a category is actually charged depends on its machine's STATE, and reporting
    the pre-state number would disagree with what the relaxation used.
    """
    costs = state.costs
    entry = getattr(costs, "machine_entry", {})
    payload = {}
    if keys:
        payload["keys"] = {k: _finite(costs.get(k, float("inf"))) for k in keys}
    if categories:
        payload["categories"] = {}
        for category in categories:
            machine_state = (state.states.get(category) or ("unknown", ""))[0]
            payload["categories"][category] = {
                "entry_cost": _finite(cost_mod.category_entry_cost(
                    category, state.states, entry)),
                "machine_entry": _finite(entry.get(category)),
                "state": machine_state,
            }
    if not payload:
        priced = sum(1 for v in costs.values() if math.isfinite(v))
        payload = {"summary": {
            "keys_in_table": len(costs), "priced": priced,
            "unpriced": len(costs) - priced,
            "categories_with_entry_cost": len(entry),
            "fingerprint_inputs": "see cost.fingerprint",
        }}
    return payload, 200


# The routing table, so `dispatch` cannot answer a path `index_payload` does not advertise.
# One dict rather than an if-chain because the index is generated from `index_payload` and a
# route missing from one of the two is the documentation drifting from the server.
def dispatch(state, path, params):
    """`(payload, status)` for an `/api/...` path. `params` is `parse_qs` output.

    Every failure that is the CALLER's is a 4xx with a readable message, because the caller is
    a person at a terminal composing a predicate: `QueryError` carries the position of the
    typo and a list of the field names, and turning that into a 500 would send them to the
    container logs to find out they wrote `producer`.
    """
    def one(name, default=""):
        return (params.get(name) or [default])[0]

    def many(name):
        return [v for v in params.get(name, []) if v]

    def cap(name, default):
        raw = one(name, "")
        if not raw:
            return default
        try:
            return max(0, int(raw))
        except ValueError:
            raise query.QueryError("%s must be a whole number, got %r" % (name, raw))

    path = path.rstrip("/") or "/api"
    try:
        if path == "/api":
            return index_payload(), 200
        if path == "/api/key":
            key = one("key")
            if not key:
                return {"error": "key= is required"}, 400
            return key_payload(state, key)
        if path == "/api/keys":
            return keys_payload(state, one("match"), one("kind"), one("mod"),
                                one("select"), one("order"), cap("limit", DEFAULT_LIMIT))
        if path == "/api/recipe":
            rid = one("rid")
            if not rid:
                return {"error": "rid= is required"}, 400
            return recipe_payload(state, rid, cap("alts", DEFAULT_ALTS))
        if path == "/api/cost":
            return cost_payload(state, many("key"), many("category"))
        if path == "/api/sweep":
            where = one("where")
            if not where:
                return {"error": "where= is required",
                        "hint": "GET /api for the field and function vocabulary"}, 400
            return sweep_payload(state, where, one("select"), one("order"),
                                 cap("limit", DEFAULT_LIMIT))
    except query.QueryError as exc:
        return {"error": str(exc), "hint": "GET /api for the vocabulary"}, 400
    except (ValueError, KeyError) as exc:
        # The handler's own net catches these and renders an HTML error page, which for a
        # path under /api hands a JSON caller a parse failure instead of a message. Caught
        # here so the content type survives a bug, and reported as 500 rather than 400
        # because -- unlike everything above -- this one is not the caller's fault.
        return {"error": "internal error", "detail": "%s: %s" % (type(exc).__name__, exc)}, 500
    return {"error": "no such endpoint", "path": path,
            "endpoints": sorted(index_payload()["endpoints"])}, 404
