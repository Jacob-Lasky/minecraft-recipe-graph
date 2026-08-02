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
# A real plan, from a real graph, in a real client. ~105 s.
RECIPEGRAPH_ORACLE=/coding/.recipegraph-build/graph-oracle.json \
  harness/shot.sh planner-live planner-live
cp /coding/.recipegraph-build/shots/planner-live.png docs/shots/planner-real-plan.png

# The same screen with no graph installed, which is what a new player sees.
harness/shot.sh planner-live planner-nograph
cp /coding/.recipegraph-build/shots/planner-nograph.png docs/shots/planner-no-graph.png
```

| File | What it shows |
| --- | --- |
| `planner-real-plan.png` | Borax planned in game against the 116 MB oracle: the graph loaded off-thread, the pack priced, the solver run, and the tree drawn. 15 nodes, routed through the Crystallizer, and byte-identical to `tests/fixtures/plan/plan-machine-choice.json`. The red line under the footer is the unread-input caveat -- see `common.ScenarioSource`. |
| `planner-no-graph.png` | No `graph.json` installed. The panel names the path it looked in, which is the difference between a player fixing it and filing a bug. |

`planner-live` is the only shot that SOLVES; the `planner*` shots read a frozen fixture and
are the right subject for a layout question. Fixtures prove the drawing, `planner-live`
proves the plumbing.

`harness/shot.sh` writes to `/coding/.recipegraph-build/shots/`, which is outside the
repository on purpose -- the harness produces far more pictures than are worth committing,
and only the ones a PR argues from belong here.
