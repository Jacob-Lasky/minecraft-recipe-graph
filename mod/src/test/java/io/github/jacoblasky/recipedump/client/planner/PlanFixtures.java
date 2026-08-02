package io.github.jacoblasky.recipedump.client.planner;

import java.util.List;

/**
 * The plan fixtures, for tests.
 *
 * A thin alias for {@link PlanFixtureFiles} so the tests read as tests. The finding and the
 * reading live there because the screenshot harness needs them too, and one directory walk is
 * one place to get the "mount the repository root" trap right.
 */
public final class PlanFixtures {

    private PlanFixtures() {
    }

    public static List<String> names() {
        return PlanFixtureFiles.names();
    }

    public static PlanView load(String name) {
        return PlanFixtureFiles.load(name);
    }
}
