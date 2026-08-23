package io.github.jacoblasky.recipedump.common.ae2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static io.github.jacoblasky.recipedump.common.ae2.Snapshots.NO_MACHINES;

import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonObject;

import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The type whose entire job is keeping "the network holds nothing" apart from "I could not
 * read the network".
 *
 * WHY THAT DISTINCTION IS WORTH A TYPE AND A TEST CLASS. The planner prices every route
 * against what you own. Give it an empty map when the read failed and it does not fail -- it
 * produces a complete, confident, wrong plan telling you to craft several thousand things
 * already sitting in your base. A refusal is visible; a wrong plan is not. Every case here is
 * an angle on that one confusion.
 */
public class StockSnapshotTest {

    @BeforeClass
    public static void bootstrap() {
        // NBT tag types register here; the serialisation cases need them.
        Bootstrap.register();
    }

    private static Map<String, Long> counts(Object... pairs) {
        Map<String, Long> out = new LinkedHashMap<String, Long>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], Long.valueOf(((Number) pairs[i + 1]).longValue()));
        }
        return out;
    }

    @Test
    public void anEmptyNetworkIsAvailableAndAFailedReadIsNot() {
        // The whole point. Both have no items; only one of them is an answer.
        StockSnapshot empty = StockSnapshot.of(counts(), NO_MACHINES);
        assertTrue(empty.isAvailable());
        assertEquals(0, empty.distinctKeys());

        StockSnapshot refused = StockSnapshot.unavailable(StockSnapshot.Reason.OUT_OF_RANGE);
        assertFalse(refused.isAvailable());
        assertEquals(0, refused.distinctKeys());
    }

    @Test
    public void okCannotBeUsedAsARefusal() {
        // Otherwise `unavailable(OK)` is a snapshot that is not available and reports no
        // reason, which is the ambiguous state this type exists to make unrepresentable.
        try {
            StockSnapshot.unavailable(StockSnapshot.Reason.OK);
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("not a refusal"));
        }
    }

    @Test
    public void everyRefusalNamesSomethingDifferentToGoAndDo() {
        // Nine reasons and nine distinct sentences: "no wireless terminal" and "out of range"
        // are different problems with different fixes, and collapsing them into one "could
        // not read your network" would make the message useless exactly when it matters.
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (StockSnapshot.Reason reason : StockSnapshot.Reason.values()) {
            assertNotNull(reason.message());
            assertFalse(reason.name(), reason.message().isEmpty());
            assertTrue("duplicate message: " + reason, seen.add(reason.message()));
        }
        assertEquals(StockSnapshot.Reason.values().length, seen.size());
    }

    @Test
    public void countsSurviveTheWireExactly() {
        StockSnapshot sent = StockSnapshot.of(counts(
                "minecraft:cobblestone", 2_000_000L,
                "forestry:can:1#48a337d94489", 3L,
                "fluid:water", 16_000L), NO_MACHINES);
        StockSnapshot back = StockSnapshot.deserializeNBT(sent.serializeNBT());

        assertTrue(back.isAvailable());
        assertEquals(3, back.distinctKeys());
        assertEquals(2_000_000L, back.count("minecraft:cobblestone"));
        assertEquals(3L, back.count("forestry:can:1#48a337d94489"));
        assertEquals(0L, back.count("minecraft:diamond"));
    }

    @Test
    public void aCountPastIntRangeIsNotWrapped() {
        // The reference save holds 71.8 million items, and a bulk key well past 2^31 is
        // ordinary. A narrowed count wraps NEGATIVE and reads as owning none of the thing you
        // own most of.
        long huge = 5L * Integer.MAX_VALUE;
        StockSnapshot back = StockSnapshot.deserializeNBT(
                StockSnapshot.of(counts("minecraft:cobblestone", huge), NO_MACHINES).serializeNBT());
        assertEquals(huge, back.count("minecraft:cobblestone"));
    }

    @Test
    public void aRefusalSurvivesTheWireAsARefusal() {
        for (StockSnapshot.Reason reason : StockSnapshot.Reason.values()) {
            if (reason == StockSnapshot.Reason.OK) {
                continue;
            }
            StockSnapshot back = StockSnapshot.deserializeNBT(
                    StockSnapshot.unavailable(reason).serializeNBT());
            assertFalse(reason.name(), back.isAvailable());
            assertEquals(reason, back.reason());
        }
    }

    @Test
    public void aPayloadThisBuildCannotUnderstandIsARefusalAndNeverAnEmptyNetwork() {
        // The direction that matters. A reason from a newer build, or a corrupt packet, must
        // not decode as "read succeeded, you own nothing" -- that is the confidently-wrong
        // plan arriving over the wire instead of out of a bug.
        NBTTagCompound alien = new NBTTagCompound();
        alien.setString("reason", "SOME_FUTURE_REASON");
        assertFalse(StockSnapshot.deserializeNBT(alien).isAvailable());

        assertFalse(StockSnapshot.deserializeNBT(new NBTTagCompound()).isAvailable());
    }

    @Test
    public void theCountsMapCannotBeEditedThroughTheSnapshot() {
        // A caller mutating what it was handed would change a record of what the network held
        // at an instant, which is the one thing a snapshot promises not to do.
        StockSnapshot snapshot = StockSnapshot.of(counts("minecraft:stone", 1L), NO_MACHINES);
        try {
            snapshot.counts().put("minecraft:dirt", Long.valueOf(1L));
            throw new AssertionError("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            assertEquals(1, snapshot.distinctKeys());
        }
    }

    @Test
    public void theSnapshotDoesNotAliasTheMapItWasBuiltFrom() {
        Map<String, Long> source = counts("minecraft:stone", 1L);
        Map<String, Integer> grid = Snapshots.machines("mod:press", 1);
        StockSnapshot snapshot = StockSnapshot.of(source, grid);
        source.put("minecraft:dirt", Long.valueOf(64L));
        grid.put("mod:crusher", Integer.valueOf(4));
        assertEquals(1, snapshot.distinctKeys());
        assertEquals(0L, snapshot.count("minecraft:dirt"));
        // BOTH HALVES, because the placed map is the newer one and a copy that was made for
        // the items and aliased for the machines would let a caller add a machine to a record
        // of what the grid carried at an instant.
        assertEquals(1, snapshot.distinctPlacedKeys());
        assertEquals(0, snapshot.placedCount("mod:crusher"));
    }

    // -- the placed half, which shares one reason with the items (#320) ----------------------

    @Test
    public void aGridWithNoMachinesIsAvailableAndAFailedReadIsNot() {
        // The same distinction the items half is built on, asserted separately because the
        // placed half is where it is newly load-bearing: an empty map means "nothing on your
        // grid is a machine", and a refusal means "I never looked". Both carry no machines.
        StockSnapshot bare = StockSnapshot.of(counts(), NO_MACHINES);
        assertTrue(bare.isAvailable());
        assertEquals(0, bare.distinctPlacedKeys());

        StockSnapshot refused = StockSnapshot.unavailable(StockSnapshot.Reason.NO_POWER);
        assertFalse(refused.isAvailable());
        assertEquals(0, refused.distinctPlacedKeys());
    }

    @Test
    public void placedMachinesSurviveTheWireExactly() {
        StockSnapshot sent = StockSnapshot.of(
                counts("minecraft:cobblestone", 64L),
                Snapshots.machines("appliedenergistics2:drive", 8,
                                   "packagedauto:crafter", 3,
                                   "thermalexpansion:machine:1", 1));
        StockSnapshot back = StockSnapshot.deserializeNBT(sent.serializeNBT());

        assertTrue(back.isAvailable());
        assertEquals(3, back.distinctPlacedKeys());
        assertEquals(8, back.placedCount("appliedenergistics2:drive"));
        assertEquals(3, back.placedCount("packagedauto:crafter"));
        // A metadata'd key survives intact: `thermalexpansion:machine` is one block at several
        // meta values, so dropping the `:1` would name the wrong machine rather than none.
        assertEquals(1, back.placedCount("thermalexpansion:machine:1"));
        assertEquals(0, back.placedCount("minecraft:furnace"));
        // The other half is untouched by the new one.
        assertEquals(64L, back.count("minecraft:cobblestone"));
    }

    @Test
    public void aRefusalCarriesNoMachinesOverTheWireEither() {
        // The direction that matters, one field over from `aRefusalSurvivesTheWireAsARefusal`:
        // a refusal that decoded as an available read with no machines would price every
        // machine the player owns as something to go and build, with no caveat saying why.
        for (StockSnapshot.Reason reason : StockSnapshot.Reason.values()) {
            if (reason == StockSnapshot.Reason.OK) {
                continue;
            }
            StockSnapshot back = StockSnapshot.deserializeNBT(
                    StockSnapshot.unavailable(reason).serializeNBT());
            assertFalse(reason.name(), back.isAvailable());
            assertEquals(reason.name(), 0, back.distinctPlacedKeys());
        }
    }

    @Test
    public void aNonPositivePlacedCountIsDroppedRatherThanCarried() {
        // Zero sightings of a machine is not a sighting. `Evidence.placed` refuses one already,
        // but `ScenarioInputs.resolveFreeSources` indexes the key regardless of its count -- so
        // a zero arriving here would make water free off a block nobody built.
        NBTTagCompound payload = new NBTTagCompound();
        payload.setString("reason", "OK");
        net.minecraft.nbt.NBTTagList rows = new net.minecraft.nbt.NBTTagList();
        rows.appendTag(row("mod:real", 2));
        rows.appendTag(row("mod:counted_none", 0));
        rows.appendTag(row("mod:negative", -1));
        rows.appendTag(row("", 5));
        payload.setTag("placed", rows);

        StockSnapshot back = StockSnapshot.deserializeNBT(payload);
        assertTrue(back.isAvailable());
        assertEquals(1, back.distinctPlacedKeys());
        assertEquals(2, back.placedCount("mod:real"));
        assertEquals(0, back.placedCount("mod:counted_none"));
    }

    @Test
    public void aPayloadWithNoPlacedListStillCarriesTheStockItHas() {
        // ASSERTED AS AN ASYMMETRY, deliberately. An unrecognised REASON refuses the whole
        // snapshot; an absent placed LIST does not, because refusing would throw away a stock
        // read that is present and correct and regress to "you own nothing" over the newer
        // field. One jar serves both sides, so this cannot arise between two halves of a build
        // -- it is pinned so that the choice is visible rather than incidental.
        NBTTagCompound payload = new NBTTagCompound();
        payload.setString("reason", "OK");
        net.minecraft.nbt.NBTTagList items = new net.minecraft.nbt.NBTTagList();
        NBTTagCompound item = new NBTTagCompound();
        item.setString("k", "minecraft:iron_ingot");
        item.setLong("n", 377L);
        items.appendTag(item);
        payload.setTag("items", items);

        StockSnapshot back = StockSnapshot.deserializeNBT(payload);
        assertTrue(back.isAvailable());
        assertEquals(377L, back.count("minecraft:iron_ingot"));
        assertEquals(0, back.distinctPlacedKeys());
    }

    @Test
    public void theSameMachineSeenTwiceIsOneKeyAtACountOfTwo() {
        // A grid with eight drives is eight sightings of one key, and the count is what the
        // offline tool's `placed` map carries too.
        StockSnapshot snapshot = StockSnapshot.of(
                counts(), Snapshots.machines("appliedenergistics2:drive", 8));
        assertEquals(1, snapshot.distinctPlacedKeys());
        assertEquals(8, snapshot.placedCount("appliedenergistics2:drive"));
    }

    @Test
    public void thePlacedMapCannotBeEditedThroughTheSnapshot() {
        StockSnapshot snapshot = StockSnapshot.of(counts(),
                                                  Snapshots.machines("mod:press", 1));
        try {
            snapshot.placed().put("mod:crusher", Integer.valueOf(1));
            throw new AssertionError("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            assertEquals(1, snapshot.distinctPlacedKeys());
        }
    }

    @Test
    public void thePlacedDocumentIsThePlacedFieldAPlanFixtureWrites() {
        JsonObject document = StockSnapshot.of(
                counts("minecraft:stone", 64L),
                Snapshots.machines("nuclearcraft:alloy_furnace", 2)).placedDocument();
        assertEquals(1, document.entrySet().size());
        assertEquals(2, document.get("nuclearcraft:alloy_furnace").getAsInt());
        // NOT the item counts. The two documents are two scenario fields and swapping them
        // would price stock as machines and machines as stock.
        assertFalse(document.has("minecraft:stone"));
    }

    @Test
    public void aRefusalPlacedDocumentsAsEmptyAndTheCALLERHasToNotice() {
        StockSnapshot refused = StockSnapshot.unavailable(StockSnapshot.Reason.NO_PERMISSION);
        assertTrue(refused.placedDocument().entrySet().isEmpty());
        assertFalse(refused.isAvailable());
    }

    private static NBTTagCompound row(String key, int count) {
        NBTTagCompound out = new NBTTagCompound();
        out.setString("k", key);
        out.setInteger("n", count);
        return out;
    }

    // -- the scenario field ---------------------------------------------------------------

    @Test
    public void theDocumentIsTheHaveFieldAPlanFixtureWrites() {
        // Keys stay keys, digest and all, and counts stay longs. This is what a plan is priced
        // against, so a narrowed count here is a route costed as though the player owned none.
        Map<String, Long> held = counts("minecraft:stone", 64L);
        held.put("thermalfoundation:material:128#a1b2c3", Long.valueOf(71_800_000L));
        JsonObject document = StockSnapshot.of(held, NO_MACHINES).document();
        assertEquals(2, document.entrySet().size());
        assertEquals(64L, document.get("minecraft:stone").getAsLong());
        assertEquals(71_800_000L,
                     document.get("thermalfoundation:material:128#a1b2c3").getAsLong());
    }

    @Test
    public void aRefusalDocumentsAsEmptyAndTheCALLERHasToNotice() {
        // Empty here is NOT the claim "you own nothing" -- it is only safe because the refusal
        // travels beside it on `ScenarioSource`. The assertion is on the pairing, not on the
        // emptiness: a reader that shipped this document without the reason would be back at
        // the confidently-wrong plan.
        StockSnapshot refused = StockSnapshot.unavailable(StockSnapshot.Reason.OUT_OF_RANGE);
        assertTrue(refused.document().entrySet().isEmpty());
        assertFalse(refused.isAvailable());
    }
}
