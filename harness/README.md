# Headless screenshot harness

Renders one of this mod's GUIs to a PNG with no GPU, no window, and nobody at the keyboard.

```bash
harness/shot.sh                 # the fixture panel -> /coding/.recipegraph-build/shots/fixture.png
harness/shot.sh <screen> [name] # any screen registered in ShotScreens
```

Exit code zero means the PNG at the reported path is **this run's**. Non-zero means there is
no new PNG, and the reason is on stdout on a line beginning `[mcrecipedump-shot]`.

## THIS HARNESS CANNOT BOOT THE WHOLE PACK. `prodclient/` IS THE ONE THAT CAN

`shot.sh` runs the client as RetroFuturaGradle's `runClient`, which is a **deobfuscated**
workspace: FML rewrites every production mod jar from SRG names to MCP names as it loads. That
is exactly what you want for developing one mod, and it is structurally fatal for a modpack,
because a coremod whose ASM transformer looks up a hardcoded SRG name finds nothing after the
rename. MeatballCraft has 75 coremods. Measured, out of ThaumcraftFix:

```
IllegalArgumentException: Target method boolean
  thaumcraft/common/entities/construct/EntityArcaneBore.func_184645_a(EntityPlayer, EnumHand)
  does not exist in the provided class
```

reported upward as a `ClassNotFoundException` for a class that is plainly in the jar, because
`LaunchClassLoader` wraps a transformer failure in one.

So do NOT reach for a flag that stages the whole pack into `run/mods`. One existed briefly
(`-Ppack_all`) and was removed rather than documented, because the failure it leads to is
expensive and reads like something else entirely. Use `prodclient/` instead: it assembles a real
Forge client and launches it obfuscated, the way a launcher does.

```bash
python3 harness/prodclient/assemble.py
harness/prodclient/prodshot.sh fixture packfixture -Dmcrecipedump.shotTimeoutSeconds=1800
```

Keep using `shot.sh` for ordinary GUI iteration. Ten mods and ninety seconds is why it exists.

### Running a long job in the background, on any of these scripts

**`setsid nohup`, always, and `... &` never.** This is not specific to `prodshot.sh`; it
applies to `tools/check.sh --java`, `mod/tools/build-jar.sh` and anything else here that runs
for minutes.

```bash
setsid nohup harness/prodclient/prodshot.sh dump > /tmp/dump.log 2>&1 < /dev/null &
```

A plain `&` leaves the job in the launching shell's process group, and when that shell is
reaped the whole group goes with it. The job dies; **the wrapper survives as a zombie**, which
is what makes it so expensive:

* `ps -o etime` reports a zombie happily and its elapsed time keeps climbing. Measured on this
  host: three liveness checks over two hours returned `01:11:29`, `01:32:13` and `02:33:51`,
  all three from a `<defunct>` process, and the reader waited two and a half hours on a run
  that had been dead for two minutes.
* **`/proc/<pid>` existing is not liveness.** A zombie keeps its `/proc` entry. `/proc/<pid>/
  cmdline` is the right way to confirm a pid's IDENTITY and is no evidence at all that it is
  running.
* The failure is silent in the "still working" direction, and on this host gate contention is
  usually true as well, so the wrong reading gets corroborated by a real queue.

Check liveness with both of these, not with either alone:

```bash
ps -o pid,ppid,stat,etime,cmd -p <pid>   # STAT must not contain Z
ps --ppid <pid>                          # a live runner HAS children
```

`prodshot.sh` ends every run with exactly one of `OK in`, `FAILED after` or `KILLED BY`, so a
log that stops without one of those was killed rather than being slow. `setsid` reporting
`Done` immediately is correct -- it forks and exits; verify the real worker with the commands
above.

### One agent's run must not restage another agent's instance, or rebuild its image

`prodshot.sh` takes the container gate **once**, around the image build, the mod restage *and*
the boot together. It used to take it twice with a gap between, and in that gap another agent's
`stage-instance.sh` could swap the jar in the shared `prodinstance/`, so the run booted
somebody else's jar and reported it as a missing screen registration. #265, and #228 for what
it cost. If you are editing these scripts, `tests/test_prodshot_gate_span.py` runs two agents
against one instance and will tell you if that window comes back.

Everything that writes into the instance is inside the gate now, including
`stage-instance.sh`'s `rm -rf`. The one sanctioned way to call a gated script from inside a
held gate is `GATE_LOCK=`, gate.sh's own opt-out; `gated` inside `gated` deadlocks.

**`docker build` is inside that acquisition too, in both `shot.sh` and `prodshot.sh`**, and it
is not only about memory. `mcrecipedump-shot:latest` and `mcrecipedump-prodclient:latest` are
tags on the daemon every worktree here shares, and a branch is allowed to change a Dockerfile
-- which is exactly why these rebuild every run. Outside the gate, another agent's build
replaces the tag between yours and your `docker run`, and you render, or boot, out of *their*
image. Cached, that build measured 0.78 s (prodclient) and 0.89 s (shot) on 2026-08-08, so the
gate pays almost nothing for it; a cold one pulls a JDK base and is the contention the gate
exists for.

## Why it exists

This machine cannot run the game and the desktop can only run it by hand. Issue #19 is mostly
GUI work, so without this every layout tweak costs a manual launch of the whole pack -- 367
jars in `mods/`, counted 2026-08-03 -- and the whole plan is paced by how often someone will
sit through one. That is the constraint this
removes: a screenshot is now a command, not an errand.

It is also where #19 Phase 3b's 60 fps panning gate gets measured, and where the visual
artifact that `/sr-dev-review` Q5 demands for a UI claim comes from.

## How it works

1. A container (`Dockerfile`) with JDK 25, Xvfb and mesa's llvmpipe. 1.12.2 is LWJGL 2 and
   OpenGL 2.1, comfortably inside what a software rasteriser covers -- the context llvmpipe
   actually hands it is `4.5 (Compatibility Profile) Mesa 26.0.3`, measured from the client's
   own banner.
2. `entrypoint.sh` starts Xvfb on `:99` and execs the command.
3. RetroFuturaGradle's `runClient` launches a dev client against a **small mod set**: five
   jars staged into `mod/run/mods` by `stageDevMods` -- MixinBooter, ModularUI, HEI, JEC and
   AE2-UEL -- plus Forge and this mod, which are not staged. FML reports **10 mods loaded**,
   because Forge alone answers to four mod IDs. `stageDevMods` is a `Sync`, so that set is
   exactly what the client loads. Booting MeatballCraft is not the point.
4. The mod sees `-Dmcrecipedump.shot=<screen>`, waits for the main menu, opens that screen,
   lets it settle, reads the framebuffer with vanilla's `ScreenShotHelper.createScreenshot`,
   writes the PNG and exits. No menu is clicked through, and no world is loaded *by default* --
   `-Dmcrecipedump.shotWorld=<name>` loads a superflat first, for about 40 s. See the world
   bullet under [Limits](#limits-and-what-this-is-not).

## Adding a screen

One line in `ShotScreens`, next to the existing entry:

```java
register("planner", new Opener() {
    @Override public void open(String arg) { ClientGUI.open(new PlannerScreen(arg)); }
});
```

`harness/shot.sh planner` then shoots it, and `harness/shot.sh planner:someArg` passes `arg`.

`flow` takes `<plan>@<zoom>` on top of its plan argument, so a zoom level can be photographed
rather than asserted: `harness/shot.sh 'flow:plan-in-stock@0.5'`. A malformed zoom throws
instead of falling back to 1.0, because a silently ignored zoom renders a screenshot that
looks entirely correct and is of the wrong thing.

`machines`, `machines-mods` and `machines-detail` (#254) all take an optional argument and all
three **need a real graph**: `machines:no_route` opens the table with that chip switched on,
`machines-detail:tconstruct.smeltery` opens one category, and both default to something the
data chooses (no filter; the busiest category) rather than to a hardcoded uid that stops
existing when the pack changes.

Unlike `planner`, these have **no fixture fallback and do not fake one.** A plan tree is a small
document that can be frozen in `tests/fixtures/plan/`; a machines table is a verdict on every
category in the graph, resolved from placed blocks and stock against a graph over 100 MB, and a
six-category
stand-in would photograph a screen no player will ever see -- the columns are sized from the
data, so it would not even have the right geometry. Without `RECIPEGRAPH_ORACLE` these shoot the
"no graph.json, looked in ..." panel and SUCCEED, which is the picture a new player sees and is
worth having. **So check which one a PR's screenshot actually is**: the two are easy to tell
apart full size and easy to confuse in a thumbnail, and the run logs `machines: <what happened>`
either way.

Under `prodclient` they get a real graph either way now: `stage-instance.sh` installs a
`graph.json` into the instance by default (#240), so a prodshot of these shoots the TABLE and
not the no-graph panel. `RECIPEGRAPH_GRAPH=` (empty) stages none and shoots the panel;
`RECIPEGRAPH_GRAPH=<path>` stages that one instead.

`sources` and `graph` (#255) are the other two browse screens. Both need a real graph for the
same reason `machines` does, and `graph` needs it more sharply than any other shot here: that
screen's entire subject is the IDENTITY of the graph being read, so a fixture would photograph
an invented instance path -- which is not a weaker artifact than the real one, it is a
misleading one, because the invented thing is exactly what a reviewer checks. `graph` needs only
the graph loaded; `sources` also waits for the scenario resolve, so it is the slower of the two.

`flow-hit`, `jei-keybind`, `ae2-probe`, `ae2-placed` and `dump` ASSERT rather than photograph, and a screen
in that shape owes the harness a verdict. It declares one with `ShotScreens.expectReport(...)`
and then answers with `reportPass()` or `reportFail(<the criterion that did not hold>)`; the
harness fails the run if the verdict never arrives OR if it is a NO. There is deliberately no
call that means only "I spoke": the first cut of this had one, and `ae2-probe` called it on all
five of its failure paths and none of its success path, which would have reported passing runs
as failures and failing ones as clean. Caught by reading it rather than by a run, since the
guard was written after the last probe run and never executed.

`flow-hit` parks the real cursor over each node in turn and logs, side by side, which box
`IWidget.isHovering()` reports and which box the layout says is there. They agree at zoom 0.5,
1.0 and 2.0 -- which is the evidence that ModularUI's own hit-testing is correct through the
scroll viewport *and* the zoom matrix, and therefore that a diagram click should go through
`getWidgetsAt` rather than through hand-rolled coordinate maths.

`jei` is the one screen that is not ours: it asks `JeiBridge` to open JEI's own recipe page,
so the picture is evidence that the runtime was captured, a focus was created and the GUI was
shown. `harness/shot.sh jei` uses an iron pickaxe; `harness/shot.sh jei:minecraft:furnace`
names another item.

`jei-keybind` is the other half of that, and it drives an INPUT GESTURE end to end rather than
opening a screen: it filters JEI's item list, walks the real cursor over the overlay until JEI
reports an item under it, presses the plan key through `PlanTargetKeybind.onPressed`, and then
fails the run unless the planner solves the key of the item that was under the cursor. It needs
a world, because the plan book is a capability on the player:
`harness/shot.sh jei-keybind jeikey -Dmcrecipedump.shotWorld=jeikey`. `jei-keybind:<filter>`
types something else into JEI's search box; the default is `@minecraft hopper`, chosen so the
same probe means the same thing on the dev set and on the whole pack.

UNDER `prodshot.sh` IT NEEDS 8g OF CONTAINER AND ~6500M OF HEAP, for the same reason `dump`
does and not for a reason of its own: the cost is the WORLD. At the 7g/5G a GUI shot is
comfortable at, the integrated server dies with `OutOfMemoryError: Java heap space` in
`PECore.serverStarting` -> `EMCMapper.map`, because ProjectE enumerates every Forestry bee
variant when a world starts. Measured here 2026-08-08. It does not present as an OOM: the
server crashes, the client bounces back to the main menu, and the harness then reports
"waiting for the main menu" until the run times out.

```bash
MEMORY=8g CLIENT_HEAP=6500M harness/prodclient/prodshot.sh jei-keybind packjeikey \
    -Dmcrecipedump.shotWorld=packjeikey -Dmcrecipedump.shotTimeoutSeconds=3000
```

DO NOT RAISE IT ABOVE 8g. Tower runs the household's Home Assistant and its doorbell in
sibling containers, and 8g is the ceiling those live under.

It exists because this is the one path where both halves of a seam can be green and the
feature still dead, and that happened twice at once. `PlannerHooks.setTargetListener` was never
called by the shipped mod, so every press was handed to a listener that does nothing; and the
keybind subscribed only to `InputEvent.KeyInputEvent`, which does not fire while a `GuiScreen`
is open, which is every frame on which JEI has an overlay to point at. Neither is visible to a
unit test, because every unit test installs a listener of its own and calls the handler
directly. The verdict compares keys rather than photographing a tree deliberately: a planner
opened on the wrong item produces a completely convincing screenshot.

It waits for the plan TWICE, and the second wait is master-specific. `PlanTarget` hands the key
to `PlannerEntry.openOn`, which holds the solve until `PlannerStock` has the ME stock reply, so
in a world with a server to ask the press does not start the plan on the frame it is made. The
probe holds until `PlannerService.generation()` moves before it reads a verdict, and calls the
wait itself a failure if the reply never comes -- reading on the next frame instead would
compare the pressed key against the service's *previous* target, which at the start of a run is
the empty string.

`ae2-probe` needs a world: `harness/shot.sh ae2-probe ae2probe
-Dmcrecipedump.shotWorld=ae2`. Measured at 177 s and 189 s on a warm cache with one Java file
changed -- above the table below because of the world load, and because both runs shared Tower
with other builds. Without `shotWorld` it refuses and says so, because a
grid exists only on the server. It also declares its own settle window through
`ShotScreens.requestSettleFrames`, since it waits twenty SERVER ticks for AE2 to connect its
nodes and the default twenty RENDER frames is shorter than that -- a screen that needs time
says so in code rather than in an incantation the next person has to know to type. See the AE2
bullet under Limits for what its verdict does and does not establish.

**That settle window is UNSOUND and #319 is open on it.** A frame count does not bound server
tick progress -- the two are separate clocks, and `requestSettleFrames(150)` is a bet on their
ratio that has already lost once, on 2026-08-03. `ShotScreens.holdCapture` is the replacement
and is what every shot that needs to WAIT on a condition already uses (`Ae2ProbeShot` is the
only `requestSettleFrames` caller left; several shots need no wait at all): it holds until the
condition it actually cares about is true, so there is no ratio to be wrong about. The description above is accurate about what the
code does today; do not read it as an endorsement, and do not copy the pattern into a new shot.

`ae2-placed` is the probe `ae2-probe` said was owed, and it needs a world the same way:
`harness/shot.sh ae2-placed ae2placed -Dmcrecipedump.shotWorld=ae2`. It calls
`Ae2Stock.read(player)` — the exact entry point `StockRequestMessage.Handler` calls in game — so
every gate is the real gate, and it reaches them by building the setup a player would have: a
creative energy cell, an ME Security Terminal whose node it takes ownership of, a Wireless
Access Point, a Molecular Assembler on the grid, and a Wireless Terminal in the player's
inventory linked to the station's serial and charged to full. A vanilla furnace goes down three
blocks clear of the AE2 line.

**Nine criteria, and two of them are NEGATIVE**, because the thing under test is a map that can
legitimately be empty and "no error" would therefore be worthless: the read succeeds; the assembler
IS in `placed()`; the furnace IS too; a stone block (no tile entity) is NOT; a mob spawner (a tile
entity with NO item form) IS, under its tile-entity id; through `ScenarioInputs.resolve` both placed
categories read `have` with evidence starting `placed:`; a category whose machine was never placed
still reads `buildable`; a placed generator makes its output free with `placed:` evidence; and the
caveat is gone. The evidence-string checks are what stop it passing on a `have` that came from the
machine's ITEM being in stock, which is a route that already worked.

**The mob spawner is a REGRESSION GUARD FOR A REAL BLOCKER, caught in review before it shipped.**
Vanilla overrides `Block.getItem` on a spawner to return `ItemStack.EMPTY` — you cannot middle-click
one — so the item-key derivation has nothing to return. The absence used to be normalised into the
per-blockstate cache but NOT into the local variable, so the first such tile entity dereferenced null
— and the NPE escaped the scan, escaped `Ae2StockReader.read`, and `Ae2Stock.read`'s
`catch (Throwable)` delivered it as `NETWORK_GONE`, killing the ENTIRE stock read, phase 1's grid
machines and the item list with it, after the terminal had been charged. Criterion 1 (the read
succeeds) is the survival half; the spawner appearing under its tile-entity id is the two-keys
fallback firing rather than the block being silently dropped.

**`Item.getItemFromBlock` AND `Block.getItem` ARE DIFFERENT QUESTIONS, and confusing them cost two
passes here.** Vanilla overrides the second in both directions: `mob_spawner` HAS an `ItemBlock` and
still has no pick-block, while `lit_furnace` has NO `ItemBlock` and still picks as a plain
`minecraft:furnace`. An earlier cut of this probe read `getItemFromBlock`, concluded the spawner was
unsuitable, swapped in a brewing stand, and built a census of "14 blocks with no item form" on the
same wrong function. **The mutation run is what caught it: with the blocker restored the probe
PASSED**, because a brewing stand's `getItem` answers a real item and the branch was never taken.
Measured through `Block.getItem`: 12 of 254 vanilla blocks answer EMPTY and **four** have a tile
entity — `piston_extension` (any piston mid-push), `mob_spawner`, `end_portal`, `end_gateway`. Which
is exactly the set the review named.

**Always mutation-run a criterion whose job is to catch a specific crash.** A criterion that cannot
fail looks identical to one that passes.

The probe logs placed keys **with their counts**, which is how the cross-source double-count stays
observable: anything that is both a grid node and a loaded tile entity (an ME Drive, most AE2
machines in a loaded chunk) lands as 2. Inert today — every consumer tests membership or `> 0` — and
`PlacedTileEntities`' class note states it as a limitation with the constraint it implies rather than
promising it cannot happen.

**THE FURNACE CRITERION WAS INVERTED BY #320 PHASE 2, and the inversion is the measurement.** Under
phase 1 the furnace had to be ABSENT from `placed()` -- it is not an `IGridHost`, so a grid walk
cannot see it -- and that assertion was the control against an enumeration that returned everything
nearby. Phase 2 scans each loaded world's `loadedTileEntityList`, so a furnace in a loaded chunk is
owned, and master and this branch assert opposite things about it: there is no version of the code
where both pass. The control moved to a block with no tile entity at all, which is strictly stronger
because it depends on nothing about where the AE2 blocks went. The stone block is PLACED by the
probe rather than assumed to exist, since an assertion about absence passes trivially if the thing
was never there.

**Modular Machinery is NOT covered by this probe and it says so on every run.** MM is not among the
five staged dev jars, so the formed-controller path is unit-tested against fakes derived from
`ModularMachinery-CE-2.2.2`'s own `javap` output (`ModularMachineryControllersTest`, plus the two
fake classes at MM's fully-qualified names carrying that output in their headers) and its live proof
is pending a full-pack prodshot. The probe also logs the scan's cost -- worlds, tile entities, key
derivations, milliseconds -- **twice, and the COMPARISON is the evidence**: once as the world stands,
then again after placing a thousand more of one block in a 10^3 cube. A single line cannot speak to
the per-block-state cache, because the harness world holds five tile entities of five different types
and `keyDerivations == tileEntities` is the correct answer there. A thousand more entries adding ZERO
derivations is the cache working; a matching rise is the cache gone. It is logged rather than
asserted, because a timing on a host shared with other agents' builds is not a pass/fail.

It uses `ShotScreens.holdCapture` and NOT `requestSettleFrames` — see #319, which is about
`ae2-probe`'s settle-frame budget being a stopping rule that is sound only by arithmetic nobody
re-checks. This one holds until its own driver has reached a verdict, so a slower host waits
instead of turning a pass into "the screen never reported". A hold is still not a verdict, so it
declares one too.

**THE INTEGRATED SERVER DOES NOT TICK IN THIS HARNESS UNLESS YOU TURN OFF `pauseOnLostFocus`.
MEASURED 2026-08-22 over four runs, and it is the single most expensive thing to not know.**

Minecraft opens `GuiIngameMenu` by itself, every tick, whenever `gameSettings.pauseOnLostFocus`
is set and `Display.isActive()` is false — and under Xvfb with no window manager the display is
never active. A single-player client with a screen open sets `Minecraft.isGamePaused`, and a
paused `IntegratedServer.tick()` skips `super.tick()` entirely: it drains only its
`futureTaskQueue`. So **no tile entity ticks, no `TickEvent.ServerTickEvent` fires, and AE2 can
never build a grid.** The four runs, in order, are worth reading as a set:

| run | what it did | result |
| --- | --- | --- |
| 1 | opened `HarnessFixtureScreen`, waited on server ticks | froze at tick 0, spun 676 s to a timeout |
| 2 | `expectNoScreen()` | still paused; logged "opened no screen" and hung anyway |
| 3 | client-tick driver, closed the screen it found | named `GuiIngameMenu`, saw **1** server tick in 200 client ticks, failed in 81 s |
| 4 | `pauseOnLostFocus = false` | **102 server ticks in 160 client ticks; grid formed, 4 nodes, powered; PASS in 82 s** |

Three lessons, and the third is the one that generalises:

* **`expectNoScreen()` is not enough.** It stops the harness failing the run for an unchanged
  screen; it does not stop the game opening one on its own.
* **Closing the screen is treating the symptom.** It reopens on the next tick, and a probe that
  logs the close once cannot tell "opened once" from "reopens forever" — which is why
  `ae2-placed` counts them.
* **Clock a probe off the CLIENT tick and count SERVER ticks as evidence.** The client tick always
  runs, so a probe driven by it can report "the server ticked 1 time in 200" instead of hanging.
  A probe clocked off the thing that can stop cannot tell "still settling" from "stopped", and
  the failure it produces is a timeout, which reads as a broken probe rather than a paused world.

**This casts doubt on `ae2-probe`'s documented pass, and #319 understates the problem.** That
probe opens `HarnessFixtureScreen` and then waits twenty SERVER ticks, which is run 1 exactly —
its ticks cannot arrive under a paused client, so its settle-frame budget is not merely an
unsound stopping rule, it is a budget for an event that never comes. Its recorded pass is from
2026-08-03 and nothing has re-run it since; whatever was true then, the shape is broken now. It
has NOT been re-run as part of #320 and should be, with `pauseOnLostFocus = false` moved into the
harness itself rather than set per-probe.

`dump` is the only entry that runs a COMMAND rather than opening a GUI, and the only one that
opens no `GuiScreen` at all: `harness/shot.sh dump dump -Dmcrecipedump.shotWorld=dump`. It
drives `/recipedump` through `ClientCommandHandler` -- the identical path a keyboard drives,
rather than calling `DumpCommand.execute`, so a pass is evidence about the path players use.
It needs a world because the sender is `mc.player`, it declares `expectNoScreen()` because
chat is the HUD and not a screen, and it holds the capture until `DumpCommand.running()` goes
false rather than guessing a frame count. See #146: this is Experiment A, and what it settles
is whether the harness can drive the command at all, on the five-jar dev set.

Keep it to one line: the moment adding a screen costs more than that, people stop adding them
and the harness stops being used.

## Knobs

All are `-D` system properties on the client, forwarded from the gradle command line by the
`runClient` block in `mod/build.gradle`. `shot.sh` sets the first four.

| Property | Default | What it does |
| --- | --- | --- |
| `mcrecipedump.shot` | *unset* | The screen to open. **Unset means the harness does nothing at all.** |
| `mcrecipedump.shotOut` | `<gamedir>/shots/<screen>.png` | Exact output path. |
| `mcrecipedump.shotWidth` / `Height` | 1280 / 800 | Window size, and therefore image size. |
| `mcrecipedump.shotSettleFrames` | 20 | Frames between opening the screen and capturing. |
| `mcrecipedump.shotTimeoutSeconds` | 600 | Give up waiting for the menu, **and for a screen that is holding the capture**. Raise it for a big pack: 600 sits inside the estimated boot range of the full 367-jar pack, so a healthy run would time out. Not raised by default, because for the five-jar dev set 600 is generous and a larger default only makes a genuinely hung run take longer to fail. |
| `mcrecipedump.shotDebugOverlay` | `false` | Keep ModularUI's widget-outline overlay in the shot. |
| `mcrecipedump.shotTimedFrames` | 0 | Time this many frames after settling, then report. See below. |
| `mcrecipedump.shotWorld` | unset | Load a superflat single-player world before opening the screen. Adds ~40 s. See the world bullet under Limits. |

The two it does not set are reachable through the pass-through tail:

```bash
harness/shot.sh fixture fixture -Dmcrecipedump.shotDebugOverlay=true
```

`shot.sh` reads `HOST_CODING`, `HOST_REPO`, `HOST_BUILD`, `CACHE_NAME`, `IMAGE`, `MEMORY`,
`SHOT_WIDTH` and `SHOT_HEIGHT` from the environment if you need to move it. `SHOT_WIDTH` and
`SHOT_HEIGHT` size the Xvfb screen as well as the window, so they stay in step.

## What it costs

Measured on Tower, 2026-08-02, warm cache:

| | wall clock |
| --- | --- |
| nothing changed | ~75-85 s |
| one Java source file changed | ~100 s |
| first ever run (cold gradle cache) | + the ~9 m RFG fernflower decompile, + a vanilla asset download |

About a minute and a half per iteration, against a manual pack launch. That is the number
that decides how usable the loop is, so re-measure it rather than quoting this line if the
dev mod set grows.

## Measuring frame cost

```bash
harness/shot.sh <screen> <name> -Dmcrecipedump.shotTimedFrames=300
```

Reports the **draw cost** per frame as percentiles, plus how many exceeded the 16.67 ms a
60 fps budget allows. A screen that implements `ShotScreens.Animated` is driven once per
timed frame, so a pannable canvas measures panning rather than a still picture; one that does
not is timed sitting still and the log says so.

**It reports the DRAW, not the frame period, and that is not a shortcut.** Minecraft throttles
hard when no world is loaded, which is always true here. Measuring the gap between frames
reports that throttle: the first version of this mode came back with `p50 = 33.23ms` for a
screen holding four widgets, which is 30.0 fps to three figures. Disabling vsync changed
nothing, because vsync was never the cause. Render-tick START to END brackets the render and
excludes `Display.update` and the limiter's wait, both of which happen afterwards -- and
"does the draw fit in 16.67 ms" is the question a 60 fps gate is actually asking. The wall
period is still printed beside it so the gap is visible rather than hidden.

Reference figure, so a later number has something to sit against: the harness fixture panel
(one ModularUI panel, three text widgets, one button) measures **p50 1.04 ms, p99 11.65 ms,
max 18.30 ms** over 300 frames at 1280x800, against a wall period of 33.32 ms.

**A pass here implies a pass on a GPU; a miss here implies nothing.** Software rasterisation
is far slower at fill than a card while the CPU-side widget work is comparable, so clearing
the budget under llvmpipe on a shared host is strong evidence -- and failing to clear it could
be the rasteriser, or another container on the box. Quote a pass; do not quote a fail.

## Limits, and what this is not

**EVERY BULLET BELOW IS AN ASSERTION, AND ASSERTIONS HERE GET THE SAME STANDARD AS ASSERTIONS
IN CODE.** This section said "It has no input. Nothing clicks, scrolls or types" for a
fortnight. It was never tested; it was inferred from there being no window manager, and it
was wrong -- the harness could always move the real cursor. Four people repeated it and it
shaped what we believed was verifiable. **A limitation nobody checked is not a limitation,
it is a guess with authority**, and it is worse than a silent skip because it stops anyone
running the check at all. So each bullet says whether it was measured.

* **It CAN render the world. MEASURED, and this bullet used to say the opposite.** It read:
  "No world is loaded, so anything that needs a player, a tile entity or a server-side
  capability has nothing to draw from. Phase 5's live AE2 read is not testable here." Three
  clauses, none of them ever tested. All three are false.

  `-Dmcrecipedump.shotWorld=<name>` makes the harness launch the integrated server on a
  superflat and wait for the world before opening the screen. `world-probe` then places a
  chest and queries it. Measured output:

  ```
  world loaded: dimension 0, player at 965.5,4.0,-598.5, block beneath = minecraft:grass,
                integratedServer=true, loadedTileEntities=0
  world-probe: setBlockState=true, block now minecraft:chest, tileEntity=TileEntityChest,
                capability: IItemHandler with 27 slots
  world-probe: loadedTileEntities now 1, integratedServer=true
  ```

  A player, a tile entity, and a server-side capability -- and `hasCapability` then
  `getCapability` on a tile entity is the same shape of call Phase 5's AE2 read needs. **So
  the harness can host that test.** What that bullet proved is the MECHANISM, and it was
  careful to say so; the grid itself is the bullet below.

  Costs about 40 s on top of a normal run, and it is OFF BY DEFAULT -- a world changes what
  is behind every panel, so turning it on would move every existing screenshot.
* **An AE2 GRID can form here. MEASURED on 2026-08-03, and it is a separate claim from the
  bullet above.** A grid that never forms hands back null and a grid that forms empty hands
  back an empty list, and both are indistinguishable from a working read of an empty network --
  so "no error" is not evidence and `ae2-probe` fixes three criteria in advance instead:

  ```
  ae2-probe: grid formed, nodes=2, powered=true (criterion: nodes >= 2 AND powered)
  ae2-probe: cell accepted at face null slot 1, leftover none
  ae2-probe: injected 64 cobblestone, leftover=none, storage list reports 64
             (criterion: stored == 64)
  ae2-probe: VERDICT nodes>=2 true, powered true, stored==64 true
  ```

  Two nodes in ONE grid, so the connection actually formed; the grid reporting itself powered,
  so the creative cell is recognised as a source and not merely present; and 64 cobblestone
  injected through `IMEMonitor` coming back out of `getStorageList`, which nothing short of a
  working grid produces. It FAILED TWICE before it passed -- once with no grid at all, once
  with `stored` at 0 -- which is most of what makes the pass worth reading.

  **WHAT IT DOES NOT PROVE: anything about production.** The two paths share only their last
  two calls, `getGridNode(AEPartLocation.INTERNAL)` and `getGrid()`. `ae2-probe` reaches those
  from a tile entity it placed itself; `Ae2StockReader` reaches them by finding a wireless
  terminal in the player's inventory, reading its encryption key, resolving that through AE2's
  locatable registry, and checking the player against an `IWirelessAccessPoint`'s range. **None
  of those four steps is exercised here.** What is established is that the environment can HOST
  the test, which is the thing that was in doubt.

  **THAT PROBE NOW EXISTS, for the machine half: `ae2-placed`.** #320. It builds the security
  station, the access point and the linked, charged wireless terminal, then calls
  `Ae2Stock.read(player)` itself — so all four steps above are exercised, and a refusal names
  which gate stopped. What it still does not cover is the PACKET: `StockRequestMessage` and
  `StockReplyMessage` are asserted by `StockSnapshotTest` and `PlannerStockTest` over a real NBT
  round trip, not by a client-server hop. The stock half of the read has no probe of its own
  either; `ae2-placed` reads it as a side effect and logs the item count, which is evidence but
  not an assertion.
* **It is not a substitute for the real pack. TRUE BY CONSTRUCTION.** Five staged jars -- ten
  loaded mods, because Forge alone answers to four mod ids -- is not the pack's 367. A
  screen that renders here can still collide with something in MeatballCraft -- a conflicting
  keybind, another mod's GUI overlay, a theme override. #19's verification plan keeps one live
  acceptance run per phase for exactly this.
* **It has a cursor. MEASURED. Clicks and typing: UNKNOWN.** There is a real X display, so
  `Mouse.setCursorPosition` moves the real cursor and Minecraft's hover pass runs for real:
  `IWidget.isHovering()` answers correctly, which is enough to exercise a hit-test end to end.
  `flow-hit` does exactly that, and `ScreenCursor` has the two coordinate conversions it needs
  (LWJGL's origin is the BOTTOM left, in display pixels rather than GUI pixels). They live
  there rather than in `FlowCanvas` because they were written out three times in that file
  where nothing could assert them; `ScreenCursorTest` now pins the y flip as a fact with no
  window (#240).

  **Hover is proven; a synthetic click is not.** Minecraft reads button state from LWJGL's
  event queue rather than by polling, so pressing a button is a different problem from moving
  the pointer and nobody has tried it here. Do not write "the harness can click" until
  something has. If it turns out to need real events,
  [HeadlessMC](https://github.com/headlesshq/headlessmc) has a command channel with `gui` and
  `click` verbs and is the fallback #124 named. Read "Why not HeadlessMC" below before
  reaching for it -- take its command channel, not its LWJGL stubs.
* **It is a dev client, so ModularUI's dev behaviour is on. MEASURED.** The widget-outline
  overlay is suppressed for the shot (see the table above), but `ModularUI.isDev` stays true
  and other dev-only branches inside ModularUI are not suppressed.
* **llvmpipe is a software rasteriser. MEASURED, and the variance more so than the floor.**
  Frame timings taken here are a floor, not a prediction of the desktop's -- see "Measuring
  frame cost" above for what that does and does not license, and for why the number to read is
  the draw rather than the period.

## Why not HeadlessMC

#124 named [HeadlessMC](https://github.com/headlesshq/headlessmc) as the fallback if llvmpipe
failed. It did not fail, and the reason to keep it that way is sharper than "we did not need
to": **HeadlessMC goes headless by patching LWJGL so every function returns a stub**, and a
stubbed GL renders nothing. Every screenshot in this project, and #36's icon atlas, come out
of a real render pass. Swapping in the stubs would produce blank output with no error, which
is the worst failure shape available.

DO NOT reach for HeadlessMC's LWJGL patching to "simplify" this container away. Its own docs
agree: they say you can "achieve headless mode without patching lwjgl by running headlessmc
with a virtual framebuffer like Xvfb", which is exactly what this Dockerfile is.

The half of HeadlessMC that *is* still interesting is launching a **production** instance,
which RetroFuturaGradle's dev-workspace `runClient` cannot do -- see #146, where the answer is
that we want a launch command rather than a launcher, because this pack runs on Cleanroom and
HeadlessMC's loader list is Fabric, Forge and NeoForge.

## Traps worth knowing before you change this

* **The `-v` sources are UnRAID HOST paths.** `/coding/X` here is `/mnt/user/misc/coding/X`
  there. A wrong one mounts an empty directory and the build fails on a missing repo rather
  than on a bad path.
* **Mount the repository root, not `mod/`.** `runClient` survives a `mod/`-only mount; the
  Java test suite does not, and fails with a bare `IOException` naming nothing.
* **The image must be JDK 25.** RFG 2.0.2 is class file version 69, and on 21 it dies naming
  the RFG plugin, which reads as a broken build script.
* **The harness has its own `GRADLE_USER_HOME`** (`gradle-cache-shot`), because Gradle locks
  it exclusively and sharing one with a concurrent `build` fails on "Timeout waiting to lock
  journal cache". Seed a new one with `cp -a` from the build cache to skip the cold decompile.
  **That separation is per-HARNESS, not per-agent, and the difference bites when several
  agents share this host**: two `shot.sh` runs still collide with each other on
  `gradle-cache-shot`, exactly as two builds collide on `gradle-cache`. `tools/check.sh` and
  `mod/tools/build-jar.sh` document `GRADLE_CACHE=<dir>` for this and the harness path needs
  the same treatment -- pass `CACHE_NAME=gradle-cache-shot-<yours>` and seed it with `cp -a`.
  The message names a PID from another container's namespace, so it reads as a stale lock when
  it usually is not; copy the directory rather than deleting the lock.
* **Cap the container at 4g and never above 8g.** Tower runs the household's Home Assistant
  and its doorbell in sibling containers. The client heap is capped separately, through
  `cmdlineJvmArgs` in `mod/build.gradle`, and the harness prints the heap ceiling it actually
  got so a cap that failed to apply is visible before the OOM.
