package io.github.jacoblasky.recipedump.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.init.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The calculator item and the recipe that makes it.
 *
 * The recipe is a resource rather than code, so nothing else checks it: a typo in an item id
 * is discovered as an `Unknown item` line in a log nobody reads, and the item is simply
 * uncraftable. Since a craft cannot be attempted here, what CAN be checked is that the JSON
 * says what it is meant to say and that the ids in it are the ones the pack actually has.
 */
public class CalculatorItemTest {

    private static final String RECIPE = "/assets/mcrecipedump/recipes/calculator.json";

    private static JsonObject recipe;

    @BeforeClass
    public static void loadResources() throws Exception {
        // The item registry, needed before an Item can be constructed. Headless-safe.
        Bootstrap.register();
        InputStream in = CalculatorItemTest.class.getResourceAsStream(RECIPE);
        assertNotNull("the recipe must be on the classpath at " + RECIPE, in);
        try {
            recipe = new JsonParser()
                    .parse(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } finally {
            in.close();
        }
    }

    @Test
    public void theItemRegistersUnderThisModsNamespace() {
        CalculatorItem item = new CalculatorItem();
        assertNotNull(item.getRegistryName());
        assertEquals("mcrecipedump", item.getRegistryName().getNamespace());
        assertEquals("calculator", item.getRegistryName().getPath());
        // The lang file keys off this exactly, with `.name` appended. A mismatch shows in
        // game as the raw key printed where the item's name should be.
        assertEquals("item.mcrecipedump.calculator", item.getTranslationKey());
    }

    @Test
    public void theItemDoesNotStack() {
        // It carries no per-stack state today, but the planner is a thing you carry one of,
        // and a stack of 64 planners in a slot is a UI question nobody wants to answer.
        assertEquals(1, new CalculatorItem().getItemStackLimit());
    }

    @Test
    public void theLangFileNamesTheItemTheRecipeProduces() throws Exception {
        // Two files, one key, and nothing at build time joins them up.
        InputStream in = CalculatorItemTest.class
                .getResourceAsStream("/assets/mcrecipedump/lang/en_us.lang");
        assertNotNull("en_us.lang must be on the classpath", in);
        try {
            String lang = readAll(in);
            assertTrue("en_us.lang must define item.mcrecipedump.calculator.name, but was:\n"
                       + lang, lang.contains("item.mcrecipedump.calculator.name="));
        } finally {
            in.close();
        }
    }

    @Test
    public void theModelPointsAtATextureThatIsActuallyShipped() throws Exception {
        // A model naming a texture that is not in the jar renders as the black-and-magenta
        // missing texture, which reads as a broken model rather than a missing file.
        InputStream model = CalculatorItemTest.class
                .getResourceAsStream("/assets/mcrecipedump/models/item/calculator.json");
        assertNotNull("the item model must be on the classpath", model);
        try {
            JsonObject json = new JsonParser()
                    .parse(new InputStreamReader(model, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            assertEquals("mcrecipedump:items/calculator",
                         json.getAsJsonObject("textures").get("layer0").getAsString());
        } finally {
            model.close();
        }
        InputStream texture = CalculatorItemTest.class
                .getResourceAsStream("/assets/mcrecipedump/textures/items/calculator.png");
        assertNotNull("the texture the model names must be shipped too", texture);
        texture.close();
    }

    @Test
    public void theRecipeProducesThisModsCalculator() {
        assertEquals("minecraft:crafting_shaped", recipe.get("type").getAsString());
        assertEquals("mcrecipedump:calculator",
                     recipe.getAsJsonObject("result").get("item").getAsString());
    }

    /**
     * The four components, by exact id and metadata.
     *
     * These were read back from the running graph rather than guessed:
     * `appliedenergistics2:material:23` is the Calculation Processor, `:35` the 1k ME Storage
     * Component and `:41` the Wireless Receiver. A wrong metadata is a different AE2 material
     * -- that item has dozens -- and the recipe would quietly want the wrong one.
     */
    @Test
    public void theRecipeAsksForTheFourComponentsThatJustifyTheItem() {
        JsonObject key = recipe.getAsJsonObject("key");
        JsonObject calculator = key.getAsJsonObject("C");
        assertEquals("jecalculation:item_calculator", calculator.get("item").getAsString());
        // Meta 0 is the Crafting Calculator; meta 1 is JEC's Math Calculator, which is a
        // different item wearing the same registry name.
        assertEquals(0, calculator.get("data").getAsInt());
        assertMaterial(key.getAsJsonObject("P"), 23);
        assertMaterial(key.getAsJsonObject("S"), 35);
        assertMaterial(key.getAsJsonObject("R"), 41);
    }

    /**
     * EVERY ingredient states its metadata, including the ones whose meta is 0.
     *
     * Forge does not treat `data` as optional for an item with subtypes: `getItemStackBasic`
     * throws `Missing data for item` and the whole recipe is dropped with an error in the log
     * and no other symptom. It did exactly that on the first dev-client boot of this recipe,
     * because JEC's calculator has two subtypes and the omitted `"data": 0` looked harmless.
     */
    @Test
    public void everyIngredientStatesItsMetadata() {
        JsonObject key = recipe.getAsJsonObject("key");
        for (java.util.Map.Entry<String, JsonElement> slot : key.entrySet()) {
            assertTrue("slot " + slot.getKey() + " omits \"data\", which Forge rejects for any"
                       + " item with subtypes",
                       slot.getValue().getAsJsonObject().has("data"));
        }
    }

    private static void assertMaterial(JsonObject ingredient, int meta) {
        assertEquals("appliedenergistics2:material", ingredient.get("item").getAsString());
        assertEquals(meta, ingredient.get("data").getAsInt());
    }

    /**
     * The JEC calculator sits in the middle, which is the lore: this is an upgrade to it.
     *
     * NO TRAILING BLANK ROW, and that is not an oversight. `ShapedRecipes.shrink` strips empty
     * rows and columns before the recipe is registered, so a written-out 3x3 with a blank
     * bottom row registers as exactly this 2x3 -- the extra row would be decoration that
     * claims a constraint the game does not apply.
     */
    @Test
    public void thePatternPutsTheJecCalculatorInTheMiddle() {
        JsonArray pattern = recipe.getAsJsonArray("pattern");
        List<String> rows = new ArrayList<String>();
        for (JsonElement row : pattern) {
            rows.add(row.getAsString());
        }
        assertEquals(Arrays.asList(" P ", "SCR"), rows);
        assertEquals('C', rows.get(1).charAt(1));
    }

    /**
     * The recipe is skipped cleanly in a pack without JEC or AE2, rather than erroring.
     *
     * DELIBERATE, AND THERE IS NO FALLBACK RECIPE. This mod is built for MeatballCraft, which
     * ships both; the condition is here so that a pack without them gets a clean skip instead
     * of two `Unknown item` errors and an item that is uncraftable anyway. Inventing a vanilla
     * recipe for a user who does not exist would cost a second recipe to keep in step and buy
     * nothing. `forge:mod_loaded` is built into Forge 1.12.2 and needs no `_factories.json` --
     * checked against `CraftingHelper.init`, not against a wiki.
     */
    @Test
    public void theRecipeIsConditionalOnBothModsItAsksFor() {
        JsonArray conditions = recipe.getAsJsonArray("conditions");
        assertNotNull("without conditions this recipe errors in a pack lacking JEC or AE2",
                      conditions);
        List<String> required = new ArrayList<String>();
        for (JsonElement element : conditions) {
            JsonObject condition = element.getAsJsonObject();
            assertEquals("forge:mod_loaded", condition.get("type").getAsString());
            required.add(condition.get("modid").getAsString());
        }
        assertEquals(Arrays.asList("jecalculation", "appliedenergistics2"), required);
    }

    /** Every ingredient's mod is one the recipe is conditional on. */
    @Test
    public void noIngredientComesFromAModTheConditionsDoNotCover() {
        List<String> guarded = new ArrayList<String>();
        for (JsonElement element : recipe.getAsJsonArray("conditions")) {
            guarded.add(element.getAsJsonObject().get("modid").getAsString());
        }
        JsonObject key = recipe.getAsJsonObject("key");
        // `entrySet`, not `keySet`: gson 2.8.0 is what Minecraft 1.12.2 ships and build.gradle
        // pins, and `JsonObject.keySet` did not arrive until 2.8.1.
        for (java.util.Map.Entry<String, JsonElement> slot : key.entrySet()) {
            String item = slot.getValue().getAsJsonObject().get("item").getAsString();
            String modid = item.substring(0, item.indexOf(':'));
            if (!guarded.contains(modid) && !"minecraft".equals(modid)) {
                fail("slot " + slot.getKey() + " needs " + modid
                     + ", which no forge:mod_loaded condition guards");
            }
        }
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            out.write(chunk, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
