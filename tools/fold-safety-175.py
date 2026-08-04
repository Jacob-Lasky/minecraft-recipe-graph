"""Can a reader of schema N parse a dump carrying a field it has never heard of?

That is the question which decides whether a new dump field FOLDS INTO an existing schema number
or has to mint the next one, and it is not the question people reach for first. "Does an artifact
of that schema exist" is about history and does not answer it. This does: extract the reader at a
given revision, build two dumps differing only in the new field, and compare the graphs.

    python3 tools/fold-safety-175.py                    # against origin/wt/dump-declares
    python3 tools/fold-safety-175.py origin/master      # against whatever master is

Verdict for #175's per-stack `p`: additive and ignorable, so the fold into schema 6 is safe.

EXTRACTED WITH `git archive` RATHER THAN READ FROM A PATH. The first version pointed at a
directory somebody had already unpacked by hand, which meant it ran for exactly one person on one
machine. This way the reference is reproducible and the question ("does THAT revision's reader
cope") is explicit in the invocation.

FOUR SHAPES, BECAUSE THEY ARE FOUR DIFFERENT PARSE RISKS AND ONE RESULT DOES NOT IMPLY THE NEXT.
Established by `barekey` while checking #170's candidate field against the same reference: a reader
that skips an unknown SCALAR can still trip on an unknown LIST, and a reader coping with both may
still enumerate the dump directory and choke on an unknown FILE. Adding a shape here is cheaper
than a second harness, and a near-copy of this file is the duplication this repository keeps
paying for.

AND A CONTROL THAT MUST DIFFER, WHICH IS WHAT MAKES THE OTHER RESULTS MEAN ANYTHING. `barekey`'s
idea, and it closes a real hole. This script proved the field reached the file and that the graph
held real content, but nothing proved the COMPARISON would notice a field that mattered. A reader
that ignored the entire stack would have reported "byte-identical" for every shape, and the verdict
would have been an absence dressed as a measurement. The `control` shape sets `"c"`, a field every
reader consumes, and the run FAILS if that does not move the graph. The earlier discrimination
check was a manual run against a branch whose reader understood `p`: it worked, but it cannot be
repeated for a field no branch has read yet.

`instance_dir` is normalised out because it records the directory the dump was read from, so two
temp directories differ there by construction. Leaving it in is how an earlier version reported
"mint schema 7" over a perfectly additive field. Filed as #226, because
`make-java-fixtures.py:graph_identity` hashes the whole file and has the same problem.

ONE HONEST LIMIT. The fixture writes no `items.csv`, so `names` is 0 and this exercises the RECIPE
parse only. A field on the names path needs its own check.
"""
import difflib
import json
import os
import shutil
import subprocess
import sys
import tempfile

REFERENCE = sys.argv[1] if len(sys.argv) > 1 else "origin/wt/dump-declares"

_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_work = tempfile.mkdtemp(prefix="fold-safety-")
subprocess.check_call("git archive %s recipegraph | tar -x -C %s" % (REFERENCE, _work),
                      shell=True, cwd=_root)
sys.path.insert(0, _work)

from recipegraph import index                      # noqa: E402
from recipegraph.sources import dump_meta          # noqa: E402

VOLATILE = ("instance_dir",)

# shape -> (field added to every input stack, sidecar files, must the graph DIFFER?)
#
# `control` is not a shape under test. It is the proof that "no difference" from the others is a
# measurement rather than a dead comparison. `c` is the stack count, which every reader consumes.
SHAPES = {
    "scalar-p": ({"p": 0.0}, {}, False),
    "scalar":   ({"s": 1}, {}, False),
    "list":     ({"acc": ["fd1adc426e12", "0a19cc3b7741"]}, {}, False),
    "sidecar":  ({}, {"nbt_sensitivity.json": {"mod:a": True}}, False),
    "control":  ({"c": 7}, {}, True),
}
ORDER = ("scalar-p", "scalar", "list", "sidecar", "control")


def write_dump(root, shape):
    """A three-recipe dump, with `shape`'s field on every input stack."""
    add, sidecars, _ = SHAPES[shape] if shape else ({}, {}, False)
    d = os.path.join(root, dump_meta.DIR_NAME)
    os.makedirs(d, exist_ok=True)

    def stack(key, count):
        out = {"i": key, "m": 0, "c": count}
        out.update(add)
        return out

    lines = [
        # The permanent catalyst from ModularSmeltery.zs:12, beside the input it sits with.
        {"cat": "modularmachinery.recipes.forge_of_the_wyvern",
         "title": "Forge of the Wyverns",
         "in": [[stack("draconicevolution:chaos_shard", 1)],
                [stack("contenttweaker:fractallite_taint", 1)]],
         "out": [{"i": "mod:widget", "m": 0, "c": 1}]},
        {"cat": "mod.tiered", "title": "Tiered",
         "in": [[stack("contenttweaker:fractallite_taint", 1)]],
         "out": [{"i": "mod:tiered_out", "m": 0, "c": 1}]},
        # A two-stack slot, so a multi-alternative slot is exercised rather than assumed.
        {"cat": "mod.press", "title": "Press",
         "in": [[stack("mod:a", 2), stack("mod:b", 2)]],
         "out": [{"i": "mod:pressed", "m": 0, "c": 1}]},
    ]
    with open(os.path.join(d, "recipes.ndjson"), "w") as fh:
        for line in lines:
            fh.write(json.dumps(line) + "\n")
    with open(os.path.join(d, "oredict.json"), "w") as fh:
        json.dump({"plateStuff": ["mod:a", "mod:b"]}, fh)
    with open(os.path.join(d, "summary.json"), "w") as fh:
        json.dump({"schema": 6, "mod_version": "0.11.0"}, fh)
    for name, body in sidecars.items():
        with open(os.path.join(d, name), "w") as fh:
            json.dump(body, fh)
    return os.path.join(d, "recipes.ndjson")


def build(shape):
    """The graph a dump carrying `shape` produces, with volatile provenance removed."""
    root = tempfile.mkdtemp()
    try:
        ndjson = write_dump(root, shape)
        if shape:
            # The control on the INPUT. Without it, "the reader coped" could mean the field never
            # reached the file, which is a pass for the wrong reason.
            add, sidecars, _ = SHAPES[shape]
            raw = open(ndjson).read()
            for name in add:
                assert '"%s"' % name in raw, \
                    "the %s fixture did not carry %s as intended" % (shape, name)
            for name in sidecars:
                assert os.path.exists(os.path.join(os.path.dirname(ndjson), name)), \
                    "the %s fixture did not write %s" % (shape, name)
        doc = index.build(root, quiet=True).to_json()
        for field in VOLATILE:
            doc.pop(field, None)
        return doc
    finally:
        shutil.rmtree(root, ignore_errors=True)


def report_diff(plain, got):
    for key in sorted(set(plain) | set(got)):
        if plain.get(key) != got.get(key):
            left = json.dumps(plain.get(key), sort_keys=True, indent=1).split("\n")
            right = json.dumps(got.get(key), sort_keys=True, indent=1).split("\n")
            print("           === %s ===" % key)
            for line in list(difflib.unified_diff(left, right, "plain", "with-field",
                                                  lineterm=""))[:12]:
                print("           " + line)


print("reader under test: %s (must have no knowledge of the fields below)" % REFERENCE)
try:
    plain = build(None)
except Exception as exc:                      # noqa: BLE001
    print("!! the reader FAILED on a dump with no new field at all, so this harness is broken:")
    print("   %r" % (exc,))
    raise SystemExit(2)

# Content control before any verdict: a reader that silently produced nothing would compare
# "identical" for every shape.
recipes = plain.get("recipes") or []
print("control: the graph holds %d recipes, so the comparison runs on real content" % len(recipes))
if len(recipes) != 3:
    print("!! expected 3 recipes; this harness is not exercising what it claims")
    raise SystemExit(2)

bad = False
for shape in ORDER:
    must_differ = SHAPES[shape][2]
    try:
        got = build(shape)
    except AssertionError as broken:
        # THE HARNESS, NOT THE READER, and the difference decides what someone does next. These
        # assertions are the input controls: the field never reached the fixture. Reporting that
        # as "the reader cannot parse this" would send the next person to mint a schema number
        # over a bug in this file.
        print("  %-9s HARNESS BROKEN: %s" % (shape, broken))
        print("           -> the fixture did not carry what it claims, so this shape was never "
              "put to the reader. Fix this script; the verdict below means nothing.")
        bad = True
        continue
    except Exception as exc:                  # noqa: BLE001
        print("  %-9s READER RAISED %r" % (shape, exc))
        print("           -> a reader of %s cannot parse this shape, so it needs a NEW schema "
              "number rather than a fold" % REFERENCE)
        bad = True
        continue
    identical = json.dumps(got, sort_keys=True) == json.dumps(plain, sort_keys=True)
    if must_differ:
        print("  %-9s parsed, graph byte-identical: %s   (expected to DIFFER)"
              % (shape, identical))
        if identical:
            print("           !! THE COMPARISON IS DEAD. `c` is a field the reader consumes, so "
                  "changing it MUST move the graph. Every result above is an absence rather than "
                  "a measurement and this run proves nothing.")
            bad = True
        else:
            print("           the comparison is live, so the results above are measurements")
    else:
        print("  %-9s parsed, graph byte-identical: %s" % (shape, identical))
        if not identical:
            print("           -> NOT additive: the field changes what the reader builds")
            report_diff(plain, got)
            bad = True

print("== fold UNSAFE ==" if bad else "== fold SAFE: every shape is additive and ignorable ==")
raise SystemExit(1 if bad else 0)
