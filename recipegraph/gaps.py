"""Coverage gap analysis over the dump mod's skipped.ndjson and summary.json.

The point is to answer "what is my graph blind to, and does it matter" rather than
just "N recipes were skipped".

The most important signal is a category with `dumped == 0`: that is a total blind
spot, an entire machine type absent from the graph, and any item made only there will
look uncraftable. A category that dumped 900 of 1000 recipes is a very different
situation and should not be ranked alongside it.

`stock_coverage` answers the same question from the other end: not what the dump missed,
but what your AE2 network holds that the graph cannot see. Both are silences, and a
silence is the one kind of failure a tool has to be told to report.
"""

import json
import os

from .ae2_inventory import DIGEST_READER
from .model import is_digest, split_discriminator

# Why a scanned stock key matches nothing in the graph. The cause decides the fix, which
# is the whole reason they are counted apart rather than as one "unmatched" number.
CAUSE_OPAQUE = "nbt the dump does not digest"
CAUSE_STALE = "scanned before the digest port; rescan the save"
CAUSE_UNKNOWN_VARIANT = "digest not in this dump"
CAUSE_UNKNOWN = "item not in this dump"


def load(dump_dir):
    summary_path = os.path.join(dump_dir, "summary.json")
    skipped_path = os.path.join(dump_dir, "skipped.ndjson")

    summary = {}
    if os.path.exists(summary_path):
        with open(summary_path, encoding="utf-8", errors="replace") as fh:
            try:
                summary = json.load(fh)
            except ValueError:
                summary = {}

    skips = []
    if os.path.exists(skipped_path):
        with open(skipped_path, encoding="utf-8", errors="replace") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                try:
                    skips.append(json.loads(line))
                except ValueError:
                    continue
    return summary, skips


def _cause(key, reader):
    if key.endswith(" (+nbt)"):
        # Reader 1 wrote this marker for EVERY NBT-bearing stack, so on an old file it
        # says nothing about the mod and everything about the file. Blaming the dump
        # there would send someone chasing a schema bug that a rescan fixes.
        return CAUSE_OPAQUE if reader >= DIGEST_READER else CAUSE_STALE
    _stem, suffix = split_discriminator(key)
    if suffix is None:
        return CAUSE_UNKNOWN
    return CAUSE_UNKNOWN_VARIANT if is_digest(suffix) else CAUSE_STALE


def stock_coverage(graph, items, reader=1):
    """How much of a scanned AE2 stock this graph can see, and why the rest is invisible.

    A stock key the graph has never heard of contributes nothing to a plan: the item
    reads as zero and goes on the shopping list even though it is sitting in a drive.
    That was the whole of #21 -- 8,629 bee drones and 1,473,740 vis pods filed under keys
    no recipe uses -- and it went unnoticed because nothing counted it. Counting it here
    means the next such divergence shows up as a number rather than as a wrong plan.

    Matched means the key is in `graph.labels`, which is the set of things the graph can
    name at all. A key outside it cannot be an ingredient of anything.

    `reader` is the `ae2_inventory.READER` stamp from the have file, and it changes what
    an unmatched NBT key MEANS rather than merely how it is worded. Defaults to 1 because
    only the pre-digest reader wrote files without a stamp.
    """
    matched, unmatched, causes = 0, [], {}
    for key, count in items.items():
        if key in graph.labels:
            matched += 1
            continue
        unmatched.append((key, count))
        cause = _cause(key, reader)
        keys, stock = causes.get(cause, (0, 0))
        causes[cause] = (keys + 1, stock + count)
    unmatched.sort(key=lambda kv: (-kv[1], kv[0]))
    return {
        "keys": len(items),
        "matched": matched,
        "stock": sum(items.values()),
        "unmatched": unmatched,
        "unmatched_stock": sum(count for _key, count in unmatched),
        "causes": causes,
    }


def stock_report(cov, top=8):
    """One block for the `have` command: the headline, then why, then the worst offenders."""
    if not cov["keys"]:
        return "no stock to reconcile"
    out = ["%s of %s stock keys match nothing in the graph (%s of %s items)"
           % ("{:,}".format(cov["keys"] - cov["matched"]), "{:,}".format(cov["keys"]),
              "{:,}".format(cov["unmatched_stock"]), "{:,}".format(cov["stock"]))]
    if not cov["unmatched"]:
        return "every stock key matches a key in the graph"
    for cause, (keys, stock) in sorted(cov["causes"].items(),
                                       key=lambda kv: (-kv[1][1], kv[0])):
        out.append("  %5s keys  %14s items  %s"
                   % ("{:,}".format(keys), "{:,}".format(stock), cause))
    for key, count in cov["unmatched"][:top]:
        out.append("    %14s  %s" % ("{:,}".format(count), key))
    return "\n".join(out)


def analyse(summary, skips):
    cats = summary.get("categories") or {}

    blind, partial = [], []
    for uid, t in cats.items():
        dumped = t.get("dumped", 0)
        threw = t.get("threw", 0)
        empty = t.get("empty", 0)
        if dumped == 0 and (threw or empty):
            blind.append((uid, t.get("mod", ""), threw, empty))
        elif threw or empty:
            partial.append((uid, t.get("mod", ""), dumped, threw, empty))

    blind.sort(key=lambda r: -(r[2] + r[3]))
    # Rank partials by the share lost, not the raw count: 40 of 50 missing matters more
    # than 40 of 40,000.
    partial.sort(key=lambda r: -((r[3] + r[4]) / float(r[2] + r[3] + r[4] or 1)))

    errs, wrappers, reasons = {}, {}, {}
    for s in skips:
        if s.get("err"):
            errs[s["err"]] = errs.get(s["err"], 0) + 1
        if s.get("wrapper"):
            wrappers[s["wrapper"]] = wrappers.get(s["wrapper"], 0) + 1
        r = s.get("reason", "?")
        reasons[r] = reasons.get(r, 0) + 1

    return {
        "recipes": summary.get("recipes", 0),
        "skipped": summary.get("skipped", 0),
        "categories": len(cats),
        "blind_categories": blind,
        "partial_categories": partial[:20],
        "errors": sorted(errs.items(), key=lambda t: -t[1])[:12],
        "wrappers": sorted(wrappers.items(), key=lambda t: -t[1])[:12],
        "reasons": sorted(reasons.items(), key=lambda t: -t[1]),
    }


def report(a):
    out = []
    empty = dict(a["reasons"]).get("no outputs", 0)
    threw = a["skipped"]
    out.append("dumped %s recipes across %d categories"
               % ("{:,}".format(a["recipes"]), a["categories"]))
    out.append("  %s wrappers threw, %s entries produced nothing usable"
               % ("{:,}".format(threw), "{:,}".format(empty)))

    if a["reasons"]:
        out.append("\nwhy things were skipped")
        for reason, n in a["reasons"]:
            out.append("  %6s  %s" % ("{:,}".format(n), reason))

    out.append("\nTOTAL BLIND SPOTS (category dumped nothing at all)")
    if not a["blind_categories"]:
        out.append("  none -- every category contributed at least one recipe")
    for uid, mod, threw, empty in a["blind_categories"]:
        out.append("  %-44s %-18s threw=%d empty=%d"
                   % (uid[:44], ("[" + mod + "]") if mod else "", threw, empty))

    out.append("\npartially covered categories (worst share first)")
    if not a["partial_categories"]:
        out.append("  none")
    for uid, mod, dumped, threw, empty in a["partial_categories"]:
        total = dumped + threw + empty
        out.append("  %-40s %-16s %s/%s dumped (%.0f%% lost)"
                   % (uid[:40], ("[" + mod + "]") if mod else "",
                      "{:,}".format(dumped), "{:,}".format(total),
                      100.0 * (threw + empty) / (total or 1)))

    if a["errors"]:
        out.append("\nexception types")
        for err, n in a["errors"]:
            out.append("  %6s  %s" % ("{:,}".format(n), err))

    if a["wrappers"]:
        out.append("\nnoisiest recipe wrapper classes")
        for wr, n in a["wrappers"]:
            out.append("  %6s  %s" % ("{:,}".format(n), wr))

    return "\n".join(out)
