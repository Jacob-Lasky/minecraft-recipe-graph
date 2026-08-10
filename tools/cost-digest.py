#!/usr/bin/env python3
"""What a change does to EVERY price in the cost table, and whether the check could fail.

    python3 tools/cost-digest.py                          # working tree vs origin/master
    python3 tools/cost-digest.py --base HEAD~1
    python3 tools/cost-digest.py --graph /path/graph.json
    python3 tools/cost-digest.py --show 40                # more of the per-key diff
    python3 tools/cost-digest.py --any-graph              # opt out of the oracle guard

WHY THIS IS CHECKED IN, AND IT IS NOT `cost-probe.py`. That tool answers "which ROUTE wins"
for eighteen hand-picked items, which is the question a tuning change asks. This one answers
"which of the 162,000 PRICES moved", which is the question `FORMULA_VERSION` asks, and the
two are not substitutes: #270 moved exactly one key and no probe route at all, so the sweep
tool would have reported a clean bill for a change that had to bump the counter.

`cost.FORMULA_VERSION`'s own comments require this measurement of every entry -- "the bump
rests on the measured price movement" -- and until now the METHOD lived only in the prose of
those entries. Version 19 and version 20 each describe two arms, separate processes, separate
source trees and a positive control, in paragraphs; the next bump would have reimplemented it
from a description. A method that has to be re-derived from a comment is one that will be
re-derived slightly differently.

WHAT MAKES IT CORRECT RATHER THAN MERELY CONVENIENT, and both halves are load-bearing:

  * SEPARATE SOURCE TREES, exported with `git archive`, NOT one tree edited between runs.
    `/coding` is a FUSE shfs mount with one-second mtime granularity, so an edit landing
    within the same second as the previous one AND leaving the file the same length is
    invisible to Python's bytecode cache: the stale `.pyc` is served and the arm silently
    measures the arm before it. `cost-probe.py`'s module docstring records that happening.
    Two directories that never share a `__pycache__` cannot have the problem at all, which
    is better than remembering to clear one.

  * A POSITIVE CONTROL THAT RUNS EVERY TIME, not one a careful person remembers. A digest
    comparison reporting "identical" is worthless until the comparison has been made to fail
    on purpose, and a comparison reporting "different" is worth less than it looks if the
    two arms differ for a reason nobody checked. So a THIRD arm always runs: the base tree
    with `BASE_RAW_COST` nudged by 1e-4. If that does not move the digest, the instrument is
    broken and this tool REFUSES TO REPORT rather than printing a verdict it cannot support.

THE DIGEST IS `make-java-fixtures.cost_digest` AND NOT A SECOND SPELLING. That function and
its `%.17e` formatting exist because `repr` disagrees as text between Python and Java on 33%
of doubles while differing in value on none, and because `%.12e` is not injective over
binary64 so a one-ULP drift hashed the same. Re-implementing either here would be a second
thing to be wrong. It is applied by THIS process to both arms, so the digest function is held
constant and only the priced table varies -- an arm computing its own digest would let a
change to `fmt_cost` masquerade as a change in prices.

ITS DIGESTS DO NOT MATCH THE ONES WRITTEN INTO THE VERSION-19 AND VERSION-20 ENTRIES, and
that is expected rather than a regression. Both of those measurements predate this tool and
were taken with ad-hoc scripts that hashed `repr(value)`; this uses the canonical `%.17e`
spelling, so every digest string differs while every VERDICT -- which key moved, by how much,
in which direction -- is identical. Do not "reconcile" the two sets of hex by changing the
format here: `repr` is the one that cannot be compared against Java, which is why
`cost_digest` exists in the shape it does.

SO CHECK THE TOOL AGAINST A KNOWN ANSWER RATHER THAN AGAINST THOSE STRINGS. #270 is the
worked example, and re-running it is the cheapest way to confirm this still measures what it
claims:

    python3 tools/cost-digest.py --base 7feea7b^ --graph <the fixtures' oracle>

        moved 1     UP 0     DOWN 1     appeared 0     vanished 0
        contenttweaker:sub_block_holder_1:8    2000.0 -> 806.0

which is exactly what `FORMULA_VERSION = 20` records, arrived at independently.

MEASURE ON THE GRAPH THE FIXTURES CAME FROM. `tools/check.sh` resolves that graph and this
refuses one that disagrees with it, for a reason paid for during #270: the first pass of that
measurement ran against `graph-oracle-248.json` because #248 was the change underneath it,
and `make-java-fixtures.py --check` on UNMODIFIED master against that oracle reports 24 of 24
fixtures differing, because every fixture embeds its oracle's sha256. The verdict happened not
to change when it was re-taken on the right graph; that it did not change was luck until it
was re-run. `--any-graph` is the deliberate override and says so in the output.

A NOTE ON THE COST OF VERIFYING THE BUMP AFTERWARDS, measured 2026-08-10 and recorded here
because this is where somebody stands when they are about to pay it. A `FORMULA_VERSION` bump
is followed by `tools/check.sh`, and running that with your own `GRADLE_CACHE` -- which you
should, since siblings share the default -- is NOT free even when you seed it: `cp -a` of
`/coding/.recipegraph-build/gradle-cache` still triggers a full RFG decompile on first use,
about ten minutes, because the cache holds absolute paths. Budget it, or accept the shared
cache and the queueing that comes with it.

Dev tooling, stdlib only, alongside the other audits.
"""

import argparse
import hashlib
import importlib.util
import json
import math
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# The perturbation the built-in control applies. Small enough that it cannot be mistaken for
# a real retune and large enough to be far above any rounding: `%.17e` resolves one ULP, so
# 1e-4 relative is roughly twelve orders of margin.
CONTROL_NUDGE = 1.0001


def _load_fixture_tools():
    """`make-java-fixtures` as a module, for its digest and nothing else.

    THROUGH `importlib` BECAUSE `tools/` IS NOT A PACKAGE and the filename is hyphenated,
    which is the same idiom `tests/test_plan_fixtures.py` uses to reach the same file. The
    alternative is a second copy of `cost_digest`, which is the one thing this must not do.
    """
    path = os.path.join(ROOT, "tools", "make-java-fixtures.py")
    spec = importlib.util.spec_from_file_location("make_java_fixtures", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


# ---------------------------------------------------------------------------
# The worker: one arm, in its own process, against its own source tree
# ---------------------------------------------------------------------------

def _price_one_arm(root, graph_path, out_path, nudge):
    """Compute every price under `root`'s code and write `{key: cost}` to `out_path`.

    RUNS IN A CHILD PROCESS with `root` first on `sys.path`, so `recipegraph` resolves to
    that arm's copy. It is deliberately the CURRENT tree's copy of this function driving an
    OLD tree's library: the calls below -- load, describe, build_targets, resolve, estimate --
    are the stable public surface, and a base old enough to have moved them is a base this
    tool cannot compare against, which is worth an import error rather than a wrong number.
    """
    sys.path.insert(0, root)
    from recipegraph import cost as cost_mod
    from recipegraph import machines as machines_mod
    from recipegraph import tokens as tokens_mod
    from recipegraph.model import Graph

    graph = Graph.load(graph_path)
    info = machines_mod.describe(graph, {}, {})
    states = {uid: (item["state"], item["why"]) for uid, item in info.items()}
    machine_items = machines_mod.build_targets(info)
    token_kinds = tokens_mod.resolve(graph=graph)
    # EVERY DIMENSION UNVISITED, which is the only arm in which a gate is charged at all and
    # is the state the reference save is actually in. Resolving gates against a real have-file
    # would make the measurement depend on an inventory that is not in the repository, so two
    # people would get two answers for one change.
    gates = {key: name for key, (_dim, name) in (graph.dimension_ores or {}).items()}

    if nudge is not None:
        cost_mod.BASE_RAW_COST = nudge

    costs = cost_mod.estimate(graph, machine_states=states, machine_items=machine_items,
                              token_kinds=token_kinds, dimension_gates=gates)
    # `repr` round-trips a binary64 exactly and JSON preserves it, so the parent hashes the
    # same doubles this arm computed. The DIGEST format is the parent's business; this is
    # only the wire between two processes.
    with open(out_path, "w") as handle:
        json.dump({key: value for key, value in costs.items()}, handle)


# ---------------------------------------------------------------------------
# The parent: three arms, a diff, and a refusal
# ---------------------------------------------------------------------------

def export(ref, into):
    """`ref`'s `recipegraph/` at its own path, so no two arms share a `__pycache__`."""
    os.makedirs(into)
    archive = subprocess.Popen(["git", "-C", ROOT, "archive", ref, "recipegraph"],
                               stdout=subprocess.PIPE)
    untar = subprocess.Popen(["tar", "-x", "-C", into], stdin=archive.stdout)
    archive.stdout.close()
    untar.communicate()
    if untar.returncode != 0 or not os.path.isdir(os.path.join(into, "recipegraph")):
        raise SystemExit("could not export %s -- is it a valid ref?" % ref)
    return into


def run_arm(label, root, graph_path, workdir, nudge=None):
    """One arm, in a child process. Returns `{key: cost}`."""
    out = os.path.join(workdir, "%s.json" % label)
    argv = [sys.executable, os.path.abspath(__file__), "--_worker", root, graph_path, out]
    if nudge is not None:
        argv.append(repr(nudge))
    done = subprocess.run(argv, cwd=workdir)
    if done.returncode != 0:
        raise SystemExit("arm %r failed under %s" % (label, root))
    with open(out) as handle:
        return json.load(handle)


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def fixture_oracle_sha():
    """The sha256 every plan fixture names, or None when they disagree or are absent.

    None ON DISAGREEMENT RATHER THAN A GUESS. Fixtures generated against two graphs is a
    condition `tests/test_plan_fixtures.py` already fails on; this tool's job is to notice it
    and get out of the way, not to pick a winner.
    """
    seen = set()
    plan_dir = os.path.join(ROOT, "tests", "fixtures", "plan")
    try:
        names = sorted(os.listdir(plan_dir))
    except OSError:
        return None
    for name in names:
        if not name.endswith(".json"):
            continue
        with open(os.path.join(plan_dir, name)) as handle:
            doc = json.load(handle)
        sha = (doc.get("graph") or {}).get("sha256")
        if sha:
            seen.add(sha)
    return seen.pop() if len(seen) == 1 else None


def compare(before, after):
    """`(moved, up, down, appeared, vanished)` as key lists."""
    keys = set(before) | set(after)
    moved = [k for k in keys if before.get(k) != after.get(k)]
    both = [k for k in moved if k in before and k in after]
    return (sorted(moved),
            sorted(k for k in both if after[k] > before[k]),
            sorted(k for k in both if after[k] < before[k]),
            sorted(set(after) - set(before)),
            sorted(set(before) - set(after)))


def finite(costs):
    return {k: v for k, v in costs.items()
            if not math.isinf(v) and not math.isnan(v)}


def main():
    parser = argparse.ArgumentParser(
        description="Every price, two arms, and a control that runs whether or not you "
                    "remember it.")
    parser.add_argument("--base", default="origin/master",
                        help="the ref to compare the working tree against")
    parser.add_argument("--graph", default=os.environ.get("RECIPEGRAPH_ORACLE"),
                        help="the oracle graph; defaults to $RECIPEGRAPH_ORACLE")
    parser.add_argument("--show", type=int, default=20,
                        help="how many moved keys to print")
    parser.add_argument("--any-graph", action="store_true",
                        help="measure on a graph the fixtures did not come from")
    parser.add_argument("--_worker", nargs=3, help=argparse.SUPPRESS)
    args, extra = parser.parse_known_args()

    if args._worker:
        root, graph_path, out = args._worker
        nudge = float(extra[0]) if extra else None
        return _price_one_arm(root, graph_path, out, nudge)

    if not args.graph or not os.path.exists(args.graph):
        raise SystemExit("no oracle graph: pass --graph or set $RECIPEGRAPH_ORACLE")

    wanted = fixture_oracle_sha()
    actual = sha256_of(args.graph)
    if wanted and actual != wanted and not args.any_graph:
        raise SystemExit(
            "REFUSING: %s is not the graph the fixtures came from.\n"
            "  fixtures name %s\n  this graph is %s\n"
            "A FORMULA_VERSION measurement taken on one graph and regenerated against\n"
            "another is #281. Point --graph at the fixtures' oracle, or pass --any-graph\n"
            "if you mean it." % (args.graph, wanted, actual))

    tools = _load_fixture_tools()
    workdir = tempfile.mkdtemp(prefix="cost-digest-")
    try:
        base_root = export(args.base, os.path.join(workdir, "base"))
        print("graph:   %s" % args.graph)
        print("         sha256 %s%s" % (actual, "" if wanted == actual else "  (NOT the "
                                        "fixtures' oracle; --any-graph was given)"))
        print("base:    %s  ->  %s" % (args.base, base_root))
        print("working: %s" % ROOT)
        print()

        before = run_arm("base", base_root, args.graph, workdir)
        after = run_arm("working", ROOT, args.graph, workdir)
        control = run_arm("control", base_root, args.graph, workdir, nudge=CONTROL_NUDGE)

        base_digest = tools.cost_digest(before)
        work_digest = tools.cost_digest(after)
        control_digest = tools.cost_digest(control)

        print("digest, base     %s" % base_digest)
        print("digest, working  %s" % work_digest)
        print("digest, control  %s   (base with BASE_RAW_COST = %s)"
              % (control_digest, CONTROL_NUDGE))
        print()

        # THE REFUSAL, AND IT COMES BEFORE THE VERDICT. If nudging every raw leaf does not
        # move the digest, then this instrument cannot see a price change and neither the
        # "identical" nor the "differs" reading above means anything.
        if control_digest == base_digest:
            raise SystemExit(
                "REFUSING: the control did not move the digest.\n"
                "Perturbing BASE_RAW_COST must change the table; that it did not means the\n"
                "comparison is incapable of failing, so no verdict here is worth reading.")
        print("control moved the digest, so the comparison can fail. Verdict:")
        print()

        moved, up, down, appeared, vanished = compare(finite(before), finite(after))
        print("  priced keys  %d -> %d" % (len(before), len(after)))
        print("  finite keys  %d -> %d" % (len(finite(before)), len(finite(after))))
        print("  moved %d     UP %d     DOWN %d     appeared %d     vanished %d"
              % (len(moved), len(up), len(down), len(appeared), len(vanished)))
        print()
        if base_digest == work_digest:
            print("  THE TABLE DID NOT MOVE. If a derived table is unchanged while the")
            print("  hashed inputs are too, FORMULA_VERSION need not move -- but read")
            print("  `cost.fingerprint` before concluding that.")
        else:
            print("  THE TABLE MOVED, so FORMULA_VERSION MUST be bumped: nothing else")
            print("  invalidates a warm .cost-cache.json when no hashed input changed.")
        print()
        shown = sorted(moved, key=lambda k: abs((after.get(k) or 0) - (before.get(k) or 0)),
                       reverse=True)[:args.show]
        for key in shown:
            print("    %-52s %r -> %r" % (key, before.get(key), after.get(key)))
        if len(moved) > len(shown):
            print("    ... %d more (--show)" % (len(moved) - len(shown)))
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


if __name__ == "__main__":
    main()
