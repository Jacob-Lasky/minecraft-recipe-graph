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
/mbcdump
```

Writes `recipes.ndjson`, `oredict.json`, `names.json` into the instance's
`mbc-recipe-dump/`. `mbcgraph build` picks them up automatically and machine recipes —
including your borax chain — light up.

`/ct oredict` is still worth running independently: your `crafttweaker.log` has zero
oredict lines, so 313 of 548 referenced ore names are currently guessed from display
names rather than read from the game. The dump mod also emits the real oredict, so this
becomes redundant once the mod works.

## 3. The GitHub push is yours to run

You picked "push to GitHub under your account", me prepping and you authing. Repo is
prepped: MIT licence, CI workflow running the test suite, README written for people who
have never seen it. Two commits, clean tree.

When you're ready:

```fish
gh repo create mbc-recipe-graph --public --source ~/Coding/mbc-recipe-graph --push
```

I deliberately have not run this. Publishing is outward-facing and yours to trigger, and
it would also make your AE2 inventory and base region coordinates public — see the note
in "Watch out for" below.

## 4. Open questions (low stakes, answer whenever)

- **Essentia**: you have 6 `thaumicenergistics:essentia_cell_64k`. Aggregating them is on
  the work list you picked. Do you want essentia treated as a plannable input (so
  Thaumcraft chains resolve against vis/essentia on hand), or just reported as a number?
- **Fluid units**: recipes consume fluids in mB (1000 per bucket). Do you want plans shown
  in mB, or in buckets where it divides cleanly?
- **The mod's identity**: currently `modid=mbcrecipedump`, author "Jacob Lasky", URL
  pointing at a `jacoblasky/mbc-recipe-graph` GitHub path that doesn't exist yet. Tell me
  the real repo path and I'll fix the metadata before you publish.

## Watch out for

- **Publishing the repo publishes `data/ae2_have.json` if it's committed.** It currently
  is, and it contains your full item list. Region filenames in docs also disclose your
  base coordinates. Neither is dangerous on a
  private server, but say the word and I'll gitignore the data dir and scrub coords from
  the docs before you push.
- `items.csv` is from **May 31** and `crafttweaker.log` from **Jul 25**. Fine for now;
  rebuild the graph if you change mods.

---

## Resolved — do not re-ask

- **Production tracking: skipped for now.** The code is written, tested (10 tests) and
  committed, but nothing is scheduled and nothing writes to Tower. `mbcgraph track` /
  `chart` work on demand if you want them later. No cron was installed.
- **AE2 scan scope: base 3×3 only.** The 9 region files around `r.<X>.<Z>`: 67 drives,
  467 cells, 3,270 distinct items. Not scanning the other 797 regions.
- **Sharing: GitHub repo under your account**, you push. Not hosting a service on Tower.
- **Work order you picked** (all four): fluid/bucket unification, whole-pack browsable
  explorer, essentia aggregation, recipe-choice overrides. I'm doing fluid/bucket first
  since it's the one that directly affects your borax chains.
