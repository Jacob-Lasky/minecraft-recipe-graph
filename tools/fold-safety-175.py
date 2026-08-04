"""Does a schema-6 reader that has never heard of `p` survive a dump that carries it?

This is the question that decides whether the consumption field folds into schema 6 or has to
mint 7. `declares` argued the fold is safe because no schema-6 dump exists, which is true and is
about ARTIFACTS. It does not answer the compatibility question, which is about READERS: if
emitting `p` changed `recipes.ndjson` in a way a schema-6 reader cannot skip past, the fold would
hand that reader a file it misparses while both sides agree the schema is 6.

Reader under test: a git rev, defaulting to `origin/wt/dump-declares`, which IS schema 6
and has no knowledge of `p`. Pass another rev as argv[1].
Two dumps, identical except that one carries `p` on every input stack, built through the real
`index.build`.

`instance_dir` is normalised out because it records the directory the dump was read from, so two
temp directories differ there by construction. The first version of this script did not, reported
"the graph DIFFERS, mint schema 7", and was wrong: the only differing field was that path. The
guard was broken, not the thing under test, which is why the diff is printed rather than a
verdict being taken on a boolean.
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile

# THE REFERENCE TO TEST AGAINST, as a git rev. Default is the schema the field is being folded
# into; pass another to check a different one:
#
#   python3 tools/fold-safety-175.py                       # against origin/wt/dump-declares
#   python3 tools/fold-safety-175.py origin/master         # against whatever master is
#
# EXTRACTED HERE RATHER THAN READ FROM A PATH. The first version of this script pointed at a
# directory somebody had already unpacked by hand, which meant it ran for exactly one person on
# exactly one machine. `git archive` makes the reference reproducible and makes the question the
# script answers ("does THAT revision's reader cope") explicit in the invocation.
REFERENCE = sys.argv[1] if len(sys.argv) > 1 else "origin/wt/dump-declares"

_work = tempfile.mkdtemp(prefix="fold-safety-")
subprocess.check_call("git archive %s recipegraph | tar -x -C %s"
                      % (REFERENCE, _work), shell=True,
                      cwd=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, _work)

from recipegraph import index                      # noqa: E402
from recipegraph.sources import dump_meta          # noqa: E402

# Volatile because it is the path the dump was read from, not a property of the dump.
VOLATILE = ("instance_dir",)


def write_dump(root, with_p):
    d = os.path.join(root, dump_meta.DIR_NAME)
    os.makedirs(d, exist_ok=True)
    shard = {"i": "draconicevolution:chaos_shard", "m": 0, "c": 1}
    material = {"i": "contenttweaker:fractallite_taint", "m": 0, "c": 1}
    if with_p:
        shard["p"] = 0.0        # the permanent catalyst from ModularSmeltery.zs:12
        material["p"] = 1.0     # explicitly consumed, the redundant spelling
    lines = [
        {"cat": "modularmachinery.recipes.forge_of_the_wyvern",
         "title": "Forge of the Wyverns",
         "in": [[shard], [material]],
         "out": [{"i": "mod:widget", "m": 0, "c": 1}]},
        # A fractional value too, the bottom rung of the Trinitas ladder.
        {"cat": "mod.tiered", "title": "Tiered",
         "in": [[dict(material, **({"p": 0.001} if with_p else {}))]],
         "out": [{"i": "mod:tiered_out", "m": 0, "c": 1}]},
        # And an oredict slot of two stacks, so a multi-alternative slot is exercised.
        {"cat": "mod.press", "title": "Press",
         "in": [[dict({"i": "mod:a", "m": 0, "c": 2}, **({"p": 0.0} if with_p else {})),
                 dict({"i": "mod:b", "m": 0, "c": 2}, **({"p": 0.0} if with_p else {}))]],
         "out": [{"i": "mod:pressed", "m": 0, "c": 1}]},
    ]
    with open(os.path.join(d, "recipes.ndjson"), "w") as fh:
        for line in lines:
            fh.write(json.dumps(line) + "\n")
    with open(os.path.join(d, "oredict.json"), "w") as fh:
        json.dump({"plateStuff": ["mod:a", "mod:b"]}, fh)
    with open(os.path.join(d, "summary.json"), "w") as fh:
        json.dump({"schema": 6, "mod_version": "0.11.0"}, fh)
    return os.path.join(d, "recipes.ndjson")


def build(with_p):
    root = tempfile.mkdtemp()
    try:
        ndjson = write_dump(root, with_p)
        raw = open(ndjson).read()
        # The control on the INPUT: if `p` never reached the file, "the reader coped" is vacuous.
        assert ('"p"' in raw) == with_p, "the fixture did not carry `p` as intended"
        doc = index.build(root, quiet=True).to_json()
        for field in VOLATILE:
            doc.pop(field, None)
        return doc
    finally:
        shutil.rmtree(root, ignore_errors=True)


print("reader under test: %s (must have no knowledge of `p`)" % REFERENCE)
try:
    without = build(False)
except Exception as exc:                      # noqa: BLE001
    print("!! the reader FAILED on a dump with no `p` at all, so this harness is broken: %r"
          % (exc,))
    raise SystemExit(2)

try:
    with_field = build(True)
except Exception as exc:                      # noqa: BLE001
    print("!! THE FOLD IS UNSAFE: the schema-6 reader raised on a dump carrying `p`")
    print("   %r" % (exc,))
    print("   -> mint schema 7 rather than folding into 6")
    raise SystemExit(1)

print("the schema-6 reader parsed a dump carrying `p` without raising")

# Control before the verdict: a reader that silently produced nothing would compare "identical".
recipes = without.get("recipes") or []
names = without.get("names") or {}
print("control: the graph holds %d recipes and %d names, so the comparison ran on real content"
      % (len(recipes), len(names)))
if len(recipes) != 3:
    print("!! expected 3 recipes; the harness is not exercising what it claims")
    raise SystemExit(2)

if json.dumps(without, sort_keys=True) == json.dumps(with_field, sort_keys=True):
    print("AND the built graph is byte-identical with and without the field")
    print("   -> `p` is additive and ignorable to a schema-6 reader; the fold is SAFE")
else:
    import difflib
    print("!! the built graph DIFFERS, so `p` is not ignorable after all -> mint schema 7")
    for key in sorted(set(without) | set(with_field)):
        if without.get(key) != with_field.get(key):
            a = json.dumps(without.get(key), sort_keys=True, indent=1).split("\n")
            b = json.dumps(with_field.get(key), sort_keys=True, indent=1).split("\n")
            print("=== %s ===" % key)
            for line in list(difflib.unified_diff(a, b, "without_p", "with_p", lineterm=""))[:20]:
                print(line)
    raise SystemExit(1)
