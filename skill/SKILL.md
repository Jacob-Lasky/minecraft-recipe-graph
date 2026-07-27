---
name: minecraft-recipe-graph
description: Plan crafting chains in MeatballCraft or other heavy 1.12.2 modpacks - resolve an item's full recipe tree down to what you actually need, pruned against the contents of your AE2 network read from the world save. Use when asked "what do I need to make X", "how do I get X", when tracing a multi-step recipe chain by hand is getting confusing, or when reading AE2/ME system contents offline.
---

# Minecraft recipe graph

Answers "what do I actually need to make X" for a ~410-mod 1.12.2 pack by computing the
whole recipe tree at once and stopping wherever the player's AE2 network already has the
ingredient. Replaces clicking through JEI one hop at a time.

Pure Python 3 stdlib, no install step. The checkout path is machine-specific: take it
from the path map in `MACHINE.md` rather than assuming, since it is `/coding/...` on
the server and `~/Coding/...` elsewhere.

## Why you cannot just grep JEI

**JEI is a viewer, not a database: it ships zero recipe data.** `HadEnoughItems.jar`
contains 0 recipe files (verified). Every recipe you page through in the JEI GUI was handed
to JEI at runtime by the owning mod's JEI plugin, out of that mod's own in-memory
registries. There is no on-disk index to search, in JEI or anywhere else.

That is why the answer is a dump-at-runtime mod rather than decompilation. Decompiling is
only ever the fallback for an isolated hardcoded constant (a search radius, a tick rate),
never for recipes, because at 366 jars it does not scale and NuclearCraft-style recipe
registration is procedural anyway.

Fluids need no special handling: they arrive through the same
`IIngredients.getInputs(VanillaTypes.FLUID)` call as items and become `fluid:<name>` node
keys with amounts in mB. The one wrinkle is that a filled bucket
(`forge:bucketfilled` + NBT) is a *different key* from the raw fluid, so bucket-based and
fluid-based routes for the same material will not unify automatically.

## Writing the user-facing text

Do not narrate the absence of a bug. An in-game message reading "the game stays playable"
was removed for exactly this: the player can see the game is running, so the reassurance is
noise and reads as an apology for a defect that is no longer there. State what is happening
("dumping 674 recipe categories..."), not what is not going wrong.

## There is a local UI

```bash
python3 -m recipegraph.cli serve      # http://127.0.0.1:8765
```

Four pages: **Search** (types-as-you-go, showing stock plus how many recipes make and consume
each item, with favourites and recents), **Machines** (filterable and sortable, each row
linking to a detail view of what that machine makes and consumes), **Sources** (what is
treated as free and on what evidence), **Coverage**. A plan renders as a nested list or as a
left-to-right flow diagram.

Prefer pointing the user at this over running `plan` for them repeatedly. Nothing auto-starts:
the mod only writes JSON files, and the server is started by hand.

### It runs as a container on Tower

The UI is worth having up when the gaming PC is off, so it is containerised and running:

```
http://192.168.86.183:8765        # Tower, LAN only, no auth
docker logs recipegraph
docker restart recipegraph        # after a new image; /reload does NOT re-import Python
```

Rebuild and redeploy after a code change (the running container holds the code it started
with):

```bash
cd /coding/minecraft-recipe-graph && docker build -t minecraft-recipe-graph:local .
docker rm -f recipegraph
docker run -d --name recipegraph -p 8765:8765 \
  -v /mnt/user/misc/coding/minecraft-recipe-graph/data:/data \
  --user 99:100 --memory=4g --memory-swap=4g --restart unless-stopped \
  minecraft-recipe-graph:local
```

The bind-mount source is a HOST path (`/mnt/user/misc/coding/...`), not this container's
`/coding` view. Give it the wrong one and the `-v` still succeeds, mounting an empty
directory, and the server exits "no graph at /data/graph.json".

Give it 40 to 90 seconds before concluding it failed: it loads a 115 MB graph before
answering anything, which is why the health check has a 180 second start period.

**THE DESKTOP BUILDS, TOWER ONLY SERVES.** `build` needs the ~410 mod jars and a 165 MB
`recipes.ndjson`, none of which live on Tower and none of which should. The gaming machine
runs `/recipedump`, builds, and rsyncs the finished artifacts over:

```bash
recipegraph build --instance '<instance>/minecraft' --out data/graph.json
recipegraph have  --regions '<world>/region/r.*.mca' --out data/ae2_have.json
rsync -avz --partial data/graph.json data/ae2_have.json \
      tower:/mnt/user/misc/coding/minecraft-recipe-graph/data/
```

That moves ~115 MB rather than several gigabytes of jars. Tower notices the file changed
and the **Reload** button picks it up with no restart.

### Changing the UI means measuring it at 390px

The UI is used on a phone. Before and after any layout change, drive it with Playwright at
a 390px viewport and measure; do not eyeball it at desktop width and assume.

**Measure `scrollWidth`, not geometry.** "Does any element's bounding box stick out" misses
the common case: an unbreakable string overflows INSIDE its own box, so every rect looks
fine while the page still scrolls sideways. That is how a 571px-wide page hid from a check
that reported no offenders. Compare `el.scrollWidth > el.clientWidth` per element.

**A flex item defaults to `min-width:auto`,** which means it refuses to shrink below its
longest unbreakable word, and `flex-basis` is simply ignored. Registry ids
(`modularmachinery:mythic_processor_melter_controller`) and the machine evidence strings
have no break opportunity, so any container holding one needs `min-width:0` AND
`overflow-wrap:anywhere`. One without the other does nothing.

**A multi-column table cannot be 390px wide.** The machines table rendered 1,424px. Rows
become cards below 700px, ordered by the `c-` classes on each cell. Those classes are the
contract between the row template and the card CSS, and `tests/test_server.py` asserts it
in both directions; add a cell and you add a class.

The two phone blocks sit LAST in `render.CSS` and in `server.HOME_CSS` because they win by
cascade order. They use different breakpoints on purpose, 640 and 700, and the comment
says why.

**Item icons are not available and cannot be faked from the id.** A registry id does not map
to a texture path by any convention; the mapping lives in each mod's models and blockstate
JSON. Real icons need a sprite sheet rendered by the dump mod. The diagram uses a per-mod hue
plus an initial, which is a real signal rather than a placeholder imitating an icon.

**Presentation lives in `present.py`, keyed by the constants themselves.** Node statuses and
machine states are rendered by four different components; each used to keep its own dict of
bare string literals, so adding a status would have drawn silently wrong. Add the case there,
never a local dict in a renderer -- `tests/test_present.py` asserts completeness both ways.

## Machine availability drives recipe choice

```bash
python3 -m recipegraph.cli machines --match nuclearcraft
python3 -m recipegraph.cli machines --set nuclearcraft_crystallizer=have
```

FOUR states per JEI category: `have` (block placed in the world, or item in stock),
`buildable` (the machine itself is craftable), `unknown` (could not be identified at all),
`unavailable` (identified, no route to it). Placed tile entities are read from the world save
during `have`, so this is evidence, not configuration. Manual overrides in
`data/machines.json` always win.

**`unknown` is not a synonym for `unavailable`, and collapsing them is catastrophic.** Machine
identity is guessed from the category's display title, and a JEI title is usually the recipe
TYPE, not the machine: "Casting" is made in a Casting Table, "Smelting" in a Smeltery
Controller, "Cover Crafting" in nothing at all. That heuristic misses about two categories in
three. Priced as unusable, that walled off 40% of the graph. `unknown` costs more than
`buildable` and far less than `unavailable`.

**Category uids fight the matcher in three ways**, all now handled and all worth recognising
if a machine reads wrongly: titles carry format codes (`"Wire Mill§r"`); uids are camelCase
while registry names are snake_case (`TechReborn.WireMill` -> `techreborn:wire_mill`); and a
modid can itself contain an underscore, so `tinker_io:smart_output` tokenises to `tinker` and
looks like a different mod. Evidence from a cross-mod name match is labelled
"(name match, other mod)" because it IS a guess -- Extra Utilities' "Furnace" category
matches `minecraft:furnace`.

**`catalysts.json` from dump mod v0.4.0+ removes the guessing.** It is JEI's own "made in"
list, so it is the authoritative category to machine mapping and beats every heuristic. If
`recipegraph build` prints "catalysts.json not present", the machines page is working from
name matching and roughly two thirds of it is `unknown`.

Machine state feeds the **cost estimate**, which is what actually picks recipes. Local
scoring alone fails badly: it once preferred 100,000 items through a machine already owned
over 2 items through one that needed building.

## The cost model is load-bearing, and it fails silently

**If every fluid prices near 0.0, the cost model is inert and recipe choice is back to being
greedy.** `FLUID_SCALE` must be applied to recipe OUTPUT quantities as well as inputs: scaling
one side made every fluid-to-fluid hop divide the cost by 1000, so a ten-hop chain priced at
1e-30 and the table looked populated while discriminating between nothing. Symptom to watch
for: a plan that routes through a nuclear fission chain to obtain a common reagent.

Chemistry chains run 10+ hops, so the Bellman-Ford relaxation needs ~20 passes, not 6. The
table is cached at `data/.cost-cache.json`, fingerprinted on graph mtime, stock, machine
states AND the tuning constants -- so editing `MACHINE_COST` invalidates it rather than
silently reusing old prices.

## Infinite generators are a curated list, not an inference

A plan for one Borax once drew its water from 71 Snowballs and 12 Wet Sponges while nine
infinite water sources sat placed in the base. An input-free block has no recipe, which is
exactly why a recipe graph cannot find it, so `generators.py` maps blocks to what they emit
and matches against placed tile entities. Extend with
`recipegraph sources --add <block>=<item or fluid key>`.

**Free is not zero.** Seeding an output at cost 0 blinds the ranker to quantity and a plan
will ask for a swimming pool. And generator draw is reported in its own list on the plan,
never folded into stock: 934,400 mB of water must be visible, not absent.

## Most of a JEI dump is not recipes

On the reference pack **235,226 of 343,860 dumped entries were not production recipes** --
`minecraft.anvil` repair permutations (77k), `EIOTank`/`forestry.bottler` container fills
(133k), plus JEI info panels, worldgen, villager trades, loot tables, Tinkers' material stat
tables and Modular Machinery structure previews. A further **11,634 are no-ops**: recipes that
consume at least as much of every output as they produce (`Empty Cell -> Empty Cell`,
`Scepter + Ender Pearl -> Scepter`, chisel variant tables, charging a flux capacitor).

**The obvious no-op test is wrong and drops real recipes.** "Every output is also an input"
discarded 3,560 good recipes, including `Chest + Tripwire Hook -> Trapped Chest` (a trapped
chest is one of three things the chest slot accepts, so the output hid among the
alternatives) and `1 Spectral Fern -> 3 Spectral Fern` in a Phytogenic Insolator, which is
the entire point of the machine. The rule needs an unambiguous input slot AND no increase in
quantity.

If a plan looks insane, suspect a false edge from a display-only category before suspecting
the solver.

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

# infinite generators: what is free, and add your own
python3 -m recipegraph.cli sources
python3 -m recipegraph.cli sources --add mymod:water_well=fluid:water

# what is the graph blind to? (needs dump mod v0.2.0+)
python3 -m recipegraph.cli gaps --dump-dir '<instance>/minecraft/mc-recipe-dump'
```

**Fluids are reported in mB, not buckets** (1000 mB = 1 bucket). Recipes are authored in
mB; converting to buckets would misreport partial-bucket recipes. State the unit when
quoting a fluid figure.

**Rates are NET, and say so when reporting them.** AE2 exposes stock levels, not machine
throughput, so an item produced and consumed at equal speed reads flat. Do not describe
these as production rates. Network power is the exception: AE2 publishes real rolling
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
| category → machine | dump mod's `catalysts.json` (v0.4.0+) | **authoritative**; without it two thirds of categories read `unknown` |
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
- **Container fill/empty recipes fake fluid production.** JEI lists `Tank -> Tank +
  16,000 mB borax_solution` as an ordinary recipe, so any fluid looks free to anyone
  holding a tank. These are flagged as `transfer` at build time by two structural signals
  (same item in and out; one item "producing" 8+ distinct fluids) and always lose to a real
  recipe. ~7k of 344k recipes on the reference pack.
- **A penalty is not enough: a transfer must NEVER produce a fluid.** 500 still beats
  infinity, so with water free the solver routed `Water Can -> squeeze -> 1,000 mB uranium
  fluoride`. The dump drops the NBT saying WHICH fluid a filled can holds, so every filled
  Forestry can collapses to `forestry:can:1` and the graph believes squeezing a can of water
  yields uranium fluoride. `Graph.real_producers` suppresses the fluid direction only --
  filling a container is real work and still counts. A fluid whose only route is emptying a
  container correctly comes out as NEED.
- **Fluids referenced only by recipes must be indexed for search.** `items.csv` covers items,
  so "Boric Acid" found `nuclearcraft:fluid_boric_acid` (the placed block, no recipes) and
  never `fluid:boric_acid`, which every chemistry chain needs. `Graph.labels` collects
  fluid/essentia/oredict keys from the recipes and holds the BARE name -- indexing the
  bracketed "[fluid] water" made the query "water" a substring match, so `fluid:water` (88
  recipes, 2,383 uses) ranked below "Water Egg".
- **The solver's backtracking needs a work budget, not just a node cap.** `nodes` is
  rewound when a failed branch is discarded, so discarded work never counts toward
  `max_nodes`; on a 340k-recipe graph the search never returns. A monotonic `work` counter
  is the only termination guarantee.
- **Read on the client, write on the server.** Recipes and configs are identical on both
  sides, so reading the local client instance is fine. The *world save* must come from
  wherever the world actually lives.
- **Ask about the ITEM, not about one NBT state of it: use `model.base_key`.** A schema-3
  dump gives every NBT variant its own key, and any code comparing whole keys silently
  stops matching. That took container detection from 7,016 flagged recipes to 117, and
  Borax went back to asking for 43 Borax Solution Cans. `base_key` strips the `#digest`
  and nothing else, so it needs no schema gate: it is the identity function on an older
  dump. **It must not strip the meta too** -- that merges `tconstruct:ingots:0` with
  `:3` into one pseudo-container that appears to melt into every molten metal in the pack.
- **A container empty does NOT return the empty container.** Forestry's squeezer gives you
  the can's MATERIAL: `forestry:can:1#48a337d94489 -> forestry:ingot_tin + 1,000 mB
  borax_solution`. So the tempting `X#d -> X` fill/empty test looks exact and catches 368
  of 7,016. Detection is the two counting signals, compared on base keys, not a direction
  test.
- **Never build a link to an item by hand: use `htmlutil.item_href`.** 298,765 of 340,324
  named keys carry a `#`, and a builder that encodes the colon but not the hash makes the
  browser treat the discriminator as a URL fragment. The server then sees a truncated key
  and answers "No item with that id", losing the `qty` in the discarded fragment too. It
  reads as missing data rather than as a broken link, and when the base key happens to
  exist it silently plans the wrong item instead of 404ing. `quote(safe="")`, always.
- **Test link building as a property, not per call site.** "A correct href contains no bare
  `#`, so what the browser sends is byte-identical to what the renderer wrote" needs no
  list of valid keys, so it covers every builder including ones added later.

## Related

`/meatballcraft` skill covers pack performance tuning, crash triage, and the Tower server
operating playbook. This skill is only about recipes and inventory.
