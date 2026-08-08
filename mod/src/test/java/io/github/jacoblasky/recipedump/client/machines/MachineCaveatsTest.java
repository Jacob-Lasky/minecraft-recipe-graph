package io.github.jacoblasky.recipedump.client.machines;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonObject;

import io.github.jacoblasky.recipedump.common.ScenarioSource;

/**
 * What the machines screen admits it could not see.
 *
 * WHY THIS IS NOT THE PLANNER'S CAVEAT. `PlanCaveats` names every scenario input a PLAN
 * consumes; a machine verdict is decided by four of them. Both directions of getting that
 * wrong are real defects and this pins both:
 *
 *   - NAMING TOO MANY is not the safe side it looks like. A reader who checks
 *     `emc_knowledge`, finds it has nothing to do with whether they own a Pulverizer, and
 *     concludes the warning is boilerplate will skip the two entries that matter.
 *   - NAMING TOO FEW hides the tool's biggest blind spot on the one screen it is about.
 *     `ScenarioSource.PLACED`'s own note is already a sentence about this screen: "every
 *     machine reads as buildable rather than owned".
 */
public class MachineCaveatsTest {

    @Before
    public void isolate() {
        ScenarioSource.resetReaders();
    }

    @After
    public void restore() {
        ScenarioSource.resetReaders();
    }

    @Test
    public void theInputsNamedAreExactlyTheOnesAVerdictIsBuiltFrom() {
        // `Machines.resolve` takes an `Evidence` carrying placed tile entities, stock and
        // hand-set overrides, plus the `no_machine` list. Nothing else reaches it. This is the
        // assertion that has to fail the day it takes a fifth input.
        // SPELT VIA `ScenarioSource.field()` AND NOT AS LITERALS. `MachineCaveats`' own comment
        // claims this test pins the spelling, and with literals here it did not -- it pinned a
        // third hand-maintained copy of the same four strings, so renaming a field would have
        // left this green while the panel silently stopped naming the input. Reading the enum
        // is what makes the claim true.
        List<String> missing = MachineCaveats.missing();
        assertTrue("stock decides `have` vs `buildable`",
                   missing.contains(ScenarioSource.HAVE.field()));
        assertTrue("placed blocks decide `have`",
                   missing.contains(ScenarioSource.PLACED.field()));
        assertFalse("EMC knowledge never reaches a machine verdict",
                    missing.contains(ScenarioSource.EMC_KNOWLEDGE.field()));
        assertFalse("dimension gates never reach a machine verdict",
                    missing.contains(ScenarioSource.VISITED_DIMENSIONS.field()));
        assertFalse("autocrafting patterns never reach a machine verdict",
                    missing.contains(ScenarioSource.CRAFTABLES.field()));
        assertFalse("pins never reach a machine verdict",
                    missing.contains(ScenarioSource.PINS.field()));
    }

    @Test
    public void theTwoSourcesWithNoUiAreLiveRatherThanMissing() {
        // "The player has set none" is the truth rather than a gap, so these must not be
        // reported as things the screen could not see. Reporting them would make the warning
        // permanent and therefore invisible.
        List<String> missing = MachineCaveats.missing();
        assertFalse(missing.toString(),
                    missing.contains(ScenarioSource.MACHINE_OVERRIDES.field()));
        assertFalse(missing.toString(), missing.contains(ScenarioSource.NO_MACHINE.field()));
    }

    @Test
    public void theLineNamesTheFieldsRatherThanCountingThem() {
        // A count says how much is missing; a player can only act on which. `have` and
        // `placed` are two different things to go and wire up.
        String line = MachineCaveats.summaryLine();
        assertTrue(line, line.startsWith("verdicts computed without: "));
        assertTrue(line, line.contains(ScenarioSource.HAVE.field()));
        assertTrue(line, line.contains(ScenarioSource.PLACED.field()));
    }

    @Test
    public void theLineIsEmptyWhenEveryInputAVerdictUsesWasRead() {
        // The panel reserves a caveat line from `summaryLine().isEmpty()`, so a line that was
        // never empty would permanently steal a row from the table.
        ScenarioSource.HAVE.readBy(new ScenarioSource.Reader() {
            @Override
            public ScenarioSource.Status status() {
                return ScenarioSource.Status.available(new JsonObject());
            }
        });
        ScenarioSource.PLACED.readBy(new ScenarioSource.Reader() {
            @Override
            public ScenarioSource.Status status() {
                return ScenarioSource.Status.available(new JsonObject());
            }
        });
        assertEquals("", MachineCaveats.summaryLine());
        assertTrue(MachineCaveats.missing().isEmpty());
    }

    @Test
    public void aRefusedReadIsStillReportedAsMissing() {
        // A reader that ran and refused is not a live input. `StockSnapshot` decides liveness
        // and contents together for exactly this reason, and a screen that treated "I tried
        // and could not" as "I read it" would price the whole table against an empty
        // inventory while claiming it had looked.
        ScenarioSource.HAVE.readBy(new ScenarioSource.Reader() {
            @Override
            public ScenarioSource.Status status() {
                return ScenarioSource.Status.unavailable("no wireless access point in range");
            }
        });
        assertTrue(MachineCaveats.missing().contains(ScenarioSource.HAVE.field()));
    }
}
