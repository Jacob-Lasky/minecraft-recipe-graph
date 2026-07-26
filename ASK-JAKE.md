# Things I need from you

Updated after your answers on 2026-07-26. Resolved decisions are recorded at the bottom
so they don't get re-litigated.

---

## 1. The JDK 8 question is RESOLVED — you don't need sudo

You asked whether running Java 25 changes which JDK we need. It doesn't, but not for the
reason I first assumed, and the conclusion flipped:

- Java 25 is your **runtime**. That works because Cleanroom is a Forge fork modernised for
  it. Irrelevant to building.
- Building 1.12.2 needs a **Java 8 toolchain**. I checked RetroFuturaGradle (GTNH's
  modern replacement for ForgeGradle) hoping to dodge this: its own 1.12 example still
  sets `toolchain { languageVersion = 8 }`, and RFG caps at Gradle 8.8, which won't run on
  Java 25 anyway. So there is no version of this that skips JDK 8.
- **But it doesn't have to be system-installed.** I downloaded Temurin
  `1.8.0_492` as a plain tarball into the scratchpad and pointed `JAVA_HOME` at it. No
  pacman, no sudo, nothing added to your system.

**So: cancel the `sudo pacman -S jdk8-openjdk` ask.** If you'd still rather have JDK 8
installed properly for future modding, it's a nice-to-have, not a blocker.

Build status is in `docs/BUILD.md`.

## 2. Still need from you: one in-game command

Once the jar builds and you drop it in `mods/`:

```
/recipedump
```

Writes `recipes.ndjson`, `oredict.json`, `names.json` into the instance's
`mc-recipe-dump/`. `recipegraph build` picks them up automatically and machine recipes —
including your borax chain — light up.

`/ct oredict` is still worth running independently: your `crafttweaker.log` has zero
oredict lines, so 313 of 548 referenced ore names are currently guessed from display
names rather than read from the game. The dump mod also emits the real oredict, so this
becomes redundant once the mod works.

## 3. The GitHub push is yours to run

Repo is prepped and the privacy cleanup is DONE (see Resolved). MIT licence, CI running
the suite on Python 3.8 and 3.13, Gradle wrapper committed so it bootstraps itself.

```fish
gh repo create minecraft-recipe-graph --public \
  --source ~/Coding/minecraft-recipe-graph --push
```

I have not run this. Publishing is outward-facing and yours to trigger.

## Watch out for

- `items.csv` is from **May 31** and `crafttweaker.log` from **Jul 25**. Fine for now;
  rebuild the graph if you change mods.

---

## Resolved — do not re-ask

- **Production tracking: skipped for now.** The code is written, tested (10 tests) and
  committed, but nothing is scheduled and nothing writes to Tower. `recipegraph track` /
  `chart` work on demand if you want them later. No cron was installed.
- **AE2 scan scope: base 3×3 only.** The 9 region files around `r.<X>.<Z>`: 67 drives,
  467 cells, 3,270 distinct items. Not scanning the other 797 regions.
- **Sharing: GitHub repo under your account**, you push. Not hosting a service on Tower.
- **Work order you picked** (all four): fluid/bucket unification, whole-pack browsable
  explorer, essentia aggregation, recipe-choice overrides. I'm doing fluid/bucket first
  since it's the one that directly affects your borax chains.

- **Essentia: plannable input.** You mentioned multiblocks that convert vis pods into
  essentia, which makes essentia a real intermediate rather than a terminal resource, so it
  needs to be a first-class node (`essentia:<aspect>`) that recipes can both consume and
  produce. Noted: you already hold ~1.47M `thaumadditions:vis_pod`, so those conversion
  chains are live. I need one thing from the game to finish it — see below.
- **Name: `minecraft-recipe-graph`.** Python package is now `recipegraph`, mod artifact
  `mc-recipe-dump`, modid `mcrecipedump`, command `/recipedump`, Java package
  `io.github.jacoblasky.recipedump`. Docs reframed as generic-1.12.2 with MeatballCraft as
  the verified reference pack.
- **Privacy cleanup: done.** `data/` is gitignored AND `data/ae2_have.json` plus all base
  coordinates were purged from every commit via `filter-branch`, with the backup refs
  dropped and the object store gc'd. Verified: no reachable commit contains either.

## Resolved since

- **Fluid units: mB**, never auto-converted to buckets.
- **Essentia: done.** Cells were storing amounts in `Amount` rather than `Cnt`, so all six
  aggregated to zero; 52 aspects now read, as plannable `essentia:<aspect>` nodes. Vis pod
  NBT is decoded per aspect too, so your ~1.47M pods split into 15 distinct ingredients
  instead of one opaque pile.
- **Repo pushed** to github.com/Jacob-Lasky/minecraft-recipe-graph.

## Superseded: the essentia NBT question

Essentia cells are detected in your network (6 × `thaumicenergistics:essentia_cell_64k`)
but aggregate to **zero**, so their contents are not stored under the `#N` keys that item
and fluid cells use. I cannot guess the layout. Either:

- run `/recipedump` once the mod is installed (the aspect ingredient type should surface
  through JEI), or
- tell me a rough location of a drive holding an essentia cell and I will dump that
  tile entity's raw NBT from the save and read the real structure.
