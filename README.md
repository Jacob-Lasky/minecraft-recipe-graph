# minecraft-recipe-graph

Offline crafting-tree planner for heavy Minecraft **1.12.2** modpacks, pruned against
what your **AE2 network already holds**.

Developed and verified against **MeatballCraft (Dimensional Ascension)** — 366 mods — but
nothing in it is pack-specific: the recipe data comes from your own instance and the
inventory from your own world save.

You ask for an item. It walks the entire recipe chain, stops wherever your **AE2
network already has the ingredient**, and hands you the actual shopping list — instead
of you clicking through JEI one hop at a time and losing your place.

It also stops at an **ore**, because an ore is something you go and mine rather than
something you craft. The pack's own `ore*` oredict registration is the signal, so the
shopping list says "4 Sednanite Ore" rather than walking the nugget/ingot ladder down to
its smallest denomination.

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

There are three moving pieces in total — this tool, that mod, and the data between them —
and they update independently. [What ships where](#what-ships-where) is the map.

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

It also records **which dimensions the save has terrain for**, which is how a plan knows
whether an ore is somewhere you can actually reach. Entering a dimension generates it, so
a `DIM<id>/region/` directory holding `.mca` files is the evidence — no portal to find, and
it reads the same for the Nether, the End, a mod dimension and an Advanced Rocketry planet.
The reference save has been to 14 and to none of the 14 planets, which is why Sednanite Ore
is priced as a trip rather than as a cobblestone. See "Dimensions" below.

A stack whose NBT decides what it is (a bee's species, a potion's effect, a vis pod's
aspect) gets the same `#digest` suffix the dump mod puts on it, so it matches the recipes
that use it. The scan finishes by reporting how much of your stock the graph cannot see and
why — that number was 320 keys and 1.5 million items before the reader learned to compute
the digest. It looks for the graph at `--graph`, and, when that is a relative path that does
not resolve, beside `--out` as well, so reading a world from inside the container works
without an extra flag. If it finds no graph either way it says so rather than skipping the
reconciliation quietly, because a scan that reconciled nothing and a scan that reconciled
cleanly otherwise look identical.

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
- **Progress is priced.** The pack expresses "you have not unlocked this yet" as a
  placeholder item in the recipe, and there are eleven of them. They used to cost what a
  cobblestone costs, so a route behind a locked quest chapter was always at least as cheap
  as the ungated one beside it. A gate now outweighs any machine, so an ungated route wins
  whenever one exists — while staying finite, so a gated route is still chosen when it is
  the only one there is. Same for "go and kill a boss", priced lower than a lock and higher
  than picking something up.

  Only gates the pack states **as an item** are priced. A dimension you have to travel to
  is not one: travelling is not a recipe, so the graph cannot see it, and a plan will
  still route you to another planet without mentioning the trip.

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

**`find` and `plan` both take a raw key as well as a name**, in any namespace:

```bash
recipegraph plan fluid:nethengeic_fluid --qty 1000   # a fluid, by key
recipegraph find ore:ingotIron                       # an oredict entry
recipegraph plan "strong mythic essence"             # or by name
```

An exact key wins outright, so it can never lose to something that merely reads like it.
A name is ranked the way the web UI ranks it — exact before prefix before substring, ties
broken on what you hold and on how connected the item is — so asking for a fluid by name
gets you the fluid rather than the can it is bottled in.

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
choice. The graph is loaded once at startup (a few seconds for ~124k recipes) and held in
memory, which is why this is a long-running server rather than a per-request script.

## Asking the graph questions

Loading the graph costs about **4.4 seconds** and 115 MB off disk. The running server already
has it and answers in **0.048**, so anything more curious than a plan should be a `curl` at
the server rather than a script that loads the file again. Everything here is read-only.

```bash
curl -s localhost:8765/api | jq                       # the endpoints, fields and functions
curl -s --get --data-urlencode 'key=fluid:nethengeic_fluid' localhost:8765/api/key
curl -s 'localhost:8765/api/keys?match=sednanite&limit=0'
curl -s --get --data-urlencode 'rid=some.recipe.id' localhost:8765/api/recipe
curl -s 'localhost:8765/api/cost?category=tconstruct.smeltery'
curl -s 'localhost:8765/plan?item=minecraft:piston&qty=8&fmt=json' | jq .tree
```

`/api/keys` matches labels and keys with no ranking and no cap, which is what `/suggest`
cannot be: that one ranks and stops at 25 rows because it answers a keystroke, and reading a
ranked capped answer as a census is how a measurement comes back wrong.

The one that replaces a script is **`/api/sweep`**, which takes a predicate:

```bash
curl -s --get localhost:8765/api/sweep \
  --data-urlencode 'where=endswith(label, "Nugget") and producers == 0' \
  --data-urlencode 'select=key,label,consumers' --data-urlencode 'limit=0'
```

Fields are `key label name kind mod stock producers all_producers consumers cost live ores`,
combined with `and or not`, the six comparisons, and
`startswith endswith contains matches lower upper len`. `GET /api` prints the list, so it
does not have to be remembered. A `limit` of 0 lifts the cap, `matched` always reports the
true total, and `order=-cost` sorts descending.

It is a small parser rather than `eval` on purpose. The container deployment below is
LAN-only with no authentication and mounts `/data` read-write, so "on the LAN" is the only
access control there is; the grammar has no attribute access, no indexing and no callable
surface beyond that fixed list of functions, which makes read-only a property of the code
rather than an intention.

## What ships where

Three pieces, and they do not all live on the same machine or update on the same schedule.
Getting this wrong is the most common way the tool goes quietly wrong rather than loudly
wrong, so it is worth reading once.

| Piece | What it is | Where it goes | Version |
| --- | --- | --- | --- |
| **The tool** | `recipegraph/` — the CLI, the renderers and the web server, one Python package | anywhere with Python 3.8+; on a server, the Docker image below | git commit, printed in the page footer |
| **The dump mod** | `mod/` — a client-side Forge jar adding `/recipedump` | the **client's** `mods/`, on the machine that plays | prebuilt as `dist/mc-recipe-dump-0.9.0.jar` |
| **The data** | `mc-recipe-dump/` from the mod, then `graph.json` and `ae2_have.json` built from it | the `/data` mount the tool reads | a `schema` number, recorded in every file and checked on read |

**The web UI is not a separate piece.** Pages are server-rendered by the same renderers the
CLI's `--html` flag uses, so there is no frontend to deploy and no API contract between the
two halves to keep in step: one image contains the lot. There *is* client-side JavaScript —
the collapsible tree, the `/machines` filters, the production chart, the search box — but it
is emitted inline by the Python module that renders each page, so there is no bundle, no
asset pipeline and no build step. Where the JS and the server-rendered HTML have to agree on
something, the value is injected from the one Python constant rather than restated, so they
cannot drift apart. The JSON endpoints under `/api` are not a second implementation of the
pages either: they wrap the same `explore` and `cost` functions the pages render, and they
exist for people and scripts asking the graph questions, not for the browser. See "Asking the
graph questions" above.

**The data is what couples the other two, and it is the only piece with a version they both
agree to check.** The mod stamps the schema it wrote; the tool compares that against the
schema it understands and against the schema at which the digest format last changed. So
the failure mode is not a crash, it is a disagreement about identity: upgrade the jar
without rebuilding, and every NBT-bearing key in the graph is one the current reader never
computes, which makes AE2 stock read as zero rather than as an error. Both severities are
reported on the page footer and by `have`; the current upgrade order is in
[the dump mod](#the-dump-mod).

**Only the machine that plays can produce the data.** A server has no game to run
`/recipedump` in and no reason to hold 410 mod jars, which is what
[Feeding it from the machine that plays](#feeding-it-from-the-machine-that-plays) is about.
A jar committed to `dist/` is therefore *published*, not *installed* — the file changing
here does nothing until someone copies it into a client.

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

`mod/` is a client-side Forge mod adding one command, `/recipedump`. It replies
`JEI runtime not available yet — open the recipe GUI once, then retry` if JEI has not handed
its runtime over, which happens on a fresh launch and reads as the mod being broken; open a
recipe GUI once and run it again. The dump is spread
across client ticks with a ~15 ms per-tick budget, so the game stays playable and progress
messages actually appear while it runs — a second of work inside a command handler is a
second the render loop never gets, and printing a warning first does not help because chat
draws on the next frame. It walks
JEI's `IRecipeRegistry` and writes `recipes.ndjson`, `oredict.json` and `names.json`
into `<gamedir>/mc-recipe-dump/`.

It then walks JEI's complete item list — a different population, since an item nothing
crafts and nothing consumes never appears in a recipe — and writes three more files.
`damageable.json` says which items use their metadata as durability, which is the only
sound way to tell 46 damage values of one Iron Axe from 9 genuinely distinct
`chisel:lapis` blocks ([#118](https://github.com/Jacob-Lasky/minecraft-recipe-graph/issues/118)).
`emc.json` carries each item's ProjectE EMC value, so a drop-only item stops dead-ending on
its loot token ([#50](https://github.com/Jacob-Lasky/minecraft-recipe-graph/issues/50)).
`machine_names.json` carries Modular Machinery's own machine registry and the machine each
blueprint builds, so a plan can say *which* of the 261 items called "Machine Blueprint" it
means ([#55](https://github.com/Jacob-Lasky/minecraft-recipe-graph/issues/55)). All three
are absent, without complaint, on a pack that does not run the mod they read.

Last, and only after every other file is closed, it renders every item to a 16×16 sprite and
writes an `icons-N.png` atlas plus `icons.json`
([#36](https://github.com/Jacob-Lasky/minecraft-recipe-graph/issues/36)). `/recipedump
noicons` skips it. It runs last because it is the one phase that can plausibly fail —
rendering tens of thousands of arbitrary modded stacks offscreen touches code paths never
written to run outside a GUI frame — and the irreplaceable thing here is the launch of the
game, not the pictures. It reports rendered/blank/threw counts in chat, so the launch itself
says whether it worked.

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

Churn is a between-JVM-run effect, so it takes two dumps from two separate launches. The
one-dump mode only narrows the field, and it is weak in both directions — measured, a tag can
read as "cleared" and still churn (5,003 such keys), and the tag it ranked first did not churn
at all. Draw conclusions from the two-dump run.

**Both dumps must come from the same dump schema.** Pairing a schema-4 dump with a schema-3
one shows churn on essentially everything, because the digest format itself changed between
them — so that comparison cannot answer whether a fix worked, in either direction. And since
the dump rewrites each file in `<gamedir>/mc-recipe-dump/` in place, move the first run's
directory aside before launching again or it is overwritten; `nbt_trace.json` in particular
cannot be reconstructed afterwards, because the NBT it describes only exists in a running JVM.

To then build from a dump you preserved under another name, use `--dump-dir`, **not `--hei`**:

```bash
recipegraph build --instance '<instance>/minecraft' \
                  --dump-dir '<instance>/minecraft/mc-recipe-dump.run1' --out data/graph.json
```

`--hei` redirects `recipes.ndjson` alone, so `names.json`, `oredict.json`, `catalysts.json`
and the schema stamp would still be read from the canonical directory — mixing two dumps into
one graph, with nothing in the output saying so. `--dump-dir` moves all five together.

On the reference pack that run answered #80: `Special` churned on 10,010 items and was
**order-only every time**, so sorting that one tag fixes it, while `ench` churned on 2,423 with
no order component at all — which is [#63](https://github.com/Jacob-Lasky/minecraft-recipe-graph/issues/63)'s
tag, and a different cause needing a different fix.

It uses **only the public `mezz.jei.api` surface** — `getRecipeCategories()`,
`getRecipeWrappers()`, `IRecipeWrapper.getIngredients()` — every signature verified
against `HadEnoughItems_1.12.2-4.28.1.jar`. That is deliberate: the older
[JEIExporter](https://github.com/way2muchnoise/JEIExporter) reflected into JEI internals
and rendered recipe GUIs to scrape them, which is both slow and broken across the JEI
4.8 → HEI 4.28 gap.

**A prebuilt jar ships in `dist/`**, so you do not have to build it to try this:

```bash
cp dist/mc-recipe-dump-0.9.0.jar '/path/to/instance/minecraft/mods/'
```

It is the reobfuscated release build, and `tests/test_dist_jar.py` asserts it agrees with the
source in `mod/` on the dump schema, the version, and a SHA-256 over the whole Java tree, so
it cannot quietly fall behind the Python side that reads its output. If that test fails,
rebuild and re-commit the jar rather than editing the expected numbers.

**Only one jar may be installed at a time.** Every version declares modid `mcrecipedump`, so
two in `mods/` is a startup failure rather than a newest-wins. Move the old one out.

**This jar writes dump schema 5.** Schema 5 adds files and renames one summary field; it does
NOT change how an NBT-bearing stack is digested, so a schema-4 graph's keys are still the keys
this reader computes and AE2 stock still matches. Upgrading from 4 costs a `/recipedump` and a
`recipegraph build` to pick up the new files, and no re-run of `have`.

**A schema-3 graph is a different matter and is not compatible.** The digest that identifies
an NBT-bearing stack changed at schema 4 (see `SORTED_LIST_TAGS` and `COSMETIC_TAGS` in
`DumpCommand.java`), so upgrading from 3 means `/recipedump`, then `recipegraph build`, then
`recipegraph have` again — in that order. Skipping the last step leaves AE2 stock filed under
keys the new graph does not use, and `have` says so rather than letting it pass.

**Status: builds clean**, and you do NOT need a system JDK 8 — Gradle provisions its own
Java 8 toolchain while running on a modern JDK:

```bash
cd mod && ./gradlew build -Phei_jar=/path/to/HadEnoughItems_1.12.2-4.28.1.jar
```

It deliberately does **not** use ForgeGradle 2.3, the plugin every 1.12.2 tutorial names:
Forge's maven no longer publishes the `userdev` artifact FG2 requires, only the FG3-format
`userdev3`. See [docs/BUILD.md](docs/BUILD.md) for the evidence and the reobfuscation
check.

### Screenshotting a GUI without launching the game

The mod is growing an in-game planner UI, and a GUI change you cannot see is a GUI change you
cannot review. `harness/` runs the client **headlessly** in a container (Xvfb plus mesa's
llvmpipe, a small dev mod set rather than the whole pack), opens one named screen, writes a
PNG and exits:

```bash
harness/shot.sh                 # the fixture panel
harness/shot.sh <screen> [name] # any screen registered in ShotScreens
```

About a minute and a half per screenshot on a warm cache, against a manual pack launch.
Adding a screen to it is one line. It renders GUIs and not the world, and seven mods is not
410, so it replaces the launch-per-iteration loop rather than the live acceptance run.
[harness/README.md](harness/README.md) has the knobs, the costs and the limits.

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

`buildable` is not one price. Two machines you have to build are rarely comparable, and on
the reference pack they are wildly incomparable: over the 380 buildable categories whose
machine item can be priced, building one runs from 1 (an AE2 grindstone) up to 9,288 (a
NuclearCraft salt fission vessel). So a buildable machine is ranked by **what building it
actually costs**, derived from its own recipe rather than assumed — otherwise a machine
needing parts you cannot get ranks level with one sitting a single craft away, and the
ranking cannot prefer the reachable one. Measured over the 1,500 items whose two machines
differ most, this moved 211 onto the cheaper machine and none onto a dearer one.

A Modular Machinery machine is priced by the **structure**, not by the controller block. Its
controller recipe is a blueprint plus a blank controller, two items, for a machine that is up
to 8,813 placed blocks, so reading the recipe alone made the pack's hardest machines look like
its easiest. The requirements come from the pack's own `config/modularmachinery/machinery/`
and are baked into `graph.json` at build time, because the server that answers plans has no
pack instance to read. On the reference pack that is 259 machines and 69,354 block positions,
of which 0.25% cannot be resolved to an item; 117 of the 188 that a plan can route through
need at least one component with no obtainable recipe, which is the difference between a
machine that is expensive and one you cannot have.

Honest pricing does not fully reach the ranking yet, and this is worth knowing before reading
a plan: a buildable machine's entry cost is capped just under the figure for an unidentified
one, so the dearest structure charges 119 where its materials really run to 279,863. The
ordering between machines is right; the magnitude is compressed, so an ingredient difference
can still outweigh it. See issue #95.

Plans then report what you must build first:

```
-- machines you do not have yet --
  Chemical Reactor    buildable    craftable: nuclearcraft:chemical_reactor_idle
  Crystallizer        buildable    craftable: nuclearcraft:crystallizer_idle
```

**That list is not costed into the shopping list above it.** The machine is named, not
priced, so a plan tells you to build a Chemical Reactor without telling you what the reactor
needs. Tracked in [#86](https://github.com/Jacob-Lasky/minecraft-recipe-graph/issues/86);
you build a machine once rather than once per craft, so folding it into per-item totals is a
different calculation from the ranking above and not a matter of adding the numbers up.

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

## Dimensions

An ore you can only mine on another planet is not as easy to get as a cobblestone, and
until this it was priced as though it were. Travelling is not a recipe, so the graph could
not see the trip.

Two sources, deliberately kept apart:

| What | Where it comes from | Lives in |
| --- | --- | --- |
| Which ores generate in which dimension | the pack's `config/advRocketry/planetDefs.xml` | `graph.json` |
| Which dimensions you have entered | `<save>/DIM<id>/region/*.mca` | `ae2_have.json` |

An ore is charged for a trip when exactly one dimension declares it, that dimension has no
generated terrain in your save, and the ore is one the pack registered under an `ore*`
oredict group. **A planet is not a special kind of place** — Advanced Rocketry registers
its planets as ordinary dimensions, and the same rule would price the Nether if a config
declared an ore exclusive to it.

The surcharge is a **floor**, not a verdict: every crafted route still competes, and one
cheaper than the trip wins. On the reference pack 11 keys are gated and 6 end up actually
paying for the trip; Uranium Ore is declared on Oi but has four crafted routes, so it
settles at 2.0 rather than 801.

**The same ore under the pack's other id is gated too.** MeatballCraft registers several of
its ores twice — once as the block `planetDefs.xml` names, once as a ContentTweaker
MaterialSystem part packed into a shared holder block — and the recipes overwhelmingly
consume the holder. Gating only the declared key produced a plan that said "mined on Sedna,
and you have not been there" above a price that had not moved. A key is treated as the same
ore when it shares **both** the display name and an `ore*` group with a gated one; needing
both is what declines `tardis:power_cell`, a Trionic Power Cell that is in `oreUranium`
without being an ore. The two keys stay separate nodes and are only priced alike.

Nothing is gated when the stock file has no dimension record, which is every file written
before this feature and any written by `tools/ae2_dump.lua`. Rescanning after a trip lifts
the gate with no edit to any list.

**Only what the pack declares is known.** planetDefs describes the dimensions Advanced
Rocketry adds; nothing equivalent exists for the Nether, the End or Erebus, so no ore is
gated behind those. On this save that gap is harmless, because all three have been visited
and a visited dimension gates nothing either way.

## Requirements

Python 3.8+, standard library only. No pip install, no third-party packages — so the
same code runs inside a server container where `pip` is unavailable.

That extends to the tests, which is worth knowing before reaching for the usual runner:

```bash
python3 -m unittest discover -s tests -q      # what CI runs; takes about 4 seconds
```

There is deliberately no pytest, so `pytest` fails with `No module named pytest`, which reads
as a broken environment rather than the wrong command. Run the suite through discovery rather
than by naming a file: a stray `unittest.main()` mid-file once made a direct run of
`tests/test_machines.py` execute 31 of its 77 tests and skip six classes without complaint.

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
