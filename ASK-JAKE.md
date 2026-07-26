# Things I need from you

Ordered by how much they unblock. Items 1 and 2 together are what make `Borax` — the
thing you originally asked for — actually resolvable.

---

## 1. Install JDK 8 so I can build the dump mod  ← biggest unblock

The mod in `mod/` is written and every JEI API signature in it was verified with `javap`
against your `HadEnoughItems_1.12.2-4.28.1.jar`. It has **not been compiled**, because
1.12.2 needs ForgeGradle 2.3, which only runs on JDK 8, and this box has JDK 25 only.

It's in the official Arch `extra` repo, so:

```fish
sudo pacman -S jdk8-openjdk
```

Then I can run the build myself:

```fish
cd ~/Coding/mbc-recipe-graph/mod
JAVA_HOME=/usr/lib/jvm/java-8-openjdk ./gradlew build
```

I did not run the pacman command because it needs sudo and you were away. Nothing else
about the project depends on this — the AE2 reader and the offline graph both work now.

**Honest caveat:** the ForgeGradle 2.3 toolchain is old and first-run setups sometimes
need a nudge (maven URL changes, mappings availability). I've pinned what should be a
known-good combination but I can't claim it builds clean until it does. That's a
when-I-can-run-it problem, not a design risk — the code surface is small and verified.

## 2. Run two commands in game (30 seconds)

Once the mod is built and dropped in `mods/`, in a single-player world or on the server
with the client attached:

```
/mbcdump
```

Writes `recipes.ndjson`, `oredict.json`, `names.json` into the instance's
`mbc-recipe-dump/`. Then `mbcgraph build` picks them up automatically and machine
recipes light up.

If you want the ore dictionary *before* the mod is ready, this alone helps a lot:

```
/ct oredict
```

That appends "Ore entries for ..." lines to `crafttweaker.log`, which the builder
already knows how to read. Right now your `crafttweaker.log` has **zero** oredict lines,
so 315 of 548 referenced ore names are unresolved and I'm guessing the rest from display
names.

---

## 3. Decisions I made for you (say the word and I'll change them)

- **Repo lives at `~/Coding/mbc-recipe-graph`**, MIT licensed, no git remote yet. I did
  not create a GitHub repo or push — that's outward-facing and yours to trigger.
- **Name**: `mbc-recipe-graph`. Fine for the community; rename is cheap now, annoying later.
- **AE2 read is offline (world save), not live (OpenComputers).** Your pack has
  OpenComputers 1.8.7 with a working AE2 driver (`getItemsInNetwork`, `getCraftables`,
  `getFluidsInNetwork` — all confirmed present in the jar), so a live feed is possible
  and would be strictly better data: it sees storage buses and knows what AE2 can already
  autocraft. It needs you to build a computer + adapter against your ME network and run a
  Lua script. I went offline-first because you asked me to just read your system rather
  than hand you a build task. Want the live version too? I'll write the Lua.
- **Heuristic oredict inference is ON by default**, labelled as a heuristic in the build
  output. Turn it off with `--no-guess`. I judged a labelled guess more useful than
  `[oredict] ingotIron` in every shopping list, but it is a guess.

## 4. Open questions

- **Sharing shape.** You said skill now, plugin/repo long term. I've written
  `skill/SKILL.md` so you can use it here immediately. For the community the real
  question is whether to ship the *tool* (repo + mod, they run it) or a *hosted service*
  (they upload a dump, get a URL). Hosted is friendlier but it's your Tower and your
  bandwidth, so that's your call.
- **Which regions count as "your base"?** I scanned the 3×3 block around
  `r.<X>.<Z>` because those 9 files were all modified in the same save tick, which is a
  good "actively loaded" signal. If you have AE2 networks in other dimensions or far-flung
  bases, name them and I'll widen the scan — the world has 806 region files and scanning
  all of them is slow but perfectly possible.
- **Essentia.** Your network has 6 `thaumicenergistics:essentia_cell_64k`. I detect them
  but don't aggregate essentia yet. Worth doing?

## 5. Things I found that you might care about independently

- Your `crafttweaker.log` is from **Jul 25**, and `items.csv` from **May 31**. Both are
  stale-ish but fine for this. If you change mods, rebuild the graph.
- `items.csv` has 53,360 rows but only 32,861 unique canonical keys — the rest are
  duplicate display names across metas. Not a problem, just noting the gap so the number
  doesn't look wrong later.
- The pack ships **cc-tweaked 1.89.2 and Plethora** alongside OpenComputers, so there are
  two independent scripting routes to a live AE2 feed if OC turns out to be awkward.
