package io.github.jacoblasky.recipedump.common.ae2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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
        StockSnapshot empty = StockSnapshot.of(counts());
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
                "fluid:water", 16_000L));
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
                StockSnapshot.of(counts("minecraft:cobblestone", huge)).serializeNBT());
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
        StockSnapshot snapshot = StockSnapshot.of(counts("minecraft:stone", 1L));
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
        StockSnapshot snapshot = StockSnapshot.of(source);
        source.put("minecraft:dirt", Long.valueOf(64L));
        assertEquals(1, snapshot.distinctKeys());
        assertEquals(0L, snapshot.count("minecraft:dirt"));
    }

    // -- the scenario field ---------------------------------------------------------------

    @Test
    public void theDocumentIsTheHaveFieldAPlanFixtureWrites() {
        // Keys stay keys, digest and all, and counts stay longs. This is what a plan is priced
        // against, so a narrowed count here is a route costed as though the player owned none.
        Map<String, Long> held = counts("minecraft:stone", 64L);
        held.put("thermalfoundation:material:128#a1b2c3", Long.valueOf(71_800_000L));
        JsonObject document = StockSnapshot.of(held).document();
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
