package lu.kolja.matter_manipulator.common.manipulator;

import lombok.Getter;
import lombok.experimental.Accessors;
import net.minecraft.network.chat.Component;

@Getter
@Accessors(fluent = true)
public enum BlockSlot {
    CORNER("corner"),
    EDGE("edge"),
    FACE("face"),
    VOLUME("volume"),
    CABLE("cable"),
    EXCHANGE_TARGET("exchange_target");

    private final String key;

    BlockSlot(String key) {
        this.key = key;
    }

    public Component displayName() {
        return Component.literal(switch (this) {
            case CORNER -> "Corners";
            case EDGE -> "Edges";
            case FACE -> "Faces";
            case VOLUME -> "Volumes";
            case CABLE -> "Cable";
            case EXCHANGE_TARGET -> "Exchange Target";
        });
    }
}
