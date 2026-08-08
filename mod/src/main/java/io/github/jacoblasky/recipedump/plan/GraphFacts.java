package io.github.jacoblasky.recipedump.plan;

import io.github.jacoblasky.recipedump.graph.RecipeGraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which graph the planner is answering from, and how big it is.
 *
 * THIS IS NOT A PORT OF `stats_page`, AND THE DIFFERENCE IS THE POINT. That page answers "how
 * complete is this graph" for whoever BUILT it -- four totals, a by-source tally and the
 * twenty-five biggest categories. In a browser that reader is the person who ran the dump and
 * chose the file the server loads, and the question is a maintenance one.
 *
 * IN GAME NOBODY IS THAT PERSON. A player holding the calculator does not ask how many oredict
 * entries their graph has. They ask a sharper question the same numbers answer, and it is one
 * the browser never had to: <b>am I planning against the right graph at all?</b> In the browser
 * the operator picked the file explicitly. In game `graph.json` is a file the player copied
 * into `config/mcrecipedump/`, where -- as `GraphSource`'s own header notes -- it "survives a
 * pack update", so the live failure is a STALE graph confidently answering for a pack that has
 * moved underneath it. Nothing in the mod reports that today.
 *
 * So this carries identity first and size second: the instance the dump came from, the mod
 * version that wrote it, the schema, and then the totals as a fingerprint rather than as
 * statistics. Two graphs are told apart by those numbers far more readably than by a sha.
 *
 * WHAT IS DELIBERATELY DROPPED: `stats_page`'s twenty-five biggest categories. That is a
 * maintainer's curiosity in a 1,424px browser table and noise on a 388px panel, and it is
 * already answerable better on the machines screen (#254), whose rows sort by recipe count
 * inside each state. Porting it here out of symmetry would spend a third of the panel
 * restating another screen.
 *
 * IN `plan/` because it computes and does not draw, which is also what puts it in the one job
 * `tools/ci-java.sh` runs on every pull request.
 */
public final class GraphFacts {

    /** One recipe source (`hei_dump`, `jar_json`, ...) and how many recipes it supplied. */
    public static final class Source {

        private final String name;
        private final int recipes;

        Source(String name, int recipes) {
            this.name = name;
            this.recipes = recipes;
        }

        public String name() {
            return name;
        }

        public int recipes() {
            return recipes;
        }
    }

    private final String instanceDir;
    private final String dumpVersion;
    private final int dumpSchema;
    private final int recipes;
    private final int keys;
    private final int namedKeys;
    private final int categories;
    private final int oreGroups;
    private final List<Source> sources;

    private GraphFacts(String instanceDir, String dumpVersion, int dumpSchema, int recipes,
                       int keys, int namedKeys, int categories, int oreGroups,
                       List<Source> sources) {
        this.instanceDir = instanceDir;
        this.dumpVersion = dumpVersion;
        this.dumpSchema = dumpSchema;
        this.recipes = recipes;
        this.keys = keys;
        this.namedKeys = namedKeys;
        this.categories = categories;
        this.oreGroups = oreGroups;
        this.sources = Collections.unmodifiableList(sources);
    }

    /**
     * Read every fact off the graph in one walk.
     *
     * ONE PASS OVER THE RECIPES, and on the reference pack that is 124,467 of them. Cheap
     * enough to do when the screen opens rather than caching it -- the graph is immutable once
     * loaded, so the only thing a cache would buy is skipping a walk nobody notices, at the
     * cost of a second thing that can be stale.
     */
    public static GraphFacts of(RecipeGraph graph) {
        Map<Integer, Integer> bySourceId = new LinkedHashMap<Integer, Integer>();
        int recipes = graph.recipes().count();
        for (int recipe = 0; recipe < recipes; recipe++) {
            Integer source = Integer.valueOf(graph.recipes().sourceId(recipe));
            Integer seen = bySourceId.get(source);
            bySourceId.put(source, Integer.valueOf(seen == null ? 1 : seen.intValue() + 1));
        }

        List<Source> sources = new ArrayList<Source>();
        for (Map.Entry<Integer, Integer> entry : bySourceId.entrySet()) {
            String name = graph.sourceName(entry.getKey().intValue());
            // AN UNNAMED SOURCE IS SHOWN, NOT SKIPPED. A recipe whose source id resolves to
            // nothing is exactly the case worth seeing on a screen about whether the graph is
            // trustworthy, and dropping it would make the per-source figures fail to sum to
            // the recipe total with nothing to explain the gap.
            sources.add(new Source(name == null || name.isEmpty() ? "(unnamed)" : name,
                                   entry.getValue().intValue()));
        }
        // BIGGEST FIRST, then by name so the order is total. `by_source` in Python is
        // insertion-ordered off the recipe walk, which is stable for one graph and meaningless
        // to a reader; the interesting fact is which source supplied most of the pack.
        Collections.sort(sources, new Comparator<Source>() {
            @Override
            public int compare(Source left, Source right) {
                if (left.recipes() != right.recipes()) {
                    return right.recipes() - left.recipes();
                }
                return left.name().compareTo(right.name());
            }
        });

        return new GraphFacts(graph.instanceDir(), graph.dumpVersion(), graph.dumpSchema(),
                              recipes, graph.keyCount(), graph.namedKeyCount(),
                              graph.categoryCount(), graph.oreGroupCount(), sources);
    }

    /**
     * The Minecraft instance the dump was taken from, or "" when the graph records none.
     *
     * THE MOST USEFUL SINGLE LINE ON THE SCREEN, and it is why this class exists. A player
     * with two packs, or one pack reinstalled to a new folder, can read this and know in one
     * glance whether the graph belongs to the game they are in. #226 records that this string
     * is identity-bearing enough that two graphs built from one dump at different paths are
     * not byte-comparable.
     */
    public String instanceDir() {
        return instanceDir == null ? "" : instanceDir;
    }

    /** The `/recipedump` build that wrote the dump, or "". */
    public String dumpVersion() {
        return dumpVersion == null ? "" : dumpVersion;
    }

    public int dumpSchema() {
        return dumpSchema;
    }

    public int recipes() {
        return recipes;
    }

    public int keys() {
        return keys;
    }

    public int namedKeys() {
        return namedKeys;
    }

    public int categories() {
        return categories;
    }

    public int oreGroups() {
        return oreGroups;
    }

    /** Recipe sources, busiest first. */
    public List<Source> sources() {
        return sources;
    }
}
