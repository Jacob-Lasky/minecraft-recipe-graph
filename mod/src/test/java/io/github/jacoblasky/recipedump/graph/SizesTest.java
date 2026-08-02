package io.github.jacoblasky.recipedump.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The size arithmetic every number in the heap report rests on.
 *
 * WHY THIS IS WORTH A TEST AND NOT OBVIOUS. The heap gate is decided by comparing an
 * ANALYTICAL total against a MEASURED post-GC heap delta, and the two agreeing is the whole
 * argument that either can be trusted. If {@link Sizes#array} were wrong by a header, the
 * analytical side would be wrong by ~16 bytes times several million arrays and the
 * disagreement would be attributed to the collector rather than to this arithmetic.
 *
 * The constants assume a 64-bit HotSpot with compressed oops, which is what a Minecraft
 * client under a sub-32 GB heap gets. These cases pin the arithmetic, not the platform; the
 * harness prints the JVM it ran on so a reading taken elsewhere is identifiable.
 */
public class SizesTest {

    @Test
    public void everySizeIsRoundedUpToTheEightByteObjectAlignment() {
        assertEquals(0L, Sizes.align(0L));
        assertEquals(8L, Sizes.align(1L));
        assertEquals(8L, Sizes.align(8L));
        assertEquals(16L, Sizes.align(9L));
    }

    @Test
    public void anEmptyArrayStillCostsItsHeader() {
        assertEquals(Sizes.ARRAY_HEADER, Sizes.bytes(new int[0]));
        assertEquals(Sizes.ARRAY_HEADER, Sizes.bytes(new byte[0]));
        assertEquals(Sizes.ARRAY_HEADER, Sizes.bytes(new long[0]));
    }

    @Test
    public void eachElementWidthIsChargedAtItsOwnSize() {
        assertEquals(16L + 4 * 100, Sizes.bytes(new int[100]));
        assertEquals(16L + 8 * 100, Sizes.bytes(new long[100]));
        // 16 + 100 = 116, padded to 120: the alignment applies to the whole object, not just
        // to the header.
        assertEquals(120L, Sizes.bytes(new byte[100]));
        // Padded up, because a 3-byte array still occupies a whole 8-byte slot beyond the
        // header. Under-charging here would understate the key-kind column by megabytes
        // across a table with hundreds of thousands of entries.
        assertEquals(24L, Sizes.bytes(new byte[3]));
    }

    @Test
    public void aNullArrayCostsNothingSoAnAbsentIndexIsFreeInTheReport() {
        // The wildcard-sibling array is null on a graph with no wildcard keys, and the key
        // lookup index is null on a table built without one. Both must read as zero rather
        // than as a header nobody allocated.
        assertEquals(0L, Sizes.bytes((int[]) null));
        assertEquals(0L, Sizes.bytes((byte[]) null));
        assertEquals(0L, Sizes.bytes((long[]) null));
    }

    @Test
    public void humanReadableSizesSwitchUnitAtTheRightThresholds() {
        assertEquals("512 B", Sizes.human(512L));
        assertEquals("1.0 KB", Sizes.human(1024L));
        assertEquals("1.0 MB", Sizes.human(1024L * 1024L));
        assertEquals("43.2 MB", Sizes.human(45261216L));
    }

    @Test
    public void everyReportRowUsesOneFormatSoTheColumnsLineUp() {
        String accounted = Sizes.row("accounted for", 45222992L);
        String measured = Sizes.row("measured retained", 45264632L);
        assertEquals(accounted.length(), measured.length());
        assertTrue(accounted.endsWith(String.format("%n")));
        assertTrue(accounted.contains("43.1 MB"));
        assertTrue(accounted.contains("45222992"));
    }
}
