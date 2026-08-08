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
# a machines table is a verdict on all 503 categories, not a document that can be frozen.
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
```

**Check which picture you got.** Without `RECIPEGRAPH_ORACLE` the three `machines` shots
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
```

| File | What it shows |
| --- | --- |
| `planner-real-plan.png` | Borax planned in game against the 116 MB oracle: the graph loaded off-thread, the pack priced, the solver run, and the tree drawn. 15 nodes, routed through the Crystallizer, and byte-identical to `tests/fixtures/plan/plan-machine-choice.json`. The red line under the footer is the unread-input caveat -- see `common.ScenarioSource`. |
| `planner-no-graph.png` | No `graph.json` installed. The panel names the path it looked in, which is the difference between a player fixing it and filing a bug. |
| `recipe-picker.png` | Every recipe the pack has for `minecraft:iron_ingot` -- 172 of them, capped at 24 and saying so. The state column marks the one the plan took; the category column is what tells the rows apart, and it exists because the first shot cut it off every row. |
| `recipe-picker-pinned.png` | The same picker over a pin file written by `recipegraph/pins.py`. The mod matched it by FINGERPRINT, not by id, and moved it up under the in-use row. This is the cross-language claim as a picture: the file was built by Python's own `pins.make`, so the mod is not agreeing with itself. |
| `recipe-picker-no-graph.png` | No graph loaded. The picker says which of the three reasons it has nothing to offer, rather than showing an empty box. |
| `plan-before-pin.png` / `plan-after-pin.png` | One hopper, planned twice against the real pack. Iron Ingot goes from Smelting to a Crafting route, the row says `pinned`, and the subtree under it is different. The pin is the only thing that changed. |
| `plan-pin-overruled.png` | A pin the cycle guard could not honour (`9 nuggets -> 1 ingot`, and the nuggets come from an ingot). The plan says so in red. Until this PR it said nothing, and the picture was byte-identical to `plan-before-pin.png` -- which is how the gap was found, by two screenshots that should have differed and did not. |

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
