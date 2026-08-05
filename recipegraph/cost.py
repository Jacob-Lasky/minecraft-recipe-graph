"""Estimated cost to obtain each item, so recipe choice can stop being greedy.

WHY THIS EXISTS. Scoring a recipe by local properties alone -- do I own the machine, are
its inputs in stock, how few inputs does it have -- cannot see what a branch costs further
down. Two observed failures, both from real runs:

  * without machine gating, "64 Borax" routed through Chaos Fragments and Nether Stars;
  * with machine gating but still greedy, it preferred an enormous chain through machines
    it owned (11,000,000 mB of water, bacteria vectors, Pink Tulips) over a two-step
    chemical route through a Crystallizer it merely had to build.

Both are the same mistake: a locally attractive recipe whose subtree is ruinous. The fix
is to precompute, for every item, roughly what it costs to obtain, then let the solver
pick the recipe with the cheapest total. That turns choice from a guess into a comparison.

THE ESTIMATE IS A RANKING, NOT A PLAN. It ignores cycles, assumes inputs can be had
independently, and never double-counts shared intermediates. It exists to rank recipes,
not to be reported as a real cost. DO NOT surface these numbers to users as quantities.

It is deliberately NOT a lower bound, and that distinction is the fix for issue #29. A true
lower bound amortises everything over the output quantity, and the pack contains recipes
that output 1,024 iron ingots or 60,466,176 fruit at once; amortising the machine over
those makes it free and prices the output at nothing. Cost of ENTRY (the machine) is
charged per run and only the ingredients amortise, so a route cannot be made cheap by
being enormous. See the comment on `per_unit` in `estimate`.

Costs are relaxed iteratively (Bellman-Ford style) rather than solved exactly: the graph
has cycles and AND-nodes (a recipe needs *all* its inputs), so there is no simple
shortest-path formulation. A handful of passes converges well enough to rank by.
"""

import hashlib
import json
import math
import os

from . import multiblocks as multiblocks_mod
from . import tokens
from .defaults import DEFAULT_COST_CACHE
from .model import FLUID_PREFIX
from .tokens import GATE, HINT, LOOT, METHOD

# What a machine costs to route through. Owning it is nearly free; building one is a real
# but one-off expense; using one you cannot get should lose to almost anything.
#
# `unknown` sits between buildable and unavailable ON PURPOSE. It means the category's
# machine could not be identified, which is a gap in this tool, not a fact about the
# player's base, so it must not be priced as unusable -- doing that put 40% of the
# reference pack's recipes behind a 5,000 wall. It must also not undercut `buildable`,
# or an unidentified machine would beat one the player can demonstrably build.
MACHINE_COST = {"have": 1.0, "buildable": 40.0, "unknown": 120.0, "unavailable": 5000.0}

# `buildable` above is a FLOOR, not the whole answer. Two machines you have to build are
# not equally expensive, and on the reference pack they are not remotely equally expensive:
# measured over the 380 buildable categories whose machine item prices finitely, the build
# cost runs min 1.0, median 2.0, p90 67, max 9,288 -- a 4,644x spread between the median and
# an endgame NuclearCraft salt fission vessel. Priced by the flat constant alone, an AE2
# grindstone and a fusion reactor both charge 40.0, so the ranker cannot prefer the one the
# player can actually reach. That is issue #86.
#
# So a buildable machine's entry cost spans [MACHINE_COST["buildable"], + BUILD_SPREAD),
# ordered by what building it costs. The map is bounded and monotonic rather than the raw
# price, for two reasons:
#
#   * THE CEILING MUST STAY BELOW MACHINE_COST["unknown"]. That invariant is stated above
#     and is load-bearing: an unidentified machine outranking one the player can
#     demonstrably build is the failure the `unknown` figure was chosen to avoid. Feeding
#     the raw 9,288 in would sail past `unknown` AND past `unavailable`, making a buildable
#     machine read as worse than a proven-impossible one.
#   * The floor must stay AT MACHINE_COST["buildable"]. The have-vs-buildable gap is what
#     the Borax and Crystallizer cases in this module's header turn on, and cheapening
#     buildable machines across the board would relitigate a decision this change is not
#     about. #86 asks to tell two buildable machines apart, not to make building cheaper.
#
# BUILD_SCALE sets where the curve bends. At 64 it lands near p90 of the measured
# distribution, so the mass of the pack (median 2.0) spreads across the low end of the band
# instead of all compressing into the first percent of it.
#
# THE CURVE IS LOGARITHMIC IN THE BUILD COST, and #93 is why. It was `b / (b + BUILD_SCALE)`,
# which is calibrated for the range a machine ITEM's recipe spans (1.0 to 9,288) and
# saturates hard above it: everything past ~6,400 sits within 1% of the ceiling. Pricing a
# Modular Machinery machine by the structure it stands for widened the input range by three
# decades, to 279,861, and 42 of the 104 fully-priced multiblocks came out within 1.0 of the
# ceiling -- flattened against each other, which is exactly the defect #86 removed, only
# among the expensive machines instead of all of them.
#
# BUILD_SLOPE IS WHAT KEEPS THE LOW END WHERE #86 MEASURED IT, which is the floor bullet above
# read in the other direction: making buildable machines DEARER would relitigate the
# Crystallizer case just as surely as cheapening them, by letting an enormous chain through
# owned machines beat a two-step route through a machine merely to be built. Near b=0 the curve
# is `BUILD_SLOPE * b / BUILD_SCALE`, so the SLOPE is the calibrated quantity and the spread is
# free to move as long as the knee follows it. #93 measured the tolerance: a build cost of 1.0
# prices at 41.21 against the old 41.22, and the median 2.0 at 42.36 against 42.39, agreeing to
# within 0.05 across the mass of the pack. Any change to the SLOPE moves that whole low end, so
# re-measure with tools/cost-probe.py before touching it.
BUILD_SLOPE = 79.0
BUILD_SCALE = 64.0
BUILD_SPREAD = 70.0
BUILD_KNEE = BUILD_SPREAD / BUILD_SLOPE

# THE REGION BETWEEN THE PRICED BAND AND `unknown` IS NOT SPARE ROOM, IT IS THREE CLAIMS THAT
# USED TO SHARE ONE NUMBER. That is issue #95. Everything a price could not be computed for
# landed on the single band ceiling, and on the reference pack that was 140 of 403 categories,
# 35% of them, holding two unrelated statements and destroying the ordering among both:
#
#   * 117 Modular Machinery categories whose structure needs a block nothing in the graph
#     makes. Measured, these run from 0.14% of positions blocked (`the_cube`, 3 of 2,125) to
#     100% (`mythic_excavation_lattice`, 135 of 135), and all 117 charged 119.000.
#   * 23 categories whose machine ITEM never priced. `build_entry_cost` below already argues
#     this is a gap in the pricing rather than a fact about the base -- and then charged it the
#     same as an evidence-based impossibility.
#
# What that cost in practice: `aoa3:holly_top_petals` had a blocked Modular Machinery route beat
# a Phytogenic Insolator, both at 119.000, by 0.037 of an ingredient point. A tie between two
# different failures, broken by noise.
#
# So they get separate slices, ordered by how strong the claim is, every boundary derived from
# the two anchors rather than typed in:
#
#     have 1.0 < priced [40.0, 110.0) < unpriced item 111.0
#              < blocked structure [112.0, 119.0] < unknown 120.0 < unavailable 5000.0
#
# THE UNPRICED ITEM SITS BELOW THE BLOCKED STRUCTURE ON PURPOSE, and that ordering is the one
# thing here not to swap. "This model failed to compute a number" is a weaker claim than "the
# pack says this needs a block nothing makes", so the failure has to be the more optimistic of
# the two. Reversed, a machine we merely could not price would lose to one we know is
# unbuildable.
#
# AND THE WHOLE BLOCKED SLICE STAYS BELOW `unknown`, WHICH LOOKS BACKWARDS AND IS NOT. A
# structure proven to need an unobtainable block sounds like a stronger claim than "we could
# not identify this machine at all", so it is tempting to rank it worse. DO NOT: the blockage
# signal has a history of being WRONG in ways that read exactly like a proof.
#
# The case this comment was written against is now fixed. Chisel and Unlimited Chisel Works
# variants had zero producers, because chisel recipes were dropped as non-recipes, so
# `chisel:concrete_brown:1` read unobtainable when it is trivially obtainable and any
# structure using one read as blocked on a false negative. #110 expands those tables instead
# of dropping them. Measured on the reference graph before and after: 26 of the 44
# chisel-family keys used as a multiblock part were absent from the cost table and are now
# all priced, blocked positions fell from 30,753 of 69,181 (44.45%) to 26,236 (37.92%), 8
# structures went from partly blocked to clean, and ZERO blocked positions still involve a
# chisel-family key.
#
# THE ORDERING DOES NOT MOVE ON THAT EVIDENCE, and #110 deliberately did not move it. What
# was measured is that ONE named false-negative family is gone, not that the remaining 37.92%
# is sound.
#
# #100 IS THAT AUDIT, AND IT ARGUES FOR THE ORDERING FAR MORE STRONGLY THAN CHISEL EVER DID.
# `tools/entry-census.py --blocking-keys` now names every blocking block and says why, and
# the split is lopsided: of 26,236 blocked positions, **25,109 (95.7%) are keys the pack DOES
# have a recipe for**, across 190 of the 250 distinct blocking keys. Only 1,127 positions
# (4.3%, 60 keys) are "nothing makes it". `contenttweaker:galaxy_conduit` at 6,456 positions
# has a 7x7 Extended Crafting recipe; `nuclearcraft:heat_exchanger_frame` at 1,475 is an
# ordinary crafting recipe; `biomesoplenty:flesh` at 844 is four flesh chunks, and a chunk is
# a mob drop this graph has no terminator for (#50).
#
# So the blocked slice is overwhelmingly reporting THIS MODEL'S COVERAGE, not the pack's
# content, and "the pack says this needs an unobtainable block" is a sentence the evidence
# does not support for 19 positions in every 20. Ranking blocked above `unknown` -- let alone
# at the `unavailable` wall -- would put real recipes behind a verdict that is mostly a
# statement about unpriced chains, which is the 40%-of-the-pack failure the `unknown` figure
# was chosen to avoid. The ordinal is safe BECAUSE the whole slice is bounded; the individual
# fractions inside it inherit that same unreliability and are a ranking, never a claim.
#
# WHAT WOULD ACTUALLY MOVE IT is pricing those chains, not reclassifying them. Every one of
# the 190 is a real recipe the model gave up on, so the honest route to a stronger verdict is
# to find out why and fix that -- at which point the keys left blocking are the 60, and the
# claim becomes one the tool can stand behind.
PRICED_CEILING = MACHINE_COST["buildable"] + BUILD_SPREAD
UNPRICED_MACHINE_COST = PRICED_CEILING + 1.0
BLOCKED_FLOOR = UNPRICED_MACHINE_COST + 1.0
BLOCKED_CEILING = MACHINE_COST["unknown"] - 1.0

# Used only when machine gating is off entirely (no states supplied), where every category
# gets the same figure and the value is arbitrary. NOT the cost of an unidentified machine
# -- that is MACHINE_COST["unknown"] above.
UNGATED_MACHINE_COST = 20.0

# A fluid quantity is in mB, so 1000 mB of water would otherwise look a thousand times
# dearer than one item. One normalised unit is one item OR one bucket.
#
# THIS MUST BE APPLIED TO OUTPUTS TOO. Scaling only the input side made every fluid→fluid
# hop divide the cost by 1000: `1000 mB A -> 1000 mB B` charged cost[A] for the inputs and
# then divided by an output quantity of 1000, so a chain ten hops deep priced at 1e-30.
# Every fluid in the reference pack converged to 0.0 and the cost model stopped
# discriminating between routes at all -- the greedy behaviour it exists to prevent, hidden
# behind a cost table that looked populated. Go through `_scaled_qty` for both directions.
FLUID_SCALE = 1.0 / 1000.0

# An item with no recipe: assume it can be obtained somehow.
#
# THAT ASSUMPTION IS SOMETIMES FALSE AND #136 IS WHERE IT SHOWS. A key nothing makes may be a
# thing you pick up, or it may be a PROCESSED FORM the pack simply never authored a recipe
# for -- the Sednanite Nugget has no producer at all, and 9 of them at 1.0 beat mining the
# ore at 801.0, so the shopping list named a step nobody can perform. This constant is still
# the right answer for a cobblestone, so the fix is a NARROWER set rather than a different
# number: `Graph.reachable_form` is the set with positive evidence the graph cannot explain
# the route, and `_seed` prices exactly that set at `UNSOURCED_COST`.
#
# FOUR WAYS OF REPRICING IT HAVE BEEN BUILT OR MEASURED AND REJECTED, none of them that one.
# Do not re-propose one without new evidence, and add to this list rather than rediscovering it:
#
#  * NO SEED AT ALL for an unobtainable processed form ("infinity is the honest reading").
#    243 keys go finite -> infinity on the reference graph, `abyssalcraft:nitre` and
#    `sulfur` among them, and 23,942 prices move. It does not even produce the intended
#    answer: Sednanite Ingot lands on a 20-row route through data models and chickens.
#  * FLOOR IT AT THE MATERIAL'S CHEAPEST OBTAINABLE MEMBER. Circular. The floor picks the
#    INGOT, whose price was itself earned through the nugget, so the nugget rises to exactly
#    the number the bug produced and nothing moves.
#  * INHERIT IT FROM A PRODUCED NBT VARIANT OF THE SAME KEY. A DIFFERENT RULE FROM THE ONE
#    ABOVE, not a second reason for it: that one keys on the oredict material family, this one
#    on the NBT axis. Not circular the way the member floor is, and wrong for a different
#    reason: a container's produced variants are the FILLED forms of it.
#    `thermalexpansion:reservoir:32000` is the harm. One recipe demands the bare key, both of
#    its variants are Fluid Transposer output, and the rule prices a Creative Reservoir at
#    521.0 against a 2,000 floor -- 4x understated, into a slot that really does ask for one.
#    `extratrees:drink` is the same shape at its clearest and its weakest: a Beer Mug whose 30
#    variants are all filled glasses made from an EMPTY glass variant, so the rule would price
#    "Beer Mug" at what a Mug of Apple Juice costs, and nothing demands that key directly so no
#    plan moves for it. A one-hop cycle check saves neither, because the empty container is
#    another VARIANT and not the bare key.
#
#    A FILLED CONTAINER READS AS A MADE THING FOR TWO INDEPENDENT REASONS, and it is worth
#    knowing which one is load-bearing before reaching for `transfer` as the discriminator:
#
#      1. THE FILL DIRECTION CAN NEVER BE FLAGGED. Both of `index.mark_container_transfers`'
#         signals skip any recipe with no FLUID output, and a fill outputs an ITEM -- the
#         filled container. So no `transposer_fill` recipe is ever a transfer, by construction
#         rather than by omission, which is what `real_producers` means by "filling a container
#         IS real work and stays; only the fluid direction is fake".
#      2. A FLAGGED EMPTY IS NOT FILTERED OUT FOR AN ITEM KEY. `real_producers` drops transfers
#         only when the key is a fluid, so a marked `transposer_extract` still counts as a
#         producer of the emptied container.
#
#    Reason 1 is the one that decides both cases above: `reachable_form` names the FILL variant
#    in each, and that variant's producer was never a candidate for flagging. So a rule keying
#    on `Recipe.transfer` cannot fix this -- measured on `extratrees:drink`, all 30 variants
#    have a non-transfer producer and such a rule discriminates nothing at all. Restricting the
#    rule to variants the key's own family does not make is what #170 proposes; the
#    unrestricted rule is what this entry refuses.
#
#    Measured on graph-oracle.json by the agent on #170, and recorded here rather than on that
#    branch because the branch is a draft and this list only works if it outlives the attempts.
#    The 521.0 is their number under their rule; the producer and demand counts above were
#    re-checked here. NOTE WHAT IT DOES NOT ARGUE AGAINST: #176 assigns the flat
#    `UNSOURCED_COST` and inherits nothing, so both keys sit at 2,000 today rather than at a
#    filled container's price. The hazard is specific to inheritance.
#  * FLOOR IT AT THE MATERIAL'S WORLD ORE, re-seeded into a clean relaxation. Structurally
#    sound and measured clean -- 6 keys floored, 0 lost, 0 cheaper, every control unchanged
#    -- and it still produced a WORSE plan than the bug at the time: with the nugget gone the
#    solver fell to a cyclic route whose shopping list contained the item being planned. That
#    second defect was the deeper one and #172 fixed it in `score_recipe`, which is why the
#    prediction "fix that first and this may be moot" came true: #176 then repriced the
#    nuggets with no ore floor at all, and the reported plan routes through the ore. The rule
#    stays rejected, because it is now redundant AND it keys on the wrong thing -- an ore
#    tells you nothing about a material that has none.
#
# A FIFTH FINDING, AND IT CONSTRAINS ANY REPLACEMENT RATHER THAN REJECTING ONE CANDIDATE:
# A FLOOR CANNOT BE PATCHED INTO A SETTLED TABLE. `_relax` only ever LOWERS, which `estimate`
# already states as the reason machine entry costs need a second clean pass -- so raising a
# price after relaxation leaves every consumer holding the price it banked before the raise.
# Measured: the nugget went 1.0 to 10.0 and the ingot stayed at 10.0. A raise has to be an
# INPUT to a relaxation, which is how #176 seeds `unsourced_keys` and why widening that set
# propagates for free.
#
# (This read "general rather than about ores", which was true when every rule above it was
# ore-shaped. The fourth is not.)
BASE_RAW_COST = 1.0
TRANSFER_PENALTY = 500.0   # container fill/empty is not production; never prefer it

# What a route through JEI PROSE costs: a random loot table, or a card explaining how to
# automate something. #211 and #169. `notproduction` decides which recipes these are.
#
# A PENALTY RATHER THAN A DROP OR AN INFINITY, and all three were built and measured.
# Dropping the recipe collapses `contenttweaker:imp_skin` to `BASE_RAW_COST`, because the
# annotation is its only producer and `_seed` then treats it as a leaf -- the plan stops lying
# about HOW and starts lying about HOW MUCH, which is worse because the first is visible in the
# tree and the second is not. Declining to relax through it reaches a different failure:
# 26 keys go from a finite price to infinity, five of #169's seven infusion catalysts among
# them. A finite penalty added to `base` strands 0 and keeps every output PRICED, which is what
# makes the demotion honest: the route is still there, still visible in `used_in`, no longer
# cheap, and never chosen -- `Graph.real_producers` withholds it from the solver outright.
#
# THE ORDERING IS THE CLAIM, NOT THE MAGNITUDE, exactly as for DIMENSION_COST and
# UNSOURCED_COST, and there is only one bound to state:
#
#     MACHINE_COST["unavailable"] < NON_PRODUCTION_PENALTY
#
# ABOVE the 5,000 wall, which is the highest price anything else in this file can reach, and
# the reason is what the two statements are. "You cannot have this machine" is a true statement
# about the world and a route through it is a real route with a real obstacle. A loot table or
# an automation card is not a route at all -- nothing in the game turns those inputs into that
# output -- so it has to lose to every claim the graph can actually account for, including the
# worst of them. Asserted in `tests/test_progression.py` rather than left to the reader.
#
# NOT AMORTISED, because it is added to `base`. That is load-bearing here rather than merely
# consistent with #29: a scrapbox entry yielding a stack would otherwise divide this by 64 and
# come out under a raw leaf.
NON_PRODUCTION_PENALTY = 50000.0

# What a PLACEHOLDER costs, by what it asks of the player. See `tokens.py` for the kinds.
#
# Until #105 this file never mentioned tokens at all, so every one of them fell through the
# generic leaf rule above and a locked quest chapter cost exactly what a cobblestone costs.
# Gating was a reporting concept only: `solve` badges the node "locked" and lists it under
# "locked behind progress", and nothing steered the planner away from the route. The error
# ran ONE WAY -- a gated route was always at least as cheap as the ungated one beside it --
# so gated routes were systematically preferred. Asked directly as "is the dimensional gate
# added to the cost?", and it was not.
#
# THE ORDERING IS THE CLAIM, NOT THE MAGNITUDES. Four properties, each one asserted in
# `tests/test_progression.py` rather than left to the reader:
#
#   1. GATE > LOOT > BASE_RAW_COST. Unlocking a chapter is a bigger ask than farming a boss,
#      which is a bigger ask than picking something up.
#   2. GATE > MACHINE_COST["unknown"]. A lock you cannot open yet is a worse obstacle than a
#      machine this tool merely failed to identify, so an ungated route through any machine
#      wins against a gated one.
#   3. Both < MACHINE_COST["unavailable"]. A gate is not an impossibility -- chapters unlock
#      and bosses die -- so a gated route must stay FINITE and still be chosen when it is the
#      only one there is. That is the whole difference from the 5,000 wall.
#   4. LOOT != GATE. #95 is the lesson: one shared number for two unrelated statements
#      destroys the ordering among both.
#
# HINT and METHOD deliberately stay at `BASE_RAW_COST`. Neither is a thing to obtain: a HINT
# says the recipe accepts any member of a class, so it stands in for one ordinary material,
# and a METHOD says the work happens in a machine, which `category_entry_cost` has already
# charged for. Pricing either as an obstacle would double-count.
LOOT_COST = 200.0
GATE_COST = 1000.0

# What MINING costs when the ore only generates somewhere you have never been. #112, and
# the half of #105 that could not ship then: #105 priced the pack's gate ITEMS and could
# not price a PLACE, because travelling is not a recipe.
#
# A DISTINCT NUMBER FROM GATE_COST, and #95 is the reason it has to be. Two unrelated
# statements sharing one figure destroys the ordering among both, and these are unrelated:
# a locked chapter is a lock, and there is nothing to build that opens it ahead of the
# story. A dimension is a construction project -- a rocket, a suit, fuel -- so it is
# something you can decide to go and do this afternoon. That makes it the SMALLER ask, and
# the ordering claim is therefore:
#
#     BASE_RAW_COST < LOOT_COST < DIMENSION_COST < GATE_COST < MACHINE_COST["unavailable"]
#
# Below GATE because a trip can be worked towards and a chapter cannot; above LOOT because
# an afternoon of fighting a boss you can already reach is less than an afternoon of
# building the thing that gets you somewhere new; finite because the trip is possible, and
# a gated route must still be chosen when it is the only one. Asserted in
# `tests/test_dimensions.py` rather than left to the reader, exactly as #105's four
# properties are.
#
# THE ORDERING IS THE CLAIM, NOT THE MAGNITUDE. Whether a Sedna trip is 800 afternoons or
# 8 is not knowable from the graph, and no one has measured it.
DIMENSION_COST = 800.0

# What a key the graph has PROVEN it cannot explain costs. #176.
#
# `Graph.unsourced_keys` is the set: nothing makes this exact key, and the graph
# demonstrably makes another form of it. Until now those seeded at `BASE_RAW_COST` like any
# other leaf, which is the cheapest value in the model -- so the solver actively PREFERRED
# routes through items it had already badged "no known source". #139 shipped that badge and
# deliberately did not touch the price; this is the other half, and `solve.py`'s DO-NOT
# comment deferring it pointed here.
#
# THE ORDERING IS THE CLAIM, NOT THE MAGNITUDE, exactly as for DIMENSION_COST and EMC_COST:
#
#     BASE_RAW_COST < LOOT_COST < DIMENSION_COST < GATE_COST < UNSOURCED_COST
#                   < MACHINE_COST["unavailable"]
#
# ABOVE GATE_COST because the claims are different in kind. A locked chapter is a lock with
# a key somewhere in the story; an unsourced item is one the TOOL cannot explain at all, and
# it has positive evidence rather than mere silence. Pricing it below a gate would put a
# route the graph cannot account for ahead of one it can, which is the whole defect.
# BELOW the 5,000 wall because `MACHINE_COST["unavailable"]` means "you cannot have this
# machine", and #95's lesson is that two unrelated statements must never share a figure.
#
# AND THE MAGNITUDE IS MEASURED INERT ACROSS THE BAND THE ORDERING PERMITS, which is the
# strongest evidence any constant in this file has. Measured over eight targets on the
# reference graph:
#
#   * 200 and 2,000 produce BYTE-IDENTICAL plans on every one of them.
#   * 5,000 diverges on exactly one, `fluid:nethengeic_fluid`, and is slightly WORSE there:
#     5 unsourced nodes left in the plan instead of 4, and 75 shopping rows instead of 58.
#   * Infinity is out. It strands 2,372 currently-priced keys -- the Actually Additions
#     battery family, the Vertical Digger, `abyssalcraft:transmutator` -- and drops the
#     finite table from 161,492 keys to 111,485. Every FINITE candidate strands zero.
#
# So moving this number is a one-line change with no re-derivation needed, as long as it
# stays inside `(GATE_COST, MACHINE_COST["unavailable"])`. Asserted in
# `tests/test_progression.py` rather than left to the reader.
#
# WHAT IT DOES NOT FIX, so nobody reads more into it than it earns: a field of alternatives
# that are ALL unsourced ties at any uniform price and keeps whatever order it had.
# `fluid:lifeessence` has 62 structurally identical Digital Mob Agonizer recipes, every slot
# alternative unsourced, so it names Blaze Data Model at 1.0 and at 5,000 alike. That is
# #181 and no constant can reach it.
UNSOURCED_COST = 2000.0

# Keyed by the kind rather than by the token, which is the honest granularity available. A
# per-token number would be more truthful still -- Chapter 1 and a Sedna trip are not the
# same afternoon -- and it needs a curated figure per id that nobody has measured. The kind
# already encodes what the placeholder asks of the player, so it is the finest split the
# existing data supports. See #105's open question 1.
TOKEN_COST = {LOOT: LOOT_COST, GATE: GATE_COST,
              HINT: BASE_RAW_COST, METHOD: BASE_RAW_COST}

# What a transmutation costs, for an item the ProjectE network has LEARNED and the pack gives
# an EMC value. Issue #50.
#
# BETWEEN STOCK AND A RAW LEAF, and both bounds are the claim. Above stock (0.0) and above an
# infinite generator's SOURCE_COST, because EMC is finite and fungible: it is spent, a
# collector has to earn it back, and a route that burns it is not as good as one that draws
# on a pile you already have. Below BASE_RAW_COST, because the alternative to transmuting a
# learned item is going and farming the dungeon it drops in, and the whole report behind #50
# is that a player with a working network does not do that.
#
# THE ORDERING IS THE CLAIM, NOT THE MAGNITUDE, exactly as for DIMENSION_COST. Nobody has
# measured what an afternoon of EMC is worth against an afternoon of mining, and the graph
# carries nothing that could. What is asserted, and pinned in `tests/test_emc.py`, is
#
#     0.0 (stock)  <  SOURCE_COST  <  EMC_COST  <  BASE_RAW_COST
#
# NOT SCALED BY THE ITEM'S EMC VALUE, and that was considered. A Nether Star is 139,264 EMC
# and a cobblestone is 1, so scaling looks obviously right -- and it would make the number a
# QUANTITY, which every docstring in this module says these are not. The estimate is a
# ranking, its units are already a mixture of machine entries and ingredient counts, and
# feeding a five-order-of-magnitude spread into it would let one expensive transmutation
# outrank an `unavailable` machine wall. Availability is what #50 asked for; affordability is
# open question 1 in that issue and is unanswered.
EMC_COST = 0.5

# What an item the AE2 network can AUTOCRAFT costs: one request. Issue #193.
#
# `Solver.expand` has terminated on a craftable since the feature shipped -- status `have`,
# note "AE2 can autocraft", and deliberately no contribution to `used_from_stock`, which
# `tests/fixtures/plan/plan-in-stock.json` records -- and until now the cost model was never
# told. So the ranker priced a route through a craftable at its FULL SUBTREE COST while the
# solver was going to stop dead at it, and the error ran one way: such routes lost to worse
# ones. The cost model and the plan were ranking two different sets of terminals.
#
# BETWEEN AN INFINITE SOURCE AND A RAW LEAF, and both bounds are the claim:
#
#     0.0 (stock) < SOURCE_COST < CRAFTABLE_COST < EMC_COST < BASE_RAW_COST
#
# Above SOURCE_COST because a generator you own is genuinely free and this is not: the
# network spends real materials to fill the request, and they have to be replaced. Below
# BASE_RAW_COST because the alternative to typing one request is going and gathering the
# thing, which is what a raw leaf prices -- the same argument EMC_COST makes for a learned
# transmutation, and the reason a craftable must not simply be seeded at 0.0 like stock: you
# do not hold it.
#
# BELOW EMC_COST, AND THAT ORDERING IS READ OFF `expand` RATHER THAN GUESSED. A craftable
# comes back `have`, the same status as a stack in the network; a learned transmutation gets
# its own `emc` status and a note naming the balance it will spend. The display already
# treats one as nearer to owning the item than the other. A DISTINCT figure from EMC_COST
# regardless, per #95: two unrelated statements sharing one number destroys the ordering
# among both.
#
# IT DOES NOT DECIDE WHICH TERMINAL WINS FOR A KEY THAT IS BOTH, and that is worth knowing
# before anyone moves it. `_seed` reproduces `expand`'s cascade by ORDER and by the
# under-a-raw-leaf guard, so a key that is learned AND autocraftable keeps EMC_COST whatever
# this number is, exactly as `expand` returns at the EMC branch first. The magnitude only
# separates two DIFFERENT keys competing for one slot, and nobody has measured that trade.
#
# NO RELAXATION CAN UNDERCUT IT, which is what makes seeding a terminal sound at all.
# `_relax` prices an output at `base + ingredients / qty` with `base` undivided, and the
# cheapest `base` there is is `MACHINE_COST["have"]` = 1.0 = `BASE_RAW_COST`
# (`AMachineCostsAtLeastAsMuchAsMiningTest` pins that equality), so anything seeded below a
# raw leaf is a floor rather than a suggestion. That is the same property `_settle_reshaped`
# leans on, read in the other direction.
CRAFTABLE_COST = 0.25

# Bumped whenever THE CODE WOULD PRICE THE SAME INPUTS DIFFERENTLY, and folded into
# `fingerprint`. The cache is keyed on the inputs (graph, stock, machine states, tuning
# constants), so any such change moves none of them, and without this a machine holding
# `.cost-cache.json` would keep serving the old prices forever -- the one failure this cache
# must never have, and one that looks like "the fix did not work" rather than like a stale
# cache.
#
# "THE PER-UNIT FORMULA IN `estimate`" IS WHAT THIS SAID, AND IT IS TOO NARROW. That wording
# names one way to earn a bump and reads as the only one, so a change that moves prices without
# touching any arithmetic looks exempt. #136 is the case: it added 14 keys to the MEMBERSHIP of
# `Graph.unsourced_keys`, which `_seed` prices at `UNSOURCED_COST`. No formula, no constant, and
# 10,810 of 161,531 prices moved. Measured before the bump, on one graph and one scenario: the
# two trees produced the SAME fingerprint, and a cache written by the earlier one was served to
# the later one in 0.1s with the fixed keys back at `BASE_RAW_COST`. The fix came back silently.
#
# So the test is the OUTPUT, not the mechanism. A different table for the same inputs earns a
# bump, whether it came from the arithmetic, from a seed rule, from the membership of a set
# derived off the graph, or from a relaxation ordering.
#
# AND DECLINING IS A POSITIVE CLAIM THAT PRICES ARE BIT-IDENTICAL, which needs a measurement
# like any other claim in this file. #175 declined, and was right, and its argument is the
# worked example of doing that properly:
#
#   #175 ADDED THE CATALYST TERM TO `_relax` AND DELIBERATELY DID NOT BUMP THIS. That looks like
#   an omission, so here is the argument, and it rests on a measurement rather than on taste.
#
#   The bump exists to stop a warm `.cost-cache.json` serving prices computed by different
#   arithmetic. The retained-input term cannot produce a different price on any graph predating
#   the `p` field: with every slot at the default chance, `retained` is 0.0 and the ingredient
#   term is `c * 1.0`, and `x + 0.0 == x` and `x * 1.0 == x` are exact in IEEE 754 rather than
#   approximately true. Measured, not asserted: `estimate` over a 40-recipe graph exercising
#   batch outputs, fluids, an oredict slot, a transfer and three machine bands produces the
#   byte-identical price digest d89f2eb4 before and after the change.
#
#   And a graph that DOES carry `p` arrives as a new `graph.json`, whose size and mtime are
#   already hashed below, so that cache is invalidated by the file rather than by this number.
#   There is no input for which a stale cache could serve a wrong price, which is the only thing
#   this counter is for.
#
#   SO THE RULE IS NOT WEAKENED, IT IS MET: bump this the moment the RETENTION ARITHMETIC
#   changes (a different amortisation, a threshold, a non-linear scaling of a fractional
#   chance), because then two graphs with identical files really would price differently.
#   Adding a field that is absent everywhere is not that.
#
# `tests/test_plan_fixtures.py` pins this number against the fixtures, so a bump costs an oracle
# regeneration and must ride with one. #136 learned that the hard way: the guard failed first.
#
# A RULE IN CODE THAT THE FINGERPRINT CANNOT SEE IS ANOTHER SOURCE, and #211 is the case.
# `notproduction.annotation_markers` decides whether a recipe is a JEI documentation card from
# three conditions in code. Two of its inputs ARE hashed -- the resolved token map, and
# `tokens.LOOT_TABLE_CATEGORIES` -- which is what makes it easy to believe the rule is covered.
# It is not: loosen one of the conditions and every price that route touched moves while every
# hashed input stays exactly where it was. This is the "output, not mechanism" test above,
# applied to a source the list does not name.
#
# AND A NEW CONSTANT ACCIDENTALLY COVERING YOU IS NOT A REASON TO DECLINE. #211 added
# `NON_PRODUCTION_PENALTY` to the hashed tuple, so a warm cache written before it is invalidated
# by that alone -- measured, the fingerprint differs. That is not the same claim as #175's:
# #175 measured that the PRICES were bit-identical, which is the positive claim this file asks
# for. "A constant I happened to add is in the hash" is instead exactly the reasoning
# `fingerprint` names as going stale, and the next edit to the rule will add no constant and
# inherit no such accident.
#
# #193 IS ANOTHER INSTANCE OF #136'S RULE, recorded because the instances are what make the rule
# legible rather than because it needs a new one. It changed which keys `_seed` calls a leaf and
# added `Graph.produced_in_name_only` to what the seed prices at `UNSOURCED_COST`, which is "the
# membership of a set derived off the graph" as named above. No arithmetic and no pre-existing
# constant moved; 10,884 of 161,531 prices did, 553 of them from infinity to finite.
#
# AND IT DECLINES #211'S ACCIDENT EXPLICITLY. `CRAFTABLE_COST` is new and lands in the hashed
# tuple, so a warm cache written before #193 is invalidated by that alone -- which is exactly the
# reasoning the paragraph above refuses. The bump rests on the measured price movement instead.
FORMULA_VERSION = 15

# Bellman-Ford needs one pass per edge in the longest useful path. MeatballCraft's chemistry
# runs 10+ hops deep (borax -> ... -> molten sugar), so 6 passes left the deep end of every
# chain unpriced. Measured on the reference pack: the last item gets a price at pass 12 and
# nothing new appears after 20, while relaxation over cycles keeps making sub-percent
# improvements forever -- hence a ceiling AND an early exit, not just one of them.
PASSES = 20
SETTLED_FRACTION = 0.002   # stop when a pass improves under 0.2% of recipes' outputs


class CostTable(dict):
    """Item costs, plus the per-category machine entry costs they were computed with.

    A plain `dict` everywhere it is read, so the solver keeps indexing it by item key and
    nothing else has to know this type exists. The entry costs ride ALONGSIDE rather than in
    a second argument every caller must remember to thread, because `estimate` and
    `recipe_cost` deriving that number separately is exactly how the relaxation and the
    ranking came to disagree about which alternative a slot used -- see
    `_cheapest_alternative`, which exists to fix the same class of divergence. Carried on the
    table, the price the ranker charges CANNOT drift from the price the relaxation used.
    """

    def __init__(self, *args, **kwargs):
        entry = kwargs.pop("machine_entry", None)
        dict.__init__(self, *args, **kwargs)
        self.machine_entry = dict(entry or {})


def build_entry_cost(build_cost):
    """Entry cost for a machine you must build, ordered by what building it costs.

    Bounded into `[MACHINE_COST["buildable"], PRICED_CEILING)` and monotonic in `build_cost`;
    see the BUILD_SPREAD comment for why the bound is not optional.

    An UNREACHABLE machine item (inf, which 23 buildable categories on the reference pack
    have: a producer exists but its own inputs never price) charges UNPRICED_MACHINE_COST. Not
    `unavailable`: the state was decided by `machines._candidate_verdict` on evidence, and a
    price this model failed to compute is a gap in the pricing, not a fact about the base.
    Charging 5,000 here would override an evidence-based verdict with a numerical failure. Nor
    the top of the reserved region, which is #95: that is where a structure proven unbuildable
    goes, and this is only a number we could not work out.
    """
    floor = MACHINE_COST["buildable"]
    if build_cost is None or math.isinf(build_cost) or math.isnan(build_cost):
        return UNPRICED_MACHINE_COST
    b = max(0.0, build_cost)
    span = math.log1p(b / BUILD_SCALE)
    return floor + BUILD_SPREAD * (span / (span + BUILD_KNEE))


def blocked_entry_cost(fraction):
    """Entry cost for a multiblock the pack says needs a block nothing in the graph makes.

    `fraction` is `multiblocks.blocked_fraction`: the share of block POSITIONS with no
    obtainable candidate. Mapped linearly onto `[BLOCKED_FLOOR, BLOCKED_CEILING]`, so the whole
    slice stays above every priced machine and above an unpriced machine item, while the 117
    categories inside it stop being one number.

    LINEAR, not the logarithmic curve `build_entry_cost` uses, because this is not a cost. A
    fraction is already bounded and already uniform over its range; there is no long tail to
    compress and nothing to calibrate against, so a curve here would be decoration implying a
    precision the ordinal does not have.

    An out-of-range or missing fraction clamps rather than raises: this decides a ranking, and
    a machine vanishing from the band because its structure parsed oddly would be a worse
    failure than one ranked at the wrong end of a slice every member of which is unbuildable.
    """
    if fraction is None or math.isnan(fraction):
        return BLOCKED_CEILING
    f = min(1.0, max(0.0, fraction))
    return BLOCKED_FLOOR + (BLOCKED_CEILING - BLOCKED_FLOOR) * f


def regions():
    """`[(label, lo, hi)]`, the slices `machine_entry_costs` can put a category in.

    HERE RATHER THAN IN THE AUDIT THAT PRINTS IT, because the boundaries are derived from the
    two anchors above and a second copy of that derivation is a second thing to forget when
    one of them moves. `tools/entry-census.py` re-exports these and
    `tools/make-java-fixtures.py` censuses the band with them; both used to be answerable
    only by the census, so nothing else could tell whether a price had landed where the
    model intended.

    A function rather than a constant because the bounds read module constants that a tuning
    run legitimately monkeypatches (`tools/cost-probe.py` sweeps `BASE_RAW_COST`), and a
    tuple built at import would answer for the value that was current then.
    """
    return [("priced", MACHINE_COST["buildable"], PRICED_CEILING),
            ("unpriced item", UNPRICED_MACHINE_COST, UNPRICED_MACHINE_COST),
            ("blocked structure", BLOCKED_FLOOR, BLOCKED_CEILING)]


def region_of(value):
    """Which slice a machine entry cost landed in, or that it landed outside the band.

    "OUTSIDE THE BAND" is a real answer and not an error: a price falling through every
    slice is exactly the defect the census exists to surface, and returning None or raising
    would turn it into either false reassurance or a crash in an audit.
    """
    for label, lo, hi in regions():
        if lo - 1e-9 <= value <= hi + 1e-9:
            return label
    return "OUTSIDE THE BAND"


def machine_entry_costs(machine_items, cost, multiblocks=None):
    """{category: entry cost} for the categories whose machine has to be built.

    `machine_items` is `{category: (candidate key, ...)}` from `machines.build_targets`. The
    CHEAPEST candidate sets the price: several blocks can open one category (smelting is not
    only the furnace), and a player building one would build the cheapest that works, so
    pricing the first listed would charge for a machine nobody would choose.

    `multiblocks` is `graph.multiblocks`. A candidate that is a Modular Machinery controller
    is charged its recipe PLUS the structure it stands for, because the recipe alone is a
    blueprint and a blank controller while the machine is up to 8,813 placed blocks (#93).
    The two are added rather than one replacing the other: you need the controller AND the
    structure, and the controller is not among the machinery file's own parts.

    THREE OUTCOMES PER CANDIDATE, not one number that might be infinite (#95): a price, a
    structure the pack proves unbuildable, or a machine item this model could not price. They
    land in three ordered regions, and which one a candidate reaches is decided here because
    this is the only place that has both the structure and the item's price.
    """
    by_controller = {}
    for entry in (multiblocks or {}).values():
        by_controller[entry.get("controller")] = entry
    out = {}
    for category, keys in (machine_items or {}).items():
        # THE MINIMUM IS TAKEN OVER ENTRY COSTS, NOT OVER RAW BUILD COSTS, because since #95 two
        # candidates for one category can fail in different ways and a raw `inf` no longer says
        # which. For the all-priced case this is the same answer -- `build_entry_cost` is
        # monotonic, so the cheapest raw cost is still the cheapest entry cost -- and where they
        # differ it is the case the old form could not express at all.
        best = math.inf
        for key in keys:
            c = cost.get(key, math.inf)
            structure = by_controller.get(key)
            if math.isinf(c):
                priced = UNPRICED_MACHINE_COST
            elif structure is None:
                priced = build_entry_cost(c)
            else:
                placed = multiblocks_mod.structure_cost(structure, cost)
                priced = (blocked_entry_cost(
                              multiblocks_mod.blocked_fraction(structure, cost))
                          if math.isinf(placed) else build_entry_cost(c + placed))
            if priced < best:
                best = priced
        out[category] = best if best < math.inf else UNPRICED_MACHINE_COST
    return out


def category_entry_cost(category, machine_states=None, machine_entry=None):
    """What running a recipe in `category` costs before any ingredient is counted.

    ONE definition, read by both the relaxation in `estimate` and the ranking in
    `recipe_cost`. Do NOT inline the MACHINE_COST lookup into either of them again: they held
    separate copies, so a change to how a machine is priced silently applied to one and not
    the other, and the symptom is a solver that expands a route the ranker did not price.
    """
    if machine_entry and category in machine_entry:
        return machine_entry[category]
    state = (machine_states or {}).get(category)
    return (MACHINE_COST.get(state[0], UNGATED_MACHINE_COST) if state
            else UNGATED_MACHINE_COST)


def _scaled_qty(key, qty):
    """Quantity in normalised units: 1 item, or 1 bucket of fluid."""
    q = max(qty, 1) * (FLUID_SCALE if key.startswith(FLUID_PREFIX) else 1.0)
    # A sub-millibucket output would divide by ~0 and manufacture a free resource.
    return max(q, FLUID_SCALE)


def input_cost(cost, key, qty, ore_members):
    """Cheapest cost of satisfying one ingredient slot.

    Public because the solver needs it too: `Solver.slot_cost` breaks its alternative and
    oredict-member ties on exactly this number, and a second implementation over there
    would be a second place for the normalisation (oredict members, fluid scale) to drift.
    """
    if key.startswith("ore:"):
        members = ore_members.get(key[4:]) or ()
        best = math.inf
        for m in members:
            c = cost.get(m, math.inf)
            if c < best:
                best = c
        if math.isinf(best):
            best = cost.get(key, BASE_RAW_COST)
    else:
        best = cost.get(key, math.inf)
    if math.isinf(best):
        return math.inf
    return best * _scaled_qty(key, qty)


def _cheapest_alternative(cost, ingredient, ore_members):
    """Which of an input slot's alternatives the ranker assumes you would use.

    One rule, shared by `estimate` and `recipe_cost`, which used to hold two: `estimate`
    compared alternatives at qty 1 and `recipe_cost` at the slot's real qty. Those happen
    to agree, because `_scaled_qty` is linear in qty and the item/fluid ratio is therefore
    the same at any quantity -- but they agreed by arithmetic accident, not by design, and
    "the relaxation priced one alternative and the ranking priced another" is a bug nobody
    would find by reading either function alone. Priced at the real qty, which is the one
    that does not depend on that accident.
    """
    alts = ingredient.alternatives
    if len(alts) == 1:
        return alts[0]
    return min(alts, key=lambda a: input_cost(cost, a, ingredient.qty, ore_members))


def estimate(graph, have=None, machine_states=None, passes=PASSES, free_sources=None,
             machine_items=None, token_kinds=None, dimension_gates=None,
             emc_available=None, craftables=None, raw=None):
    """{item key: estimated cost}. Lower is easier to get.

    `craftables` and `raw` are PER-INVENTORY, on the same footing as `have`, which is why
    they are arguments here rather than anything read off the graph. Both terminate a branch
    in `Solver.expand` and until #193 neither reached a price. `fingerprint` covers them for
    the same reason it covers `have`: a table computed for one player's network served to
    another is a wrong answer that looks like a cache hit.

    With `machine_items` (`{category: (machine item key, ...)}` from
    `machines.build_targets`) this runs the relaxation TWICE, and the second run is issue
    #86's fix. A buildable machine's entry cost is what building it costs, which is itself a
    number this function computes, so it cannot be known before the first run.

    TWO PASSES RATHER THAN RECOMPUTING ENTRY COSTS INSIDE THE LOOP, deliberately. The
    relaxation only ever LOWERS a cost, so an entry price that rises between passes -- which
    is exactly what happens when a machine's real cost replaces the optimistic flat 40 --
    never propagates: the cheap prices computed in pass 1 stick, and the result silently
    depends on pass order. Seeding a second clean relaxation with entry costs derived from
    the first is deterministic and says what it does.

    Returns a `CostTable` carrying those entry costs, so `recipe_cost` charges the same
    machine price this relaxation used instead of re-deriving a flat one.
    """
    # BEFORE THE SEED, because `_seed`'s leaf rule reads `graph.real_output` and `_relax` reads
    # the flag on every recipe. Memoised on the graph, so the solver asking the same question
    # after this does no work. See `notproduction` for what it decides and why it is derived
    # here rather than stored in the graph.
    #
    # `graph.by_output` IS WHAT THIS SAID, AND #193 MADE IT FALSE: the seed reads the predicate
    # now, not the raw index. The ordering requirement is unchanged and is in fact stronger,
    # because `real_output` and `produced_in_name_only` both consult the transfer flag this call
    # sets, and `produced_in_name_only` is MEMOISED -- compute it before the marking and the
    # cached set answers for a graph that had not been marked yet.
    graph.mark_non_production(token_kinds)
    seed = _seed(graph, have, free_sources, token_kinds, dimension_gates, emc_available,
                 craftables, raw)
    cost = _settle_reshaped(graph, _relax(graph, dict(seed), passes, machine_states, None),
                            passes, machine_states, None)
    if not machine_items:
        return CostTable(cost)
    entry = machine_entry_costs(machine_items, cost, getattr(graph, "multiblocks", None))
    return CostTable(
        _settle_reshaped(graph, _relax(graph, dict(seed), passes, machine_states, entry),
                         passes, machine_states, entry),
        machine_entry=entry)


def _settle_reshaped(graph, cost, passes, machine_states, machine_entry):
    """Give back the leaf price to keys only a reshaping can make, when nothing can.

    RUNS AFTER RELAXATION BECAUSE IT CANNOT BE DECIDED BEFORE IT. `graph.reshaped_only` is
    structural -- every producer is one arm of an expanded chisel table -- but whether the
    group has any way IN is not: the anchor may have a producer that is itself unreachable,
    two tables deep. #110 tried to answer it at build time from "does some member have a
    producer" and left 168 keys unreachable that had been finite, `bewitchment:coquina_shell`
    and `contenttweaker:stone_of_life_essence` among them.

    So the rule is stated where the answer exists. A reshaped-only key that relaxation could
    not reach means the graph knows how to convert this material and no way to obtain any of
    it, which is exactly what it knew before the table was expanded -- and back then the key
    was a leaf at `BASE_RAW_COST`. Restore that and relax again so the price propagates to
    whatever consumes it.

    THIS CANNOT REINTRODUCE #110's UNDERCUT. It only ever fires on a key relaxation left at
    infinity, so a variant with any real route keeps the price that route earned, one chisel
    above its anchor. The keys it does fire on were priced `BASE_RAW_COST` before #110 too,
    so the floor it restores is a floor that already shipped.

    `graph.reshaped_only` IS A GENUINE SET (a set comprehension in `model.py`), so the order
    of the loop below VARIES BETWEEN PROCESSES with CPython's per-process string hashing.
    That is safe HERE and only here, because every iteration assigns the same constant to a
    distinct key: the result is order-independent by construction, not by luck of the seed.
    DO NOT copy this pattern into a loop whose outcome depends on which element it sees
    first -- `tests/fixtures/plan/*.json` freezes whole solver results for the Java port
    (#19), so an order-dependent read of a set would make those fixtures flip between runs,
    and a flaky golden fixture is one people learn to regenerate instead of read. Sort it
    first if the loop ever stops being idempotent.
    """
    stranded = [key for key in graph.reshaped_only
                if math.isinf(cost.get(key, math.inf))]
    if not stranded:
        return cost
    for key in stranded:
        cost[key] = BASE_RAW_COST
    return _relax(graph, cost, passes, machine_states, machine_entry)


def _seed(graph, have, free_sources, token_kinds=None, dimension_gates=None,
          emc_available=None, craftables=None, raw=None):
    """Starting costs, before any recipe is considered. Shared by both relaxation passes.

    THE ORDER OF THESE LOOPS IS `Solver.expand`'s CASCADE, not a sequence of independent
    rules. Where two of them could answer for one key, whichever the solver returns at first
    has to be the one that sets the price, or the plan stops at a node the ranking priced as
    something else. The `min`/`max`/guard on each loop is what implements that, and every one
    of them says which branch of `expand` it is standing in for.
    """
    from .generators import SOURCE_COST

    cost = {}
    # Anything in stock is free at the margin; that is what makes the solver prefer using
    # what you already own without needing a separate rule for it.
    for key, qty in (have or {}).items():
        if qty:
            cost[key] = 0.0

    # An infinite generator's output is near-free but NOT free. At zero the ranker cannot
    # see quantity and will happily plan a swimming pool of water; at SOURCE_COST it still
    # beats every crafted route while preferring less over more. See generators.py.
    for key in free_sources or ():
        cost[key] = min(cost.get(key, math.inf), SOURCE_COST)

    # Seed every produced key as unreachable, and every leaf (no recipe) as cheap-ish.
    #
    # A LEAF BEHIND A DIMENSION IS NOT CHEAP, AND THIS RUNS FIRST, so the surcharge has to
    # be applied here too rather than only in the world-ore loop below. "No recipe, so
    # assume you can go and get one" is precisely the assumption #112 exists to qualify:
    # you cannot go and get it, you have never been where it is. Left to the loop below,
    # `min` would already be holding BASE_RAW_COST from this pass and would keep it -- the
    # gate would compute, appear in the plan's note, and change no price at all.
    #
    # LEAF-NESS COMES FROM `Graph.real_output` AND NOT FROM `by_output`, WHICH IS #193. This
    # loop used to test `alt not in graph.by_output`, the one place in the codebase that
    # answered "is this key produced" without excluding container empties -- so a fluid whose
    # only route is emptying a can was not a leaf here and got no seed, while `_relax` below
    # applied the exclusion and refused to price it from that recipe. Nothing seeded it and
    # nothing relaxed it; the cost model held infinity while `Solver.expand` called it raw and
    # put it on the shopping list. 120 fluids on the reference graph, `fluid:liquid_uu_matter`
    # and `fluid:sewage` among them, nearly all of them produced only by a Forestry Squeezer.
    # Every parent's price inherited the cost model's version, so cost said impossible while
    # the plan said go and buy it.
    #
    # THEY DO NOT END AT THE LEAF PRICE THIS LOOP GIVES THEM. The rule below that raises
    # `graph.produced_in_name_only` to UNSOURCED_COST collects exactly this population, because
    # a key whose only producers are container empties is one the graph has PROVEN it cannot
    # explain. This loop makes them finite; that one says what finite number.
    #
    # AND NOT ONE OF THE 120 CARRIES THE `unsourced` BADGE, measured, which is worth recording
    # because #193 states the opposite. `reachable_form` returns None for every one of them: a
    # fluid has no meta sibling, no NBT variant and no `<form><Material>` group, so there is no
    # other form to name and #139's mark correctly stays off. The plan said "go and buy this"
    # with no annotation at all, which is why nothing on screen hinted at the disagreement.
    #
    # RESOLVED ONCE INTO A SET RATHER THAN ASKED PER OCCURRENCE. The loop below reaches an
    # already-priced key by `alt not in cost` and a PRODUCED one every single time it appears
    # as an ingredient, which on the reference graph is millions of visits against 51,486
    # distinct output keys. Asked per occurrence the predicate cost 23s of the seed; asked once
    # per key it is the same dict lookup the old `by_output` test was.
    #
    # THE PREDICATE IS READ DIRECTLY HERE, not derived as the complement of
    # `graph.produced_in_name_only` below. The two are the same answer by construction and the
    # complement is one line shorter, and a reader then has to hold the identity in their head
    # to see what this loop tests. One more pass over 51,486 keys costs nothing beside the
    # relaxation. Java does the same, in the same two places, for the same reason.
    produced = frozenset(key for key in graph.by_output if graph.real_output(key))
    gates = dimension_gates or {}
    for r in graph.recipes:
        for ing in r.inputs:
            for alt in ing.alternatives:
                if alt not in cost and alt not in produced:
                    cost[alt] = BASE_RAW_COST + (DIMENSION_COST if alt in gates else 0.0)

    # AND EVERY WORLD ORE, WHETHER OR NOT SOMETHING PRODUCES IT. The loop above prices a
    # leaf as obtainable only when NO recipe outputs it, which quietly assumes the only way
    # to get a thing is to make it. That is false for the one class of key the graph can
    # positively identify as obtainable another way: `world_ores` is the pack's own `ore*`
    # oredict registration, meaning a block you find in the ground and hit.
    #
    # `contenttweaker:sednanite_ore` is the case. It is registered `oreSednanite`, and two
    # Plasmatic Condenser recipes also emit it, each wanting 160,000 mB of Dense Plasma. So
    # it counted as produced, both routes priced at infinity, and an ore you MINE ended up
    # unreachable -- which then made every honest route to Sednanite Ingot invisible and
    # sent the planner down a nugget ladder instead. See #106.
    #
    # Measured on the reference graph: 19 of 286 world ores priced at infinity, 14 of them
    # because a recipe claimed to produce them, and a further 68 priced above a raw leaf.
    #
    # `min`, so this only ever LOWERS a price and can never overrule stock (0.0) or an
    # infinite generator (SOURCE_COST). Mining is a ceiling on what an ore can cost, not a
    # claim that mining is the best route: a genuinely cheaper crafted route still wins,
    # because `_relax` goes on to lower it further.
    #
    # AND MINING COSTS WHAT THE TRIP COSTS, which is #112. The line above says "you go and
    # get this" and charges the same for a cobblestone and for an ore that only generates
    # on Sedna, a planet the reference save has never been to. `dimension_gates` raises the
    # FLOOR for exactly those keys -- see `dimensions.gates` for how they are identified --
    # and raises nothing else.
    #
    # RAISING A FLOOR IS WHY A WRONG ENTRY HERE IS SURVIVABLE. `min` is still `min`, so a
    # gated ore with any crafted route keeps that route's price; all this can do is stop
    # MINING from being the cheap answer. Measured on the reference graph, all 8 gated ores
    # have between 1 and 6 producers, so the worst a misclassification does is decline to
    # discount an ore that had another way in anyway. The failure that would matter is a
    # terrestrial ore with NO producer wrongly declared exclusive to a planet, and
    # `tests/test_dimensions.py` asserts the reference set contains none.
    for key in graph.world_ores:
        floor = BASE_RAW_COST + (DIMENSION_COST if key in gates else 0.0)
        cost[key] = min(cost.get(key, math.inf), floor)

    # AND EVERY KEY THE TRANSMUTATION NETWORK CAN MAKE, for the same reason and by the same
    # mechanism as the world ores above: it counts as produced, so the leaf rule never sees
    # it, and its recipes may all price at infinity while the player can simply make one.
    # `erebus:materials` is the reported case -- its only "recipe" is a pseudo-item saying
    # it drops in a dungeon, so the solver dead-ends there while the network already makes it.
    #
    # `min`, so this only ever LOWERS a price: stock (0.0) and an infinite generator
    # (SOURCE_COST) both still win, and a genuinely cheaper crafted route still wins after
    # `_relax`. It is a ceiling on what a learned item can cost, not a claim that transmuting
    # is the best route.
    #
    # THE MEMBERSHIP TEST IS DONE BY THE CALLER, and that is the safety property. What
    # arrives here is already "learned AND carrying a positive EMC value" -- see
    # `projecte.available`. An item the pack has DISABLED has an EMC of 0, so it never
    # reaches this loop, and #50's stated worst case (asserting a route the pack blocked)
    # cannot happen here even if the knowledge file is wrong.
    for key in emc_available or ():
        cost[key] = min(cost.get(key, math.inf), EMC_COST)

    # THE KEYS THE GRAPH HAS PROVEN IT CANNOT EXPLAIN, which is the second seed that RAISES
    # rather than lowers. See UNSOURCED_COST. Until #176 these were `BASE_RAW_COST` like any
    # other leaf -- the CHEAPEST value in the model -- so the solver preferred a route through
    # an item it had already badged "no known source" over any route it could account for.
    # Measured over the golden fixture set, this drives unsourced plan nodes from 57 to 3.
    #
    # IT INSERTS AS WELL AS RAISES, FOR 39 KEYS, and that is a second effect rather than a
    # detail of the first. The leaf rule above walks recipe INPUTS, so a key nothing consumes
    # is never seeded and prices at infinity; `unsourced_keys` is built over `live_keys`,
    # which is wider by exactly those 39. For them `cost.get(key, BASE_RAW_COST)` returns the
    # default and this line CREATES the entry, taking them from infinite to 2,000. That is
    # deliberate and argued in `Graph.unsourced_keys` -- they reach no plan but they do reach
    # `/api/sweep` and `/api/cost`, and pricing a key one way in the table and another way in
    # the sweep is the drift #178 removed. Downstream it is visible: 18 machine categories
    # whose cheapest build candidate is one of the 39 move off UNPRICED_MACHINE_COST (111.0)
    # onto `build_entry_cost(2000.0)` = 95.773 in `tools/entry-census.py`.
    #
    # SO DO NOT "NEUTRALISE" THIS LOOP BY SETTING UNSOURCED_COST LOW TO GET A BASELINE. The
    # write happens regardless of the value, so a low arm still moves those 39 and silently
    # measures the wrong thing -- a control that performs the effect it controls for. Pre-#176
    # behaviour is the loop over an EMPTY set: `graph._unsourced_keys = frozenset()`.
    #
    # SAME GUARD AS THE TOKEN LOOP BELOW, and for the same reason: anything already priced
    # under a raw leaf is stock, an infinite generator or a learned EMC item, and every one
    # of those is a stronger claim about THIS world than a structural inference is. If you
    # are holding one, the graph's inability to explain where it came from is irrelevant.
    #
    # `max`, NOT ASSIGNMENT, AND IT IS DEFENSIVE RATHER THAN LOAD-BEARING TODAY. Measured on
    # the reference graph, all 47,674 of these keys hold exactly BASE_RAW_COST when this loop
    # runs, so `max` and a bare assignment produce identical tables -- the count of keys where
    # they differ is 0. An earlier version of this comment justified `max` by saying a
    # dimension-gated leaf "would be lowered by a bare assignment"; that is backwards. Such a
    # leaf sits at BASE_RAW_COST + DIMENSION_COST = 801, which is BELOW UNSOURCED_COST, so an
    # assignment would RAISE it exactly as `max` does.
    #
    # KEEP `max` ANYWAY, and this is the real reason: it is what makes the loop correct for
    # any ordering of the constants rather than only for the current one. The seed rules above
    # can leave at most BASE_RAW_COST + DIMENSION_COST here, and nothing enforces that that
    # stays under UNSOURCED_COST -- `test_progression` pins UNSOURCED_COST between GATE_COST
    # and the `unavailable` wall, and DIMENSION_COST is free to move. A bare assignment would
    # silently start LOWERING gated leaves the day DIMENSION_COST passes 2,000, and the symptom
    # would be a cheaper route through a planet you have never visited.
    #
    # BEFORE THE TOKENS, so a token wins. A placeholder is already an instruction with its
    # own price for what the player must go and DO, and `Solver.expand` returns at the token
    # branch before it ever reaches the unsourced mark -- so the price has to agree with the
    # display about which of the two answers a reader gets.
    #
    # TWO SETS, ONE RULE, AND #193 IS THE SECOND OF THEM. A key every one of whose producers
    # is a container empty is one the graph has proven it cannot explain, on exactly the
    # positive evidence UNSOURCED_COST exists for -- see `Graph.produced_in_name_only`, whose
    # docstring carries the measurement and the reason the two sets cannot be merged into one.
    # They are disjoint by construction (`unsourced_keys` needs `by_output` empty, this needs it
    # non-empty), so which one is walked first cannot matter, and both are genuine `frozenset`s
    # whose iteration order varies between processes. That is safe HERE for the same reason
    # `_settle_reshaped` gives: every iteration writes the same constant to a distinct key, so
    # the result is order-independent by construction rather than by luck of the hash seed.
    #
    # NOT `BASE_RAW_COST`, WHICH IS THE OTHER READING AND WAS BUILT AND MEASURED. A raw leaf is
    # the arithmetic that agrees with `expand`'s `raw` verdict most literally -- and it is the
    # CHEAPEST value in the model, so it makes a route through a fluid the tool cannot source
    # more attractive than any route it can account for. That is verbatim #176's defect, in the
    # one population #176's set cannot reach, and #176's argument decides it: what #193 reported
    # is an INFINITY, finiteness is what fixes it, and the cheapest finite number is not owed.
    #
    # THE ORDERING IS THE CLAIM, NOT THE MAGNITUDE, exactly as for UNSOURCED_COST itself, and
    # the magnitude measures nearly inert on the one target that could be checked end to end.
    # `fluid:nethengeic_fluid` plans 141 nodes and 41 shopping rows at BASE_RAW_COST against 152
    # and 43 at UNSOURCED_COST, and BOTH shopping-list `fluid:liquid_uu_matter` and `fluid:meat`
    # because on that target there is no alternative to prefer. The ordering is what matters
    # where there IS one.
    for population in (graph.unsourced_keys, graph.produced_in_name_only):
        for key in population:
            if cost.get(key, BASE_RAW_COST) < BASE_RAW_COST:
                continue
            cost[key] = max(cost.get(key, BASE_RAW_COST), UNSOURCED_COST)

    # And LAST, the placeholders, because this is the one seed that RAISES a price. Every
    # rule above answers "how cheaply can this be had"; a token answers "what does the
    # player have to go and do", and the generic leaf rule has already given it 1.0.
    #
    # Skipped when something already priced it below a raw leaf: that means stock or an
    # infinite generator, and either is a stronger claim about this world than a curated
    # list is. Nonsense for a placeholder in practice, and free to honour.
    for key, kind in (token_kinds or {}).items():
        if cost.get(key, BASE_RAW_COST) < BASE_RAW_COST:
            continue
        cost[key] = TOKEN_COST.get(kind, BASE_RAW_COST)

    # AND LAST OF ALL, THE TWO TERMINALS THE PLAYER DECLARES. #193. Both stop `Solver.expand`
    # dead and until now neither changed a price, so the cost model ranked routes over one set
    # of terminals while the solver planned with another.
    #
    # AFTER EVERY OTHER RULE BECAUSE THAT IS WHERE `expand` CHECKS THEM: the `raw`/`craftables`
    # branch sits above the cycle check, above the world-ore stop, above the token branch and
    # above the unsourced mark, so each of those has to lose here. A `raw` key that is also a
    # gated world ore drops from 801 to a raw leaf, and that is the right way round -- the
    # player saying "I will get this myself" is a statement about THIS world, which outranks
    # the graph's inference that they have never been to Sedna.
    #
    # THE GUARD IS THE SAME ONE THE TOKENS USE, and it is what keeps the three earlier
    # branches of `expand` winning: anything already priced below a raw leaf is stock, an
    # infinite generator or a learned EMC item, and `expand` returns at all three before it
    # reaches this branch. So the cascade is reproduced by structure rather than by the
    # relative size of the constants.
    for key in raw or ():
        if cost.get(key, BASE_RAW_COST) < BASE_RAW_COST:
            continue
        # BASE_RAW_COST AND NOT A CONSTANT OF ITS OWN, which is not a #95 violation but the
        # opposite. #95 forbids one figure carrying two unrelated STATEMENTS; a declared stop
        # and a key no recipe makes are the same statement -- "you go and get this yourself" --
        # and `expand` backs that up by giving both `STATUS_RAW` and adding both to
        # `leaf_totals`. A separate number would assert a difference the solver does not make.
        #
        # ASSIGNMENT RATHER THAN `min`, because this has to be able to LOWER a token, an
        # unsourced mark or a dimension surcharge that a rule above already wrote.
        cost[key] = BASE_RAW_COST

    # AFTER `raw`, because `expand` prefers the craftable reading when a key is in both: it
    # reports `have` with the autocraft note rather than putting the key on the shopping list.
    for key in craftables or ():
        if cost.get(key, BASE_RAW_COST) < BASE_RAW_COST:
            continue
        cost[key] = CRAFTABLE_COST
    return cost


def _relax(graph, cost, passes, machine_states, machine_entry):
    """One Bellman-Ford style relaxation over every recipe, mutating and returning `cost`."""
    machine_cost = {}
    for r in graph.recipes:
        if r.category not in machine_cost:
            machine_cost[r.category] = category_entry_cost(
                r.category, machine_states, machine_entry)

    ore_members = graph.ore_members
    recipes = graph.recipes
    # A DICT LOOKUP PER OUTPUT, NOT A PREDICATE CALL: this loop runs over every output of
    # every one of ~340,000 recipes, `passes` times. See `Graph.variant_subsumption` for the
    # relation and for why it is not spelled out here.
    subsumption = graph.variant_subsumption

    settled = max(1, int(len(recipes) * SETTLED_FRACTION))
    for _ in range(passes):
        changed = 0
        for r in recipes:
            base = machine_cost[r.category]
            if r.transfer:
                base += TRANSFER_PENALTY
            # A LOOT TABLE OR A JEI AUTOMATION CARD IS NOT PRODUCTION EITHER, and it is CHARGED
            # rather than skipped. #211 and #169.
            #
            # THE ASYMMETRY WITH THE TRANSFER SKIP BELOW IS DELIBERATE AND MEASURED. Skipping
            # was written first, because it makes this loop agree exactly with
            # `Graph.real_producers`, which is what the solver walks. It STRANDS 26 KEYS: five
            # of #169's seven infusion catalysts, `techreborn:rubber_sapling` and 20 others go
            # from a finite price to infinity, because their only route that is not
            # documentation is itself unreachable. Charging the penalty strands 0 and produces
            # a byte-identical plan on the #211 reproduction.
            #
            # That is the same finding `UNSOURCED_COST` records above -- infinity was measured
            # there too and rejected for stranding 2,372 priced keys, while "every FINITE
            # candidate strands zero". The magnitude differs; the reason does not.
            #
            # SO THE TWO DISAGREE, AND THE HARM THE MIRRORING RULE GUARDS AGAINST STILL CANNOT
            # HAPPEN. That rule exists so the ranker never prefers a route the solver cannot
            # take, and this penalty is above the 5,000 wall, so every route the graph can
            # account for outranks it. What survives is only that a key with NO accountable
            # route reads 50,000-odd rather than unreachable, and a plan still reports it raw.
            # A number saying "nothing here can obtain this" beats a missing number.
            if r.not_production:
                base += NON_PRODUCTION_PENALTY
            ingredients = 0.0
            # A RETAINED INPUT IS ECONOMICALLY A MACHINE, so its cost joins `base` and NOT the
            # amortising term below. #175: an input with `consume_chance == 0.0` survives the
            # run, so you buy one and run the recipe forever. Dividing it by the batch would
            # say a big enough output makes the retained input free, the identical error
            # the amortisation comment further down was written about for machines.
            #
            # IT IS NOT PRICED AT ZERO, AND THAT IS THE POINT. Free would make every such
            # route the cheapest one in the model, so the solver would prefer machines whose
            # retained input the player cannot obtain -- the defect #176 fixed for
            # unsourced keys, reintroduced through a different door. `min` still applies
            # afterwards, so a genuinely cheaper uncatalysed route still wins.
            retained = 0.0
            for ing in r.inputs:
                c = input_cost(cost, _cheapest_alternative(cost, ing, ore_members),
                                ing.qty, ore_members)
                if math.isinf(c):
                    ingredients = math.inf
                    break
                if ing.survives_run:
                    retained += c
                else:
                    # A FRACTIONAL CHANCE GENUINELY AMORTISES, unlike a permanent one: an
                    # input consumed 30% of the time costs 0.3 of itself per run, and over a
                    # batch that is exactly what you spend. Only 24 input slots in the
                    # reference pack are fractional (one deliberate 8-tier ladder in
                    # `Trinitas.zs`), but they span 0.95 down to 0.001, so rounding them to a
                    # boolean would be wrong by three orders of magnitude at one end.
                    ingredients += c * ing.consume_chance
            if math.isinf(ingredients):
                continue
            for key, qty in r.outputs:
                # A container transfer never makes its fluid cheaper: emptying a can you own
                # is not production. THROUGH `Graph.real_production`, which is the predicate
                # the solver walks -- if these disagree the ranker prices a route the solver
                # cannot take. This used to be a hand-rolled copy of it (#193), correct on
                # the day it was written, and a mirror that is currently correct is still a
                # mirror: the failure is at the next edit to `real_production`.
                if not graph.real_production(r, key):
                    continue
                # ONLY THE INGREDIENTS AMORTISE. `base` is what running this recipe costs
                # you at all -- overwhelmingly the machine -- and dividing it by the batch
                # says a big enough output makes the machine free. It is not a small error:
                # the reference pack has a Hostile Computing Unit recipe yielding 1,024 iron
                # ingots and an Enchanted Greenhouse one yielding 60,466,176 fruit, so the
                # 5,000 wall MACHINE_COST puts in front of an unavailable machine collapsed
                # to 8e-5 and 126 items (diamond, coal, string, redstone) priced under 0.1.
                # That is how "one iron ingot" came out as "smelt a Spawner Shard", which
                # needs a Pristine Matter run, which needs bee drones. See issue #29.
                per_unit = base + retained + ingredients / _scaled_qty(key, qty)
                if per_unit < cost.get(key, math.inf) - 1e-9:
                    cost[key] = per_unit
                    changed += 1
                # AND THE BARE KEY THIS VARIANT SATISFIES A DEMAND FOR. #170: the Alchemy
                # Array makes `animus:kama_bound#fd1adc426e12` while four recipes ask for
                # `animus:kama_bound`, so `_seed` found no producer for the bare key and
                # #176 priced it UNSOURCED_COST -- a 60.0 route reported as a 2,000 wall.
                # 88 keys on the reference graph move, and 613 prices in all.
                #
                # THE CHEAPEST PRODUCTION, NOT `min` OVER VARIANT COSTS, AND THE DIFFERENCE
                # IS A MEASURED HAZARD RATHER THAN A STYLE. Stock enters the table through
                # `_seed` as `cost[key] = 0.0` and never through `per_unit`, which is
                # `base + ingredients / qty`. Attributing a RECIPE therefore cannot leak
                # stock, and 18 of the eligible bare keys have a variant the AE2 network
                # holds: `min` over costs would price those at 0.0 and the plan would say
                # HAVE for an item nobody owns under that key. Reading the recipe instead
                # lands `forestry:sapling` on what growing one costs.
                #
                # A PURE LOWERING, WHICH IS WHY IT CAN LIVE IN THIS LOOP AT ALL. `_relax`
                # only ever lowers, so a floor cannot be patched into a settled table (see
                # BASE_RAW_COST's fourth finding) -- and the floor here is #176's 2,000
                # seed, which is an INPUT to this relaxation and stays underneath every key
                # this rule fails to reach. Riding the existing fixpoint also means no extra
                # pass and no settle step: `changed` counts the move, so the loop keeps
                # going until the subsumed price has propagated to whatever consumes it.
                #
                # AND IT NEEDS NO DEMOTION GUARD OF ITS OWN, which is worth writing down
                # because `real_producers` sends the next reader here before making its two
                # exclusions identical. A demoted recipe reaches this line -- the loop charges
                # `NON_PRODUCTION_PENALTY` rather than skipping it -- but cannot lower a
                # subsumed key below its floor, because that penalty is 50,000 and the floor
                # is `UNSOURCED_COST`'s 2,000, so `per_unit` is never the smaller number. The
                # SOLVER half needs no argument at all: `_variant_candidates` reads
                # `real_producers`, which drops demoted recipes outright, so a loot table is
                # never offered as a substitution route. The two rules compose because this
                # one is built on `real_producers` and not on `producers`. #211, #169.
                bare = subsumption.get(key)
                if bare is not None and per_unit < cost.get(bare, math.inf) - 1e-9:
                    cost[bare] = per_unit
                    changed += 1
        if changed < settled:
            break
    return cost


def fingerprint(graph_path, have, machine_states, free_sources, machine_items=None,
                multiblocks=None, token_kinds=None, dimension_gates=None,
                emc_available=None, craftables=None, raw=None):
    """Stable digest of everything `estimate` reads, for cache validation.

    Deliberately hashes the machine states and stock CONTENTS rather than the file mtimes:
    a manual override changes machine state without touching the graph, and mtimes move
    when a file is rewritten with identical contents. Also folds in the tuning constants,
    so editing MACHINE_COST invalidates the cache instead of silently reusing prices
    computed under the old table. FORMULA_VERSION covers the other half of that: a CHANGE TO
    THE CODE, which moves no input here at all. This docstring used to say "a change to the
    arithmetic", and see FORMULA_VERSION for why that is too narrow -- #136 moved 10,810
    prices with no arithmetic and no constant, and this function could not tell.
    """
    h = hashlib.sha256()
    try:
        stat = os.stat(graph_path)
        h.update(("%s|%d|%d" % (graph_path, stat.st_size, int(stat.st_mtime))).encode())
    except OSError:
        h.update(str(graph_path).encode())
    h.update(repr(sorted((MACHINE_COST.items()))).encode())
    # Every tuning constant `machine_entry_costs` and `_relax` read. The #95 slice boundaries
    # are derived from BUILD_SPREAD and MACHINE_COST rather than typed in, so hashing those two
    # would in fact cover them today -- they are listed anyway, because "the cache is correct
    # because of how a constant happens to be defined" is the kind of reasoning that goes stale
    # the moment someone gives one of them a literal value.
    h.update(("%r %r %r %r %r %r %r %r %r %r %r %r %r %r"
              % (UNGATED_MACHINE_COST, FLUID_SCALE, BASE_RAW_COST, TRANSFER_PENALTY, PASSES,
                 FORMULA_VERSION, BUILD_SPREAD, BUILD_SCALE, BUILD_KNEE, BUILD_SLOPE,
                 UNPRICED_MACHINE_COST, BLOCKED_FLOOR, BLOCKED_CEILING,
                 NON_PRODUCTION_PENALTY)).encode())
    # AND THE LOOT-TABLE DECLARATION, which is neither a price nor a per-world input: adding a
    # category name to `tokens.LOOT_TABLE_CATEGORIES` moves the prices of everything that
    # category claimed to produce and moves no other input at all. The token map is hashed
    # further down with the other per-world data, and it is the second half of what
    # `notproduction` reads.
    h.update(repr(sorted(tokens.LOOT_TABLE_CATEGORIES)).encode())
    # AND EVERY CONSTANT `_seed` READS, which this hash did not cover until #176 added one.
    # The docstring above has always claimed that "editing MACHINE_COST invalidates the cache
    # instead of silently reusing prices computed under the old table" -- true of the
    # relaxation's constants and, until now, false of the seed's. Editing GATE_COST from
    # 1,000 to 1,500 moved no other input, so a warm `.cost-cache.json` went on serving
    # 1,000 forever, and the symptom would read as "the tuning change did not work" rather
    # than as a stale cache. That is the failure this whole function exists to prevent,
    # sitting inside it.
    #
    # `dimension_gates` and `emc_available` are hashed further down because they are DATA
    # that moves without any constant moving; these are the PRICES those data are charged
    # at, and both halves are needed.
    from .generators import SOURCE_COST
    h.update(("%r %r %r %r %r %r"
              % (LOOT_COST, GATE_COST, DIMENSION_COST, EMC_COST, UNSOURCED_COST,
                 CRAFTABLE_COST)).encode())
    h.update(("%r" % (SOURCE_COST,)).encode())
    # Beside the stock rather than with the constants: a gate depends on which dimensions
    # the SAVE has terrain for, so it moves when the player flies somewhere without the
    # graph or any tuning constant changing. Miss it and a cache written before the trip
    # keeps charging for it forever.
    for key, dim in sorted((dimension_gates or {}).items()):
        h.update(("%s@%s;" % (key, dim)).encode())
    h.update(b"\x00")
    # Beside the gates, for the same reason: what the player has LEARNED moves without the
    # graph, the stock or any constant moving. A cache written before an item was learned
    # would go on pricing it as a dungeon drop forever, which reads as "#50 does not work".
    for key in sorted(emc_available or ()):
        h.update(("%s;" % key).encode())
    h.update(b"\x00")
    for key, qty in sorted((have or {}).items()):
        h.update(("%s=%s;" % (key, qty)).encode())
    h.update(b"\x00")
    # Beside the stock, because they are the same KIND of input: what this player's network can
    # autocraft and what this player has declared they will fetch themselves. #193 fed both to
    # `estimate`, and an input the prices depend on that the fingerprint does not cover is a
    # cache HIT serving one inventory's table to another -- a silent wrong answer, which is the
    # one failure this function exists to prevent. Two separate runs rather than one merged
    # set, so a key moving between `raw` and `craftables` moves the digest.
    for key in sorted(craftables or ()):
        h.update(("%s;" % key).encode())
    h.update(b"\x00")
    for key in sorted(raw or ()):
        h.update(("%s;" % key).encode())
    h.update(b"\x00")
    for uid, state in sorted((machine_states or {}).items()):
        h.update(("%s=%s;" % (uid, state[0])).encode())
    h.update(b"\x00")
    for key in sorted(free_sources or ()):
        h.update(("%s;" % key).encode())
    # The machine ITEMS, not just the states. A catalyst change can move which block a
    # category's machine is without moving the state, and the entry cost is derived from that
    # item's price -- so hashing states alone would serve prices for the old machine.
    h.update(b"\x00")
    for uid, keys in sorted((machine_items or {}).items()):
        h.update(("%s=%s;" % (uid, ",".join(keys))).encode())
    # The multiblock structures, in full. The graph file's size and mtime above already move
    # when a rebuild changes them, so this is belt and braces for the one case that skips the
    # file: a caller that supplies or edits `graph.multiblocks` in process, where every other
    # input is identical and a hit would serve prices computed for a different structure. That
    # is the shape of trap #86 hit, where patching a function moved no fingerprint input.
    h.update(b"\x00")
    h.update(json.dumps(multiblocks or {}, sort_keys=True).encode())
    # The TOKEN MAP, because it is user-editable. `data/tokens.json` can add a gate or
    # disable one, which moves a key between 1.0 and 1,000.0 while the graph, the stock and
    # every machine state stay exactly as they were -- so without this the edit takes effect
    # in the badges (which read the map directly) and not in the prices, and the plan
    # disagrees with its own annotations.
    h.update(b"\x00")
    h.update(json.dumps(token_kinds or {}, sort_keys=True).encode())
    return h.hexdigest()


def cache_beside(graph_path):
    """Where to memoise the cost table for `graph_path`: beside the GRAPH, not the cwd.

    DO NOT restore `DEFAULT_COST_CACHE` as the default for `cache_path`. That constant is the
    RELATIVE "data/.cost-cache.json", and a relative default plus a container is the #92 bug
    in a second place -- once here and once in `server.State`, the two entry points that
    memoise. The image's WORKDIR is /app while the graph is the mounted /data/graph.json, so
    both the server and a containerised `plan` memoised into /app/data/ inside the container
    layer and threw the table away on every recreation, paying the 26s relaxation again with a
    valid cache one bind-mount away.

    It also let the TEST SUITE write into the developer's real data/ dir. `tests/test_server`
    puts the graph, stock, machines, sources, tokens and pins in a tempdir, and the cost cache
    was the one path `State` gave it no way to redirect -- so `unittest discover` from the repo
    root replaced data/.cost-cache.json with four fixture prices, and on Tower, where the repo
    checkout's data/ IS the serving bind mount, a test run discarded the live server's warm
    table. Resolved HERE rather than in each caller so a new caller cannot reintroduce it by
    forgetting to pass a path; only the BASENAME comes from the constant, so the name of the
    file stays in one place.
    """
    return os.path.join(os.path.dirname(os.path.abspath(graph_path)),
                        os.path.basename(DEFAULT_COST_CACHE))


def estimate_cached(graph, graph_path, have=None, machine_states=None, free_sources=None,
                    cache_path=None, passes=PASSES, machine_items=None, token_kinds=None,
                    dimension_gates=None, emc_available=None, craftables=None, raw=None):
    """`estimate`, memoised on disk. Falls back to computing on any cache problem.

    `cache_path` defaults to `cache_beside(graph_path)`; see there for why it is not the
    relative constant. Pass one explicitly to override, which is what an A/B of a cost change
    must do -- the fingerprint covers the constants but NOT the code, so two arms sharing a
    cache serve arm one's table to arm two and agree by construction.

    Measured on the 117.7k-recipe reference graph: 15.6s for one relaxation, 26.1s for the
    two `estimate` runs when build targets are supplied (#86). Fine once at server startup
    and tedious on every `plan` invocation, which is what this is for. A stale cache would be
    far worse than a slow one, so validation is a content fingerprint and every failure path
    recomputes rather than guessing.

    #110 ROUGHLY DOUBLED A CACHE MISS and that is the price of the fix, not an oversight.
    `_settle_reshaped` restores the leaf price to keys an expanded chisel table stranded and
    then relaxes again so it propagates, and the propagation is real work: 1,608 stranded
    keys turn 4,814 others from unreachable into priced, over 8 further passes. Re-measured
    end to end on one machine so the arms are comparable, single-relaxation path: 25.5s over
    117,681 recipes before, 48.0s over 124,467 after, of which 20.0s is the second
    relaxation and the rest is the 6,786 added conversions. Do not "optimise" this by
    seeding the fallback instead: relaxation only ever lowers a cost, so a key seeded at
    BASE_RAW_COST keeps it, and #110's undercut comes straight back.

    The cached document carries the machine entry costs alongside the item costs. It has to:
    a hit that returned only the item prices would hand back a plain table, `recipe_cost`
    would fall back to the flat constants, and the ranking would disagree with the very
    relaxation the cache is serving -- a divergence visible only on a cache HIT, which is the
    hard way to find it.
    """
    # ON THE HIT PATH TOO, which `estimate` cannot cover. A cache hit returns before
    # `estimate` runs, and the flag has consumers besides the relaxation: `recipe_cost` charges
    # the penalty, `Solver.score_recipe` ranks on it, and `/api/recipe` reports it. A warm
    # cache would otherwise serve correct PRICES beside a graph that says every recipe is a
    # production route, which is the divergence that only shows up on the second run.
    graph.mark_non_production(token_kinds)
    cache_path = cache_path or cache_beside(graph_path)
    stamp = fingerprint(graph_path, have, machine_states, free_sources, machine_items,
                        getattr(graph, "multiblocks", None), token_kinds, dimension_gates,
                        emc_available, craftables, raw)
    if cache_path and os.path.exists(cache_path):
        try:
            with open(cache_path) as fh:
                doc = json.load(fh)
            if doc.get("fingerprint") == stamp and isinstance(doc.get("cost"), dict):
                entry = doc.get("machine_entry")
                return CostTable(
                    {k: (math.inf if v is None else v) for k, v in doc["cost"].items()},
                    machine_entry=entry if isinstance(entry, dict) else None)
        except (ValueError, OSError):
            pass

    cost = estimate(graph, have=have, machine_states=machine_states, passes=passes,
                    token_kinds=token_kinds, dimension_gates=dimension_gates,
                    free_sources=free_sources, machine_items=machine_items,
                    emc_available=emc_available, craftables=craftables, raw=raw)
    if cache_path:
        try:
            os.makedirs(os.path.dirname(cache_path) or ".", exist_ok=True)
            tmp = cache_path + ".tmp"
            with open(tmp, "w") as fh:
                json.dump({"fingerprint": stamp,
                           "cost": {k: (None if math.isinf(v) else v)
                                    for k, v in cost.items()},
                           "machine_entry": getattr(cost, "machine_entry", None) or {}}, fh)
            os.replace(tmp, cache_path)   # atomic, so a killed run cannot leave a torn file
        except OSError:
            pass
    return cost


def recipe_cost(cost, recipe, ore_members, machine_states=None, pick=None):
    """Estimated cost of running one recipe once, given precomputed item costs.

    `pick(ingredient) -> alternative` names, per slot, the option whoever is asking will
    ACTUALLY use; it defaults to the cheapest. The solver passes its own
    `Solver.pick_alternative`, and it has to: pricing a slot at its cheapest option and
    then expanding a different one is how "1 Iron Ingot" became "cast 1,296 mB of molten
    iron". That recipe's slot accepts a Block of Iron or a decorative Chisel block, the
    Chisel block WAS a raw leaf costing BASE_RAW_COST, so the recipe priced at 2.0 and beat
    smelting an ore -- and then the solver expanded the Block of Iron instead, because it
    is the one with a recipe. Nothing was mispriced; the price was simply for a route
    nobody took. See issue #29.

    Past tense on the Chisel block because #110 expanded the chiselling tables and it now
    prices one chisel ABOVE the Block of Iron rather than below it. That removes this
    example's cheapest wrong answer; it does NOT remove the need for `pick`. Any slot whose
    cheapest option is not the one the caller will expand reproduces the same divergence,
    and the solver still picks on grounds of its own -- stock, pins, `ore_backed` -- that
    the cheapest-alternative default knows nothing about.
    """
    # Off the table when it is a `CostTable`, so the machine price charged here is the one
    # the relaxation actually used (#86). A plain dict still works and still gets the flat
    # constants, which is what every caller passing a hand-built table wants.
    total = category_entry_cost(recipe.category, machine_states,
                                getattr(cost, "machine_entry", None))
    if recipe.transfer:
        total += TRANSFER_PENALTY
    if recipe.not_production:
        total += NON_PRODUCTION_PENALTY
    for ing in recipe.inputs:
        alt = pick(ing) if pick else _cheapest_alternative(cost, ing, ore_members)
        best = input_cost(cost, alt, ing.qty, ore_members)
        if math.isinf(best):
            return math.inf
        # A FRACTIONAL CHANCE SCALES HERE, AND A RETAINED INPUT DOES NOT (#175). This function
        # prices ONE RUN, so there is no batch to amortise over and the two cases separate
        # differently from `_relax`:
        #
        #   p == 0.0   charged in FULL, once. You must own the shard before the forge runs, so
        #              a recipe demanding an expensive permanent input has to rank worse for it.
        #              Scaling by 0.0 here would price that recipe as though the shard were
        #              free and hand the ranker the #176 defect: the cheapest route in the
        #              model being the one whose input you cannot get.
        #   0 < p < 1  scaled, because one run spends `p` of it in expectation.
        #
        # AND THIS HAS TO AGREE WITH `_relax`, which is the reason it is here at all. That
        # charges a retained input at full cost and a fractional one at `p` of itself; a ranker
        # that charged a 30%-consumed input in full would price a route the relaxation prices
        # differently, and the divergence between "what the ranker charged" and "what the
        # solver expands" is exactly the class of bug #29 is about.
        if not ing.survives_run:
            best *= ing.consume_chance
        total += best
    return total
