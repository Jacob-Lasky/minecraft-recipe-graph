/**
 * The planner's ranking half: which machines you have, and what everything costs to obtain.
 *
 * <h2>Read these in this order</h2>
 *
 * <ol>
 * <li>{@link io.github.jacoblasky.recipedump.plan.MachineInfo} -- the four states and why
 *     collapsing any pair of them produces wrong plans. Short, and everything else assumes
 *     it.</li>
 * <li>{@link io.github.jacoblasky.recipedump.plan.Cost} -- what the numbers MEAN. The
 *     constants carry the arguments; the code is short by comparison.</li>
 * <li>{@link io.github.jacoblasky.recipedump.plan.Machines} -- how a category is matched to a
 *     block, which is a pile of heuristics over strings mods were never obliged to make
 *     consistent.</li>
 * </ol>
 *
 * The rest are the pieces those three hand around:
 * {@link io.github.jacoblasky.recipedump.plan.Evidence} (world state in),
 * {@link io.github.jacoblasky.recipedump.plan.MachineStates} and
 * {@link io.github.jacoblasky.recipedump.plan.CostTable} (results out),
 * {@link io.github.jacoblasky.recipedump.plan.CostInputs},
 * {@link io.github.jacoblasky.recipedump.plan.Structures} (multiblock pricing) and
 * {@link io.github.jacoblasky.recipedump.plan.Tokens}.
 *
 * <h2>Three rules that hold for the whole package</h2>
 *
 * <p>THE ORDERING IS THE CLAIM, NOT THE MAGNITUDES. Almost no constant here has an argument
 * for its exact value -- nobody has measured whether a Sedna trip is 800 afternoons or 8 --
 * and all of them have an argument for where they sit relative to each other. Moving a
 * magnitude is tuning; reordering a pair is a behaviour change, and
 * {@code CostOrderingTest} makes it argue with a test.
 *
 * <p>EXACT REPRODUCTION OF THE PYTHON ARITHMETIC IS A REQUIREMENT. IEEE 754 binary64 is the
 * same in both languages, so anything less than bit-identical is a defect rather than a
 * rounding difference. Everything stays {@code double}, operation ORDER is preserved, and
 * every {@code min} keeps the FIRST extremum because python's does. Verified end to end by
 * {@code mod/tools/cost-oracle.sh}: all 161,514 prices and all 504 machine verdicts identical
 * on the reference graph.
 *
 * <p>PYTHON IS THE ORACLE. When the two disagree, this side moves, and the fix belongs beside
 * the comment in `cost.py` or `machines.py` that says why the rule exists.
 *
 * <h2>What is deliberately absent</h2>
 *
 * The disk cache -- `fingerprint`, `estimate_cached`, `cache_beside` -- is not ported. Python
 * memoises because a relaxation costs it 100 seconds on the reference graph; this runs in
 * 2.7, which is affordable once at startup. Anyone adding one must fingerprint the FORMULA as
 * well as the inputs; {@code Cost.estimate} says why.
 *
 * `machines.py`'s reporting surface -- `responsibilities` (`machines.py:484`),
 * `mod_state_counts` (`:631`), `state_totals` (`:653`), `mod_order` (`:671`), and the
 * `machines.json` reader and writer -- is not ported yet. NOT BECAUSE IT IS BEING RETIRED:
 * #19 Phase 6 deletes the web UI only ONCE THE MACHINES PAGE EXISTS IN GAME, so those four
 * functions are the arithmetic that work needs and this is a to-port list rather than a
 * won't-port one. Port them here, against the Python as oracle, the same as everything above.
 */
package io.github.jacoblasky.recipedump.plan;
