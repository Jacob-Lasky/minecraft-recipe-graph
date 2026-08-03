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
        // NAMED, not counted, so a skip that stops happening is visible too: an allowlist entry
        // that never fires is either a fixed gap nobody removed from the list or a test that
        // stopped running altogether, and both are worth a line.
        for (String allowed : allowedSkips) {
            if (!counting.skipped.containsKey(allowed)) {
                System.out.println("-- allowlisted skip did NOT skip (it ran, or it is gone): "
                        + allowed);
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

        @Override
        public void testStarted(Description description) {
            classesThatRan.add(description.getClassName());
        }

        @Override
        public void testAssumptionFailure(Failure failure) {
            skipped.put(name(failure.getDescription()), firstLine(failure.getMessage()));
        }

        @Override
        public void testIgnored(Description description) {
            // @Ignore counts as a skip here on purpose. It is a different mechanism from
            // Assume and the same lie in the output.
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
