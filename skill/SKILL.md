---
name: minecraft-recipe-graph
description: Plan crafting chains in MeatballCraft or other heavy 1.12.2 modpacks - resolve an item's full recipe tree down to what you actually need, pruned against the contents of your AE2 network read from the world save. Use when asked "what do I need to make X", "how do I get X", when tracing a multi-step recipe chain by hand is getting confusing, or when reading AE2/ME system contents offline.
---

# Minecraft recipe graph

Answers "what do I actually need to make X" for a ~410-mod 1.12.2 pack by computing the
whole recipe tree at once and stopping wherever the player's AE2 network already has the
ingredient. Replaces clicking through JEI one hop at a time.

Tool lives at `~/Coding/minecraft-recipe-graph`. Pure Python 3 stdlib, no install step.

## Why you cannot just grep JEI

**JEI is a viewer, not a database — it ships zero recipe data.** `HadEnoughItems.jar`
contains 0 recipe files (verified). Every recipe you page through in the JEI GUI was handed
to JEI at runtime by the owning mod's JEI plugin, out of that mod's own in-memory
registries. There is no on-disk index to search, in JEI or anywhere else.

That is why the answer is a dump-at-runtime mod rather than decompilation. Decompiling is
only ever the fallback for an isolated hardcoded constant (a search radius, a tick rate) —
never for recipes, because at 366 jars it does not scale and NuclearCraft-style recipe
registration is procedural anyway.

Fluids need no special handling: they arrive through the same
`IIngredients.getInputs(VanillaTypes.FLUID)` call as items and become `fluid:<name>` node
keys with amounts in mB. The one wrinkle is that a filled bucket
(`forge:bucketfilled` + NBT) is a *different key* from the raw fluid, so bucket-based and
fluid-based routes for the same material will not unify automatically.

## The one thing to get right

**Check graph coverage before trusting a "no recipe" answer.** The offline graph only has
crafting-table recipes (`assets/*/recipes/*.json` inside mod jars). 1.12.2 registers
furnace and machine recipes *in code*, so NuclearCraft chemistry, Modular Machinery,
inscribers and centrifuges are absent until the `/recipedump` mod has been run. If an item
resolves to `NEED: <itself>` with no children, that is almost always missing coverage,
not a missing recipe. Run `recipegraph stats` and look for `hei_dump` in `by_source`; if it
is absent, say so rather than reporting "no recipe exists".

## Usage

```bash
cd ~/Coding/minecraft-recipe-graph

# 1. read the AE2 network out of the world save (read-only, safe on a live server)
python3 -m recipegraph.cli have --regions '<world>/region/r.*.mca' --out data/ae2_have.json

# 2. build the recipe graph from the instance
python3 -m recipegraph.cli build --instance '<instance>/minecraft' --out data/graph.json

# 3. plan
python3 -m recipegraph.cli plan "Borax" --qty 64 --have data/ae2_have.json --html plan.html
python3 -m recipegraph.cli find borax          # resolve a name to an item id
python3 -m recipegraph.cli stats               # coverage: how complete is the graph

# search: stock, every recipe that makes it, everything that consumes it
python3 -m recipegraph.cli explore "ultimate component" --html explore.html

# production monitoring: record snapshots (cron this), then chart levels + net rates
python3 -m recipegraph.cli track
python3 -m recipegraph.cli chart --window 2h --html chart.html
python3 -m recipegraph.cli metrics             # db size / row counts
```

**Rates are NET, and say so when reporting them.** AE2 exposes stock levels, not machine
throughput, so an item produced and consumed at equal speed reads flat. Do not describe
these as production rates. Network power is the exception — AE2 publishes real rolling
averages, but only over the OpenComputers live feed, not the world save.

Useful `plan` flags: `--ignore-stock` (pretend nothing is owned), `--exact` (skip
name-match disambiguation), `--depth`, `--max-nodes`, `--json`, `--html`.

## Reading the output

Node badges: `in stock` (covered by AE2) · `part stock` · `craft` · `NEED` (raw leaf,
goes on the shopping list) · `loop` (cycle) · `cut off` (hit the node cap).

The `you still need` list is the answer to most questions. `drawn from your AE2 stock` is
the audit trail showing what it assumed you have.

If output says `truncated`, raise `--max-nodes` before drawing conclusions.

## Where the data comes from

| Need | Source | Note |
| --- | --- | --- |
| item display names | `config/AppliedEnergistics2/items.csv` | pack writes it on first run; 53k rows |
| crafting recipes | `assets/*/recipes/*.json` in mod jars | ~10.3k, offline |
| machine recipes | `/recipedump` mod → `mc-recipe-dump/recipes.ndjson` | **required for chemistry chains** |
| ore dictionary | dump mod's `oredict.json`, or `/ct oredict` → `crafttweaker.log` | otherwise inferred from names (labelled heuristic, ~43% recovery) |
| AE2 contents | world save region files | cells in drives/chests/IO ports/workbenches |

## Gotchas that cost real debugging time

- **AE2 cell counts live in the `Cnt` tag, not `Count`.** `Count` is an ItemStack byte
  capped at 127 and is meaningless for cell contents.
- **Cell contents are `tag/#N` keys**, not an `Items` list, and the amount field differs
  per cell type: `Cnt` for item and fluid cells, **`Amount` for essentia cells**. `Count`
  is the ItemStack byte and reads 0 in all of them, so preferring it silently drops whole
  cells instead of erroring.
- **Aspect NBT is decoded into the item key.** `thaumadditions:vis_pod` with
  `tag:{Aspect:"perditio"}` becomes `thaumadditions:vis_pod#perditio`, because a Vis Pod
  of Perditio is a different ingredient from one of Lux and they feed the item→essentia
  multiblocks. Names for these are format strings in items.csv (`"%s Vis Pod"`), filled in
  at display time. Undecodable NBT keeps a ` (+nbt)` marker so it never unifies with the
  bare item.
- **Essentia is a plannable node** (`essentia:<aspect>`), not a report-only number -- it is
  an intermediate that recipes both consume and produce.
- **Forge `_constants.json`**: 38 jars in this pack use `"item": "#name"` references. An
  extractor that ignores them produces phantom `minecraft:#name` items.
- **Uncrafting recipes poison naive solvers.** `block → 9 ingots` looks like a cheap
  one-input recipe and gets picked over the real route. The solver backtracks out of
  cycling recipes; if you write another one, do the same.
- **Read on the client, write on the server.** Recipes and configs are identical on both
  sides, so reading the local client instance is fine. The *world save* must come from
  wherever the world actually lives.

## Related

`/meatballcraft` skill covers pack performance tuning, crash triage, and the Tower server
operating playbook. This skill is only about recipes and inventory.
