# Handoff: schema-3 discriminator regressions

Mod 0.5.0 and the NBT discriminator shipped and work; three regressions came with them, two of them P0, and all three are fixable in Python with no re-dump.

## TL;DR / next action

Fix, in this order, on one branch:

1. **#34 (P0)** container detection collapsed, `recipegraph/index.py` `mark_container_transfers`
2. **#31 (P1)** the end-to-end fixture, land it WITH #34 rather than after
3. **#23 (P0)** `#` not escaped in plan links, `recipegraph/render.py`
4. **#28 (P1)** catalysts are bare keys, recipes now make discriminated ones, `recipegraph/machines.py`

Nothing here is blocked on a human. Nothing here needs the game, a re-dump, or a new mod build.

**You have the real graph.** A checkout with real data sits at `/coding/minecraft-recipe-graph` inside the pocket-dev container (`/mnt/user/misc/coding/minecraft-recipe-graph` on Tower). Verified 2026-07-26 from inside the container: `Graph.load` returns 117,685 recipes at schema 3 in 13 seconds, the 230-test suite passes in 17 seconds, and the P0 symptom reproduces (`sum(1 for r in g.recipes if r.transfer)` is **117**, and should be roughly 7,000). Prove every fix against that, not only against a fixture.

**Correction, measured 2026-07-26:** the target is **7,016**, not the 23,000 quoted below and in #34. 7,016 is what the detector flags on this graph once every `#discriminator` is stripped, which is the pre-schema-3 behaviour it has to restore, and it agrees with the README's own "~7k of 344k recipes". Nobody has produced a measurement that yields 23,000.

## What shipped (verified 2026-07-26)

- **PR #22 merged to `master` as `f8423c7`.** Closed #13, #14, #16, #17, #18. `git log --oneline -1` on master confirms.
- **230 tests pass.** `cd <repo> && python3 -m unittest discover -s tests` -> `Ran 230 tests OK`.
- **Mod 0.5.0 is installed in Jake's client**, at
  `~/.local/share/PrismLauncher/instances/Meatballcraft, 0-18-4-cleanroom/minecraft/mods/mc-recipe-dump-0.5.0.jar`.
  0.4.2 is backed up in the session scratchpad only, so treat it as gone; rebuild from git if a rollback is ever needed.
- **A schema-3 dump exists and has been built.** `data/graph.json` reports
  `recipes 117685, dump_schema 3, names 340324, catalysts 645`.
- **Solver perf fix is real and holding.** `nuclearcraft:americium:1` went 20.23s to 0.40s, `split_key` calls 23,027,264 to 42,204, same node count and same shopping list.
- **10 JUnit tests** on the digest pass against real Minecraft NBT: `cd mod && ./gradlew test -Phei_jar=<path>`. Reobfuscation verified, prod jar 30 SRG names, dev jar 0.

## What remains

### Blocked on code only (all of it)

| Issue | P | One line |
| --- | --- | --- |
| #34 | P0 | Container detection went from 7,016 flagged recipes to **117**. Borax resolves to "43 Borax Solution Cans". |
| #23 | P0 | 298,765 of 340,324 keys carry a `#`; the renderer does not escape it, so most plan links 404 and drop `qty`. |
| #28 | P1 | 16 Thermal Expansion categories read "no route". Three stacked causes, named in the issue. |
| #31 | P1 | No test puts a discriminated key through the whole pipeline. This is why the three above shipped. |
| #24 | P1 | A 3x3 of one ingredient renders as nine `x1` siblings, each with a full subtree. Also why plans hit the node cap. |
| #26 | P1 | 169,245 named keys that no recipe touches, mostly enchanted tool variants, drowning search. |
| #27 | P1 | Legacy dotted tile-entity ids get a bogus `minecraft:` prefix, so a placed Tinkers smeltery reads as buildable. |
| #29 | P1 | Iron resolves through an uncrafting step and 1,296 mB of molten iron while free smelting loses. |
| #21 | P1 | AE2 stock still emits ` (+nbt)` and cannot match a species key. Needs `anvil_nbt` to stop discarding tag types. |
| #33 | P2 | `chickens.breeding` missing from `NO_MACHINE_PATTERNS`. Two-line fix. |
| #32 | P2 | Zero-count mods should sort below live ones in the machines dropdown. |

### Blocked on a human decision, do not start

- **#12** (serve the UI from the mod) and **#19** (in-game GUI instead of a web UI). Both are "pick a direction before 1.0.0" questions, they are coupled to each other, and each implies porting ~2,000 lines of core. Jake has not chosen. Do not implement either, and do not partially implement one as a "step toward" it.

## Locked decisions: do NOT reopen

- **The NBT discriminator is a DIGEST, not a decoded species name.** Reading Forestry's chromosome layout fixes Forestry and nothing else; ~410 mods each hide identity differently. Readability comes from `names.json`, keyed by the discriminated id.
- **The digest's canonical form is language-neutral and is part of schema 3.** Sorted compound keys, tagged types, length-prefixed strings, floats as IEEE bits. Explicitly NOT `NBTBase.toString()`, because the world-save reader has to reproduce it in Python one day and cannot reproduce Java's formatting. Changing the format changes every discriminated key. `mod/src/test/java/.../DiscriminatorTest.java` pins it.
- **`machines.same_mod` reads the registry modid; `machines.mod_name` reads JEI's display name.** Two fields, two jobs. "Industrial Foregoing" can never substring-match `industrialforegoing:plant_gatherer`, and conflating them breaks machine identification, which is correct today.
- **The "no machine needed" verdict is gated on `machines.SPECIES_SCHEMA`.** Ungating it at schema 2 was measured and rerouted Americium-242 onto bee larvae, diamond and glass panes. It self-heals on a schema-3 dump.
- **`generators.SOURCE_COST` is 0.02 and must not be 0.** Zero destroys the cost model's ability to see quantity, and a plan will ask for a swimming pool.
- **The plan and explore renderers emit a FRAGMENT with no document wrapper.** That is what makes them publishable as Claude Artifacts. Do not fix a nav or layout problem by wrapping a full document in `render.py`; the shell belongs in `server._shell`.
- **`Solver.work` is never snapshotted.** It is the monotonic budget counter and the only guarantee the search terminates. Every other accumulator must be in `_snapshot`.

## Gotchas the next agent MUST know

1. **`data/` is gitignored, so a fresh clone has no graph. One checkout has been seeded for you.**
   `/coding/minecraft-recipe-graph` in the pocket-dev container holds `data/graph.json` (117 MB, schema 3, 117,685 recipes) and `data/ae2_have.json`. Ownership is `99:100`, which is what UnRAID array shares expect; if you create files there as root you will wedge it for everything else, so `chown -R 99:100 .` after anything that might.

   **What is still NOT there: the mod jars and the raw dump.** `build` reads `mods/*.jar` (~410 of them) and a 165 MB `recipes.ndjson` from a PrismLauncher instance on Jake's desktop. So you cannot run `recipegraph build` and you cannot re-derive the graph. Use the pre-built one.

   **This matters for #34 specifically**, because `mark_container_transfers` runs at BUILD time and its result is baked into `graph.json` as the `xf` flag. Do not conclude your fix works because the file still says 117. Re-run the detector in process against the loaded graph:

   ```python
   from recipegraph.model import Graph
   from recipegraph import index
   g = Graph.load("data/graph.json")
   for r in g.recipes:          # clear what the old build baked in
       r.transfer = False
   flagged, containers = index.mark_container_transfers(g, quiet=False)
   print(flagged, len(containers))   # want 7,016, currently 117
   ```

   Then re-solve Borax and check the shape by hand: `nuclearcraft:compound:7` at qty 64 must not want Borax Solution Cans.

2. **A running `serve` holds the CODE it started with.** The `/reload` button re-reads the graph and the stock file; it does NOT re-import Python. On 2026-07-26 this cost an hour: four separate defects were reported and none existed, because the server had been up since before the merge. Symptom: a fix you can see in the source and not in the browser. Restart the process. #38 is the fix for the underlying invisibility.

3. **Do not use `pkill -f` to stop the server.** The pattern matches the agent's own shell command line and kills the session (observed exit 144). Kill by port:
   `ss -ltnp | grep ':8765' | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2`

4. **`serve` takes 40 to 50 seconds to come up** on the real graph, and answers nothing until it does. Sleep before curling `/healthz`, or you will conclude it failed.

5. **The mod needs the HEI jar passed explicitly.**
   `cd mod && ./gradlew build -Phei_jar="<instance>/mods/HadEnoughItems_1.12.2-4.28.1.jar"`
   Without it you get 40 "package mezz.jei.api does not exist" errors. There is a `checkHeiJar` task that fails with a readable message instead.

6. **If you use a git worktree, symlink `data/`.** `ln -s <main>/data data`, and note that the `data/` gitignore pattern has a trailing slash so it does NOT cover a symlink named `data`. It will get committed. Add `/data` to `.git/info/exclude`.

7. **Borax x64 is the canonical regression test for the cost model.** It must stay at roughly 14 nodes drawing 128 Granite, 52 Boron Ore and 934,400 mB of water from an infinite source. `FLUID_SCALE` exists because of it. #29 asks for a change to fluid pricing; whatever you do there, re-check Borax.

8. **`Graph.load` on the real graph takes about 2 seconds and 1 GB.** Do not load it once per assertion in a loop.

9. **No em dashes and no double dashes** in code comments, commit messages, PR bodies or issues. Repo convention.

## What is NOT done that might look done

- **#20 is still open on purpose.** The recipe side shipped; the issue stays open until Jake confirms the dump produced a sane graph and #21 closes the stock side.
- **#20's ISSUE BODY IS STALE.** It proposes "generalising the existing detector" over distinct items as a cheap interim fix. That was measured on 2026-07-26 and is **not safe at any threshold**: over items it flags `minecraft:iron_ingot`, `stone`, `cobblestone`, `diamond`; at category level it flags `ORE_SIEVE` and `botania.orechid`; per input signature the worst non-target is a `minecraft.crafting` signature with 255 output sets. A comment on the issue records this. Do not implement the body's proposal.
- **#15 is still open and its verdict is shipped but GATED.** The code is in `machines.NO_MACHINE_PATTERNS` and does nothing below schema 3. Reading the issue body alone would suggest it is unimplemented.
- **#16's "open design question" was answered one way and is being reversed.** The zero-count mod options were made disabled-in-place rather than removed. Jake then asked for them to sort to the bottom; that is #32. Do not treat the issue body's reasoning as the current decision.
- **`serve` was restarted for Jake at 22:03 local on 2026-07-26** and is running merged code. If a later session finds a stale process again, that is gotcha 2, not a new bug.
- **CI DOES exist**, contrary to an earlier note here: `.github/workflows/test.yml` runs the unit suite and `compileall` on every push and PR, against Python 3.8 and 3.13. **3.8 is the floor**, so no walrus-in-comprehension, no `dict |`, no `match`. Run `python3 -m unittest discover -s tests` locally anyway; the workflow is the backstop, not the loop.
- **The Tower checkout's `data/` is a SNAPSHOT taken 2026-07-26, not a live copy.** It will not follow a re-dump on Jake's desktop. If a measurement disagrees with one quoted in an issue, check the snapshot's age before concluding the code changed behaviour.
- **The fixture in #31 is still worth building even though real data is available.** Real data proves a fix; a fixture stops the next regression, runs in milliseconds, and is the only one of the two that a contributor without Jake's base can use.
- **`mod/logs/` and `mod/localmaven/` are build residue.** `mod/logs/` is now gitignored; `localmaven` already was. Do not commit either.

## Suggested order for the next session

`#34` and `#31` together, in one PR. #34 is the P0 with the widest blast radius (every fluid in the pack is currently cheap), and its fix is the clean structural test that the discriminator finally makes possible: `X -> X#something` with a fluid consumed is a fill, `X#something -> X` with a fluid produced is an empty. #31's fixture is what proves it without the real graph. Keep signal 2 for dumps below schema 3.

Then `#23`, which is small and self-contained. The highest-value single test in the whole backlog belongs here: assert that every `href` any renderer emits round-trips through `urllib.parse.parse_qs` back to the key it started from. It is a property, it covers every current and future link builder, and it would have caught this.

Then `#28`, which needs #31's fixture to exist first, since its shape is "a catalyst names a bare key while the recipes produce discriminated ones".

`#24` before `#25`: fixing the nine-siblings bug may take the common case back under the node cap and change what a sensible "go deeper" step even looks like.

## Refs

- Repo: https://github.com/Jacob-Lasky/minecraft-recipe-graph, branch `master`, at `f8423c7`
- PR #22, the change these regressions came from
- Issues: #34, #23, #28, #31 (do these), #24, #26, #27, #29, #21, #33, #32 (then these), #12 and #19 (do not start)
- `recipegraph/index.py`, `mark_container_transfers`, both signals, and `NON_RECIPE_CATEGORY_PATTERNS`
- `recipegraph/machines.py`, `describe`, `mod_name`, `NO_MACHINE_PATTERNS`, `SPECIES_SCHEMA`
- `recipegraph/render.py`, the fragment contract in the module docstring, and the plan hrefs
- `recipegraph/server.py`, `_shell`, `_wrap_fragment`, `_safe_back`, `MACHINES_JS`
- `recipegraph/solve.py`, `_index_pool`, `_equivalent`, `_snapshot`
- `recipegraph/cost.py`, `MACHINE_COST`, `FLUID_SCALE`, `fingerprint`
- `mod/src/main/java/io/github/jacoblasky/recipedump/DumpCommand.java`, `discriminator` and `canonical`
- `mod/src/test/java/io/github/jacoblasky/recipedump/DiscriminatorTest.java`, the behaviour a Python twin must match
- `README.md` "Gotchas that cost real debugging time"
