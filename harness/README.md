# Headless screenshot harness

Renders one of this mod's GUIs to a PNG with no GPU, no window, and nobody at the keyboard.

```bash
harness/shot.sh                 # the fixture panel -> /coding/.recipegraph-build/shots/fixture.png
harness/shot.sh <screen> [name] # any screen registered in ShotScreens
```

Exit code zero means the PNG at the reported path is **this run's**. Non-zero means there is
no new PNG, and the reason is on stdout on a line beginning `[mcrecipedump-shot]`.

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
3. RetroFuturaGradle's `runClient` launches a dev client against a **small mod set** --
   Forge, MixinBooter, ModularUI, HEI, AE2-UEL, JEC and this mod -- staged into `mod/run/mods`
   by `stageDevMods`, which is a `Sync` so that set is exactly what the client loads. Booting
   MeatballCraft is not the point.
4. The mod sees `-Dmcrecipedump.shot=<screen>`, waits for the main menu, opens that screen,
   lets it settle, reads the framebuffer with vanilla's `ScreenShotHelper.createScreenshot`,
   writes the PNG and exits. No world is loaded and no menu is clicked through.

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

`flow-hit` is the one screen that asserts rather than photographs. It parks the real cursor
over each node in turn and logs, side by side, which box `IWidget.isHovering()` reports and
which box the layout says is there. They agree at zoom 0.5, 1.0 and 2.0 -- which is the
evidence that ModularUI's own hit-testing is correct through the scroll viewport *and* the
zoom matrix, and therefore that a diagram click should go through `getWidgetsAt` rather than
through hand-rolled coordinate maths.

`jei` is the one screen that is not ours: it asks `JeiBridge` to open JEI's own recipe page,
so the picture is evidence that the runtime was captured, a focus was created and the GUI was
shown. `harness/shot.sh jei` uses an iron pickaxe; `harness/shot.sh jei:minecraft:furnace`
names another item.
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
| `mcrecipedump.shotTimeoutSeconds` | 600 | Give up waiting for the main menu. |
| `mcrecipedump.shotDebugOverlay` | `false` | Keep ModularUI's widget-outline overlay in the shot. |
| `mcrecipedump.shotTimedFrames` | 0 | Time this many frames after settling, then report. See below. |

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

* **It renders GUIs, not the world.** No world is loaded, so anything that needs a player, a
  tile entity or a server-side capability has nothing to draw from. Phase 5's live AE2 read
  is not testable here.
* **It is not a substitute for the real pack.** Seven mods is not 410. A screen that renders
  here can still collide with something in MeatballCraft -- a conflicting keybind, another
  mod's GUI overlay, a theme override. #19's verification plan keeps one live acceptance run
  per phase for exactly this.
* **It has a cursor, and does not yet have clicks or typing.** This bullet used to say the
  harness had no input at all, and that was never tested -- it was assumed, because there is
  no window manager. There is a real X display, so `Mouse.setCursorPosition` moves the real
  cursor and Minecraft's hover pass runs for real: `IWidget.isHovering()` answers correctly,
  which is enough to exercise a hit-test end to end. `flow-hit` does exactly that, and
  `FlowCanvas.parkCursorOverBox` has the two coordinate conversions it needs (LWJGL's origin
  is the BOTTOM left, in display pixels rather than GUI pixels).

  **Hover is proven; a synthetic click is not.** Minecraft reads button state from LWJGL's
  event queue rather than by polling, so pressing a button is a different problem from moving
  the pointer and nobody has tried it here. Do not write "the harness can click" until
  something has. If it turns out to need real events,
  [HeadlessMC](https://github.com/headlesshq/headlessmc) has a command channel with `gui` and
  `click` verbs and is the fallback #124 named. Read "Why not HeadlessMC" below before
  reaching for it -- take its command channel, not its LWJGL stubs.
* **It is a dev client, so ModularUI's dev behaviour is on.** The widget-outline overlay is
  suppressed for the shot (see the table above), but `ModularUI.isDev` stays true and other
  dev-only branches inside ModularUI are not suppressed.
* **llvmpipe is a software rasteriser.** Frame timings taken here are a floor, not a
  prediction of the desktop's -- see "Measuring frame cost" above for what that does and does
  not license, and for why the number to read is the draw rather than the period.

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
* **Cap the container at 4g and never above 8g.** Tower runs the household's Home Assistant
  and its doorbell in sibling containers. The client heap is capped separately, through
  `cmdlineJvmArgs` in `mod/build.gradle`, and the harness prints the heap ceiling it actually
  got so a cap that failed to apply is visible before the OOM.
