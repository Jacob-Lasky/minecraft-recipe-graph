# minecraft-recipe-graph

Offline crafting-tree planner for heavy Minecraft **1.12.2** modpacks, pruned against
what your **AE2 network already holds**.

Developed and verified against **MeatballCraft (Dimensional Ascension)** — 366 mods — but
nothing in it is pack-specific: the recipe data comes from your own instance and the
inventory from your own world save.

You ask for an item. It walks the entire recipe chain, stops wherever your **AE2
network already has the ingredient**, and hands you the actual shopping list — instead
of you clicking through JEI one hop at a time and losing your place.

```
$ recipegraph plan "Ultimate Crafting Table" --have data/ae2_have.json

== Ultimate Crafting Table x1 ==
-- you still need --
           265  Black Dye
             1  Block of Emerald
-- drawn from your AE2 stock --
           377  Iron Ingot
           112  Glowstone Dust
           112  Redstone
            ...
```

Plus an HTML view: collapsible tree, per-node "in stock / craft / NEED" badges, and a
"show only what I need" filter.

## Quickstart

There is nothing to install. Clone it and run it with the Python you already have:

```bash
git clone https://github.com/Jacob-Lasky/minecraft-recipe-graph
cd minecraft-recipe-graph
python3 -m recipegraph --help
```

Every example below is written as `recipegraph <verb>`, which is also the name the tool
reports in its own `--help` and error messages. To make that real, alias it:

```bash
alias recipegraph="python3 -m recipegraph"        # add to ~/.bashrc or ~/.zshrc
```

Without the alias, put `python3 -m` in front of any command in this README.

You cannot do anything useful until you have built a graph from your own instance, because
no recipe data ships with the repo. Shortest path from a clone to a plan:

```bash
recipegraph build --instance '/path/to/instance/minecraft' --out data/graph.json
recipegraph plan "Iron Ingot" --qty 64
```

`build` alone gives you every recipe the mod jars declare. Machine recipes, NBT variants and
localized names need a `/recipedump` from a running game first, which is what
[the dump mod](#the-dump-mod) is for. `have` is optional and only prunes the plan against
your AE2 network.

## Why this exists

[Just Enough Calculation](https://www.curseforge.com/minecraft/mc-mods/just-enough-calculation)
already does the flatten-to-raw-ingredients math and ships with MeatballCraft — but you
have to import every recipe in the chain by hand, one click per hop. A seven-deep
chemistry chain is a lot of clicking, and it does not know what is in your ME system.
[Krutoy242's CraftTreeVisualizer](https://github.com/Krutoy242/CraftTreeVisualizer) is a
beautiful renderer, but it reads a hand-curated JEC `groups.json`.

This tool takes the other approach: dump the recipe registry once, read the AE2 network
off the world save, and compute the tree automatically.

## How it works

Three stages, each usable alone.

### 1. What you have — read AE2 straight out of the world save

```bash
recipegraph have --regions '/path/to/world/region/r.*.mca' --out data/ae2_have.json
```

A pure-stdlib Anvil + NBT reader finds every AE2/NAE2/ExtraCells storage cell inside
drives, ME chests, IO ports and cell workbenches, and aggregates the contents. Nothing
to build in game, no mod required, read-only — safe against a live server.

On the reference network this reads **3,321 distinct items across 467 cells in 195
holders** in about a minute.

A stack whose NBT decides what it is (a bee's species, a potion's effect, a vis pod's
aspect) gets the same `#digest` suffix the dump mod puts on it, so it matches the recipes
that use it. If `data/graph.json` is present, the scan finishes by reporting how much of
your stock the graph cannot see and why — that number was 320 keys and 1.5 million items
before the reader learned to compute the digest.

Counts come from the cell's `Cnt` tag, *not* the ItemStack `Count` byte, which is capped
at 127 and meaningless for cell contents.

**Optional live feed.** `tools/ae2_dump.lua` is an OpenComputers script that exports the
same file from the running network instead of the save. It's better data — it sees storage
buses and external inventories, and `getCraftables()` tells the planner what AE2 can
already autocraft, which becomes a stopping condition so the tree doesn't expand branches
your ME system would just make for you. Costs you an OC computer and an Adapter block
touching an ME Controller.

### 2. What things cost — build the recipe graph

```bash
recipegraph build --instance '/path/to/instance/minecraft' --out data/graph.json
```

Two recipe sources:

| Source | Coverage | Needs |
| --- | --- | --- |
| `jar_json` | ~10,300 crafting-table recipes from `assets/*/recipes/*.json` in every mod jar, including Forge `_constants.json` `#name` references (38 jars use them) | nothing |
| `hei_dump` | **everything** — machine recipes, NuclearCraft chemistry, Modular Machinery, inscribers, centrifuges | the `/recipedump` mod in `mod/` |

**The offline source alone is not enough for machine chains.** 1.12.2 furnace recipes
are registered in code, not JSON, and machine recipes never touch `assets/*/recipes/`.
So `Borax` — a NuclearCraft chemistry product — shows up with no recipe until you run
the dump. That gap is the whole reason `mod/` exists.

### 3. What to do — solve the tree

```bash
recipegraph plan "Borax" --qty 64 --have data/ae2_have.json --html plan.html
```

The solver handles the three things that make this harder than it looks:

- **Cycles.** Recipe graphs are not DAGs (`ingot → block → 9 ingots`). Cycles are
  guarded per path, and the solver *backtracks* out of a cycling recipe and tries the
  next-best one, so an uncrafting recipe never gets chosen over a real production route.
- **Recipe choice.** Items have many recipes, and the best one depends on what you own,
  so choice happens at solve time — ranked by how much of the recipe your stock already
  covers. When you disagree, **pin** one: open the recipe count on any craft step, compare
  the candidates side by side (category, machine availability, estimated cost) and choose.
  The ranking keeps suggesting; the pin outranks it until you take it off.

  Pins survive a redump. A recipe id is `hei:<category>:<line number>` and a redump
  renumbers every one of them, so a pin is stored by a fingerprint of what the recipe *is*
  — its category, outputs and inputs. If the pack changes that recipe the fingerprint stops
  matching and the pin falls back to the category ("make it in the Alloy Smelter"), saying
  so rather than reverting in silence. Kept in `data/recipes.json`; `recipegraph pins`
  lists them.
- **Stock is consumed, not just checked.** The inventory pool is drawn down as the tree
  is built, so two branches cannot both claim the same 5 redstone.

### Explore — search before you plan

```bash
recipegraph explore "ultimate component" --html explore.html
```

For each match: how much is in your network, every recipe that makes it (with per-input
stock badges, so you can see at a glance which ingredient is the blocker), everything that
consumes it, and which ore dictionaries it belongs to. Ore chips marked `?` were inferred
rather than read from the game.

Answers the question that actually comes first — *which* of the twelve things called
"Ultimate something" did I mean, and is it even craftable in this pack.

### Track — Factorio-style production graphs

```bash
recipegraph track                      # record one snapshot (cron this)
recipegraph chart --window 2h --html chart.html
```

Stock levels over time, net per-minute rates, and ME network power. Charts are Canvas,
theme-aware, with clickable legends.

**These are net rates, and that limit is real.** AE2 reports stock *levels*, not machine
throughput, so production and consumption cannot be separated the way Factorio does it —
Factorio instruments every machine; we only see the warehouse. An item produced and
consumed at the same speed reads as a flat line. Network power is the exception: AE2
publishes its own rolling averages, so `avg use` / `avg injected` are true rates (live
feed only).

Storage is bounded two ways: a row is written only when a quantity actually **changes**
(most of a big network is inert), and samples live in tiered buckets — 1 min kept 2 h,
10 min kept 2 d, 1 h kept 60 d. Measured on a 3,270-item network with ~150 items moving
per minute: **2.3 MB per 2 hours** at 1-minute cadence, and the fine tier reaches steady
state rather than growing. Pruning deliberately keeps one "carry" row per item just
before each horizon — without it an item that last changed before the window would become
unknowable rather than merely coarse.

## A local UI

```bash
recipegraph serve            # http://127.0.0.1:8765
```

Search for an item, click it, get the plan. Also a Machines page where availability can be
toggled per category with one click, a Sources page for the resources a generator makes
free, and a Coverage page. Stdlib `http.server` only, no Flask and no pip.

**This is the only step after a dump.** `serve` builds the graph itself when there isn't
one or the dump is newer, and the graph remembers the instance it came from, so step 2
above is only needed when you want to control how it is built (`--no-guess`, a different
`--out`) or to see the coverage report as it runs.

Bound to **127.0.0.1** deliberately: the graph exposes the contents of a live base and
there is no authentication, so widening it to `--host 0.0.0.0` has to be an explicit
choice. The graph is loaded once at startup (a few seconds for 121k recipes) and held in
memory, which is why this is a long-running server rather than a per-request script.

Pages reuse the same renderers as the CLI's `--html` flag, so there is one implementation
of each view rather than a UI and an API drifting apart.

## Running the UI on a server

The UI is worth keeping up when the machine that plays Minecraft is off, so there is a
`Dockerfile`. It is a plain `python:3.14-slim-trixie` with the package copied in and no
install step, because the tool is stdlib only.

```bash
docker build -t recipegraph:latest \
  --build-arg RECIPEGRAPH_VERSION="$(git describe --tags --always --dirty)" \
  --build-arg RECIPEGRAPH_BUILD_DATE="$(git log -1 --format=%cd --date=short)" .

docker run -d --name recipegraph \
  -p 8765:8765 \
  -v /srv/minecraft-recipe-graph/data:/data \
  --user 99:100 \
  --memory=4g --memory-swap=4g \
  --restart unless-stopped \
  recipegraph:latest
```

Everything mutable lives in the one `/data` mount: `graph.json`, `ae2_have.json`, the
machine and free-source overrides, the metrics DB and the cost cache. Nothing
Minecraft-related needs to exist on the server.

**The container binds `0.0.0.0` and that is not a relaxation of the rule above.** Binding
the loopback default inside a container would make the server unreachable through its own
published port, so the boundary moves outward to the port publish. There is still no
authentication and the graph still exposes a live base's contents, so publish it to a LAN
or to `127.0.0.1`, never to a public interface.

`--user 99:100` is for UnRAID hosts, whose array shares expect `nobody:users`; drop or
change it elsewhere. The health check allows a 180 second start period because loading a
115 MB graph takes 40 to 90 seconds, and a shorter one restarts the container forever
while it is doing exactly what it should.

### Feeding it from the machine that plays

Build on the box that has the pack, ship the result:

```bash
# on the gaming machine, after /recipedump
recipegraph build --instance '<instance>/minecraft' --out data/graph.json
recipegraph have  --regions '<world>/region/r.*.mca' --out data/ae2_have.json

rsync -avz --partial data/graph.json data/ae2_have.json \
      server:/srv/minecraft-recipe-graph/data/
```

`build` reads the ~410 mod jars and a ~165 MB `recipes.ndjson`, so it belongs where those
already are. Shipping the built graph moves ~115 MB instead of several gigabytes of jars,
and the server needs no copy of the pack.

The server notices the file changed and says so; the **Reload** button picks it up without
a restart. That button re-reads the graph and the stock file, it does not re-import Python,
so a code change still needs a new image.

### Checking it on a phone

The UI gets used on a phone, and phone bugs do not show up in a desktop browser window
made narrow: sticky `:hover`, `[hidden]` losing to a `display` rule, a flex item refusing
to shrink below an unbreakable registry id. `tools/mobile-audit.js` drives a real browser
at 390px against a running server and fails loudly on all three.

```bash
corepack enable pnpm     # once; Node ships corepack through 24
pnpm install             # playwright, from the committed lockfile
pnpm run browsers        # the chromium build that playwright pins
pnpm run audit:mobile  http://127.0.0.1:8765   # phone layout
pnpm run audit:filters http://127.0.0.1:8765   # the /machines filters, which are JS only
python3 tools/cost-probe.py                    # what a cost-model constant reroutes
```

**This is dev tooling only.** The tool itself is Python 3 stdlib with no install step, the
container image copies `recipegraph/` and nothing else, and CI stays stdlib-only. The
`package.json` exists so the audit's one dependency is pinned and reproducible rather than
a version someone happened to have.

pnpm's version lives in `packageManager` in `package.json`, in exactly one place, and
corepack reads it. Use `pnpm install --frozen-lockfile` when you want the lockfile to be
authoritative rather than updated.

It exercises **interactive** state as well as loading each page, because that is where the
misses have been: an audit that only navigated to every page passed cleanly while every
search result was rendering with its name squeezed to zero width.

## The dump mod

`mod/` is a client-side Forge mod adding one command, `/recipedump`. The dump is spread
across client ticks with a ~15 ms per-tick budget, so the game stays playable and progress
messages actually appear while it runs — a second of work inside a command handler is a
second the render loop never gets, and printing a warning first does not help because chat
draws on the next frame. It walks
JEI's `IRecipeRegistry` and writes `recipes.ndjson`, `oredict.json` and `names.json`
into `<gamedir>/mc-recipe-dump/`.

It also writes `nbt_trace.json` by default: a per-top-level-tag digest of every key that
carries identifying NBT, in two flavours per tag — lists in order, and lists sorted.
`/recipedump notrace` skips it. That is a diagnostic for [#80](https://github.com/Jacob-Lasky/minecraft-recipe-graph/issues/80),
where the digest moves between two dumps of an unchanged pack for ~11,353 keys, so one item
ends up wearing two keys and a Tinkers tool in the ME system stops matching the recipe that
consumes it. Nothing in `recipegraph build` reads the file, but it is on by default anyway: it cannot be
reconstructed after the fact, and proving churn needs TWO dumps carrying it, since the effect
only appears between JVM runs. Opt-in would make two comparable dumps in a row the unlikely
case. On the reference pack it is 34.3 MB against a 245 MB dump. Read it with:

```bash
python3 tools/digest-churn.py <dump-dir>                # suspect tags, from ONE dump
python3 tools/digest-churn.py <old-dump> <new-dump>      # which tag actually moved
```

Churn is a between-JVM-run effect, so *proving* it needs two dumps from two separate launches;
the one-dump mode names the tags that are even capable of it, which needs only one.

It uses **only the public `mezz.jei.api` surface** — `getRecipeCategories()`,
`getRecipeWrappers()`, `IRecipeWrapper.getIngredients()` — every signature verified
against `HadEnoughItems_1.12.2-4.28.1.jar`. That is deliberate: the older
[JEIExporter](https://github.com/way2muchnoise/JEIExporter) reflected into JEI internals
and rendered recipe GUIs to scrape them, which is both slow and broken across the JEI
4.8 → HEI 4.28 gap.

**A prebuilt jar ships in `dist/`**, so you do not have to build it to try this:

```bash
cp dist/mc-recipe-dump-0.7.0.jar '/path/to/instance/minecraft/mods/'
```

It is the reobfuscated release build, and `tests/test_dist_jar.py` asserts it agrees with the
source in `mod/` on both the dump schema and the version, so it cannot quietly fall behind
the Python side that reads its output. If that test fails, rebuild and re-commit the jar
rather than editing the expected numbers.

**Status: builds clean**, and you do NOT need a system JDK 8 — Gradle provisions its own
Java 8 toolchain while running on a modern JDK:

```bash
cd mod && ./gradlew build -Phei_jar=/path/to/HadEnoughItems_1.12.2-4.28.1.jar
```

It deliberately does **not** use ForgeGradle 2.3, the plugin every 1.12.2 tutorial names:
Forge's maven no longer publishes the `userdev` artifact FG2 requires, only the FG3-format
`userdev3`. See [docs/BUILD.md](docs/BUILD.md) for the evidence and the reobfuscation
check.

### Machines — route through what you actually have

```bash
recipegraph machines --match nuclearcraft        # see what you have
recipegraph machines --set nuclearcraft_crystallizer=have   # toggle by hand
```

Every recipe carries its JEI category, and a category is a machine — so machine
availability is a constraint on recipe choice rather than a guess. Three states: `have`
(placed in the world, or the item in stock), `buildable` (the machine is craftable),
`unavailable`. Placed machines are read out of the world save during `have`, so this is
evidence rather than configuration; manual overrides always win.

Plans then report what you must build first:

```
-- machines you do not have yet --
  Chemical Reactor    buildable    craftable: nuclearcraft:chemical_reactor_idle
  Crystallizer        buildable    craftable: nuclearcraft:crystallizer_idle
```

### Gaps — what the dump could not read

```bash
recipegraph gaps --dump-dir <instance>/minecraft/mc-recipe-dump
```

`/recipedump` guards every category and every recipe individually, because third-party
recipe wrappers genuinely throw. v0.2.0+ records each failure to `skipped.ndjson` with the
category, mod, wrapper class and exception, plus per-category tallies in `summary.json`.

`gaps` turns that into an answer to *does this matter*. A category that dumped **zero**
recipes is a total blind spot — an entire machine type missing, so anything made only there
looks uncraftable. That is ranked first and separately. Partially-covered categories are
ranked by the **share** lost, not the raw count, so losing 30 of 70 Thaumcraft infusions
outranks losing 6 of 39,000 vanilla crafting recipes.

## Ore dictionary

Real membership comes from the dump mod's `oredict.json`. Without it, the builder falls
back to **inferring** membership from display names (`ingotIron` → "Iron Ingot"), which
recovers 233 of 548 referenced entries on the reference pack. That fallback is a
labelled heuristic, never presented as ground truth — `recipegraph build --no-guess`
disables it. `/ct oredict` in game is the other exact source.

## Requirements

Python 3.8+, standard library only. No pip install, no third-party packages — so the
same code runs inside a server container where `pip` is unavailable.

## Status / known limits

- Machine recipes require the dump mod (see above).
- **Fluid amounts are always in mB** (1000 mB = 1 bucket), never auto-converted to
  buckets, because recipes are authored in mB and rounding to buckets would misreport any
  recipe using a partial bucket.
- Fluid *conversion* chains are shallow: a filled bucket
  (`forge:bucketfilled` + NBT) is still a different key from the raw fluid, so
  bucket-based and fluid-based routes for one material do not yet unify.
- Essentia cells are detected but not yet aggregated.
- The AE2 reader sees cells in drives and chests. Items parked in ME Interfaces or in
  external inventories behind a storage bus are not counted.
- The tree is capped (`--max-nodes`, default 4000) and says so when it truncates,
  naming which of the two budgets ran out. The web page offers a **Go deeper**
  control instead of the flag, doubling the cap up to 4x.

## Licence

MIT. Not affiliated with the MeatballCraft team; issues and PRs welcome.
