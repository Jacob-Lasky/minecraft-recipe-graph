import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.runner.Description;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

/**
 * Run a list of JUnit 4 classes and REFUSE TO EXIT 0 OVER A SKIP.
 *
 * WHY THIS EXISTS RATHER THAN `JUnitCore.main`. JUnit 4's own text runner prints
 * `OK (263 tests)` and says NOTHING about an assumption failure: `Assume.assumeTrue` is
 * reported to listeners through `testAssumptionFailure` and is counted as neither a failure
 * nor an ignore, so `Result.wasSuccessful()` is true and the count is unchanged. The one
 * assertion in this repository that most needs watching -- `PlanFixtureTest`'s golden gate --
 * is an `Assume` on `$RECIPEGRAPH_ORACLE`, and CI does not have that graph. So a CI job built
 * on the stock runner would print green over its most important skip, which is worse than no
 * job because it would look like coverage. That is the failure this whole repository's
 * `tools/check.sh` exists to prevent, one language over.
 *
 * So: every skip is named, and any skip NOT on the explicit `--allow-skip` allowlist fails the
 * run. The allowlist is the honest statement of what CI cannot cover, and it lives on the
 * command line in `tools/ci-java.sh` where a human reads it rather than being inferred from
 * output nobody checks.
 *
 * A CLASS THAT RAN NOTHING ALSO FAILS THE RUN, because "the suite stopped collecting" looks
 * identical to "the suite is fast" -- #86's `unittest.main()` mid-file ran 31 of 77 tests and
 * complained about none of it.
 *
 * AND AN ALLOWLISTED NAME THAT DOES NOT EXIST FAILS THE RUN, which is the same argument one
 * level up and was a live hole until #192. An allowlist entry is a CLAIM that a specific
 * assertion exists and that CI cannot run it. Deleting the assertion makes the claim false, and
 * before this guard it did so silently:
 *
 *   renaming the gate     CAUGHT, though not by this. The renamed method still carries its
 *                         `Assume`, so it still skips, under a name that is not allowlisted,
 *                         and `SKIPPED, NOT ALLOWED` fires.
 *   DELETING the gate     MISSED. No method means no `Assume`, so nothing skips and nothing is
 *                         missing from the allowlist. `PlanFixtureTest` has fourteen other
 *                         tests so the collected-nothing guard cannot fire, and
 *                         `ci-java.sh` counts *.java files against *.class files rather than
 *                         methods, so its count still balances. Measured: the run printed
 *                         `275 run, 0 failed, 0 skipped` and `== java core green ==` with the
 *                         repository's single most important assertion gone from the tree.
 *
 * So the two outcomes an allowlisted name can have are now told apart. It RAN (someone set
 * $RECIPEGRAPH_ORACLE and the gate is being enforced, the best case and not an error), or it
 * SKIPPED as expected, or IT IS NOT THERE, which is a failure. The third case is the only one
 * that used to look like the first.
 *
 * Deliberately dependency-free beyond JUnit 4 itself, and deliberately not in
 * `mod/src/test/java`: it is CI plumbing rather than a test, and a class in there would be
 * compiled into the Gradle test set and run by the real build.
 */
public final class JavaCoreSuite {

    public static void main(String[] args) {
        Set<String> allowedSkips = new LinkedHashSet<String>();
        List<String> classNames = new ArrayList<String>();
        for (int i = 0; i < args.length; i++) {
            if ("--allow-skip".equals(args[i])) {
                // SAID, not thrown. A trailing `--allow-skip` used to walk off the end of the
                // array and die with a bare ArrayIndexOutOfBoundsException, which in a CI log
                // reads as a broken runner rather than as a malformed invocation.
                if (i + 1 >= args.length) {
                    System.err.println("!! --allow-skip needs a Class.method after it");
                    System.exit(2);
                }
                allowedSkips.add(args[++i]);
            } else {
                classNames.add(args[i]);
            }
        }
        if (classNames.isEmpty()) {
            System.err.println("usage: JavaCoreSuite [--allow-skip Class.method ...] "
                    + "<test class> ...");
            System.exit(2);
        }

        Class<?>[] classes = new Class<?>[classNames.size()];
        for (int i = 0; i < classes.length; i++) {
            try {
                classes[i] = Class.forName(classNames.get(i));
            } catch (ClassNotFoundException missing) {
                System.err.println("!! no such test class: " + classNames.get(i));
                System.exit(2);
            }
        }

        Counting counting = new Counting();
        JUnitCore core = new JUnitCore();
        core.addListener(counting);
        Result result = core.run(classes);

        System.out.println("classes: " + classes.length + ", tests: " + result.getRunCount()
                + " run, " + result.getFailureCount() + " failed, "
                + counting.skipped.size() + " skipped");

        boolean bad = false;
        for (Failure failure : result.getFailures()) {
            System.out.println("-- FAILED " + failure.getTestHeader());
            System.out.println(failure.getTrace());
            bad = true;
        }
        for (Map.Entry<String, String> skip : counting.skipped.entrySet()) {
            boolean allowed = allowedSkips.contains(skip.getKey());
            System.out.println((allowed ? "-- skipped (allowed) " : "!! SKIPPED, NOT ALLOWED ")
                    + skip.getKey() + "  (" + skip.getValue() + ")");
            bad = bad || !allowed;
        }
        // AN ALLOWLIST ENTRY IS A CLAIM THAT THE ASSERTION EXISTS, so a name that never turned
        // up in ANY form is a failure and not a note. `testStarted` fires for a test that runs
        // AND for one that then trips an `Assume`, and `testIgnored` covers `@Ignore`, so
        // `counting.seen` holds every method JUnit knew about. A name in neither that set nor
        // the skip map has been renamed or deleted, and the entry naming it is now a lie.
        for (String allowed : allowedSkips) {
            if (counting.skipped.containsKey(allowed)) {
                continue;
            }
            if (counting.seen.contains(allowed)) {
                // The good case, and deliberately not an error: someone exported
                // $RECIPEGRAPH_ORACLE and the gate is actually being enforced.
                System.out.println("-- allowlisted skip RAN rather than skipping, so it is "
                        + "being enforced here: " + allowed);
            } else {
                System.out.println("!! ALLOWLISTED TEST DOES NOT EXIST: " + allowed);
                System.out.println("   Nothing skipped under that name and nothing ran under "
                        + "it, so it has been renamed or deleted. Either restore it, or "
                        + "remove the --allow-skip entry in tools/ci-java.sh that claims CI "
                        + "cannot run it. An allowlist naming a test that is gone is the "
                        + "exact lie this runner exists to refuse.");
                bad = true;
            }
        }
        for (Class<?> type : classes) {
            if (!counting.classesThatRan.contains(type.getName())) {
                System.out.println("!! " + type.getName() + " ran no tests at all");
                bad = true;
            }
        }
        if (result.getRunCount() == 0) {
            System.out.println("!! the run collected nothing");
            bad = true;
        }
        System.out.println(bad ? "== java core FAILED ==" : "== java core green ==");
        System.exit(bad ? 1 : 0);
    }

    /** Counts what the default listener throws away. */
    private static final class Counting extends RunListener {
        /** `Class.method` -> why, sorted so two runs of one tree print identical lines. */
        final Map<String, String> skipped = new TreeMap<String, String>();
        final Set<String> classesThatRan = new LinkedHashSet<String>();
        /**
         * Every `Class.method` JUnit knew about, whether it passed, failed, skipped or was
         * ignored. This is what lets an allowlisted name that RAN be told apart from one that
         * is not in the tree at all; without it those two look identical from the outside.
         */
        final Set<String> seen = new LinkedHashSet<String>();

        @Override
        public void testStarted(Description description) {
            classesThatRan.add(description.getClassName());
            seen.add(name(description));
        }

        @Override
        public void testAssumptionFailure(Failure failure) {
            skipped.put(name(failure.getDescription()), firstLine(failure.getMessage()));
        }

        @Override
        public void testIgnored(Description description) {
            // @Ignore counts as a skip here on purpose. It is a different mechanism from
            // Assume and the same lie in the output. It does NOT fire `testStarted`, so the
            // name has to be recorded here too or an allowlisted @Ignore would read as
            // missing from the tree.
            seen.add(name(description));
            skipped.put(name(description), "@Ignore");
        }

        private static String name(Description description) {
            return description.getClassName() + "." + description.getMethodName();
        }

        private static String firstLine(String message) {
            if (message == null) {
                return "no reason given";
            }
            String[] lines = message.split("\n");
            return lines[0].length() > 120 ? lines[0].substring(0, 117) + "..." : lines[0];
        }
    }

    private JavaCoreSuite() {
    }
}
