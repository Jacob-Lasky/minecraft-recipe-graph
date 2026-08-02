package io.github.jacoblasky.recipedump.graph;

/**
 * Which Modular Machinery machine a blueprint item is FOR.
 *
 * WHY THIS IS NOT COSMETIC. All 259 blueprints in the pack are genuinely named "Machine
 * Blueprint" -- that is what the game returns and what items.csv records -- so a plan for any
 * multiblock reads "1 of 261 possibilities" and the player cannot tell which one they are
 * holding. The dump's own MM registry is the only thing that can say. See #55.
 *
 * TWO MAPS, NOT ONE, and the reason is churn. A blueprint's key carries an NBT digest and so
 * changes with every dump; the machine's registry name does not. Joining through the registry
 * name means a re-dump moves one table and leaves the other alone.
 *
 * DELIBERATELY SEPARATE FROM {@link Multiblocks}, which is keyed by the BARE registry name
 * (`dragonfire_crucible`) because it comes from the pack's config files, while these are
 * keyed with the modid (`modularmachinery:dragonfire_crucible`) because they come from the
 * registry. Merging them means normalising two spellings of an id, and getting that wrong is
 * silent: a blueprint would simply stop being named.
 */
public final class Blueprints {

    private final KeyIndex blueprintKeys;
    /** Per blueprint slot: which entry of {@link #machineIds} it names. */
    private final int[] machineOfBlueprint;
    /** MM registry names, with the modid, as they appear in both maps. */
    private final StringTable machineIds;
    /** Per machine: its localized name, or -1. */
    private final int[] machineNameId;
    private final StringTable machineNames;

    Blueprints(KeyIndex blueprintKeys, int[] machineOfBlueprint, StringTable machineIds,
               int[] machineNameId, StringTable machineNames) {
        this.blueprintKeys = blueprintKeys;
        this.machineOfBlueprint = machineOfBlueprint;
        this.machineIds = machineIds;
        this.machineNameId = machineNameId;
        this.machineNames = machineNames;
    }

    public int blueprintCount() {
        return blueprintKeys.size();
    }

    /** Machines named by either map, including one a blueprint points at with no name. */
    public int machineCount() {
        return machineIds.size();
    }

    /** Machines the registry actually gave a localized name, which may be fewer. */
    public int namedMachineCount() {
        int total = 0;
        for (int nameId : machineNameId) {
            if (nameId >= 0) {
                total++;
            }
        }
        return total;
    }

    /** The MM registry name a blueprint is for, or null. */
    public String machineIdOf(int blueprintKeyId) {
        int slot = blueprintKeys.slotOf(blueprintKeyId);
        return slot < 0 ? null : machineIds.get(machineOfBlueprint[slot]);
    }

    /**
     * The localized machine name a blueprint is for, or null.
     *
     * Null rather than a decorated absence when either half of the join is missing, so a
     * caller falls back to the plain label instead of rendering "Machine Blueprint ()".
     * Same policy as every other optional field: no data means the old behaviour.
     */
    public String machineNameOf(int blueprintKeyId) {
        int slot = blueprintKeys.slotOf(blueprintKeyId);
        if (slot < 0) {
            return null;
        }
        int nameId = machineNameId[machineOfBlueprint[slot]];
        return nameId < 0 ? null : machineNames.get(nameId);
    }

    /** The localized name for an MM registry name, or null. */
    public String machineName(String machineId) {
        int machine = machineIds.idOf(machineId);
        if (machine < 0 || machineNameId[machine] < 0) {
            return null;
        }
        return machineNames.get(machineNameId[machine]);
    }

    public long retainedBytes() {
        return Sizes.object(5 * Sizes.REFERENCE) + blueprintKeys.retainedBytes()
                + Sizes.bytes(machineOfBlueprint) + machineIds.retainedBytes()
                + Sizes.bytes(machineNameId) + machineNames.retainedBytes();
    }

    /**
     * Accumulates both maps, in either order.
     *
     * ORDER-INDEPENDENT ON PURPOSE. `graph.json` is written with sorted keys, so
     * `blueprint_machines` is read before `machine_names`, and a builder that resolved a
     * machine id on arrival would find every one of them missing.
     */
    public static final class Builder {

        private final KeyIndex.Builder blueprintKeys = new KeyIndex.Builder();
        private final IntArray machineOfBlueprint = new IntArray();
        private final StringTable.Builder machineIds =
                StringTable.builder(512, 16384, true, true);
        private final IntArray machineNameId = new IntArray();
        private final StringTable.Builder machineNames =
                StringTable.builder(512, 16384, true, true);

        public void blueprint(int blueprintKeyId, String machineId) {
            blueprintKeys.add(blueprintKeyId);
            machineOfBlueprint.add(machineIds.add(machineId));
        }

        public void machineName(String machineId, String localizedName) {
            int machine = machineIds.add(machineId);
            machineNameId.ensureSize(machine + 1, -1);
            machineNameId.set(machine, machineNames.add(localizedName));
        }

        public Blueprints build() {
            int[] order = blueprintKeys.permutation();
            int[] sortedMachines = new int[order.length];
            for (int slot = 0; slot < order.length; slot++) {
                sortedMachines[slot] = machineOfBlueprint.get(order[slot]);
            }
            StringTable ids = machineIds.build();
            machineNameId.ensureSize(ids.size(), -1);
            return new Blueprints(blueprintKeys.build(order), sortedMachines, ids,
                    machineNameId.trimmed(), machineNames.build());
        }
    }
}
