"""summary.json: the dump's provenance, and JEI's own name for each category's mod.

WHY THIS EXISTS. A dump directory was undatable. The only signal that `catalysts.json` was
missing because the mod predated it -- rather than because the categories genuinely had no
catalysts -- was its absence, which is indistinguishable from a mod that tried and found
none. Same for any future field: an older dump read by a newer reader just quietly lacks
things.

SCHEMA tracks the SHAPE of the dumped files, or the MEANING of a value a reader recomputes,
not the mod version. Bump it in DumpCommand.java and here together:
  1  recipes.ndjson, oredict.json, names.json, skipped.ndjson, summary.json
  2  adds catalysts.json, and summary.json gains mod_version and schema
  3  item stacks may carry `n`, a digest of the NBT that decides what the stack IS, and
     names.json keys by the discriminated id so the digest has a readable name
  4  `n` is computed differently: named lists are sorted and `ench` is cosmetic, so every
     discriminated key moves. Shape-identical to 3 and NOT parse-compatible with it. #80, #63
  5  summary.json's `skipped` becomes `threw` and gains `skip_lines` (#90); adds
     damageable.json (#118), emc.json (#50), machine_names.json (#55) and the
     icons-N.png / icons.json atlas (#36)
  6  summary.json gains `names` and `names_failed`, so a names.json that lost entries stops
     being undetectable in principle, and `mod_count` / `mod_digest`, so a dump can say
     which jars it saw (#194)

SCHEMA 6 IS ADDITIVE AND CHANGES NO EXISTING FIELD, so a schema-5 dump is read exactly as it
was; what it cannot do is answer the question the new fields exist to answer, and `describe`
says so rather than reporting a clean bill it did not measure. `DIGEST_FORMAT_SCHEMA` stays
at 4 for the same reason it stayed there through 5.

SCHEMA 5 IS A SHAPE CHANGE ONLY -- `n` is computed exactly as it was at 4, so a schema-4
graph's discriminated keys are still the keys this reader computes and AE2 stock still
matches. That is why `DIGEST_FORMAT_SCHEMA` stayed at 4 and why `describe` says "re-run
/recipedump to pick up newer fields" for a schema-4 dump rather than the far louder warning
it gives for a schema-3 one. Moving DIGEST_FORMAT_SCHEMA for a bump that did not touch the
digest would cry wolf, and a warning that cries wolf gets trained away before the one time
it matters.

`tests/test_catalysts.py` reads DumpCommand.java and asserts the two numbers are equal, so
bumping one alone fails without a JVM.
"""

import json
import os

try:
    from ..nbt_digest import DIGEST_FORMAT_SCHEMA
except ImportError:  # run directly as a script; see ae2_inventory's module docstring
    from nbt_digest import DIGEST_FORMAT_SCHEMA

SCHEMA = 6

#: The directory `/recipedump` writes into, relative to the pack's `minecraft/` dir.
#:
#: ONE definition on purpose. Five call sites used to spell this literal themselves --
#: `index.build` three times, `catalysts.find`, `dump_names.find` -- which made pointing a
#: build at a differently-named dump directory impossible, and that is not hypothetical: the
#: churn proof for #80 REQUIRES preserving a dump under another name, because a second
#: `/recipedump` rewrites this directory in place. With the literal scattered, `--hei` could
#: redirect `recipes.ndjson` while `names.json`, `oredict.json` and `catalysts.json` still
#: came from whatever sat at the canonical path, silently mixing two dumps in one graph.
DIR_NAME = "mc-recipe-dump"

#: The provenance file inside that directory, for the same reason `DIR_NAME` is one string.
#:
#: `gaps.load` spelled it itself, which made two modules independently responsible for
#: agreeing with `DumpCommand.writeSummary`. The failure that arrangement produces is not an
#: exception: `gaps` opens the path, finds nothing, and reports an empty summary -- the same
#: shape a dump legitimately has before schema 2. A renamed file would read as an old dump.
SUMMARY_NAME = "summary.json"


def dir_for(instance_dir, dump_dir=None):
    """The dump directory to read, as a path.

    `dump_dir` is an explicit path and wins outright, so a caller can point at a preserved
    dump (`mc-recipe-dump.s4-run1`) or one outside the instance entirely. Absent, it is
    `DIR_NAME` under the instance, which is where the mod actually writes.
    """
    return dump_dir if dump_dir else os.path.join(instance_dir, DIR_NAME)


def _document(dump_dir):
    """summary.json as a dict, or {} if it is absent or unreadable.

    Never raises: a missing or corrupt summary.json means an old or partial dump, which is
    exactly the case this module is here to describe.
    """
    path = os.path.join(dump_dir, SUMMARY_NAME)
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


def _count(doc, field):
    """A non-negative int field, or None when the dump does not declare it.

    NONE AND ZERO ARE DIFFERENT ANSWERS and the whole of #194 turns on it: None means a
    schema-5 dump that never recorded this, and zero means a schema-6 dump that measured it
    and found nothing wrong. Coercing the absent case to 0 would report a clean bill nobody
    measured, which is the failure the field was added to end.

    `bool` is excluded because `isinstance(True, int)` is True in python and a `true` in the
    JSON is a malformed count, not a count of one.
    """
    value = doc.get(field)
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        return None
    return value


def read(dump_dir):
    """What summary.json declares about the dump that wrote it."""
    doc = _document(dump_dir)
    schema = doc.get("schema")
    return {
        "mod_version": doc.get("mod_version"),
        # A dump with no schema field predates schema 2, so it IS schema 1 -- reporting
        # None would lose the one thing we can infer from the absence.
        "schema": int(schema) if isinstance(schema, int) else (1 if doc else None),
        "present": bool(doc),
        # Schema 6. `names` is how many entries names.json should hold, `names_failed` how
        # many keys the dump has no name for AT ALL because getDisplayName threw. See #194.
        "names": _count(doc, "names"),
        "names_failed": _count(doc, "names_failed"),
        # Schema 6, and the pair that answers "which jars was this taken against". Both
        # None for any dump older than that, which is every dump on disk today. See #194.
        "mod_count": _count(doc, "mod_count"),
        "mod_digest": (doc.get("mod_digest")
                       if isinstance(doc.get("mod_digest"), str) and doc.get("mod_digest")
                       else None),
    }


class RefusedBuild(Exception):
    """A build that must not happen, raised rather than reported.

    RAISED, NOT RETURNED AS A VERDICT SOMEONE PRINTS. Both subclasses guard artifacts that
    cost a game launch to reproduce, and this project has repeatedly measured what a printed
    warning is worth in a long run: the `!!` line scrolls past and the run still ends in a
    success message. `tools/check.sh` says it twice in its own header, and #192 replaced a
    warning with a refusal for the same reason. So the failure has to be the exit code.

    ONE BASE SO ONE CATCH COVERS BOTH. `recipegraph.cli.main` catches this and turns it into
    exit 2 with the message; a third refusal added here needs no change there.
    """


class DamagedDump(RefusedBuild):
    """A dump directory whose own summary.json says it is not what is on disk.

    A SEPARATE TYPE FROM A MISSING FILE, because they call for opposite responses. Every
    other absence this package handles is degradation the graph survives -- no catalysts,
    no emc, no icons -- and each of those is reported and stepped over. This is the dump
    contradicting itself, which no amount of stepping over makes safe, so it is raised
    rather than said.
    """


class WrongPack(RefusedBuild):
    """The dump was taken against a different set of jars than the graph being replaced.

    NOT a damaged dump: both artifacts may be perfectly good, and the wrong one is about to
    overwrite the other. The five-jar case is the one #194 was filed about, and it is not
    hypothetical -- the headless harness now produces valid dumps from a six-mod dev client.
    """


def check_names(meta, on_disk):
    """Raise `DamagedDump` when names.json is not the file summary.json says it is.

    REFUSAL RATHER THAN A WARNING, and the reason is not that display names are precious.
    It is that a count mismatch cannot be explained by age, by an optional mod, or by a
    pack that genuinely has none of something -- every benign absence in this package
    produces a MISSING file, not a short one. A short names.json means the bytes on disk are
    not the bytes the dump wrote, and nothing else in the directory has been checked. This
    project has repeatedly found that a `!!` line in a long run scrolls past and the run
    still ends in a success message, so the artifact refuses instead of the reader noticing.

    NO OVERRIDE FLAG, because a supported escape already exists and is honest: delete
    names.json. `dump_names.find` returns None, items.csv covers what it covers, and the
    graph is then built from sources that are what they claim to be. A `--yes-really` would
    only be a way to build a graph from a file known to be damaged.

    Silent -- returns None -- when the dump predates schema 6 (`names` is None) or there is
    no names.json to compare (`on_disk` is None). Neither is evidence of damage.
    """
    declared = meta.get("names")
    if declared is None or on_disk is None or declared == on_disk:
        return
    raise DamagedDump(
        "names.json holds %d entries but summary.json says the dump wrote %d. That is a "
        "damaged dump directory, not an old one -- an interrupted write, a partial copy or "
        "a hand edit. Re-run /recipedump, re-copy the dump, or delete names.json to build "
        "without it (items.csv still covers the undiscriminated keys)."
        % (on_disk, declared))


#: The `build` flag that gets past `check_mod_set`. Spelled here as well as in `cli` so the
#: refusal can name it; a message that says "pass the flag" without saying which one sends
#: the reader to `--help` at the moment they are already surprised.
#:
#: NAMED AS A WHOLE `recipegraph build` COMMAND IN THE MESSAGE, not as a bare flag, because
#: the refusal also fires from `plan` and `serve` through `ensure_graph`'s implicit rebuild
#: -- and those two have no such flag. "Pass --allow-mod-set-change" is unactionable advice
#: from a `plan` prompt; "run `recipegraph build --allow-mod-set-change`" works everywhere.
OVERRIDE_FLAG = "--allow-mod-set-change"


def check_mod_set(meta, graph_count, graph_digest):
    """Raise `WrongPack` when this dump did not come from the pack the graph on disk did.

    THE FAILURE THIS EXISTS FOR, stated concretely. A dump from five jars produces a graph,
    and prints `dump: written by mod 0.9.11, schema 6` -- a line identical IN FORM to the one
    a 410-jar dump prints. The contents cannot settle it either: a client-only mod that
    registers no JEI category is invisible in the output. So the small graph silently
    replaces the large one at the same path, and every downstream consumer trusts it.

    IT REFUSES RATHER THAN WARNING, on the team's explicit instruction and for the reason
    `RefusedBuild` records. The graph is the cheap artifact here, but it is the only thing
    standing between a wrong dump and every plan priced from it.

    THE OVERRIDE IS A FLAG AND NOT AN ENVIRONMENT VARIABLE, so that replacing a 410-jar graph
    with a six-jar one is a thing someone typed on the line that did it, findable in a shell
    history when the plans come out wrong.

    Silent whenever it cannot compare: a dump older than schema 6 (`mod_digest` is None), no
    graph at the output path yet, or a graph built before #194 recorded the digest. All three
    are absence of evidence, and refusing on them would refuse the first build after this
    change lands -- which is every build.
    """
    digest = meta.get("mod_digest")
    if digest is None or graph_digest is None or digest == graph_digest:
        return
    raise WrongPack(
        "the graph already at this path was built from a DIFFERENT set of mods (%s there, "
        "%s in this dump). One of the two is not the pack you meant, and overwriting hides "
        "which: a dump from a smaller jar set produces a graph that looks entirely normal "
        "and is missing whole mods. Check which dump you are pointing at, or run "
        "`recipegraph build %s` if replacing it is deliberate."
        % (mods_phrase(graph_count), mods_phrase(meta.get("mod_count")), OVERRIDE_FLAG))


def mods_phrase(count):
    """`410 mods`, or an honest non-answer for a graph or dump that did not record one."""
    return "an unrecorded number of mods" if count is None else "%d mods" % count


def _lost_names(meta):
    """The clause `describe` carries when the dump could not read some display names.

    APPENDED TO THE PROVENANCE LINE RATHER THAN PRINTED BESIDE IT, because that line is the
    one sentence every surface already shows -- `build` prints it, and the server prints it
    from `of_graph` for a graph built weeks ago. A loss recorded only where the build
    happened is a loss nobody sees again. Empty at zero and empty when unknown, so a healthy
    dump keeps the sentence it has always had.
    """
    failed = meta.get("names_failed")
    if not failed:
        return ""
    return (" -- %d item%s in it could NOT be named (getDisplayName threw) and show%s as "
            "raw ids" % (failed, "" if failed == 1 else "s", "s" if failed == 1 else ""))


def describe(meta):
    """A one-line provenance note, or a warning when the schema is not the expected one."""
    if not meta["present"]:
        return ("dump provenance: unknown (no summary.json) -- if files are missing, "
                "re-run /recipedump with the current mod")
    version = meta["mod_version"] or "pre-0.4.2 (unstamped)"
    if meta["schema"] == SCHEMA:
        # THE JAR COUNT IS IN THE CURRENT-SCHEMA SENTENCE AND NOWHERE ELSE, because it is
        # the one branch that can print a number it actually has. It is also the branch that
        # used to be the problem: "written by mod 0.9.11, schema 6" was identical whether
        # five jars or 410 produced it, and this is the word that separates them. #194
        return "dump: written by mod %s, schema %d, from %s%s" % (
            version, SCHEMA, mods_phrase(meta.get("mod_count")), _lost_names(meta))
    if meta["schema"] is not None and meta["schema"] < SCHEMA:
        # Two different severities wear the same sentence otherwise. Missing a field costs
        # a feature and the graph is still correct; predating the digest format means every
        # discriminated key in this graph is one the current reader would never compute, so
        # AE2 stock cannot match it. Naming which one it is here is the difference between
        # "worth doing sometime" and "your stock reads as zero until you redump".
        if meta["schema"] < DIGEST_FORMAT_SCHEMA:
            return ("dump: written by mod %s at schema %s, OLDER THAN THE DIGEST FORMAT (%d)"
                    " -- every discriminated key in it predates the current digest, so AE2"
                    " stock cannot match one; re-run /recipedump, then `build`, then `have`"
                    % (version, meta["schema"], DIGEST_FORMAT_SCHEMA))
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
            "present": schema is not None,
            # CARRIED ON THE GRAPH, not recomputed: the dump directory is gone by the time
            # the server runs, and #194's point is that the loss must survive in the
            # artifact rather than in the log of the build that noticed it.
            "names_failed": getattr(graph, "dump_names_failed", None),
            # `names` is a check against a file the graph no longer has beside it, so a
            # graph cannot answer it and must not appear to. `check_names` reads None as
            # "nothing to compare", which is exactly right here.
            "names": None,
            # Carried for the same reason as the loss above: the server's footer is where
            # someone finds out their graph came from six jars, and by then there is no
            # summary.json within reach to ask.
            "mod_count": getattr(graph, "dump_mod_count", None),
            "mod_digest": getattr(graph, "dump_mod_digest", None)}
