"""A tiny read-only expression language, so a graph sweep is a URL and not a script.

WHY A LANGUAGE AND NOT A LIST OF FILTERS. #108 counted six shapes of question asked of this
graph in one session; five are single lookups and have endpoints now, and the sixth is a
sweep with a predicate nobody can enumerate in advance. "Every key labelled `<M> Nugget`
with no producers" was #106; the one before it was "every fluid whose label still looks like
a registry name"; the next one is not known. A fixed `?producers=0&label_suffix=Nugget`
answers this week's question and sends next week's back to a throwaway script, which is the
thing being fixed.

WHY NOT `eval`. That was option 3 in #108 and it is the one that would cover everything. The
server is LAN-only with no auth, `/data` is mounted read-write from the array, and "LAN-only"
is therefore the single control standing between a query string and arbitrary code as uid 99.
This grammar has no attribute access, no indexing, no calls other than the fixed table in
`FUNCTIONS`, and no way to name anything the caller did not pass in `fields` -- so the worst
a hostile expression can do is compare two numbers slowly.

    endswith(label, "Nugget") and producers == 0
    kind == "fluid" and cost > 500
    not live and stock > 0
    matches(key, "^thermal") and consumers > 100

Values are scalars only -- text, numbers, booleans -- because every operator here is a
scalar operator, and a field that could not be compared would be a field the language can
select but not filter on. `api.FIELDS` flattens its one list-valued fact (`ores`) into text
for exactly that reason.
"""

import re

# `and`/`or` return the operand rather than a bool so they short-circuit, which is not a
# micro-optimisation here: `api.Facts` computes each field on demand, so a left-hand
# `endswith(label, "Nugget")` that fails means the expensive `consumers` count on the right
# is never computed for that key. A sweep over 266,703 keys pays the full cost of a field
# only for the keys that get that far.


class QueryError(ValueError):
    """A malformed expression. Carried to the caller as a 400, never as a traceback."""


def _text(value, func):
    if not isinstance(value, str):
        raise QueryError("%s() expects text, got %s" % (func, _describe(value)))
    return value


def _describe(value):
    if isinstance(value, bool):
        return "the boolean %s" % ("true" if value else "false")
    if isinstance(value, str):
        return "the text %r" % value
    return "the number %r" % (value,)


# name -> (arity, implementation). The whole callable surface of the language: a call to
# anything not in here is a parse error, which is what keeps the grammar from reaching the
# interpreter it is embedded in.
FUNCTIONS = {
    "startswith": (2, lambda s, p: _text(s, "startswith").startswith(_text(p, "startswith"))),
    "endswith": (2, lambda s, p: _text(s, "endswith").endswith(_text(p, "endswith"))),
    "contains": (2, lambda s, p: _text(p, "contains") in _text(s, "contains")),
    "matches": (2, lambda s, p: re.search(_text(p, "matches"), _text(s, "matches")) is not None),
    "lower": (1, lambda s: _text(s, "lower").lower()),
    "upper": (1, lambda s: _text(s, "upper").upper()),
    "len": (1, lambda s: len(_text(s, "len"))),
}

# `inf` is a real value here rather than a curiosity: `api.FIELDS["cost"]` is infinite for a
# key the relaxation never priced, so `cost < inf` is how you ask for "reachable at all".
CONSTANTS = {"true": True, "false": False, "inf": float("inf")}

_COMPARISONS = {
    "==": lambda a, b: a == b,
    "!=": lambda a, b: a != b,
    "<": lambda a, b: a < b,
    "<=": lambda a, b: a <= b,
    ">": lambda a, b: a > b,
    ">=": lambda a, b: a >= b,
}

_TOKEN = re.compile(r"""
      (?P<space>\s+)
    | (?P<string>"[^"]*"|'[^']*')
    | (?P<number>\d+(?:\.\d+)?)
    | (?P<name>[A-Za-z_][A-Za-z0-9_]*)
    | (?P<op><=|>=|==|!=|<|>)
    | (?P<punct>[(),-])
""", re.VERBOSE)


def tokenize(text):
    """`[(kind, value, position)]`, with `("end", "", n)` last.

    STRINGS CARRY NO ESCAPES, deliberately: a quoted run ends at the next matching quote and
    every character inside it is literal. `matches(key, "^thermal\\w+")` has to survive being
    typed into a URL, and a backslash that the tokenizer eats before the regex engine sees it
    is a bug nobody would look for. Both quote styles exist so a pattern needing one can be
    written in the other.
    """
    out = []
    pos = 0
    while pos < len(text):
        found = _TOKEN.match(text, pos)
        if not found:
            raise QueryError("cannot read %r at position %d" % (text[pos], pos))
        kind = found.lastgroup
        value = found.group()
        pos = found.end()
        if kind == "space":
            continue
        if kind == "string":
            value = value[1:-1]
        out.append((kind, value, found.start()))
    out.append(("end", "", len(text)))
    return out


class _Parser:
    """Recursive descent, compiling straight to closures rather than to a tree.

    No separate eval walk, so the short-circuit the `and`/`or` note above depends on is
    Python's own rather than something a tree-walker has to remember to implement.
    """

    def __init__(self, tokens, fields):
        self.tokens = tokens
        self.at = 0
        self.fields = fields

    # ---- token helpers ----

    def peek(self):
        return self.tokens[self.at]

    def take(self):
        token = self.tokens[self.at]
        self.at += 1
        return token

    def accept(self, kind, value=None):
        k, v, _p = self.peek()
        if k == kind and (value is None or v == value):
            return self.take()
        return None

    def expect(self, kind, value=None):
        token = self.accept(kind, value)
        if token is None:
            k, v, p = self.peek()
            wanted = value or kind
            got = "end of expression" if k == "end" else repr(v)
            raise QueryError("expected %s at position %d, got %s" % (wanted, p, got))
        return token

    # ---- grammar ----

    def parse(self):
        node = self.disjunction()
        self.expect("end")
        return lambda facts: bool(node(facts))

    def disjunction(self):
        node = self.conjunction()
        while self.accept("name", "or"):
            right = self.conjunction()
            node = (lambda a, b: lambda f: a(f) or b(f))(node, right)
        return node

    def conjunction(self):
        node = self.negation()
        while self.accept("name", "and"):
            right = self.negation()
            node = (lambda a, b: lambda f: a(f) and b(f))(node, right)
        return node

    def negation(self):
        if self.accept("name", "not"):
            node = self.negation()
            return lambda f: not node(f)
        return self.comparison()

    def comparison(self):
        left = self.operand()
        token = self.accept("op")
        if token is None:
            # A bare value in boolean position: `live`, or `contains(label, "Ore")`. Wanted
            # because most fields and every function already answer a yes/no question, and
            # `live == true` reads worse than `live`.
            return left
        op = _COMPARISONS[token[1]]
        symbol = token[1]
        right = self.operand()

        def run(facts):
            a, b = left(facts), right(facts)
            try:
                return op(a, b)
            except TypeError:
                # Python 3 refuses `"a" < 1`, and the raw TypeError names neither the field
                # nor the operator. Reaching the caller as a 500 would read as a broken
                # server rather than as a query comparing a label to a count.
                raise QueryError("cannot compare %s with %s using %s"
                                 % (_describe(a), _describe(b), symbol))
        return run

    def operand(self):
        if self.accept("punct", "-"):
            inner = self.operand()
            return lambda f: -inner(f)
        if self.accept("punct", "("):
            node = self.disjunction()
            self.expect("punct", ")")
            return node
        kind, value, pos = self.take()
        if kind == "string":
            return lambda f: value
        if kind == "number":
            number = float(value) if "." in value else int(value)
            return lambda f: number
        if kind == "name":
            return self.named(value, pos)
        got = "end of expression" if kind == "end" else repr(value)
        raise QueryError("expected a value at position %d, got %s" % (pos, got))

    def named(self, value, pos):
        if value in FUNCTIONS:
            return self.call(value)
        if value in CONSTANTS:
            constant = CONSTANTS[value]
            return lambda f: constant
        if value not in self.fields:
            raise QueryError("no such field %r at position %d. Known fields: %s"
                             % (value, pos, ", ".join(sorted(self.fields))))
        return lambda f: f[value]

    def call(self, name):
        arity, impl = FUNCTIONS[name]
        self.expect("punct", "(")
        args = []
        if not self.accept("punct", ")"):
            args.append(self.disjunction())
            while self.accept("punct", ","):
                args.append(self.disjunction())
            self.expect("punct", ")")
        if len(args) != arity:
            raise QueryError("%s() takes %d argument%s, got %d"
                             % (name, arity, "" if arity == 1 else "s", len(args)))

        def run(facts):
            try:
                return impl(*[a(facts) for a in args])
            except QueryError:
                raise
            except re.error as exc:
                raise QueryError("%s(): bad regular expression: %s" % (name, exc))
        return run


def compile_query(text, fields):
    """`f(facts) -> bool` for `text`, or raise `QueryError`.

    `fields` is the set of names the expression may read, passed in rather than imported so
    this module knows nothing about what a graph key is and can be tested on a plain dict.
    An unknown name is a PARSE error, not a KeyError at scan time: mistyping `producer` for
    `producers` should say so once, not once per key.

    `facts` need only support `__getitem__`; `api.Facts` computes each field on first read.
    """
    if not text or not text.strip():
        raise QueryError("empty expression")
    return _Parser(tokenize(text), set(fields)).parse()
