"""Coverage gap analysis over the dump mod's skipped.ndjson and summary.json.

The point is to answer "what is my graph blind to, and does it matter" rather than
just "N recipes were skipped".

The most important signal is a category with `dumped == 0`: that is a total blind
spot, an entire machine type absent from the graph, and any item made only there will
look uncraftable. A category that dumped 900 of 1000 recipes is a very different
situation and should not be ranked alongside it.
"""

import json
import os


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
