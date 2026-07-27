# Handoff: schema-3 discriminator regressions

Mod 0.5.0 and the NBT discriminator shipped and work. Three regressions came with them, two of them P0. **All three are fixed and merged**, along with the missing fixture that would have caught them.

## TL;DR / next action

The four items this handoff was written for are DONE, merged to `master`, and verified against the real graph:

| | | PR |
| --- | --- | --- |
| #34 | P0 | container detection restored, 117 back to 7,016 | #39 |
| #31 | P1 | `tests/fixtures.py`, the discriminated-key fixture | #39 |
| #23 | P0 | every item link built through one encoder | #40 |
| #28 | P1 | a bare catalyst matches its NBT variants | #42 |

The UI is also containerised and running on Tower (#41). See "The server" below.

**Next, in the order the last session would pick:** `#24` (nine `x1` siblings, also why plans hit the node cap) before `#25`, then `#26` (169,245 unreferenced named keys drowning search), `#27` (tile-entity ids are not item ids, and it is cause 2 of #28), `#29` (iron routes through uncrafting), `#21` (AE2 stock ` (+nbt)`, cause 3 of #28), then the two P2s `#33` and `#32`.

Nothing below is blocked on a human. Nothing needs the game, a re-dump, or a new mod build.

**You have the real graph.** A checkout with real data sits at `/coding/minecraft-recipe-graph` inside the pocket-dev container (`/mnt/user/misc/coding/minecraft-recipe-graph` on Tower). `Graph.load` returns 117,685 recipes at schema 3 in about 11 seconds and the 255-test suite passes in about 21.

**`data/graph.json` has had its `xf` flags regenerated in place** (2026-07-26) so the served graph is correct without a re-dump: 117 to 7,016. The pre-fix file is kept beside it as `graph.json.pre34.bak`; delete it once a fresh dump lands. Everything else in `data/` is still the original snapshot.

**The 23,000 figure in #34 was wrong; the number is 7,016.** That is what the detector flags once every `#discriminator` is stripped, which is by construction the pre-schema-3 behaviour, and it agrees with the README's "~7k of 344k recipes". A comment on #34 records the measurement.

## The server

The UI runs as a container on Tower, LAN only, no auth:

```
http://192.168.86.183:8765
docker logs recipegraph
```

Rebuild and redeploy after a code change. The running container holds the code it started with, and the `/reload` button re-reads the graph and the stock file but does NOT re-import Python:

```bash
cd /coding/minecraft-recipe-graph && docker build -t minecraft-recipe-graph:local .
docker rm -f recipegraph
docker run -d --name recipegraph -p 8765:8765 \
  -v /mnt/user/misc/coding/minecraft-recipe-graph/data:/data \
  --user 99:100 --memory=4g --memory-swap=4g --restart unless-stopped \
  minecraft-recipe-graph:local
```

The `-v` source is a HOST path, not this container's `/coding` view. Get it wrong and the mount silently succeeds against an empty directory. Give it 40 to 90 seconds to load the graph before concluding it failed.

**Tower only serves; the desktop builds.** `build` needs the ~410 mod jars and a 165 MB `recipes.ndjson`, none of which are on Tower. The gaming machine runs `/recipedump`, builds, and rsyncs `graph.json` plus `ae2_have.json` into that data directory. README has the commands.

## What shipped (verified 2026-07-26)

- **PR #22 merged to `master` as `f8423c7`.** Closed #13, #14, #16, #17, #18. `git log --oneline -1` on master confirms.
- **255 tests pass.** `cd <repo> && python3 -m unittest discover -s tests` -> `Ran 255 tests OK`. CI runs the same suite plus `compileall` on 3.8 and 3.14.
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
- **`producers` answers "give me exactly this stack"; `producers_any_variant` answers "can this item be made at all".** Two questions, two methods. The catalyst check uses the wide one because a catalyst is a claim about an ITEM; the solver keeps the narrow one, because a machine with different augments is not a substitute for the one a recipe called for. Do not merge them.
- **`base_key` strips the NBT discriminator and NOT the meta.** Stripping meta too merges `tconstruct:ingots:0` with `:3` into one pseudo-container that appears to melt into every molten metal in the pack, which would suppress the smeltery.
- **Container detection is the two counting signals compared on base keys, not a `X#d -> X` direction test.** The direction test looks exact and catches 368 of 7,016, because Forestry's squeezer returns the can's MATERIAL and the empty can is nowhere in the outputs.
- **Item links are built by `htmlutil.item_href`, never by hand.** `quote(safe="")`, so `#` is encoded. Test it as a property over every rendered href, not per call site.
- **`Solver.work` is never snapshotted.** It is the monotonic budget counter and the only guarantee the search terminates. Every other accumulator must be in `_snapshot`.

## Gotchas the next agent MUST know

1. **`data/` is gitignored, so a fresh clone has no graph. One checkout has been seeded for you.**
   `/coding/minecraft-recipe-graph` in the pocket-dev container holds `data/graph.json` (117 MB, schema 3, 117,685 recipes) and `data/ae2_have.json`. Ownership is `99:100`, which is what UnRAID array shares expect; if you create files there as root you will wedge it for everything else, so `chown -R 99:100 .` after anything that might.

   **What is still NOT there: the mod jars and the raw dump.** `build` reads `mods/*.jar` (~410 of them) and a 165 MB `recipes.ndjson` from a PrismLauncher instance on Jake's desktop. So you cannot run `recipegraph build` and you cannot re-derive the graph. Use the pre-built one.

   **Anything computed at BUILD time is baked into `graph.json`, so a fix to it will not show up by reloading the file.** `mark_container_transfers` is the example that cost a session: it writes the `xf` flag during `build`, so after fixing it the file still said 117. Re-run the stage in process against the loaded graph instead:

   ```python
   from recipegraph.model import Graph
   from recipegraph import index
   g = Graph.load("data/graph.json")
   for r in g.recipes:          # clear what the old build baked in
       r.transfer = False
   flagged, containers = index.mark_container_transfers(g, quiet=False)
   print(flagged, len(containers))   # 7,016 across 35 container items
   ```

   The flags in `data/graph.json` have since been regenerated in place, so it now carries 7,016 and the served plans are correct. The same trap applies to `is_non_recipe`, `produces_nothing_new` and the oredict guesser.

2. **A running `serve` holds the CODE it started with.** The `/reload` button re-reads the graph and the stock file; it does NOT re-import Python. On 2026-07-26 this cost an hour: four separate defects were reported and none existed, because the server had been up since before the merge. Symptom: a fix you can see in the source and not in the browser. Restart the process. #38 is the fix for the underlying invisibility.

3. **Do not use `pkill -f` to stop the server.** The pattern matches the agent's own shell command line and kills the session (observed exit 144). Kill by port:
   `ss -ltnp | grep ':8765' | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2`

   Better, when you are starting the server yourself for a check: launch it from a Python script with `subprocess.Popen` and stop it with `proc.terminate()`. No pattern matching, so the failure mode cannot happen.

4. **`serve` takes 40 to 90 seconds to come up** on the real graph, and answers nothing until it does. Sleep before curling `/healthz`, or you will conclude it failed. The container's health check allows a 180 second start period for the same reason.

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
- **The UI now runs as the `recipegraph` container on Tower**, rebuilt and redeployed at the end of 2026-07-26 against merged `master`. A stale-looking fix means the image predates it, not that the fix is missing; see "The server" above. Gotcha 2 still applies to any `serve` you start by hand.
- **CI DOES exist**, contrary to an earlier note here: `.github/workflows/test.yml` runs the unit suite and `compileall` on every push and PR, against Python 3.8 and 3.14. **3.8 is the floor**, and 3.14 is what the Dockerfile ships so the tested version is the shipped one, so no walrus-in-comprehension, no `dict |`, no `match`. Run `python3 -m unittest discover -s tests` locally anyway; the workflow is the backstop, not the loop.
- **The Tower checkout's `data/` is a SNAPSHOT taken 2026-07-26, not a live copy**, apart from the `xf` flags, which were regenerated in place after #34. It will not follow a re-dump on Jake's desktop until one is pushed. If a measurement disagrees with one quoted in an issue, check the snapshot's age before concluding the code changed behaviour.
- **`tests/fixtures.py` is the #31 fixture and it is meant to be extended, not copied.** It carries a container with 8 NBT variants, a machine whose catalyst names the bare key while its recipe makes a discriminated one, and one variant named and one not. Three suites already build on it. Add to it rather than writing a fourth private graph.
- **`mod/logs/` and `mod/localmaven/` are build residue.** `mod/logs/` is now gitignored; `localmaven` already was. Do not commit either.

## Suggested order for the next session

`#24` before `#25`: fixing the nine-siblings bug may take the common case back under the node cap and change what a sensible "go deeper" step even looks like.

`#27` next after that, because it is cause 2 of #28 and the machines audit only becomes meaningful once tile-entity ids stop being read as item ids. Cause 1 is fixed, so the 4 categories still reading "no route" are now the honest ones; re-run the audit after #27 rather than trusting that number.

`#21` pairs with it: it is cause 3 of the same symptom, and it is also what closes the stock side of #20.

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
