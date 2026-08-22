# Screenshots

Evidence for GUI pull requests. `skill/SKILL.md` and `/sr-dev-review` both require a visual
artefact for anything that changes what a player sees, and a passing layout test is not one:
a widget tree can size itself perfectly and still draw one row through another, which is
exactly what the first shot of the planner showed.

**These are REGENERABLE AND DISPOSABLE. Replace them, do not curate them.** Every one is a
single command against the headless harness (#124), so a stale picture is a picture nobody
re-took rather than a record worth keeping. If a PR changes a screen, overwrite the file
rather than adding `-v2` beside it.

```bash
ORACLE=/coding/.recipegraph-build/graph-oracle.json
SHOTS=/coding/.recipegraph-build/shots

# A real plan, from a real graph, in a real client. ~105 s.
RECIPEGRAPH_ORACLE=$ORACLE harness/shot.sh planner-live planner-live
cp $SHOTS/planner-live.png docs/shots/planner-real-plan.png

# The same screen with no graph installed, which is what a new player sees.
harness/shot.sh planner-live planner-nograph
cp $SHOTS/planner-nograph.png docs/shots/planner-no-graph.png

# The recipe picker. `planner-recipes` shoots the node with the MOST candidates rather
# than the root, because a picker with one row is a picture of the feature not working.
RECIPEGRAPH_ORACLE=$ORACLE harness/shot.sh planner-recipes:plan-in-stock picker
cp $SHOTS/picker.png docs/shots/recipe-picker.png

harness/shot.sh planner-recipes:plan-in-stock picker-nograph
cp $SHOTS/picker-nograph.png docs/shots/recipe-picker-no-graph.png

# The machines table (#254). ALL THREE NEED THE ORACLE and there is no fixture fallback:
# a machines table is a verdict on every category in the graph, not a document that can be frozen.
RECIPEGRAPH_ORACLE=$ORACLE harness/shot.sh machines machines
cp $SHOTS/machines.png docs/shots/machines-table.png

# Filtered, because an unfiltered table opens on `have` -- the least informative rows in it.
# The screen exists for `no route` and `buildable`, and a static shot cannot scroll to them.
RECIPEGRAPH_ORACLE=$ORACLE harness/shot.sh machines:unavailable machines-noroute
cp $SHOTS/machines-noroute.png docs/shots/machines-no-route.png

RECIPEGRAPH_ORACLE=$ORACLE harness/shot.sh machines-mods machines-mods
cp $SHOTS/machines-mods.png docs/shots/machines-mod-picker.png

# Defaults to the BUSIEST category rather than a named one: a hardcoded uid stops existing
# when the pack changes, and the shot would then photograph an empty panel and still exit 0.
RECIPEGRAPH_ORACLE=$ORACLE harness/shot.sh machines-detail machines-detail
cp $SHOTS/machines-detail.png docs/shots/machines-detail.png

# The planner opened DURING the graph read, and the same window once the graph lands (#201).
# TWO RUNS, AND THE PAIR IS THE ARTIFACT: either picture on its own is exactly what the
# BROKEN build produces too, because the bug was that the second state never arrived.
# `planner-recovery:recovered` states its criteria and fails the run if they do not hold, so
# a green exit here is a behavioural claim and not only a picture.
RECIPEGRAPH_ORACLE=$ORACLE harness/shot.sh planner-recovery:loading planner-loading
cp $SHOTS/planner-loading.png docs/shots/planner-during-load.png
RECIPEGRAPH_ORACLE=$ORACLE harness/shot.sh planner-recovery:recovered planner-recovered
cp $SHOTS/planner-recovered.png docs/shots/planner-after-load.png

# The INSIDE of that wait (#271): the same panel half-way through the same read. It holds
# until `progress()` passes 0.5, counting every `Minecraft.currentScreen` it sees on the way,
# and FAILS the run if the panel was drawn once and never redrawn -- which is what #271 was.
#
# `-Dmcrecipedump.shotSettleFrames=2` IS REQUIRED HERE AND IS NOT TIDINESS. The default twenty
# frames settle a panel's open animation and are spent BEFORE the hold starts, so on a slow
# rasteriser they come out of the 5.47 s this shot is watching and the hold opens on a read
# that has nearly finished. It then sees one window and reports "never redrawn" -- the defect's
# own verdict, produced by a knob. They buy nothing here: the captured frame is chosen by the
# hold, seconds later. See `PlannerRecoveryShot.progressHold`, which says the same thing at the
# code, because `requestSettleFrames` can raise the window and has no way to lower it.
# It also registers a `Drawn` check (#293's hook), so the run EXITS 7 rather than 0 if the panel
# it is about to photograph carries no percentage -- and rejects `0%` by name, because #271's own
# defect artifact reads `reading oracle.json, 0%` and would satisfy a guard that merely looked
# for a percentage. A green exit here is a claim about what the picture says, not just that one
# was written.
RECIPEGRAPH_ORACLE=$ORACLE harness/shot.sh planner-recovery:progress planner-progress \
    -Dmcrecipedump.shotSettleFrames=2
cp $SHOTS/planner-progress.png docs/shots/planner-mid-load.png
```

**`planner-recovery` needs the oracle and says so by failing.** Every other screen degrades
into a picture of the no-graph panel without `$RECIPEGRAPH_ORACLE`, which is a legitimate
subject. This one cannot: with no file the load resolves MISSING synchronously and there is no
wait to photograph, so the run reports that rather than producing a plausible picture of
something else.

```bash
# The other two browse tabs (#255). Both need the oracle; `graph` is the faster.
RECIPEGRAPH_ORACLE=$ORACLE harness/shot.sh sources sources
cp $SHOTS/sources.png docs/shots/browse-free.png

RECIPEGRAPH_ORACLE=$ORACLE harness/shot.sh graph graph
cp $SHOTS/graph.png docs/shots/browse-graph.png
```

**The `graph` shot shows `pack: UNCHECKED`, and that is correct.** I predicted MISMATCH here and
the run disproved it: the oracle graph is **schema 5**, and the jar-set stamp arrived in schema
6, so there is no recorded digest to compare against and the honest verdict is "cannot tell"
rather than "differs". The run logs it either way --
`graph: pack check CANNOT_TELL -- this dump predates the mod-set stamp; redump to fix` -- and
that line is what tells a harness artefact apart from a real finding.

A `pack: OK` in a harness shot would be the suspicious result, since the dev set is five jars.

Its whole subject is which graph is being read, so the other useful check is that the instance
path in the picture is the pack you expect -- which is also the check a fixture could have
faked, and the reason there is no fixture.

**Check which picture you got.** Without `RECIPEGRAPH_ORACLE` the `machines`, `sources` and `graph` shots
succeed and photograph the "no graph.json, looked in ..." panel instead of the table. That is
a legitimate picture and it is also easy to mistake for the real one in a PR thumbnail. The
run logs `machines: <what happened>`; read that line before attaching the file.

**Pinning needs a pin file, and the honest way to make one is from the OTHER side.**
`recipegraph/pins.py` writes the same format the mod reads, so building the file in Python
and photographing the mod reading it is the cross-language claim rather than an assertion
about it. `-Dmcrecipedump.pins` points the mod at any path; `/shots` is already mounted.

```bash
python3 - <<'EOF'
from recipegraph.model import Graph
from recipegraph import pins
g = Graph.load("/coding/.recipegraph-build/graph-oracle.json")
for r in g.real_producers("minecraft:iron_ingot"):
    if (r.category or "") == "minecraft.smelting":
        pins.save("/coding/.recipegraph-build/shots/pins-demo.json",
                  {"minecraft:iron_ingot": pins.make(g, r)})
        break
EOF

RECIPEGRAPH_ORACLE=$ORACLE harness/shot.sh planner-recipes:plan-in-stock picker-pinned \
  -Dmcrecipedump.pins=/shots/pins-demo.json
cp $SHOTS/picker-pinned.png docs/shots/recipe-picker-pinned.png

# And the pair that shows a pin changing a plan. Pin `Iron Ore, Petrotheum Dust,
# Pyrotheum Dust` for the second; the smelting pin above will NOT move this plan,
# because the solver already chose smelting.
RECIPEGRAPH_ORACLE=$ORACLE harness/shot.sh planner-live:minecraft:hopper before
cp $SHOTS/before.png docs/shots/plan-before-pin.png
RECIPEGRAPH_ORACLE=$ORACLE harness/shot.sh planner-live:minecraft:hopper after \
  -Dmcrecipedump.pins=/shots/pins-demo.json
cp $SHOTS/after.png docs/shots/plan-after-pin.png

# Two rows sharing a name, SCROLLED, because no unscrolled shot can show the rule (#232).
# The only same-name pair inside a 14-row viewport is `plan-variant-table`'s two Brown
# Concretes and BOTH of those are at capacity, so that fixture can only ever photograph one
# branch. `planner-collide` scrolls to the first pair with a same-label, different-key
# partner in frame, which in `plan-fluid-chain` is Ender Pearl at row 159.
harness/shot.sh planner-collide collide
cp $SHOTS/collide.png docs/shots/planner-same-name.png

# The flow diagram at the budget ceiling (#293). NO ORACLE: `synthetic:N` builds a balanced
# tree of exactly N nodes, so the size is the subject and a real plan's size is an accident.
# The run sweeps 300 frames, then poses on the ROOT box and photographs that -- and refuses
# the picture unless the box it aimed at is WHOLLY in frame, which is the check `panToBox`
# exists to satisfy. ~510 s, nearly all of it the sweep.
harness/shot.sh flow:synthetic:4000 flow4000
cp $SHOTS/flow4000.png docs/shots/flow-4000-composed.png
```

| File | What it shows |
| --- | --- |
| `planner-real-plan.png` | Borax planned in game against the 116 MB oracle: the graph loaded off-thread, the pack priced, the solver run, and the tree drawn. 15 nodes, routed through the Crystallizer, and byte-identical to `tests/fixtures/plan/plan-machine-choice.json`. The red line under the footer is the unread-input caveat -- see `common.ScenarioSource`. |
| `planner-no-graph.png` | No `graph.json` installed. The panel names the path it looked in, which is the difference between a player fixing it and filing a bug. |
| `recipe-picker.png` | Every recipe the pack has for `minecraft:iron_ingot` -- 172 of them, capped at 24 and saying so. The state column marks the one the plan took; the category column is what tells the rows apart, and it exists because the first shot cut it off every row. |
| `recipe-picker-pinned.png` | The same picker over a pin file written by `recipegraph/pins.py`. The mod matched it by FINGERPRINT, not by id, and moved it up under the in-use row. This is the cross-language claim as a picture: the file was built by Python's own `pins.make`, so the mod is not agreeing with itself. |
| `recipe-picker-no-graph.png` | No graph loaded. The picker says which of the three reasons it has nothing to offer, rather than showing an empty box. |
| `plan-before-pin.png` / `plan-after-pin.png` | One hopper, planned twice against the real pack. Iron Ingot goes from Smelting to a Crafting route, the row says `pinned`, and the subtree under it is different. The pin is the only thing that changed. |
| `planner-during-load.png` / `planner-after-load.png` | The calculator used a second after joining, and the same window a few seconds later, with nothing touched in between (#201). The first is the wait; the second is the plan the window replayed when the graph landed. Before #201 the second picture did not exist -- the window showed the first one until the player closed it and used the item again -- so this pair is the artifact and either half alone is equally consistent with the bug. |
| `graph-schema-behind.png` / `plan-schema-behind.png` | The same real schema-7 graph read by a schema-8 jar, on the two surfaces that report it (#285). The Graph tab carries `format: OLD GRAPH -- it lacks what this build reads` over `graph is schema 7 and this build reads 8; redump to fix`; the planner carries `graph is schema 7, this build reads 8 -- plans may be wrong` above the tree, because the Graph tab is a screen the player who needs it does not know to open. **The `pack: MISMATCH` line above it is the harness and not a defect** -- `shot.sh` runs a 10-jar dev set against a dump of the full 406, which is what `graph: pack check DIFFERS` in the log says. Taken with `RECIPEGRAPH_ORACLE=$BUILD/graph-s7.json`, a real dump of this pack one schema behind the jar, which is `stage-instance.sh`'s pinned-proceeds case rather than a way around its guard. |
| `planner-mid-load.png` | The panel above, half-way through the same read instead of at the start of it (#271). Put it beside `planner-during-load.png` and the pair is the number moving: `0%` when the window opens, `50%` a couple of seconds later. Before #271 the second picture could not exist -- every term of the counter the window watches moves on a state TRANSITION, and LOADING is one state, so the panel built at 0% was the panel still on screen at 99%. The run that produced this one also logged the seven windows it replaced on the way, at 23/25/31/35/40/46/50%, because one frame cannot show motion and the picture alone is not the whole artifact. The eyebrow reading `Planner` is the positive control: it is drawn by the same panel and nothing about #271 can change it, so a legible one makes the line under it a reading rather than a hope. **The panel lags the service by at most one twentieth and that is the design, not a defect** -- this capture reads `50%` while the log's `at capture` line reads `51%`, because the panel is the one built at the last step boundary. Before #271 that gap was `0%` against `37%` and unbounded. |
| `flow-4000-before.png` / `flow-4000-composed.png` | The flow diagram at `DEFAULT_MAX_NODES`, before and after #293. **THEY DIFFER BY ONE NODE AND THAT IS NOT THE ARGUMENT** -- a reviewer opening the second alone could reasonably conclude the fix did nothing. The panel is still mostly empty in both, and that is the plan rather than the camera: the layout is roughly 2,200 x 69,000, depth is capped at 24 columns while the leaf level is thousands of rows, and the sweep's own peak over 300 frames is 20 of 4,000 nodes drawn in any one frame. There is no viewport position at zoom 1.0 from which a 4,000 node plan looks like a diagram. What changed is that the run now STATES what it photographed and exits non-zero when that is nothing, so the pair is evidence about the guard and not about the picture. |
| `planner-mid-load-refused.png` | THE ONLY COMMITTED PICTURE THAT FAILED ITS OWN RUN, and it is here to be looked at rather than to be argued from. Same screen and same guard as `planner-mid-load.png`, against `graph-tiny-271.json`: the read finished before the hold opened, so what got photographed is a fully drawn, entirely legible panel that is not a picture of a read -- and the guard refused it. Nothing about the image looks like a failure until you read the line in it, which is the whole case against a check that counts pixels or asks whether anything was drawn. Its recipe is under "Making each guard say no"; #303 committed it because the only copy was in the rotating `/coding/.recipegraph-build/shots/` and would have aged out. |
| `planner-same-name.png` | BOTH BRANCHES OF #232's WIDTH RULE, on adjacent rows, which is the only arrangement that argues anything. `Ender Pearl · Crafting · 5…` is at capacity and declines the fragment, keeping the machine name it draws on master; `Ender Pearl (itemblacklist)` has an empty meta run and takes it. A shot of the taking row alone would be equally consistent with a fix that evicts the machine name to make room, which is the regression the no-eviction rule exists to prevent -- so the row that DOESN'T change is half the evidence. |
| `plan-pin-overruled.png` | A pin the cycle guard could not honour (`9 nuggets -> 1 ingot`, and the nuggets come from an ingot). The plan says so in red. Until this PR it said nothing, and the picture was byte-identical to `plan-before-pin.png` -- which is how the gap was found, by two screenshots that should have differed and did not. |

## Making each guard say no

Three screens register a `Drawn` check (#293): after the capture the screen is asked whether
what it drew is the thing this shot claims to be about, and the run exits non-zero when it is
not. **Each of those checks is a first version, and two of the three were WRONG on their first
attempt.** #293's asked `drawnLastFrame() > 0`, got 1, logged `drawn check PASSED` and wrote a
panel holding a two-pixel sliver against the left edge. #271's called `stateFor` at capture
time, which reports what the panel WOULD say if rebuilt -- so on a build carrying #271's own
defect it reads a healthy percentage while the frozen panel on screen reads `0%`, and it would
have passed the exact bug it was added for.

So a green guard is not evidence that it can refuse; only a refusal is, and a guard whose
refusal nobody has seen is the state each of these was built to eliminate, reintroduced one
level up. Every line quoted below was transcribed from a run that really failed. **Re-run the
matching recipe after touching any of these checks**, because the edit that breaks one leaves
the passing case passing.

**GREP FOR `DRAWN CHECK FAILED`. DO NOT WAIT ON AN EXIT CODE.** `runClient` is a Gradle task,
so the harness's own code never reaches `shot.sh`, which reports Gradle's 1 -- as `shot.sh`'s
own header says. Someone watching for a 7 concludes the guard did not fire. The codes, from
`ShotHarness`, because the point of having seven of them is that a reader can tell a broken
harness from a finding:

| | |
| --- | --- |
| 0 | the PNG at the reported path is this run's and the screen was satisfied |
| 2 | no such screen |
| 3 | timed out waiting for the screen |
| 4 | the write itself failed |
| 5 | the screen registered a verdict and never delivered one |
| 6 | the screen reported that its own criteria did not hold |
| 7 | the drawn check refused the picture |

The number does survive as TEXT, one line inside Gradle's failure block, which is where to look
when 6 and 7 need telling apart:

```
> Process 'command '.../bin/java'' finished with non-zero exit value 7
```

7 wins over 6 when both apply: the drawn check runs at capture, so a run can log its screen's
`!!` verdict line and still exit 7. The first recipe below is exactly that case.

**In every case below the PNG is written and is this run's.** A refusal is a finding about the
picture, not a failure to take one, so the file is there to look at -- which is the point of
the committed control image in the first recipe.

### `planner-recovery:progress` -- a read that finished before the guard could photograph it

Point the oracle at a graph small enough that the load completes before the hold opens.
`graph-tiny-271.json` is two keys and one recipe, written for this.

```bash
RECIPEGRAPH_ORACLE=/coding/.recipegraph-build/graph-tiny-271.json \
  harness/shot.sh planner-recovery:progress reject -Dmcrecipedump.shotSettleFrames=2
```

```
graph after startLoad: reading oracle.json, 100%
DRAWN CHECK FAILED -- the panel on screen is a FAILED panel reading "no such item in the
  graph: nuclearcraft:compound:7", which is not a picture of a read
!! the screen's verdict was NO: the read finished before 50%, so there was no loading panel
  left to photograph. Rebuilds seen: 1. Lower -Dmcrecipedump.progressFloor, or point
  $RECIPEGRAPH_ORACLE at the full graph (graph ready: 2 keys, 1 recipes).
Java has been asked to exit (code 7)
```

**`planner-mid-load-refused.png` is that run's picture and it is committed beside the passing
one on purpose.** It is fully rendered, entirely legible, and correctly refused -- so it is the
argument, in one image, for why a pixel count or a not-blank test would not have been enough.
Nothing about it looks like a failure until you read what the panel says.

### `planner-stale` -- a graph that agrees with the jar, so there is no warning to photograph

This screen is `planner-live` with one extra claim, and the claim is why it is a separate
screen: the staged graph must REALLY disagree with the jar, or no warning renders and the shot
is a good picture of the wrong case. Verified both ways (#285) -- `graph-s7.json` gives
`drawn check PASSED`, and `graph-s8b.json`, which matches the jar, gives:

```
DRAWN CHECK FAILED ... so no warning renders
```

with the reason and the fix in the same line. `planner-live` itself is deliberately left
without a `Drawn` check: its header pays for succeeding with no graph at all, which is every
CI run.

### `flow` -- a composed capture that frames a sliver instead of the box it aimed at

The check is `drawnLastFrame() > 0 && fullyShowing(rootBox())`, and the second half is the half
that was missing. To see it refuse, put back the defect it was written for: in
`FlowCanvas.panToBox`, route the computed offsets through `panTo(x, y)` instead of
`clampToRange`. `panTo` wraps, centring on the root asks for a negative offset, and
`Math.abs(value % range)` turns that into a pan AWAY from the target.

```bash
harness/shot.sh flow:synthetic:4000 flowreject
```

```
flow: composed on the root box for the capture
DRAWN CHECK FAILED -- flow: 1 node(s) drawn on the composed frame; box 0 of 4000 is NOT
  wholly in frame
Java has been asked to exit (code 7)
shot.sh: FAILED after 510s (exit 1); the PNG ... IS this run's
```

**`1 node(s) drawn` IS THE WHOLE POINT OF THIS RECIPE.** The first version of this check asked
only `drawnLastFrame() > 0`, and on this exact input that is TRUE -- so the first version passes
the very screenshot it was written to reject, and reports `drawn check PASSED -- 1 node(s)
drawn` over a two-pixel sliver. Only `fullyShowing(rootBox())` refuses it. Anyone weakening the
second clause should run this and watch the run go green, which is what going wrong looks like
here.

Restore `panToBox` afterwards. Do not leave the revert in a tree you then measure from.

`planner-live` is the only shot that SOLVES. `planner`, `planner-menu`, `planner-todo` and
`flow` read a frozen fixture and nothing else, which is the right subject for a layout
question. `planner-recipes` sits between the two: the tree is a fixture and the CANDIDATE
LIST comes from the loaded graph, because the plan shape carries `alternatives` as a count
and no fixture can supply a list of recipes. Fixtures prove the drawing, `planner-live`
proves the plumbing, and `planner-recipes` needs an oracle mounted to prove anything at all
-- without one it photographs the empty case, which is also worth having.

`harness/shot.sh` writes to `/coding/.recipegraph-build/shots/`, which is outside the
repository on purpose -- the harness produces far more pictures than are worth committing,
and only the ones a PR argues from belong here.
