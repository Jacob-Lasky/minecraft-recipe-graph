"""The golden plan fixtures, which are the Java port's correctness proof (#19 phase 1).

WHAT BREAKS WITHOUT THIS. `tests/fixtures/plan/*.json` is a contract between two languages,
and the Python side is the one that moves: a scoring term changes, a constant is retuned, a
recipe filter is tightened, and the fixtures on disk quietly stop describing what this
implementation does. Nothing fails. The Java suite goes on passing against a snapshot of an
implementation that no longer exists, which is worse than having no fixtures at all --
`DigestFixtureTest`'s own docstring makes the same point about editing an expected value.

TWO LAYERS, AND THE FAST ONE IS NOT THE WEAK ONE.

  * Always on, no graph needed. The claims each fixture makes about itself are RE-PROVED
    against the stored result, the constants recorded in `cost.json` are compared with the
    live `cost` module, and the files are checked for being exactly what the generator
    writes. That catches a hand edit, a stale file, a renamed target and a cost constant
    moving without a regeneration -- in milliseconds, on a checkout with no data/ at all.
  * Oracle-gated. Regenerating means loading a 125 MB graph and running `cost.estimate`
    once per priced scenario, which is about two minutes each. SKIPPED unless
    $RECIPEGRAPH_ORACLE names a readable graph, because the oracle is not in git and CI
    will never have it -- and because an always-on version would take the suite from 17
    seconds to five and a half minutes, which is how a suite stops being run.

DO NOT "FIX" A FAILURE HERE BY EDITING A FIXTURE. Regenerate, read the diff, and decide
whether the behaviour change was intended; the diff IS the change to the port's contract.
"""

import importlib.util
import json
import math
import os
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from recipegraph import api as api_mod  # noqa: E402
from recipegraph import cost as cost_mod  # noqa: E402

import fixtures  # noqa: E402

# Loaded through importlib because `tools/` is not a package and the filename is hyphenated;
# `tests/test_entry_census.py` reaches `tools/entry-census.py` the same way. Importing the
# generator rather than restating its target list is the point: a target added there with no
# regeneration fails `TheFilesOnDiskAreTheDeclaredSetTest` instead of being invisible.
_PATH = os.path.join(ROOT, "tools", "make-java-fixtures.py")
_spec = importlib.util.spec_from_file_location("make_java_fixtures", _PATH)
maker = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(maker)

FIXTURE_DIR = os.path.join(ROOT, "tests", "fixtures", "plan")

REGENERATE = maker.REGENERATE_HINT

# The behaviours #19 names as the spread the fixtures have to cover. Asserted over the whole
# SET rather than per file, because which target happens to exercise a cycle is an accident
# of the pack's data and may legitimately move; the set losing cycles altogether is not.
REQUIRED_COVERAGE = ("have", "partial", "from_stock", "craft", "craftable", "raw", "cycle",
                     "depth", "truncated", "token", "tokens_needed", "source",
                     "from_sources", "oredict", "alternatives", "fluid", "dimension",
                     "machine", "machine_have", "machine_to_build", "pinned",
                     "emc", "from_emc", "unsourced")


def fixture_names():
    return sorted(n for n in os.listdir(FIXTURE_DIR) if n.endswith(".json"))


def load(name):
    with open(os.path.join(FIXTURE_DIR, name)) as fh:
        return fh.read()


class TheFilesOnDiskAreTheDeclaredSetTest(unittest.TestCase):
    """A renamed or deleted target must not leave its fixture behind for Java to assert."""

    def test_every_target_has_a_fixture_and_every_fixture_has_a_target(self):
        want = set("plan-%s.json" % t.name for t in maker.TARGETS)
        want |= {"cost.json", "machines.json", "machines-overridden.json"}
        self.assertEqual(set(fixture_names()), want, REGENERATE)

    def test_no_two_targets_share_a_name(self):
        # They would share a filename, so one would silently overwrite the other and the
        # set would still look complete.
        names = [t.name for t in maker.TARGETS]
        self.assertEqual(len(names), len(set(names)))


class EveryFixtureIsExactlyWhatTheGeneratorWritesTest(unittest.TestCase):
    """Round-tripping the file through `maker.dump` must be a no-op.

    A hand edit is the failure this is for, and it does not have to be a wrong VALUE to do
    damage: reordering a key or reflowing the indentation makes the oracle-gated comparison
    below fail for a reason that has nothing to do with behaviour, and then the temptation
    is to relax that comparison. Pinning the canonical form here keeps the byte comparison
    honest.
    """

    def test_each_file_is_in_canonical_form(self):
        for name in fixture_names():
            text = load(name)
            self.assertEqual(text, maker.dump(json.loads(text)),
                             "%s is not canonical JSON; %s" % (name, REGENERATE))

    def test_each_file_carries_the_do_not_edit_banner(self):
        for name in fixture_names():
            doc = json.loads(load(name))
            self.assertEqual(doc.get("_"), maker.BANNER, name)
            self.assertTrue(doc.get("why"), "%s has no stated purpose" % name)

    def test_every_fixture_names_the_same_oracle(self):
        # Two fixtures generated against different graphs would be internally consistent
        # and jointly meaningless, and a half-finished regeneration is exactly how that
        # happens.
        ids = {name: json.loads(load(name))["graph"] for name in fixture_names()}
        first = ids[fixture_names()[0]]
        for name, got in ids.items():
            self.assertEqual(got, first, "%s was generated against another graph" % name)
        self.assertTrue(first["dimension_ores"],
                        "the oracle had no dimension_ores, so it predates #112/#117")
        # `.get`, NOT `[...]`, unlike the line above. `dimension_ores` is in every fixture on
        # disk so a subscript there can only fail the way the message describes; this key is
        # NEW, and the state it will actually be met in is a fixture set written before #248 --
        # where a subscript raises KeyError and the reader gets a crash instead of being told
        # to regenerate.
        self.assertTrue(first.get("offworld_ores"),
                        "the oracle had no offworld_ores, so it predates #248 and every "
                        "iron ore in it ties at BASE_RAW_COST; %s" % REGENERATE)


class EveryClaimAFixtureMakesIsStillTrueOfItTest(unittest.TestCase):
    """Re-run the generator's own coverage checks against the STORED plan.

    The value of a fixture is entirely in the code path it covers, and a fixture can stop
    covering it while looking identical -- a repricing moves the chosen route and the cyclic
    recipe is no longer chosen. The generator checks this at write time; doing it again here
    means the check survives in the repository rather than only in the run that produced the
    file, and it needs no graph.
    """

    def test_every_covers_tag_is_a_real_check(self):
        for name in fixture_names():
            for tag in json.loads(load(name)).get("covers") or ():
                self.assertIn(maker.check_name(tag), maker.CHECKS,
                              "%s claims unknown tag %r" % (name, tag))

    def test_every_covers_tag_holds_against_the_stored_result(self):
        for name in fixture_names():
            doc = json.loads(load(name))
            if "result" not in doc:
                continue
            for tag in doc["covers"]:
                self.assertTrue(maker.holds(tag, doc["result"]),
                                "%s claims %r and the stored plan does not bear it out"
                                % (name, tag))

    def test_a_negated_claim_means_the_opposite_of_the_plain_one(self):
        # The control fixtures rest entirely on this, so it gets its own assertion rather
        # than being inferred from them passing.
        empty = {"tree": {"status": "raw"}, "from_emc": []}
        self.assertFalse(maker.holds("emc", empty))
        self.assertTrue(maker.holds("!emc", empty))
        self.assertEqual(maker.check_name("!emc"), "emc")
        self.assertEqual(maker.check_name("emc"), "emc")

    def test_every_required_behaviour_is_a_check_that_exists(self):
        # A typo here would report the behaviour as missing forever, which reads as a
        # coverage regression and is a typo. Separating the two failures separates the two
        # fixes.
        for tag in REQUIRED_COVERAGE:
            self.assertIn(tag, maker.CHECKS, tag)

    def test_no_check_is_dead(self):
        # A check nothing claims proves nothing and rots: the pack's data moves, the tag
        # stops being reachable, and nobody finds out because nothing asserts it. Either a
        # target claims it or it should not be in the table.
        claimed = set()
        for name in fixture_names():
            claimed.update(maker.check_name(t)
                           for t in json.loads(load(name)).get("covers") or ())
        self.assertFalse(sorted(set(maker.CHECKS) - claimed),
                         "no fixture claims these checks, so they assert nothing")

    def test_the_set_covers_everything_the_port_has_to_reproduce(self):
        seen = set()
        for name in fixture_names():
            seen.update(maker.check_name(t)
                        for t in json.loads(load(name)).get("covers") or ())
        missing = sorted(set(REQUIRED_COVERAGE) - seen)
        self.assertFalse(missing, "no fixture exercises %s any more" % missing)

    def test_each_plan_records_the_scenario_its_target_declares(self):
        # The Java side builds its solver from the fixture's own `scenario` block, so a
        # fixture whose scenario has drifted from the generator's is one that cannot be
        # regenerated into itself.
        by_name = {t.name: t for t in maker.TARGETS}
        for name in fixture_names():
            doc = json.loads(load(name))
            if not name.startswith("plan-"):
                continue
            target = by_name[name[len("plan-"):-len(".json")]]
            self.assertEqual(doc["scenario"], target.scenario, name)
            self.assertEqual(doc["request"], {"item": target.item, "qty": target.qty,
                                              "max_nodes": target.max_nodes}, name)


class TheCostConstantsHaveNotMovedUnderTheFixturesTest(unittest.TestCase):
    """`cost.json` records what priced it, so a retune cannot pass unnoticed.

    THIS IS THE TRIPWIRE, and it is the reason the cost fixture records its inputs at all. A
    change to `BASE_RAW_COST` or the machine band reprices the whole graph and moves plans
    the fixtures do not contain, so nothing else in a stdlib suite would notice; the Java
    port would then be asserting against prices this implementation no longer produces.
    Failing here is correct even though it is inconvenient -- the fix is a regeneration, not
    a smaller assertion.
    """

    def setUp(self):
        self.doc = json.loads(load("cost.json"))

    def test_the_recorded_constants_are_the_live_ones(self):
        for name, value in sorted(self.doc["constants"].items()):
            self.assertEqual(value, getattr(cost_mod, name),
                             "cost.%s moved; %s" % (name, REGENERATE))

    def test_the_recorded_machine_band_is_the_live_one(self):
        self.assertEqual(self.doc["machine_cost"], cost_mod.MACHINE_COST, REGENERATE)

    def test_the_recorded_formula_version_is_the_live_one(self):
        self.assertEqual(self.doc["formula_version"], cost_mod.FORMULA_VERSION, REGENERATE)

    def test_every_pinned_constant_still_exists(self):
        # A renamed constant would otherwise drop out of the recorded set silently, taking
        # its tripwire with it.
        for name in maker.PINNED_CONSTANTS:
            self.assertTrue(hasattr(cost_mod, name), name)
            self.assertIn(name, self.doc["constants"], "%s; %s" % (name, REGENERATE))

    def test_every_cost_constant_is_pinned_or_explicitly_exempt(self):
        """The other direction, and the one that was missing until #176.

        `test_every_pinned_constant_still_exists` catches a constant being RENAMED out of the
        recorded set. Nothing caught one being ADDED and never entering it, so the tripwire
        this class exists to be simply did not cover it -- measured, not hypothetical:
        `EMC_COST` had been unpinned since it was introduced, and #176's `UNSOURCED_COST`
        would have been the second.

        A NEW CONSTANT NOW FAILS HERE UNTIL SOMEONE DECIDES, which is the point. Pin it, or
        name it in `maker.NOT_PINNED` with a reason. Both are cheap; neither is silent.
        """
        live = set(name for name in dir(cost_mod)
                   if name.isupper() and not name.startswith("_")
                   and isinstance(getattr(cost_mod, name), (int, float))
                   and not isinstance(getattr(cost_mod, name), bool))
        unaccounted = live - set(maker.PINNED_CONSTANTS) - maker.NOT_PINNED
        self.assertEqual(
            set(), unaccounted,
            "cost.py declares %s, which is neither in PINNED_CONSTANTS nor in NOT_PINNED. "
            "A constant nothing records can move without any fixture changing, which is the "
            "one failure this file exists to prevent." % sorted(unaccounted))

    def test_nothing_is_both_pinned_and_exempt(self):
        # The two lists disagreeing would make the exemption a lie: the constant would be
        # recorded anyway and the reason beside it would describe a decision nobody took.
        self.assertEqual(set(), set(maker.PINNED_CONSTANTS) & maker.NOT_PINNED)

    def test_every_exempt_constant_still_exists(self):
        # Same rot as a renamed pinned constant, one list over: a stale exemption silently
        # widens what the test above accepts.
        for name in sorted(maker.NOT_PINNED):
            self.assertTrue(hasattr(cost_mod, name),
                            "%s is exempt from pinning but no longer exists" % name)

    def test_every_entry_cost_lands_inside_the_band(self):
        # The census is the shape a flattening makes, and "OUTSIDE THE BAND" is the shape a
        # broken derivation makes. Neither is visible in any single price.
        self.assertNotIn("OUTSIDE THE BAND", self.doc["region_census"])
        self.assertEqual(sum(self.doc["region_census"].values()),
                         len(self.doc["machine_entry"]))


class AnOverrideIsDistinguishableFromEvidenceTest(unittest.TestCase):
    """`machines-overridden.json`, the delta, against `machines.json`, the control.

    WHY THIS NEEDS ITS OWN TEST. A plan node carries `machine_state`, and `machine_why` ONLY
    when the state is not `have` -- so once an override lands, a plan tree cannot say whether
    the machine is owned, overridden, or declared to need no machine. All three read
    identically. `machines.describe` keeps them apart in the evidence on purpose, and a port
    is free to collapse them into one boolean unless something asserts otherwise.
    """

    def setUp(self):
        self.plain = json.loads(load("machines.json"))
        self.delta = json.loads(load("machines-overridden.json"))
        self.changed = self.delta["changed"]

    def test_exactly_the_declared_categories_moved(self):
        # The delta IS the claim, so a third category appearing here is the finding, not a
        # detail: `describe` would have stopped being the pure function it is documented as.
        want = set(maker.OVERRIDDEN["machine_overrides"]) | set(
            maker.OVERRIDDEN["no_machine"])
        self.assertEqual(set(self.changed), want, REGENERATE)

    def test_the_control_has_both_categories_buildable_on_evidence(self):
        for uid in self.changed:
            self.assertEqual(self.plain["categories"][uid]["state"], "buildable", uid)
            self.assertEqual(self.changed[uid]["from"], self.plain["categories"][uid], uid)

    def test_a_manual_override_says_it_is_manual(self):
        for uid, state in maker.OVERRIDDEN["machine_overrides"].items():
            got = self.changed[uid]["to"]
            self.assertEqual(got["state"], state, uid)
            self.assertEqual(got["why"], "manual override", uid)
            self.assertTrue(got["manual"], uid)

    def test_a_no_machine_declaration_says_something_else_entirely(self):
        for uid in maker.OVERRIDDEN["no_machine"]:
            got = self.changed[uid]["to"]
            self.assertEqual(got["state"], "have", uid)
            self.assertIn("no machine needed", got["why"], uid)
            # NOT `manual`: that flag means "the user set a state", and a `no_machine` entry
            # is a claim about how the thing is produced. Collapsing them would make the
            # machines page offer to un-set something it cannot un-set.
            self.assertFalse(got["manual"], uid)
            self.assertNotEqual(got["why"], "manual override", uid)

    def test_a_category_that_stops_being_buildable_stops_being_priced_as_one(self):
        # Both categories were `buildable`, so both must leave `build_targets`; a machine
        # you own or that does not exist is not priced from a machine item.
        self.assertEqual(set(self.delta["build_targets_removed"]), set(self.changed))
        self.assertEqual(self.delta["build_targets_added"], [])

    def test_the_summary_moves_by_exactly_the_two_categories(self):
        before = self.plain["summary"]
        after = self.delta["summary"]
        self.assertEqual(after["have"], before["have"] + len(self.changed))
        self.assertEqual(after["buildable"], before["buildable"] - len(self.changed))
        self.assertEqual(self.delta["categories"], len(self.plain["categories"]))


class ThePureHelpersBehaveTest(unittest.TestCase):
    """The generator's own logic, on a graph small enough to build in a millisecond.

    Everything here is what the oracle-gated test cannot check quickly and what a reader
    would otherwise have to take on trust: the cross-language float format, the canonical
    serialisation, and every scenario-validation refusal. The refusals matter most -- each
    one exists because the failure it prevents is SILENT, so a broken refusal restores the
    silence and nothing else would notice.
    """

    def setUp(self):
        self.graph = fixtures.discriminated_graph()
        self.key = self.graph.recipes[0].outputs[0][0]
        self.category = self.graph.recipes[0].category

    def test_a_cost_formats_the_same_way_in_both_languages(self):
        # `%.17e` is the claim `fmt_cost` makes and the reason the digest can match at all.
        # Java's String.format writes these identically, including the two-digit exponent;
        # `repr` does not -- measured, 33% of doubles differ as TEXT between the languages
        # and 0% differ in value -- which is why this is not repr.
        self.assertEqual(maker.fmt_cost(1.0), "1.00000000000000000e+00")
        self.assertEqual(maker.fmt_cost(1e-30), "1.00000000000000008e-30")
        self.assertEqual(maker.fmt_cost(1e-5), "1.00000000000000008e-05")
        self.assertEqual(maker.fmt_cost(5000.0), "5.00000000000000000e+03")

    def test_the_digest_format_can_tell_two_adjacent_doubles_apart(self):
        # THE REASON IT IS 17 DIGITS. At `%.12e` these two hash identically, so a port whose
        # arithmetic drifted by one ULP passed the only check that covers the whole table.
        # 17 significant digits is injective over binary64.
        a = 1.0
        b = math.nextafter(a, 2.0) if hasattr(math, "nextafter") else 1.0000000000000002
        self.assertNotEqual(a, b)
        self.assertNotEqual(maker.fmt_cost(a), maker.fmt_cost(b))
        self.assertNotEqual(maker.cost_digest({"k": a}), maker.cost_digest({"k": b}))

    def test_an_unpriced_key_digests_as_a_word_rather_than_a_number(self):
        # `%.12e` of infinity is "inf" in Python and "Infinity" in Java, so the one value
        # that cannot be formatted is the one spelled out.
        self.assertEqual(maker.fmt_cost(float("inf")), "inf")

    def test_the_digest_is_order_independent_and_content_dependent(self):
        a = maker.cost_digest({"b": 2.0, "a": 1.0})
        self.assertEqual(a, maker.cost_digest({"a": 1.0, "b": 2.0}))
        self.assertNotEqual(a, maker.cost_digest({"a": 1.0, "b": 2.5}))
        self.assertNotEqual(a, maker.cost_digest({"a": 1.0, "b": float("inf")}))

    def test_dump_flattens_an_unpriced_key_the_way_the_api_does(self):
        # Not merely "does not crash": the fixture and `/api/cost` have to spell an
        # unpriced key the same way, or a port reads one as null and the other as absent.
        text = maker.dump({"cost": {"x": float("inf")}})
        self.assertEqual(json.loads(text)["cost"]["x"], None)
        self.assertEqual(json.loads(text), json.loads(api_mod.dumps({
            "cost": {"x": float("inf")}})))

    def test_dump_never_rounds_a_float(self):
        # A rounded cost silently lets a drifting port pass, which is the one thing these
        # fixtures exist to prevent. `json.dumps` emits `repr`, the shortest string that
        # round-trips, so the written value parses back bit-identical in either language.
        value = 0.1 + 0.2
        text = maker.dump({"cost": value})
        self.assertIn("0.30000000000000004", text)
        self.assertEqual(json.loads(text)["cost"], value)

    def test_dump_is_idempotent(self):
        doc = {"b": [1, {"a": 2}], "a": "x"}
        self.assertEqual(maker.dump(doc), maker.dump(json.loads(maker.dump(doc))))

    def test_a_scenario_is_bare_plus_the_differences(self):
        sc = maker.scenario(have={"mod:ingot": 1})
        self.assertEqual(sc["have"], {"mod:ingot": 1})
        self.assertEqual(sc["pins"], {})
        self.assertEqual(set(sc), set(maker.BARE))

    def test_a_scenario_cannot_invent_a_field(self):
        # Silent otherwise: an unrecognised field would ride into the fixture, be echoed to
        # the Java side, and be read by nothing.
        with self.assertRaises(SystemExit):
            maker.scenario(hav={"mod:ingot": 1})

    def test_the_cost_signature_ignores_what_cost_never_sees(self):
        # `pins` do not reach `cost.estimate`, so two scenarios differing only in them must
        # share one cost table -- otherwise `pin-lapsed` pays for a second two-minute
        # relaxation identical to the first.
        #
        # `craftables` USED TO BE NAMED HERE and moved to the test below in #193, which is the
        # whole reason `cost_signature` keys on what `estimate` is handed rather than on a list
        # of field names: the field changed side, and a hand-maintained partition would have
        # gone on quietly sharing the bare table with the stocked one.
        base = maker.derive_inputs(self.graph, maker.BARE)
        pinned = maker.derive_inputs(self.graph, maker.scenario(
            pins={self.key: {"fingerprint": "0" * 16}}))
        self.assertEqual(maker.cost_signature(base), maker.cost_signature(pinned))

    def test_the_cost_signature_separates_what_cost_does_see(self):
        # The failure this prevents is silent: a scenario sharing a table computed for a
        # different one is priced for a configuration nobody asked about, and every plan in
        # it looks perfectly reasonable.
        base = maker.derive_inputs(self.graph, maker.BARE)
        for sc in (maker.scenario(have={self.key: 1}),
                   maker.scenario(machine_overrides={self.category: "have"}),
                   # #193's two. Both are per-inventory terminals that now set a price, so a
                   # scenario differing only in one of them is a different table.
                   maker.scenario(craftables=[self.key]),
                   maker.scenario(raw=[self.key])):
            self.assertNotEqual(maker.cost_signature(base),
                                maker.cost_signature(maker.derive_inputs(self.graph, sc)),
                                sc)

    def test_the_two_declared_terminals_do_not_share_a_signature(self):
        # `craftables` and `raw` price DIFFERENTLY -- one request against a thing to go and
        # gather -- so a key moving from one set to the other has to move the signature. A
        # single merged set of "declared terminals" would collapse them and serve the
        # craftable table to a scenario that declared a stop.
        craftable = maker.derive_inputs(self.graph, maker.scenario(craftables=[self.key]))
        stopped = maker.derive_inputs(self.graph, maker.scenario(raw=[self.key]))
        self.assertNotEqual(maker.cost_signature(craftable), maker.cost_signature(stopped))

    def test_a_scenario_field_that_resolves_to_nothing_shares_the_table(self):
        # `visited_dimensions` is a PRICING field in general, and on this graph it prices
        # nothing at all: the fixture graph declares no `dimension_ores`, so `gates_for`
        # returns {} whatever it is given. A key made of scenario FIELD NAMES would compute
        # a second identical two-minute relaxation here; keying on the derived inputs sees
        # that there is nothing to separate. That sharpness is the reason for the design,
        # and this pins it rather than leaving it as an accident.
        base = maker.derive_inputs(self.graph, maker.BARE)
        nowhere = maker.derive_inputs(self.graph, maker.scenario(visited_dimensions={}))
        self.assertFalse(self.graph.dimension_ores)
        self.assertEqual(nowhere["gates"], {})
        self.assertEqual(maker.cost_signature(base), maker.cost_signature(nowhere))

    def test_knowledge_that_buys_nothing_shares_the_bare_cost_table(self):
        # The sharper half of keying on the DERIVED inputs rather than on scenario field
        # names: learning a key the graph gives no EMC value resolves to an empty available
        # set, which genuinely is the bare table. A field-name key could never see that.
        base = maker.derive_inputs(self.graph, maker.BARE)
        unvalued = maker.derive_inputs(self.graph, maker.scenario(
            emc_knowledge={"learned": [self.key], "emc": 10, "full": False}))
        self.assertFalse(unvalued["emc_available"])
        self.assertEqual(maker.cost_signature(base), maker.cost_signature(unvalued))

    def test_learning_a_key_the_graph_lacks_is_refused(self):
        # A typo here resolves to nothing available, so the EMC fixture terminates nowhere
        # and quietly freezes the pre-#50 behaviour while claiming the opposite.
        with self.assertRaises(SystemExit):
            maker._check_scenario(self.graph, maker.scenario(
                emc_knowledge={"learned": ["mod:nope"], "emc": 1, "full": False}))

    def test_learning_a_key_with_no_emc_value_is_allowed(self):
        # Deliberately NOT refused: `emc-unvalued` learns a valueless key on purpose,
        # because "learned AND positive value" is the clause it tests.
        maker._check_scenario(self.graph, maker.scenario(
            emc_knowledge={"learned": [self.key], "emc": 1, "full": False}))

    def test_stock_for_a_key_the_graph_lacks_is_refused(self):
        with self.assertRaises(SystemExit):
            maker._check_scenario(self.graph, maker.scenario(have={"mod:nope": 1}))

    def test_a_craftable_the_graph_lacks_is_refused(self):
        with self.assertRaises(SystemExit):
            maker._check_scenario(self.graph, maker.scenario(craftables=["mod:nope"]))

    def test_a_placed_block_that_generates_nothing_is_refused(self):
        with self.assertRaises(SystemExit):
            maker._check_scenario(self.graph, maker.scenario(placed={"mod:rock": 1}))

    def test_an_override_naming_no_category_is_refused(self):
        with self.assertRaises(SystemExit):
            maker._check_scenario(
                self.graph, maker.scenario(machine_overrides={"mod:nope": "have"}))

    def test_an_override_with_a_state_that_is_not_a_state_is_refused(self):
        category = self.graph.recipes[0].category
        with self.assertRaises(SystemExit):
            maker._check_scenario(
                self.graph, maker.scenario(machine_overrides={category: "owned"}))

    def test_a_no_machine_entry_naming_no_category_is_refused(self):
        with self.assertRaises(SystemExit):
            maker._check_scenario(self.graph, maker.scenario(no_machine=["mod:nope"]))

    def test_a_scenario_that_names_real_things_is_accepted(self):
        key = self.graph.recipes[0].outputs[0][0]
        category = self.graph.recipes[0].category
        maker._check_scenario(self.graph, maker.scenario(
            have={key: 1}, craftables=[key], machine_overrides={category: "have"},
            no_machine=[category]))

    def test_the_sampled_prices_are_the_audit_s_own_list(self):
        # `tools/cost-probe.py` owns which eighteen items a human can judge; this asserts
        # the generator is reading that list rather than having grown a second one.
        path = os.path.join(ROOT, "tools", "cost-probe.py")
        spec = importlib.util.spec_from_file_location("cost_probe_check", path)
        probe = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(probe)
        self.assertEqual(maker.COST_PROBE_ITEMS, [k for k, _label in probe.PROBES])
        self.assertTrue(maker.COST_PROBE_ITEMS)


class TheSampledPricesAreReadableNumbersTest(unittest.TestCase):
    """`cost.json`'s sample is compared as JSON doubles, so it must survive a round trip."""

    def setUp(self):
        self.doc = json.loads(load("cost.json"))

    def test_every_sampled_item_is_present(self):
        for key in maker.COST_PROBE_ITEMS:
            self.assertIn(key, self.doc["sample"], "%s; %s" % (key, REGENERATE))

    def test_every_plan_target_is_sampled(self):
        for target in maker.TARGETS:
            self.assertIn(target.item, self.doc["sample"],
                          "%s; %s" % (target.item, REGENERATE))

    def test_a_sampled_price_is_a_finite_number_or_an_explicit_null(self):
        # `null` is how an unpriced key is spelled; NaN or a bare Infinity would mean the
        # flattening was skipped and gson would refuse the file outright.
        for key, value in self.doc["sample"].items():
            if value is None:
                continue
            self.assertIsInstance(value, float, key)
            self.assertTrue(math.isfinite(value), key)


@unittest.skipUnless(os.environ.get(maker.ORACLE_ENV)
                     and os.path.exists(os.environ.get(maker.ORACLE_ENV, "")),
                     "$%s does not name a readable oracle graph" % maker.ORACLE_ENV)
class TheFixturesStillMatchTheImplementationTest(unittest.TestCase):
    """Regenerate from the oracle and require byte equality. The real guard.

    Slow by nature -- see the module docstring -- and gated on the environment variable
    rather than on a default path, so that whether it runs is a decision rather than a
    property of which machine the suite happens to be on.
    """

    def test_regenerating_changes_nothing(self):
        path = os.environ[maker.ORACLE_ENV]
        docs = maker.generate(path)
        stored = {name: load(name) for name in fixture_names()}
        for name, doc in docs.items():
            want = maker.dump(doc)
            got = stored.get(name)
            if got == want:
                continue
            # THE GRAPH COMPARISON FIRST. "The solver changed" and "the oracle moved"
            # produce the same diff, and only one of them is a bug; a bare "these bytes
            # differ" sends the reader looking in the wrong place.
            theirs = json.loads(got)["graph"] if got else None
            self.fail("%s differs.\n  oracle now: %s\n  fixtures were built against: %s\n"
                      "  If the graph moved, that alone explains it. If it did not, this "
                      "is a behaviour change and %s."
                      % (name, docs[name]["graph"], theirs, REGENERATE))
        self.assertEqual(sorted(docs), sorted(stored))


if __name__ == "__main__":
    unittest.main()
