"""Time series for AE2 stock levels and network power, with bounded storage.

WHAT THIS CAN AND CANNOT TELL YOU
AE2 exposes stock *levels*, not throughput. So every rate here is a NET rate
(production minus consumption) derived from consecutive levels. Factorio can split
produced-vs-consumed because it instruments each machine; we only see the warehouse.
An item being made and eaten at the same speed reads as a flat line. Do not present
these numbers as production rates.

STORAGE
Naively storing 3,270 item levels every minute is ~4.7M rows/day. Two things keep it
bounded:

1. SPARSE: a row is written only when an item's quantity CHANGES. Most of a big
   network is inert ore sitting in a drive. Level at time T is therefore "the most
   recent row at or before T", which needs no baseline rows.
2. TIERED RETENTION: fine detail is kept briefly, coarse detail for a long time
   (see TIERS). Pruning is the subtle part -- for each key it must KEEP the newest
   row older than the horizon, otherwise that item's level becomes unknown for the
   whole retained window instead of merely coarse. That row is the "carry".

Power is dense (one small row per snapshot) because it is a handful of scalars.
"""

import os
import sqlite3
import time

# (bucket_seconds, keep_seconds). A snapshot is snapped to its bucket, so a finer
# cadence than the smallest bucket collapses instead of inflating the table.
TIERS = [
    (60, 2 * 3600),          # 1 min, 2 hours
    (600, 2 * 86400),        # 10 min, 2 days
    (3600, 60 * 86400),      # 1 hour, 60 days
]

SCHEMA = """
CREATE TABLE IF NOT EXISTS level (
  tier INTEGER NOT NULL,
  ts   INTEGER NOT NULL,
  key  TEXT    NOT NULL,
  qty  INTEGER NOT NULL,
  PRIMARY KEY (tier, key, ts)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS level_tier_ts ON level(tier, ts);

CREATE TABLE IF NOT EXISTS power (
  tier INTEGER NOT NULL,
  ts   INTEGER NOT NULL,
  stored REAL, max_stored REAL, avg_use REAL, avg_inject REAL, idle REAL, demand REAL,
  PRIMARY KEY (tier, ts)
) WITHOUT ROWID;

CREATE TABLE IF NOT EXISTS snapshot (
  ts INTEGER PRIMARY KEY,
  source TEXT,
  items INTEGER
);

CREATE TABLE IF NOT EXISTS name (key TEXT PRIMARY KEY, label TEXT);
"""


def connect(path):
    fresh = not os.path.exists(path)
    parent = os.path.dirname(path)
    if parent:
        os.makedirs(parent, exist_ok=True)
    conn = sqlite3.connect(path)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.executescript(SCHEMA)
    if fresh:
        conn.commit()
    return conn


def _bucket(ts, size):
    return (ts // size) * size


def _last_value(conn, tier, key, before_ts):
    row = conn.execute(
        "SELECT qty FROM level WHERE tier=? AND key=? AND ts<=? ORDER BY ts DESC LIMIT 1",
        (tier, key, before_ts),
    ).fetchone()
    return row[0] if row else None


def record(conn, items, ts=None, source="save", power=None, names=None):
    """Store one snapshot. Returns {tier: rows_written}."""
    ts = int(ts if ts is not None else time.time())
    written = {}

    conn.execute("INSERT OR REPLACE INTO snapshot(ts, source, items) VALUES (?,?,?)",
                 (ts, source, len(items)))

    if names:
        conn.executemany("INSERT OR REPLACE INTO name(key, label) VALUES (?,?)",
                         list(names.items()))

    for tier, (size, _keep) in enumerate(TIERS):
        bts = _bucket(ts, size)
        rows = []
        for key, qty in items.items():
            qty = int(qty)
            # Compare against the value already effective at this bucket. Writing only
            # on change is what keeps the table small; an unchanged item costs nothing.
            prev = _last_value(conn, tier, key, bts)
            if prev is None or prev != qty:
                rows.append((tier, bts, key, qty))
        if rows:
            conn.executemany(
                "INSERT OR REPLACE INTO level(tier, ts, key, qty) VALUES (?,?,?,?)", rows)
        written[tier] = len(rows)

        if power:
            conn.execute(
                "INSERT OR REPLACE INTO power(tier, ts, stored, max_stored, avg_use,"
                " avg_inject, idle, demand) VALUES (?,?,?,?,?,?,?,?)",
                (tier, bts, power.get("stored"), power.get("max_stored"),
                 power.get("avg_use"), power.get("avg_inject"),
                 power.get("idle"), power.get("demand")))

    conn.commit()
    return written


def prune(conn, now=None):
    """Drop rows past each tier's horizon, keeping one carry row per key.

    The carry is what makes a pruned tier still usable: without it, an item whose
    level last changed before the horizon would read as "unknown" across the entire
    retained window rather than simply "unchanged".
    """
    now = int(now if now is not None else time.time())
    removed = {}
    for tier, (_size, keep) in enumerate(TIERS):
        horizon = now - keep
        # newest pre-horizon row per key survives as the carry
        cur = conn.execute(
            """DELETE FROM level
               WHERE tier=? AND ts < ?
                 AND ts NOT IN (
                   SELECT MAX(ts) FROM level l2
                   WHERE l2.tier=level.tier AND l2.key=level.key AND l2.ts < ?
                 )""",
            (tier, horizon, horizon),
        )
        # rowcount, NOT conn.total_changes: total_changes is cumulative for the whole
        # connection, so it would report every earlier delete again on each tier.
        removed[tier] = cur.rowcount
        conn.execute("DELETE FROM power WHERE tier=? AND ts < ?", (tier, horizon))
    conn.commit()
    return removed


def pick_tier(window_seconds):
    """Finest tier that still covers the requested window."""
    for tier, (_size, keep) in enumerate(TIERS):
        if keep >= window_seconds:
            return tier
    return len(TIERS) - 1


def series(conn, key, since, until=None, tier=None):
    """[(ts, qty)] for one item, with the effective level at `since` prepended."""
    until = int(until if until is not None else time.time())
    tier = pick_tier(until - since) if tier is None else tier
    out = []
    start = _last_value(conn, tier, key, since)
    if start is not None:
        out.append((since, start))
    for ts, qty in conn.execute(
            "SELECT ts, qty FROM level WHERE tier=? AND key=? AND ts>? AND ts<=?"
            " ORDER BY ts", (tier, key, since, until)):
        out.append((ts, qty))
    return out


def movers(conn, since, until=None, limit=20, tier=None):
    """Items with the largest net change over the window, biggest swing first."""
    until = int(until if until is not None else time.time())
    tier = pick_tier(until - since) if tier is None else tier

    keys = [r[0] for r in conn.execute(
        "SELECT DISTINCT key FROM level WHERE tier=? AND ts>? AND ts<=?",
        (tier, since, until))]

    span_min = max((until - since) / 60.0, 1e-9)
    out = []
    for key in keys:
        first = _last_value(conn, tier, key, since)
        last_row = conn.execute(
            "SELECT qty FROM level WHERE tier=? AND key=? AND ts<=? ORDER BY ts DESC LIMIT 1",
            (tier, key, until)).fetchone()
        if last_row is None:
            continue
        last = last_row[0]
        if first is None:
            # No row at or before `since` -- happens for the very first window after
            # tracking starts, and after pruning drops a tier's carry. Fall back to the
            # earliest sample INSIDE the window. Using `last` here instead would make
            # every item report zero change and the whole window look idle.
            earliest = conn.execute(
                "SELECT qty FROM level WHERE tier=? AND key=? AND ts>? AND ts<=?"
                " ORDER BY ts LIMIT 1", (tier, key, since, until)).fetchone()
            if earliest is None:
                continue
            first = earliest[0]
        delta = last - first
        if delta == 0:
            continue
        out.append({
            "key": key,
            "label": label_of(conn, key),
            "first": first,
            "last": last,
            "delta": delta,
            "per_min": delta / span_min,
        })
    out.sort(key=lambda d: -abs(d["delta"]))
    return out[:limit]


def power_series(conn, since, until=None, tier=None):
    until = int(until if until is not None else time.time())
    tier = pick_tier(until - since) if tier is None else tier
    return [
        {"ts": r[0], "stored": r[1], "max_stored": r[2], "avg_use": r[3],
         "avg_inject": r[4], "idle": r[5], "demand": r[6]}
        for r in conn.execute(
            "SELECT ts, stored, max_stored, avg_use, avg_inject, idle, demand"
            " FROM power WHERE tier=? AND ts>? AND ts<=? ORDER BY ts",
            (tier, since, until))
    ]


def label_of(conn, key):
    row = conn.execute("SELECT label FROM name WHERE key=?", (key,)).fetchone()
    return row[0] if row and row[0] else key


def stats(conn):
    def one(sql, *a):
        row = conn.execute(sql, a).fetchone()
        return row[0] if row else 0

    per_tier = {}
    for tier, (size, keep) in enumerate(TIERS):
        per_tier["%ds/%dd" % (size, keep // 86400)] = one(
            "SELECT COUNT(*) FROM level WHERE tier=?", tier)
    return {
        "snapshots": one("SELECT COUNT(*) FROM snapshot"),
        "first_snapshot": one("SELECT MIN(ts) FROM snapshot"),
        "last_snapshot": one("SELECT MAX(ts) FROM snapshot"),
        "distinct_items": one("SELECT COUNT(DISTINCT key) FROM level"),
        "level_rows": one("SELECT COUNT(*) FROM level"),
        "power_rows": one("SELECT COUNT(*) FROM power"),
        "rows_by_tier": per_tier,
    }
