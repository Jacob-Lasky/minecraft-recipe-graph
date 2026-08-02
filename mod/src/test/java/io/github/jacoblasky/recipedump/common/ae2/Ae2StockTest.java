package io.github.jacoblasky.recipedump.common.ae2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

/**
 * The facade's behaviour with no AE2 and no server, which is what a unit test can reach.
 *
 * WHAT THIS CANNOT COVER, said plainly rather than implied: every interesting path in
 * {@link Ae2StockReader} needs a running server with a live ME grid -- a linked wireless
 * terminal, an access point in range, a security terminal denying access. None of those exist
 * in a JUnit run and none of them are faked here, because a fake grid would assert that my
 * mock matches my code rather than that either matches AE2. They are named in the PR as
 * needing a hand on a keyboard.
 *
 * What IS worth pinning is that the facade never throws and never lies when the world it
 * expects is absent, because that is the state a pack without AE2 is in permanently.
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
}
