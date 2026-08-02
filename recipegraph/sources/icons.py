"""The item icon atlas the dump mod renders: icons.json plus its icons-N.png pages.

WHY THE MOD RENDERS THESE AT ALL. Item textures do live in the jars, at
`assets/<modid>/textures/items/<name>.png`, and a python-side extractor over the ~410 jars
was the obvious plan. Three things defeat it: the texture name is not the registry name (it
comes from the item's model JSON, and blocks resolve through a blockstate); metadata variants
have separate models, which is the rule in this pack rather than the exception; and some
items have no static texture at all, being drawn by a TESR or tinted at runtime. The dump mod
is standing inside the running game with the model system loaded, so it asks the same
renderer the player's inventory asks. See #36 and IconAtlas.java.

THE INDEX TRAVELS IN graph.json AND THE PIXELS TRAVEL BESIDE IT. The deployment ships a
graph and nothing else, so anything the server needs has to be in the graph or next to it --
that is why `multiblocks` is baked in. Pixels are the exception: base64 inside a JSON
document inflates them by a third and makes every 4.4-second graph load pay for pictures the
caller may never draw. So `copy_pages` puts the PNGs in the same directory as the graph, and
they rsync with it.

A PAGE THAT DID NOT COPY IS DROPPED FROM THE INDEX, not left dangling. An `<img>` at a 404
draws a broken-image glyph in every row, which is worse than the per-mod hue chip the UI
already falls back to -- the same argument IconAtlas makes for dropping a blank sprite.
"""

import json
import os
import shutil

from . import dump_meta


def load(path):
    """The atlas index as `{"icon", "cols", "pages", "keys"}`, or {} if unusable.

    `keys` maps a base item key to `[page, column, row]`. Column and row rather than pixel
    offsets, so the reader multiplies by `icon` and cannot disagree with the writer about
    the sprite size.
    """
    if not path or not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8", errors="replace") as fh:
        try:
            doc = json.load(fh)
        except ValueError:
            return {}
    if not isinstance(doc, dict):
        return {}

    icon = doc.get("icon")
    cols = doc.get("cols")
    pages = doc.get("pages")
    keys = doc.get("keys")
    if not isinstance(icon, int) or icon <= 0:
        return {}
    if not isinstance(cols, int) or cols <= 0:
        return {}
    if not isinstance(pages, list) or not pages:
        return {}
    if not isinstance(keys, dict):
        return {}

    pages = [str(p) for p in pages]
    placed = {}
    for key, at in keys.items():
        # An entry naming a page that is not in `pages` is a sprite whose file failed to
        # write. Dropping it here rather than at render time means exactly one place has to
        # know that rule.
        if (isinstance(at, list) and len(at) == 3
                and all(isinstance(v, int) and v >= 0 for v in at)
                and at[0] < len(pages) and at[1] < cols and at[2] < cols):
            placed[str(key)] = at
    if not placed:
        return {}
    return {"icon": icon, "cols": cols, "pages": pages, "keys": placed}


def copy_pages(index, dump_dir, out_dir, say=None):
    """Copy the atlas PNGs beside the graph. Returns the index, minus any page that failed.

    Copied rather than referenced in place because the pack instance is on the machine that
    can run the game and the server is not -- `data/` is what rsyncs to the container.
    Copying a page that is already identical is skipped, so a rebuild against an unchanged
    dump does no I/O for ~40 MB of pictures.
    """
    if not index or not out_dir:
        return index
    kept, dropped = [], set()
    for i, name in enumerate(index["pages"]):
        src = os.path.join(dump_dir, name)
        dst = os.path.join(out_dir, name)
        try:
            if not os.path.exists(src):
                raise IOError("not in the dump")
            if not (os.path.exists(dst)
                    and os.path.getsize(dst) == os.path.getsize(src)
                    and os.path.getmtime(dst) >= os.path.getmtime(src)):
                os.makedirs(out_dir, exist_ok=True)
                shutil.copy2(src, dst)
            kept.append(name)
        except (IOError, OSError) as e:
            dropped.add(i)
            if say:
                say("icons: page %s not copied (%s) -- its sprites are dropped" % (name, e))

    if not dropped:
        return index
    # Page indexes shift when one is dropped, so every surviving entry is renumbered rather
    # than left pointing at whatever now sits at its old position. Getting this wrong shows
    # the wrong picture for every key after the gap, which looks like a corrupt atlas.
    renumber = {}
    for i in range(len(index["pages"])):
        if i not in dropped:
            renumber[i] = len(renumber)
    keys = {k: [renumber[at[0]], at[1], at[2]]
            for k, at in index["keys"].items() if at[0] in renumber}
    if not keys:
        return {}
    return {"icon": index["icon"], "cols": index["cols"], "pages": kept, "keys": keys}


def find(instance_dir, dump_dir=None):
    path = os.path.join(dump_meta.dir_for(instance_dir, dump_dir), "icons.json")
    return path if os.path.exists(path) else None
