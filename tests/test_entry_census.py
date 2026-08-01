"""The entry census's classifier, which is the whole product.

`tools/entry-census.py` exists because `tools/cost-probe.py` cannot see a census: every one
of the 140 categories that shared the band ceiling before #95 had a perfectly stable route,
so a route probe reported no problem while no two of them could be told apart. The tool's
answer is which REGION a price landed in, and a classifier that quietly maps a value to the
wrong region -- or to nothing -- turns the audit into false reassurance.

Loaded through importlib because `tools/` is not a package and the filename is hyphenated.
The census itself needs the 115 MB graph and a full relaxation, which does not belong in a
suite CI runs on every push; the pure classification and stock-reading layers are covered.
"""

import importlib.util
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import cost as cost_mod  # noqa: E402

_PATH = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                     "tools", "entry-census.py")
_spec = importlib.util.spec_from_file_location("entry_census", _PATH)
census = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(census)


class EveryPriceTheModelCanProduceIsClassifiedTest(unittest.TestCase):
    """The census must not have a hole. A price falling through every region reads as
    "OUTSIDE THE BAND", which is the honest answer for a genuine escape and a lie if the
    regions simply fail to meet."""

    def test_the_regions_tile_the_whole_band_with_no_gap(self):
        bounds = census.regions()
        for (_la, _loa, hi), (lb, lo, _hib) in zip(bounds, bounds[1:]):
            self.assertLessEqual(hi, lo, "a price between %r and %r lands nowhere" % (hi, lo))
            self.assertLessEqual(lo - hi, 1.0 + 1e-9,
                                 "the gap below %r is wide enough to hide a price" % lb)

    def test_every_value_build_entry_cost_can_return_reads_as_priced(self):
        for b in (0.0, 1.0, 2.0, 67.0, 9288.0, 279863.0, 1e12, 1e300):
            self.assertEqual(census.region_of(cost_mod.build_entry_cost(b)), "priced")

    def test_an_unpriced_item_reads_as_one(self):
        self.assertEqual(census.region_of(cost_mod.build_entry_cost(float("inf"))),
                         "unpriced item")

    def test_every_value_blocked_entry_cost_can_return_reads_as_blocked(self):
        for f in (0.0, 0.001, 0.25, 0.5, 0.999, 1.0):
            self.assertEqual(census.region_of(cost_mod.blocked_entry_cost(f)),
                             "blocked structure")

    def test_a_price_that_escapes_the_band_is_named_rather_than_hidden(self):
        for v in (cost_mod.MACHINE_COST["unknown"], cost_mod.MACHINE_COST["unavailable"],
                  cost_mod.MACHINE_COST["have"]):
            self.assertEqual(census.region_of(v), "OUTSIDE THE BAND")


class TheCensusReadsStockTheWayTheServerDoesTest(unittest.TestCase):
    """Reading only `items` would drop every fluid and reprice the chemistry chains, so the
    tool's answer would disagree with the running server for no visible reason."""

    def stock(self, doc):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "have.json")
            with open(path, "w") as fh:
                json.dump(doc, fh)
            return census.load_stock(path)

    def test_all_three_namespaces_arrive(self):
        have, placed = self.stock({"items": {"mod:a": 3}, "fluids": {"water": 1000},
                                   "essentia": {"Perditio": 5},
                                   "placed": {"mod:machine": 1}})
        self.assertEqual(have, {"mod:a": 3, "fluid:water": 1000, "essentia:perditio": 5})
        self.assertEqual(placed, {"mod:machine": 1})

    def test_an_aspect_is_lowercased_like_the_server_does(self):
        have, _ = self.stock({"essentia": {"ORDO": 2}})
        self.assertIn("essentia:ordo", have)

    def test_a_missing_file_is_empty_rather_than_an_error(self):
        self.assertEqual(census.load_stock(None), ({}, {}))
        self.assertEqual(census.load_stock("/nonexistent/have.json"), ({}, {}))


if __name__ == "__main__":
    unittest.main()
