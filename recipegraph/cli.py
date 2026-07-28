"""recipegraph command line.

  python3 -m recipegraph.cli build   --instance <minecraft dir> --out data/graph.json
  python3 -m recipegraph.cli have    --regions 'save/region/*.mca' --out data/ae2_have.json
  python3 -m recipegraph.cli find    borax
  python3 -m recipegraph.cli plan    'Borax' --qty 64 --have data/ae2_have.json --html plan.html
  python3 -m recipegraph.cli stats
"""

import argparse
import glob
import json
import os
import sys

from . import index
from . import machines
from . import pins as pins_mod
from . import tokens as tokens_mod
from .defaults import (DEFAULT_COST_CACHE, DEFAULT_GRAPH, DEFAULT_HAVE, DEFAULT_HOST,
                       DEFAULT_PINS, DEFAULT_TOKENS,
                       DEFAULT_MACHINES, DEFAULT_METRICS_DB, DEFAULT_PORT, DEFAULT_SOURCES)
from .model import Graph, essentia_key
from .names import build_reverse, resolve



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
    from . import ae2_inventory
    from .ae2_inventory import scan

    paths = []
    for pattern in args.regions:
        paths.extend(sorted(glob.glob(pattern)))
    if not paths:
        print("no region files matched", file=sys.stderr)
        return 2
    items, fluids, essentia, stats, _s, placed = scan(paths)
    # `reader` stamps WHICH scanner wrote this, because an unmatched NBT key means
    # "rescan" on a pre-#21 file and "the dump cannot digest this stack" on a current
    # one. See ae2_inventory.READER and gaps.stock_coverage.
    payload = {"stats": stats, "reader": ae2_inventory.READER, "items": dict(items),
               "fluids": dict(fluids), "essentia": dict(essentia),
               "placed": dict(placed)}
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    with open(args.out, "w") as fh:
        json.dump(payload, fh, indent=1, sort_keys=True)
    print("wrote %s: %d items, %d fluids, %d essentia aspects from %d cells; "
          "%d placed machine types"
          % (args.out, len(items), len(fluids), len(essentia), stats["cells"], len(placed)))
    # Reconcile against the graph while both are in hand. A scan that writes 3,321 keys
    # looks like a success even when 320 of them name nothing any recipe uses, and that
    # silence is what let #21 sit unnoticed: the stock was there, the plans ignored it.
    if os.path.exists(args.graph):
        from . import gaps
        print(gaps.stock_report(gaps.stock_coverage(Graph.load(args.graph), items,
                                                    reader=ae2_inventory.READER)))
    return 0


def _placed_and_stock(have_path):
    """Placed tile entities and item stock from a have file, for evidence-based checks."""
    if not have_path or not os.path.exists(have_path):
        return {}, {}
    with open(have_path) as fh:
        doc = json.load(fh)
    return doc.get("placed") or {}, doc.get("items") or {}


def _machine_states(graph, have_path, overrides_path):
    placed, stock = _placed_and_stock(have_path)
    overrides = machines.load_overrides(overrides_path)
    return machines.resolve(graph, placed, stock, overrides=overrides,
                            no_machine=machines.load_no_machine(overrides_path)), overrides


def _token_kinds(args):
    """{key: kind} for pack placeholders, honouring the user's overrides file.

    Read through `tokens.resolve` rather than reaching for DEFAULT_TOKENS directly, so the
    plan, the `tokens` listing and any future caller all see the same effective map.
    """
    return tokens_mod.for_path(getattr(args, "tokens", None))


def _free_sources(have_path, sources_path):
    """Item/fluid keys an infinite generator in this world makes effectively free."""
    from . import generators

    placed, stock = _placed_and_stock(have_path)
    return generators.resolve(placed, stock, generators.load_overrides(sources_path))


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
    for aspect, amount in (doc.get("essentia") or {}).items():
        have[essentia_key(aspect)] = amount
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
    states = {}
    if not args.ignore_machines:
        states, _ov = _machine_states(g, args.have, args.machines)
    free = {} if args.ignore_sources else _free_sources(args.have, args.sources)
    costs = None
    if not args.no_cost:
        from . import cost as cost_mod
        costs = cost_mod.estimate_cached(g, args.graph, have=have, machine_states=states,
                                         free_sources=free)
    # Pins outrank the ranking, and a pin that has lapsed says so on stderr rather than
    # quietly reverting: "i'm fine with suggestions", not with silent overwrites (#30).
    pinned, pin_notes = ({}, {})
    if not args.ignore_pins:
        pinned, pin_notes = pins_mod.resolve(g, pins_mod.load(args.pins))
    for pin_key, (pstate, why) in sorted(pin_notes.items()):
        if pstate != pins_mod.EXACT:
            print("pin on %s: %s" % (g.bare_name(pin_key), why), file=sys.stderr)
    solver = Solver(g, have=have, craftables=craftables, machine_states=states,
                    costs=costs, max_depth=args.depth, max_nodes=args.max_nodes,
                    free_sources=free, token_kinds=_token_kinds(args), pinned=pinned)
    result = solver.solve(key, args.qty)
    if craftables:
        print("(%d items treated as satisfied because AE2 can autocraft them; "
              "--ignore-craftable to expand them)" % len(craftables), file=sys.stderr)

    for why in sorted((result.get("pins_overruled") or {}).values()):
        print(why, file=sys.stderr)
    print("== %s x%d ==" % (result["target_name"], result["qty"]))
    print("nodes: %d%s" % (result["nodes"], "  (TRUNCATED)" if result["truncated"] else ""))
    print("\n-- you still need --")
    for row in result["shopping_list"][: args.limit]:
        print("  %14s  %s" % ("{:,}".format(row["qty"]), row["name"]))
    if not result["shopping_list"]:
        # NOT "fully covered by stock" when placeholders remain. That sentence on a plan
        # that still needs a Dungeon Drop is simply false, and it is the sentence a reader
        # stops at.
        print("  nothing"
              if result.get("tokens_needed") else "  nothing: fully covered by stock")

    if result.get("tokens_needed"):
        # Printed for the same reason infinite-source draw is: moving these off the
        # shopping list must not move them off the page. Grouped, because the whole point
        # is that "Dungeon Drop" and "From Battle Tower Loot" are one instruction.
        print("\n-- not crafted, obtained --")
        for _kind, label, rows in tokens_mod.group(result["tokens_needed"]):
            print("  %s:" % label)
            for row in rows:
                print("  %14s  %s" % ("{:,}".format(row["qty"]), row["name"]))
    if result["used_from_stock"]:
        print("\n-- drawn from your AE2 stock --")
        for row in result["used_from_stock"][:15]:
            print("  %14s  %s" % ("{:,}".format(row["qty"]), row["name"]))
    if result.get("from_sources"):
        # Printed, not hidden: a free resource still has a quantity, and "8,000,000 mB of
        # water" is a signal about the route even when the water itself costs nothing.
        print("\n-- drawn from infinite sources --")
        for row in result["from_sources"][:15]:
            print("  %14s  %-34s %s" % ("{:,}".format(row["qty"]),
                                        row["name"][:34], row["why"]))

    if result.get("machines_to_build"):
        print("\n-- machines you do not have yet --")
        for m in result["machines_to_build"]:
            print("  %-38s %-12s %s" % (m["machine"][:38], m["state"], m["why"]))

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
    from . import present
    from .render import render_explore_html

    g = _load_graph(args.graph)
    have, _stats, craftables, extra_names = _load_have(args.have)
    for k, v in (extra_names or {}).items():
        g.names.setdefault(k, v)

    hits = explore.search(g, args.query, have=have, limit=args.limit)
    results = hits.results
    if not results:
        print("no item name matched %r" % args.query, file=sys.stderr)
        if hits.hidden:
            # Distinguishes "that word is nowhere in the pack" from "every match is an NBT
            # variant nothing can make". Only the second is worth widening the query for.
            print(present.hidden_note(hits.hidden), file=sys.stderr)
        hints = explore.name_hints(g, args.query)
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

    if hits.hidden:
        print("\n" + present.hidden_note(hits.hidden))

    payload = {"query": args.query, "results": results, "hidden": hits.hidden,
               "searched": len(g.live_keys), "named": len(g.labels)}
    if args.json:
        with open(args.json, "w") as fh:
            json.dump(payload, fh, indent=1)
        print("\nwrote %s" % args.json)
    if args.html:
        note = None
        if not any(r.source == "hei_dump" for r in g.recipes):
            note = ("This graph has no machine recipes yet: run /recipedump in game, "
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
        items, fluids, _ess, st, _s, _pl = scan(paths)
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


def cmd_gaps(args):
    """What is the graph blind to, per the dump mod's skip log."""
    from . import gaps

    if not os.path.isdir(args.dump_dir):
        print("no dump dir at %s -- run /recipedump in game first" % args.dump_dir,
              file=sys.stderr)
        return 2
    summary, skips = gaps.load(args.dump_dir)
    if not summary and not skips:
        print("no summary.json or skipped.ndjson in %s. A dump made by mod v0.1.0 only\n"
              "counted failures without recording them; v0.2.0+ writes both files."
              % args.dump_dir, file=sys.stderr)
        return 1
    analysis = gaps.analyse(summary, skips)
    print(gaps.report(analysis))
    if args.json:
        with open(args.json, "w") as fh:
            json.dump(analysis, fh, indent=1)
        print("\nwrote %s" % args.json)
    return 0


def cmd_metrics(args):
    from . import metrics
    conn = metrics.connect(args.db)
    print(json.dumps(metrics.stats(conn), indent=2))
    return 0


def _dump_newer_than_graph(graph_path, instance_dir):
    """True when a `/recipedump` has happened since this graph was built."""
    if not instance_dir:
        return False
    dump = os.path.join(instance_dir, "mc-recipe-dump", "recipes.ndjson")
    if not (os.path.exists(dump) and os.path.exists(graph_path)):
        return False
    return os.path.getmtime(dump) > os.path.getmtime(graph_path)


def ensure_graph(graph_path, instance_dir=None, quiet=False, allow_build=True):
    """Build the graph if it is missing or older than the dump. Returns the path.

    Exists because the documented flow was "run /recipedump, then build, then serve", and
    the middle step is one the tool can work out for itself: the graph records the instance
    it came from, so a dump newer than the graph is unambiguous. Fewer steps between the
    player and the answer is the whole point of the UI.
    """
    def say(msg):
        if not quiet:
            print(msg, file=sys.stderr)

    have_graph = os.path.exists(graph_path)
    instance_dir = instance_dir or (
        Graph.load(graph_path).instance_dir if have_graph else None)

    if have_graph and not _dump_newer_than_graph(graph_path, instance_dir):
        return graph_path
    if not allow_build:
        if not have_graph:
            print("no graph at %s -- run `build` first" % graph_path, file=sys.stderr)
            sys.exit(2)
        say("note: %s has a newer dump than this graph; run `build` to pick it up"
            % instance_dir)
        return graph_path
    if not instance_dir or not os.path.isdir(instance_dir):
        if not have_graph:
            print("no graph at %s and no --instance to build one from"
                  % graph_path, file=sys.stderr)
            sys.exit(2)
        return graph_path

    say("%s -- building from %s"
        % ("no graph yet" if not have_graph else "dump is newer than the graph",
           instance_dir))
    g = index.build(instance_dir)
    os.makedirs(os.path.dirname(graph_path) or ".", exist_ok=True)
    g.save(graph_path)
    say("wrote %s (%.1f MB)" % (graph_path, os.path.getsize(graph_path) / 1e6))
    return graph_path


def cmd_serve(args):
    """Local web UI so the tool is usable without a terminal."""
    from . import server

    ensure_graph(args.graph, args.instance, allow_build=not args.no_build)
    print("loading graph %s ..." % args.graph, file=sys.stderr)
    httpd, state = server.serve(args.graph, args.have, args.machines,
                                host=args.host, port=args.port,
                                sources_path=args.sources, tokens_path=args.tokens,
                                pins_path=args.pins)
    print("recipegraph UI on http://%s:%d  (%s recipes, %s stocked items)"
          % (args.host, args.port, "{:,}".format(len(state.graph.recipes)),
             "{:,}".format(len(state.have))))
    print("Ctrl-C to stop.")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nstopped")
    finally:
        httpd.server_close()
    return 0


def cmd_pins(args):
    """List the recipe choices made by hand, and say which ones still apply.

    A listing plus a clear, no set: choosing a recipe wants the category, the machine
    state and the cost side by side, which is the /recipes page. What a terminal is for
    is the other half, finding out why a plan stopped obeying a pin after a redump.
    """
    from . import present

    g = _load_graph(args.graph)
    stored = pins_mod.load(args.pins)
    if args.clear:
        gone = [k for k in args.clear if stored.pop(k, None) is not None]
        pins_mod.save(args.pins, stored)
        print("cleared %d of %d; wrote %s" % (len(gone), len(args.clear), args.pins))
        return 0
    if not stored:
        print("no pins in %s" % args.pins)
        return 0
    _accepted, notes = pins_mod.resolve(g, stored)
    for key in sorted(stored):
        state, why = notes.get(key, (pins_mod.DEAD, ""))
        text, _cls = present.pin_badge(state)
        print("%-40s %s" % (g.bare_name(key), stored[key]["label"]))
        print("%-40s %s" % ("", why or "%s [%s]" % (text, stored[key]["category"])))
    return 0


def cmd_tokens(args):
    """Show which pack placeholders are recognised, and offer the ones that are not."""
    g = _load_graph(args.graph)
    ov = tokens_mod.load_overrides(args.file)
    added = dict(ov.get("tokens") or {})
    disabled = set(ov.get("disabled") or ())
    dirty = False
    for pair in args.add or ():
        if "=" not in pair:
            print("--add expects KEY=KIND, got %r" % pair, file=sys.stderr)
            return 2
        key, kind = (part.strip() for part in pair.split("=", 1))
        if kind not in tokens_mod.KINDS:
            print("unknown kind %r; expected one of %s"
                  % (kind, ", ".join(tokens_mod.KINDS)), file=sys.stderr)
            return 2
        added[key] = kind
        disabled.discard(key)
        dirty = True
    for key in args.disable or ():
        disabled.add(key.strip())
        added.pop(key.strip(), None)
        dirty = True
    if dirty:
        tokens_mod.save_overrides(args.file, added, disabled)
        print("wrote %s" % args.file)
        ov = tokens_mod.load_overrides(args.file)
    known = tokens_mod.resolve(ov)
    by_kind = {}
    for key, kind in known.items():
        by_kind.setdefault(kind, []).append(key)
    total = 0
    for kind in tokens_mod.KINDS:
        keys = by_kind.get(kind)
        if not keys:
            continue
        keys.sort(key=lambda k: (-len(g.by_input.get(k, ())), k))
        slots = sum(len(g.by_input.get(k, ())) for k in keys)
        total += slots
        print("%s -- %s (%d ids, %d recipe slots)"
              % (kind, tokens_mod.KIND_LABEL[kind], len(keys), slots))
        for key in keys:
            n = len(g.by_input.get(key, ()))
            # A curated id no recipe uses is the signal the list has drifted from the pack.
            flag = "" if n else "   <- UNUSED by any recipe in this graph"
            print("  %5d  %-44s %s%s" % (n, key, g.bare_name(key), flag))
        print("")
    print("recognised: %d ids across %d recipe slots" % (len(known), total))

    offered = tokens_mod.candidates(g, known, limit=args.limit)
    if offered:
        print("\nnot recognised, most-used first. These are OFFERS, not findings: the test")
        print("is only 'some recipe needs it and none makes it', which is also true of any")
        print("item the dump has no recipe for. Add real ones with --add KEY=KIND.")
        for key, name, n in offered:
            print("  %5d  %-44s %s" % (n, key, name))
    return 0


def cmd_sources(args):
    """Show, or edit, which infinite generators make a resource free."""
    from . import generators

    ov = generators.load_overrides(args.file)
    dirty = False
    if args.add:
        for pair in args.add:
            if "=" not in pair:
                print("--add expects block=key, got %r" % pair, file=sys.stderr)
                return 2
            block, key = (part.strip() for part in pair.split("=", 1))
            ov["generators"].setdefault(block, [])
            if key not in ov["generators"][block]:
                ov["generators"][block].append(key)
            dirty = True
    if args.disable:
        ov["disabled"] |= {k.strip() for k in args.disable}
        dirty = True
    if args.no_vanilla_water:
        ov["vanilla_water"] = False
        dirty = True
    if dirty:
        generators.save_overrides(args.file, ov["generators"], ov["disabled"],
                                  ov["vanilla_water"])
        print("wrote %s" % args.file)

    g = _load_graph(args.graph)
    placed, stock = _placed_and_stock(args.have)
    free = generators.resolve(placed, stock, ov)
    if not free:
        print("no infinite generators detected in this world.")
    for key, why in sorted(free.items()):
        print("  %-40s %-34s %s" % (key, g.display(key)[:34], why))

    # Detection is a curated list, so say what was NOT matched rather than implying the
    # world was searched exhaustively.
    known = set(generators.DEFAULT_GENERATORS) | set(ov["generators"])
    # `sightings`, not `b not in placed`: the literal test called a block unmatched that
    # `resolve` had just matched through a state suffix or a legacy dotted id, so the
    # count under the list contradicted the list itself.
    seen = generators.sightings(known, placed, stock)
    unmatched = sorted(b for b in known if b not in seen)
    print("\n%d known generator blocks, %d matched in this world."
          % (len(known), len(known) - len(unmatched)))
    print("Detection is a curated list, not a search: add yours with")
    print("  sources --add <block id>=<item or fluid key>")
    return 0


def cmd_machines(args):
    """List machine availability per recipe category, or toggle one by hand."""
    g = _load_graph(args.graph)
    overrides = machines.load_overrides(args.file)

    if args.set:
        for pair in args.set:
            if "=" not in pair:
                print("--set expects uid=state, got %r" % pair, file=sys.stderr)
                return 2
            uid, state = pair.split("=", 1)
            state = state.strip()
            if state not in machines.STATES:
                print("state must be one of %s, got %r"
                      % ("|".join(machines.STATES), state), file=sys.stderr)
                return 2
            overrides[uid.strip()] = state
        machines.save_overrides(args.file, overrides)
        print("wrote %s (%d overrides)" % (args.file, len(overrides)))

    states, overrides = _machine_states(g, args.have, args.file)
    counts = machines.summarise(states)
    print("categories: %s"
          % ", ".join("%d %s" % (counts[s], s) for s in machines.STATES))

    rows = sorted(states.items())
    if args.state:
        rows = [r for r in rows if r[1][0] == args.state]
    if args.match:
        needle = args.match.lower()
        rows = [r for r in rows if needle in r[0].lower() or needle in r[1][1].lower()]

    for uid, (state, why) in rows[: args.limit]:
        mark = "*" if uid in overrides else " "
        print("%s %-11s %-46s %s" % (mark, state, uid[:46], why))
    if len(rows) > args.limit:
        print("... %d more (use --limit)" % (len(rows) - args.limit))
    print("\n* = manual override. Toggle with:  machines --set <uid>=have")
    return 0


def cmd_stats(args):
    g = _load_graph(args.graph)
    print(json.dumps(index.coverage(g), indent=2))
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(prog="recipegraph")
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
    p.add_argument("--out", default=DEFAULT_HAVE)
    p.set_defaults(fn=cmd_have)

    p = sub.add_parser("find", help="look up item ids by name")
    p.add_argument("query")
    p.add_argument("--limit", type=int, default=20)
    p.set_defaults(fn=cmd_find)

    p = sub.add_parser("plan", help="resolve a crafting tree against your stock")
    p.add_argument("item")
    p.add_argument("--qty", type=int, default=1)
    p.add_argument("--have", default=DEFAULT_HAVE)
    p.add_argument("--ignore-stock", action="store_true")
    p.add_argument("--ignore-craftable", action="store_true",
                   help="expand items AE2 could autocraft instead of stopping")
    p.add_argument("--machines", default=DEFAULT_MACHINES,
                   help="manual machine availability overrides")
    p.add_argument("--ignore-machines", action="store_true",
                   help="do not weight recipes by whether you own the machine")
    p.add_argument("--sources", default=DEFAULT_SOURCES,
                   help="infinite generator additions and removals")
    p.add_argument("--ignore-sources", action="store_true",
                   help="do not treat infinite generator output as free")
    p.add_argument("--tokens", default=DEFAULT_TOKENS,
                   help="pack placeholder additions and removals")
    p.add_argument("--pins", default=DEFAULT_PINS,
                   help="recipe choices made by hand, which outrank the ranking")
    p.add_argument("--ignore-pins", action="store_true",
                   help="plan as if nothing were pinned")
    p.add_argument("--no-cost", action="store_true",
                   help="skip the cost precompute and choose recipes greedily")
    p.add_argument("--exact", action="store_true")
    p.add_argument("--depth", type=int, default=24)
    p.add_argument("--max-nodes", type=int, default=4000)
    p.add_argument("--limit", type=int, default=40)
    p.add_argument("--json")
    p.add_argument("--html")
    p.set_defaults(fn=cmd_plan)

    p = sub.add_parser("explore", help="search items: how made, what uses them, stock")
    p.add_argument("query")
    p.add_argument("--have", default=DEFAULT_HAVE)
    p.add_argument("--limit", type=int, default=60)
    p.add_argument("--json")
    p.add_argument("--html")
    p.set_defaults(fn=cmd_explore)

    p = sub.add_parser("track", help="record one AE2 stock snapshot into the metrics db")
    p.add_argument("--have", default=DEFAULT_HAVE)
    p.add_argument("--regions", nargs="+", help="scan the save directly instead")
    p.add_argument("--db", default=DEFAULT_METRICS_DB)
    p.add_argument("--no-prune", action="store_true")
    p.set_defaults(fn=cmd_track)

    p = sub.add_parser("chart", help="stock levels and net rates over time")
    p.add_argument("--db", default=DEFAULT_METRICS_DB)
    p.add_argument("--window", default="2h", help="e.g. 30m, 2h, 2d")
    p.add_argument("--top", type=int, default=12, help="series to chart")
    p.add_argument("--limit", type=int, default=15, help="rows to print")
    p.add_argument("--html")
    p.add_argument("--json")
    p.set_defaults(fn=cmd_chart)

    p = sub.add_parser("metrics", help="metrics db size and coverage")
    p.add_argument("--db", default=DEFAULT_METRICS_DB)
    p.set_defaults(fn=cmd_metrics)

    p = sub.add_parser("gaps", help="what the graph is blind to, from the dump skip log")
    p.add_argument("--dump-dir", default="mc-recipe-dump",
                   help="the mc-recipe-dump/ dir written by /recipedump")
    p.add_argument("--json")
    p.set_defaults(fn=cmd_gaps)

    p = sub.add_parser("pins", help="recipe choices you made by hand")
    p.add_argument("--pins", default=DEFAULT_PINS)
    p.add_argument("--clear", nargs="+", metavar="ITEM",
                   help="stop pinning these item keys")
    p.set_defaults(fn=cmd_pins)

    p = sub.add_parser("serve",
                       help="local web UI; builds the graph first if it is missing or stale")
    p.add_argument("--instance", help="pack's minecraft/ dir, if the graph must be built "
                                      "(remembered from the last build otherwise)")
    p.add_argument("--sources", default=DEFAULT_SOURCES)
    p.add_argument("--tokens", default=DEFAULT_TOKENS)
    p.add_argument("--pins", default=DEFAULT_PINS)
    p.add_argument("--no-build", action="store_true",
                   help="never build; only warn if the graph is behind the dump")
    p.add_argument("--have", default=DEFAULT_HAVE)
    p.add_argument("--machines", default=DEFAULT_MACHINES)
    # Localhost by default on purpose: the graph exposes a live base's contents and there
    # is no auth. Binding wider has to be a deliberate act.
    p.add_argument("--host", default=DEFAULT_HOST)
    p.add_argument("--port", type=int, default=DEFAULT_PORT)
    p.set_defaults(fn=cmd_serve)

    p = sub.add_parser("machines", help="which machines you have, and manual toggles")
    p.add_argument("--have", default=DEFAULT_HAVE)
    p.add_argument("--file", default=DEFAULT_MACHINES, help="overrides file")
    p.add_argument("--set", nargs="+", metavar="UID=STATE",
                   help="set availability by hand, e.g. nuclearcraft_crystallizer=have")
    p.add_argument("--state", choices=list(machines.STATES))
    p.add_argument("--match", help="filter by category uid or reason")
    p.add_argument("--limit", type=int, default=40)
    p.set_defaults(fn=cmd_machines)

    p = sub.add_parser("sources", help="infinite generators that make resources free")
    p.add_argument("--have", default=DEFAULT_HAVE)
    p.add_argument("--file", default=DEFAULT_SOURCES, help="additions and removals")
    p.add_argument("--add", nargs="+", metavar="BLOCK=KEY",
                   help="e.g. mymod:water_well=fluid:water")
    p.add_argument("--disable", nargs="+", metavar="KEY",
                   help="stop treating a key as free, e.g. fluid:water")
    p.add_argument("--no-vanilla-water", action="store_true",
                   help="this pack has disabled infinite water spreading")
    p.set_defaults(fn=cmd_sources)

    p = sub.add_parser("tokens",
                       help="pack placeholders that stand in for an instruction")
    p.add_argument("--graph", default=DEFAULT_GRAPH)
    p.add_argument("--file", default=DEFAULT_TOKENS, help="additions and removals")
    p.add_argument("--limit", type=int, default=25,
                   help="how many unrecognised candidates to offer")
    p.add_argument("--add", nargs="+", metavar="KEY=KIND",
                   help="e.g. contenttweaker:my_drop=loot; kinds: "
                        + ", ".join(tokens_mod.KINDS))
    p.add_argument("--disable", nargs="+", metavar="KEY",
                   help="stop treating a key as a placeholder")
    p.set_defaults(fn=cmd_tokens)

    p = sub.add_parser("stats", help="graph coverage")
    p.set_defaults(fn=cmd_stats)

    args = ap.parse_args(argv)
    return args.fn(args)


if __name__ == "__main__":
    sys.exit(main())
