"""The issue #80 churn analyser, which is the whole payoff of a diagnostic dump.

`tools/digest-churn.py` is the thing that turns `nbt_trace.json` into an answer, and its
output decides which tag name goes into the narrow sort list. A wrong aggregate here would
send the fix at the wrong tag convincingly, so the pairing, the "same name, different
digest" filter and the order-only verdict are all covered.

Loaded through importlib because `tools/` is not a package and the filename is hyphenated,
the same way `tests/test_cost_probe.py` loads its tool. Everything under test is pure
dict-in/dict-out, so none of this needs a dump, a graph or a JVM.
"""

import importlib.util
import io
import json
import os
import re
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

_PATH = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                     "tools", "digest-churn.py")
_spec = importlib.util.spec_from_file_location("digest_churn", _PATH)
digest_churn = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(digest_churn)


def tags(**kw):
    """{"Traits": ("ordered", "sorted")} -> the on-disk shape."""
    return {name: {"o": o, "u": u} for name, (o, u) in kw.items()}


class BaseKey(unittest.TestCase):

    def test_it_strips_the_digest_and_nothing_else(self):
        self.assertEqual(digest_churn.base_key("tconstruct:hatchet:804#19d716f2142c"),
                         "tconstruct:hatchet:804")
        self.assertEqual(digest_churn.base_key("minecraft:diamond"), "minecraft:diamond")

    def test_it_keeps_the_meta_because_two_metas_are_two_items(self):
        # Stripping the meta would merge `tconstruct:ingots:0` with `:3` into one
        # pseudo-item, which is the mistake model.base_key's docstring records.
        self.assertEqual(digest_churn.base_key("tconstruct:ingots:3#abc"),
                         "tconstruct:ingots:3")
        self.assertNotEqual(digest_churn.base_key("tconstruct:ingots:0#a"),
                            digest_churn.base_key("tconstruct:ingots:3#a"))

    def test_the_mod_is_the_namespace_not_the_first_underscore_token(self):
        # `tinker_io:smart_output` tokenises to "tinker" if you split on the wrong thing,
        # which the skill records as a real misreading of a modid.
        self.assertEqual(digest_churn.mod_of("tinker_io:smart_output#abc"), "tinker_io")
        self.assertEqual(digest_churn.mod_of("plustic:katana:528#def"), "plustic")


class Suspects(unittest.TestCase):

    def test_a_tag_whose_two_digests_disagree_is_flagged(self):
        trace = {"tconstruct:hatchet:1#aaa": tags(Traits=("o1", "u1"))}
        rows = digest_churn.suspects(trace)
        self.assertEqual(rows["Traits"]["flagged"], 1)
        self.assertEqual(rows["Traits"]["cleared"], 0)

    def test_a_tag_whose_digests_agree_is_cleared_and_that_is_a_result(self):
        trace = {"tconstruct:hatchet:1#aaa": tags(Material=("same", "same"))}
        rows = digest_churn.suspects(trace)
        self.assertEqual(rows["Material"]["flagged"], 0)
        self.assertEqual(rows["Material"]["cleared"], 1)

    def test_it_counts_which_mods_a_flagged_tag_came_from(self):
        trace = {
            "tconstruct:hatchet:1#a": tags(Traits=("o", "u")),
            "tconstruct:kama:2#b": tags(Traits=("o", "u")),
            "plustic:katana:3#c": tags(Traits=("o", "u")),
        }
        rows = digest_churn.suspects(trace)
        self.assertEqual(rows["Traits"]["flagged"], 3)
        self.assertEqual(rows["Traits"]["mods"]["tconstruct"], 2)
        self.assertEqual(rows["Traits"]["mods"]["plustic"], 1)

    def test_the_mod_filter_excludes_other_namespaces(self):
        trace = {
            "tconstruct:hatchet:1#a": tags(Traits=("o", "u")),
            "plustic:katana:3#c": tags(Traits=("o", "u")),
        }
        rows = digest_churn.suspects(trace, mod="plustic")
        self.assertEqual(rows["Traits"]["flagged"], 1)
        self.assertEqual(rows["Traits"]["mods"]["tconstruct"], 0)


class Churn(unittest.TestCase):

    def test_it_names_the_tag_that_moved(self):
        old = ({"tconstruct:hatchet:1#old": tags(Traits=("t_old", "t_same"),
                                                 Material=("m", "m"))},
               {"tconstruct:hatchet:1#old": "Cobalt Hatchet"})
        new = ({"tconstruct:hatchet:1#new": tags(Traits=("t_new", "t_same"),
                                                Material=("m", "m"))},
               {"tconstruct:hatchet:1#new": "Cobalt Hatchet"})
        result = digest_churn.churn(old, new)
        self.assertEqual(result["changed_items"], 1)
        self.assertEqual(result["tags"]["Traits"]["changed"], 1)
        self.assertEqual(result["tags"]["Material"].get("changed", 0), 0)

    def test_an_order_only_change_is_distinguished_from_a_content_change(self):
        # The distinction the fix hangs on: if the SORTED digest survived, sorting that tag
        # fixes it. If it did not, the contents genuinely differ and sorting is no help.
        order_only = digest_churn.churn(
            ({"a:b#1": tags(T=("o1", "same"))}, {"a:b#1": "N"}),
            ({"a:b#2": tags(T=("o2", "same"))}, {"a:b#2": "N"}))
        self.assertEqual(order_only["tags"]["T"]["changed"], 1)
        self.assertEqual(order_only["tags"]["T"]["order_only"], 1)

        content = digest_churn.churn(
            ({"a:b#1": tags(T=("o1", "u1"))}, {"a:b#1": "N"}),
            ({"a:b#2": tags(T=("o2", "u2"))}, {"a:b#2": "N"}))
        self.assertEqual(content["tags"]["T"]["changed"], 1)
        self.assertEqual(content["tags"]["T"].get("order_only", 0), 0)

    def test_an_item_that_kept_its_key_is_not_counted_as_churn(self):
        same = ({"a:b#1": tags(T=("o", "u"))}, {"a:b#1": "N"})
        result = digest_churn.churn(same, same)
        self.assertEqual(result["changed_items"], 0)
        self.assertEqual(result["unchanged_items"], 1)
        self.assertEqual(result["tags"], {})

    def test_a_renamed_item_is_not_churn(self):
        # #80's filter: churn means the SAME item wearing two keys. A different display
        # name is ordinary pack drift and counting it would inflate the answer.
        old = ({"a:b#1": tags(T=("o1", "u1"))}, {"a:b#1": "Old Name"})
        new = ({"a:b#2": tags(T=("o2", "u2"))}, {"a:b#2": "Totally Different"})
        result = digest_churn.churn(old, new)
        self.assertEqual(result["changed_items"], 0)

    def test_a_new_item_absent_from_the_old_dump_is_ignored(self):
        old = ({}, {})
        new = ({"a:b#2": tags(T=("o", "u"))}, {"a:b#2": "N"})
        self.assertEqual(digest_churn.churn(old, new)["changed_items"], 0)

    def test_it_pairs_by_maximum_tag_agreement_within_a_name_group(self):
        # Two species share one (item, name), which is the real shape: 6,565 tconstruct
        # entries share a handful of names. The right pairing is the one agreeing on the
        # tags that did NOT move, not the one the dict happens to list first.
        old = ({
            "a:b#o1": tags(T=("x1", "s"), Stable=("keepA", "keepA")),
            "a:b#o2": tags(T=("y1", "s"), Stable=("keepB", "keepB")),
        }, {"a:b#o1": "N", "a:b#o2": "N"})
        new = ({
            "a:b#n2": tags(T=("y2", "s"), Stable=("keepB", "keepB")),
            "a:b#n1": tags(T=("x2", "s"), Stable=("keepA", "keepA")),
        }, {"a:b#n2": "N", "a:b#n1": "N"})
        result = digest_churn.churn(old, new)
        self.assertEqual(result["changed_items"], 2)
        # Both pairings moved only T. If it had paired across the Stable groups, Stable
        # would show as changed too.
        self.assertEqual(result["tags"]["T"]["changed"], 2)
        self.assertEqual(result["tags"]["Stable"].get("changed", 0), 0)
        self.assertEqual(result["tags"]["Stable"]["same"], 2)

    def test_an_unpaired_remainder_is_reported_rather_than_dropped_silently(self):
        # A silent cap reads as full coverage. #80 is an aggregate argument, so an
        # unreported exclusion would change the conclusion.
        old = ({"a:b#o1": tags(T=("x", "s")), "a:b#o2": tags(T=("y", "s"))},
               {"a:b#o1": "N", "a:b#o2": "N"})
        new = ({"a:b#n1": tags(T=("z", "s"))}, {"a:b#n1": "N"})
        result = digest_churn.churn(old, new)
        self.assertEqual(result["unpaired"], 1)

    def test_an_oversized_group_is_excluded_and_counted_not_paired_quadratically(self):
        # _pair is quadratic and a group is one (base key, display name); #80 measured 11,328
        # keys sharing a name, so an unbounded group is the expected shape, not a freak.
        old_trace, old_names, new_trace, new_names = {}, {}, {}, {}
        for i in range(6):
            old_trace["a:b#o%d" % i] = tags(T=("o%d" % i, "s"))
            old_names["a:b#o%d" % i] = "Shared Name"
            new_trace["a:b#n%d" % i] = tags(T=("n%d" % i, "s"))
            new_names["a:b#n%d" % i] = "Shared Name"
        result = digest_churn.churn((old_trace, old_names), (new_trace, new_names),
                                    max_pairs=4)
        self.assertEqual(result["changed_items"], 0, "the group must not be paired at all")
        self.assertEqual(result["oversized"], 6)
        self.assertEqual(result["tags"], {})

    def test_a_group_within_the_bound_is_still_paired(self):
        # The bound must not quietly swallow ordinary groups.
        old = ({"a:b#o1": tags(T=("x", "s"))}, {"a:b#o1": "N"})
        new = ({"a:b#n1": tags(T=("y", "s"))}, {"a:b#n1": "N"})
        result = digest_churn.churn(old, new, max_pairs=4)
        self.assertEqual(result["changed_items"], 1)
        self.assertEqual(result["oversized"], 0)

    def test_groups_only_in_the_new_dump_are_counted_rather_than_ignored_silently(self):
        old = ({"a:b#1": tags(T=("o", "u"))}, {"a:b#1": "Kept"})
        new = ({"a:b#1": tags(T=("o", "u")), "c:d#9": tags(T=("o", "u"))},
               {"a:b#1": "Kept", "c:d#9": "Brand New"})
        result = digest_churn.churn(old, new)
        self.assertEqual(result["new_only_groups"], 1)
        self.assertEqual(result["changed_items"], 0)

    def test_a_tag_added_or_removed_between_dumps_is_counted_apart(self):
        old = ({"a:b#1": tags(T=("o", "u"), Gone=("g", "g"))}, {"a:b#1": "N"})
        new = ({"a:b#2": tags(T=("o2", "u2"), Fresh=("f", "f"))}, {"a:b#2": "N"})
        result = digest_churn.churn(old, new)
        self.assertEqual(result["tags"]["Gone"]["removed"], 1)
        self.assertEqual(result["tags"]["Fresh"]["added"], 1)

    def test_it_counts_churn_by_mod(self):
        old = ({"tconstruct:a#1": tags(T=("o", "u")), "plustic:b#1": tags(T=("o", "u"))},
               {"tconstruct:a#1": "N1", "plustic:b#1": "N2"})
        new = ({"tconstruct:a#2": tags(T=("p", "v")), "plustic:b#2": tags(T=("p", "v"))},
               {"tconstruct:a#2": "N1", "plustic:b#2": "N2"})
        result = digest_churn.churn(old, new)
        self.assertEqual(result["mods"]["tconstruct"], 1)
        self.assertEqual(result["mods"]["plustic"], 1)


class ForcedPairing(unittest.TestCase):
    """Whether a pairing was certain or guessed, which decides if a row is evidence.

    A group holding one candidate per side pairs one way and one way only, so every tag
    difference in it is real. A group holding several can be paired wrongly, and a wrong
    pairing compares two genuinely different variants -- reporting CONTENT differences that
    never happened to any single item.

    This is not a hypothetical refinement. On the reference pack it is the whole difference
    between #80's two answers: `ench` moved on 2,423 pairs of which 1,335 were forced, and
    `StoredEnchantments` moved on 4 of which none were. Read together they look like one
    family of findings; read apart, only the first exists.
    """

    def test_a_lone_pair_in_a_group_is_forced(self):
        old = ({"a:b#1": tags(T=("o1", "s"))}, {"a:b#1": "N"})
        new = ({"a:b#2": tags(T=("o2", "s"))}, {"a:b#2": "N"})
        result = digest_churn.churn(old, new)
        self.assertEqual(result["forced_changed_items"], 1)
        self.assertEqual(result["tags"]["T"]["forced"], 1)
        self.assertEqual(result["tags"]["T"]["forced_order_only"], 1)

    def test_a_pairing_chosen_out_of_several_candidates_is_not_forced(self):
        # Two variants each side: the greedy choice may be right, but nothing here proves it.
        old = ({"a:b#o1": tags(T=("x1", "s")), "a:b#o2": tags(T=("y1", "s"))},
               {"a:b#o1": "N", "a:b#o2": "N"})
        new = ({"a:b#n1": tags(T=("x2", "s")), "a:b#n2": tags(T=("y2", "s"))},
               {"a:b#n1": "N", "a:b#n2": "N"})
        result = digest_churn.churn(old, new)
        self.assertEqual(result["changed_items"], 2)
        self.assertEqual(result["forced_changed_items"], 0)
        self.assertEqual(result["tags"]["T"]["changed"], 2)
        self.assertEqual(result["tags"]["T"].get("forced", 0), 0)

    def test_forced_order_only_tracks_the_sorted_digest_too(self):
        # A forced pair whose sorted digest ALSO moved is a content change, and the forced
        # order-only count must not claim sorting would have fixed it.
        old = ({"a:b#1": tags(T=("o1", "u1"))}, {"a:b#1": "N"})
        new = ({"a:b#2": tags(T=("o2", "u2"))}, {"a:b#2": "N"})
        result = digest_churn.churn(old, new)
        self.assertEqual(result["tags"]["T"]["forced"], 1)
        self.assertEqual(result["tags"]["T"].get("forced_order_only", 0), 0)

    def test_forced_counts_are_broken_down_by_mod_as_well(self):
        # #80's two causes turned out to be mod-disjoint, and that was only visible once the
        # per-mod split was taken over forced pairs.
        old = ({"tconstruct:a#1": tags(T=("o", "u"))}, {"tconstruct:a#1": "N1"})
        new = ({"tconstruct:a#2": tags(T=("p", "v"))}, {"tconstruct:a#2": "N1"})
        result = digest_churn.churn(old, new)
        self.assertEqual(result["forced_mods"]["tconstruct"], 1)

    def test_an_unchanged_item_is_never_counted_as_a_forced_change(self):
        same = ({"a:b#1": tags(T=("o", "u"))}, {"a:b#1": "N"})
        result = digest_churn.churn(same, same)
        self.assertEqual(result["forced_changed_items"], 0)

    def test_a_key_change_no_tag_explains_is_counted_apart(self):
        """The residue that made the header disagree with its own rows.

        The key digests the whole identity compound, so identical per-tag digests over an
        identical tag set ought to mean an identical key. On the reference pack it does not,
        6 times -- and not because of pairing: two entries in ONE dump were found sharing a
        signature under different keys. Attributing these to a tag is impossible, and folding
        them into the forced total made it exceed the sum of the per-tag rows by exactly 6
        with nothing in the output naming the gap.
        """
        old = ({"a:b#1": tags(T=("same", "same"))}, {"a:b#1": "N"})
        new = ({"a:b#2": tags(T=("same", "same"))}, {"a:b#2": "N"})
        result = digest_churn.churn(old, new)
        self.assertEqual(result["changed_items"], 1, "the key did change")
        self.assertEqual(result["unexplained"], 1)
        self.assertEqual(result["forced_changed_items"], 0)
        self.assertEqual(result["tags"].get("T", {}).get("changed", 0), 0)

    def test_the_forced_total_equals_the_sum_of_what_the_tags_explain(self):
        # The invariant the `unexplained` bucket exists to preserve: every forced change is
        # attributed to at least one tag, so a reader can add the rows up and get the header.
        old_trace, old_names, new_trace, new_names = {}, {}, {}, {}
        for i, (o, n) in enumerate([("x", "y"), ("p", "q"), ("same", "same")]):
            old_trace["a:b%d#o" % i] = tags(T=(o, "s"))
            old_names["a:b%d#o" % i] = "N%d" % i
            new_trace["a:b%d#n" % i] = tags(T=(n, "s"))
            new_names["a:b%d#n" % i] = "N%d" % i
        result = digest_churn.churn((old_trace, old_names), (new_trace, new_names))
        self.assertEqual(result["unexplained"], 1)
        self.assertEqual(result["forced_changed_items"], result["tags"]["T"]["forced"])


class Loading(unittest.TestCase):

    def _dump(self, root, name, trace, names):
        d = os.path.join(root, name)
        os.makedirs(d)
        with open(os.path.join(d, "nbt_trace.json"), "w") as fh:
            json.dump(trace, fh)
        with open(os.path.join(d, "names.json"), "w") as fh:
            json.dump(names, fh)
        return d

    def test_it_reads_a_dump_directory(self):
        with tempfile.TemporaryDirectory() as root:
            d = self._dump(root, "one", {"a:b#1": tags(T=("o", "u"))}, {"a:b#1": "N"})
            trace, names = digest_churn.load_dump(d)
            self.assertEqual(names["a:b#1"], "N")
            self.assertEqual(trace["a:b#1"]["T"]["o"], "o")

    def test_it_also_accepts_the_trace_file_itself(self):
        with tempfile.TemporaryDirectory() as root:
            d = self._dump(root, "one", {"a:b#1": tags(T=("o", "u"))}, {"a:b#1": "N"})
            trace, names = digest_churn.load_dump(os.path.join(d, "nbt_trace.json"))
            self.assertEqual(names["a:b#1"], "N")
            self.assertEqual(trace["a:b#1"]["T"]["o"], "o")

    def test_a_dump_without_the_trace_explains_why_rather_than_reading_as_a_bad_path(self):
        # Now that the trace is DEFAULT, an absent one means one of two specific things --
        # `notrace` was passed, or the jar predates 0.6.0 -- and neither is guessable from
        # "file not found". It also cannot be reconstructed afterwards, so the error has to
        # say that another dump is required rather than implying a wrong path.
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaises(SystemExit) as caught:
                digest_churn.load_dump(root)
            message = str(caught.exception)
        self.assertIn(digest_churn.NO_TRACE_ARG, message)
        self.assertIn("cannot be reconstructed", message)
        self.assertIn("/recipedump", message)

    def test_a_missing_names_file_does_not_stop_the_suspect_mode(self):
        # names.json only matters for PAIRING across two dumps; the one-dump suspect list
        # needs the trace alone.
        with tempfile.TemporaryDirectory() as root:
            d = os.path.join(root, "one")
            os.makedirs(d)
            with open(os.path.join(d, "nbt_trace.json"), "w") as fh:
                json.dump({"a:b#1": tags(T=("o", "u"))}, fh)
            trace, names = digest_churn.load_dump(d)
            self.assertEqual(names, {})
            self.assertEqual(digest_churn.suspects(trace)["T"]["flagged"], 1)


class TheJavaWriterContract(unittest.TestCase):
    """The other half of the cross-language contract, like `test_nbt_digest.py`.

    `tests/fixtures/nbt_trace_sample.json` was WRITTEN BY `DumpCommand.writeNbtTrace`, and
    `NbtTraceTest.theWrittenFileMatchesTheFixtureThePythonSideParses` fails if the Java
    output drifts from it. This class parses that exact text. Without the pairing, both
    languages are tested against their own idea of the shape and agree with themselves
    right up until a live dump -- which costs a launch of the game, the one resource this
    whole diagnostic exists to spend carefully.

    DO NOT hand-edit the fixture to make a side pass. A disagreement means one language
    changed the format; regenerate it from the Java per that test's javadoc.
    """

    FIXTURE = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                           "fixtures", "nbt_trace_sample.json")

    def test_the_fixture_the_java_writer_produced_still_exists(self):
        self.assertTrue(os.path.exists(self.FIXTURE),
                        "the Java half of the contract writes this file")

    def test_the_reader_parses_what_the_writer_wrote(self):
        trace, _names = digest_churn.load_dump(self.FIXTURE)
        self.assertEqual(len(trace), 2)
        for key, tags in trace.items():
            self.assertIn("#", key, "every traced key carries a digest")
            for tag, digests in tags.items():
                self.assertRegex(digests["o"], r"^[0-9a-f]{12}$")
                self.assertRegex(digests["u"], r"^[0-9a-f]{12}$")

    def test_the_real_output_drives_the_suspect_logic_end_to_end(self):
        # The fixture deliberately holds one flagged tag and one cleared one, so this
        # exercises both verdicts against bytes Java actually emitted.
        trace, _names = digest_churn.load_dump(self.FIXTURE)
        rows = digest_churn.suspects(trace)
        self.assertEqual(rows["Traits"]["flagged"], 1,
                         "Traits holds an out-of-order list and must be flagged")
        self.assertEqual(rows["Material"]["flagged"], 0)
        self.assertEqual(rows["Material"]["cleared"], 2,
                         "Material is a scalar on both traced items")
        self.assertEqual(rows["Traits"]["mods"]["tconstruct"], 1)

    def test_the_keys_the_writer_emits_split_the_way_the_reader_expects(self):
        # The join between names.json and nbt_trace.json is base_key + display name, so a
        # key the reader cannot split is a silent pairing failure rather than an error.
        trace, _names = digest_churn.load_dump(self.FIXTURE)
        for key in trace:
            self.assertNotIn("#", digest_churn.base_key(key))
            self.assertEqual(digest_churn.mod_of(key), "tconstruct")


class TheJavaSourceContract(unittest.TestCase):
    """The two literals this tool shares with the mod, pinned by reading the Java source.

    Same idea as `test_nbt_digest.JavaSourceContractTest`, and it needs no JVM. Both strings
    are load-bearing across the language boundary and neither is otherwise covered:

      * the FILENAME the mod writes. Rename it in Java and this tool looks for a file that
        is never produced, reporting "no nbt_trace.json ... run as `/recipedump nbttrace`"
        against a dump that DID ask for the trace. The reader would be blaming the user.
      * the ARGUMENT that turns it on, which this tool prints in that error message. If the
        Java flag is renamed, the tool instructs the player to run a command that does
        nothing, and the cost of believing it is a launch of the game.

    The committed fixture cannot catch either one: it is read from a fixed test path, so it
    exercises the SHAPE of the file and never its name or how it came to exist.
    """

    JAVA = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                        "mod", "src", "main", "java", "io", "github", "jacoblasky",
                        "recipedump", "DumpCommand.java")

    @classmethod
    def setUpClass(cls):
        with open(cls.JAVA) as fh:
            cls.src = fh.read()

    def test_the_mod_writes_the_filename_this_tool_reads(self):
        self.assertIn('"%s"' % digest_churn.TRACE_FILE, self.src,
                      "the mod does not write %s" % digest_churn.TRACE_FILE)

    def test_the_mod_reads_the_names_file_this_tool_joins_against(self):
        # The pairing across two dumps is (base key, display name); without names.json
        # there is nothing to pair on.
        self.assertIn('"%s"' % digest_churn.NAMES_FILE, self.src)

    def test_the_suppress_flag_this_tool_names_is_the_one_the_mod_accepts(self):
        # The tool's "no trace here" error names this flag. If Java renames it, the message
        # instructs the player to type something inert, and the cost of believing it is a
        # launch of the game.
        found = re.search(r'NO_TRACE_ARG\s*=\s*"([^"]+)"', self.src)
        self.assertIsNotNone(found, "NO_TRACE_ARG not found in %s" % self.JAVA)
        self.assertEqual(found.group(1), digest_churn.NO_TRACE_ARG)
        with tempfile.TemporaryDirectory() as empty:
            with self.assertRaises(SystemExit) as caught:
                digest_churn.load_dump(empty)
        self.assertIn(digest_churn.NO_TRACE_ARG, str(caught.exception))

    def test_the_trace_is_on_by_default_in_the_mod(self):
        # The tool's error message asserts "every dump from v0.7.0 writes it unless...".
        # That claim lives in Java, so pin it: wantsTrace must return TRUE for no args.
        body = re.search(r"static boolean wantsTrace\(String\[\] args\) \{(.*?)\n    \}",
                         self.src, re.S)
        self.assertIsNotNone(body, "wantsTrace not found in %s" % self.JAVA)
        self.assertIn("return true;", body.group(1),
                      "wantsTrace no longer defaults to writing the trace, but the tool "
                      "still tells the player it is written by default")


class Cli(unittest.TestCase):
    """The output IS the product: its verdict is what decides the fix."""

    def _dump(self, root, name, trace, names):
        d = os.path.join(root, name)
        os.makedirs(d)
        with open(os.path.join(d, "nbt_trace.json"), "w") as fh:
            json.dump(trace, fh)
        with open(os.path.join(d, "names.json"), "w") as fh:
            json.dump(names, fh)
        return d

    def _run(self, argv):
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = digest_churn.main(argv)
        self.assertEqual(rc, 0)
        return buf.getvalue()

    def test_one_dump_prints_the_suspect_table(self):
        with tempfile.TemporaryDirectory() as root:
            d = self._dump(root, "one", {"tconstruct:a#1": tags(Traits=("o", "u"))},
                           {"tconstruct:a#1": "N"})
            out = self._run([d])
            self.assertIn("SUSPECT TAGS", out)
            self.assertIn("Traits", out)

    def test_two_dumps_print_an_order_only_verdict_naming_the_tag(self):
        with tempfile.TemporaryDirectory() as root:
            old = self._dump(root, "old", {"tconstruct:a#1": tags(Traits=("o1", "same"))},
                             {"tconstruct:a#1": "Cobalt Hatchet"})
            new = self._dump(root, "new", {"tconstruct:a#2": tags(Traits=("o2", "same"))},
                             {"tconstruct:a#2": "Cobalt Hatchet"})
            out = self._run([old, new])
            self.assertIn("VERDICT", out)
            self.assertIn("Traits", out)
            self.assertIn("list order is the entire difference", out)

    def test_a_content_change_does_not_claim_sorting_would_fix_it(self):
        # The dangerous wrong answer: recommending the narrow sort fix for churn that
        # sorting cannot address.
        with tempfile.TemporaryDirectory() as root:
            old = self._dump(root, "old", {"a:b#1": tags(T=("o1", "u1"))}, {"a:b#1": "N"})
            new = self._dump(root, "new", {"a:b#2": tags(T=("o2", "u2"))}, {"a:b#2": "N"})
            out = self._run([old, new])
            self.assertIn("would not have helped", out)

    def test_the_unpaired_remainder_appears_in_the_report(self):
        with tempfile.TemporaryDirectory() as root:
            old = self._dump(root, "old",
                             {"a:b#o1": tags(T=("x", "s")), "a:b#o2": tags(T=("y", "s"))},
                             {"a:b#o1": "N", "a:b#o2": "N"})
            new = self._dump(root, "new", {"a:b#n1": tags(T=("z", "s"))}, {"a:b#n1": "N"})
            out = self._run([old, new])
            self.assertIn("EXCLUDED", out)

    def test_an_oversized_exclusion_is_visible_in_the_report(self):
        # The exclusion has to be readable in the output, or the aggregate looks complete.
        with tempfile.TemporaryDirectory() as root:
            ot, on, nt, nn = {}, {}, {}, {}
            for i in range(6):
                ot["a:b#o%d" % i] = tags(T=("o%d" % i, "s"))
                on["a:b#o%d" % i] = "Shared"
                nt["a:b#n%d" % i] = tags(T=("n%d" % i, "s"))
                nn["a:b#n%d" % i] = "Shared"
            old = self._dump(root, "old", ot, on)
            new = self._dump(root, "new", nt, nn)
            out = self._run([old, new, "--max-pairs", "4"])
            self.assertIn("too large to pair", out)
            self.assertIn("EXCLUDED", out)

    def test_json_mode_is_machine_readable(self):
        with tempfile.TemporaryDirectory() as root:
            old = self._dump(root, "old", {"a:b#1": tags(T=("o1", "s"))}, {"a:b#1": "N"})
            new = self._dump(root, "new", {"a:b#2": tags(T=("o2", "s"))}, {"a:b#2": "N"})
            parsed = json.loads(self._run([old, new, "--json"]))
            self.assertEqual(parsed["tags"]["T"]["changed"], 1)
            self.assertEqual(parsed["changed_items"], 1)

    def test_three_dumps_is_an_error_rather_than_a_silent_truncation(self):
        with tempfile.TemporaryDirectory() as root:
            d = self._dump(root, "one", {"a:b#1": tags(T=("o", "u"))}, {"a:b#1": "N"})
            # argparse writes its own usage to stderr; swallow it so a passing suite stays
            # readable rather than looking like it hit a real error.
            with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                digest_churn.main([d, d, d])


if __name__ == "__main__":
    unittest.main()
