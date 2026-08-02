/**
 * The recipe graph, in a form that fits a Minecraft client's spare heap.
 *
 * <h2>Read these in this order</h2>
 *
 * <ol>
 * <li>{@link io.github.jacoblasky.recipedump.graph.RecipeGraph} -- what a graph IS, and why
 *     it is flat arrays rather than the object graph `recipegraph/model.py` uses. Start
 *     here; everything else is machinery it needs.</li>
 * <li>{@link io.github.jacoblasky.recipedump.graph.Keys} -- what an item key MEANS. The
 *     string grammar both this and the python oracle have to read identically.</li>
 * <li>{@link io.github.jacoblasky.recipedump.graph.GraphBuilder} -- how one is assembled,
 *     and where every derived index is computed.</li>
 * <li>{@link io.github.jacoblasky.recipedump.graph.GraphJsonReader} -- how one is loaded
 *     from `graph.json` without ever holding the document.</li>
 * </ol>
 *
 * The rest are the storage primitives those four are built out of:
 * {@link io.github.jacoblasky.recipedump.graph.StringTable} (interning),
 * {@link io.github.jacoblasky.recipedump.graph.Csr} (one-to-many int indexes),
 * {@link io.github.jacoblasky.recipedump.graph.RecipeStore} (recipes),
 * {@link io.github.jacoblasky.recipedump.graph.Bits},
 * {@link io.github.jacoblasky.recipedump.graph.IntArray} and
 * {@link io.github.jacoblasky.recipedump.graph.Sizes}. Two carry pack data that only the
 * cost model reads: {@link io.github.jacoblasky.recipedump.graph.Multiblocks} and
 * {@link io.github.jacoblasky.recipedump.graph.FluidNames}.
 *
 * <h2>Two rules that hold for the whole package</h2>
 *
 * <p>NO MINECRAFT AND NO FORGE IMPORTS. Not a style preference: it is what lets the model be
 * unit-tested with no game running, and what lets the heap harness measure it in a bare JVM
 * whose baseline is not polluted by a loaded client. Convert to and from {@code ItemStack}
 * at the boundary, in the caller.
 *
 * <p>PYTHON IS THE ORACLE. `recipegraph/model.py` is the authoritative description of what a
 * graph is (#19). When the two disagree about what a key means or which recipes produce
 * something, this side is what moves, and the fix belongs beside the comment in `model.py`
 * that says why.
 *
 * <h2>Why the shape is what it is</h2>
 *
 * <p>Measured for #126 against the reference pack: 117,681 recipes, 261,095 display names,
 * 265,980 distinct keys. A transliteration of `model.py` into Java objects retains 364 MB on
 * Java 8; this package retains 43 MB and loads in a 96 MB heap. The gate was 400 MB. Nothing
 * here is premature optimisation -- the ladder between those two numbers is in the PR that
 * introduced the package, and {@code NaiveGraph} in the test source set is the measurement
 * that produced it.
 */
package io.github.jacoblasky.recipedump.graph;
