package io.github.jacoblasky.recipedump.common.ae2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import io.github.jacoblasky.recipedump.ClassFiles;

/**
 * The facade's behaviour with no AE2 and no server, which is what a unit test can reach.
 *
 * WHAT THIS CANNOT COVER, said plainly rather than implied: every interesting path in
 * {@link Ae2StockReader} needs a running server with a live ME grid -- a linked wireless
 * terminal, a resolvable live grid, a security terminal denying access. None of those exist
 * in a JUnit run and none of them are faked here, because a fake grid would assert that my
 * mock matches my code rather than that either matches AE2. They are named in the PR as
 * needing a hand on a keyboard.
 *
 * What IS worth pinning is that the facade never throws and never lies when the world it
 * expects is absent, because that is the state a pack without AE2 is in permanently.
 *
 * <h2>AND THE ABSENCE OF A GATE IS ASSERTABLE EVEN WHERE ITS PRESENCE WAS NOT. #331</h2>
 *
 * The paragraph above is why #331's range gate shipped broken for a whole release: no JUnit test
 * could reach it, so nothing noticed that it refused every read. A gate's PRESENCE needs a live
 * grid to exercise; its ABSENCE is a fact about the compiled class, and a constant-pool scan reads
 * that with no AE2, no server and no player. That is what the two scans at the bottom of this file
 * do, and they are the regression test for the reported world -- a linked, loaded, powered,
 * permitted grid with no access point anywhere must read OK, and it does because nothing is left in
 * the class that could refuse it for range.
 */
public class Ae2StockTest {

    @Test
    public void withNoPlayerTheAnswerIsARefusalRatherThanAnException() {
        // Called from a packet handler, so a throw here would land in netty's dispatch.
        StockSnapshot snapshot = Ae2Stock.read(null);
        assertNotNull(snapshot);
        assertFalse(snapshot.isAvailable());
    }

    @Test
    public void withNoAe2OnTheClasspathTheAnswerIsNoAe2AndNothingResolves() {
        // THIS RUN IS THE NO-AE2 CASE, and that is what makes it worth asserting rather than
        // a tautology. AE2 is a `compileOnly` dependency, so it is on the compile path and
        // NOT on the test runtime path -- which is exactly the situation a player on a pack
        // without AE2 is in, permanently.
        //
        // So this proves the isolation argument end to end: `Ae2Stock` answers, `Ae2Stock`
        // loads, and `Ae2StockReader` is never touched. If an AE2 import ever creeps into the
        // facade, this fails with NoClassDefFoundError instead of returning false.
        assertFalse(Ae2Stock.isAvailable());
        // Deliberately NOT asserting that `read` returns NO_AE2. Reaching that branch needs a
        // non-null player on a server-side world, and an `EntityPlayer` cannot be constructed
        // in a JUnit run -- so the honest coverage stops at the presence check, and the
        // branch below it is named in the PR as needing a keyboard rather than mocked into
        // looking covered.
        assertEquals(Boolean.FALSE, Boolean.valueOf(Ae2Stock.isAvailable()));
    }

    /**
     * REGRESSION FOR #331. The range gate is gone from the compiled class, not just from the source.
     *
     * <h2>WHY A CONSTANT-POOL SCAN AND NOT A BEHAVIOURAL TEST</h2>
     *
     * The behavioural test asked for is "a player with a linked, loaded, powered, permitted grid and
     * no access point anywhere reads OK". That is the reported world, and it cannot be built here:
     * an `EntityPlayer` cannot be constructed in a JUnit run, AE2 is `compileOnly` so `AEApi` is not
     * even on the test runtime path, and a faked grid would assert that my mock matches my code
     * rather than that either matches AE2 -- this class's own header says so and #331 is what
     * happens when that gap goes unguarded.
     *
     * So the claim is made one level down, where it is checkable and where it is actually strong: if
     * NO production class holds a reference to `IWirelessAccessPoint` at all, there is no code path
     * anywhere that can refuse a read for range, whatever the world looks like. That is a stronger
     * statement than any single scenario, and it needs nothing running.
     *
     * IT SCANS EVERY PRODUCTION CLASS AND NOT ONLY THE READER'S, which is a correction: narrowing
     * it to `Ae2StockReader*` would pass a gate re-added in a new class beside it.
     *
     * <h2>THE CONTROL IS THE HALF THAT MAKES IT A TEST</h2>
     *
     * A scan returning "not found" is a claim about the scan until the scan has been made to find
     * something. So this also asserts the reader still names the gates that REMAIN -- and those are
     * `Reason` constants in the same constant pool the absent one would be in, so a scan that could
     * not see `IWirelessAccessPoint` could not see them either.
     */
    @Test
    public void noRangeGateSurvivesToRefuseALinkedLoadedGridWithNoAccessPoint() throws Exception {
        List<File> reader = readerClasses();
        assertFalse("no compiled Ae2StockReader was found, so this scanned nothing --"
                    + " ClassFiles.classesRoot() is " + ClassFiles.classesRoot(),
                    reader.isEmpty());

        // EVERY PRODUCTION CLASS, NOT JUST THE READER'S. Scanning only `Ae2StockReader*` left a
        // hole this test is the whole point of closing: a range gate re-added in a NEW class under
        // `common/ae2` would be invisible here, and the `nothingInProductionMintsTheRetiredRangeRefusal`
        // guard below would miss it too if the new gate refused with `NETWORK_GONE` instead of
        // reaching for the retired constant. The interface name is the thing that cannot be
        // renamed around, so scan for it everywhere the mod actually runs.
        //
        // `shot/` IS EXCLUDED FOR THE REASON THE OTHER SCANS EXCLUDE IT: `Ae2ProbeShot` and
        // `Ae2PlacedProbeShot` are harness code and BOTH legitimately place an access point, which
        // `harness/README.md` explains is now scaffolding rather than a gate. Their hits are
        // correct and must not be scanned for.
        int scanned = 0;
        for (File file : ClassFiles.under(ClassFiles.ROOT_PACKAGE)) {
            String name = ClassFiles.internalName(file);
            if (name.contains("/shot/")) {
                continue;
            }
            scanned++;
            assertFalse(name + " references IWirelessAccessPoint, so something can refuse a read"
                        + " for range again -- #331 measured that gate never passing on this pack",
                        ClassFiles.contains(ClassFiles.read(file), "IWirelessAccessPoint"));
        }
        assertTrue("nothing was scanned, so this asserted nothing", scanned > 0);

        for (File file : reader) {
            byte[] bytes = ClassFiles.read(file);
            assertFalse(ClassFiles.internalName(file) + " still names OUT_OF_RANGE, which is"
                        + " retired: nothing may mint it",
                        ClassFiles.contains(bytes, "OUT_OF_RANGE"));
        }

        // THE CONTROL. Each of these is a `Reason` the reader still refuses with, so finding them
        // proves the scan can see exactly the kind of name it just reported absent. Without this,
        // a scan pointed at the wrong directory passes every assertion above.
        String[] surviving = {"NO_TERMINAL", "NOT_LINKED", "NETWORK_GONE", "NO_POWER",
                              "NO_PERMISSION", "NO_STORAGE"};
        for (String reason : surviving) {
            boolean seen = false;
            for (File file : reader) {
                if (ClassFiles.contains(ClassFiles.read(file), reason)) {
                    seen = true;
                }
            }
            assertTrue("the reader no longer names " + reason + " -- either a gate was deleted"
                       + " that should not have been, or this scan is looking at nothing",
                       seen);
        }
    }

    /**
     * GUARD. No production class mints the retired range refusal. #331
     *
     * THE `@Deprecated` ANNOTATION IS ADVICE AND THIS IS THE GATE. A compiler warning is read by
     * whoever happens to look at the build log; a red test is read by whoever runs the suite. The
     * value has to stay decodable off the wire -- {@link StockSnapshot#deserializeNBT} maps an
     * unknown reason to `NETWORK_GONE`, which would turn an unnameable refusal into a claim about
     * the player's base -- so "keep it but never produce it" is a rule that needs enforcing rather
     * than documenting.
     *
     * EVERY PRODUCTION CLASS EXCEPT THE ENUM'S OWN, and `shot/` is excluded for the reason the
     * repository's other constant-pool scans exclude it: it is harness code. Nested classes are
     * covered because this walks every class file under the root rather than a list of names --
     * `ClientProxy$1` is the standing example of a call that lives in a nested class, and a scan
     * narrowed to outer classes misses it.
     */
    @Test
    public void nothingInProductionMintsTheRetiredRangeRefusal() throws Exception {
        List<String> offenders = new ArrayList<String>();
        int scanned = 0;
        for (File file : ClassFiles.under(ClassFiles.ROOT_PACKAGE)) {
            String name = ClassFiles.internalName(file);
            if (name.contains("/shot/") || name.contains("StockSnapshot")) {
                continue;
            }
            scanned++;
            if (ClassFiles.contains(ClassFiles.read(file), "OUT_OF_RANGE")) {
                offenders.add(name);
            }
        }
        assertTrue("nothing was scanned, so this asserted nothing", scanned > 0);
        assertEquals("OUT_OF_RANGE is retired and nothing may mint it; see its javadoc for the"
                     + " measurement -- offenders: " + offenders,
                     0, offenders.size());
    }

    /** Every compiled class of the reader, its nested classes included. */
    private static List<File> readerClasses() {
        List<File> found = new ArrayList<File>();
        for (File file : ClassFiles.under(ClassFiles.ROOT_PACKAGE + "/common/ae2")) {
            String name = ClassFiles.internalName(file);
            if (name.substring(name.lastIndexOf('/') + 1).startsWith("Ae2StockReader")) {
                found.add(file);
            }
        }
        return found;
    }
}
