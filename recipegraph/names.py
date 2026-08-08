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
import re

from .model import canonical_item_key

# Minecraft colour/format codes (U+00A7 + one char). The pack bakes them into display
# names, so they land in items.csv verbatim -- strip them or they break substring
# search ("§8Ultimate Furnace" does not match a leading "ultimate") and leak into the UI.
FORMAT_CODE = re.compile("§.")


def clean_label(label):
    """Strip format codes. None-safe, because JEI category titles are often absent."""
    if label is None:
        return None
    return FORMAT_CODE.sub("", str(label)).strip() or None


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
            label = clean_label(",".join(row[1:]))
            if not raw_id or raw_id.lower().startswith("mod:item"):
                continue
            # `model.canonical_item_key`, not a local copy of its three lines: this reader
            # and `sources/dump_names` are the two that name items, they disagreed about
            # the 32767 wildcard, and #253 is what that cost. See it for the measurement.
            key = canonical_item_key(raw_id)
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
