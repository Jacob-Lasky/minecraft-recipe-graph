"""The read-only JSON surface, over a real socket. See #108.

Uses `test_server.LiveServerCase`, which was extracted for exactly this: a second suite
against a DIFFERENT graph without copying the harness. The graph here is shaped like the
investigations that motivated the endpoints -- a nugget/ingot/dust ladder with a
producerless bottom rung (#106), a fluid named after the can it is bottled in (#103), an
oredict, and stock -- so a test failing says which real question stopped working.
"""

import json
import os
import sys
import unittest
import urllib.parse

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from test_server import LiveServerCase  # noqa: E402

from recipegraph import api  # noqa: E402
from recipegraph.model import Graph, Ingredient, Recipe  # noqa: E402


def plan_json(key, qty=1):
    return "/plan?%s" % urllib.parse.urlencode({"item": key, "qty": qty, "fmt": "json"})


def api_graph():
    g = Graph()
    g.names = {
        "mod:sednanite_nugget": "Sednanite Nugget",
        "mod:sednanite_ingot": "Sednanite Ingot",
        "mod:sednanite_dust": "Sednanite Dust",
        "mod:press": "Press",
        "forestry:can:1": "Can",
        "forestry:can:1#aa": "Molten Sednanite Can",
        "other:widget": "Widget",
    }
    g.ore_members = {"ingotSednanite": ["mod:sednanite_ingot"]}
    # The denomination ladder. `nugget` has no producer, which is what makes it the only
    # rung a solver can terminate on, and what a sweep has to be able to find.
    g.add(Recipe("nugget_to_ingot", "t", [("mod:sednanite_ingot", 1)],
                 [Ingredient(["mod:sednanite_nugget"], 9)], category="minecraft.crafting"))
    g.add(Recipe("dust_to_ingot", "t", [("mod:sednanite_ingot", 1)],
                 [Ingredient(["mod:sednanite_dust"], 1)],
                 category="mod.press", machine="Press"))
    # Takes the ORE entry rather than the ingot key, so `consumers` has to reach the ingot
    # through its oredict group the way the real graph does.
    g.add(Recipe("ingot_to_dust", "t", [("mod:sednanite_dust", 2)],
                 [Ingredient(["ore:ingotSednanite"], 1)],
                 category="mod.press", machine="Press"))
    # Emptying a can is a TRANSFER: it moves the fluid, it does not make it. `producers`
    # must not count it while `all_producers` must, which is the distinction that stops a
    # sweep for unmakeable fluids reporting every bottled one as makeable.
    g.add(Recipe("empty_can", "t",
                 [("forestry:can:1", 1), ("fluid:molten_sednanite", 1000)],
                 [Ingredient(["forestry:can:1#aa"], 1)],
                 category="transposer", transfer=True))
    g.add(Recipe("widget", "t", [("other:widget", 1)],
                 [Ingredient(["fluid:molten_sednanite"], 500, "fluid")],
                 category="mod.press", machine="Press"))
    # #171. Two pack-authored keys nothing produces, identical to every structural rule and
    # different in the one way that matters: the pack says where one of them comes from.
    # The sweep has to be able to tell them apart, because that is the difference between
    # "go and solve its puzzle" and "the tool cannot help you".
    g.names["contenttweaker:from_a_puzzle"] = "Puzzle Reward"
    g.names["contenttweaker:from_nowhere"] = "Marker"
    for rid, ingredient in (("uses_puzzle", "contenttweaker:from_a_puzzle"),
                            ("uses_marker", "contenttweaker:from_nowhere")):
        g.add(Recipe(rid, "t", [("other:widget", 1)], [Ingredient([ingredient], 1)],
                     category="minecraft.crafting"))
    g.declared_provenance = {"contenttweaker:from_a_puzzle": "puzzle"}
    return g


class ApiCase(LiveServerCase):
    HAVE = {"items": {"mod:sednanite_nugget": 18}}

    @staticmethod
    def graph():
        return api_graph()

    def json(self, path):
        status, ctype, body = self.get(path)
        self.assertIn("application/json", ctype, path)
        return status, json.loads(body)

    def ok(self, path):
        status, payload = self.json(path)
        self.assertEqual(status, 200, "%s -> %s" % (path, payload))
        return payload

    def sweep(self, where, extra=""):
        return self.ok("/api/sweep?where=%s%s" % (urllib.parse.quote(where), extra))


class IndexTest(ApiCase):
    def test_the_index_documents_every_route_that_answers(self):
        # The index is what a person reads before composing anything, so a route missing
        # from it is a route nobody finds, and a route listed but unrouted is worse.
        payload = self.ok("/api")
        for advertised in payload["endpoints"]:
            path = advertised.split("?")[0]
            if path == "/plan":
                continue      # exercised in PlanJsonTest; it needs a real item
            status, _body = self.json(path)
            self.assertIn(status, (200, 400), "%s -> %s" % (path, status))

    def test_a_trailing_slash_is_the_same_endpoint(self):
        self.assertEqual(self.ok("/api/"), self.ok("/api"))

    def test_the_index_lists_the_real_field_and_function_vocabulary(self):
        payload = self.ok("/api")
        self.assertEqual(sorted(payload["fields"]), sorted(api.FIELDS))
        self.assertEqual(sorted(payload["functions"]),
                         sorted(api.query.FUNCTIONS))

    def test_every_worked_example_in_the_index_actually_runs(self):
        # An example that 400s is worse than no example: it teaches the wrong syntax and
        # reads as the server being broken.
        for example in self.ok("/api")["examples"]:
            status, payload = self.json(example)
            self.assertEqual(status, 200, "%s -> %s" % (example, payload))

    def test_an_unknown_api_path_is_a_json_404_not_an_html_page(self):
        status, payload = self.json("/api/nope")
        self.assertEqual(status, 404)
        self.assertIn("/api/sweep?where=EXPR&select=&order=&limit=", payload["endpoints"])


class KeyTest(ApiCase):
    def test_one_key_carries_describe_plus_the_sweep_vocabulary(self):
        payload = self.ok("/api/key?key=mod%3Asednanite_ingot")
        self.assertEqual(payload["label"], "Sednanite Ingot")
        self.assertEqual(payload["makes_total"], 2)      # from nuggets, and from dust
        self.assertIn("ingotSednanite", payload["oredicts"])
        # Every field the sweep can filter on, on the single-key page too: that is how you
        # learn what to sweep on without reading the source.
        self.assertEqual(sorted(payload["facts"]), sorted(api.FIELDS))

    def test_stock_matches_what_the_network_holds(self):
        self.assertEqual(self.ok("/api/key?key=mod%3Asednanite_nugget")["facts"]["stock"], 18)

    def test_a_fluid_is_reachable_even_though_it_is_not_in_names(self):
        # `graph.names` covers items only, so /plan's own known-item test would call this
        # a 404. A fluid is exactly the thing #103 was about being unable to look up.
        payload = self.ok("/api/key?key=fluid%3Amolten_sednanite")
        self.assertEqual(payload["kind"], "fluid")
        self.assertEqual(payload["label"], "Molten Sednanite")

    def test_mod_is_blank_for_a_kind_that_has_no_mod(self):
        # `fluid:molten_sednanite`.split(":")[0] is "fluid", which is the KIND wearing the
        # shape of an answer. A sweep grouping by mod would report a mod called fluid.
        self.assertEqual(self.ok("/api/key?key=fluid%3Amolten_sednanite")["facts"]["mod"], "")
        self.assertEqual(self.ok("/api/key?key=ore%3AingotSednanite")["facts"]["mod"], "")
        self.assertEqual(self.ok("/api/key?key=mod%3Asednanite_ingot")["facts"]["mod"], "mod")

    def test_a_container_empty_does_not_count_as_producing_the_fluid(self):
        facts = self.ok("/api/key?key=fluid%3Amolten_sednanite")["facts"]
        self.assertEqual(facts["producers"], 0)
        self.assertEqual(facts["all_producers"], 1)

    def test_an_unknown_key_is_a_404_with_the_key_in_it(self):
        status, payload = self.json("/api/key?key=mod%3Anope")
        self.assertEqual(status, 404)
        self.assertEqual(payload["key"], "mod:nope")

    def test_a_missing_key_parameter_is_a_400(self):
        self.assertEqual(self.json("/api/key")[0], 400)


class KeysTest(ApiCase):
    def test_a_match_is_unranked_and_reports_its_own_total(self):
        # Every kind of key participates, which is the difference from `/suggest`: the
        # oredict group, the fluid and the NBT-discriminated can are all findable, and the
        # rows come back in key order rather than in relevance order.
        payload = self.ok("/api/keys?match=sednanite")
        self.assertFalse(payload["truncated"])
        self.assertEqual([r["key"] for r in payload["results"]],
                         ["fluid:molten_sednanite", "forestry:can:1#aa",
                          "mod:sednanite_dust", "mod:sednanite_ingot",
                          "mod:sednanite_nugget", "ore:ingotSednanite"])
        self.assertEqual(payload["matched"], 6)

    def test_the_key_matches_too_not_only_the_label(self):
        self.assertEqual(self.ok("/api/keys?match=forestry")["matched"], 2)

    def test_kind_and_mod_narrow_it(self):
        self.assertEqual(self.ok("/api/keys?kind=fluid")["matched"], 1)
        self.assertEqual(self.ok("/api/keys?mod=other")["matched"], 1)

    def test_a_truncated_answer_says_how_much_it_is_hiding(self):
        payload = self.ok("/api/keys?match=sednanite&limit=1")
        self.assertEqual((payload["matched"], payload["returned"]), (6, 1))
        self.assertTrue(payload["truncated"])

    def test_limit_zero_lifts_the_cap(self):
        payload = self.ok("/api/keys?limit=0")
        self.assertEqual(payload["returned"], payload["matched"])
        self.assertFalse(payload["truncated"])

    def test_a_non_numeric_limit_is_a_400_not_a_500(self):
        status, payload = self.json("/api/keys?limit=lots")
        self.assertEqual(status, 400)
        self.assertIn("whole number", payload["error"])


class SweepTest(ApiCase):
    def test_the_predicate_that_forced_a_script(self):
        # #106, as a URL. This is the whole reason the endpoint exists.
        payload = self.sweep('endswith(label, "Nugget") and producers == 0')
        self.assertEqual([r["key"] for r in payload["results"]],
                         ["mod:sednanite_nugget"])
        self.assertEqual(payload["where"],
                         'endswith(label, "Nugget") and producers == 0')

    def test_provenance_names_how_the_pack_hands_a_key_out(self):
        # #171. A new FIELD is a runtime string with no compile-time check, so a typo in the
        # registry or a rename on `Graph` fails silently on every sweep that asks for it.
        payload = self.sweep('provenance != ""', "&select=key,provenance")
        self.assertEqual([["contenttweaker:from_a_puzzle", "puzzle"]],
                         [[r["key"], r["provenance"]] for r in payload["results"]])

    def test_provenance_is_empty_rather_than_missing_for_everything_else(self):
        # Empty string and not null, so `startswith`/`contains` work on the column and a
        # sweep does not have to special-case the overwhelmingly common answer.
        payload = self.sweep('key == "contenttweaker:from_nowhere"', "&select=key,provenance")
        self.assertEqual("", payload["results"][0]["provenance"])

    def test_the_sweep_and_the_plan_agree_about_which_key_is_unsourced(self):
        # THE DRIFT THIS FIELD EXISTS TO PREVENT, asserted rather than described. #178 spent
        # a PR removing a surface-to-surface divergence here; #171 takes 53 keys out of
        # `unsourced` and a sweep that could see the mark vanish but not what replaced it
        # would report a declared key as an ordinary raw leaf.
        payload = self.sweep('startswith(key, "contenttweaker:")',
                             "&select=key,unsourced,provenance&order=key")
        self.assertEqual(
            [["contenttweaker:from_a_puzzle", False, "puzzle"],
             ["contenttweaker:from_nowhere", True, ""]],
            [[r["key"], r["unsourced"], r["provenance"]] for r in payload["results"]])

    def test_select_chooses_the_columns(self):
        payload = self.sweep('kind == "fluid"', "&select=key,cost,kind")
        self.assertEqual(sorted(payload["results"][0]), ["cost", "key", "kind"])

    # The ladder, whose three rungs have three distinct producer counts: nugget 0, dust 1,
    # ingot 2. Named once so a sort assertion below reads as an order and not as a list.
    LADDER = 'startswith(key, "mod:sednanite")'

    def test_order_sorts_and_a_leading_minus_reverses(self):
        up = self.sweep(self.LADDER, "&order=producers")
        down = self.sweep(self.LADDER, "&order=-producers")
        self.assertEqual([r["producers"] for r in up["results"]], [0, 1, 2])
        self.assertEqual([r["key"] for r in up["results"]],
                         list(reversed([r["key"] for r in down["results"]])))

    def test_text_reverses_too(self):
        # `-label` has to mean something; negating only the numeric branch would leave a
        # descending name sort silently ascending.
        down = self.sweep(self.LADDER, "&order=-label&select=key,label")
        self.assertEqual([r["label"] for r in down["results"]],
                         ["Sednanite Nugget", "Sednanite Ingot", "Sednanite Dust"])

    def test_ordering_by_a_column_that_was_not_selected_still_works(self):
        payload = self.sweep(self.LADDER, "&select=key&order=-producers")
        self.assertEqual([r["key"] for r in payload["results"]],
                         ["mod:sednanite_ingot", "mod:sednanite_dust",
                          "mod:sednanite_nugget"])
        self.assertEqual(sorted(payload["results"][0]), ["key"])

    def test_sorting_happens_before_truncation(self):
        # Otherwise `limit` returns the first N the scan happened to reach, which changes
        # with the graph's insertion order and makes a measurement unrepeatable.
        payload = self.sweep(self.LADDER, "&order=-producers&limit=1")
        self.assertEqual([r["key"] for r in payload["results"]], ["mod:sednanite_ingot"])
        self.assertEqual(payload["matched"], 3)

    def test_an_unpriced_key_sorts_last_in_both_directions(self):
        # `cost` is None for a key the relaxation never reached, and "no answer" is not a
        # small answer. Both directions, because that is the claim.
        for order in ("cost", "-cost"):
            payload = self.sweep("true", "&select=key,cost&order=%s" % order)
            costs = [r["cost"] for r in payload["results"]]
            unset = [i for i, c in enumerate(costs) if c is None]
            self.assertEqual(unset, list(range(len(costs) - len(unset), len(costs))),
                             "order=%s put an unpriced key before a priced one" % order)

    def test_a_bad_predicate_is_a_400_carrying_the_parser_message(self):
        status, payload = self.json("/api/sweep?where=producer%20%3D%3D%200")
        self.assertEqual(status, 400)
        self.assertIn("no such field", payload["error"])
        self.assertIn("/api", payload["hint"])

    def test_an_unknown_select_field_is_a_400_rather_than_a_dropped_column(self):
        # Silently dropping it would hand back rows missing the column that was asked for,
        # which reads as the data being absent rather than the name being wrong.
        status, payload = self.json("/api/sweep?where=true&select=key,producer")
        self.assertEqual(status, 400)
        self.assertIn("no such field", payload["error"])

    def test_an_unknown_order_field_is_a_400(self):
        self.assertEqual(self.json("/api/sweep?where=true&order=nope")[0], 400)

    def test_a_missing_where_says_where_to_read_the_vocabulary(self):
        status, payload = self.json("/api/sweep")
        self.assertEqual(status, 400)
        self.assertIn("/api", payload["hint"])

    def test_a_comparison_that_cannot_be_made_is_a_400_not_a_500(self):
        # Raised per key at scan time rather than at parse time, so it takes a different
        # path out of the handler than the parse errors above.
        status, payload = self.json("/api/sweep?where=label%20%3E%203")
        self.assertEqual(status, 400)
        self.assertIn("cannot compare", payload["error"])

    def test_a_key_a_recipe_names_but_items_csv_never_did_is_still_swept(self):
        # 2,789 keys on the reference graph are live and unlabelled. Sweeping only
        # `graph.labels` misses them, and a census that silently omits a key reads as a
        # fact about the pack rather than about the scan. `mod:press` is named; the
        # unlabelled one here is the ore group's own key, reached via live_keys.
        keys = [r["key"] for r in self.sweep("live", "&limit=0")["results"]]
        self.assertIn("ore:ingotSednanite", keys)
        for key in self.state.graph.live_keys:
            self.assertIn(key, keys, "%s is live but unsweepable" % key)

    def test_a_held_key_the_dump_never_saw_is_still_swept(self):
        # The correction `explore.rank_matches` makes at the bottom of its scan: an item in
        # the AE2 network that no recipe touches exists in the world, and a sweep that only
        # walked graph.labels would report it absent.
        payload = self.sweep("stock > 0", "&select=key,stock")
        self.assertIn("mod:sednanite_nugget", [r["key"] for r in payload["results"]])


class RecipeTest(ApiCase):
    def test_a_recipe_carries_its_slots_machine_and_transfer_flag(self):
        payload = self.ok("/api/recipe?rid=empty_can")
        self.assertEqual(payload["count"], 1)
        row = payload["results"][0]
        self.assertTrue(row["transfer"])
        self.assertEqual(row["category"], "transposer")
        self.assertEqual(sorted(o["key"] for o in row["outputs"]),
                         ["fluid:molten_sednanite", "forestry:can:1"])

    def test_an_oredict_slot_lists_its_alternatives_resolved(self):
        row = self.ok("/api/recipe?rid=ingot_to_dust")["results"][0]
        slot = row["inputs"][0]
        self.assertEqual(slot["alt_total"], 1)
        self.assertEqual(slot["alts"][0]["key"], "ore:ingotSednanite")

    def test_alts_caps_the_resolved_list_and_alt_total_tells_the_truth(self):
        row = self.ok("/api/recipe?rid=ingot_to_dust&alts=0")["results"][0]
        self.assertEqual(len(row["inputs"][0]["alts"]), 1)
        row = self.ok("/api/recipe?rid=ingot_to_dust&alts=1")["results"][0]
        self.assertEqual(row["inputs"][0]["alt_total"], 1)

    def test_an_unknown_rid_is_a_404(self):
        self.assertEqual(self.json("/api/recipe?rid=nope")[0], 404)

    def test_a_missing_rid_is_a_400(self):
        self.assertEqual(self.json("/api/recipe")[0], 400)


class CostTest(ApiCase):
    def test_a_key_slice_comes_from_the_warm_table(self):
        payload = self.ok("/api/cost?key=mod%3Asednanite_ingot")
        self.assertIn("mod:sednanite_ingot", payload["keys"])

    def test_several_keys_at_once(self):
        payload = self.ok("/api/cost?key=mod%3Asednanite_ingot&key=mod%3Asednanite_dust")
        self.assertEqual(len(payload["keys"]), 2)

    def test_a_category_reports_the_entry_cost_and_the_machine_state(self):
        payload = self.ok("/api/cost?category=mod.press")
        row = payload["categories"]["mod.press"]
        self.assertIn("entry_cost", row)
        self.assertIn(row["state"], ("have", "buildable", "unknown", "unavailable"))

    def test_no_parameters_summarises_rather_than_dumping_the_table(self):
        summary = self.ok("/api/cost")["summary"]
        self.assertEqual(summary["keys_in_table"],
                         summary["priced"] + summary["unpriced"])

    def test_an_infinite_cost_serialises_as_null_not_as_Infinity(self):
        # `json.dumps(float("inf"))` writes the bare token `Infinity`, which is valid
        # Python and invalid JSON: `jq` rejects the whole document. Asserted on the RAW
        # body, because `json.loads` accepts it and would hide the bug.
        body = self.get("/api/sweep?where=true&select=key,cost&limit=0")[2]
        self.assertNotIn("Infinity", body)
        self.assertNotIn("NaN", body)


class PlanJsonTest(ApiCase):
    """`fmt=json` was already being passed and silently ignored, so #106's plan tree was
    recovered by regexing the tags out of the rendered HTML."""

    def test_a_plan_comes_back_as_data(self):
        status, payload = self.json(plan_json("mod:sednanite_ingot", 4))
        self.assertEqual(status, 200)
        self.assertEqual(payload["target_name"], "Sednanite Ingot")
        self.assertIn("tree", payload)

    def test_without_the_parameter_it_is_still_html(self):
        status, ctype, body = self.get("/plan?item=mod%3Asednanite_ingot&qty=4")
        self.assertEqual(status, 200)
        self.assertIn("text/html", ctype)
        self.assertIn("Crafting plan", body)

    def test_an_unknown_item_is_a_json_404_not_an_html_page(self):
        # A JSON caller receiving the HTML 404 gets a parse error where it wanted "no such
        # item", which is worse than the parameter not being supported at all.
        status, payload = self.json(plan_json("mod:nope"))
        self.assertEqual(status, 404)
        self.assertEqual(payload["key"], "mod:nope")

    def test_a_missing_item_is_a_json_400(self):
        status, payload = self.json("/plan?fmt=json")
        self.assertEqual(status, 400)
        self.assertIn("item=", payload["error"])


class SharedRowShapeTest(ApiCase):
    """Every payload naming an item agrees on what the four identity fields are called.

    They were written out five times across `explore` and `api` before `explore.identity`
    existed. The failure a copy causes is narrow and late: one renderer reading `label` off
    the one row built by the stale copy, at display time, on that row only.
    """

    IDENTITY = ["key", "kind", "label", "name"]

    def test_the_single_key_page_a_suggest_row_and_a_recipe_slot_agree(self):
        described = self.ok("/api/key?key=mod%3Asednanite_ingot")
        suggested = self.json("/suggest?q=sednanite%20ingot")[1]["results"][0]
        slot = self.ok("/api/recipe?rid=nugget_to_ingot")["results"][0]["inputs"][0]
        for row in (described, suggested, slot["alts"][0]):
            self.assertEqual(sorted(set(self.IDENTITY) & set(row)), self.IDENTITY,
                             sorted(row))
            self.assertIn("stock", row)

    def test_a_recipe_output_row_carries_the_identity_plus_a_quantity(self):
        out = self.ok("/api/recipe?rid=nugget_to_ingot")["results"][0]["outputs"][0]
        self.assertEqual(sorted(out), self.IDENTITY + ["qty"])
        self.assertEqual(out["qty"], 1)

    def test_the_explore_page_and_the_api_still_describe_a_key_the_same_way(self):
        # `/api/key` is `explore.describe` plus a `facts` block and nothing else. If it grew
        # its own opinion about a field, the page and the API would drift.
        payload = self.ok("/api/key?key=mod%3Asednanite_ingot")
        direct = api.explore.describe(self.state.graph, "mod:sednanite_ingot",
                                      have=self.state.have)
        self.assertEqual({k: v for k, v in payload.items() if k != "facts"},
                         json.loads(api.dumps(direct)))


class SuggestStillWorksTest(ApiCase):
    """`/suggest` moved onto `api.dumps` and `api.JSON_CTYPE`; the typeahead reads it."""

    def test_the_typeahead_contract_is_unchanged(self):
        status, payload = self.json("/suggest?q=sednanite")
        self.assertEqual(status, 200)
        for field in ("key", "name", "kind", "label", "stock", "makes", "uses"):
            self.assertIn(field, payload["results"][0], field)


if __name__ == "__main__":  # pragma: no cover
    unittest.main()
