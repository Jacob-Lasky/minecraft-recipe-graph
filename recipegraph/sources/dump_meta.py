"""summary.json: the dump's provenance, and JEI's own name for each category's mod.

WHY THIS EXISTS. A dump directory was undatable. The only signal that `catalysts.json` was
missing because the mod predated it -- rather than because the categories genuinely had no
catalysts -- was its absence, which is indistinguishable from a mod that tried and found
none. Same for any future field: an older dump read by a newer reader just quietly lacks
things.

SCHEMA tracks the SHAPE of the dumped files, not the mod version. Bump it in
DumpCommand.java and here together when a file's shape changes:
  1  recipes.ndjson, oredict.json, names.json, skipped.ndjson, summary.json
  2  adds catalysts.json, and summary.json gains mod_version and schema
  3  item stacks may carry `n`, a digest of the NBT that decides what the stack IS, and
     names.json keys by the discriminated id so the digest has a readable name
"""

import json
import os

SCHEMA = 3


def _document(dump_dir):
    """summary.json as a dict, or {} if it is absent or unreadable.

    Never raises: a missing or corrupt summary.json means an old or partial dump, which is
    exactly the case this module is here to describe.
    """
    path = os.path.join(dump_dir, "summary.json")
    if not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8", errors="replace") as fh:
        try:
            doc = json.load(fh)
        except ValueError:
            return {}
    return doc if isinstance(doc, dict) else {}


def category_mods(dump_dir):
    """{category uid: mod display name}, from JEI's own IRecipeCategory.getModName().

    This is the ONLY authoritative answer to "which mod owns this category". Deriving it
    from the uid's first token is a guess that fails whenever a uid does not begin with
    its modid, and 3 of the first 3 examples anyone looked at were wrong:
    `foregoing_plant_gatherer` is Industrial Foregoing, `safe_nuke_meatball` is Extreme
    Reactors, `SoulBinder` is enderiomachines. On the reference pack all 674 categories
    carry one.

    NOT interchangeable with the registry modid. This is a display name with spaces and
    capitals ("Industrial Foregoing"), and it will never substring-match
    `industrialforegoing:plant_gatherer` -- machine identification must keep using
    `machines.same_mod` on the uid. Two fields, two jobs.
    """
    cats = _document(dump_dir).get("categories")
    if not isinstance(cats, dict):
        return {}
    out = {}
    for uid, info in cats.items():
        name = info.get("mod") if isinstance(info, dict) else None
        if isinstance(name, str) and name.strip():
            out[str(uid)] = name.strip()
    return out


def read(dump_dir):
    """{'mod_version', 'schema', 'present'} for a dump directory."""
    doc = _document(dump_dir)
    schema = doc.get("schema")
    return {
        "mod_version": doc.get("mod_version"),
        # A dump with no schema field predates schema 2, so it IS schema 1 -- reporting
        # None would lose the one thing we can infer from the absence.
        "schema": int(schema) if isinstance(schema, int) else (1 if doc else None),
        "present": bool(doc),
    }


def describe(meta):
    """A one-line provenance note, or a warning when the schema is not the expected one."""
    if not meta["present"]:
        return ("dump provenance: unknown (no summary.json) -- if files are missing, "
                "re-run /recipedump with the current mod")
    version = meta["mod_version"] or "pre-0.4.2 (unstamped)"
    if meta["schema"] == SCHEMA:
        return "dump: written by mod %s, schema %d" % (version, SCHEMA)
    if meta["schema"] is not None and meta["schema"] < SCHEMA:
        return ("dump: written by mod %s at schema %s, but this reader expects %d -- "
                "re-run /recipedump to pick up newer fields"
                % (version, meta["schema"], SCHEMA))
    return ("dump: written by mod %s at schema %s, NEWER than this reader (%d) -- "
            "update recipegraph, some fields will be ignored"
            % (version, meta["schema"], SCHEMA))


# What a GRAPH says when it carries no mod version, which is not the same absence
# `read` reports. From a dump directory, no `mod_version` field means the mod predated
# 0.4.2 and did not stamp itself. From a graph.json it means the graph was built before
# `dump_version` was persisted (#38) and the version is simply not recorded -- the mod may
# well have been current. Reusing the "pre-0.4.2" wording there would assert an age nobody
# measured, on every graph on disk today.
UNRECORDED_VERSION = "(version unrecorded; rebuild to record it)"


def of_graph(graph):
    """`read()`'s shape, recovered from a graph that was built from a dump.

    So `describe` has ONE caller shape. The builder has the dump directory in hand and the
    server does not -- it has a graph.json that recorded what the builder found -- and
    without this the UI would grow a second sentence for the same three facts, free to
    drift from the one `recipegraph build` prints.
    """
    schema = getattr(graph, "dump_schema", 0) or None
    return {"mod_version": getattr(graph, "dump_version", None) or UNRECORDED_VERSION,
            "schema": schema,
            # `present` means "a summary.json was read", and a recorded schema is the only
            # evidence of that a graph carries. A graph built before the dump existed has
            # neither, which `describe` renders as "provenance: unknown".
            "present": schema is not None}
