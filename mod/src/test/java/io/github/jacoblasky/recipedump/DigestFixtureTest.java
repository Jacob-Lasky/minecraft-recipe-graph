package io.github.jacoblasky.recipedump;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Map;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByte;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.nbt.NBTTagShort;
import net.minecraft.nbt.NBTTagString;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The Java half of a contract shared with the python world-save reader.
 *
 * WHY A SHARED FILE RATHER THAN TWO TEST SUITES. `recipegraph/nbt_digest.py` recomputes
 * this class's discriminator so that a stack in an AE2 network can be matched against a
 * stack in a recipe (#21). Two independent implementations of one hash is exactly the
 * pair that drifts silently, and the symptom of drift is "the tool says I do not own my
 * bees" -- indistinguishable from the port not existing. So both sides assert the SAME
 * file: `tests/fixtures/nbt_digest.json`, at the repository root, outside this module.
 *
 * DO NOT edit an expected value in that file to make this test pass. A disagreement
 * means one side changed the format, which is part of dump schema 3, and the fix belongs
 * on whichever side moved. Six of the cases are digests read back out of a real dump, so
 * changing them is changing what the shipped data means.
 *
 * `DiscriminatorTest` remains the place for behaviour that needs no python counterpart.
 */
public class DigestFixtureTest {

    private static JsonArray cases;

    @BeforeClass
    public static void bootstrap() throws IOException {
        Bootstrap.register();
        cases = load().getAsJsonArray("cases");
        assertTrue("fixture is empty", cases.size() > 0);
    }

    /**
     * Gradle runs tests with the module directory as the working directory, but a run
     * from the repository root is a reasonable thing to do too, so try both rather than
     * failing with a path nobody can read.
     */
    private static JsonObject load() throws IOException {
        String relative = "tests/fixtures/nbt_digest.json";
        File[] candidates = {new File("../" + relative), new File(relative)};
        for (File candidate : candidates) {
            if (candidate.isFile()) {
                Reader reader = new FileReader(candidate);
                try {
                    return new JsonParser().parse(reader).getAsJsonObject();
                } finally {
                    reader.close();
                }
            }
        }
        throw new IOException("cannot find " + relative + " from "
                + new File(".").getAbsolutePath());
    }

    /** One `[type, value]` pair from the fixture, as real NBT. */
    private static NBTBase node(JsonElement spec) {
        JsonArray pair = spec.getAsJsonArray();
        String kind = pair.get(0).getAsString();
        JsonElement value = pair.get(1);
        if (kind.equals("byte")) {
            return new NBTTagByte(value.getAsByte());
        } else if (kind.equals("short")) {
            return new NBTTagShort(value.getAsShort());
        } else if (kind.equals("int")) {
            return new NBTTagInt(value.getAsInt());
        } else if (kind.equals("long")) {
            return new NBTTagLong(value.getAsLong());
        } else if (kind.equals("float")) {
            return new NBTTagFloat(value.getAsFloat());
        } else if (kind.equals("double")) {
            return new NBTTagDouble(value.getAsDouble());
        } else if (kind.equals("string")) {
            return new NBTTagString(value.getAsString());
        } else if (kind.equals("bytearray")) {
            JsonArray items = value.getAsJsonArray();
            byte[] out = new byte[items.size()];
            for (int i = 0; i < items.size(); i++) {
                out[i] = items.get(i).getAsByte();
            }
            return new NBTTagByteArray(out);
        } else if (kind.equals("intarray")) {
            JsonArray items = value.getAsJsonArray();
            int[] out = new int[items.size()];
            for (int i = 0; i < items.size(); i++) {
                out[i] = items.get(i).getAsInt();
            }
            return new NBTTagIntArray(out);
        } else if (kind.equals("list")) {
            NBTTagList out = new NBTTagList();
            for (JsonElement item : value.getAsJsonArray()) {
                out.appendTag(node(item));
            }
            return out;
        } else if (kind.equals("compound")) {
            return compound(value.getAsJsonObject());
        }
        // Deliberately no TAG_Long_Array: the one fixture case using it is flagged
        // `java_diverges` and never reaches here. If a new case does, the fixture has
        // outgrown this builder, and saying so beats digesting something else.
        throw new IllegalArgumentException("unknown fixture node type: " + kind);
    }

    private static NBTTagCompound compound(JsonObject spec) {
        NBTTagCompound out = new NBTTagCompound();
        for (Map.Entry<String, JsonElement> entry : spec.entrySet()) {
            out.setTag(entry.getKey(), node(entry.getValue()));
        }
        return out;
    }

    private static ItemStack stackOf(NBTTagCompound tag) {
        ItemStack stack = new ItemStack(Items.STICK);
        if (!tag.isEmpty()) {
            stack.setTagCompound(tag);
        }
        return stack;
    }

    private static String asStringOrNull(JsonElement element) {
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    /**
     * Also the builder's coverage check: a fixture case added on the python side whose
     * node types `node` cannot construct throws here, naming the type, rather than being
     * a compile-clean and quietly unasserted hole.
     */
    @Test
    public void everyCaseDigestsToTheRecordedValue() {
        for (JsonElement element : cases) {
            JsonObject c = element.getAsJsonObject();
            if (c.has("java_diverges")) {
                // A case this side answers and python declines, by way of a tag
                // `canonical` serialises through Java's toString(). Not built at all,
                // because building it is the part that has no counterpart: the python
                // suite asserts the refusal, and the fixture records why.
                continue;
            }
            String actual = DumpCommand.discriminator(stackOf(compoundFor(c)));
            assertEquals(c.get("name").getAsString() + " ("
                         + c.get("note").getAsString() + ")",
                         asStringOrNull(c.get("digest")), actual);
        }
    }

    @Test
    public void everyCaseRendersTheRecordedCanonicalString() {
        // Asserted separately from the digest because a hash mismatch says only "these
        // differ"; the canonical string says where.
        //
        // COUNTED, because the `continue` is how this test can pass without running.
        // `c.get("canonical")` is null both when the field is absent and when it is null, so
        // a regenerated fixture that stopped writing it turns every case into a skip and
        // leaves a green no-op where the cross-language string contract used to be. 42 of
        // the 46 cases carry one. `NodeStatusTest` counts its rows out of present.py for the
        // same reason and is the pattern here; `tests/test_nbt_digest.py` counts the same 42.
        int checked = 0;
        for (JsonElement element : cases) {
            JsonObject c = element.getAsJsonObject();
            String expected = asStringOrNull(c.get("canonical"));
            if (expected == null) {
                continue;
            }
            NBTTagCompound tag = compoundFor(c);
            for (String cosmetic : DumpCommand.COSMETIC_TAGS) {
                tag.removeTag(cosmetic);
            }
            StringBuilder sb = new StringBuilder();
            DumpCommand.canonical(tag, sb);
            assertEquals(c.get("name").getAsString(), expected, sb.toString());
            checked++;
        }
        assertEquals("the fixture must carry 42 canonical strings; a case that stopped "
                     + "recording one is skipped silently and asserts nothing",
                     42, checked);
    }

    private static NBTTagCompound compoundFor(JsonObject c) {
        return compound(c.get("nbt").getAsJsonObject());
    }
}
