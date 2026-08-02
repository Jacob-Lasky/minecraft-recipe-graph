package io.github.jacoblasky.recipedump.common;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.jacoblasky.recipedump.RecipeDumpMod;
import org.junit.Test;

/**
 * NOTHING A DEDICATED SERVER LOADS MAY MENTION A CLIENT CLASS.
 *
 * This is the one claim in #19 Phase 2 that nobody working on this repository can test by
 * running it: the mod stopped being `clientSideOnly` so the planner's item, capability and
 * packets exist on Jake's server, and the way that goes wrong is a `NoClassDefFoundError`
 * during server startup, on his machine, after a deploy. There is no server to launch here
 * and the desktop cannot launch one either.
 *
 * So it is asserted against the COMPILED BYTES instead. A class's constant pool holds the
 * internal name of every type it references, so scanning for `net/minecraft/client/` catches
 * an import, a field type, a method signature and a cast alike -- which is more than reading
 * the import block would.
 *
 * WHAT IT CANNOT SEE: a client class reached by reflection or by a string. That is deliberate
 * and is what the sided proxy is for -- `ClientProxy` is named as a string in `@SidedProxy`
 * and FML loads it only on a client.
 */
public class CommonSideSafetyTest {

    /**
     * Package roots a dedicated server has no jar for.
     *
     * `net/minecraftforge/client/` and `net/minecraftforge/fml/client/` are on the list for
     * the same reason as Minecraft's own client package: they ship in the client jar only.
     * `mezz/jei/` is there because JEI is a client mod, which is why this one was
     * `clientSideOnly` in the first place.
     */
    private static final List<String> FORBIDDEN = Arrays.asList(
            "net/minecraft/client/",
            "net/minecraftforge/client/",
            "net/minecraftforge/fml/client/",
            "mezz/jei/");

    /** Everything under here runs on both sides. */
    private static final String COMMON_PACKAGE = "io/github/jacoblasky/recipedump/common";

    /**
     * The mod class itself, which is the one class a server is GUARANTEED to load.
     *
     * It sits outside `common/` for the ordinary reason that `@Mod` classes sit at the root
     * of their package, so it has to be named separately or it would not be checked at all.
     */
    private static final String MOD_CLASS = "io/github/jacoblasky/recipedump/RecipeDumpMod";

    @Test
    public void noCommonClassReferencesAnythingOnlyAClientHas() throws IOException {
        List<String> checked = new ArrayList<String>();
        List<String> offences = new ArrayList<String>();
        for (File file : commonClassFiles()) {
            byte[] bytes = read(file);
            String name = file.getPath();
            checked.add(name);
            for (String forbidden : FORBIDDEN) {
                if (contains(bytes, forbidden)) {
                    offences.add(name + " references " + forbidden);
                }
            }
        }

        // A DIRECTORY SCAN THAT FOUND NOTHING PASSES VACUOUSLY, which is the failure mode this
        // whole file exists to prevent one level up. Assert the population before the
        // property, and name classes that must be in it.
        assertTrue("scanned " + checked.size() + " classes; the common side is not that small,"
                   + " so the scan is looking in the wrong place", checked.size() >= 8);
        assertContains(checked, "RecipeDumpMod.class");
        assertContains(checked, "CommonProxy.class");
        assertContains(checked, "CalculatorItem.class");
        assertContains(checked, "PlanBookCapability.class");
        assertContains(checked, "PlanBookSyncMessage.class");
        assertContains(checked, "PlanBookEditMessage.class");

        if (!offences.isEmpty()) {
            fail("a dedicated server would fail to load these:\n  "
                 + String.join("\n  ", offences));
        }
    }

    /**
     * The scan proves its own teeth on a class that genuinely does reference a client type.
     *
     * Without this, a bug in `contains` -- a wrong separator, an encoding slip -- makes the
     * test above pass on everything forever, which is indistinguishable from success.
     */
    @Test
    public void theScanActuallyDetectsAClientReferenceWhenThereIsOne() throws IOException {
        File clientProxy = new File(classesRoot(),
                                    "io/github/jacoblasky/recipedump/client/ClientProxy.class");
        assertTrue("expected the client proxy at " + clientProxy, clientProxy.isFile());
        assertTrue("ClientProxy names Minecraft, so the scan must see it there",
                   contains(read(clientProxy), "net/minecraft/client/"));
    }

    private static void assertContains(List<String> paths, String suffix) {
        for (String path : paths) {
            if (path.endsWith(suffix)) {
                return;
            }
        }
        fail("the scan never saw " + suffix + "; it found " + paths);
    }

    private static List<File> commonClassFiles() {
        File root = classesRoot();
        List<File> files = new ArrayList<File>();
        collect(new File(root, COMMON_PACKAGE), files);
        File modClass = new File(root, MOD_CLASS + ".class");
        assertTrue("expected the mod class at " + modClass, modClass.isFile());
        files.add(modClass);
        return files;
    }

    /** Where the build put `main`'s classes, found from a class rather than from a path. */
    private static File classesRoot() {
        URL url = RecipeDumpMod.class.getResource("/" + MOD_CLASS + ".class");
        assertTrue("the mod class must be on the test classpath as a file, not a jar; got "
                   + url, url != null && "file".equals(url.getProtocol()));
        File file = new File(url.getPath());
        for (int i = 0; i < MOD_CLASS.split("/").length; i++) {
            file = file.getParentFile();
        }
        return file;
    }

    private static void collect(File dir, List<File> into) {
        File[] entries = dir.listFiles();
        if (entries == null) {
            return;
        }
        for (File entry : entries) {
            if (entry.isDirectory()) {
                collect(entry, into);
            } else if (entry.getName().endsWith(".class")) {
                into.add(entry);
            }
        }
    }

    private static byte[] read(File file) throws IOException {
        InputStream in = file.toURI().toURL().openStream();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    /** Substring search over raw bytes; a constant pool entry is plain ASCII here. */
    private static boolean contains(byte[] haystack, String needle) {
        byte[] pattern = needle.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + pattern.length <= haystack.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (haystack[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
