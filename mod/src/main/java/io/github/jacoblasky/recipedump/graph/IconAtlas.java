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
 */
public final class IconAtlas {

    private final String icon;
    private final int columns;
    private final int pages;
    private final KeyIndex keys;
    /** Per slot, packed as page, column, row -- three entries per key. */
    private final int[] position;

    IconAtlas(String icon, int columns, int pages, KeyIndex keys, int[] position) {
        this.icon = icon;
        this.columns = columns;
        this.pages = pages;
        this.keys = keys;
        this.position = position;
    }

    public static IconAtlas empty() {
        return new IconAtlas(null, 0, 0, KeyIndex.empty(), new int[0]);
    }

    /** The base name of the sprite sheet files, or null when this graph carries no atlas. */
    public String icon() {
        return icon;
    }

    public int columns() {
        return columns;
    }

    public int pages() {
        return pages;
    }

    public int size() {
        return keys.size();
    }

    public boolean has(int baseKeyId) {
        return keys.slotOf(baseKeyId) >= 0;
    }

    /** The atlas page holding this key's icon, or -1. */
    public int page(int baseKeyId) {
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
        return Sizes.object(3 * Sizes.REFERENCE + 8) + keys.retainedBytes()
                + Sizes.bytes(position);
    }

    public static final class Builder {

        private final KeyIndex.Builder keys = new KeyIndex.Builder();
        private final IntArray position = new IntArray();
        private String icon;
        private int columns;
        private int pages;

        public void sheet(String iconName, int columnCount, int pageCount) {
            this.icon = iconName;
            this.columns = columnCount;
            this.pages = pageCount;
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
            return new IconAtlas(icon, columns, pages, keys.build(order), sorted);
        }
    }
}
