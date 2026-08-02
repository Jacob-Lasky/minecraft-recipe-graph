package io.github.jacoblasky.recipedump.graph;

/**
 * Where an item's icon sits in the sprite sheet the dump renders.
 *
 * THE PNG PAGES TRAVEL BESIDE `graph.json`, NOT INSIDE IT, and that is a deliberate constraint
 * rather than an accident of packaging: base64 in a JSON document inflates the bytes by a
 * third and makes every graph load pay for pictures it may never draw. What lives in the
 * graph is only the INDEX -- which page, column and row a key is at. DO NOT "simplify" this
 * by embedding the images; the load-time cost is the whole reason the split exists.
 *
 * Why an atlas at all: a registry id does not map to a texture path by any convention. The
 * mapping lives in each mod's models and blockstate JSON, so an icon cannot be derived from a
 * key and has to be rendered by the dump mod and looked up.
 *
 * KEYED BY BASE ITEM KEY, so every NBT variant of an item shares its icon. That is right for
 * the overwhelming majority and wrong for a few enchanted-glint cases, which is the trade the
 * dump made rather than rendering 261,089 sprites.
 *
 * COLUMN AND ROW, NOT PIXEL OFFSETS, so the reader multiplies by {@link #iconSize} and cannot
 * disagree with the writer about the sprite size. {@code pages} names the PNG files in order,
 * and a page that failed to copy is DROPPED from the index rather than left dangling -- an
 * image element pointing at a 404 draws a broken-image glyph in every row, which is worse
 * than the per-mod colour chip the UI already falls back to.
 */
public final class IconAtlas {

    /** The sprite edge length in pixels. Zero when this graph carries no atlas. */
    private final int iconSize;
    private final int columns;
    /** The PNG page filenames, in the order `page` indexes them. */
    private final StringTable pages;
    private final KeyIndex keys;
    /** Per slot, packed as page, column, row -- three entries per key. */
    private final int[] position;

    IconAtlas(int iconSize, int columns, StringTable pages, KeyIndex keys, int[] position) {
        this.iconSize = iconSize;
        this.columns = columns;
        this.pages = pages;
        this.keys = keys;
        this.position = position;
    }

    public static IconAtlas empty() {
        return new IconAtlas(0, 0, StringTable.builder(0, 0, true, false).build(),
                KeyIndex.empty(), new int[0]);
    }

    /** The sprite edge length in pixels, or 0 when this graph carries no atlas. */
    public int iconSize() {
        return iconSize;
    }

    public int columns() {
        return columns;
    }

    public int pageCount() {
        return pages.size();
    }

    /** The PNG filename for a page index, as it sits beside the graph file. */
    public String page(int index) {
        return pages.get(index);
    }

    public int size() {
        return keys.size();
    }

    public boolean has(int baseKeyId) {
        return keys.slotOf(baseKeyId) >= 0;
    }

    /** The atlas page index holding this key's icon, or -1. */
    public int pageOf(int baseKeyId) {
        int slot = keys.slotOf(baseKeyId);
        return slot < 0 ? -1 : position[slot * 3];
    }

    public int column(int baseKeyId) {
        int slot = keys.slotOf(baseKeyId);
        return slot < 0 ? -1 : position[slot * 3 + 1];
    }

    public int row(int baseKeyId) {
        int slot = keys.slotOf(baseKeyId);
        return slot < 0 ? -1 : position[slot * 3 + 2];
    }

    public long retainedBytes() {
        return Sizes.object(3 * Sizes.REFERENCE + 8) + pages.retainedBytes()
                + keys.retainedBytes() + Sizes.bytes(position);
    }

    public static final class Builder {

        private final KeyIndex.Builder keys = new KeyIndex.Builder();
        private final IntArray position = new IntArray();
        private final StringTable.Builder pages = StringTable.builder(8, 256, false, false);
        private int iconSize;
        private int columns;

        public void sheet(int spritePixels, int columnCount) {
            this.iconSize = spritePixels;
            this.columns = columnCount;
        }

        /** Pages in order; the index a key carries is a position in this sequence. */
        public void page(String pngName) {
            pages.add(pngName);
        }

        public void at(int baseKeyId, int page, int column, int row) {
            keys.add(baseKeyId);
            position.add(page);
            position.add(column);
            position.add(row);
        }

        public IconAtlas build() {
            int[] order = keys.permutation();
            int[] sorted = new int[order.length * 3];
            for (int slot = 0; slot < order.length; slot++) {
                int from = order[slot] * 3;
                sorted[slot * 3] = position.get(from);
                sorted[slot * 3 + 1] = position.get(from + 1);
                sorted[slot * 3 + 2] = position.get(from + 2);
            }
            return new IconAtlas(iconSize, columns, pages.build(), keys.build(order),
                    sorted);
        }
    }
}
