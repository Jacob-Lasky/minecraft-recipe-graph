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
   OpenGL 2.1, comfortably inside what a software rasteriser covers.
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

## Limits, and what this is not

* **It renders GUIs, not the world.** No world is loaded, so anything that needs a player, a
  tile entity or a server-side capability has nothing to draw from. Phase 5's live AE2 read
  is not testable here.
* **It is not a substitute for the real pack.** Seven mods is not 410. A screen that renders
  here can still collide with something in MeatballCraft -- a conflicting keybind, another
  mod's GUI overlay, a theme override. #19's verification plan keeps one live acceptance run
  per phase for exactly this.
* **It has no input.** Nothing clicks, scrolls or types. A screen that only reaches an
  interesting state after interaction needs to expose that state through the `:arg` in its
  spec. If real input ever becomes necessary,
  [HeadlessMC](https://github.com/headlesshq/headlessmc) has a command channel with `gui` and
  `click` verbs and is the fallback #124 named; it was not needed, because llvmpipe carried
  it.
* **It is a dev client, so ModularUI's dev behaviour is on.** The widget-outline overlay is
  suppressed for the shot (see the table above), but `ModularUI.isDev` stays true and other
  dev-only branches inside ModularUI are not suppressed.
* **llvmpipe is a software rasteriser.** Frame timings taken here are a floor, not a
  prediction of the desktop's. That is fine for the 60 fps panning gate in the direction that
  matters -- passing here means passing there -- and worthless in the other.

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
