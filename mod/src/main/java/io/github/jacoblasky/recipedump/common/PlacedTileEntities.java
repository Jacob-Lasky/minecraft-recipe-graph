package io.github.jacoblasky.recipedump.common;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import io.github.jacoblasky.recipedump.DumpCommand;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

/**
 * Every machine standing in a LOADED chunk, keyed the way a machine verdict is looked up.
 * #320 phase 2.
 *
 * <h2>WHY A SECOND SOURCE OF `placed` WHEN THE GRID WALK ALREADY FILLS IT</h2>
 *
 * Phase 1 read the machines on the player's ME grid, which was Jake's "simple over complete" call
 * and is genuinely most of the answer for an AE2-centric base. It cannot be the whole answer,
 * because the thing he asked about is not a grid node: measured on #320, ModularMachinery-CE
 * declares `IGridHost` on nothing, so a formed multiblock -- *"the entire structure is completed
 * and you can see the machine"* -- was invisible to phase 1 by construction. So were standalone
 * generators: a placed `nuclearcraft:water_source` joins no grid, and `resolveFreeSources` checks
 * placed blocks BEFORE stock precisely so that owning one makes water free.
 *
 * This is the sanctioned widening `ScenarioSource.PLACED`'s javadoc names: a scan of loaded tile
 * entities, on the server thread, in the same request and reply as the stock read.
 *
 * <h2>THE GATE IS UNCHANGED AND THAT IS THE BALANCE PROPERTY</h2>
 *
 * This runs from {@code Ae2StockReader.read}, AFTER every gate that read passes: a linked wireless
 * terminal in the inventory, an active access point in range, power in the terminal, and EXTRACT
 * permission on the network. No link, no range, no power or no permission and no machine counts as
 * owned, exactly as no item counts as held. Nothing here is granted without an ME connection, which
 * is #320's stated balance decision. DO NOT call this from anywhere that has not passed that gate.
 *
 * <h2>CHUNK-LOADED-ONLY IS THE HONEST SCOPE, and it is stated to the player</h2>
 *
 * `world.loadedTileEntityList` is what the server currently has in memory. Your base is loaded when
 * you are standing in it, which is when you plan -- so this is the right scope rather than merely
 * the affordable one -- but a machine in an unloaded chunk is not seen and must not be claimed.
 * Reading it anyway would mean the region walk `ScenarioSource.PLACED`'s javadoc rules out for a
 * live server. The scope sentence lives in `client.planner.PlanCaveats.PLACED_SCOPE`; keep the two
 * in step.
 *
 * <h2>TWO KEYS PER TILE ENTITY, BECAUSE THE TWO READERS SPELL A PLACED BLOCK DIFFERENTLY</h2>
 *
 * The offline tool keys `placed` by TILE ENTITY ID -- `recipegraph/ae2_inventory.py`'s region walk
 * records `te["id"]` verbatim -- while the graph's machine CANDIDATES are item keys, and
 * `Machines.matchForms` exists to bridge the gap for legacy dotted ids. Phase 1's grid walk keys by
 * item, off AE2's own `getMachineRepresentation()`.
 *
 * So this writes BOTH forms, and that is not belt-and-braces:
 *
 * <ul>
 * <li>the ITEM key is the direct match for a candidate and needs no aliasing to land;</li>
 * <li>the TILE ENTITY ID is what the offline reader produces, so a match that works offline works
 *     in game, and it is the ONLY answer for a block whose item form does not exist -- a machine
 *     with no item drops out of the item path entirely and would otherwise be invisible.</li>
 * </ul>
 *
 * They are usually the same string, and when they are only ONE is counted -- within this scan.
 *
 * <h2>THE COUNT IS A SIGHTING FLAG, NOT A QUANTITY, AND THAT IS A REAL LIMITATION</h2>
 *
 * ACROSS the two sources it double-counts and nothing here prevents it: an ME Drive is a grid node
 * (so `Ae2StockReader.placedOf` counts it) AND a loaded tile entity (so this counts it), and it
 * lands in the merged map as 2. One drive, count 2. The same is true of every AE2 machine in a
 * loaded chunk, which on a real base is most of them.
 *
 * It is INERT TODAY, and that is a measured statement about every consumer rather than a hope.
 * `Machines.candidateVerdict` does `placedIndex.containsKey(norm)` and quotes the KEY;
 * `resolveFreeSources` does `normalisedPlaced.containsKey(norm)`; `ScenarioInputs.resolve` and
 * `Evidence.placed` both test `> 0`. Not one of them reads the number as "how many".
 *
 * SO THE CONSTRAINT IS: NOTHING MAY READ THIS COUNT AS A QUANTITY UNTIL THE TWO SOURCES ARE
 * DEDUPED. A screen showing "you own 2 Drives", a cost model scaling by machine count, or a plan
 * saying "build 3 more" would all be wrong by a factor that varies with how much of your base is
 * loaded. Deduping is not hard -- `IGridBlock.getLocation()` gives a `DimensionalCoord`, so the
 * grid walk could hand this scan the positions it has already counted -- but it is a change to the
 * merge semantics that every consumer above reads, so it belongs with the feature that first needs
 * a real quantity rather than ahead of it. The `ae2-placed` probe logs the per-key counts, so the
 * doubling is observable rather than folklore.
 *
 * <h2>PERFORMANCE: ONE PASS, AND THE PER-BLOCKSTATE WORK IS CACHED</h2>
 *
 * `loadedTileEntityList` on a big server world runs to tens of thousands of entries and this runs
 * once per stock request on the server thread, so the cost per entry is what matters rather than
 * the total. Per tile entity: one `isInstance`, one `isBlockLoaded`, one `getBlockState` (a chunk
 * array lookup), and two map probes. The EXPENSIVE part -- asking a block for its item form, which
 * allocates an ItemStack and runs whatever the mod overrode -- happens once per distinct
 * {@link IBlockState}, not once per tile entity, because 1.12.2 interns block states in
 * `BlockStateContainer` and they can therefore be cached by identity. A room of 400 machines of one
 * type costs one `getItem`.
 *
 * MEASURED RATHER THAN ARGUED, and NOT by a unit test -- there is no way to stand up a
 * `MinecraftServer` with loaded worlds on a JUnit classpath, and a mocked one would be measuring my
 * own fake. The numbers come from the `ae2-placed` probe, which runs this against a real world and
 * logs `Result` twice: once as the world stands, and once after placing a thousand more of ONE
 * block. THE COMPARISON IS THE EVIDENCE AND A SINGLE LINE IS NOT: a world where every tile entity
 * is a different block legitimately reports `keyDerivations == tileEntities`, so that ratio only
 * means something when many entries share a state.
 *
 * <h2>WHAT IT ACTUALLY MEASURED, 2026-08-23, dev harness `ae2` world</h2>
 *
 * <pre>
 *   as placed:                    2 worlds,     5 tile entities,  5 key derivations,  0 ms
 *   after +1000 minecraft:furnace: 2 worlds,  1005 tile entities,  6 key derivations,  9 ms
 * </pre>
 *
 * A THOUSAND MORE ENTRIES ADDED ONE DERIVATION. That is the cache doing exactly what it claims: the
 * cube shares one block state, and the one extra derivation is that state differing from the
 * already-placed furnace's facing. A matching rise of a thousand would have been the cache gone.
 *
 * <h2>AND THE HONEST COST, which is NOT negligible and is not hidden here</h2>
 *
 * 9 ms for 1,005 entries is roughly 9 microseconds each, and it scales linearly because the work
 * per entry is fixed. So a base with 20,000 loaded tile entities extrapolates to around 180 ms --
 * ON THE SERVER THREAD, which means a visible hitch. THAT IS ORDERS OF MAGNITUDE MORE THAN THE GRID
 * WALK IT JOINS, which visits a few dozen nodes. Three things make it acceptable rather than a
 * stall, and if any of them stops being true this needs redesigning rather than tuning:
 *
 * <ul>
 * <li>it is ONCE PER STOCK REQUEST, not once per tick -- and a stock request happens when a player
 *     opens the planner, so the cost lands on an action they took and are waiting on;</li>
 * <li>`LiveStock`'s thirty-second freshness window means opening the planner twice does not pay it
 *     twice;</li>
 * <li>it is bounded by what is already in memory, so it cannot grow with the size of the save.</li>
 * </ul>
 *
 * The 9 ms is a FIRST-RUN figure on a host shared with other builds, so treat it as an upper bound
 * on the per-entry cost rather than a benchmark. DO NOT add a tick handler, a poll or a refresh
 * timer that calls this -- `LiveStock`'s header already rejects polling for the item list, and this
 * is heavier.
 */
public final class PlacedTileEntities {

    /** What one scan cost and what it found, for the log line and for the tests. */
    public static final class Result {

        private final int worlds;
        private final int tileEntities;
        private final int keyDerivations;
        private final long millis;

        Result(int worlds, int tileEntities, int keyDerivations, long millis) {
            this.worlds = worlds;
            this.tileEntities = tileEntities;
            this.keyDerivations = keyDerivations;
            this.millis = millis;
        }

        public int worlds() {
            return worlds;
        }

        /** Tile entities actually examined -- invalid and unloaded ones are not counted. */
        public int tileEntities() {
            return tileEntities;
        }

        /**
         * How many times a block was asked for its item form.
         *
         * THE NUMBER THAT PROVES THE CACHE. It is bounded by the count of distinct block states
         * among the tile entities, so on a real base it is two orders of magnitude below
         * {@link #tileEntities}; if the two ever converge the cache has stopped working.
         */
        public int keyDerivations() {
            return keyDerivations;
        }

        public long millis() {
            return millis;
        }

        @Override
        public String toString() {
            return worlds + " loaded worlds, " + tileEntities + " tile entities, "
                    + keyDerivations + " key derivations, " + millis + " ms";
        }
    }

    private PlacedTileEntities() {
    }

    /**
     * Merge every loaded tile entity into `into`, and say what that cost.
     *
     * MERGES RATHER THAN RETURNS ITS OWN MAP, because the wire carries ONE `placed` field and
     * `StockSnapshot` refuses to have two. #320's design comment settles the question the merge
     * raises -- "the two evidence sources (grid machines, loaded TEs) merge; grid wins nothing over
     * TE or vice versa -- a count is a count" -- and `Machines.candidateVerdict` bears it out: it
     * tests MEMBERSHIP of the placed index and quotes the key, never the count. So there is nothing
     * for a per-source tag on the wire to change, and a second field would be a distinction the
     * reader could not act on.
     *
     * ON THE SERVER THREAD ONLY, for `StockRequestMessage.Handler`'s reason: this walks live world
     * state that the server tick mutates.
     */
    public static Result scan(MinecraftServer server, Map<String, Integer> into) {
        long started = System.currentTimeMillis();
        int worldCount = 0;
        int visited = 0;
        int keyDerivations = 0;
        // IDENTITY-KEYED, which is the point rather than a micro-optimisation: 1.12.2 interns every
        // IBlockState in its block's `BlockStateContainer`, so identity is the cheapest correct
        // comparison and `hashCode` on a state with many properties is not free.
        Map<IBlockState, String> keyByState = new IdentityHashMap<IBlockState, String>();
        // Keyed by tile entity CLASS, since `TileEntity.getKey` is a registry lookup per class and
        // the answer cannot vary between two instances of one class.
        Map<Class<?>, String> idByTileClass = new HashMap<Class<?>, String>();
        if (server == null) {
            return new Result(0, 0, 0, System.currentTimeMillis() - started);
        }
        WorldServer[] loaded = server.worlds;
        if (loaded == null) {
            return new Result(0, 0, 0, System.currentTimeMillis() - started);
        }
        for (WorldServer world : loaded) {
            if (world == null) {
                continue;
            }
            worldCount++;
            List<TileEntity> tiles = world.loadedTileEntityList;
            if (tiles == null) {
                continue;
            }
            // INDEXED, AND THE BOUND IS RE-READ EVERY ITERATION. An iterator over this list would
            // be a ConcurrentModificationException waiting for the first mod that adds a tile
            // entity from a callback we provoke; an index that re-checks `size()` cannot throw, and
            // the worst it can do is skip or repeat one entry, which moves a count by one.
            for (int i = 0; i < tiles.size(); i++) {
                TileEntity te;
                try {
                    te = tiles.get(i);
                } catch (IndexOutOfBoundsException shrank) {
                    break;
                }
                if (te == null || te.isInvalid()) {
                    continue;
                }
                BlockPos pos = te.getPos();
                // `isBlockLoaded` AND NOT A BARE `getBlockState`. `World.getBlockState` goes through
                // `getChunkFromBlockCoords`, which on the SERVER will load or even generate a chunk
                // -- so reading a stale entry out of this list could make a read of the player's
                // network generate terrain. `isBlockLoaded` never loads.
                if (pos == null || !world.isBlockLoaded(pos)) {
                    continue;
                }
                visited++;

                // MODULAR MACHINERY FIRST, AND IT DOES NOT REPLACE THE GENERIC PATH BELOW. A formed
                // controller is two true things at once: you own the controller block, and you own
                // the machine it has become. An unformed one is only the first, which is exactly
                // what falling through with no machine key says.
                String machine = ModularMachineryControllers.formedMachineKey(te);
                if (machine != null) {
                    count(into, machine);
                }

                IBlockState state = world.getBlockState(pos);
                String itemKey = keyByState.get(state);
                if (itemKey == null) {
                    // ABSENCE IS CACHED TOO, as the empty string -- `keyOfPickBlock` never
                    // answers null, which is what makes the dereference below safe. A block with
                    // no item form would otherwise be re-derived for every one of its tile
                    // entities, the one case where the derivation is guaranteed to be wasted.
                    itemKey = keyOfPickBlock(pickBlock(world, pos, state));
                    keyByState.put(state, itemKey);
                    keyDerivations++;
                }
                if (!itemKey.isEmpty()) {
                    count(into, itemKey);
                }

                String tileId = idByTileClass.get(te.getClass());
                if (tileId == null) {
                    ResourceLocation id = TileEntity.getKey(te.getClass());
                    tileId = id == null ? "" : id.toString();
                    idByTileClass.put(te.getClass(), tileId);
                }
                // ONLY WHEN IT DIFFERS. For most machines the tile entity id and the block's item
                // id are the same string, and counting both would report two machines where one
                // stands. See the class note on why both forms are written at all.
                if (!tileId.isEmpty() && !tileId.equals(itemKey)) {
                    count(into, tileId);
                }
            }
        }
        Result result = new Result(worldCount, visited, keyDerivations,
                                   System.currentTimeMillis() - started);
        report(result);
        return result;
    }

    /**
     * Whether the shape of this scan has been logged this session.
     *
     * ONE LINE PER SESSION, AND THAT IS THE DESIGN. The cost of this scan is a claim -- see the
     * performance note on the class -- and a claim about a real base is worth exactly one log line
     * that anyone can go and read. A line per stock read would be spam on a busy server and would
     * be the first thing an admin turned off, taking the measurement with it.
     *
     * VOLATILE AND CHECKED-THEN-SET WITHOUT A LOCK. A race here logs the line twice, which is not a
     * problem worth a monitor on the server thread's hot path.
     */
    private static volatile boolean reported;

    private static void report(Result result) {
        if (reported) {
            return;
        }
        reported = true;
        org.apache.logging.log4j.LogManager
                .getLogger(io.github.jacoblasky.recipedump.RecipeDumpMod.MODID)
                .info("placed-machine scan (#320 phase 2): " + result
                      + "; Modular Machinery controllers "
                      + (ModularMachineryControllers.available()
                              ? "readable"
                              : "NOT read -- "
                                + ModularMachineryControllers.unavailableBecause()));
    }

    /**
     * What a player would get from middle-clicking the block at `pos`, or `ItemStack.EMPTY`.
     *
     * `Block.getItem(World, BlockPos, IBlockState)` IS THE PICK-BLOCK ANSWER, which is the whole
     * reason to use it rather than `Item.getItemFromBlock` plus the state's metadata. A block's
     * state metadata is not its item's metadata: `BlockFurnace` stores its facing as
     * `EnumFacing.getIndex()`, so a furnace facing east is state meta 5 while the item is meta 0,
     * and the naive form would produce `minecraft:furnace:5` and match nothing. `getItem` is what
     * the game itself answers when a player middle-clicks, so a machine keys the way its own mod
     * says it keys.
     *
     * NEVER THROWS, AND NEVER PROPAGATES A THROW. `getItem` runs arbitrary mod code inside a
     * player's stock read; a `Throwable` escaping here reaches `Ae2Stock.read`'s catch-all and the
     * player is told "the linked network no longer exists" for a read that was fine. An empty
     * stack is the honest answer for a block this cannot key.
     */
    private static ItemStack pickBlock(World world, BlockPos pos, IBlockState state) {
        try {
            ItemStack stack = state.getBlock().getItem(world, pos, state);
            return stack == null ? ItemStack.EMPTY : stack;
        } catch (Throwable mods) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * The item key for a pick-block stack, or `""` when the block has no item form.
     *
     * <h2>IT RETURNS `""` AND NEVER null, AND THAT INVARIANT IS THE WHOLE POINT OF THIS METHOD</h2>
     *
     * A REAL DEFECT LIVED HERE, found in review before it shipped. The absence used to be
     * normalised only on the way INTO the per-blockstate cache while the local variable kept the
     * null, so the FIRST tile entity of any block with no item form dereferenced null.
     *
     * WHICH BLOCKS DO IT IS MEASURED THROUGH `Block.getItem`, WHICH IS THE ONLY FUNCTION THAT
     * MATTERS HERE -- and getting that wrong cost two passes. `Item.getItemFromBlock(block)` asks a
     * DIFFERENT question and vanilla overrides `getItem` in both directions: `mob_spawner` HAS an
     * `ItemBlock` yet `getItem` answers EMPTY (you cannot pick-block a spawner), while `lit_furnace`
     * has NO `ItemBlock` yet `getItem` hands back a perfectly good `minecraft:furnace`. A census
     * built on `getItemFromBlock` produced a wrong list AND a live probe criterion that passed for
     * the wrong reason; the probe's own mutation run is what exposed it, by NOT failing.
     *
     * Measured 2026-08-23 through `Block.getItem`: 12 of 254 vanilla blocks answer EMPTY, and FOUR
     * of those have a tile entity -- `piston_extension` (any piston mid-push), `mob_spawner`,
     * `end_portal`, `end_gateway`. Modded blocks add to that set, and the `catch` in
     * {@link #pickBlock} routes into it too.
     *
     * The blast radius was the whole feature and then some -- the NPE escaped {@link #scan}, escaped
     * `Ae2StockReader.read`, and `Ae2Stock.read`'s `catch (Throwable)` reported it as
     * `NETWORK_GONE`, so one piston mid-push or one dungeon spawner in a loaded chunk killed the
     * ENTIRE stock read (phase 1's grid machines and the item list included) with "the linked network
     * no longer exists", AFTER `usePower` had already charged the terminal. MEASURED LIVE, both ways:
     * with the blocker restored, `ae2-placed` reports `available=false, reason=NETWORK_GONE,
     * items=0, placed=0`.
     *
     * So the normalisation happens ONCE, HERE, where the value is produced -- not at the call site,
     * and not twice. `PlacedTileEntitiesTest` pins it for every block whose pick can be taken
     * without a world, and the `ae2-placed` probe places a mob spawner so the exact failure is a
     * permanent live regression rather than a comment.
     *
     * `baseStackKey` RATHER THAN `stackKey`, for `Ae2StockReader.placedOf`'s reason: a machine
     * verdict is a claim about an ITEM and not about one NBT state of it, and a digest here would
     * strand the match against the graph's bare machine key.
     *
     * PACKAGE PRIVATE FOR ITS TEST, which is the only way this invariant is reachable: {@link #scan}
     * needs a `MinecraftServer` with loaded worlds and no JUnit case can stand one up, so the
     * seam is here or the invariant is untested.
     */
    static String keyOfPickBlock(ItemStack pick) {
        String key = DumpCommand.baseStackKey(pick);
        return key == null ? "" : key;
    }

    /** Summed, not put: N sightings of one key are N, and the Nth must not replace the first. */
    private static void count(Map<String, Integer> into, String key) {
        Integer already = into.get(key);
        into.put(key, Integer.valueOf((already == null ? 0 : already.intValue()) + 1));
    }
}
