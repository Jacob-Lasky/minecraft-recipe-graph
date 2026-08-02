# Building the dump mod

```bash
cd mod
./gradlew build -Ppack_mods='/path/to/instance/minecraft/mods'
# -> build/libs/mc-recipe-dump-<version>.jar   (and a -dev jar; install the other one)
```

`pack_mods` is a directory, not a jar: the build resolves HadEnoughItems, ModularUI and
AE2-UEL out of it by glob, and reports **all** the missing ones at once rather than the first.
Override a single entry with `-Phei_jar=`, `-Pmodularui_jar=` or `-Pae2_jar=` when a pack ships
two matching jars and the glob refuses to guess.

Install by dropping the jar into the instance's `mods/`. Move the superseded one out to
`<instance>/mods-archive/` rather than leaving it beside the new one -- both declare modid
`mcrecipedump`, and two of those is a duplicate-mod failure at startup.

## You do not need to install JDK 8

Minecraft 1.12.2 must be compiled by Java 8, but it does not have to be a system JDK.
`settings.gradle` applies `org.gradle.toolchains.foojay-resolver-convention`, so **Gradle
downloads its own Java 8 toolchain** into `$GRADLE_USER_HOME/jdks`. Gradle itself runs on
whatever modern JDK you have (tested on Java 25).

To reuse a JDK 8 you already have instead of downloading one:

```bash
./gradlew build -Porg.gradle.java.installations.paths=/path/to/jdk8 -Ppack_mods=...
```

## Why RetroFuturaGradle and not ForgeGradle 2.3

ForgeGradle 2.3 is the plugin every 1.12.2 tutorial names. **It can no longer resolve
Forge**, so do not start there. Measured, not assumed:

| Artifact | Result |
| --- | --- |
| `forge-1.12.2-14.23.5.2860-userdev.jar` (what FG2 asks for) | **404** |
| `forge-1.12.2-14.23.5.2860-userdev3.jar` | 200 |
| Same file on GTNH / Sponge / covers1624 mirrors | 404 |

`userdev3` is the FG3+ layout — `config.json`, `joined.lzma`, `patches`, and crucially
**no `dev.json`** — which FG2's `extractUserdev` cannot read. Renaming `userdev3` to
`userdev` therefore fails too; it is a different format, not a different name.

Pinning newer FG releases does not help either. `2.3-SNAPSHOT` resolves to 2.3.4, and even
the newest 2.3.x on Forge's maven (2.3.10) still requests the plain `userdev` classifier.

**RetroFuturaGradle 2.0.2** is the working path, and it is what
[CleanroomMC's own 1.12.2 template](https://github.com/CleanroomMC/ForgeDevEnv) uses —
relevant because Cleanroom is the loader this pack runs on. Note RFG's README claims
Gradle 7.6–8.8; that is stale, Cleanroom's template ships a Gradle 9.6.1 wrapper and it
works.

## Why the mod dependencies are local files

`compileOnly files(...)` rather than maven coordinates, because these builds are not on maven.
This pack uses **HadEnoughItems 4.28.1** (modid `jei`), published by DimensionalDevelopment;
`mezz.jei:jei_1.12.2:4.28.1` **does not exist**, since progwml6's maven only carries JEI up to
4.16.x for 1.12.2. ModularUI and AE2-UEL have the same problem. The pack is the only place the
versions the pack runs are guaranteed to exist.

Compiling against a pack jar needs no deobfuscation, because compilation reads only that mod's
own signatures and reobfuscation does not touch those.

**RUNNING one in a plain JUnit JVM is a different matter, and the difference is silent.** A
pack jar is a production build, so the Minecraft calls *inside* it are SRG
(`ResourceLocation.func_110623_a`), while this mod and RFG's test classpath are MCP
(`getResourcePath`). In game everything is SRG, and inside a dev workspace FML remaps mod jars
as it loads them, so neither the real client nor `runClient` notices. A JUnit JVM has neither,
and the call dies with `NoSuchMethodError`. That is why the ModularUI **test** dependency goes
through `rfg.deobf` while the compile-time one does not — see the comment in `mod/build.gradle`
for how quietly it fails when it is missing.

## Verifying a built jar

Reobfuscation is the step most likely to silently not happen, and a non-reobfuscated jar
fails at runtime with confusing `NoSuchMethodError`s. Check the direction of the mapping:

```bash
unzip -p build/libs/mc-recipe-dump-<version>.jar \
  io/github/jacoblasky/recipedump/DumpCommand.class | strings | grep -cE 'func_|field_'
```

The **production** jar reports a non-zero count (SRG names; 32 at v0.9.0); the `-dev` jar
reports 0. If production reports 0, reobf did not run and you are about to install the wrong
one of the two jars in `build/libs`.

JEI references must stay human-readable (`mezz/jei/api/IJeiRuntime`) — JEI is not
Minecraft, so its names are never remapped.

A version string does not identify a build: two jars can both say 0.9.0 and differ. From v0.6.0
the jar embeds `mcrecipedump-source.sha256`, a hash over `mod/src/main/java`, and
`tests/test_dist_jar.py` recomputes it. That stamp is the only reliable "is this jar current"
signal; size and sha256 differ between two builds of identical source.

## What this file does not tell you

**Which jar is installed, and what it can do in game.** Runtime behaviour was proven at v0.8.0:
Forge loads the mod in the full pack, `@JEIPlugin` discovery works against HEI 4.28, and
`/recipedump` walks every category (it try/catches per category and per recipe and reports how
many it skipped, so a non-zero skip count is expected rather than a failure). Whether the jar
currently sitting in an instance is the current one is a question for the jar, not for this
file — read its `SCHEMA` constant with `tests.test_dist_jar._jar_schema(path)` and its source
hash. A recorded "what is installed" line here would be a rot generator, and this project has
already paid for one.
