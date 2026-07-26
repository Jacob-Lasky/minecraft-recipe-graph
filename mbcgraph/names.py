"""Item display names.

Source of truth is AE2's generated `config/AppliedEnergistics2/items.csv`, which the
pack writes on first run and which covers every registered item (53k rows in
MeatballCraft). DO NOT try to reconstruct names from each mod's en_us.lang instead:
lang keys are unlocalized names, and mapping registry-name -> unlocalized-name is
not mechanical, so lang files cannot be joined to registry ids without the running
game. items.csv already did that join.
"""

import csv
import os

from .model import norm_key


def load_items_csv(path):
    """Parse items.csv -> {canonical key: localized name}."""
    names = {}
    with open(path, newline="", encoding="utf-8", errors="replace") as fh:
        reader = csv.reader(fh)
        for row in reader:
            if len(row) < 2:
                continue
            raw_id = row[0].strip()
            # Localized names can contain commas, so rejoin everything after col 0.
            label = ",".join(row[1:]).strip()
            if not raw_id or raw_id.lower().startswith("mod:item"):
                continue
            parts = raw_id.split(":")
            meta = 0
            if len(parts) >= 3 and parts[-1].lstrip("-").isdigit():
                meta = int(parts[-1])
                raw_id = ":".join(parts[:-1])
            key = norm_key(raw_id, meta)
            if key and label:
                names.setdefault(key, label)
    return names


def find_items_csv(instance_dir):
    candidate = os.path.join(instance_dir, "config", "AppliedEnergistics2", "items.csv")
    return candidate if os.path.exists(candidate) else None


def build_reverse(names):
    """{lowercased localized name: [keys]} for name -> id lookup."""
    rev = {}
    for key, label in names.items():
        rev.setdefault(label.lower(), []).append(key)
    return rev


def resolve(query, names, reverse=None):
    """Resolve a user query (id or display name, case-insensitive) to item keys."""
    q = query.strip()
    if q in names:
        return [q]
    nk = norm_key(q)
    if nk in names:
        return [nk]
    reverse = reverse if reverse is not None else build_reverse(names)
    low = q.lower()
    if low in reverse:
        return list(reverse[low])
    # substring fallback, shortest names first so "Borax" beats "Borax Singularity"
    hits = [(label, keys) for label, keys in reverse.items() if low in label]
    hits.sort(key=lambda t: (len(t[0]), t[0]))
    out = []
    for _label, keys in hits[:12]:
        out.extend(keys)
    return out
