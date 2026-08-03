"""The NBT digest, which decides whether a stack in your AE2 network is a stack in a recipe.

Two implementations of one hash is the pair that drifts silently, and the symptom of
drift is "the tool says I do not own my bees" -- indistinguishable from the port not
existing at all. So this file checks the Python side three ways:

  1. Against `tests/fixtures/nbt_digest.json`, the shared contract the Java suite reads
     too. Six of its cases are GROUND TRUTH: their digests were produced by the mod and
     read back out of the shipped schema-3 dump, so matching them proves the port rather
     than proving it agrees with itself.
  2. Against the Java SOURCE TEXT, for the constants a fixture cannot pin: the cosmetic
     tag list, the FNV seed and prime, the digest width. Editing one language and not the
     other fails here, with no JVM needed.
  3. Through the real parse path, from NBT bytes rather than a hand-built tree, because
     the types the digest depends on are exactly what a parser is liable to drop.
"""

import json
import os
import re
import struct
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import nbt_digest  # noqa: E402
from recipegraph.anvil_nbt import (Byte, Double, Float, Int, IntArray, Long,  # noqa: E402
                                   LongArray, Short, parse_nbt, tag_of)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FIXTURE = os.path.join(ROOT, "tests", "fixtures", "nbt_digest.json")
JAVA = os.path.join(ROOT, "mod", "src", "main", "java", "io", "github", "jacoblasky",
                    "recipedump", "DumpCommand.java")

_BUILD = {
    "byte": Byte,
    "short": Short,
    "int": Int,
    "long": Long,
    "float": Float,
    "double": Double,
    "string": lambda v: v,
    # The fixture writes byte arrays as the SIGNED values Java shows, so that a reader
    # can check them against the canonical string by eye.
    "bytearray": lambda v: bytes((x + 256) % 256 for x in v),
    "intarray": IntArray,
    "longarray": LongArray,
    "list": lambda v: [_build(x) for x in v],
    "compound": lambda v: {k: _build(x) for k, x in v.items()},
}


def _build(node):
    kind, value = node
    return _BUILD[kind](value)


def _tree(case):
    return {k: _build(v) for k, v in case["nbt"].items()}


def load_cases():
    with open(FIXTURE) as fh:
        return json.load(fh)["cases"]


def by_name(cases):
    return {c["name"]: c for c in cases}


class FixtureTest(unittest.TestCase):
    """Every case in the shared contract, canonical string and digest."""

    @classmethod
    def setUpClass(cls):
        cls.cases = load_cases()

    def test_every_case_digests_to_the_recorded_value(self):
        for case in self.cases:
            with self.subTest(case["name"]):
                if case.get("java_diverges"):
                    # The fixture records both halves of the divergence: python declines
                    # loudly, and the Java suite asserts its own side still answers.
                    self.assertIsNone(case["digest"])
                    with self.assertRaises(nbt_digest.OpaqueTag):
                        nbt_digest.digest(_tree(case))
                    continue
                self.assertEqual(nbt_digest.digest(_tree(case)), case["digest"],
                                 case["note"])

    def test_every_case_renders_the_recorded_canonical_string(self):
        # Asserted separately from the digest because a hash mismatch says only "these
        # differ"; the canonical string says WHERE.
        #
        # COUNTED, because the `continue` is how this test can pass without running. A
        # regenerated fixture that stopped writing `canonical` -- or wrote it as null --
        # skips every case and leaves a green no-op where the whole cross-language string
        # contract used to be. 42 of the 46 cases carry it; the four that do not are the
        # `java_diverges` case and the empty-tag ones. `NodeStatusTest` counts its rows out
        # of present.py for the same reason and is the pattern here.
        #
        # `DigestFixtureTest.everyCaseRendersTheRecordedCanonicalString` asserts the same 42
        # on the Java side. TWO INDEPENDENT LITERALS RATHER THAN ONE SHARED NUMBER, on
        # purpose: a count carried in the fixture would be regenerated along with the field
        # it is meant to guard, so it has to come from outside the fixture -- and having to
        # edit both languages is the deliberate cost of adding a case.
        checked = 0
        for case in self.cases:
            # Subscript rather than `.get`, so a fixture that DROPS the field raises here
            # instead of quietly skipping. The count below is for the other half: a field
            # present and null.
            if case["canonical"] is None:
                continue
            with self.subTest(case["name"]):
                tree = {k: v for k, v in _tree(case).items()
                        if k not in nbt_digest.COSMETIC_TAGS}
                self.assertEqual(nbt_digest.canonical(tree), case["canonical"])
            checked += 1
        self.assertEqual(42, checked,
                         "the fixture must carry 42 canonical strings; a case that stopped "
                         "recording one is skipped silently and asserts nothing")

    def test_the_ground_truth_cases_are_real_dump_output(self):
        # Guards the provenance, not the arithmetic. These six digests were not computed
        # here: they are keys out of data/graph.json, written by the mod. If someone
        # regenerates the fixture from this implementation they must not quietly relabel
        # a self-computed value as ground truth.
        expected = {
            "potion of healing": "84c94462a641",
            "potion of strong healing": "1c1b9a77647d",
            "spawn egg, nested compound": "7607aba3d959",
            "spawn egg, chicken": "00fe6f1edf1e",
            "vis pod, aqua": "03c878f080d5",
            "vis pod, perditio": "531b71e0ba2d",
        }
        cases = by_name(self.cases)
        for name, digest in expected.items():
            self.assertEqual(cases[name]["digest"], digest, name)
            self.assertIn("=", cases[name]["note"], "note must name the dump key")

    def test_every_case_is_named_once(self):
        names = [c["name"] for c in self.cases]
        self.assertEqual(len(names), len(set(names)), "subTest labels must be unique")


class DiscriminationTest(unittest.TestCase):
    """The relationships between fixture cases, which are the actual claims.

    `DiscriminatorTest` on the Java side asserts these same pairs. A digest that is
    merely stable is worthless; what matters is that it separates what should be
    separate and unifies what should be unified.
    """

    @classmethod
    def setUpClass(cls):
        cls.cases = by_name(load_cases())

    def digest(self, name):
        """RECOMPUTED from the case's NBT, not read back out of the fixture.

        Reading the recorded digest made every test in this class pass on code with the #80
        and #63 fixes reverted: the fixture says two cases share a digest, and comparing two
        recorded strings to each other cannot notice that the implementation no longer
        produces either of them. `FixtureTest` would still have failed, so the pair was sound
        in composition -- but a test that holds only because a different test also runs is
        one deletion away from being decorative, and these are the claims the change is FOR.

        `FixtureTest` keeps pinning recorded == recomputed, which is what stops a careless
        regeneration from moving the ground truth and taking these relationships with it.
        """
        return nbt_digest.digest(_tree(self.cases[name]))

    def test_two_species_get_two_digests(self):
        self.assertNotEqual(self.digest("species, forest"),
                            self.digest("species, meadows"))
        self.assertRegex(self.digest("species, forest"), r"^[0-9a-f]{12}$")

    def test_cosmetic_tags_are_ignored(self):
        self.assertEqual(self.digest("species, forest"),
                         self.digest("species with cosmetic tags added"))

    def test_key_insertion_order_does_not_matter(self):
        self.assertEqual(self.digest("keys in one order"),
                         self.digest("keys in another order"))

    def test_a_stack_with_no_identity_in_its_nbt_has_no_digest(self):
        self.assertIsNone(self.digest("no nbt at all"))
        self.assertIsNone(self.digest("cosmetic only"))

    def test_the_five_types_of_five_are_five_digests(self):
        # The whole reason the parser had to keep tag types. Before #21 a byte 5 and an
        # int 5 were both Python `int` 5 and would have collided here.
        five = [self.digest(n) for n in ("five as a string", "five as a byte",
                                         "five as a short", "five as an int",
                                         "five as a long")]
        self.assertEqual(len(set(five)), 5, five)

    def test_structure_cannot_be_forged_out_of_string_contents(self):
        self.assertNotEqual(self.digest("separators inside a string"),
                            self.digest("the structure it imitates"))

    def test_list_order_matters(self):
        self.assertNotEqual(self.digest("list in order"), self.digest("list reversed"))

    def test_the_order_of_a_special_list_does_not_matter(self):
        """#80's fix. The pair that churned 9,359 times, reduced to one key."""
        self.assertEqual(self.digest("special list in one order"),
                         self.digest("special list in another order"))

    def test_a_special_list_is_sorted_at_any_depth(self):
        self.assertEqual(self.digest("special list nested one level down"),
                         self.digest("special list nested one level down, permuted"))

    def test_sorting_does_not_leak_from_special_to_a_sibling(self):
        """The narrowness IS the fix, so it needs a test that fails if the sort spreads.

        `canonical` threads one flag down the tree, and the flag turning on for `Special`
        must not turn on for the compound that CONTAINS it. If it leaked, the sibling `l`
        would sort too and the last assertion here would collapse into the first -- which is
        the global sort that `canonical`'s javadoc rejects, arrived at by accident.
        """
        self.assertEqual(self.digest("special beside an ordinary list"),
                         self.digest("special beside an ordinary list, special permuted"))
        self.assertNotEqual(self.digest("special beside an ordinary list"),
                            self.digest("special beside an ordinary list, sibling permuted"))

    def test_the_sort_uses_java_string_order_not_python_string_order(self):
        """The one place `sorted(parts)` and `sorted(parts, key=_utf16)` disagree.

        Java's `Collections.sort` on Strings compares UTF-16 code units; Python's default
        compares code points. The two agree on everything until a string holds an astral
        character, and then they invert. The fixture pair here is built so the length prefix
        cannot decide the order, which forces the comparison onto the characters themselves --
        without it the case would pass under either sort and prove nothing.

        `DigestFixtureTest` checks the same two cases in a real JVM, which is what makes this
        a cross-language claim rather than python agreeing with itself.
        """
        self.assertEqual(self.digest("special list sorted across a surrogate pair"),
                         self.digest("special list sorted across a surrogate pair, permuted"))
        # And the sort must have landed on the CODE UNIT order, not merely on some order.
        case = self.cases["special list sorted across a surrogate pair"]
        self.assertEqual(nbt_digest.canonical(_tree(case)),
                         "{7:Special=[t2:\U00010000;t2:￿￿;];}")

    def test_a_list_inside_a_special_list_is_sorted_too(self):
        # The trace's "u" field sorts the whole subtree, and "u" is what measured `Special`
        # as order-only. A fix that sorted only the outermost list would not be the thing
        # the measurement licensed.
        self.assertEqual(self.digest("special holding a nested list"),
                         self.digest("special holding a nested list, inner permuted"))

    def test_enchantments_do_not_change_what_an_item_is(self):
        """#63. An enchanted item is the same crafting ingredient as a plain one."""
        self.assertEqual(self.digest("species, forest"),
                         self.digest("enchanted, with a species"))
        self.assertEqual(self.digest("species, forest"),
                         self.digest("enchanted differently, same species"))

    def test_a_stack_whose_only_nbt_is_enchantments_is_the_bare_key(self):
        self.assertIsNone(self.digest("enchantments only"))

    def test_a_float_and_a_double_of_the_same_number_differ(self):
        self.assertNotEqual(self.digest("float 0.1"), self.digest("double 0.1"))


class TypedNodeTest(unittest.TestCase):
    def test_an_untyped_number_is_an_error_not_a_guess(self):
        # A plain int could be any of four tags. Guessing yields a digest that looks
        # perfectly good and matches nothing, which is the failure mode this whole
        # module exists to end.
        with self.assertRaises(nbt_digest.UntypedNode):
            nbt_digest.digest({"v": 5})
        with self.assertRaises(nbt_digest.UntypedNode):
            nbt_digest.digest({"v": 0.5})

    def test_untyped_numbers_nested_anywhere_are_caught(self):
        with self.assertRaises(nbt_digest.UntypedNode):
            nbt_digest.digest({"c": {"l": [Int(1), 2]}})

    def test_a_tag_java_serialises_by_tostring_declines_rather_than_guessing(self):
        # DumpCommand.canonical has no case for TAG_Long_Array, so the mod falls through
        # to Java's toString(). Reproducing that is not possible; producing a plausible
        # digest anyway would be worse than admitting it.
        with self.assertRaises(nbt_digest.OpaqueTag):
            nbt_digest.canonical(LongArray([1, 2]))
        with self.assertRaises(nbt_digest.OpaqueTag):
            nbt_digest.digest({"a": LongArray([1, 2])})

    def test_no_digest_and_cannot_digest_are_different_answers(self):
        # Flattening `OpaqueTag` into the same None that means "the bare key is right"
        # would unify a stack whose identity is genuinely unknown with the plain item.
        self.assertIsNone(nbt_digest.digest({"RepairCost": Int(3)}))
        with self.assertRaises(nbt_digest.OpaqueTag):
            nbt_digest.digest({"RepairCost": Int(3), "a": LongArray([1])})

    def test_tag_of_reports_the_type_the_parser_kept(self):
        self.assertEqual(tag_of(Byte(1)), 1)
        self.assertEqual(tag_of(Short(1)), 2)
        self.assertEqual(tag_of(Int(1)), 3)
        self.assertEqual(tag_of(Long(1)), 4)
        self.assertEqual(tag_of(Float(1)), 5)
        self.assertEqual(tag_of(Double(1)), 6)
        self.assertEqual(tag_of(b"\x01"), 7)
        self.assertEqual(tag_of("x"), 8)
        self.assertEqual(tag_of([]), 9)
        self.assertEqual(tag_of({}), 10)
        self.assertEqual(tag_of(IntArray([1])), 11)
        self.assertEqual(tag_of(LongArray([1])), 12)
        self.assertIsNone(tag_of(1))
        self.assertIsNone(tag_of(1.0))

    def test_typed_numbers_still_behave_as_numbers(self):
        # The reason subclasses were chosen over wrapper objects: `classify` tests
        # `isinstance(value, int) and value > 0`, and `json.dump` has to serialise a
        # scan result. Both must keep working on a parsed tree.
        self.assertTrue(isinstance(Int(7), int))
        self.assertEqual(Int(7) + 1, 8)
        self.assertEqual(json.dumps({"a": Int(7), "b": Double(0.5)}),
                         '{"a": 7, "b": 0.5}')


def _nbt_bytes(pairs):
    """A root TAG_Compound as real NBT bytes. `pairs` is [(tag id, name, payload)]."""
    out = bytearray(b"\x0a\x00\x00")
    for tag, name, payload in pairs:
        encoded = name.encode("utf-8")
        out += struct.pack(">BH", tag, len(encoded)) + encoded + payload
    out += b"\x00"
    return bytes(out)


class ParsedFromBytesTest(unittest.TestCase):
    """The production path: bytes off disk, not a tree built by hand.

    Everything above builds typed values directly, so all of it would still pass if
    `read_payload` went back to returning plain ints. This is the test that would not.
    """

    def test_a_byte_and_an_int_parsed_from_bytes_stay_distinct(self):
        as_byte = parse_nbt(_nbt_bytes([(1, "v", b"\x05")]))
        as_int = parse_nbt(_nbt_bytes([(3, "v", struct.pack(">i", 5))]))
        self.assertEqual(as_byte, as_int, "both read as the number five")
        cases = by_name(load_cases())
        self.assertEqual(nbt_digest.digest(as_byte), cases["five as a byte"]["digest"])
        self.assertEqual(nbt_digest.digest(as_int), cases["five as an int"]["digest"])

    def test_a_real_potion_stack_parsed_from_bytes_hits_the_dump_key(self):
        # End to end, and against a digest the mod wrote: this is the exact shape the
        # world-save reader pulls out of an AE2 cell.
        potion = b"minecraft:healing"
        raw = _nbt_bytes([(8, "Potion", struct.pack(">H", len(potion)) + potion)])
        self.assertEqual(nbt_digest.digest(parse_nbt(raw)), "84c94462a641")

    def test_nested_compounds_and_lists_survive_the_parse(self):
        pig = b"minecraft:pig"
        inner = _nbt_bytes([(8, "id", struct.pack(">H", len(pig)) + pig)])[3:]
        raw = _nbt_bytes([(10, "EntityTag", inner)])
        self.assertEqual(nbt_digest.digest(parse_nbt(raw)), "7607aba3d959")

    def test_a_byte_array_keeps_its_sign_through_the_parse(self):
        # 0x80 is -128 to Java and 128 to Python, and the digest follows Java.
        raw = _nbt_bytes([(7, "a", struct.pack(">i", 5) + bytes([0, 1, 1, 127, 128]))])
        self.assertEqual(nbt_digest.canonical(parse_nbt(raw)), "{1:a=B0,1,1,127,-128,;}")


class JavaSourceContractTest(unittest.TestCase):
    """Constants a fixture cannot pin, read straight out of the mod's source.

    A fixture proves the two sides agree on the cases someone thought to write down. It
    cannot notice that `ench` was added to the cosmetic list in Java only, because no
    fixture case carries an `ench` tag. These do, and they run in the ordinary suite
    with no JVM and no build.
    """

    @classmethod
    def setUpClass(cls):
        with open(JAVA) as fh:
            cls.src = fh.read()

    def test_the_cosmetic_tag_lists_are_the_same_list(self):
        # `ench` joined this list in schema 4 (#63). The list is a change to BOTH languages
        # plus a re-dump, and this test is what makes forgetting the second one loud.
        block = re.search(r"COSMETIC_TAGS\s*=\s*\{(.*?)\};", self.src, re.S)
        self.assertIsNotNone(block, "COSMETIC_TAGS not found in %s" % JAVA)
        java = tuple(re.findall(r'"([^"]+)"', block.group(1)))
        self.assertEqual(java, nbt_digest.COSMETIC_TAGS)

    def test_the_sorted_list_tags_are_the_same_list(self):
        """#80's other half, and it drifts exactly the way COSMETIC_TAGS would.

        A name in one language only means the reader sorts a list the dump left alone (or the
        reverse), so every stack carrying that tag gets a digest the other side never
        computes. Same silent symptom as a COSMETIC_TAGS mismatch: stock that reads as zero.
        """
        block = re.search(r"SORTED_LIST_TAGS\s*=\s*\{(.*?)\};", self.src, re.S)
        self.assertIsNotNone(block, "SORTED_LIST_TAGS not found in %s" % JAVA)
        java = tuple(re.findall(r'"([^"]+)"', block.group(1)))
        self.assertEqual(java, nbt_digest.SORTED_LIST_TAGS)

    def test_no_tag_is_both_stripped_and_sorted(self):
        # The two lists would not error if they overlapped, they would just make the sort
        # dead code on a tag nothing digests -- which reads in review as a live claim about
        # order and is not one.
        self.assertEqual(set(nbt_digest.COSMETIC_TAGS) & set(nbt_digest.SORTED_LIST_TAGS),
                         set())

    def test_the_schema_the_digest_format_belongs_to_is_the_dump_schema(self):
        """`DIGEST_FORMAT_SCHEMA` has to name a schema the mod actually stamps.

        It exists to be compared against a graph's recorded schema, so a value above what any
        dump can carry would flag every graph forever, and a value below the schema in which
        the format last changed would flag none of them.
        """
        found = re.search(r"static final int SCHEMA\s*=\s*(\d+)", self.src)
        self.assertIsNotNone(found, "SCHEMA constant not found in %s" % JAVA)
        self.assertLessEqual(nbt_digest.DIGEST_FORMAT_SCHEMA, int(found.group(1)))

    def test_the_fnv_seed_and_prime_are_the_same_numbers(self):
        seed = re.search(r"long h = 0x([0-9a-fA-F]+)L;", self.src)
        prime = re.search(r"\* 0x([0-9a-fA-F]+)L;", self.src)
        self.assertIsNotNone(seed)
        self.assertIsNotNone(prime)
        self.assertEqual(int(seed.group(1), 16), nbt_digest.FNV_OFFSET)
        self.assertEqual(int(prime.group(1), 16), nbt_digest.FNV_PRIME)

    def test_the_digest_is_the_same_width_and_mask(self):
        fmt = re.search(r'String\.format\("%0(\d+)x", h & 0x([0-9a-fA-F]+)L\)', self.src)
        self.assertIsNotNone(fmt, "the digest format line moved")
        self.assertEqual(int(fmt.group(1)), nbt_digest.DIGEST_DIGITS)
        self.assertEqual(int(fmt.group(2), 16), nbt_digest.DIGEST_MASK)

    def test_the_java_test_reads_the_same_fixture_this_one_does(self):
        # The contract is only shared if both sides actually open the file.
        path = os.path.join(ROOT, "mod", "src", "test", "java", "io", "github",
                            "jacoblasky", "recipedump", "DigestFixtureTest.java")
        self.assertTrue(os.path.exists(path), "the Java half of the contract is missing")
        with open(path) as fh:
            self.assertIn("nbt_digest.json", fh.read())


if __name__ == "__main__":
    unittest.main()
