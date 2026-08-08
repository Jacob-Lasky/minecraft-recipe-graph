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
GUI work, so without this every layout tweak costs a manual launch of a 410-mod pack and the
whole plan is paced by how often someone will sit through one. That is the constraint this
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
document that can be frozen in `tests/fixtures/plan/`; a machines table is a verdict on all 503
categories resolved from placed blocks and stock against a 121 MB graph, and a six-category
stand-in would photograph a screen no player will ever see -- the columns are sized from the
data, so it would not even have the right geometry. Without `RECIPEGRAPH_ORACLE` these shoot the
"no graph.json, looked in ..." panel and SUCCEED, which is the picture a new player sees and is
worth having. **So check which one a PR's screenshot actually is**: the two are easy to tell
apart full size and easy to confuse in a thumbnail, and the run logs `machines: <what happened>`
either way.

`flow-hit`, `ae2-probe` and `dump` ASSERT rather than photograph, and a screen in that shape
owes the harness a verdict. It declares one with `ShotScreens.expectReport(...)`
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

`ae2-probe` needs a world: `harness/shot.sh ae2-probe ae2probe
-Dmcrecipedump.shotWorld=ae2`. Measured at 177 s and 189 s on a warm cache with one Java file
changed -- above the table below because of the world load, and because both runs shared Tower
with other builds. Without `shotWorld` it refuses and says so, because a
grid exists only on the server. It also declares its own settle window through
`ShotScreens.requestSettleFrames`, since it waits twenty SERVER ticks for AE2 to connect its
nodes and the default twenty RENDER frames is shorter than that -- a screen that needs time
says so in code rather than in an incantation the next person has to know to type. See the AE2
bullet under Limits for what its verdict does and does not establish.

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
| `mcrecipedump.shotTimeoutSeconds` | 600 | Give up waiting for the menu, **and for a screen that is holding the capture**. Raise it for a big pack: 600 sits inside the estimated 410-jar boot range, so a healthy run would time out. Not raised by default, because for the five-jar dev set 600 is generous and a larger default only makes a genuinely hung run take longer to fail. |
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
  the test, which is the thing that was in doubt. #191 is where the probe that drives the real
  path belongs.
* **It is not a substitute for the real pack. TRUE BY CONSTRUCTION.** Seven mods is not 410. A
  screen that renders here can still collide with something in MeatballCraft -- a conflicting
  keybind, another mod's GUI overlay, a theme override. #19's verification plan keeps one live
  acceptance run per phase for exactly this.
* **It has a cursor. MEASURED. Clicks and typing: UNKNOWN.** There is a real X display, so
  `Mouse.setCursorPosition` moves the real cursor and Minecraft's hover pass runs for real:
  `IWidget.isHovering()` answers correctly, which is enough to exercise a hit-test end to end.
  `flow-hit` does exactly that, and `FlowCanvas.parkCursorOverBox` has the two coordinate
  conversions it needs (LWJGL's origin is the BOTTOM left, in display pixels rather than GUI
  pixels).

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
