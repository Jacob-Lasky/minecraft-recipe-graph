#!/usr/bin/env python3
"""Which NBT tag makes the digest move between two dumps. Issue #80.

WHY THIS EXISTS. Two `/recipedump` runs against an unchanged pack produce different
discriminated keys for ~11,353 of 340,318 named items, concentrated in tconstruct (6,565)
and plustic (1,298). One item ends up wearing two keys, so a Tinkers tool sitting in the
ME system stops matching the recipe that consumes it. The cause could not be investigated
at all, because a dump records only the final digest and never the NBT behind it: there was
nothing on disk to recompute or compare.

`/recipedump` now also writes `nbt_trace.json`, a per-TOP-LEVEL-TAG digest of every
key with identifying NBT, in two flavours per tag:

    "o"   the tag as the real digest serialises it, lists IN ORDER
    "u"   the same tag with list order made irrelevant

This tool reads those.

    python3 tools/digest-churn.py <dump-dir>                  # suspects, ONE dump
    python3 tools/digest-churn.py <old-dump> <new-dump>        # the answer, TWO dumps
    python3 tools/digest-churn.py <old> <new> --mod tconstruct # narrow to one mod

TWO MODES, AND ONE OF THEM NEEDS ONLY ONE LAUNCH OF THE GAME. Churn is a between-JVM-run
effect, so OBSERVING it needs two dumps from two separate launches. But a single dump can
say which tags are even capable of it: a tag whose "o" and "u" disagree holds a
non-trivially-ordered list right now.

DO NOT TREAT THE ONE-DUMP MODE AS AN ANSWER. It is weak in both directions, measured: a list
already in sorted order reads as "cleared" and still churns (5,003 such keys), and the tag it
ranked first (`Traits`, 7,962) did not churn at all while `Special` churned on 10,010. Use it
to narrow, never to conclude.

THE KEY IS NOT THE JOIN COLUMN. The digest is IN the key (`tconstruct:hatchet:804#<digest>`),
so the key itself churns and cannot be used to pair an item across two dumps. Pairing is on
`(base key, display name)` from names.json, which is why both files are written from the same
place. #80 measured that 11,328 of the 11,353 churned keys carry an IDENTICAL display name on
both sides, which is what makes that join work.

Dev tooling, alongside the other audits. Python 3 stdlib only.
"""

import argparse
import collections
import json
import os
import sys

TRACE_FILE = "nbt_trace.json"
# The arg that SUPPRESSES the trace, mirrored from DumpCommand.NO_TRACE_ARG and pinned to it
# by tests/test_digest_churn.TheJavaSourceContract. Named here so the error message above
# cannot tell a player to use a flag the mod does not accept.
NO_TRACE_ARG = "notrace"
NAMES_FILE = "names.json"


def base_key(key):
    """`tconstruct:hatchet:804#19d716f2142c` -> `tconstruct:hatchet:804`.

    Strips the digest and NOTHING else. It must not touch the meta: `tconstruct:ingots:0`
    and `:3` are different items, and merging them is the mistake `model.base_key`'s
    docstring records for the graph side.
    """
    return key.split("#", 1)[0]


def mod_of(key):
    """The registry namespace, which is the aggregation axis #80 reports churn along."""
    return base_key(key).split(":", 1)[0]


def load_dump(path):
    """Read one dump directory -> {key: {tag: {"o":..., "u":...}}}, {key: name}.

    Accepts the dump DIRECTORY, or either file inside it, because the natural thing to
    paste is whatever path is already on the clipboard.
    """
    if os.path.isdir(path):
        trace_path = os.path.join(path, TRACE_FILE)
        names_path = os.path.join(path, NAMES_FILE)
    else:
        trace_path = path
        names_path = os.path.join(os.path.dirname(path), NAMES_FILE)
    if not os.path.exists(trace_path):
        raise SystemExit(
            "no %s in %s.\nEvery dump from mod v0.7.0 writes it unless `%s` was passed, "
            "and a pre-0.6.0 jar cannot write it at all. It cannot be reconstructed after "
            "the fact -- the NBT it describes only exists in a running JVM -- so this needs "
            "another `/recipedump`." % (TRACE_FILE, path, NO_TRACE_ARG))
    with open(trace_path) as fh:
        trace = json.load(fh)
    names = {}
    if os.path.exists(names_path):
        with open(names_path) as fh:
            names = json.load(fh)
    return trace, names


def suspects(trace, mod=None):
    """Tags capable of churning from list order: those whose "o" and "u" disagree.

    Returns {tag name: {"flagged": n, "cleared": n, "mods": Counter}}.

    "AGREE" IS NOT INNOCENCE, and an earlier version of this docstring said it was. `o == u`
    means only that THIS dump's copy of the list was already in canonical order -- a list can
    be sorted by luck in one dump and permuted in the next. Measured on the reference pack:
    `Special` read as flagged on 5,007 keys and CLEARED on 5,163, and the two-dump comparison
    then found it churning on 10,010, so at least 5,003 genuinely-churning keys were sitting
    in the "cleared" column.

    It is weak in the other direction too. The one-dump list ranked `Traits` top at 7,962
    flagged, and `Traits` turned out not to churn at all.

    So read this as "could this MECHANISM apply to this tag", never as a ranking of likely
    culprits and never as a clearance. Only the two-dump mode answers what actually moved.
    """
    out = collections.defaultdict(
        lambda: {"flagged": 0, "cleared": 0, "mods": collections.Counter()})
    for key, tags in trace.items():
        if mod and mod_of(key) != mod:
            continue
        for tag, digests in tags.items():
            row = out[tag]
            if digests.get("o") != digests.get("u"):
                row["flagged"] += 1
                row["mods"][mod_of(key)] += 1
            else:
                row["cleared"] += 1
    return dict(out)


# Largest old x new group `_pair` will evaluate before giving up on it. A comment rather
# than a string literal, which would just be a discarded expression here.
#
# `_pair` is quadratic in the group size, and a group is one (base key, display name). #80
# measured 11,328 churned keys carrying an identical display name, so a big group is the
# expected shape rather than a pathological one, and an unbounded pairing can spend minutes
# inside a single one. Groups over this are counted into `oversized` and REPORTED, never
# dropped quietly: the whole argument in #80 is an aggregate, so an unreported exclusion
# would change the conclusion while the output still looked complete.
MAX_GROUP_PAIRS = 10000


def _pair(old_entries, new_entries, max_pairs=MAX_GROUP_PAIRS):
    """Pair (key, tags) across two dumps by MAXIMUM per-tag agreement.

    Both sides can hold several NBT species under one (base key, display name) -- 6,565
    tconstruct entries share a handful of names -- so there is no 1:1 join available. Two
    entries that are the same item across two dumps agree on every tag EXCEPT the churning
    one, so "most tags match" is the strongest available signal and it degrades gracefully:
    a wrong pairing shows up as many differing tags, which reads as noise rather than as a
    confident wrong answer.

    Greedy, best-agreement-first. Returns [(old_key, old_tags, new_key, new_tags)].

    AN EXACT SIEVE RUNS FIRST, and it is what makes this finish on a real pack. If every one
    of an entry's tag digests matches an entry on the other side, the two are the same stack
    and nothing churned -- the key digest is computed over the whole compound, so identical
    tags means an identical key. Those need no search at all.

    That is not a micro-optimisation, it is the difference between an answer and an
    exclusion. Measured on the reference pack: 90,583 groups compared, 351.6M combinations
    if paired naively, and 349M of them sit in just 68 groups -- every one a FLUID TANK
    (`thermalexpansion:reservoir`, Blood Tank, Portable Tank) carrying ~3,600 NBT variants,
    one per fluid and amount. Tanks do not churn, so the sieve retires all 3,602 of them in
    linear time and the quadratic pass only ever sees genuinely-changed entries, of which
    there are ~12k in the whole pack. Without it, 117,520 entries were excluded from the
    verdict -- MORE than the 12,377 that were analysed, which is a conclusion drawn from a
    minority while the report still looked complete.
    """
    def signature(tags):
        return tuple(sorted((t, d.get("o")) for t, d in tags.items()))

    new_by_sig = collections.defaultdict(list)
    for ni, (_nk, nt) in enumerate(new_entries):
        new_by_sig[signature(nt)].append(ni)

    pairs, rest_old, used_new = [], [], set()
    for ok, ot in old_entries:
        bucket = new_by_sig.get(signature(ot))
        if bucket:
            ni = bucket.pop()
            used_new.add(ni)
            nk, nt = new_entries[ni]
            pairs.append((ok, ot, nk, nt))
        else:
            rest_old.append((ok, ot))
    rest_new = [e for ni, e in enumerate(new_entries) if ni not in used_new]

    # Whatever the sieve could not settle is churned, renamed, or new, and those groups are
    # small. The cap still guards the pathological case rather than being the common path.
    if len(rest_old) * len(rest_new) > max_pairs:
        return pairs, min(len(rest_old), len(rest_new))

    scored = []
    for oi, (ok, ot) in enumerate(rest_old):
        for ni, (nk, nt) in enumerate(rest_new):
            shared = set(ot) & set(nt)
            agree = sum(1 for t in shared if ot[t].get("o") == nt[t].get("o"))
            scored.append((agree, -len(set(ot) ^ set(nt)), oi, ni))
    scored.sort(reverse=True)
    used_o, used_n = set(), set()
    for _agree, _sym, oi, ni in scored:
        if oi in used_o or ni in used_n:
            continue
        used_o.add(oi)
        used_n.add(ni)
        ok, ot = rest_old[oi]
        nk, nt = rest_new[ni]
        pairs.append((ok, ot, nk, nt))
    return pairs, 0


def churn(old, new, mod=None, max_pairs=MAX_GROUP_PAIRS):
    """The answer to #80: which tags actually moved between two dumps.

    Only items whose DIGEST changed while the display name stayed identical are considered,
    which is #80's own filter -- a genuine rename or a new item is not churn, and counting
    it would inflate the result with ordinary pack drift.

    Returns a dict with per-tag change counts, per-mod totals, and the unpaired remainder.
    Reporting the remainder is the point of it: a silent cap would read as full coverage.
    """
    old_trace, old_names = old
    new_trace, new_names = new

    def group(trace, names):
        out = collections.defaultdict(list)
        for key, tags in trace.items():
            if mod and mod_of(key) != mod:
                continue
            out[(base_key(key), names.get(key, ""))].append((key, tags))
        return out

    old_groups = group(old_trace, old_names)
    new_groups = group(new_trace, new_names)

    tags = collections.defaultdict(collections.Counter)
    mods = collections.Counter()
    changed_items = 0
    unchanged_items = 0
    unpaired = 0
    oversized = 0
    new_only_groups = 0
    for ident in new_groups:
        if ident not in old_groups:
            # Present only in the new dump. Not churn by #80's own filter -- a churned item
            # keeps its display name -- but counted so the report can say what it set aside
            # rather than implying the two dumps held the same population.
            new_only_groups += 1
    for ident, old_entries in old_groups.items():
        new_entries = new_groups.get(ident)
        if not new_entries:
            continue
        paired, skipped = _pair(old_entries, new_entries, max_pairs)
        oversized += skipped
        for ok, ot, nk, nt in paired:
            if ok == nk:
                # Same key on both sides: this item did not churn at all.
                unchanged_items += 1
                continue
            changed_items += 1
            mods[mod_of(ok)] += 1
            for tag in sorted(set(ot) | set(nt)):
                if tag not in ot:
                    tags[tag]["added"] += 1
                elif tag not in nt:
                    tags[tag]["removed"] += 1
                elif ot[tag].get("o") != nt[tag].get("o"):
                    tags[tag]["changed"] += 1
                    # Did the SORTED digest survive? If it did, list order is the whole
                    # difference and the narrow fix works on this tag. If it did not, the
                    # contents themselves differ and sorting would not have helped.
                    if ot[tag].get("u") == nt[tag].get("u"):
                        tags[tag]["order_only"] += 1
                else:
                    tags[tag]["same"] += 1
        unpaired += abs(len(old_entries) - len(new_entries))
    return {
        "tags": {t: dict(c) for t, c in tags.items()},
        "mods": mods,
        "changed_items": changed_items,
        "unchanged_items": unchanged_items,
        "unpaired": unpaired,
        "oversized": oversized,
        "new_only_groups": new_only_groups,
    }


def _report_suspects(rows, limit):
    print("SUSPECT TAGS -- could list order matter for this tag at all?")
    print()
    print("WEAK EVIDENCE, IN BOTH DIRECTIONS. `cleared` is NOT a clearance: a list that is")
    print("already in sorted order in THIS dump reads as cleared and can still churn in the")
    print("next one -- measured, 5,003 churning keys sat in that column. And a high flagged")
    print("count is not a ranking: `Traits` led this table at 7,962 and did not churn, while")
    print("`Special` churned on 10,010. Only two dumps answer what actually moved.\n")
    print("%-34s %9s %9s  %s" % ("tag", "flagged", "cleared", "top mods"))
    ranked = sorted(rows.items(), key=lambda kv: -kv[1]["flagged"])
    shown = 0
    for tag, row in ranked:
        if not row["flagged"]:
            continue
        top = ", ".join("%s %d" % (m, n) for m, n in row["mods"].most_common(3))
        print("%-34s %9d %9d  %s" % (tag[:34], row["flagged"], row["cleared"], top))
        shown += 1
        if shown >= limit:
            break
    hidden = sum(1 for _t, r in ranked if r["flagged"]) - shown
    if hidden > 0:
        print("\n... %d further flagged tags not shown (--limit to raise)" % hidden)
    if not shown:
        print("(nothing flagged: no traced tag holds an order-sensitive list)")


def _report_churn(result, limit):
    print("CHURNED TAGS -- what actually moved between the two dumps.\n")
    print("%d items changed key while keeping the same name; %d kept their key."
          % (result["changed_items"], result["unchanged_items"]))
    if result["unpaired"]:
        print("%d entries could not be paired 1:1 within their (item, name) group and are "
              "EXCLUDED from the tag counts below." % result["unpaired"])
    if result.get("oversized"):
        print("%d entries sat in (item, name) groups too large to pair (over %d "
              "combinations) and are EXCLUDED. Narrow with --mod, or raise --max-pairs."
              % (result["oversized"], MAX_GROUP_PAIRS))
    if result.get("new_only_groups"):
        print("%d (item, name) groups exist only in the NEW dump, so they had nothing to "
              "pair against; a churned item keeps its name, so these are not churn."
              % result["new_only_groups"])
    print()
    print("%-34s %9s %11s %8s %8s" % ("tag", "changed", "order_only", "added", "removed"))
    ranked = sorted(result["tags"].items(), key=lambda kv: -kv[1].get("changed", 0))
    for tag, c in ranked[:limit]:
        print("%-34s %9d %11d %8d %8d"
              % (tag[:34], c.get("changed", 0), c.get("order_only", 0),
                 c.get("added", 0), c.get("removed", 0)))
    if len(ranked) > limit:
        print("\n... %d further tags not shown (--limit to raise)" % (len(ranked) - limit))
    print("\nby mod: " + ", ".join("%s %d" % (m, n) for m, n in result["mods"].most_common(8)))
    print()
    top = ranked[0] if ranked else None
    if top and top[1].get("changed"):
        name, counts = top
        if counts.get("order_only") == counts.get("changed"):
            print("VERDICT: `%s` changed on every churned item and its SORTED digest was "
                  "identical every time, so list order is the entire difference. That is "
                  "the tag to put in the narrow sort list." % name)
        elif counts.get("order_only"):
            print("VERDICT: `%s` is the leading tag, and %d of its %d changes were order-only. "
                  "Sorting it fixes those; the remainder differ in CONTENT and need another "
                  "explanation." % (name, counts["order_only"], counts["changed"]))
        else:
            print("VERDICT: `%s` leads, but NONE of its changes were order-only, so sorting "
                  "it would not have helped. #80's list-order hypothesis does not explain "
                  "this; the tag's contents genuinely differ between runs." % name)
    else:
        print("VERDICT: nothing churned between these two dumps.")


def main(argv=None):
    ap = argparse.ArgumentParser(
        description="Which NBT tag moves the digest between dumps (issue #80)")
    ap.add_argument("dumps", nargs="+", metavar="DUMP",
                    help="one dump dir for the suspect list, or two (old then new) "
                         "for the churn report")
    ap.add_argument("--mod", help="restrict to one registry namespace, e.g. tconstruct")
    ap.add_argument("--limit", type=int, default=20, help="rows to print (default 20)")
    ap.add_argument("--max-pairs", type=int, default=MAX_GROUP_PAIRS,
                    help="largest (item, name) group to pair; bigger ones are reported as "
                         "excluded rather than pairing quadratically (default %d)"
                         % MAX_GROUP_PAIRS)
    ap.add_argument("--json", action="store_true", help="machine-readable output")
    args = ap.parse_args(argv)

    if len(args.dumps) > 2:
        ap.error("at most two dumps: the old one and the new one")

    if len(args.dumps) == 1:
        trace, _names = load_dump(args.dumps[0])
        rows = suspects(trace, args.mod)
        if args.json:
            print(json.dumps({t: {"flagged": r["flagged"], "cleared": r["cleared"],
                                  "mods": dict(r["mods"])}
                              for t, r in rows.items()}, indent=1, sort_keys=True))
        else:
            _report_suspects(rows, args.limit)
        return 0

    old = load_dump(args.dumps[0])
    new = load_dump(args.dumps[1])
    result = churn(old, new, args.mod, args.max_pairs)
    if args.json:
        print(json.dumps({"tags": result["tags"], "mods": dict(result["mods"]),
                          "changed_items": result["changed_items"],
                          "unchanged_items": result["unchanged_items"],
                          "unpaired": result["unpaired"],
                          "oversized": result["oversized"],
                          "new_only_groups": result["new_only_groups"]},
                         indent=1, sort_keys=True))
    else:
        _report_churn(result, args.limit)
    return 0


if __name__ == "__main__":
    sys.exit(main())
