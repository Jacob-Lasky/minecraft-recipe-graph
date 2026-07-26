"""Pure-stdlib Anvil region + NBT reader.

No third-party deps on purpose: the AMP container has python3 but no pip, so this
module must run unchanged both here and inside the container. DO NOT add nbtlib
or numpy -- that breaks in-container use, which is the whole point.
"""

import gzip
import struct
import zlib

TAG_END = 0
TAG_BYTE = 1
TAG_SHORT = 2
TAG_INT = 3
TAG_LONG = 4
TAG_FLOAT = 5
TAG_DOUBLE = 6
TAG_BYTE_ARRAY = 7
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10
TAG_INT_ARRAY = 11
TAG_LONG_ARRAY = 12


class Reader:
    __slots__ = ("buf", "pos")

    def __init__(self, buf):
        self.buf = buf
        self.pos = 0

    def raw(self, n):
        p = self.pos
        self.pos = p + n
        if self.pos > len(self.buf):
            raise EOFError("truncated NBT")
        return self.buf[p:self.pos]

    def u1(self):
        return self.raw(1)[0]

    def i1(self):
        return struct.unpack(">b", self.raw(1))[0]

    def u2(self):
        return struct.unpack(">H", self.raw(2))[0]

    def i2(self):
        return struct.unpack(">h", self.raw(2))[0]

    def i4(self):
        return struct.unpack(">i", self.raw(4))[0]

    def i8(self):
        return struct.unpack(">q", self.raw(8))[0]

    def f4(self):
        return struct.unpack(">f", self.raw(4))[0]

    def f8(self):
        return struct.unpack(">d", self.raw(8))[0]

    def string(self):
        # Minecraft writes modified UTF-8. surrogatepass+replace keeps odd bytes
        # from aborting a whole-region scan; we only ever read here, never write.
        n = self.u2()
        b = self.raw(n)
        try:
            return b.decode("utf-8")
        except UnicodeDecodeError:
            return b.decode("utf-8", "replace")


def read_payload(r, tag):
    if tag == TAG_BYTE:
        return r.i1()
    if tag == TAG_SHORT:
        return r.i2()
    if tag == TAG_INT:
        return r.i4()
    if tag == TAG_LONG:
        return r.i8()
    if tag == TAG_FLOAT:
        return r.f4()
    if tag == TAG_DOUBLE:
        return r.f8()
    if tag == TAG_BYTE_ARRAY:
        return r.raw(r.i4())
    if tag == TAG_STRING:
        return r.string()
    if tag == TAG_LIST:
        item_tag = r.u1()
        n = r.i4()
        if n <= 0:
            return []
        return [read_payload(r, item_tag) for _ in range(n)]
    if tag == TAG_COMPOUND:
        out = {}
        while True:
            t = r.u1()
            if t == TAG_END:
                return out
            name = r.string()
            out[name] = read_payload(r, t)
    if tag == TAG_INT_ARRAY:
        n = r.i4()
        return list(struct.unpack(">%di" % n, r.raw(4 * n)))
    if tag == TAG_LONG_ARRAY:
        n = r.i4()
        return list(struct.unpack(">%dq" % n, r.raw(8 * n)))
    raise ValueError("unknown NBT tag %d" % tag)


def parse_nbt(data):
    r = Reader(data)
    tag = r.u1()
    if tag == TAG_END:
        return {}
    r.string()  # root name, always empty in practice
    return read_payload(r, tag)


def iter_region(path):
    """Yield (chunk_x, chunk_z, root_compound) for every populated chunk."""
    with open(path, "rb") as fh:
        header = fh.read(4096)
        if len(header) < 4096:
            return
        for idx in range(1024):
            off_hi, off_lo, sectors = (
                struct.unpack(">H", header[idx * 4:idx * 4 + 2])[0],
                header[idx * 4 + 2],
                header[idx * 4 + 3],
            )
            offset = (off_hi << 8) | off_lo
            if offset == 0 or sectors == 0:
                continue
            fh.seek(offset * 4096)
            head = fh.read(5)
            if len(head) < 5:
                continue
            length, comp = struct.unpack(">IB", head)
            payload = fh.read(length - 1)
            try:
                if comp == 1:
                    raw = gzip.decompress(payload)
                elif comp == 2:
                    raw = zlib.decompress(payload)
                elif comp == 3:
                    raw = payload
                else:
                    continue
                root = parse_nbt(raw)
            except Exception:
                continue
            yield idx % 32, idx // 32, root


def tile_entities(root):
    level = root.get("Level") or {}
    tes = level.get("TileEntities")
    return tes if isinstance(tes, list) else []
