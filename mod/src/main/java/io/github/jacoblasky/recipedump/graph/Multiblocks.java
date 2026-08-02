package io.github.jacoblasky.recipedump.graph;

/**
 * Modular Machinery structures, as parsed from the pack's own config.
 *
 * CARRIED IN THE GRAPH RATHER THAN READ WHERE IT IS USED, for the reason `model.py` gives:
 * a deployment ships the graph alone, and the process answering plans has no pack instance
 * to read `config/modularmachinery/` from.
 *
 * Why the cost model needs it: an MM machine's controller recipe is a blueprint plus a blank
 * controller -- two items, for a machine of up to 8,813 placed blocks that appears in no
 * recipe. Pricing one by its controller alone puts every multiblock at the floor of the
 * buildable band. `parts` is what lets it be priced by its STRUCTURE instead.
 *
 * Small in absolute terms -- 259 machines and 124 KB of JSON on the reference pack -- so the
 * layout here is about clarity rather than bytes, but it is still id-based and flat, because
 * a second representation style in one package is its own kind of cost.
 */
public final class Multiblocks {

    private final StringTable registryNames;
    private final StringTable displayNames;
    private final int[] displayNameId;
    private final int[] controllerKeyId;
    private final int[] slots;
    private final int[] blind;
    /** machine -&gt; its part entries. */
    private final int[] partOffsets;
    /** How many blocks this part entry accounts for. */
    private final int[] partCount;
    /** part entry -&gt; the keys that satisfy it. */
    private final int[] partAltOffsets;
    private final int[] partAltKey;

    Multiblocks(StringTable registryNames, StringTable displayNames, int[] displayNameId,
                int[] controllerKeyId, int[] slots, int[] blind, int[] partOffsets,
                int[] partCount, int[] partAltOffsets, int[] partAltKey) {
        this.registryNames = registryNames;
        this.displayNames = displayNames;
        this.displayNameId = displayNameId;
        this.controllerKeyId = controllerKeyId;
        this.slots = slots;
        this.blind = blind;
        this.partOffsets = partOffsets;
        this.partCount = partCount;
        this.partAltOffsets = partAltOffsets;
        this.partAltKey = partAltKey;
    }

    public int count() {
        return registryNames.size();
    }

    /** The MM `registryname`, which links a structure to both its recipes and its controller. */
    public String registryName(int machine) {
        return registryNames.get(machine);
    }

    public int idOf(String registryName) {
        return registryNames.idOf(registryName);
    }

    public String displayName(int machine) {
        return displayNameId[machine] < 0 ? null : displayNames.get(displayNameId[machine]);
    }

    public int controllerKeyId(int machine) {
        return controllerKeyId[machine];
    }

    public int slots(int machine) {
        return slots[machine];
    }

    /** How many structure positions the parser could not resolve to any key. */
    public int blind(int machine) {
        return blind[machine];
    }

    public int partStart(int machine) {
        return partOffsets[machine];
    }

    public int partEnd(int machine) {
        return partOffsets[machine + 1];
    }

    public int partCount(int part) {
        return partCount[part];
    }

    public int partAltStart(int part) {
        return partAltOffsets[part];
    }

    public int partAltEnd(int part) {
        return partAltOffsets[part + 1];
    }

    public int partAltKeyAt(int position) {
        return partAltKey[position];
    }

    /** Total block positions across every part entry of every machine. */
    public long positions() {
        long total = 0;
        for (int count : partCount) {
            total += count;
        }
        return total;
    }

    public long retainedBytes() {
        return Sizes.object(10 * Sizes.REFERENCE)
                + registryNames.retainedBytes() + displayNames.retainedBytes()
                + Sizes.bytes(displayNameId) + Sizes.bytes(controllerKeyId)
                + Sizes.bytes(slots) + Sizes.bytes(blind) + Sizes.bytes(partOffsets)
                + Sizes.bytes(partCount) + Sizes.bytes(partAltOffsets)
                + Sizes.bytes(partAltKey);
    }

    public static final class Builder {

        private final StringTable.Builder registryNames =
                StringTable.builder(256, 8192, true, true);
        private final StringTable.Builder displayNames =
                StringTable.builder(256, 8192, true, true);
        private final IntArray displayNameId = new IntArray();
        private final IntArray controllerKeyId = new IntArray();
        private final IntArray slots = new IntArray();
        private final IntArray blind = new IntArray();
        private final IntArray partOffsets = new IntArray();
        private final IntArray partCount = new IntArray();
        private final IntArray partAltOffsets = new IntArray();
        private final IntArray partAltKey = new IntArray();
        private boolean open;

        public Builder() {
            partOffsets.add(0);
            partAltOffsets.add(0);
        }

        public void beginMachine() {
            if (open) {
                throw new IllegalStateException("beginMachine() without endMachine()");
            }
            open = true;
        }

        public void beginPart(int count) {
            partCount.add(count);
        }

        public void addPartAlternative(int keyId) {
            partAltKey.add(keyId);
        }

        public void endPart() {
            partAltOffsets.add(partAltKey.size());
        }

        public void endMachine(String registryName, String displayName, int controller,
                               int slotCount, int blindPositions) {
            if (!open) {
                throw new IllegalStateException("no machine is open");
            }
            registryNames.add(registryName);
            displayNameId.add(displayName == null ? -1 : displayNames.add(displayName));
            controllerKeyId.add(controller);
            slots.add(slotCount);
            blind.add(blindPositions);
            partOffsets.add(partCount.size());
            open = false;
        }

        public Multiblocks build() {
            if (open) {
                throw new IllegalStateException("a machine is still open");
            }
            return new Multiblocks(registryNames.build(), displayNames.build(),
                    displayNameId.trimmed(), controllerKeyId.trimmed(), slots.trimmed(),
                    blind.trimmed(), partOffsets.trimmed(), partCount.trimmed(),
                    partAltOffsets.trimmed(), partAltKey.trimmed());
        }
    }
}
