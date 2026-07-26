"""Metrics store: sparse writes, tiered pruning, and rate derivation."""

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from recipegraph import metrics  # noqa: E402


class MetricsTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.db = os.path.join(self.dir, "m.db")
        self.conn = metrics.connect(self.db)
        self.now = 1_700_000_000

    def rows(self, tier=0):
        return self.conn.execute(
            "SELECT COUNT(*) FROM level WHERE tier=?", (tier,)).fetchone()[0]

    def test_unchanged_items_cost_no_rows(self):
        metrics.record(self.conn, {"a": 5, "b": 7}, ts=self.now)
        after_first = self.rows()
        metrics.record(self.conn, {"a": 5, "b": 7}, ts=self.now + 60)
        self.assertEqual(self.rows(), after_first,
                         "identical snapshot must not write new level rows")

    def test_changed_item_writes_one_row(self):
        metrics.record(self.conn, {"a": 5, "b": 7}, ts=self.now)
        before = self.rows()
        metrics.record(self.conn, {"a": 6, "b": 7}, ts=self.now + 60)
        self.assertEqual(self.rows(), before + 1)

    def test_series_carries_last_known_level(self):
        metrics.record(self.conn, {"a": 5}, ts=self.now)
        metrics.record(self.conn, {"a": 9}, ts=self.now + 600)
        got = metrics.series(self.conn, "a", self.now + 120, self.now + 900, tier=0)
        self.assertEqual(got[0][1], 5, "level at window start should carry forward")
        self.assertEqual(got[-1][1], 9)

    def test_rate_uses_prior_baseline(self):
        metrics.record(self.conn, {"a": 100}, ts=self.now)
        metrics.record(self.conn, {"a": 400}, ts=self.now + 600)
        got = metrics.movers(self.conn, self.now, self.now + 600, tier=0)
        self.assertEqual(len(got), 1)
        self.assertEqual(got[0]["delta"], 300)
        self.assertAlmostEqual(got[0]["per_min"], 30.0, places=3)

    def test_first_window_without_baseline_still_reports_change(self):
        # Regression: with no row at/before `since`, movers used to diff a value
        # against itself and report the whole window as idle.
        metrics.record(self.conn, {"a": 10}, ts=self.now + 60)
        metrics.record(self.conn, {"a": 70}, ts=self.now + 120)
        got = metrics.movers(self.conn, self.now, self.now + 120, tier=0)
        self.assertEqual([m["delta"] for m in got], [60])

    def test_prune_keeps_a_carry_row_per_key(self):
        horizon = metrics.TIERS[0][1]
        metrics.record(self.conn, {"a": 1}, ts=self.now - horizon - 7200)
        metrics.record(self.conn, {"a": 2}, ts=self.now - horizon - 3600)
        metrics.record(self.conn, {"b": 5}, ts=self.now - 60)
        metrics.prune(self.conn, now=self.now)
        got = metrics.series(self.conn, "a", self.now - horizon, self.now, tier=0)
        self.assertTrue(got, "carry row missing: level unknowable inside the window")
        self.assertEqual(got[0][1], 2, "carry should be the newest pre-horizon value")

    def test_prune_reports_per_tier_counts_not_cumulative(self):
        horizon = metrics.TIERS[0][1]
        for i in range(4):
            metrics.record(self.conn, {"a": i}, ts=self.now - horizon - 3600 * (5 - i))
        removed = metrics.prune(self.conn, now=self.now)
        self.assertTrue(all(isinstance(v, int) for v in removed.values()))
        self.assertLessEqual(removed[0], 4)

    def test_pick_tier_selects_finest_that_covers_window(self):
        self.assertEqual(metrics.pick_tier(600), 0)
        self.assertEqual(metrics.pick_tier(metrics.TIERS[0][1] + 1), 1)
        self.assertEqual(metrics.pick_tier(10 ** 9), len(metrics.TIERS) - 1)

    def test_power_is_recorded_and_queryable(self):
        metrics.record(self.conn, {"a": 1}, ts=self.now,
                       power={"stored": 1000.0, "max_stored": 4000.0, "avg_use": 12.0,
                              "avg_inject": 20.0, "idle": 2.0, "demand": 11.0})
        got = metrics.power_series(self.conn, self.now - 60, self.now + 60, tier=0)
        self.assertEqual(len(got), 1)
        self.assertEqual(got[0]["stored"], 1000.0)
        self.assertEqual(got[0]["avg_use"], 12.0)


if __name__ == "__main__":
    unittest.main()
