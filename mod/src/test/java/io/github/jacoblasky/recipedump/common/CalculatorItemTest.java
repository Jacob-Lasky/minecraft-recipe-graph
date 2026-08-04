package io.github.jacoblasky.recipedump.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
 * uncraftable. A craft still cannot be attempted here, but since every ingredient became
 * vanilla the ids can be RESOLVED rather than only spelled: `Bootstrap.register` gives this
 * test the real registries, so `everyIngredientResolvesToAnItemThatExists` catches a typo that
 * string comparison against a remembered id never could.
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
     * The four components, each doing in the recipe the job it does in the item.
     *
     * A Book is the TODO list that survives a relog. A Comparator is the vanilla block whose
     * entire purpose is reading how much is in a container, which is what a plan is diffed
     * against. A Crafting Table is the set of recipes being planned over. An Ender Pearl is
     * reading that stock at range rather than standing at the chest.
     */
    @Test
    public void theRecipeAsksForTheFourComponentsThatJustifyTheItem() {
        JsonObject key = recipe.getAsJsonObject("key");
        assertEquals("minecraft:book", key.getAsJsonObject("B").get("item").getAsString());
        assertEquals("minecraft:comparator", key.getAsJsonObject("Q").get("item").getAsString());
        assertEquals("minecraft:crafting_table",
                     key.getAsJsonObject("C").get("item").getAsString());
        assertEquals("minecraft:ender_pearl", key.getAsJsonObject("E").get("item").getAsString());
        assertEquals("the pattern uses exactly the four keys the lore names",
                     4, key.entrySet().size());
    }

    /**
     * Every id in the recipe resolves to an item that exists.
     *
     * THE POINT OF THE WHOLE FILE, and it only became possible once the ingredients were
     * vanilla. The class comment says a craft cannot be attempted here so the ids can only be
     * eyeballed; that was true while they came from AE2 and JEC, which are not on the test
     * classpath. `Bootstrap.register` populates the real vanilla registries, so a typo now
     * fails here instead of surfacing as an `Unknown item` line in a log nobody reads and an
     * item that is silently uncraftable.
     */
    @Test
    public void everyIngredientResolvesToAnItemThatExists() {
        for (java.util.Map.Entry<String, JsonElement> slot
                : recipe.getAsJsonObject("key").entrySet()) {
            String id = slot.getValue().getAsJsonObject().get("item").getAsString();
            assertNotNull("slot " + slot.getKey() + " names " + id
                          + ", which is not a registered item",
                          net.minecraft.item.Item.getByNameOrId(id));
        }
    }

    /**
     * EVERY ingredient states its metadata, including the ones whose meta is 0.
     *
     * Forge does not treat `data` as optional for an item with subtypes: `getItemStackBasic`
     * throws `Missing data for item` and the whole recipe is dropped with an error in the log
     * and no other symptom. It did exactly that on the first dev-client boot of an earlier
     * version of this recipe, whose centre item had two subtypes and whose omitted `"data": 0`
     * looked harmless.
     *
     * None of today's four ingredients has subtypes, so all four would survive the omission.
     * The rule stays absolute anyway, because the next ingredient someone reaches for is the
     * one that does, and a rule with an exception is a rule nobody applies.
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

    /**
     * The Crafting Table sits in the middle, which is the lore: this queries one.
     *
     * NO TRAILING BLANK ROW, and that is not an oversight. `ShapedRecipes.shrink` strips empty
     * rows and columns before the recipe is registered, so a written-out 3x3 with a blank
     * bottom row registers as exactly this 2x3 -- the extra row would be decoration that
     * claims a constraint the game does not apply.
     */
    @Test
    public void thePatternPutsTheCraftingTableInTheMiddle() {
        JsonArray pattern = recipe.getAsJsonArray("pattern");
        List<String> rows = new ArrayList<String>();
        for (JsonElement row : pattern) {
            rows.add(row.getAsString());
        }
        assertEquals(Arrays.asList(" B ", "QCE"), rows);
        assertEquals('C', rows.get(1).charAt(1));
    }

    /**
     * EVERY ingredient is vanilla, so the item is craftable in any pack that loads this mod.
     *
     * THIS REPLACES A DELIBERATE DECISION THAT WENT THE OTHER WAY, and the reasoning it
     * replaces is recorded here rather than deleted. The first recipe was built around
     * `jecalculation:item_calculator` with `forge:mod_loaded` conditions on JEC and AE2, on the
     * argument that this mod targets MeatballCraft, which ships both, and that a vanilla
     * fallback would be a second recipe to keep in step for a user who does not exist.
     *
     * The cost that argument missed is not the missing user. It is that requiring another
     * mod's calculator declares this mod an ADD-ON to that mod, and a player who wants a plan
     * has to build JEC's calculator first to get one. The conditions made it worse in a way
     * that is invisible: in a pack without both mods the recipe is skipped cleanly and the item
     * exists, in the creative tab, uncraftable, with nothing anywhere saying why.
     *
     * So the rule is now the strong one, asserted rather than intended: no ingredient may come
     * from any mod, and there must be no `forge:mod_loaded` condition to need. Reaching for one
     * mod's item is how the dependency comes back, and it comes back looking reasonable.
     */
    @Test
    public void everyIngredientIsVanillaAndTheRecipeIsGatedOnNoMod() {
        assertNull("a mod-gated recipe is skipped in packs without that mod, leaving an item"
                   + " that exists and cannot be crafted, with no in-game explanation",
                   recipe.get("conditions"));
        JsonObject key = recipe.getAsJsonObject("key");
        // `entrySet`, not `keySet`: gson 2.8.0 is what Minecraft 1.12.2 ships and build.gradle
        // pins, and `JsonObject.keySet` did not arrive until 2.8.1.
        for (java.util.Map.Entry<String, JsonElement> slot : key.entrySet()) {
            String item = slot.getValue().getAsJsonObject().get("item").getAsString();
            String modid = item.substring(0, item.indexOf(':'));
            if (!"minecraft".equals(modid)) {
                fail("slot " + slot.getKey() + " needs " + modid + ", so the calculator is"
                     + " uncraftable without it; every ingredient must be vanilla");
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
