"""Just enough PNG to cut one 16x16 sprite out of the icon atlas and re-encode it.

WHY THIS EXISTS RATHER THAN A DEPENDENCY. `render.py`'s module docstring records the rule
that decides it: a plan publishes as a Claude Artifact under a CSP that blocks every
off-host request, so an icon has to arrive as a `data:` URI inside the document. Serving the
atlas page and positioning it with CSS -- which is what the live server does and what needs
no code at all -- is exactly the thing that CSP forbids. And inlining a whole 2048x2048 page
per document is several megabytes of base64 for the twenty items a plan mentions.

So a standalone document needs sprites cut out one at a time, which means decoding a PNG.
recipegraph is python 3 stdlib and the image copies `recipegraph/` and nothing else; adding
Pillow to draw twenty 16x16 thumbnails would put a C extension and a wheel build into a
project that has neither.

ALL FIVE FILTER TYPES ARE IMPLEMENTED, INCLUDING THE ONES THIS PROJECT'S OWN ENCODER NEVER
EMITS. The atlas is written by Java's ImageIO, which picks filters per scanline adaptively,
so "our encoder only uses None" would be reasoning about the wrong encoder -- and the symptom
of getting it wrong is not an exception but a picture that looks like static, on some rows,
for some items. `tests/test_png.py` round-trips an image encoded with every filter.

DELIBERATELY NARROW. 8-bit RGB and RGBA, no interlacing, no palettes, no 16-bit. That is what
`BufferedImage.TYPE_INT_ARGB` through ImageIO produces, and a decoder that quietly mishandled
a format it claimed to support would be worse than one that refuses it: `Unsupported` is
raised by name so the caller can fall back to no icon rather than draw a wrong one.
"""

import struct
import zlib

_SIGNATURE = b"\x89PNG\r\n\x1a\n"


class Unsupported(Exception):
    """This PNG is outside the narrow subset above. Callers fall back to no icon."""


def decode(data):
    """`(width, height, bytearray of RGBA)` for an 8-bit non-interlaced PNG.

    Rows are top-down and pixels are 4 bytes each, so pixel (x, y) starts at
    `(y * width + x) * 4` -- the same layout `encode` takes back.
    """
    if not data.startswith(_SIGNATURE):
        raise Unsupported("not a PNG")
    width = height = None
    channels = 0
    idat = []
    pos = len(_SIGNATURE)
    while pos + 8 <= len(data):
        length, kind = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + length]
        pos += 12 + length          # 4 length + 4 type + body + 4 CRC
        if kind == b"IHDR":
            width, height, depth, colour, compress, filt, interlace = struct.unpack(
                ">IIBBBBB", body[:13])
            if depth != 8:
                raise Unsupported("bit depth %d" % depth)
            if colour not in (2, 6):
                raise Unsupported("colour type %d" % colour)
            if compress != 0 or filt != 0:
                raise Unsupported("compression %d filter %d" % (compress, filt))
            if interlace:
                # Adam7 reorders the whole image, so a decoder that ignored this would
                # produce a scrambled picture rather than an error.
                raise Unsupported("interlaced")
            channels = 3 if colour == 2 else 4
        elif kind == b"IDAT":
            # Concatenated BEFORE inflating, not per chunk: one zlib stream is split across
            # IDAT chunks at arbitrary byte offsets, so inflating them individually fails on
            # any image an encoder chose to split.
            idat.append(body)
        elif kind == b"IEND":
            break
    if width is None:
        raise Unsupported("no IHDR")
    raw = zlib.decompress(b"".join(idat))
    return width, height, _unfilter(raw, width, height, channels)


def _unfilter(raw, width, height, channels):
    """Undo the per-scanline filters and widen RGB to RGBA. Returns RGBA bytes.

    Each row is one filter-type byte followed by `width * channels` bytes, and every filter
    but None refers to the row above, so this cannot start anywhere but row 0.
    """
    stride = width * channels
    out = bytearray(width * height * 4)
    prior = bytearray(stride)
    pos = 0
    for y in range(height):
        method = raw[pos]
        pos += 1
        line = bytearray(raw[pos:pos + stride])
        pos += stride
        if method == 1:      # Sub: the pixel to the left
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 0xFF
        elif method == 2:    # Up: the pixel above
            for i in range(stride):
                line[i] = (line[i] + prior[i]) & 0xFF
        elif method == 3:    # Average: floor((left + above) / 2)
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + ((left + prior[i]) >> 1)) & 0xFF
        elif method == 4:    # Paeth
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                up = prior[i]
                upleft = prior[i - channels] if i >= channels else 0
                line[i] = (line[i] + _paeth(left, up, upleft)) & 0xFF
        elif method != 0:
            raise Unsupported("filter type %d" % method)
        base = y * width * 4
        if channels == 4:
            out[base:base + stride] = line
        else:
            for x in range(width):
                o, s = base + x * 4, x * 3
                out[o:o + 3] = line[s:s + 3]
                out[o + 3] = 0xFF
        prior = line
    return out


def _paeth(a, b, c):
    """The PNG spec's predictor: whichever neighbour is closest to a + b - c."""
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    return b if pb <= pc else c


def encode(width, height, rgba):
    """An RGBA PNG, filter type None on every row.

    NO FILTERING ON PURPOSE. Filters exist to help the deflate stage, and a 16x16 sprite is
    1,024 bytes before compression -- the difference is tens of bytes, against the cost of
    another loop that has to agree with `_unfilter` about five predictors. What this encoder
    produces has to be decodable by a BROWSER, which handles every filter, so there is
    nothing to buy here.
    """
    raw = bytearray()
    stride = width * 4
    for y in range(height):
        raw.append(0)
        raw += rgba[y * stride:(y + 1) * stride]
    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return b"".join([_SIGNATURE,
                     _chunk(b"IHDR", header),
                     _chunk(b"IDAT", zlib.compress(bytes(raw), 9)),
                     _chunk(b"IEND", b"")])


def _chunk(kind, body):
    return (struct.pack(">I", len(body)) + kind + body
            + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF))


def crop(width, height, rgba, x0, y0, size):
    """The `size`x`size` square at (x0, y0) as its own RGBA buffer.

    Raises `Unsupported` when the square runs off the image rather than returning a partial
    sprite. An atlas index that disagrees with its own page by a row is a bug worth failing
    on: silently clamping would give every key past the disagreement the wrong picture, and
    a wrong picture is far harder to notice than a missing one.
    """
    if x0 < 0 or y0 < 0 or x0 + size > width or y0 + size > height:
        raise Unsupported("sprite at (%d,%d) size %d is outside a %dx%d page"
                          % (x0, y0, size, width, height))
    out = bytearray(size * size * 4)
    for row in range(size):
        src = ((y0 + row) * width + x0) * 4
        out[row * size * 4:(row + 1) * size * 4] = rgba[src:src + size * 4]
    return out


def is_blank(rgba):
    """True when every pixel is fully transparent.

    The atlas writer already drops blank sprites, so this is a second line rather than the
    first. It matters because a document that inlines an all-transparent 16x16 spends the
    bytes to draw nothing, and #36's own conclusion is that a hole where the neighbouring
    rows have art reads worse than no icons at all.
    """
    return not any(rgba[i] for i in range(3, len(rgba), 4))
