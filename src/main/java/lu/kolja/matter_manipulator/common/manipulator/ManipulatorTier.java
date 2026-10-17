package lu.kolja.matter_manipulator.common.manipulator;

import lombok.Getter;
import lombok.experimental.Accessors;
import lu.kolja.matter_manipulator.common.config.MMConfig;
import net.minecraft.network.chat.Component;

@Getter
@Accessors(fluent = true)
public enum ManipulatorTier {
    PROTOTYPE("prototype", 32, 64, 10_000_000, 32_768, true, false, false, false, false),
    MKI("mki", 64, 256, 100_000_000, 131_072, true, true, true, false, false),
    MKII("mkii", 128, 1024, 1_000_000_000, 524_288, true, true, true, true, false),
    MKIII("mkiii", -1, 4096, 2_000_000_000, 2_000_000_000, true, true, true, true, true);

    private final String serializedName;
    private final int range;
    private final int blocksPerSecond;
    private final int energyCapacity;
    private final int transferLimit;
    private final boolean geometry;
    private final boolean remove;
    private final boolean cableExchange;
    private final boolean copyMove;
    private final boolean uplink;

    ManipulatorTier(
            String serializedName,
            int range,
            int blocksPerSecond,
            int energyCapacity,
            int transferLimit,
            boolean geometry,
            boolean remove,
            boolean cableExchange,
            boolean copyMove,
            boolean uplink
    ) {
        this.serializedName = serializedName;
        this.range = range;
        this.blocksPerSecond = blocksPerSecond;
        this.energyCapacity = energyCapacity;
        this.transferLimit = transferLimit;
        this.geometry = geometry;
        this.remove = remove;
        this.cableExchange = cableExchange;
        this.copyMove = copyMove;
        this.uplink = uplink;
    }

    public boolean canUse(ManipulatorMode mode) {
        return switch (mode) {
            case GEOMETRY -> geometry;
            case CABLE, EXCHANGE -> cableExchange;
            case COPY, MOVE, BLUEPRINT -> copyMove;
        };
    }

    public boolean canRemove() {
        return remove;
    }

    public boolean canUplink() {
        return uplink;
    }

    public int fePerBlock() {
        return switch (this) {
            case PROTOTYPE -> MMConfig.PROTOTYPE_FE_PER_BLOCK.get();
            case MKI -> MMConfig.MKI_FE_PER_BLOCK.get();
            case MKII -> MMConfig.MKII_FE_PER_BLOCK.get();
            case MKIII -> MMConfig.MKIII_FE_PER_BLOCK.get();
        };
    }

    public Component displayName() {
        return Component.literal(switch (this) {
            case PROTOTYPE -> "Prototype";
            case MKI -> "MKI";
            case MKII -> "MKII";
            case MKIII -> "MKIII";
        });
    }
}
