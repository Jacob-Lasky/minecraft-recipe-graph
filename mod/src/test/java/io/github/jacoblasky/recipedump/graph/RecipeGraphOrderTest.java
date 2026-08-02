package io.github.jacoblasky.recipedump.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * ITERATION ORDER, pinned. An order contract with no test is a comment.
 *
 * WHY THIS IS BEHAVIOUR AND NOT AN IMPLEMENTATION DETAIL. `solve.py` resolves cost ties with
 * `max`/`min` over the lists this model returns, so the winner is whichever candidate
 * iteration reached FIRST. Measured across three PYTHONHASHSEED values on the real pack, a
 * 90-node budget-exhausted plan is byte-identical, and that rests entirely on
 * `real_producers` returning an ordered list (#129). On top of that,
 * `tests/fixtures/plan/*.json` freezes whole solver results so this port can be asserted
 * against them: a list with the right members in the wrong order is a failing fixture with
 * no behavioural change to point at, and the obvious reading of that failure -- "the fixture
 * is stale, regenerate it" -- is the wrong one.
 *
 * EVERY FIXTURE HERE DELIBERATELY DISAGREES WITH ALPHABETICAL ORDER. That is the same guard
 * #129 put on the python side, and it exists because a fixture whose insertion order and
 * sorted order coincide passes under a `HashSet`, under a `TreeMap` and under the correct
 * implementation alike -- it tests nothing. {@link #theFixtureItselfIsNotAccidentallySorted}
 * guards the guard.
 */
public class RecipeGraphOrderTest {

    private static RecipeGraph graph;

    /**
     * Recipe ids in build order, with rids chosen so build order and alphabetical order
     * disagree: `zulu`, `alpha`, `mike`.
     */
    private static final int ZULU = 0;
    private static final int ALPHA = 1;
    private static final int MIKE = 2;
    private static final int WILDCARD_MAKER = 3;
    private static final int WILDCARD_EATER = 4;
    private static final int ORE_EATER_YANKEE = 5;
    private static final int ORE_EATER_BRAVO = 6;
    private static final int EXACT_WOOL_EATER = 7;
    private static final int ORE_EATER_QUEBEC = 8;

    @BeforeClass
    public static void buildFixture() {
        GraphBuilder b = new GraphBuilder();

        // Three producers of one key, and one of them is a container transfer sitting in the
        // MIDDLE, so `realProducers` has to preserve order across a filter rather than merely
        // across a copy.
        produce(b, "zulu", "fluid:target", false);
        produce(b, "alpha", "fluid:target", true);
        produce(b, "mike", "fluid:target", false);

        // A wildcard producer and consumer, so the exact-then-wildcard concatenation order is
        // observable.
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("minecraft:string"));
        b.endSlot();
        b.output(b.key("mod:wool:*"), 1);
        b.endRecipe("wildcard-maker", "crafting", null, "jar_json", false, false);

        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:wool:*"));
        b.endSlot();
        b.output(b.key("mod:carpet"), 1);
        b.endRecipe("wildcard-eater", "crafting", null, "jar_json", false, false);

        // Two consumers reached through two oredict groups, added so that neither the group
        // names nor the recipe ids are in alphabetical order.
        consume(b, "yankee", "ore:zincDust");
        consume(b, "bravo", "ore:aluminiumDust");

        // Added AFTER the wildcard eater ON PURPOSE. `consumers` must still return it FIRST,
        // which is what makes "exact, then wildcard, then oredict" an observable contract
        // rather than an artefact of the order recipes happened to be built in.
        consume(b, "romeo", "mod:wool:3");
        consume(b, "quebec", "ore:woolAny");

        // Members deliberately NOT alphabetical: `by_output` order is dump order, and the
        // oredict section's arrays are read as authored.
        b.beginOreGroup("zincDust");
        b.oreMember(b.key("mod:zinc_c"));
        b.oreMember(b.key("mod:zinc_a"));
        b.oreMember(b.key("mod:zinc_b"));
        b.endOreGroup();
        b.beginOreGroup("aluminiumDust");
        b.oreMember(b.key("mod:zinc_c"));
        b.endOreGroup();

        // `mod:wool:3` is a concrete meta of a wildcarded base and also an oredict member,
        // so all three arms of `consumers` fire on one key.
        b.beginOreGroup("woolAny");
        b.oreMember(b.key("mod:wool:3"));
        b.endOreGroup();

        graph = b.build();
    }

    private static void produce(GraphBuilder b, String rid, String output, boolean transfer) {
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:feedstock"));
        b.endSlot();
        b.output(b.key(output), 1);
        b.endRecipe(rid, "chemistry", null, "hei_dump", transfer, false);
    }

    private static void consume(GraphBuilder b, String rid, String input) {
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key(input));
        b.endSlot();
        b.output(b.key("mod:out_" + rid), 1);
        b.endRecipe(rid, "crafting", null, "jar_json", false, false);
    }

    private static List<Integer> producers(String key) {
        IntArray out = new IntArray();
        graph.producers(graph.keyId(key), out);
        return toList(out);
    }

    private static List<Integer> realProducers(String key) {
        IntArray out = new IntArray();
        graph.realProducers(graph.keyId(key), out);
        return toList(out);
    }

    private static List<Integer> consumers(String key) {
        IntArray out = new IntArray();
        graph.consumers(graph.keyId(key), out);
        return toList(out);
    }

    private static List<Integer> toList(IntArray values) {
        List<Integer> out = new ArrayList<Integer>(values.size());
        for (int i = 0; i < values.size(); i++) {
            out.add(Integer.valueOf(values.get(i)));
        }
        return out;
    }

    private static List<Integer> ids(int... values) {
        List<Integer> out = new ArrayList<Integer>(values.length);
        for (int value : values) {
            out.add(Integer.valueOf(value));
        }
        return out;
    }

    @Test
    public void producersComeBackInDumpOrderNotSortedByAnything() {
        assertEquals(ids(ZULU, ALPHA, MIKE), producers("fluid:target"));
    }

    @Test
    public void realProducersPreserveOrderAcrossTheTransferFilter() {
        // The suppressed recipe is the MIDDLE one, so a filter that rebuilt the list through
        // a set would be caught here and not by a filter at either end.
        assertEquals(ids(ZULU, MIKE), realProducers("fluid:target"));
    }

    @Test
    public void anExactMatchPrecedesTheWildcardFallbackWhichPrecedesTheOredictOne() {
        // `list(by_input[key])`, then `extend(by_input[base + ":*"])`, then one extend per
        // oredict group. The expected list is NOT in recipe-id order -- 7 before 4 before 8
        // -- which is the point: the concatenation order is the contract, not build order.
        assertEquals(ids(EXACT_WOOL_EATER, WILDCARD_EATER, ORE_EATER_QUEBEC),
                consumers("mod:wool:3"));
        assertEquals(ids(WILDCARD_MAKER), producers("mod:wool:3"));
    }

    @Test
    public void consumersConcatenateExactThenWildcardThenEachOredictGroup() {
        // `mod:zinc_c` is in two groups, added zincDust first. Its consumers must come back
        // in THAT order -- yankee (zincDust) then bravo (aluminiumDust) -- which is neither
        // alphabetical by rid nor alphabetical by group name.
        assertEquals(ids(ORE_EATER_YANKEE, ORE_EATER_BRAVO), consumers("mod:zinc_c"));
    }

    @Test
    public void oredictGroupsComeBackInMembershipOrderAndMembersInAuthoredOrder() {
        Csr ores = graph.oresOf();
        int zincC = graph.keyId("mod:zinc_c");
        List<String> groups = new ArrayList<String>();
        for (int p = ores.start(zincC); p < ores.end(zincC); p++) {
            groups.add(graph.oreGroupName(ores.at(p)));
        }
        assertEquals(Arrays.asList("zincDust", "aluminiumDust"), groups);

        Csr members = graph.oreMembers();
        int zinc = graph.oreGroupId("zincDust");
        List<String> keys = new ArrayList<String>();
        for (int p = members.start(zinc); p < members.end(zinc); p++) {
            keys.add(graph.key(members.at(p)));
        }
        assertEquals(Arrays.asList("mod:zinc_c", "mod:zinc_a", "mod:zinc_b"), keys);
    }

    @Test
    public void nbtVariantsComeBackInTheOrderRECIPESProducedThemNotInternOrder() {
        // CAUGHT BY THE ORACLE DIFF, NOT BY A UNIT TEST, which is why it is pinned here now.
        // Python builds its variant index by walking `by_output`, whose insertion order is
        // the order each key was first seen as some recipe's OUTPUT. Interning order is the
        // order keys were first seen anywhere -- as an ingredient, in the names section, or
        // as a catalyst -- and the two differ whenever a variant is mentioned before it is
        // made.
        //
        // Measured on the real graph, exactly three categories disagreed: `botania.orechid`,
        // `orechid_ignem` and `pureDaisy` each named a different `botania:specialflower`
        // variant as their craftable route. Same state, same build targets, every one of the
        // 161,514 prices identical -- only the evidence sentence moved, which is the quietest
        // way two implementations can drift apart.
        GraphBuilder b = new GraphBuilder();
        // The bare key exists as a catalyst on the real pack, which is what makes it
        // askable; here it is simply interned first.
        b.key("mod:flower");
        // Interned before it is produced, as a mere ingredient would be, and produced SECOND.
        b.key("mod:flower#bbbbbbbbbbbb");
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:petal"));
        b.endSlot();
        b.output(b.key("mod:flower#aaaaaaaaaaaa"), 1);
        b.endRecipe("makes-a", "crafting", null, "jar_json", false, false);
        b.beginRecipe();
        b.beginSlot(1, "item");
        b.alternative(b.key("mod:petal"));
        b.endSlot();
        b.output(b.key("mod:flower#bbbbbbbbbbbb"), 1);
        b.endRecipe("makes-b", "crafting", null, "jar_json", false, false);
        RecipeGraph variants = b.build();

        int base = variants.keyId("mod:flower");
        int[] found = variants.variantsOf(base);
        // An unknown key answers empty rather than throwing, since `variantsOf(keyId(name))`
        // is the natural call shape.
        assertEquals(0, variants.variantsOf(variants.keyId("mod:nope")).length);
        assertEquals(2, found.length);
        assertEquals("mod:flower#aaaaaaaaaaaa", variants.key(found[0]));
        assertEquals("mod:flower#bbbbbbbbbbbb", variants.key(found[1]));
        // The guard on the guard: intern order really is the other way round here, so this
        // fixture would pass under either rule if that stopped being true.
        assertTrue(variants.keyId("mod:flower#bbbbbbbbbbbb")
                < variants.keyId("mod:flower#aaaaaaaaaaaa"));
    }

    @Test
    public void theFixtureItselfIsNotAccidentallySorted() {
        // The guard on the guard. A fixture whose build order happens to equal its sorted
        // order passes under a HashSet, under a TreeMap and under the correct implementation
        // alike, so it proves nothing. If a later edit tidies these names into alphabetical
        // order, this fails and says why before the other cases quietly stop testing.
        assertFalse("producer rids must not be in alphabetical order",
                isSorted(ridsOf(producers("fluid:target"))));
        assertFalse("oredict members must not be in alphabetical order",
                isSorted(Arrays.asList("mod:zinc_c", "mod:zinc_a", "mod:zinc_b")));
        assertFalse("a key's oredict groups must not be in alphabetical order",
                isSorted(Arrays.asList("zincDust", "aluminiumDust")));
    }

    private static List<String> ridsOf(List<Integer> recipes) {
        List<String> out = new ArrayList<String>(recipes.size());
        for (Integer recipe : recipes) {
            out.add(graph.recipes().rid(recipe.intValue()));
        }
        return out;
    }

    private static boolean isSorted(List<String> values) {
        for (int i = 1; i < values.size(); i++) {
            if (values.get(i - 1).compareTo(values.get(i)) > 0) {
                return false;
            }
        }
        return true;
    }
}
