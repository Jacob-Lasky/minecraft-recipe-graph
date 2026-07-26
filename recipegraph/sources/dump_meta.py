"""Provenance for a dump: which mod version wrote it, to which file schema.

WHY THIS EXISTS. A dump directory was undatable. The only signal that `catalysts.json` was
missing because the mod predated it -- rather than because the categories genuinely had no
catalysts -- was its absence, which is indistinguishable from a mod that tried and found
none. Same for any future field: an older dump read by a newer reader just quietly lacks
things.

SCHEMA tracks the SHAPE of the dumped files, not the mod version. Bump it in
DumpCommand.java and here together when a file's shape changes:
  1  recipes.ndjson, oredict.json, names.json, skipped.ndjson, summary.json
  2  adds catalysts.json, and summary.json gains mod_version and schema
"""

import json
import os

SCHEMA = 2


def read(dump_dir):
    """{'mod_version', 'schema', 'present'} for a dump directory.

    Never raises: a missing or corrupt summary.json means an old or partial dump, which is
    exactly the case this is here to describe.
    """
    path = os.path.join(dump_dir, "summary.json")
    doc = {}
    if os.path.exists(path):
        with open(path, encoding="utf-8", errors="replace") as fh:
            try:
                loaded = json.load(fh)
                if isinstance(loaded, dict):
                    doc = loaded
            except ValueError:
                pass
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
