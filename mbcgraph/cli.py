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

    p = sub.add_parser("stats", help="graph coverage")
    p.set_defaults(fn=cmd_stats)

    args = ap.parse_args(argv)
    return args.fn(args)


if __name__ == "__main__":
    sys.exit(main())
