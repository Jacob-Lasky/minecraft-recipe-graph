---
name: minecraft-recipe-graph
description: Plan crafting chains in MeatballCraft or other heavy 1.12.2 modpacks - resolve an item's full recipe tree down to what you actually need, pruned against the contents of your AE2 network read from the world save. Use when asked "what do I need to make X", "how do I get X", when tracing a multi-step recipe chain by hand is getting confusing, or when reading AE2/ME system contents offline.
---

# Minecraft recipe graph

Answers "what do I actually need to make X" for a 366-mod 1.12.2 pack by computing the
whole recipe tree at once and stopping wherever the player's AE2 network already has the
ingredient. Replaces clicking through JEI one hop at a time.

Pure Python 3 stdlib, no install step. The checkout path is machine-specific: take it
from the path map in `MACHINE.md` rather than assuming, since it is `/coding/...` on
the server and `~/Coding/...` elsewhere.

## READ THIS BEFORE SAYING ANYTHING IS BLOCKED

Two Claudes work on this repo, on two machines with different powers, and Jake talks to
both. If either of us reports "blocked" without saying **which machine** and **which
capability**, he ends up relaying the question between us to find out who can actually do
the thing. That is the failure this section exists to prevent, and avoiding it is worth more
than brevity.

**Every blocked claim is one line, in this shape, or it is not a blocked claim:**

```
BLOCKED: <machine> cannot <capability>. <other machine> can. Unblocks when: <one thing>.
```

Worked examples:

```
BLOCKED: nobody. pocket-dev can compile this. Unblocks when: nothing, doing it now.
BLOCKED: pocket-dev cannot run the game. desktop can. Unblocks when: Jake runs /recipedump.
BLOCKED: pocket-dev cannot compile the mod (<exact error>). desktop can. Unblocks when:
         desktop builds it, or that error is fixed here.
```

**"Blocked" is legal for a build you genuinely cannot do.** It is NOT legal for work you
have not started. Before writing the word:

- **Is it unwritten rather than blocked?** "Needs new mod Java" means go and write it.
- **Is it a missing tool rather than a wall?** pocket-dev has no `java`. That is a container
  you have not started, not a blocker.
- **Are you sure it is still true?** Re-check, do not inherit. This section replaced a
  paragraph asserting the mod could not be built on Tower because no pack instance existed
  there, which was false for months: the AMP server on the same host holds 364 jars,
  `HadEnoughItems_1.12.2-4.28.1.jar` among them. Four issues sat behind it.

### Who can do what

Verify a row before relying on it; that is the whole lesson above. Commands are in
[Building the dump mod from pocket-dev](#building-the-dump-mod-from-pocket-dev) and
[It runs as a container on Tower](#it-runs-as-a-container-on-tower).

| Capability | pocket-dev (Tower) | desktop |
| --- | --- | --- |
| Run the Python tool, tests, the UI | yes | yes |
| Read the AE2 network from the world save | yes, server world is on the same host | yes |
| Serve the UI as a container | yes, this is where it runs | not where it lives |
| Get the HEI jar `checkHeiJar` demands | yes, from the AMP server instance | yes |
| Compile the dump mod into a jar | **yes**, verified 2026-07-29, JDK 25 container | yes |
| `recipegraph build` into a graph | prerequisites are present, jar parity is not | yes, authoritative |
| **Run the game and `/recipedump`** | **no, and never** | **yes, and only here** |

The last row is the only permanent asymmetry, and it is Jake's hands on a keyboard rather
than either machine's. Anything that needs a fresh dump ends there no matter who wrote the
Java: #36 (item icons), #50 (ProjectE EMC), #55 (Modular Machinery blueprint names) and #63
(`COSMETIC_TAGS`) all do.

## Why you cannot just grep JEI

**JEI is a viewer, not a database: it ships zero recipe data.** `HadEnoughItems.jar`
contains 0 recipe files (verified). Every recipe you page through in the JEI GUI was handed
to JEI at runtime by the owning mod's JEI plugin, out of that mod's own in-memory
registries. There is no on-disk index to search, in JEI or anywhere else.

That is why the answer is a dump-at-runtime mod rather than decompilation. Decompiling is
only ever the fallback for an isolated hardcoded constant (a search radius, a tick rate),
never for recipes, because at ~410 jars it does not scale and NuclearCraft-style recipe
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
flow diagram, which runs left-to-right or top-to-bottom (#35).

Prefer pointing the user at this over running `plan` for them repeatedly. Nothing auto-starts:
the mod only writes JSON files, and the server is started by hand.

**"This item has no machine recipe" is usually the wrong item, not missing coverage.** 5,095
display names are shared by two or more plain item keys on the reference pack, covering 21,888
keys: six things called "Iron Plate", 286 called "Spell Book". The duplicates are vestigial and
the pack routes its recipes through exactly one of them. Reported as "the only way I can find to
craft an iron plate is shaped crafting" -- `thermalfoundation:material:32` has eight producers
including five machines, while `abyssalcraft:ironp` and `immersiveengineering:metal:39` genuinely
have only crafting. Before concluding the dump missed a category, check the OTHER items with that
name.

Since #101 the search breaks same-name ties on evidence -- a stack in stock, then consumers, then
producers, registry id last -- so the canonical one leads; it used to fall straight through to
the registry id and let the alphabet decide. The **oredict is not the tie-break and is the
weakest signal available**: on the Iron Plate cluster it separates 2 of the 6 while `uses`
separates all six, so it was checked and rejected rather than overlooked.
`explore.STOCK_IS_DECISIVE` is a threshold rather than `stock > 0` because one of something is a
thing you picked up; see its comment for the measurement.

### It runs as a container on Tower

The UI is worth having up when the gaming PC is off, so it is containerised and running:

```
http://<tower-lan-ip>:8765        # LAN only, no auth
docker logs recipegraph
docker restart recipegraph        # after a new image; /reload does NOT re-import Python
```

The literal address is deliberately not written down here, because this file is public and
the server has no auth. It is in the private machine notes; from a sibling container on the
same host, reach it through the docker gateway and the published port instead.

Rebuild and redeploy after a code change (the running container holds the code it started
with):

```bash
cd /coding/minecraft-recipe-graph
# FIRST, and that order is the whole point: `latest` loses its old target the moment the
# build moves it, so a tag applied afterwards names the NEW image and preserves nothing.
# `|| true` for a first-ever build, where there is no `latest` to preserve.
docker tag recipegraph:latest recipegraph:rollback-$(git rev-parse --short HEAD) || true
docker build -t recipegraph:latest \
  --build-arg RECIPEGRAPH_VERSION="$(git describe --tags --always --dirty)" \
  --build-arg RECIPEGRAPH_BUILD_DATE="$(git log -1 --format=%cd --date=short)" .
docker rm -f recipegraph
docker run -d --name recipegraph -p 8765:8765 \
  -v /mnt/user/misc/coding/minecraft-recipe-graph/data:/data \
  --user 99:100 --memory=4g --memory-swap=4g --restart unless-stopped \
  recipegraph:latest
```

**`localhost:8765` DOES NOT REACH IT FROM POCKET-DEV.** The published port is on the
UnRAID host's loopback, and a curl to `127.0.0.1:8765` from inside pocket-dev fails to
connect -- it does not return stale HTML, it returns nothing, so a `grep -c` over the reply
answers 0 and looks exactly like "the deploy did not take". Use the container IP:

```bash
IP=$(docker inspect recipegraph --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}')
curl -s "http://$IP:8765/" | grep -o "<footer class='ver'>.*</footer>"
```

That footer is the fastest way to know what is actually deployed (#38), and it works in the
container because the build args above stamp the version in. The image has no `.git`.

The bind-mount source is a HOST path (`/mnt/user/misc/coding/...`), not this container's
`/coding` view. Give it the wrong one and the `-v` still succeeds, mounting an empty
directory, and the server exits "no graph at /data/graph.json".

Give it 40 to 90 seconds before concluding it failed: it loads a 115 MB graph before
answering anything, which is why the health check has a 180 second start period.

### Building the dump mod from pocket-dev

Whether this works decides one row of the capability table above, so it is the row to
re-verify rather than inherit. The wording that used to live here claimed it was impossible
because no pack instance existed on Tower; see
[the blocked section](#read-this-before-saying-anything-is-blocked) for why that was wrong
and what it cost.

`compileJava dependsOn checkHeiJar` (mod/build.gradle:77) needs `-Phei_jar` pointing at a
HadEnoughItems jar inside a pack instance, and the AMP server on this host has one:
`/mnt/cache/AMP_Games/instances/Meatballcraft01/Minecraft/mods` holds 364 jars including
`HadEnoughItems_1.12.2-4.28.1.jar`. pocket-dev has no `java`, which is a container rather
than a wall. The instance directory is mode 0700 uid 1000, so stage the jar out with a root
container and `chown 99:100` rather than trying to read it as 99:100.

```bash
docker run --rm --user 0:0 --memory=1g \
  -v /mnt/cache/AMP_Games/instances/Meatballcraft01/Minecraft/mods:/mods:ro \
  -v /mnt/user/misc/coding/.recipegraph-build/hei:/out \
  alpine sh -c 'cp /mods/HadEnoughItems*.jar /out/ && chown -R 99:100 /out'

# MOUNT THE REPOSITORY ROOT, NOT `mod/`. See below.
docker run --rm --user 99:100 --memory=4g \
  -v /mnt/user/misc/coding/minecraft-recipe-graph:/repo \
  -v /mnt/user/misc/coding/.recipegraph-build/hei:/hei:ro \
  -v /mnt/user/misc/coding/.recipegraph-build/gradle-cache:/gradle \
  -e GRADLE_USER_HOME=/gradle -w /repo/mod eclipse-temurin:25-jdk \
  ./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx3g \
    -Phei_jar=/hei/HadEnoughItems_1.12.2-4.28.1.jar build
```

**Verified 2026-07-29: BUILD SUCCESSFUL, 12 of 12 tests pass, and
`mod/build/libs/mc-recipe-dump-0.8.0.jar` comes out reobfuscated.** About 9m20s cold, since
RFG decompiles and patches Minecraft through fernflower on first run, then about 4m45s with
`/coding/.recipegraph-build/gradle-cache` warm. Keep that cache directory.

**It must be JDK 25, not 21.** RetroFuturaGradle 2.0.2 ships class file version 69, so
`eclipse-temurin:21-jdk` dies on `UnsupportedClassVersionError` loading
`com.gtnewhorizons.retrofuturagradle.UserDevPlugin` before any of the mod's own Java is
looked at, and produces 0 class files. The error names the plugin, not your code, which
reads like a broken build script rather than a wrong JDK.

**Mounting only `mod/` compiles fine and then fails the tests**, which is the confusing
shape: `:jar`, `:reobfJar` and `:assemble` all succeed, a usable jar appears in
`build/libs`, and then `DigestFixtureTest > classMethod` fails with a bare
`java.io.IOException at DigestFixtureTest.java:80`. That test loads
`tests/fixtures/nbt_digest.json`, the cross-language digest fixture, by trying `../` and
then `./` relative to the working directory, so with only `mod/` mounted the repository root
is not there to find. Nothing in the message says so. Mount `/repo` and it passes.

So the true gate on #36 (item icons), #50 (ProjectE EMC), #55 (Modular Machinery blueprint
names), #63 (`COSMETIC_TAGS`) and #80 (digest churn) is the in-game `/recipedump`, and
everything up to that is ours to do: write the Java, compile it here, hand Jake a jar.

**#63 and #80 are written and compiled as of v0.8.0 (dump schema 4).** Both were one jar
because each re-keys every discriminated item, so they cost one redump between them rather
than two. After installing it the order is `/recipedump`, then `recipegraph build`, then
`recipegraph have` -- skipping the last strands every discriminated key, and `have` now says
so rather than leaving it to be noticed.

**A DUMP FROM AN UNCHANGED JAR BUYS NOTHING, AND COSTS THE CHURN.** Before asking for a
launch, check that the installed jar emits something the last one did not. Re-running
`/recipedump` on the same mod version against the same pack re-rolls #80's ~11,353 digests
for no new data, which re-strands AE2 stock and forces a `have` re-run: strictly negative.
`mod_version` is stamped into `summary.json` and shown in the UI footer, so it is the thing
to compare. v0.6.0 is the first jar that can write `nbt_trace.json`; v0.7.0 writes it by
default; v0.8.0 changes the digest itself, so it is the one jar whose redump is worth a
launch right now.

### Installing a built jar

The install procedure, the one-jar-at-a-time rule, the JEI-runtime retry and the
two-dumps-two-launches proof are all in the README; this is only what building here adds to
them. **Compiling is not the last step** -- a jar left in `build/libs/` means the next launch
runs the OLD mod and nobody finds out until afterwards, and a launch is the expensive action.

Three things the README does not cover:

- **`mkdir -p` the archive directory before moving the old jar out.** Without it `mv` errors,
  and if the trailing slash is dropped from the destination it silently renames the jar TO the
  archive name instead of moving it into one.
- **Confirm the game is not running, and NOT with `pgrep -f`.** Forge reads `mods/` only at
  startup, so a swap under a running game has no effect on it and the next `/recipedump`
  spends a launch on the old jar. `pgrep -f <pattern>` matches the shell running the check
  itself, so it reports the game as up when nothing is; `pgrep -a java` cannot.
- **Verify the jar in `mods/`, not the one it was copied from,** by reading `SCHEMA` out of the
  class constant pool -- neither the filename nor the version string can answer it, and
  `tests.test_dist_jar._jar_schema(path)` works on any jar including an already-installed one.
  More than one line of output here is the duplicate-modid crash, before the game says so.

```bash
python3 - <<'PY'
import glob, sys
sys.path.insert(0, ".")
from tests.test_dist_jar import _jar_schema
for p in sorted(glob.glob("<instance>/minecraft/mods/mc-recipe-dump-*.jar")):
    print(p, "SCHEMA", _jar_schema(p))
PY
```

**THE DESKTOP BUILDS AND TOWER SERVES, but not because Tower cannot build.** That was the
standing claim and it is wrong: the AMP server instance has both prerequisites, 364 jars in
`/mnt/cache/AMP_Games/instances/Meatballcraft01/Minecraft/mods` and a 2.5 MB
`config/AppliedEnergistics2/items.csv`, and `data/mc-recipe-dump/` is already synced here.

The real reason to keep building on the desktop is **jar parity, not availability**. The
server pack carries 364 jars; #36 records the client instance at roughly 410, so a
Tower-built graph would silently miss whatever client-only mods make up the difference, and
a graph missing recipes is worse than no graph because nothing in the UI says so. Nobody has
run the comparison, so treat the exact gap as unmeasured. If you ever want Tower to build,
diff the two jar lists first and write down what is absent.

`build` also needs a 165 MB `recipes.ndjson`, which only `/recipedump` produces. The gaming
machine runs it, builds, and rsyncs the finished artifacts over:

```bash
recipegraph build --instance '<instance>/minecraft' --out data/graph.json
recipegraph have  --regions '<world>/region/r.*.mca' --out data/ae2_have.json
rsync -avz --partial data/graph.json data/ae2_have.json \
      tower:/mnt/user/misc/coding/minecraft-recipe-graph/data/
```

That moves ~115 MB rather than several gigabytes of jars. Tower notices the file changed
and the **Reload** button picks it up with no restart.

**The Reload button re-reads DATA, never code.** That distinction cost an hour once: four
bugs were re-diagnosed that had all been fixed, because the running `serve` held the new
graph and the old modules. Every page now ends with a footer naming the build
(`recipegraph <git describe> (<date>)`) and the dump that produced the graph, and puts up a
warn strip saying **restart** when `recipegraph/*.py` has changed since the process started
-- deliberately with no button, because nothing a handler can do re-imports a module. See
`recipegraph/version.py` and #38. **Read the footer before believing a fix did not work.**

The container has no `.git`, so it takes its version from `--build-arg RECIPEGRAPH_VERSION`
in the build command above. Drop the args and every image reports the same fallback string,
which is the same failure one level up.

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

**`[hidden]` LOSES TO ANY AUTHOR `display` RULE.** `[hidden]{display:none}` lives in the
UA sheet with almost no specificity, so `table.mach tr{display:flex}` beat it and the
machines filter set the attribute on 499 of 503 rows, reported "4", and left all 503 on
screen. `[hidden]{display:none!important}` is in HOME_CSS and must stay.

**Gate every `:hover` on `@media(hover:hover)`.** A touch browser leaves `:hover` applied
to the last thing tapped, and the generic `button:hover` sets the same accent border as
`[aria-pressed=true]`, so unselecting a chip looked like it stayed selected. There is a
test that lints all four stylesheets for this.

**Audit INTERACTIVE state, not just page loads.** `tools/mobile-audit.js` types into
search and taps a filter chip, because an audit that only navigated to each page passed
clean while every search result rendered with its name at zero width. Run it against a
running server:

```bash
corepack enable pnpm && pnpm install && pnpm run browsers   # once
pnpm run audit:mobile  http://host:8765
pnpm run audit:filters http://host:8765
```

**The `/machines` filters interact client-side, but the DATA they filter on is computed in
Python.** `machines.mod_state_counts` ships the mod x state cross-tab into the page (77 x 4
= 2,609 bytes, 0.37% of the 713 KB page) and `machines.mod_order` decides the one mod
ordering. The browser used to build that cross-tab itself on every keystroke, which put a
domain fact where this suite could not reach it -- #16 and #32 were both reported by a human
rather than caught by a test.

**DO NOT compare two mod NAMES in JavaScript.** Python's `sorted` is codepoint order and
`localeCompare` is locale-aware, and over the pack's 77 mod names they disagree at every
position. The count term dominates, so it only showed inside a tie -- and filtering to
`no route` leaves 74 of 77 mods tied at zero, where the browser re-alphabetised the whole
group by a rule written down nowhere in Python. The client sorts on the `data-rank` the
server emits. The column sorter is a different job and legitimately compares cell text.

**Anything shipped into an inline `<script>` goes through `htmlutil.script_json`.** An HTML
parser finds the literal `</script` before any JS runs, so one mod display name containing
it turns the rest of the page into markup, and no amount of quoting inside JSON prevents
that. Mod display names are whatever 366 mod authors typed.

**`pnpm run audit:filters` is still the regression check** for what a test cannot see: the
counts narrow each other, mods with no matches sink below the ones with matches but stay in
the list disabled, and the chosen mod survives the reorder. **Run it after touching
`MACHINES_JS`.**

**A probe that reads a running server must say WHICH BUILD it hit.** Twice in one session a
measurement was labelled "before" and "after" while both runs went to a server that had
never been restarted, so the two numbers matched and looked like proof of no change. Have
the probe grep the served page for the code under test and print what it found.

**pnpm, not npm.** playwright is a pinned devDependency with a committed lockfile, and
pnpm's own version lives in `packageManager` in `package.json` so corepack reads it. Dev
tooling only: recipegraph itself is Python 3 stdlib, the image copies `recipegraph/` and
nothing else, and CI stays stdlib-only.

**`getDisplayName()` returns format codes.** 14,425 of 340,324 names arrived as
`§3Abyssalnite Axe`. `dump_names.load` cleans them now, the same way `load_items_csv`
always did; if a new name source appears it needs `clean_label` too.

**`getDisplayName()` also returns unlocalized lang keys.** 1,429 names arrived as
`tile.null.name` and similar, which is what the game draws for a block whose mod shipped no
lang entry. 268 of them were the SAME string, so 268 unrelated items rendered identically
and a plan said "build tile.null.name". `model.is_unlocalized` recognises the shape and
`Graph.relabel_unlocalized` swaps in the prettified registry path. It runs from
`Graph.load` and from `index.build` (after every name source merges, before
`oredict.guess_from_names` reads a name); a third way of populating `names` has to call it
too. It RELABELS and never deletes: `Graph.labels` is built from `names` and search is
built from `labels`, so dropping the key would make the item unfindable.

**A pack placeholder is not an ingredient.** The scripts define one item per SOURCE of
loot, so a plan asked for a "Dungeon Drop" AND a "From Battle Tower Loot" as if they were
two materials. `tokens.py` curates 37 of them across four kinds, and the plan groups by kind
while still naming the sources. DETECTION IS CURATED, NEVER A SUBSTRING: `vibranium_chest`
is a CHESTPLATE, one of four armour pieces, and `vox_ponds_token_legs` is armour too, so
matching "chest" or "token" rewrites real gear into "go find it in a chest". `recipegraph
tokens` lists what is recognised and offers what is not; the offer test is only "needed by
one recipe, made by none", which is also true of Ogerite Ore, so its output is for a human
to read. Extend via `data/tokens.json` or `tokens --add KEY=KIND`.

**261 Modular Machinery blueprints share one legitimate name.** Separate problem from the
above, and still open: every `modularmachinery:itemblueprint#<digest>` is called "Machine
Blueprint", so a plan says "craft a Machine Blueprint" without saying which of 261 machines
it builds. Deriving the name from the recipe the blueprint feeds looks like the answer,
255 of the 261 map to exactly one output, but that rule is unsafe in general: the same
shape names `bloodmagic:blood_tank:12#…` "Rite of Super-Enchanting" (it is a reagent
container, not a rite) and a `tinker_io:crushed_ore` variant "Pure Metal". The machine id
lives in the blueprint's NBT, so the fix is dump-side.

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

**With catalysts and a schema-3 dump the reference pack has ZERO `unknown` categories**, and
exactly three `unavailable`: `projectex.alchemy_table`, `divinerpg:arcana_extractor` and
`thaumicwonders.catalyzationChamber`, all identified and genuinely unreachable. That is the
number to re-measure after touching identification, and the command is
`recipegraph machines --limit 0 | head -1`, which prints `machines.summarise`'s own line:

```
categories: 96 have, 404 buildable, 0 unknown, 3 unavailable
```

**DO NOT re-measure with `machines --limit 1000 | awk '{print $1}' | sort | uniq -c`.** That
was documented here and it is wrong twice: a manually overridden row starts with `*`, so its
state lands in a `*` bucket and `have` reads 95 instead of 96; and `uniq -c` emits no row at
all for a state with no categories, so the `0 unknown` that is the whole point of the
measurement is indistinguishable from a broken pipeline.

Three widenings got it to zero and each is deliberately narrow: legacy dotted tile-entity
ids
(#27), NBT variants of a catalyst key (#28, `producers_any_variant`), and the metadata value
(#33, `Graph.meta_sibling_made`). The third fires on **one** category, a Sign Toolbox
catalogued under the damage value that selects its mode, and it is gated on the meta being
UNNAMED: without that gate it fired on four and two were false, asserting a Ritual Diviner
[Dusk] as the route to a [Dawn] and a Gene Database as the route to a Master Gene Database.

**DO NOT reach for any of the three from the solver.**
They answer "does this block exist in the pack", and the solver asks "give me exactly this
stack", where `tconstruct:ingots:0` standing in for `:3` melts the wrong ingot.

Machine state feeds the **cost estimate**, which is what actually picks recipes. Local
scoring alone fails badly: it once preferred 100,000 items through a machine already owned
over 2 items through one that needed building.

**The flow diagram draws BOTH orientations and CSS picks one.** Left-to-right and
top-to-bottom are one `layout` plus two coordinate functions (`graphview._geometry`), so the
toggle is an attribute write and there is no round trip -- a round trip would re-solve, and
a plan can take two minutes. Measured: the second SVG is 5 to 16 KB on a 48 to 67 KB page.
The choice is remembered in `localStorage` under `rg.diagdir`. Top-down is NOT a transpose:
a box keeps `BOX_W` so the label budget does not move, and siblings space out sideways
instead. See #35.

**A diagram label is budgeted against the quantity beside it, not capped at a number.** SVG
text does not wrap, so a label runs straight under the right-aligned quantity, and that
quantity is anywhere from `64` to `768,000 mB`. The old flat 20 was wrong both ways --
measured in chromium, `Sodium Fluoride Sol...` overlapped `85,248 mB` by 6.8px while
`Boron Ore` next to `64` wasted 116px. `graphview._label_limit` spends the interior. The
per-character advances in it were measured off the rendered SVG; re-measure with
`getBBox()` if the fonts change.

## The cost model is load-bearing, and it fails silently

**Never move a constant in `cost.py` without running BOTH audits first; they see different
defects.** `tools/cost-probe.py` sweeps a tuning value across 18 items whose right answer a human
can judge at a glance, four of them a control group that is correct today, and prints what each one
reroutes to. Before it existed there was no way to see the damage or the benefit of moving a
number, so constants were either argued about or left alone. Borax resolving to
`nuclearcraft_crystallizer` is the canary for the whole low-end calibration.

**A route probe is STRUCTURALLY BLIND to a clamp, which is why `tools/entry-census.py` exists.**
All 140 categories stacked on the band ceiling before #95 had perfectly stable routes, so
cost-probe reported no change while no two of them could be told apart. The census reports how many
categories land in each region of the band and how many DISTINCT values they hold, which is the
shape a flattening actually makes; a big cluster on one value is #95 recurring.

```bash
python3 tools/entry-census.py --have data/ae2_have.json --machines data/machines.json
```

Pass `--have` and `--machines` to reproduce a running server: machine states differ between a bare
graph and a live instance -- a placed machine is `have` and drops out of the census entirely -- so
a prediction made without them describes a configuration nobody is running. It computes uncached on
purpose, so it answers for the constants in the working tree rather than for whatever was current
when `.cost-cache.json` was written.

**The probe was BLIND to machine entry costs until `64f6f12`**, because it went through
`machines.resolve`, which drops the machine ITEM, so `estimate` ran with no build targets,
`machine_entry_costs` never ran, and every buildable category priced at the flat constant. Any
measurement of `BUILD_SCALE`, `BUILD_KNEE` or multiblock pricing from before that commit is
worthless, and the failure mode was the worst available one: the tool reported no change and was
believed.

```bash
python3 tools/cost-probe.py                                  # the default sweep, ~15 min
python3 tools/cost-probe.py --rank                           # ~50x faster, and it LIES
python3 tools/cost-probe.py --item minecraft:diamond --explain
```

**`--rank` and the real solve disagree, and BOTH lie in their own direction.** The ranking
skips the cycle guard, so `9 nuggets -> ingot` looks like the best route in the world; at
`BASE_RAW_COST=20` it reorders 1,962 of 21,468 items. But do NOT assume the guard saves
you: measured in real solves at that value, Diamond, Iron Ingot, Gold Ingot, Emerald and
Redstone all really do end up on nugget assembly, which is #29's regression coming back.
Use the slow mode for conclusions.

**`BASE_RAW_COST` prices EVERY recipe-less input at the same 1.0**, so a decorative
microblock, a loot token and a real ore tie exactly and a later tiebreak picks between
them. `--explain minecraft:diamond` shows 44 candidates at one price, and before #61's fix
the panel won on the `plain` bonus that hand crafting gets and smelting does not.

**The tie is broken by the pack's own ore dictionary.** `Solver.ore_backed` prefers a route
whose dead ends are all members of an `ore*` oredict group, which is what
`Graph.world_ores` collects. It is pack-declared data rather than a guess at a registry
name, and the `ore` prefix is load-bearing: `chisel:diamond` is in `blockDiamond`, so
accepting every group readmits the decorative blocks this exists to demote. Measured with
the pool emptied it moves 14 of 46,727 routes and no others; the cost-probe control group
moves one line, Diamond onto Volcanic Diamond Ore.

Three heuristics have been measured and REJECTED, so do not re-propose any of them without
new evidence (#61):

- **Demand** (prefer a leaf many recipes consume). Does not separate: Witch Hat 41 uses,
  Sand 310, the microblock 2, and a genuine Deep Mob Learning data model also 2. Worse, on
  Diamond it elects `chisel:diamond` at 67 uses over `erebus:ore_diamond` at 24, so it
  picks a decorative block *because* it is popular.
- **Raising the constant.** It is global, so it cannot reorder raw leaves against each
  OTHER, only against produced ones. 2.5 and 5.0 fix Stick (Witch Hat to Oak Wood Planks)
  and leave Diamond exactly where it was; 20 breaks the control group. At 40 Diamond and
  Lapis do move, onto `nuclearcraft_ingot_former <- [fluid] Diamond`, which is #29's molten
  metal returning.
- **Ranking "rests on no raw leaf at all" above ore.** Sounds strictly better and is not:
  it outranks `simple`, so it avoids a leaf at any price in complexity. 64 further routes
  move, `Cherry Fence <- Cherry Wood Planks + Stick` becomes a nine-slot spelling of
  itself, and `Tape Measure <- Iron Ingot + Tape` becomes `Iron Ingot + Iron Ingot + Tape
  Measure Reel + Iron Ingot`.

**A recipe's own outputs count as ancestors in `score_recipe`, not just the path's.** Two
cyclic shapes an ancestor set cannot see, both found while measuring #61. A BYPRODUCT that
feeds back is invisible at every depth, because `_build` passes `ancestors | {key}` and the
cycle is through the *other* output: an insolator emitting 12 Heart Fruit plus 1 Heart Fruit
Seed while eating a seed. And `score_recipe` called with no ancestors at all is what the
recipe-chooser page does, so `/recipes?item=aoa3:heart_fruit_seeds` recommended that
insolator recipe as the top choice to pin. 78 routes move; the Aedialite Fragment plan drops
a Quartz Sliver line from 815 to 30. Gated on `available(alt) < qty` like the ancestor case,
so a genuine upgrade or repair recipe you can feed from stock is still usable.

**If every fluid prices near 0.0, the cost model is inert and recipe choice is back to being
greedy.** `FLUID_SCALE` must be applied to recipe OUTPUT quantities as well as inputs: scaling
one side made every fluid-to-fluid hop divide the cost by 1000, so a ten-hop chain priced at
1e-30 and the table looked populated while discriminating between nothing. Symptom to watch
for: a plan that routes through a nuclear fission chain to obtain a common reagent.

Chemistry chains run 10+ hops, so the Bellman-Ford relaxation needs ~20 passes, not 6. The
table is fingerprinted on graph mtime, stock, machine states, the machine ITEMS, the multiblock
structures, the tuning constants AND `cost.FORMULA_VERSION` -- so editing `MACHINE_COST`
invalidates it, and so does changing the arithmetic, which moves no other input at all.
**Bump `FORMULA_VERSION` whenever you touch the per-unit formula**, or every machine holding a
cache keeps serving pre-change prices and the fix looks like it did not work.

**The cache file lands BESIDE THE GRAPH, via `cost.cache_beside(graph_path)`, not at the relative
`data/.cost-cache.json`** (PR #97). `estimate_cached(cache_path=None)` resolves through it, so
`serve` and `plan` agree and a new caller cannot reintroduce the old behaviour by forgetting to
pass a path. Two things the relative default broke: a container memoised into its own `/app/data/`
and lost the table on every recreation, and **the test suite overwrote the real
`data/.cost-cache.json` with fixture prices** -- fatal where a checkout's `data/` is also a
server's bind mount. After the fix a container restart is ~15s rather than ~75s, because a
7.4 MB / 159,663-price table survives in the mount.

**A/B-ING A COST CHANGE NEEDS A SEPARATE CACHE PATH PER ARM.** The fingerprint covers the
constants, not the code, so if you A/B by monkeypatching a function (the obvious way to simulate
"without the fix") NOTHING in the fingerprint moves, arm two is served arm one's cached table, and
both plans come out identical. That reads exactly like "the change has no effect" and it is the
wrong conclusion, drawn from a stale file. Pass `cache_path=` explicitly per arm -- overriding
`defaults.DEFAULT_COST_CACHE` no longer does anything, since only its BASENAME is read. Hit this
while verifying #86: two live CLI runs agreed, and the change was in fact moving 211 items.

`buildable` is NOT one price (#86, `353d049`). A buildable machine's entry cost is derived
from its own recipe, into a bounded band `[MACHINE_COST["buildable"], + BUILD_SPREAD)`. The
bound is load-bearing at both ends: the ceiling must stay under `unknown` or a machine you can
build reads as worse than one proven impossible (raw build costs reach 9,288 against an
`unavailable` wall of 5,000), and the floor must stay at the old constant or every buildable
machine gets cheaper across the board, which relitigates the Borax and Crystallizer cases.
Two relaxations, not entry costs recomputed in the loop: relaxation only ever LOWERS a cost, so
a rising entry price never propagates and the answer would depend on recipe order.

`estimate` and `recipe_cost` must charge the SAME machine price. They each held their own
`MACHINE_COST` lookup, so a change to one silently skipped the other, and the symptom is a
solver expanding a route the ranker never priced (#29's shape one level up). Both go through
`cost.category_entry_cost`, and the entry costs ride on the returned `CostTable` so a caller
cannot forget to pass them. **The cache persists them too** -- dropping them would revert the
ranking to flat constants on a cache HIT only, which is the hard way to find a bug.

A Modular Machinery machine is priced by its STRUCTURE (#93, `64f6f12`). Its controller recipe
is a blueprint plus a blank controller, two items, for a machine of up to 8,813 placed blocks that
appears in no recipe at all, because a multiblock is pack config rather than a crafting output. So
#86 moved every MM machine toward the FLOOR of the band, making the pack's hardest machines look
like its easiest. `recipegraph/multiblocks.py` reads
`<instance>/config/modularmachinery/machinery/*.json`, and `graph.json` carries the result,
because the server that answers plans has no pack instance to read. Reference pack: 259 machines,
69,354 block positions, 0.25% unresolvable, and 117 of the 188 categories a plan can route through
need at least one component with no obtainable recipe.

Four traps in that data: `elements` is a bare string 32 times, so iterating it yields characters;
`@meta` is BLOCKSTATE metadata rather than item metadata, so `stone_slab@9` is a top-side slab
whose item is meta 1 and the base key is the correct fallback; the abstract port names have no
colon, so `norm_key` would mint `minecraft:generalized_input_item`, a phantom key nobody can
trace (`regex.txt` resolves the four that matter, alias in the SECOND column, CRLF); and one of
the pack's own machinery files is malformed JSON, which the parser must survive and report.

**The generic blueprint is a catalyst for all 188 MM categories, and the CHEAPEST candidate sets
the price**, so one cheap non-machine would price every multiblock as trivial. It does not today
only because the bare `modularmachinery:itemblueprint` has no producer and prices at infinity, the
real blueprints being NBT-discriminated variants of it. That is luck, not a rule.
`machines.NOT_A_MACHINE` excludes it in `build_targets`; do not drop that on the grounds that
nothing appears to depend on it.

**Issue #95 shipped, and its own diagnosis was wrong.** It said the band was too narrow to express
a 279,863-block structure. Measured, the log curve was doing fine: the 71 priced multiblocks spread
across 55.06 to 110.58, four decades of structure cost. The defect was the CEILING, which was a
clamp holding three different claims on one number -- 140 of 403 buildable categories, 35% of them,
all at exactly 119.000:

| at 119.000 | n | what it means |
| --- | --- | --- |
| MM structure needs a block nothing makes | 117 | evidence from the pack |
| machine ITEM never priced | 23 | a gap in this model, not a fact about the base |

Those 117 ran from `the_cube` at 0.14% of positions blocked (3 of 2,125) to
`mythic_excavation_lattice` at 100% (135 of 135), priced identically. The observable damage was
`aoa3:holly_top_petals`, where a blocked multiblock beat a Phytogenic Insolator -- both at
119.000 -- by 0.037 of an ingredient point.

The region under `unknown` is now three ordered slices, every boundary derived from the two anchors
rather than typed in:

```
have 1.0 < priced [40.0, 110.0) < unpriced item 111.0
         < blocked structure [112.0, 119.0] < unknown 120.0 < unavailable 5000.0
```

Blocked structures are ordered by `multiblocks.blocked_fraction`, the share of block POSITIONS with
no obtainable candidate. That is an ORDINAL, not a cost: `structure_cost` still returns `inf` the
moment one position is unsatisfiable, so a machine that cannot be placed never reads as merely
expensive, and the whole slice stays above every priced machine. The 117-way tie became 76 distinct
values; the 23 unpriced items sit alone at 111.0.

Two counter-intuitive orderings, both load-bearing. An unpriced ITEM is cheaper than a blocked
structure, because "we failed to compute a number" is a weaker claim than "the pack says this needs
an unobtainable block". And the whole blocked slice stays BELOW `unknown` even though a proven
blockage sounds like the stronger claim -- because the blockage signal is known-wrong in an unfixed
way: chisel recipes are dropped as non-recipes, so `chisel:concrete_brown:1` reads unobtainable when
it is trivial, and any structure using one reads blocked on a false negative. Promoting that above
`unknown` rebuilds the 40%-of-the-pack wall `unknown` exists to avoid. The chisel question is what
#95 left open.

**Going from #93's flat price to structure-derived was visible in plans, and a session once told
Jake to expect otherwise.** Measured when the deployed graph was first rebuilt with `multiblocks`
present: 187 of 188 MM categories moved (117 clamping to 119.000), 5,103 MM recipes repriced,
12,027 of 110,927 non-MM recipes repriced by propagation, and 668 of 45,418 produced keys changed
their cheapest producer -- 616 of them moving OFF an MM route, which is the point. The "0 of 241
moved" figure that suggested otherwise was about a change INSIDE the saturated band, which the band
absorbs by construction.

Every "cheapest producer changed" count here is an argmin proxy, not a plan diff -- `score_recipe`
also weighs `ore_backed` and `simple + plain`, plus stock and the cycle guard -- so it bounds the
blast radius rather than describing it.

**The low end is pinned by `BUILD_SLOPE = BUILD_SPREAD / BUILD_KNEE = 79`.** Near b=0 the curve is
`BUILD_SLOPE * b / BUILD_SCALE`, so the SLOPE is the calibrated quantity and the spread is free to
move as long as the knee follows it -- which is what let #95 lower the asymptote without touching
the calibration: `build_entry_cost(1.0)` moved 0.002 against a documented 0.05 tolerance. Raising
the low end relitigates the Crystallizer case from the other side, because buildable machines
getting DEARER lets an enormous chain through owned machines win. The curve had to stop being
`b / (b + BUILD_SCALE)` because it saturates above ~6,400, flattening 20 of the 71 fully-priced
multiblocks within 1.0 of the ceiling, which is #86's own defect recurring among the expensive
machines.

What the #95 headroom cost: the priced band's asymptote came down from 119 to 110, so the dearest
priced machines are ~7 points cheaper in absolute terms. Measured blast radius on the live graph,
36 of 42,249 produced keys changed cheapest producer -- 25 moving off MM routes, 5 onto them (all
five priced rather than blocked, all near-ties), 6 within class. All 18 cost-probe routes unchanged.

**Only the ingredients amortise over a recipe's output quantity; the machine is charged per
run.** The pack has a Hostile Computing Unit recipe yielding 1,024 iron ingots and an
Enchanted Greenhouse one yielding 60,466,176 fruit, so dividing the machine by the batch
made it free: the 5,000 wall in front of an *unavailable* machine came out at 8e-5, and 126
items -- diamond, coal, string, redstone -- priced under 0.1. Symptom to watch for: a basic
material routing through a mob-simulation multiblock. The estimate is therefore **not a
lower bound**, deliberately; it is a ranking.

**Whatever picks a slot's alternative must also be what prices it.** `Solver.estimated_cost`
passes `pick=self.pick_alternative` into `cost.recipe_cost` for exactly this reason. The
iron-ingot unpacking recipe accepts a Block of Iron *or* a decorative Chisel block; the
Chisel block is a raw leaf at `BASE_RAW_COST`, so the recipe priced at 2.0 and beat smelting
an ore, and then the solver expanded the Block of Iron, which is cast from 1,296 mB of
molten iron. Nothing was mispriced. The price was for a route nobody took.

**With an empty pool every alternative ties, so cost has to be the tiebreak.** Both
`pick_alternative` and `resolve_ore` used to fall through to dump order, which is how one
Lapis Lazuli became six rounds of compressing Netherrack. Availability still wins: cost only
separates options the pool cannot.

`FLUID_SCALE` was the suspected cause of the 1,296 mB iron plan and was not involved. Ranking
a fluid chain against an item chain was fine; the batch amortisation and the pricing/expansion
disagreement were doing all the damage, and fixing them also cut a plan for one iron ingot
from 43s (truncated at the work budget) to 0.01s.

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

## Half the named keys are dead, and search must not offer them

`names.json` records every stack the dump SAW, including ones from categories filtered out
afterwards. **174,705 of 342,070 searchable labels are touched by no recipe at all** -- damaged
and enchanted tool variants, mostly. Six identical "Pluton Scythe" NBT variants and a "Pluton
Banner", all `makes=0 uses=0`, pushed Plutonium-238 through -242 off the first page of a
search for `plut`. `Graph.live_keys` is the filter; `explore.rank_matches` applies it.

**`live_keys` has to stay in step with `producers` and `consumers`.** A key hidden from search
while those two would have found recipes for it is an item that exists, works when you link to
it, and cannot be found. Three widenings are load-bearing and each has a test:

| widening | why |
| --- | --- |
| wildcard meta | `producers`/`consumers` fall back to `base:*` |
| oredict | `consumers` reaches an item through any ore it belongs to |
| catalysts | 51 keys, `thermalexpansion:machine:1` among them, appear ONLY as a JEI catalyst; their recipes output a discriminated variant instead (#28) |

Deliberately NOT widened from a bare key to its produced variants: that re-admits the
duplicate rows the filter exists to remove.

**Stock overrules the graph.** An item you hold is never hidden, however dead the graph thinks
it is. A stack in the AE2 system is a fact about the world; "no recipe touches it" is a fact
about the dump.

**The count is reported, never applied silently** -- the same rule the per-item caps follow.
`present.hidden_note` is the one place that sentence is written; the typeahead gets the
finished string from `/suggest` rather than formatting its own, and `tests/test_present.py`
fails if a second copy appears anywhere in `recipegraph/`.

Fixing this also fixed the ordering complaint that came with it. `fluid:plutonium` outranking
`Plutonium-238` needed no ranking change at all once the dead keys were gone.

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

If output says `truncated`, raise `--max-nodes` before drawing conclusions. **On the web
page there is a "Go deeper" control instead**, which doubles the cap; the flag belongs to
`plan`, not to `serve`, so quoting it on a running server was not merely inconvenient but
wrong (#25). It stops at `defaults.MAX_NODES_CEILING`, 4x the default, and the notice says
so rather than offering a dead button.

**`truncated` has TWO causes and they need different words.** The node cap, and the WORK
budget, which is the monotonic counter below that actually guarantees termination. On a
graph this cyclic the work budget usually goes first, so the node count lands far below the
cap: `avaritia:resource:6` reports 1,162 nodes against a cap of 4,000. "Tree hit the node
cap (1,162)" reads as a bug in the tool, so the notice names the real cause.

**Timings, because the control multiplies them.** A typical plan is 0.4s at the default. The
worst case is not typical: `avaritia:resource:3` spends its whole budget backtracking and
takes 26s at 4,000 nodes, ~110s at 16,000, 417s at 32,000, roughly linear in the budget.
That measurement is why the ceiling is 4x and not the 64x it was first written as, and why
`/plan` builds its Solver under `State.lock` and **solves outside it** -- holding the lock
across a two-minute solve froze every other page.

## Where the data comes from

| Need | Source | Note |
| --- | --- | --- |
| item display names | `config/AppliedEnergistics2/items.csv` | pack writes it on first run; 53k rows |
| crafting recipes | `assets/*/recipes/*.json` in mod jars | ~10.3k, offline |
| machine recipes | `/recipedump` mod → `mc-recipe-dump/recipes.ndjson` | **required for chemistry chains** |
| ore dictionary | dump mod's `oredict.json`, or `/ct oredict` → `crafttweaker.log` | otherwise inferred from names (labelled heuristic, ~43% recovery) |
| category → machine | dump mod's `catalysts.json` (v0.4.0+) | **authoritative**; without it two thirds of categories read `unknown` |
| AE2 contents | world save region files | cells in drives/chests/IO ports/workbenches |
| why a digest churned | `nbt_trace.json`, written by every dump (v0.7.0+) | #80 diagnostic; `notrace` skips it. Read by `tools/digest-churn.py`, not by `build`. READ ITS `forced` COLUMN, never `changed` |

## Pinning a recipe choice

- **A pin is stored by a FINGERPRINT, never by a recipe id.** `hei:<category>:<line>`
  renumbers on every redump and a jar path carries the mod version, so an id-keyed pin
  would silently stop applying the first time Jake redumps, which is the exact failure the
  feature exists to prevent. `pins.fingerprint` is blake2b over category, sorted outputs
  and sorted input slots. Deliberately NOT the machine name (localised) or the source
  (which extractor found it). Verified on the real pack: renumbering all 117,685 ids left
  the pin resolving `exact` to the same recipe.
- **Do not reuse `nbt_digest.fnv1a` for it.** That hash is a cross-language contract with
  the mod; tying pin identity to it would mean a change to the dump's NBT format silently
  lapsed every pin. Two unrelated hashes, two implementations, on purpose.
- **Three outcomes, and the middle one is why the category is stored too.** `exact`, then
  `category` when the recipe changed but the pack still makes it that way ("make iron by
  smelting" is usually what a pin meant), then `dead`. 2,108 fingerprints on the reference
  pack are shared by more than one recipe, up to 192 identical `thermaldynamics.covers`
  entries, so `pins.resolve` returns a SET of acceptable ids and the solver ranks within
  it rather than being handed a choice made by dump order.
- **The cycle guard outranks a pin, and the plan has to SAY so.** Pinning
  `Steel Ingot from Block of Steel` produces a plan asking for the item being crafted, so
  `expand` backtracks out of it. The chooser had already badged the choice as taken, so
  `Solver.pins_overruled` reports it on the plan and in the CLI. A badge on the wrong
  recipe is worse than no badge.
- **`item_href` is for an href attribute and `plan_url` is for everywhere else.** The
  `&amp;` in the first is correct there and wrong anywhere it gets encoded again:
  percent-encoding it as a return path produced a parameter called `amp;qty`, so the back
  link worked and the quantity silently reverted to 1.

## Gotchas that cost real debugging time

- **A relative default path plus a container is a silent skip** (#92, `b5afdaf`). `--graph` is a
  GLOBAL defaulting to `data/graph.json` and the image's WORKDIR is `/app`, so inside the
  container the default resolves to nothing: `have` skipped its entire stock reconciliation and
  `track` recorded snapshots labelled with raw item keys, both printing a success line and no
  hint that anything was missing. Both go through `cli._resolve_graph` now, which also tries the
  path beside the file the command WRITES. The general rule: **a check that exists to break
  silence must never fail silently**, so a step guarded by `os.path.exists` owes the reader a
  sentence in the else branch.
- **AE2 cell counts live in the `Cnt` tag, not `Count`.** `Count` is an ItemStack byte
  capped at 127 and is meaningless for cell contents.
- **Cell contents are `tag/#N` keys**, not an `Items` list, and the amount field differs
  per cell type: `Cnt` for item and fluid cells, **`Amount` for essentia cells**. `Count`
  is the ItemStack byte and reads 0 in all of them, so preferring it silently drops whole
  cells instead of erroring.
- **The world-save reader computes the dump's OWN digest, and readability is not a reason
  to deviate.** A stack's `#suffix` comes from `nbt_digest.digest`, a Python port of
  `DumpCommand.discriminator` (canonical NBT string, FNV-1a, low 48 bits). The reader used
  to decode the `Aspect` tag into `thaumadditions:vis_pod#perditio` instead, which reads
  far better and matched **nothing**: the dump calls that stack
  `thaumadditions:vis_pod#03c878f080d5`, so all 52 vis-pod keys (1,473,740 items) were
  invisible to every plan, alongside 8,629 bee drones. Readability comes from names.json,
  which is keyed by the discriminated id and holds JEI's own "Aqua Vis Pod". See #21.
  - **Two languages, one hash, so pin it three ways.** `tests/fixtures/nbt_digest.json` is
    asserted by both suites; six of its cases are digests read back out of a real dump, so
    they prove the port rather than proving it self-consistent.
    `test_nbt_digest.JavaSourceContractTest` greps `DumpCommand.java` for the cosmetic tag
    list and the FNV constants, catching a one-sided edit with no JVM.
  - **DO NOT change `COSMETIC_TAGS` on one side, or ahead of a dump.** The list decides the
    digest, so the two copies must move together AND the graph must be rebuilt from a fresh
    `/recipedump` in the same change. Shipping the Python half early is not a partial fix,
    it is a regression: `recipegraph have` starts computing digests that match nothing in
    the graph.json on disk, which is the exact failure #21 existed to fix.
  - **The parser had to start keeping tag types.** Byte, short, int and long all arrive as
    Python `int`, and the digest tags every value with its type, so a type-blind parse can
    never reproduce it. `anvil_nbt` now returns `int`/`float` SUBCLASSES carrying `TAG`,
    which every existing consumer reads unchanged. An untyped number raises `UntypedNode`
    rather than being guessed at.
  - **`None` and "cannot digest" are opposite answers.** `digest()` returns None only where
    the mod also declines, meaning the BARE key is right (no NBT, or nothing but cosmetic
    NBT: a renamed pickaxe is one pickaxe). TAG_Long_Array raises `OpaqueTag` instead,
    because `canonical` has no case for it and falls through to Java's `toString()`; that
    stack keeps the ` (+nbt)` marker, which matches no recipe and so over-reports rather
    than claiming you own something you do not.
- **A have file is stamped with the reader that wrote it** (`ae2_inventory.READER`), and
  `gaps.stock_coverage` needs the stamp to interpret it: reader 1 wrote ` (+nbt)` for
  *every* NBT stack, so on an unstamped file that marker means "rescan", while on a current
  one it means the mod cannot serialise that stack. `recipegraph have` prints the
  reconciliation, because 320 of 3,321 stock keys matching nothing looked exactly like a
  successful scan for as long as nobody counted.
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
