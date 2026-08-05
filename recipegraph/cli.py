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

from . import dimensions
from . import explore as explore_mod
from . import index
from . import machines
from . import pins as pins_mod
from . import projecte
from . import tokens as tokens_mod
from .defaults import (DEFAULT_GRAPH, DEFAULT_HAVE, DEFAULT_HOST, DEFAULT_MACHINES,
                       DEFAULT_MAX_NODES, DEFAULT_METRICS_DB, DEFAULT_PINS, DEFAULT_PORT,
                       DEFAULT_SOURCES, DEFAULT_TOKENS)
from .model import Graph, essentia_key, fluid_key
from .sources import dump_meta



def _duration(text):
    """'90s' / '30m' / '2h' / '7d' -> seconds."""
    text = str(text).strip().lower()
    units = {"s": 1, "m": 60, "h": 3600, "d": 86400}
    if text and text[-1] in units:
        return int(float(text[:-1]) * units[text[-1]])
    return int(float(text))


def cmd_build(args):
    # `out_path` is passed so the icon atlas pages land BESIDE the graph rather than being
    # left in the pack instance, which the serving container cannot see. See sources/icons.
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    g = index.build(args.instance, hei_path=args.hei, no_guess=args.no_guess,
                    dump_dir=args.dump_dir, out_path=args.out,
                    allow_mod_set_change=args.allow_mod_set_change)
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
    # Which dimensions the save has terrain for, which is #112's evidence that you have
    # BEEN somewhere. A directory listing beside the region walk, not part of it: it reads
    # no chunk data and costs nothing, and it deliberately looks at the whole save rather
    # than at `paths`, which name the overworld's region files only.
    #
    # A PORTAL WAS THE OBVIOUS SIGNAL AND IT IS THE WRONG ONE. #112 proposed reading placed
    # portal blocks and noted that a vanilla nether portal has no tile entity, so `placed`
    # above cannot see one -- true, and beside the point. Entering a dimension GENERATES
    # it, so the terrain is the evidence, and it works identically for the Nether, the End,
    # a planet and a mod dimension with no portal at all.
    world_dir = _world_dir(paths)
    visited = dimensions.visited(world_dir) if world_dir else {}
    # ProjectE transmutation knowledge, from the same save root, for the same reason: it is
    # world state that decides whether a drop-only item is reachable, and it changes without
    # the pack changing. Reads `playerdata/*.dat` and no chunk data, so it costs milliseconds
    # against the region walk's seven minutes. See projecte and #50.
    knowledge = projecte.read_knowledge(world_dir) if world_dir else {}
    # `reader` stamps WHICH scanner wrote this, because an unmatched NBT key means
    # "rescan" on a pre-#21 file and "the dump cannot digest this stack" on a current
    # one. See ae2_inventory.READER and gaps.stock_coverage.
    payload = {"stats": stats, "reader": ae2_inventory.READER, "items": dict(items),
               "fluids": dict(fluids), "essentia": dict(essentia),
               "placed": dict(placed), "dimensions": visited,
               "emc_knowledge": knowledge}
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    with open(args.out, "w") as fh:
        json.dump(payload, fh, indent=1, sort_keys=True)
    print("wrote %s: %d items, %d fluids, %d essentia aspects from %d cells; "
          "%d placed machine types; %d dimensions visited"
          % (args.out, len(items), len(fluids), len(essentia), stats["cells"], len(placed),
             len(visited)))
    if knowledge.get("players"):
        print("  EMC: %d items learned across %d player(s), %s EMC banked%s"
              % (len(knowledge["learned"]), knowledge["players"],
                 "{:,}".format(knowledge["emc"]),
                 " (full knowledge)" if knowledge["full"] else ""))
    else:
        # Said out loud rather than left to be inferred from an absent line. An empty EMC
        # section is indistinguishable from a save with no ProjectE, and #50 only pays off
        # when there is knowledge to read.
        print("  EMC: no playerdata read -- transmutation is not an available route")
    # Reconcile against the graph while both are in hand. A scan that writes 3,321 keys
    # looks like a success even when 320 of them name nothing any recipe uses, and that
    # silence is what let #21 sit unnoticed: the stock was there, the plans ignored it.
    graph_path, note = _coverage_graph(args.graph, args.out)
    if note:
        print("coverage: %s" % note)
    if graph_path is None:
        return 0
    from . import gaps
    print(gaps.stock_report(gaps.stock_coverage(Graph.load(graph_path), items,
                                                reader=ae2_inventory.READER)))
    return 0


def _world_dir(region_paths):
    """The save root, from any of its overworld region files, or None.

    `<save>/region/r.0.0.mca` -> `<save>`, which is where the per-dimension folders live.
    Derived rather than asked for as a flag: `have` is already given the region files and a
    second path that had to agree with them is a second thing to get wrong.
    """
    for path in region_paths:
        region = os.path.dirname(os.path.abspath(path))
        if os.path.basename(region) != "region":
            continue
        root = os.path.dirname(region)
        if os.path.isdir(root):
            return root
    return None


def _resolve_graph(graph_path, beside_path):
    """(an existing graph to read, every path tried) -- the graph is None when there is none.

    For the commands that use a graph OPTIONALLY, to enrich their output, rather than the
    ones `_load_graph` can exit for. It resolves; the caller words the failure, because what
    is lost differs: `have` loses the check that catches a stranded stock file, `metrics`
    loses English labels on a chart.

    A RELATIVE --graph IS ALSO TRIED BESIDE `beside_path`, and that is not a convenience.
    `--graph` is a GLOBAL defaulting to the relative "data/graph.json", while the documented
    way to read a server world is a container where data/ is mounted at /data and the working
    directory has no data/ at all, so the default resolves to nothing there and the optional
    step vanished with it (#92). The candidate carries the given BASENAME over rather than
    assuming "graph.json", since reading a differently named file than the one asked for is
    worse than reading none. An ABSOLUTE --graph is taken literally: the caller named a path,
    and second-guessing it would open a file they did not ask for.
    """
    tried = [graph_path]
    if os.path.exists(graph_path):
        return graph_path, tried
    beside = os.path.join(os.path.dirname(os.path.abspath(beside_path)),
                          os.path.basename(graph_path))
    if os.path.isabs(graph_path) or os.path.abspath(graph_path) == beside:
        return None, tried
    tried.append(beside)
    return (beside if os.path.exists(beside) else None), tried


def _coverage_graph(graph_path, out_path):
    """(graph for `have` to reconcile against, sentence to print first) -- either can be empty.

    A CHECK THAT EXISTS TO BREAK SILENCE MUST NOT FAIL SILENTLY. `stock_coverage` is what
    surfaces both a stale-schema stock file and #21-style keys no recipe uses, so a run that
    skips it while printing "wrote ...: 4599 items" looks like a complete success and is not
    (#92). Never return None without a sentence saying so.
    """
    found, tried = _resolve_graph(graph_path, out_path)
    if found == graph_path:
        return found, ""
    if found is not None:
        return found, ("no graph at %s, reading %s beside --out instead"
                       % (graph_path, found))
    return None, ("no graph at %s, so the stock was NOT reconciled against the graph -- a "
                  "stale stock file or keys no recipe uses would go unnoticed. Pass --graph "
                  "to check." % " or ".join(tried))


def _have_document(have_path):
    """A have file as a dict, or {} when there is not one.

    ONE reader for the raw document, because three different callers want three different
    slices of it -- stock, placed tile entities, ProjectE knowledge -- and each of them
    opening the file itself is three places to get the missing-file case wrong. Never
    raises: an absent stock file is an ordinary state that every caller here already treats
    as "an empty network".
    """
    if not have_path or not os.path.exists(have_path):
        return {}
    try:
        with open(have_path) as fh:
            doc = json.load(fh)
    except ValueError:
        return {}
    return doc if isinstance(doc, dict) else {}


def _placed_and_stock(have_path):
    """Placed tile entities and item stock from a have file, for evidence-based checks."""
    doc = _have_document(have_path)
    return doc.get("placed") or {}, doc.get("items") or {}


def _machine_states(graph, have_path, overrides_path):
    """(states, overrides, build targets) for the machine-gated commands.

    Goes through `describe` rather than `resolve` because the cost model needs the machine
    ITEM to price building it (#86), and `resolve` is the two-value view that drops it. The
    states are derived from the same call, so the states a plan is gated on and the items it
    prices cannot come from two different resolutions of the same question.
    """
    placed, stock = _placed_and_stock(have_path)
    overrides = machines.load_overrides(overrides_path)
    info = machines.describe(graph, placed, stock, overrides=overrides,
                             no_machine=machines.load_no_machine(overrides_path))
    states = {uid: (i["state"], i["why"]) for uid, i in info.items()}
    return states, overrides, machines.build_targets(info)


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
    # `explore.resolve_query`, not `names.resolve`: the latter reads `graph.names`, which is
    # items only, so `find fluid:water` could never match its own key. See #107.
    have, _stats, _craftables, _extra, _dims = _load_have(args.have)
    keys = explore_mod.resolve_query(g, args.query, have, limit=args.limit)
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
        return {}, {}, set(), {}, {}
    if not os.path.exists(path):
        # WARN AND CONTINUE, rather than either crashing or going quiet. `--have` defaults to
        # a path, so a checkout that has not run `have` yet made every `plan` die on a
        # traceback -- and #107 gave `find` the same default, which is how this surfaced.
        # Silence would be worse than the crash: a mistyped `--have` would plan against an
        # empty network and never say so, and "you still need everything" looks like an
        # answer. `server.State.load_all` has always guarded this; the CLI never did.
        print("no stock file at %s -- planning against an empty network" % path,
              file=sys.stderr)
        return {}, {}, set(), {}, {}
    doc = _have_document(path)
    have = dict(doc.get("items", {}))
    for name, amount in (doc.get("fluids") or {}).items():
        have[fluid_key(name)] = amount
    for aspect, amount in (doc.get("essentia") or {}).items():
        have[essentia_key(aspect)] = amount
    craftables = set(doc.get("craftables") or ())
    return (have, doc.get("stats", {}), craftables, doc.get("names") or {},
            doc.get("dimensions") or {})


def cmd_plan(args):
    from . import present
    from .solve import Solver

    g = _load_graph(args.graph)
    # Stock is loaded BEFORE resolution, not after, because it is a tie-break input: among
    # keys sharing a display name, a stack in the network is the pack telling you which one
    # you actually use. See explore._canonical_first and #101.
    have, _stats, craftables, extra_names, visited = _load_have(args.have)
    keys = explore_mod.resolve_query(g, args.item, have)
    if not keys:
        print("no item matched %r -- try `find`" % args.item, file=sys.stderr)
        return 1
    key = keys[0]
    if len(keys) > 1 and not args.exact:
        print("matched %s (%s); %d other candidates, use `find` to disambiguate"
              % (key, g.display(key), len(keys) - 1), file=sys.stderr)

    if extra_names:
        # A live dump knows names for items items.csv may predate.
        for k, v in extra_names.items():
            g.names.setdefault(k, v)
    if args.ignore_stock:
        have, craftables = {}, set()
    if args.ignore_craftable:
        craftables = set()
    states, machine_items = {}, {}
    if not args.ignore_machines:
        states, _ov, machine_items = _machine_states(g, args.have, args.machines)
    free = {} if args.ignore_sources else _free_sources(args.have, args.sources)
    # Resolved ONCE and handed to both the cost table and the solver. Two calls would be two
    # reads of data/tokens.json, and a plan whose prices disagreed with its own badges.
    token_kinds = _token_kinds(args)
    # Same rule as the tokens above: resolved once, so the price a route is ranked at and
    # the trip the plan reports are derived from one answer.
    gates = dimensions.gates_for(g, visited)
    # Same rule again: one answer, shared by the cost seed and the solver's terminator, so a
    # route priced as reachable through EMC cannot be one the plan then refuses to stop at.
    emc_available = projecte.available(g, _have_document(args.have).get("emc_knowledge"))
    costs = None
    if not args.no_cost:
        from . import cost as cost_mod
        # `craftables` goes to the cost table as well as to the Solver below, and the two
        # must be the same set: an item AE2 can autocraft terminates a branch, so pricing it
        # at its full subtree makes the ranker avoid a route the plan would have stopped at
        # immediately (#193). `--ignore-craftable` has already emptied the set by here, so
        # the flag turns it off for both at once.
        costs = cost_mod.estimate_cached(g, args.graph, have=have, machine_states=states,
                                         free_sources=free, machine_items=machine_items,
                                         token_kinds=token_kinds, dimension_gates=gates,
                                         emc_available=emc_available,
                                         craftables=craftables)
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
                    free_sources=free, token_kinds=token_kinds, pinned=pinned,
                    dimension_gates=gates, emc_available=emc_available)
    result = solver.solve(key, args.qty)
    if craftables:
        print("(%d items treated as satisfied because AE2 can autocraft them; "
              "--ignore-craftable to expand them)" % len(craftables), file=sys.stderr)

    for why in sorted((result.get("pins_overruled") or {}).values()):
        print(why, file=sys.stderr)
    print("== %s x%d ==" % (result["target_name"], result["qty"]))
    # Names WHICH budget ran out, the same distinction the web notice draws. Without it
    # "(TRUNCATED)" beside a node count far below `--max-nodes` reads as a bug in the tool,
    # and it is also what gives `work_budget` a consumer rather than leaving it a key only
    # `--json` ever dumps.
    if not result["truncated"]:
        cut = ""
    elif result["exhausted"]:
        cut = "  (TRUNCATED: work budget %s spent on %s attempts; raise --max-nodes)" % (
            "{:,}".format(result["work_budget"]), "{:,}".format(result["work"]))
    else:
        cut = "  (TRUNCATED: hit the node cap of %s; raise --max-nodes)" % (
            "{:,}".format(result["max_nodes"]))
    print("nodes: %d%s" % (result["nodes"], cut))
    print("\n-- you still need --")
    for row in result["shopping_list"][: args.limit]:
        # The terminal gets the same warning the HTML does. A shopping list is what someone
        # works from, and one of these lines being unbackable is exactly the thing they need
        # to know before setting off. See Graph.reachable_form and #136.
        print("  %14s  %s%s" % ("{:,}".format(row["qty"]), row["name"],
                                "  <- " + present.UNSOURCED_BADGE
                                if row.get("unsourced") else ""))
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

    if result.get("from_emc"):
        # Same argument as the sources block above: a route that costs no crafting still has
        # a quantity, and the EMC value is what makes the claim checkable rather than
        # something the reader has to take on trust.
        print("\n-- made by transmutation (ProjectE) --")
        for row in result["from_emc"][:15]:
            print("  %14s  %-34s EMC %s" % ("{:,}".format(row["qty"]), row["name"][:34],
                                            "{:,}".format(row.get("emc", 0))))

    if result.get("machines_to_build"):
        print("\n-- machines you do not have yet --")
        for m in result["machines_to_build"]:
            print("  %-38s %-12s %s" % (m["machine"][:38], m["state"], m["why"]))

    if args.json:
        with open(args.json, "w") as fh:
            json.dump(result, fh, indent=1)
        print("\nwrote %s" % args.json)
    if args.html:
        from . import iconset
        from .render import render_html

        with open(args.html, "w", encoding="utf-8") as fh:
            # `inline=True`: this file outlives the server and may be published as a
            # Claude Artifact, whose CSP blocks every off-host request. The atlas pages sit
            # beside the graph, which is where `build` copied them. See iconset.resolver.
            #
            # `standalone=True` for the same reason one level up: a browser opens this file
            # directly, so it is the caller that has to ask for the viewport meta the served
            # page gets from `server._wrap_fragment`. Without it a phone lays the page out at
            # 980px and shows the desktop layout zoomed out. See #138.
            #
            # `encoding="utf-8"` is what makes that document's `<meta charset>` true. The
            # default is the process locale, so a container with LANG unset writes ASCII and
            # dies on the ellipsis `graphview._shorten` puts in a truncated label.
            fh.write(render_html(result, g, standalone=True, icon=iconset.resolver(
                g, os.path.dirname(os.path.abspath(args.graph)), inline=True)))
        print("wrote %s" % args.html)
    return 0


def cmd_explore(args):
    from . import explore
    from . import present
    from . import iconset
    from .render import render_explore_html

    g = _load_graph(args.graph)
    have, _stats, craftables, extra_names, visited = _load_have(args.have)
    for k, v in (extra_names or {}).items():
        g.names.setdefault(k, v)

    hits = explore.search(g, args.query, have=have, limit=args.limit)
    results = hits.results
    if not results:
        print("no item name matched %r" % args.query, file=sys.stderr)
        if hits.hidden or hits.collapsed:
            # Distinguishes "that word is nowhere in the pack" from "every match is an NBT
            # variant nothing can make". Only the second is worth widening the query for.
            print(present.hidden_note(hits.hidden, hits.collapsed), file=sys.stderr)
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

    if hits.hidden or hits.collapsed:
        print("\n" + present.hidden_note(hits.hidden, hits.collapsed))

    payload = {"query": args.query, "results": results, "hidden": hits.hidden,
               "collapsed": hits.collapsed,
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
        with open(args.html, "w", encoding="utf-8") as fh:
            # `standalone=True` and `encoding="utf-8"` for the reasons `cmd_plan`'s writer
            # records: this file is opened straight from disk, and both writers had the same
            # defect. #138
            fh.write(render_explore_html(payload, note, standalone=True,
                                         icon=iconset.resolver(
                                             g, os.path.dirname(os.path.abspath(args.graph)),
                                             inline=True)))
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
            have[fluid_key(name)] = amount
        source, power, names = "save", None, None
    else:
        have, st, _craftables, names, _dims = _load_have(args.have)
        with open(args.have) as fh:
            doc = json.load(fh)
        source = doc.get("source", "save")
        power = doc.get("power")

    if not have:
        print("nothing to record", file=sys.stderr)
        return 1

    # Backfill labels from the graph so the charts read in English. Anchored on --db because
    # that is what this command writes, and metrics.db sits beside graph.json in data/.
    if not names:
        gpath, tried = _resolve_graph(args.graph, args.db)
        if gpath is None:
            # Same silence as #92, one command over: skipping this quietly ships a chart
            # labelled with raw item keys and no hint that a graph would have fixed it.
            print("no graph at %s, so the charts will show raw item keys rather than names"
                  % " or ".join(tried), file=sys.stderr)
        else:
            g = Graph.load(gpath)
            names = {k: g.display(k) for k in have if k in g.names}

    with metrics.open_db(args.db) as conn:
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

    with metrics.open_db(args.db) as conn:
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
            print("no quantity changed in the last %s (need >=2 snapshots apart)"
                  % args.window, file=sys.stderr)
            return 1

        # Every query the payload needs happens INSIDE the connection's scope; the rendering
        # below must not reach back for a lazy series after the db is closed.
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
        with open(args.html, "w", encoding="utf-8") as fh:
            # `standalone=True` and `encoding="utf-8"`, for the reasons `cmd_plan`'s writer
            # records. This page is written to a file and never served, so it had #138 with
            # nobody reporting it.
            fh.write(render_chart_html(payload, standalone=True))
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
    with metrics.open_db(args.db) as conn:
        print(json.dumps(metrics.stats(conn), indent=2))
    return 0


def _dump_newer_than_graph(graph_path, instance_dir):
    """True when a `/recipedump` has happened since this graph was built."""
    if not instance_dir:
        return False
    dump = os.path.join(dump_meta.dir_for(instance_dir), "recipes.ndjson")
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
    os.makedirs(os.path.dirname(graph_path) or ".", exist_ok=True)
    g = index.build(instance_dir, out_path=graph_path)
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

    states, overrides, _targets = _machine_states(g, args.have, args.file)
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
    # Through `_token_kinds` rather than letting `coverage` default, so the demotion counts it
    # reports are the ones a plan on this machine would actually use. See notproduction.
    print(json.dumps(index.coverage(g, _token_kinds(args)), indent=2))
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(prog="recipegraph")
    ap.add_argument("--graph", default=DEFAULT_GRAPH)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("build", help="extract recipes into a graph")
    p.add_argument("--instance", required=True, help="the pack's minecraft/ dir")
    p.add_argument("--hei", help="path to recipes.ndjson from the dump mod")
    # `--hei` alone is a TRAP for a preserved dump: it redirects recipes.ndjson while
    # names/oredict/catalysts keep coming from the canonical directory, mixing two dumps
    # into one graph without saying so. Preserving a dump is required by #80's churn proof,
    # since a second /recipedump rewrites the directory in place, so this has to exist.
    p.add_argument("--dump-dir", help="the mc-recipe-dump/ dir to read, if not the one "
                                      "inside --instance (e.g. a preserved mc-recipe-dump.run1)")
    p.add_argument("--out", default=DEFAULT_GRAPH)
    p.add_argument("--no-guess", action="store_true",
                   help="disable heuristic oredict inference")
    # `--allow-mod-set-change`, spelled from `dump_meta.OVERRIDE_FLAG` so the refusal that
    # tells you to type it and the parser that accepts it cannot disagree. The literal is
    # here in prose because a reader greps for the flag, not for the constant.
    #
    # #194: `build` refuses when the dump names a different jar set than the graph it is
    # about to replace. This is the way past it, and it is a flag rather than a prompt
    # because the graph outlives the terminal -- a shell history can answer "who replaced
    # the full-pack graph with a six-mod one" months later, and a y/n cannot.
    p.add_argument(dump_meta.OVERRIDE_FLAG, action="store_true",
                   help="overwrite a graph that was built from a different set of mods")
    p.set_defaults(fn=cmd_build)

    p = sub.add_parser("have", help="read AE2 network contents from a world save")
    p.add_argument("--regions", nargs="+", required=True)
    p.add_argument("--out", default=DEFAULT_HAVE)
    p.set_defaults(fn=cmd_have)

    p = sub.add_parser("find", help="look up item ids by name")
    p.add_argument("query")
    p.add_argument("--limit", type=int, default=20)
    # Stock ranks the results, so `find` needs the same file `plan` reads or the two
    # commands disagree about which of six items called "Iron Plate" you meant.
    p.add_argument("--have", default=DEFAULT_HAVE)
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
    p.add_argument("--max-nodes", type=int, default=DEFAULT_MAX_NODES)
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
    p.add_argument("--dump-dir", default=dump_meta.DIR_NAME,
                   help="the %s/ dir written by /recipedump" % dump_meta.DIR_NAME)
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
    try:
        return args.fn(args)
    except dump_meta.RefusedBuild as e:
        # CAUGHT ONCE HERE RATHER THAN AT EACH `index.build` CALL, of which there are two
        # (`build`, and `ensure_graph`'s rebuild behind `serve` and `plan`). A per-caller
        # catch is the arrangement where the third caller forgets and gets a traceback --
        # and a traceback is a stack, not an instruction, so the one reader who most needs
        # to be told to re-run /recipedump is the one shown a frame list instead.
        print("refusing to build a graph: %s" % e, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
