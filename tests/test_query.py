"""The sweep expression language, on plain dicts. See #108.

Deliberately no graph anywhere in this file: `compile_query` takes the field names as an
argument precisely so the parser can be exercised without loading 115 MB, and a test that
reached for a Graph would quietly undo that.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph.query import (CONSTANTS, FUNCTIONS, QueryError,  # noqa: E402
                               compile_query, tokenize)

FIELDS = ("key", "label", "kind", "producers", "consumers", "stock", "cost", "live", "ores")

NUGGET = {"key": "mod:sednanite_nugget", "label": "Sednanite Nugget", "kind": "item",
          "producers": 0, "consumers": 1, "stock": 18, "cost": 1.0, "live": True,
          "ores": "nuggetSednanite"}
INGOT = {"key": "mod:sednanite_ingot", "label": "Sednanite Ingot", "kind": "item",
         "producers": 2, "consumers": 3, "stock": 0, "cost": 9.0, "live": True,
         "ores": "ingotSednanite"}
GHOST = {"key": "mod:ghost", "label": "Ghost", "kind": "item", "producers": 0,
         "consumers": 0, "stock": 0, "cost": float("inf"), "live": False, "ores": ""}


def run(text, facts):
    return compile_query(text, FIELDS)(facts)


class GrammarTest(unittest.TestCase):
    def test_the_predicate_that_forced_a_script(self):
        # #106 in one URL. This exact question was answered by a throwaway script twice.
        where = 'endswith(label, "Nugget") and producers == 0'
        self.assertTrue(run(where, NUGGET))
        self.assertFalse(run(where, INGOT))

    def test_comparisons(self):
        for op, expected in (("==", False), ("!=", True), ("<", False), ("<=", False),
                             (">", True), (">=", True)):
            self.assertEqual(run("producers %s 1" % op, INGOT), expected, op)

    def test_and_or_not_and_parentheses(self):
        self.assertTrue(run('kind == "item" and stock > 0', NUGGET))
        self.assertFalse(run('kind == "fluid" and stock > 0', NUGGET))
        self.assertTrue(run('kind == "fluid" or stock > 0', NUGGET))
        self.assertTrue(run("not live", GHOST))
        # `and` binds tighter than `or`, so without the parentheses this is true for INGOT.
        self.assertFalse(run('(kind == "fluid" or producers == 0) and stock > 0', INGOT))

    def test_a_bare_field_or_call_is_a_truth_test(self):
        # `live == true` is legal but reads worse, and every function already answers
        # yes/no, so a bare value in boolean position has to work.
        self.assertTrue(run("live", NUGGET))
        self.assertFalse(run("live", GHOST))
        self.assertTrue(run('contains(label, "Nugget")', NUGGET))
        self.assertFalse(run("ores", GHOST))

    def test_fields_can_be_compared_to_each_other(self):
        self.assertTrue(run("consumers > producers", NUGGET))
        self.assertFalse(run("consumers > producers", GHOST))

    def test_every_function_in_the_table_is_reachable_from_the_grammar(self):
        # A function added to FUNCTIONS but unreachable would look supported in /api's
        # index and fail at the first use. One call each, so the table cannot outgrow the
        # parser silently.
        calls = {
            "startswith": ('startswith(label, "Sednanite")', True),
            "endswith": ('endswith(label, "Nugget")', True),
            "contains": ('contains(label, "nanite N")', True),
            "matches": (r'matches(key, "nugget$")', True),
            "lower": ('lower(label) == "sednanite nugget"', True),
            "upper": ('upper(kind) == "ITEM"', True),
            "len": ("len(label) == 16", True),
        }
        self.assertEqual(sorted(calls), sorted(FUNCTIONS),
                         "FUNCTIONS and this test's call list have diverged")
        for name, (text, expected) in calls.items():
            self.assertEqual(run(text, NUGGET), expected, name)

    def test_every_constant_is_reachable_too(self):
        self.assertEqual(sorted(CONSTANTS), ["false", "inf", "true"])
        self.assertTrue(run("live == true", NUGGET))
        self.assertTrue(run("live != false", NUGGET))
        # The point of `inf`: "priced at all" has no other spelling, and an unreachable key
        # is exactly what a sweep for pricing gaps is looking for.
        self.assertTrue(run("cost < inf", NUGGET))
        self.assertFalse(run("cost < inf", GHOST))

    def test_unary_minus(self):
        self.assertTrue(run("producers > -1", NUGGET))

    def test_a_regex_keeps_its_backslashes(self):
        # Escapes are NOT processed inside a quoted run, so `\d` reaches `re` intact. A
        # tokenizer that ate the backslash would turn this into a search for "d".
        self.assertTrue(run(r'matches(label, "^\w+ Nugget$")', NUGGET))
        self.assertFalse(run(r'matches(label, "^\d+ Nugget$")', NUGGET))

    def test_the_other_quote_style_is_available_inside_a_string(self):
        self.assertTrue(run("""contains(label, 'Nugget')""", NUGGET))
        self.assertTrue(run('''contains(label, "Nugget")''', NUGGET))


class ShortCircuitTest(unittest.TestCase):
    """`and`/`or` must not evaluate the right side when the left decides it.

    This is not a micro-optimisation. `api.Facts` computes each field on first read, and a
    sweep over 342,070 keys that evaluated `consumers` for every one of them would be the
    slow thing the endpoint was built to replace.
    """

    class Counting(dict):
        def __init__(self, *a, **k):
            dict.__init__(self, *a, **k)
            self.reads = []

        def __getitem__(self, name):
            self.reads.append(name)
            return dict.__getitem__(self, name)

    def facts(self, base):
        return self.Counting(base)

    def test_and_stops_at_a_false_left_side(self):
        f = self.facts(INGOT)
        compile_query('endswith(label, "Nugget") and consumers > 0', FIELDS)(f)
        self.assertNotIn("consumers", f.reads)

    def test_or_stops_at_a_true_left_side(self):
        f = self.facts(NUGGET)
        compile_query('endswith(label, "Nugget") or consumers > 0', FIELDS)(f)
        self.assertNotIn("consumers", f.reads)

    def test_a_field_read_twice_is_only_asked_for_once_per_side(self):
        # Not a caching claim about the parser -- `Facts` does that -- only that the
        # compiler does not duplicate reads by expanding a term.
        f = self.facts(NUGGET)
        compile_query("producers == 0", FIELDS)(f)
        self.assertEqual(f.reads, ["producers"])


class ErrorTest(unittest.TestCase):
    """Every mistake here is made by a person composing a URL, so it must read as one."""

    def bad(self, text, *needles):
        with self.assertRaises(QueryError) as caught:
            compile_query(text, FIELDS)
        message = str(caught.exception)
        for needle in needles:
            self.assertIn(needle, message, message)
        return message

    def test_an_unknown_field_is_caught_at_parse_time_and_lists_the_real_ones(self):
        # Once, not once per key. A plural typo is the likeliest mistake there is.
        message = self.bad("producer == 0", "no such field", "'producer'")
        self.assertIn("consumers", message)

    def test_an_unknown_function_reads_as_an_unknown_field(self):
        self.bad('startwith(label, "x")', "no such field", "startwith")

    def test_wrong_arity(self):
        self.bad('endswith(label)', "endswith() takes 2 arguments")
        self.bad('len(label, 2)', "len() takes 1 argument")

    def test_unbalanced_parentheses(self):
        self.bad("(producers == 0", "expected )")

    def test_an_empty_expression_says_so_rather_than_matching_everything(self):
        # Matching everything would be a 342,070-row answer to a blank box.
        self.bad("", "empty expression")
        self.bad("   ", "empty expression")

    def test_a_stray_character(self):
        self.bad("producers == 0 & live", "cannot read '&'")

    def test_a_dangling_operator(self):
        self.bad("producers ==", "expected a value")

    def test_comparing_text_to_a_number_names_both_sides(self):
        # Raised at scan time rather than parse time, because the types are only known
        # once a key is in hand. It must still not escape as a bare TypeError.
        with self.assertRaises(QueryError) as caught:
            compile_query("label > 3", FIELDS)(NUGGET)
        self.assertIn("cannot compare", str(caught.exception))
        self.assertIn("'Sednanite Nugget'", str(caught.exception))

    def test_a_function_given_a_number_says_which_function(self):
        with self.assertRaises(QueryError) as caught:
            compile_query("endswith(producers, \"x\")", FIELDS)(NUGGET)
        self.assertIn("endswith() expects text", str(caught.exception))

    def test_a_bad_regex_is_a_query_error_not_a_crash(self):
        with self.assertRaises(QueryError) as caught:
            compile_query('matches(label, "(")', FIELDS)(NUGGET)
        self.assertIn("bad regular expression", str(caught.exception))


class NoInterpreterReachableTest(unittest.TestCase):
    """The reason this is a parser and not `eval`. See query.py's docstring and #108.

    The server is LAN-only with no auth and `/data` is mounted read-write, so the grammar
    having no way to name anything outside `fields` is the actual security boundary, not a
    stylistic preference. Each of these is a parse error, and each would be a working
    expression under `eval`.
    """

    def test_attribute_access_is_not_expressible(self):
        for text in ("label.__class__", "().__class__.__bases__",
                     "key.__class__.__mro__"):
            with self.assertRaises(QueryError, msg=text):
                compile_query(text, FIELDS)

    def test_builtins_are_not_reachable(self):
        for text in ('__import__("os")', "open('/etc/passwd')", "eval('1')",
                     "globals()", "exec('x=1')"):
            with self.assertRaises(QueryError, msg=text):
                compile_query(text, FIELDS)

    def test_assignment_and_statements_are_not_expressible(self):
        for text in ("producers = 0", "import os", "producers; live"):
            with self.assertRaises(QueryError, msg=text):
                compile_query(text, FIELDS)

    def test_indexing_and_slicing_are_not_expressible(self):
        for text in ("label[0]", "label[0:2]"):
            with self.assertRaises(QueryError, msg=text):
                compile_query(text, FIELDS)


class TokenizerTest(unittest.TestCase):
    def test_positions_are_reported_so_a_long_predicate_can_be_found_in(self):
        kinds = [(k, v) for k, v, _p in tokenize('producers == 0')]
        self.assertEqual(kinds, [("name", "producers"), ("op", "=="), ("number", "0"),
                                 ("end", "")])
        self.assertEqual([p for _k, _v, p in tokenize("a == 1")], [0, 2, 5, 6])


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
