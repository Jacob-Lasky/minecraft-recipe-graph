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

On the reference network this reads **3,270 distinct items across 467 cells in 67
drives** in about a minute.

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
  covers. Override per item when you disagree.
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

## The dump mod

`mod/` is a ~400-line client-side Forge mod adding one command, `/recipedump`. It walks
JEI's `IRecipeRegistry` and writes `recipes.ndjson`, `oredict.json` and `names.json`
into `<gamedir>/mc-recipe-dump/`.

It uses **only the public `mezz.jei.api` surface** — `getRecipeCategories()`,
`getRecipeWrappers()`, `IRecipeWrapper.getIngredients()` — every signature verified
against `HadEnoughItems_1.12.2-4.28.1.jar`. That is deliberate: the older
[JEIExporter](https://github.com/way2muchnoise/JEIExporter) reflected into JEI internals
and rendered recipe GUIs to scrape them, which is both slow and broken across the JEI
4.8 → HEI 4.28 gap.

**Status: builds clean**, and you do NOT need a system JDK 8 — Gradle provisions its own
Java 8 toolchain while running on a modern JDK:

```bash
cd mod && ./gradlew build -Phei_jar=/path/to/HadEnoughItems_1.12.2-4.28.1.jar
```

It deliberately does **not** use ForgeGradle 2.3, the plugin every 1.12.2 tutorial names:
Forge's maven no longer publishes the `userdev` artifact FG2 requires, only the FG3-format
`userdev3`. See [docs/BUILD.md](docs/BUILD.md) for the evidence and the reobfuscation
check.

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
- Fluids are tracked but fluid *conversion* chains are shallow.
- Essentia cells are detected but not yet aggregated.
- The AE2 reader sees cells in drives and chests. Items parked in ME Interfaces or in
  external inventories behind a storage bus are not counted.
- The tree is capped (`--max-nodes`, default 4000) and says so when it truncates.

## Licence

MIT. Not affiliated with the MeatballCraft team; issues and PRs welcome.
