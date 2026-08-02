"""The stdlib PNG codec that lets a standalone plan inline item sprites. #36

WHY THIS IS TESTED HARDER THAN IT LOOKS. The atlas is written by Java's ImageIO, which picks
a filter per scanline adaptively, and none of the filters this project's own encoder emits.
A decoder that got one of them wrong would not raise: it would return a picture that is
correct for most rows and static for the rest, on some items, and the next chance to notice
is a launch of the game. So the round-trip below builds a fixture using EVERY filter type by
hand rather than trusting our own encoder to exercise them.
"""

import os
import struct
import sys
import unittest
import zlib

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import png  # noqa: E402


def _rgba(width, height, fn):
    out = bytearray()
    for y in range(height):
        for x in range(width):
            out += bytes(fn(x, y))
    return out


def _encode_with_filters(width, height, rgba, filters):
    """A valid RGBA PNG whose scanline `y` uses `filters[y % len(filters)]`.

    Hand-built rather than produced by `png.encode`, which only ever writes filter 0. The
    point is to decode bytes our own encoder cannot make.
    """
    stride = width * 4
    raw = bytearray()
    prior = bytearray(stride)
    for y in range(height):
        method = filters[y % len(filters)]
        line = bytearray(rgba[y * stride:(y + 1) * stride])
        encoded = bytearray(stride)
        for i in range(stride):
            left = line[i - 4] if i >= 4 else 0
            up = prior[i]
            upleft = prior[i - 4] if i >= 4 else 0
            if method == 0:
                encoded[i] = line[i]
            elif method == 1:
                encoded[i] = (line[i] - left) & 0xFF
            elif method == 2:
                encoded[i] = (line[i] - up) & 0xFF
            elif method == 3:
                encoded[i] = (line[i] - ((left + up) >> 1)) & 0xFF
            else:
                encoded[i] = (line[i] - png._paeth(left, up, upleft)) & 0xFF
        raw.append(method)
        raw += encoded
        prior = line
    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return b"".join([b"\x89PNG\r\n\x1a\n",
                     png._chunk(b"IHDR", header),
                     png._chunk(b"IDAT", zlib.compress(bytes(raw))),
                     png._chunk(b"IEND", b"")])


class RoundTripTest(unittest.TestCase):
    def setUp(self):
        self.w = self.h = 12
        self.rgba = _rgba(self.w, self.h,
                          lambda x, y: (x * 17 % 256, y * 23 % 256, (x * y) % 256,
                                        255 if (x + y) % 5 else 0))

    def test_our_own_encoder_round_trips(self):
        w, h, got = png.decode(png.encode(self.w, self.h, self.rgba))
        self.assertEqual((w, h), (self.w, self.h))
        self.assertEqual(bytes(got), bytes(self.rgba))

    def test_every_filter_type_decodes_to_the_same_image(self):
        """The assertion that matters, and the one our own encoder cannot make.

        ImageIO chooses filters per row. If `_unfilter` had `Average` or `Paeth` wrong, the
        atlas would decode to garbage on exactly the rows that used them -- so this fixture
        cycles all five through a single image rather than testing them one at a time, which
        is also the only way the "refers to the row above" coupling gets exercised.
        """
        for filters in ([0], [1], [2], [3], [4], [0, 1, 2, 3, 4], [4, 3, 2, 1, 0]):
            blob = _encode_with_filters(self.w, self.h, self.rgba, filters)
            w, h, got = png.decode(blob)
            self.assertEqual((w, h), (self.w, self.h), filters)
            self.assertEqual(bytes(got), bytes(self.rgba), filters)

    def test_rgb_widens_to_opaque_rgba(self):
        # ImageIO writes colour type 2 when an image has no transparency, so a decoder that
        # only handled type 6 would fail on a fully opaque atlas page and on nothing else.
        rgb = bytearray()
        for y in range(4):
            for x in range(4):
                rgb += bytes([x * 40, y * 40, 7])
        raw = bytearray()
        for y in range(4):
            raw.append(0)
            raw += rgb[y * 12:(y + 1) * 12]
        blob = b"".join([b"\x89PNG\r\n\x1a\n",
                         png._chunk(b"IHDR", struct.pack(">IIBBBBB", 4, 4, 8, 2, 0, 0, 0)),
                         png._chunk(b"IDAT", zlib.compress(bytes(raw))),
                         png._chunk(b"IEND", b"")])
        _w, _h, got = png.decode(blob)
        self.assertEqual(list(got[:4]), [0, 0, 7, 255])
        self.assertEqual(set(got[3::4]), {255})

    def test_idat_split_across_chunks_still_decodes(self):
        """One zlib stream, several IDAT chunks: legal, common, and easy to get wrong.

        Inflating chunks individually rather than concatenating first fails on any encoder
        that chose to split, and the split point is arbitrary -- so the failure would depend
        on image size and look like "big atlases are corrupt".
        """
        blob = png.encode(self.w, self.h, self.rgba)
        head = blob.index(b"IDAT") - 4
        length = struct.unpack(">I", blob[head:head + 4])[0]
        body = blob[head + 8:head + 8 + length]
        cut = len(body) // 2
        rebuilt = (blob[:head] + png._chunk(b"IDAT", body[:cut])
                   + png._chunk(b"IDAT", body[cut:]) + blob[head + 12 + length:])
        _w, _h, got = png.decode(rebuilt)
        self.assertEqual(bytes(got), bytes(self.rgba))


class RefusalTest(unittest.TestCase):
    """Every unsupported form must RAISE, never decode to something wrong.

    A wrong picture is far harder to notice than a missing one, and the caller's fallback
    for `Unsupported` is simply to draw no icon.
    """

    def test_not_a_png(self):
        with self.assertRaises(png.Unsupported):
            png.decode(b"GIF89a and then some")

    def test_interlaced_is_refused_rather_than_scrambled(self):
        header = struct.pack(">IIBBBBB", 4, 4, 8, 6, 0, 0, 1)
        blob = b"\x89PNG\r\n\x1a\n" + png._chunk(b"IHDR", header)
        with self.assertRaises(png.Unsupported):
            png.decode(blob)

    def test_sixteen_bit_and_palette_are_refused(self):
        for depth, colour in ((16, 6), (8, 3)):
            header = struct.pack(">IIBBBBB", 4, 4, depth, colour, 0, 0, 0)
            blob = b"\x89PNG\r\n\x1a\n" + png._chunk(b"IHDR", header)
            with self.assertRaises(png.Unsupported):
                png.decode(blob)


class CropTest(unittest.TestCase):
    def setUp(self):
        # A 4x4 grid of 2x2 sprites, each a flat colour equal to its cell index.
        self.size = 2
        self.w = self.h = 8
        self.rgba = _rgba(self.w, self.h,
                          lambda x, y: ((y // 2) * 4 + (x // 2), 0, 0, 255))

    def test_it_cuts_the_square_the_index_names(self):
        sprite = png.crop(self.w, self.h, self.rgba, 2 * self.size, 1 * self.size, self.size)
        self.assertEqual(len(sprite), self.size * self.size * 4)
        self.assertEqual(set(sprite[0::4]), {1 * 4 + 2})

    def test_a_sprite_off_the_edge_raises_rather_than_clamping(self):
        # An index that disagrees with its page by a row would otherwise give every key past
        # the disagreement a plausible, wrong picture.
        with self.assertRaises(png.Unsupported):
            png.crop(self.w, self.h, self.rgba, 7, 7, self.size)
        with self.assertRaises(png.Unsupported):
            png.crop(self.w, self.h, self.rgba, -1, 0, self.size)

    def test_blank_is_alpha_only(self):
        self.assertTrue(png.is_blank(bytearray(b"\xff\xff\xff\x00" * 16)))
        self.assertFalse(png.is_blank(bytearray(b"\x00\x00\x00\x01" * 16)))


if __name__ == "__main__":
    unittest.main()
