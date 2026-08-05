package io.github.jacoblasky.recipedump.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The bare-to-variant relation, clause by clause. #170.
 *
 * WHY A DEDICATED FILE WITH ITS OWN GRAPHS rather than four more cases on
 * {@link RecipeGraphTest}'s shared fixture. Each clause needs a graph that differs from the
 * next by ONE edge -- the bare key gaining a producer, the variant's recipe gaining the bare
 * key as an ingredient -- and a shared fixture cannot express "the same graph without this
 * one recipe". Building them separately is what makes each assertion attributable to the
 * clause it names.
 *
 * THE PYTHON SIDE HAS THE SAME CASES, in `tests/test_unsourced.py`'s
 * `ProducedOnlyAsAVariantTest`, and `Graph.variant_subsumption` carries the argument for
 * every clause and the measurements behind it. This file is the port's own gate on the
 * relation: `PlanFixtureTest` proves the two planners agree on the reference oracle, which
 * catches a divergence but cannot say which clause diverged.
 */
public class VariantSubsumptionTest {

    private static final String BARE = "mod:kama_bound";
    private static final String MADE = "mod:kama_bound#fd1adc426e12";
    private static final String SIBLING = "mod:kama_bound#aaaaaaaaaaaa";

    /** One recipe: `inputs -> output x1`, hand crafting, not a transfer. */
    private static void recipe(GraphBuilder b, String rid, String output, String... inputs) {
        b.beginRecipe();
        for (String input : inputs) {
            b.beginSlot(1, "item");
            b.alternative(b.key(input));
            b.endSlot();
        }
        b.output(b.key(output), 1);
        b.endRecipe(rid, "crafting_shaped", null, "jar_json", false, false);
    }

    /** The reported shape: something wants the bare key, and the array makes a variant. */
    private static RecipeGraph reported() {
        GraphBuilder b = new GraphBuilder();
        recipe(b, "fixture:use", "mod:out", BARE);
        recipe(b, "fixture:array", MADE, "mod:reagent");
        return b.build();
    }

    private static int[] satisfying(RecipeGraph g, String key) {
        IntArray out = new IntArray();
        g.satisfyingVariants(g.keyId(key), out);
        return out.trimmed();
    }

    @Test
    public void aProducedVariantSatisfiesADemandForTheBareKey() {
        RecipeGraph g = reported();
        assertEquals(g.keyId(BARE), g.subsumedBareKey(g.keyId(MADE)));
        assertArray(new int[] {g.keyId(MADE)}, satisfying(g, BARE));
    }

    @Test
    public void theReverseDirectionIsRefused() {
        // #28's constraint, which #170 does not relax: a slot naming `X#d` is matched by that
        // stack and not by bare `X`, and not by a sibling variant either. The relation only
        // ever answers for a bare key, so `producers` never has to widen.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "fixture:use", "mod:out", MADE);
        recipe(b, "fixture:plain", BARE, "mod:reagent");
        recipe(b, "fixture:sibling", SIBLING, "mod:reagent");
        RecipeGraph g = b.build();
        assertEquals(0, satisfying(g, MADE).length);
        assertEquals(-1, g.subsumedBareKey(g.keyId(BARE)));
    }

    @Test
    public void aBareKeyWithItsOwnRouteIsExcluded() {
        // CLAUSE 2, and the control class it protects is twenty times the size of the defect:
        // 2,117 bare keys on the reference graph carry this relation with an honest route of
        // their own, and 82 have a produced variant CHEAPER than themselves. A rule keyed on
        // the relation rather than on the absence of a route cheapens
        // `simplyjetpacks:itemjetpack:14` by 597x.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "fixture:use", "mod:out", BARE);
        recipe(b, "fixture:array", MADE, "mod:reagent");
        recipe(b, "fixture:plain", BARE, "mod:expensive");
        RecipeGraph g = b.build();
        assertEquals(0, satisfying(g, BARE).length);
        assertEquals(-1, g.subsumedBareKey(g.keyId(MADE)));
    }

    @Test
    public void anUnproducedVariantIsExcluded() {
        // CLAUSE 3. The variant is named as an INGREDIENT and nothing makes it, so it cannot
        // satisfy anything.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "fixture:use", "mod:out", BARE);
        recipe(b, "fixture:consume", "mod:other", MADE);
        RecipeGraph g = b.build();
        assertEquals(0, satisfying(g, BARE).length);
    }

    @Test
    public void aVariantMadeFromTheBareKeyIsExcluded() {
        // CLAUSE 4, one hop: the `X -> X#d -> X` shape, 9 keys on the reference graph. An
        // Ender IO Soul Binder turns a Broken Spawner into a Broken Spawner with a mob in its
        // NBT, and pricing the bare key through that prices "get one" at what "get one and
        // upgrade it" costs.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "fixture:use", "mod:out", BARE);
        recipe(b, "fixture:upgrade", MADE, BARE);
        RecipeGraph g = b.build();
        assertEquals(0, satisfying(g, BARE).length);
    }

    @Test
    public void aVariantMadeFromASiblingVariantIsExcluded() {
        // CLAUSE 4, one hop longer: the container gate.
        // `thermalexpansion:reservoir:32000` is the measured case -- one recipe demands the bare
        // key directly, both produced variants are Fluid Transposer output made from the family,
        // and without this half it prices 521.0 against the 2,000 floor. The shape is clearest
        // on `extratrees:drink`, a Beer Mug whose 30 variants are all filled mugs made from an
        // EMPTY mug variant, but that key has no direct consumer so no plan moves for it. See
        // `Graph.variant_subsumption` in python, which keeps the two apart.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "fixture:use", "mod:out", BARE);
        recipe(b, "fixture:fill", MADE, SIBLING);
        recipe(b, "fixture:empty", SIBLING, BARE);
        RecipeGraph g = b.build();
        assertEquals(0, satisfying(g, BARE).length);
    }

    @Test
    public void aSiblingWithAnHonestRouteSurvivesTheFamilyClause() {
        // The family clause must not swallow the whole family. Here `SIBLING` is made from
        // clay and `MADE` is made from `SIBLING`, so the bare key is priced through the
        // sibling -- which is the cheaper one anyway, since the other is it plus work.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "fixture:use", "mod:out", BARE);
        recipe(b, "fixture:plain", SIBLING, "minecraft:clay");
        recipe(b, "fixture:upgrade", MADE, SIBLING);
        RecipeGraph g = b.build();
        assertArray(new int[] {g.keyId(SIBLING)}, satisfying(g, BARE));
    }

    @Test
    public void theVariantsComeBackInDumpOrder() {
        // It reaches a plan tree and `tests/fixtures/plan/*.json` freeze those for this port,
        // so a variant chosen by index order must not move between runs. `variantsOf` is
        // recipe-output order, which is the order the dump saw.
        GraphBuilder b = new GraphBuilder();
        recipe(b, "fixture:use", "mod:out", BARE);
        recipe(b, "fixture:second", SIBLING, "mod:reagent");
        recipe(b, "fixture:first", MADE, "mod:reagent");
        RecipeGraph g = b.build();
        assertArray(new int[] {g.keyId(SIBLING), g.keyId(MADE)}, satisfying(g, BARE));
    }

    @Test
    public void anUnknownKeyIsAnOrdinaryAnswerRatherThanAnIndexError() {
        // `subsumedBareKey(keyId(name))` is the natural call and a name the graph never saw
        // yields -1, exactly as `variantsOf` tolerates it.
        RecipeGraph g = reported();
        assertEquals(-1, g.subsumedBareKey(g.keyId("mod:never_heard_of_it")));
        assertEquals(-1, g.subsumedBareKey(-1));
        assertEquals(0, satisfying(g, "mod:never_heard_of_it").length);
    }

    @Test
    public void theRelationIsAccountedForInTheHeapReport() {
        // A lazily built index reported as free understates the steady state of any process
        // that plans, which is every process since #170. `sizes()` counts it once built.
        RecipeGraph g = reported();
        long before = g.sizes().total();
        g.subsumedBareKey(g.keyId(MADE));
        assertTrue("building the relation must show up in the heap report",
                   g.sizes().total() > before);
    }

    @Test
    public void theRelationIsSpeltOnceInThisSourceTree() throws java.io.IOException {
        // THE SAME GUARD PYTHON KEEPS, in `ThereIsOnlyOneSpellingOfTheSubsumptionRelationTest`,
        // and for the same reason: two consumers, `Cost.relax` and `Solver.expand`, is exactly
        // the shape that drifted before. Structural rather than comparative, because a
        // comparison test is written by whoever forgot the branch. Reads the sources as TEXT,
        // the trick `NodeStatusTest` already uses to hold this port to python's own constants.
        java.util.List<String> defining = new java.util.ArrayList<String>();
        for (String dir : new String[] {"graph", "plan"}) {
            java.io.File folder = new java.io.File(repoRoot(),
                    "mod/src/main/java/io/github/jacoblasky/recipedump/" + dir);
            java.io.File[] files = folder.listFiles();
            assertTrue("expected sources under " + folder, files != null && files.length > 0);
            for (java.io.File file : files) {
                if (!file.getName().endsWith(".java")) {
                    continue;
                }
                String source = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                                           java.nio.charset.StandardCharsets.UTF_8);
                if (source.contains(" subsumedBareKey(int")
                        || source.contains(" satisfyingVariants(int")) {
                    defining.add(file.getName());
                }
            }
        }
        assertEquals("the relation and its view belong to RecipeGraph and nowhere else: "
                     + defining, 1, defining.size());
        assertEquals("RecipeGraph.java", defining.get(0));
    }

    /** `..` then `.`, for the same reason `PlanFixtures` and `NodeStatusTest` do it. */
    private static java.io.File repoRoot() {
        for (String prefix : new String[] {"../", "./"}) {
            if (new java.io.File(prefix + "recipegraph/model.py").isFile()) {
                return new java.io.File(prefix);
            }
        }
        throw new IllegalStateException("recipegraph/ not found -- mount the repository root");
    }

    private static void assertArray(int[] expected, int[] actual) {
        assertEquals("length", expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals("at " + i, expected[i], actual[i]);
        }
    }
}
