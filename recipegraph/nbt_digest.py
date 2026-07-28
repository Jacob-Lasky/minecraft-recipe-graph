"""The dump mod's NBT discriminator, recomputed in Python.

WHY THIS EXISTS. Schema 3 gave every NBT-bearing stack a digest of the NBT that decides
what it is, so `forestry:bee_drone_ge#a3f19c02b8d1` is a Forest drone rather than "some
bee". 298,765 of the reference pack's 340,324 named keys are discriminated that way. The
world-save reader did not move with it: it filed everything it could not decode under
` (+nbt)`, so 8,629 drones in an AE2 network read as zero against every bee recipe and
plans put bees Jake already owns on the shopping list. The two sides have to compute the
SAME twelve hex digits from the same stack or they never meet. See #21.

THE FORMAT IS NOT OURS TO CHOOSE. It is defined by `DumpCommand.canonical` and
`DumpCommand.discriminator` in the mod, it is part of dump schema 3, and this module is
the second implementation of it. Two implementations of one hash is exactly the pair that
drifts silently, and the symptom of drift is "the tool says I do not own my bees", which
is indistinguishable from this module not existing. So:

  * `tests/fixtures/nbt_digest.json` is a shared contract. Python asserts it in
    `tests/test_nbt_digest.py`; Java asserts the same file in `DigestFixtureTest`.
    DO NOT edit an expected digest to make one side pass. A disagreement means one side
    changed the format, and the fix is on that side.
  * `COSMETIC_TAGS` and the FNV constants below are checked against the Java source text
    by `tests/test_nbt_digest.py`, so editing one file and not the other fails a test
    that needs no JVM to run.

The format, for reading the code against the mod:

  b/s/i/l  signed decimal of a byte, short, int, long
  f/d      IEEE-754 BITS of a float/double, as a signed int/long. Decimal float
           formatting differs between Java and Python and would split one item in two.
  t<n>:<s> a string, length-prefixed in UTF-16 code units so its contents can never
           imitate the separators around it
  B/I      a byte or int array, each element followed by a comma
  [a;b;]   a list, in order
  {n:k=v;} a compound, keys sorted, each key length-prefixed like a string

then FNV-1a over the UTF-16 code units of that string, keeping the low 48 bits.
"""

import struct

try:
    from .anvil_nbt import (TAG_BYTE, TAG_BYTE_ARRAY, TAG_COMPOUND, TAG_DOUBLE,
                            TAG_FLOAT, TAG_INT, TAG_INT_ARRAY, TAG_LIST, TAG_LONG,
                            TAG_SHORT, TAG_STRING, tag_of)
except ImportError:  # run directly as a script; see ae2_inventory's module docstring
    from anvil_nbt import (TAG_BYTE, TAG_BYTE_ARRAY, TAG_COMPOUND, TAG_DOUBLE,
                           TAG_FLOAT, TAG_INT, TAG_INT_ARRAY, TAG_LIST, TAG_LONG,
                           TAG_SHORT, TAG_STRING, tag_of)

# Tags the mod strips before digesting: they change an item's condition or presentation,
# never what it is, so a repaired pickaxe and a fresh one stay one key.
#
# THIS LIST MUST MATCH `DumpCommand.COSMETIC_TAGS` EXACTLY, and a test asserts that it
# does by reading the Java source. Adding an entry here alone makes the reader strip
# something the dump kept, which merges two stock keys onto a digest no recipe has.
# Adding it in Java alone does the reverse. See #63, which wants `ench` added: that is a
# change to BOTH lists plus a re-dump, in that order.
COSMETIC_TAGS = ("RepairCost", "display", "HideFlags", "Damage")

FNV_OFFSET = 0xCBF29CE484222325
FNV_PRIME = 0x100000001B3
# 48 bits kept. Not a checksum: it only has to separate the few thousand NBT variants in
# one pack, and 12 hex digits stay short enough to read inside a key.
DIGEST_BITS = 48
DIGEST_MASK = (1 << DIGEST_BITS) - 1
DIGEST_DIGITS = DIGEST_BITS // 4

# Java canonicalises every NaN to one bit pattern (`Float.floatToIntBits`,
# `Double.doubleToLongBits`), so reproducing it means doing the same rather than passing
# whatever payload the parse happened to yield.
_NAN_F32 = 0x7FC00000
_NAN_F64 = 0x7FF8000000000000


class UntypedNode(TypeError):
    """A value reached the digest without its NBT tag type.

    A programming error, not a data condition: `anvil_nbt.read_payload` types everything
    it parses, so this means a hand-built tree used a plain `int` where the tag matters.
    Raised rather than guessed, because a guessed type yields a digest that looks fine
    and matches nothing.
    """


class OpaqueTag(ValueError):
    """A tag the MOD ITSELF does not serialise reproducibly.

    `DumpCommand.canonical` has no case for TAG_Long_Array and falls through to
    `node.toString()`, which is Java formatting no other language can reproduce. That is
    a gap in the format, not in this module, and it cannot be closed without a schema
    bump and a re-dump. Propagated out of `digest` rather than flattened into its None,
    which means "the bare key is right": here the bare key is WRONG and the caller must
    fall back to the opaque marker instead.
    """


def _utf16(text):
    # surrogatepass because `Reader.string` decodes with errors="replace" but a lone
    # surrogate can still survive a modified-UTF-8 sequence, and raising here would
    # abort a whole region scan over one malformed item name.
    return text.encode("utf-16-be", "surrogatepass")


def _u16len(text):
    """`String.length()` in Java: UTF-16 code units, not code points."""
    return len(_utf16(text)) // 2


def _float_bits(value):
    # Round-tripping the double back through float32 is exact for every finite float32
    # and for the infinities, so only NaN needs the explicit case.
    if value != value:
        return _NAN_F32
    return struct.unpack(">i", struct.pack(">f", value))[0]


def _double_bits(value):
    if value != value:
        return _NAN_F64
    return struct.unpack(">q", struct.pack(">d", value))[0]


def canonical(node):
    """The mod's deterministic, language-neutral rendering of one NBT value."""
    out = []
    _write(node, out)
    return "".join(out)


def _write(node, out):
    tag = tag_of(node)
    if tag is None:
        raise UntypedNode(
            "%r has no NBT tag type; byte/short/int/long and float/double are not"
            " interchangeable in the digest" % (node,))
    if tag == TAG_BYTE:
        out.append("b%d" % node)
    elif tag == TAG_SHORT:
        out.append("s%d" % node)
    elif tag == TAG_INT:
        out.append("i%d" % node)
    elif tag == TAG_LONG:
        out.append("l%d" % node)
    elif tag == TAG_FLOAT:
        out.append("f%d" % _float_bits(node))
    elif tag == TAG_DOUBLE:
        out.append("d%d" % _double_bits(node))
    elif tag == TAG_BYTE_ARRAY:
        out.append("B")
        # Java bytes are signed and Python's are not, so 0xFF is -1 here or the two
        # sides disagree on every array holding a high byte.
        for value in struct.unpack("%db" % len(node), node):
            out.append("%d," % value)
    elif tag == TAG_STRING:
        out.append("t%d:%s" % (_u16len(node), node))
    elif tag == TAG_LIST:
        out.append("[")
        for item in node:
            _write(item, out)
            out.append(";")
        out.append("]")
    elif tag == TAG_COMPOUND:
        out.append("{")
        # Sorted the way `Collections.sort` sorts Java Strings: by UTF-16 code unit,
        # which is what comparing the big-endian encodings does. Python's default sorts
        # by code point, and the two disagree once a key holds an astral character.
        for key in sorted(node, key=_utf16):
            out.append("%d:%s=" % (_u16len(key), key))
            _write(node[key], out)
            out.append(";")
        out.append("}")
    elif tag == TAG_INT_ARRAY:
        out.append("I")
        for value in node:
            out.append("%d," % value)
    else:
        raise OpaqueTag("tag %d is serialised by Java's toString() and cannot be"
                        " reproduced here" % tag)


def fnv1a(text):
    """FNV-1a over UTF-16 code units, matching Java's `charAt` loop.

    Code units, not code points: Java hashes an emoji as its two surrogates. Ordinary
    item NBT is ASCII and the two agree there, but "agrees on the data I happened to
    look at" is how a hash function drifts.
    """
    encoded = _utf16(text)
    h = FNV_OFFSET
    for unit in struct.unpack(">%dH" % (len(encoded) // 2), encoded):
        h = ((h ^ unit) * FNV_PRIME) & 0xFFFFFFFFFFFFFFFF
    return h


def digest(tag):
    """The `#suffix` for a stack's NBT compound, mirroring `DumpCommand.discriminator`.

    None means exactly what the mod's null means: THIS STACK IS THE BARE KEY. It carries
    no NBT, or nothing but cosmetic NBT, and the dump gave it no suffix either, so a
    caller should not add one. A renamed pickaxe is still a pickaxe.

    `OpaqueTag` PROPAGATES rather than becoming another None, because "the bare key is
    correct" and "this module cannot answer" are opposite conclusions and a caller that
    treats them alike will unify a stack it should have kept separate. The one caller,
    `ae2_inventory.classify`, catches it and falls back to the opaque marker.
    """
    if not isinstance(tag, dict) or not tag:
        return None
    stripped = {k: v for k, v in tag.items() if k not in COSMETIC_TAGS}
    if not stripped:
        return None
    return "%0*x" % (DIGEST_DIGITS, fnv1a(canonical(stripped)) & DIGEST_MASK)
