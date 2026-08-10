# #259 — the hop1|hop2 dimension-gate sweep

Three JSONL files, 208 KB. One row per key, written as each plan finished.

**THESE ARE NOT LIKE `docs/shots/`, AND THE DIFFERENCE IS THE REASON THEY ARE COMMITTED.**
That directory's README opens by calling its contents "REGENERABLE AND DISPOSABLE — replace
them, do not curate them", because every picture in it is one command against the headless
harness. **These are the opposite: two of the three cannot be regenerated at all.**
`hop2-v19.jsonl` and `holes-v19.jsonl` were measured under `cost.FORMULA_VERSION` **19**, and
#270 replaced it with 20 on `7feea7b`. The code that produced those numbers is gone. Do not
apply the shots mental model here and overwrite them.

They lived in `/coding/.recipegraph-build/sweep259/` until this commit, which is a build
directory this repo treats as regenerable and which does get cleared.

## What each file is

| file | rows | `FORMULA_VERSION` | base | cap | what it answers |
| --- | --- | --- | --- | --- | --- |
| `hop2-v19.jsonl` | 560 | **19** | `7513b14` | 60s | the exhaustive pass — **does a 7-of-7 exist** |
| `holes-v19.jsonl` | 29 | **19** | `7513b14` | 900s | 29 of the 76 keys `hop2-v19` left unmeasured |
| `deep8-v20.jsonl` | 8 | **20** | `7e723ba` | 900s | **did #270 remove a positive** (it did not) |

**`hop2-v19` and `deep8-v20` are NOT duplicates and neither supersedes the other.** The first
is 560 keys under the old cost model and is the only exhaustive pass that exists. The second
is 8 keys under the new one and answers a different, narrower question. Deleting either loses
something the other does not carry.

All three ran against the same oracle — `graph-s8b.json`, sha256 `cbbe136bddf02c9f…`, which is
the graph `tests/fixtures/plan/*.json` name and the one `tools/check.sh` resolves to by hash.
The oracle did not move across #270; only the code did.

## Reading a row

`{"key", "hop", "held": {…7 claims…}, "n_held", "gate_depths", "gate_below_root", "nodes",
"work", "truncated", "exhausted", "seconds", "timeout", "error"}`

**`timeout: true` MEANS UNMEASURED, NOT NEGATIVE, and this is the field to get right.** Such a
row carries `held: null` — it has no claims, because the plan never finished. Counting one as
"reached no gate" is the failure #248 made at a larger scale, and it is why the cap is wall
clock rather than a smaller `max_nodes`: shrinking the node budget would make slow keys finish
*into truncation*, and `not_truncated` is one of the seven, so they would come back 6-of-7
looking like measured near-misses. `error` non-null with `timeout: false` is a raised plan,
also unmeasured — #248's probe once printed a confident `0 of 10` because every plan threw.

`hop2-v19.jsonl` has 76 such rows out of 560. `holes-v19.jsonl` closes 29 of them and every one
answered; the other 47 are still unmeasured and no file here claims otherwise.

## The result these support

hop1|hop2 is 514 keys and all 514 were planned: hop 0 10/10, hop 1 22/22, hop 2 482/482. (The
extra 46 rows in `hop2-v19.jsonl` are hop 3, which the run reached before it was stopped; they
are out of scope and `tools/sweep-dimension-report.py --max-hop 2` excludes them.)

**Exactly one 7-of-7 with its gate at depth ≥ 2: `contenttweaker:etherium_ingot`.** Sedna at
plan depth 4, 131 nodes, 563 work, not truncated. Both 6-of-7s fail `not_truncated`, which
disqualifies them rather than ranking them second — a truncated plan's shape depends on where
the budget stopped, which is the one thing a cross-language contract must not encode.

`tools/sweep-dimension-candidates.py` carries the caveat that matters before anyone repoints a
fixture at that key: it is 7-of-7 by the **tree-level** definition `make-java-fixtures.CHECKS`
measures, with `oredict` and `alternatives` satisfied on other branches rather than on the
spine to the gate.

## Regenerating what still can be

Only the v20 arm. The v19 arm cannot be reproduced without checking out `7513b14`.

```bash
python3 tools/sweep-dimension-candidates.py \
    --graph /coding/.recipegraph-build/graph-s8b.json \
    --out /tmp/v20.jsonl --max-hop 2 --seconds 400
python3 tools/sweep-dimension-report.py \
    --rows docs/measurements/259/hop2-v19.jsonl docs/measurements/259/holes-v19.jsonl \
    --enum <enumeration json> --max-hop 2
```

A later `--rows` file supersedes an earlier one per key, which is how `holes-v19` replaces the
holes in `hop2-v19` instead of being counted beside them.
