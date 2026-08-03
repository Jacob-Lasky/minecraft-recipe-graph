package io.github.jacoblasky.recipedump;

import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reading `main`'s COMPILED CLASSES from a test, which two gates in this repository do.
 *
 * WHY BYTES RATHER THAN SOURCE OR REFLECTION. `CommonSideSafetyTest` needs the constant pool
 * because a client type can arrive as an import, a field type, a signature or a cast, and
 * only the pool sees all four; `SeamInstallationTest` needs it because "does anything CALL
 * this" is a question reflection cannot answer at all. Grepping the source would answer both
 * badly -- a match in a comment counts, and a call through a generated bridge does not.
 *
 * SHARED SO THERE IS ONE ANSWER TO "WHERE DID THE BUILD PUT THE CLASSES". That lookup walks
 * up from a known class's own URL rather than assuming `build/classes/java/main`, because the
 * assumption is silently wrong under a different Gradle layout and the symptom is a scan that
 * finds nothing and passes.
 */
public final class ClassFiles {

    /** Every class the mod ships lives under here. */
    public static final String ROOT_PACKAGE = "io/github/jacoblasky/recipedump";

    /**
     * The class the roots lookup navigates from.
     *
     * `RecipeDumpMod` because it sits at the top of the package tree and a dedicated server
     * is guaranteed to load it, so it cannot be removed without the build noticing.
     */
    private static final String ANCHOR = ROOT_PACKAGE + "/RecipeDumpMod";

    private ClassFiles() {
    }

    /** Where the build put `main`'s classes, found from a class rather than from a path. */
    public static File classesRoot() {
        URL url = RecipeDumpMod.class.getResource("/" + ANCHOR + ".class");
        assertTrue("the mod class must be on the test classpath as a file, not a jar; got "
                   + url, url != null && "file".equals(url.getProtocol()));
        File file = new File(url.getPath());
        for (int i = 0; i < ANCHOR.split("/").length; i++) {
            file = file.getParentFile();
        }
        return file;
    }

    /** Every `.class` file under an internal package name, recursively. */
    public static List<File> under(String internalPackage) {
        List<File> files = new ArrayList<File>();
        collect(new File(classesRoot(), internalPackage), files);
        return files;
    }

    /**
     * The internal name of a class file -- `io/github/.../Foo$Bar` -- from its path.
     *
     * DERIVED FROM THE ROOT rather than from the file name, so a nested class and a
     * same-named class in another package cannot collapse onto one entry.
     */
    public static String internalName(File classFile) {
        String root = classesRoot().getPath();
        String path = classFile.getPath();
        assertTrue(path + " is not under " + root, path.startsWith(root));
        String relative = path.substring(root.length()).replace(File.separatorChar, '/');
        while (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        assertTrue(relative + " is not a class file", relative.endsWith(".class"));
        return relative.substring(0, relative.length() - ".class".length());
    }

    public static byte[] read(File file) throws IOException {
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
    public static boolean contains(byte[] haystack, String needle) {
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

    /**
     * Every method this class CALLS, as `owner.nameDescriptor`.
     *
     * READ OUT OF THE CONSTANT POOL RATHER THAN BY DISASSEMBLING THE CODE. A `Methodref` is
     * emitted for every call site javac writes, including the ones inside an anonymous class
     * and the ones a lambda or a bridge desugars into, and the pool is a flat list that needs
     * no control-flow analysis to walk. What that costs is precision about WHERE in the class
     * the call is -- which nothing here needs -- and it means a reference that only appears in
     * dead code still counts. That is the safe direction for the only question asked of this:
     * a seam reported as installed when the call is unreachable is a false PASS this cannot
     * produce, because javac does not emit a `Methodref` for a call it did not compile.
     *
     * PARSED BY HAND RATHER THAN WITH ASM. ASM is on the workspace classpath by accident of
     * Forge rather than by declaration, so depending on it here would make a test gate turn on
     * a transitive dependency nobody chose. The pool format is fixed by the JVM spec and this
     * module compiles at Java 8, so the tag list below is complete for what javac can emit.
     */
    public static Set<String> methodReferences(byte[] classFile) {
        int count = u2(classFile, 8);
        String[] utf8 = new String[count];
        int[][] refs = new int[count][];
        int[] classNameIndex = new int[count];
        int[][] nameAndType = new int[count][];
        int at = 10;
        for (int i = 1; i < count; i++) {
            int tag = classFile[at] & 0xFF;
            at++;
            switch (tag) {
                case 1: { // Utf8
                    int length = u2(classFile, at);
                    utf8[i] = new String(classFile, at + 2, length, StandardCharsets.UTF_8);
                    at += 2 + length;
                    break;
                }
                case 7: // Class
                    classNameIndex[i] = u2(classFile, at);
                    at += 2;
                    break;
                case 8: // String
                case 16: // MethodType
                    at += 2;
                    break;
                case 10: // Methodref
                case 11: // InterfaceMethodref
                    refs[i] = new int[] {u2(classFile, at), u2(classFile, at + 2)};
                    at += 4;
                    break;
                case 9: // Fieldref
                case 12: // NameAndType
                case 17: // Dynamic
                case 18: // InvokeDynamic
                    if (tag == 12) {
                        nameAndType[i] = new int[] {u2(classFile, at), u2(classFile, at + 2)};
                    }
                    at += 4;
                    break;
                case 3: // Integer
                case 4: // Float
                    at += 4;
                    break;
                case 5: // Long
                case 6: // Double
                    at += 8;
                    // A long or a double TAKES TWO POOL SLOTS. The JVM spec calls this a
                    // historical mistake and it is still the format; skipping the second slot
                    // is what keeps every later index aligned.
                    i++;
                    break;
                case 15: // MethodHandle
                    at += 3;
                    break;
                case 19: // Module
                case 20: // Package
                    at += 2;
                    break;
                default:
                    throw new IllegalStateException("unknown constant pool tag " + tag
                            + " at entry " + i);
            }
        }
        Set<String> out = new LinkedHashSet<String>();
        for (int i = 1; i < count; i++) {
            if (refs[i] == null) {
                continue;
            }
            String owner = utf8[classNameIndex[refs[i][0]]];
            int[] signature = nameAndType[refs[i][1]];
            out.add(owner + "." + utf8[signature[0]] + utf8[signature[1]]);
        }
        return out;
    }

    private static int u2(byte[] bytes, int at) {
        return ((bytes[at] & 0xFF) << 8) | (bytes[at + 1] & 0xFF);
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
}
