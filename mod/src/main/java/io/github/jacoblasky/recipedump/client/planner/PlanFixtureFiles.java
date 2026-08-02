package io.github.jacoblasky.recipedump.client.planner;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Finds and reads the frozen plan fixtures out of the working tree.
 *
 * ONE COPY, shared by the layout tests and by the screenshot harness. It briefly had two --
 * the test one and the harness one -- with the same directory walk written twice, which is
 * two places for the same "mount the repository root, not mod/" trap to be got wrong.
 *
 * DEV-ONLY BY NATURE, and it lives in the mod's own source set anyway because the screenshot
 * harness is main-source code. It reads `tests/fixtures/`, which does not exist beside a
 * shipped jar; nothing in the shipped code path calls it, and {@link #available} is how a
 * caller asks rather than finding out through an exception.
 */
public final class PlanFixtureFiles {

    private static final String DIR = "tests/fixtures/plan";

    /**
     * Where to look, in order.
     *
     * `../../` first because RFG runs the dev client in `mod/run`; `../` covers gradle running
     * tests with `mod/` as the working directory, and `./` a run from the repository root.
     * Mounting only `mod/` in the build container leaves none of them valid, which is the
     * trap `DigestFixtureTest` has documented since the digest contract was written.
     */
    private static final List<String> PREFIXES = Arrays.asList("../../", "../", "./");

    private PlanFixtureFiles() {
    }

    /** True when the fixtures are reachable from here. */
    public static boolean available() {
        return findDir() != null;
    }

    /** Every `plan-*.json` fixture, by bare name, sorted. */
    public static List<String> names() {
        File[] files = dir().listFiles();
        if (files == null) {
            throw new IllegalStateException("no fixtures in " + dir());
        }
        List<String> names = new ArrayList<String>();
        for (File file : files) {
            String name = file.getName();
            if (name.startsWith("plan-") && name.endsWith(".json")) {
                names.add(name.substring(0, name.length() - ".json".length()));
            }
        }
        Collections.sort(names);
        return names;
    }

    public static PlanView load(String name) {
        File file = new File(dir(), name + ".json");
        if (!file.isFile()) {
            throw new IllegalArgumentException("no fixture " + file.getAbsolutePath()
                                               + "; have " + names());
        }
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            return PlanJson.readFixture(in);
        } catch (IOException failed) {
            throw new IllegalStateException("could not read " + file, failed);
        } finally {
            close(in);
        }
    }

    private static File dir() {
        File found = findDir();
        if (found == null) {
            throw new IllegalStateException(
                    DIR + " not found from " + new File(".").getAbsolutePath()
                    + " -- mount the REPOSITORY ROOT, not mod/");
        }
        return found;
    }

    private static File findDir() {
        for (String prefix : PREFIXES) {
            File candidate = new File(prefix + DIR);
            if (candidate.isDirectory()) {
                return candidate;
            }
        }
        return null;
    }

    private static void close(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (IOException ignored) {
            // Already parsed; a failed close says nothing about the result.
        }
    }
}
