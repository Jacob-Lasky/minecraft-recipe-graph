# Building the dump mod

**Status: builds clean.** Verified 2026-07-26 against MeatballCraft 0.18.4 —
`mbc-recipe-dump-0.1.0.jar`, 4 classes, reobfuscation confirmed. Runtime behaviour
(`/mbcdump` in game) is still unverified; see "What is not yet proven".

```bash
cd mod
./gradlew build -Phei_jar='/path/to/instance/minecraft/mods/HadEnoughItems_1.12.2-4.28.1.jar'
# -> build/libs/mbc-recipe-dump-0.1.0.jar
```

Install by dropping that jar into the instance's `mods/`. It is `clientSideOnly` and adds
nothing but one command, so removing the jar fully reverts it.

## You do not need to install JDK 8

Minecraft 1.12.2 must be compiled by Java 8, but it does not have to be a system JDK.
`settings.gradle` applies `org.gradle.toolchains.foojay-resolver-convention`, so **Gradle
downloads its own Java 8 toolchain** into `$GRADLE_USER_HOME/jdks`. Gradle itself runs on
whatever modern JDK you have (tested on Java 25).

To reuse a JDK 8 you already have instead of downloading one:

```bash
./gradlew build -Porg.gradle.java.installations.paths=/path/to/jdk8 -Phei_jar=...
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

## Why the JEI dependency is a local file

`compileOnly files(hei_jar)` rather than a maven coordinate, because:

1. This pack uses **HadEnoughItems 4.28.1** (modid `jei`), published by
   DimensionalDevelopment. `mezz.jei:jei_1.12.2:4.28.1` **does not exist** — progwml6's
   maven only carries JEI up to 4.16.x for 1.12.2.
2. A 1.12.2 mod jar already carries MCP-named references, and this mod compiles against
   MCP names too, so a plain file dependency links with no deobfuscation step.

Point `hei_jar` at whatever JEI-family jar your pack ships. The mod only touches the
public `mezz.jei.api` surface, so any JEI 4.x should work.

## Verifying a built jar

Reobfuscation is the step most likely to silently not happen, and a non-reobfuscated jar
fails at runtime with confusing `NoSuchMethodError`s. Check the direction of the mapping:

```bash
unzip -p build/libs/mbc-recipe-dump-0.1.0.jar \
  com/meatballcraft/recipedump/DumpCommand.class | strings | grep -cE 'func_|field_'
```

The **production** jar should report a non-zero count (SRG names, 12 at time of writing);
the `-dev` jar should report 0. If production reports 0, reobf did not run.

JEI references must stay human-readable (`mezz/jei/api/IJeiRuntime`) — JEI is not
Minecraft, so its names are never remapped.

## What is not yet proven

The jar compiles, packages and reobfuscates correctly. Not yet confirmed:

- that Forge loads it in a 366-mod pack without a conflict,
- that `@JEIPlugin` discovery works against HEI 4.28 specifically,
- that `/mbcdump` completes over every recipe category without a wrapper throwing.

The third is partly designed for: every category and every recipe is individually
try/caught, and the command reports how many it skipped. Recipe wrappers from third-party
mods genuinely do throw, so a non-zero skip count is expected, not a failure.
