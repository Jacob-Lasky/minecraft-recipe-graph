"""mbcgraph command line.

  python3 -m mbcgraph.cli build   --instance <minecraft dir> --out data/graph.json
  python3 -m mbcgraph.cli have    --regions 'save/region/*.mca' --out data/ae2_have.json
  python3 -m mbcgraph.cli find    borax
  python3 -m mbcgraph.cli plan    'Borax' --qty 64 --have data/ae2_have.json --html plan.html
  python3 -m mbcgraph.cli stats
"""

import argparse
import glob
import json
import os
import sys

from . import index
from .model import Graph
from .names import build_reverse, resolve

DEFAULT_GRAPH = "data/graph.json"
DEFAULT_DB = "data/metrics.db"


def _duration(text):
    """'90s' / '30m' / '2h' / '7d' -> seconds."""
    text = str(text).strip().lower()
    units = {"s": 1, "m": 60, "h": 3600, "d": 86400}
    if text and text[-1] in units:
        return int(float(text[:-1]) * units[text[-1]])
    return int(float(text))


def cmd_build(args):
    g = index.build(args.instance, hei_path=args.hei, no_guess=args.no_guess)
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    g.save(args.out)
    print("wrote %s (%.1f MB)" % (args.out, os.path.getsize(args.out) / 1e6))
    return 0


def cmd_have(args):
    from .ae2_inventory import scan

    paths = []
    for pattern in args.regions:
        paths.extend(sorted(glob.glob(pattern)))
    if not paths:
        print("no region files matched", file=sys.stderr)
        return 2
    items, fluids, essentia, stats, _ = scan(paths)
    payload = {"stats": stats, "items": dict(items),
               "fluids": dict(fluids), "essentia": dict(essentia)}
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    with open(args.out, "w") as fh:
        json.dump(payload, fh, indent=1, sort_keys=True)
    print("wrote %s: %d items, %d fluids from %d cells"
          % (args.out, len(items), len(fluids), stats["cells"]))
    return 0


def _load_graph(path):
    if not os.path.exists(path):
        print("no graph at %s -- run `build` first" % path, file=sys.stderr)
        sys.exit(2)
    return Graph.load(path)


def cmd_find(args):
    g = _load_graph(args.graph)
    rev = build_reverse(g.names)
    keys = resolve(args.query, g.names, rev)
    if not keys:
        print("no match for %r" % args.query)
        return 1
    for key in keys[: args.limit]:
        n = len(g.producers(key))
        print("%-46s %-40s %s" % (key, g.display(key),
                                  "%d recipe(s)" % n if n else "no recipe"))
    return 0


def _load_have(path):
    """Load a stock file from either the world-save reader or tools/ae2_dump.lua.

    The OpenComputers dump additionally carries `craftables` (what AE2 can already
    autocraft) and `names`; both are absent from the offline reader, so treat them
    as optional rather than assuming one producer.
    """
    if not path:
        return {}, {}, set(), {}
    with open(path) as fh:
        doc = json.load(fh)
    have = dict(doc.get("items", {}))
    for name, amount in (doc.get("fluids") or {}).items():
        have["fluid:%s" % name] = amount
    craftables = set(doc.get("craftables") or ())
    return have, doc.get("stats", {}), craftables, doc.get("names") or {}


def cmd_plan(args):
    from .solve import Solver

    g = _load_graph(args.graph)
    keys = resolve(args.item, g.names, build_reverse(g.names))
    if not keys:
        print("no item matched %r -- try `find`" % args.item, file=sys.stderr)
        return 1
    key = keys[0]
    if len(keys) > 1 and not args.exact:
        print("matched %s (%s); %d other candidates, use `find` to disambiguate"
              % (key, g.display(key), len(keys) - 1), file=sys.stderr)

    have, _stats, craftables, extra_names = _load_have(args.have)
    if extra_names:
        # A live dump knows names for items items.csv may predate.
        for k, v in extra_names.items():
            g.names.setdefault(k, v)
    if args.ignore_stock:
        have, craftables = {}, set()
    if args.ignore_craftable:
        craftables = set()
    solver = Solver(g, have=have, craftables=craftables,
                    max_depth=args.depth, max_nodes=args.max_nodes)
    result = solver.solve(key, args.qty)
    if craftables:
        print("(%d items treated as satisfied because AE2 can autocraft them; "
              "--ignore-craftable to expand them)" % len(craftables), file=sys.stderr)

    print("== %s x%d ==" % (result["target_name"], result["qty"]))
    print("nodes: %d%s" % (result["nodes"], "  (TRUNCATED)" if result["truncated"] else ""))
    print("\n-- you still need --")
    for row in result["shopping_list"][: args.limit]:
        print("  %14s  %s" % ("{:,}".format(row["qty"]), row["name"]))
    if not result["shopping_list"]:
        print("  nothing: fully covered by stock")
    if result["used_from_stock"]:
        print("\n-- drawn from your AE2 stock --")
        for row in result["used_from_stock"][:15]:
            print("  %14s  %s" % ("{:,}".format(row["qty"]), row["name"]))

    if args.json:
        with open(args.json, "w") as fh:
            json.dump(result, fh, indent=1)
        print("\nwrote %s" % args.json)
    if args.html:
        from .render import render_html

        with open(args.html, "w") as fh:
            fh.write(render_html(result, g))
        print("wrote %s" % args.html)
    return 0


def cmd_explore(args):
    from . import explore
    from .render import render_explore_html

    g = _load_graph(args.graph)
    have, _stats, craftables, extra_names = _load_have(args.have)
    for k, v in (extra_names or {}).items():
        g.names.setdefault(k, v)

    results = explore.search(g, args.query, have=have, limit=args.limit)
    if not results:
        print("no item name matched %r" % args.query, file=sys.stderr)
        hints = explore.suggest(g, args.query)
        if hints:
            print("did you mean: %s" % ", ".join(hints), file=sys.stderr)
        return 1

    for r in results:
        flags = []
        if r["stock"]:
            flags.append("%s in stock" % "{:,}".format(r["stock"]))
        flags.append("%d recipe(s)" % r["makes_total"])
        flags.append("%d use(s)" % r["used_in_total"])
        if r["oredicts"]:
            flags.append("ore: " + ",".join(r["oredicts"][:3]))
        print("%-44s %-38s %s" % (r["key"], r["name"][:38], " | ".join(flags)))

    payload = {"query": args.query, "results": results, "searched": len(g.names)}
    if args.json:
        with open(args.json, "w") as fh:
            json.dump(payload, fh, indent=1)
        print("\nwrote %s" % args.json)
    if args.html:
        note = None
        if not any(r.source == "hei_dump" for r in g.recipes):
            note = ("This graph has no machine recipes yet: run /mbcdump in game, "
                    "otherwise machine-made items show as having no recipe.")
        with open(args.html, "w") as fh:
            fh.write(render_explore_html(payload, note))
        print("wrote %s" % args.html)
    return 0


def cmd_track(args):
    """Record one snapshot of AE2 stock (and power, if the dump provides it)."""
    from . import metrics

    if args.regions:
        from .ae2_inventory import scan
        paths = []
        for pattern in args.regions:
            paths.extend(sorted(glob.glob(pattern)))
        if not paths:
            print("no region files matched", file=sys.stderr)
            return 2
        items, fluids, _ess, st, _s = scan(paths)
        have = dict(items)
        for name, amount in fluids.items():
            have["fluid:%s" % name] = amount
        source, power, names = "save", None, None
    else:
        have, st, _craftables, names = _load_have(args.have)
        with open(args.have) as fh:
            doc = json.load(fh)
        source = doc.get("source", "save")
        power = doc.get("power")

    if not have:
        print("nothing to record", file=sys.stderr)
        return 1

    # Backfill labels from the graph so the charts read in English.
    if not names and os.path.exists(args.graph):
        g = Graph.load(args.graph)
        names = {k: g.display(k) for k in have if k in g.names}

    conn = metrics.connect(args.db)
    written = metrics.record(conn, have, source=source, power=power, names=names)
    if not args.no_prune:
        metrics.prune(conn)
    info = metrics.stats(conn)
    print("recorded %d items from %s; rows written per tier: %s"
          % (len(have), source, written))
    print("db %s: %d snapshots, %d level rows, %d distinct items"
          % (args.db, info["snapshots"], info["level_rows"], info["distinct_items"]))
    return 0


def cmd_chart(args):
    from . import metrics
    from .chart import render_chart_html

    conn = metrics.connect(args.db)
    info = metrics.stats(conn)
    if not info["snapshots"]:
        print("no snapshots yet -- run `track` at least twice", file=sys.stderr)
        return 1

    until = info["last_snapshot"]
    window = _duration(args.window)
    since = until - window
    tier = metrics.pick_tier(window)

    tops = metrics.movers(conn, since, until, limit=args.top, tier=tier)
    if not tops:
        print("no quantity changed in the last %s (need >=2 snapshots apart)" % args.window,
              file=sys.stderr)
        return 1

    payload = {
        "since": since, "until": until, "tier": tier,
        "window_label": args.window,
        "range_label": "%s snapshots recorded, %s tracked items"
                       % ("{:,}".format(info["snapshots"]),
                          "{:,}".format(info["distinct_items"])),
        "source": "mixed",
        "movers": tops,
        "series": {m["key"]: metrics.series(conn, m["key"], since, until, tier)
                   for m in tops},
        "power": metrics.power_series(conn, since, until, tier),
        "storage": info,
    }

    for m in tops[: args.limit]:
        print("%-40s %14s -> %-14s %+12s  %s"
              % (m["label"][:40], "{:,}".format(m["first"]), "{:,}".format(m["last"]),
                 "{:,}".format(m["delta"]),
                 ("%+.1f/min" % m["per_min"])))

    if args.html:
        with open(args.html, "w") as fh:
            fh.write(render_chart_html(payload))
        print("\nwrote %s" % args.html)
    if args.json:
        with open(args.json, "w") as fh:
            json.dump(payload, fh, indent=1)
        print("wrote %s" % args.json)
    return 0


def cmd_metrics(args):
    from . import metrics
    conn = metrics.connect(args.db)
    print(json.dumps(metrics.stats(conn), indent=2))
    return 0


def cmd_stats(args):
    g = _load_graph(args.graph)
    print(json.dumps(index.coverage(g), indent=2))
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(prog="mbcgraph")
    ap.add_argument("--graph", default=DEFAULT_GRAPH)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("build", help="extract recipes into a graph")
    p.add_argument("--instance", required=True, help="the pack's minecraft/ dir")
    p.add_argument("--hei", help="path to recipes.ndjson from the dump mod")
    p.add_argument("--out", default=DEFAULT_GRAPH)
    p.add_argument("--no-guess", action="store_true",
                   help="disable heuristic oredict inference")
    p.set_defaults(fn=cmd_build)

    p = sub.add_parser("have", help="read AE2 network contents from a world save")
    p.add_argument("--regions", nargs="+", required=True)
    p.add_argument("--out", default="data/ae2_have.json")
    p.set_defaults(fn=cmd_have)

    p = sub.add_parser("find", help="look up item ids by name")
    p.add_argument("query")
    p.add_argument("--limit", type=int, default=20)
    p.set_defaults(fn=cmd_find)

    p = sub.add_parser("plan", help="resolve a crafting tree against your stock")
    p.add_argument("item")
    p.add_argument("--qty", type=int, default=1)
    p.add_argument("--have", default="data/ae2_have.json")
    p.add_argument("--ignore-stock", action="store_true")
    p.add_argument("--ignore-craftable", action="store_true",
                   help="expand items AE2 could autocraft instead of stopping")
    p.add_argument("--exact", action="store_true")
    p.add_argument("--depth", type=int, default=24)
    p.add_argument("--max-nodes", type=int, default=4000)
    p.add_argument("--limit", type=int, default=40)
    p.add_argument("--json")
    p.add_argument("--html")
    p.set_defaults(fn=cmd_plan)

    p = sub.add_parser("explore", help="search items: how made, what uses them, stock")
    p.add_argument("query")
    p.add_argument("--have", default="data/ae2_have.json")
    p.add_argument("--limit", type=int, default=60)
    p.add_argument("--json")
    p.add_argument("--html")
    p.set_defaults(fn=cmd_explore)

    p = sub.add_parser("track", help="record one AE2 stock snapshot into the metrics db")
    p.add_argument("--have", default="data/ae2_have.json")
    p.add_argument("--regions", nargs="+", help="scan the save directly instead")
    p.add_argument("--db", default=DEFAULT_DB)
    p.add_argument("--no-prune", action="store_true")
    p.set_defaults(fn=cmd_track)

    p = sub.add_parser("chart", help="stock levels and net rates over time")
    p.add_argument("--db", default=DEFAULT_DB)
    p.add_argument("--window", default="2h", help="e.g. 30m, 2h, 2d")
    p.add_argument("--top", type=int, default=12, help="series to chart")
    p.add_argument("--limit", type=int, default=15, help="rows to print")
    p.add_argument("--html")
    p.add_argument("--json")
    p.set_defaults(fn=cmd_chart)

    p = sub.add_parser("metrics", help="metrics db size and coverage")
    p.add_argument("--db", default=DEFAULT_DB)
    p.set_defaults(fn=cmd_metrics)

    p = sub.add_parser("stats", help="graph coverage")
    p.set_defaults(fn=cmd_stats)

    args = ap.parse_args(argv)
    return args.fn(args)


if __name__ == "__main__":
    sys.exit(main())
