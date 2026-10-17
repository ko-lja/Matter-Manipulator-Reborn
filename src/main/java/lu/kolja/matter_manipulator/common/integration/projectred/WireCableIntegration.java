package lu.kolja.matter_manipulator.common.integration.projectred;

import codechicken.multipart.block.TileMultipart;
import codechicken.multipart.init.MultiPartRegistries;
import lu.kolja.matter_manipulator.api.CableIntegration;
import lu.kolja.matter_manipulator.api.IntegrationContext;
import lu.kolja.matter_manipulator.common.localization.MMIntegrationText;
import mrtjp.projectred.core.PlacementLib;
import mrtjp.projectred.transmission.WireType;
import mrtjp.projectred.transmission.part.BaseFaceWirePart;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class WireCableIntegration implements CableIntegration {

    private static final Map<String, WireType> WIRES = new HashMap<>();

    static {
        for (var type : WireType.values()) {
            WIRES.put(MultiPartRegistries.multipartTypes().getKey(type.getPartType()).toString(), type);
        }
    }

    @Override
    public CablePlacementData prepare(IntegrationContext.Cable context) {
        var template = context.payload(MultipartIntegration.ID);
        int index = wire(template);
        if (index < 0) {
            var refused = new CompoundTag();
            refused.putString("reason", MMIntegrationText.BLOCK_COST_PROJECTRED_NOT_A_WIRE.getTranslationKey());
            template.put("refused", refused);
            return CablePlacementData.duringPlacement(template);
        }
        if (holds(context.blockEntity(), template.getList("parts", Tag.TAG_COMPOUND).getCompound(index))) {
            return CablePlacementData.duringPlacement(new CompoundTag());
        }
        var payload = routed(template, Set.of(), context.level().registryAccess());
        var drops = new ListTag();
        drops.add(template.getList("drops", Tag.TAG_LIST).getList(index).copy());
        payload.put("drops", drops);
        return CablePlacementData.duringPlacement(payload);
    }

    @Override
    public boolean canReuse(IntegrationContext.CableReuse context) {
        var template = context.payload(MultipartIntegration.ID);
        int index = wire(template);
        return index >= 0 && holds(context.blockEntity(), template.getList("parts", Tag.TAG_COMPOUND).getCompound(index));
    }

    @Override
    public @Nullable Component routeRefusal(IntegrationContext.CableSite context) {
        var template = context.payload(MultipartIntegration.ID);
        int index = wire(template);
        if (index < 0) {
            return null;
        }
        var wire = template.getList("parts", Tag.TAG_COMPOUND).getCompound(index);
        if (WIRES.get(wire.getString("id")).isCenterPart()) {
            return null;
        }
        var side = Direction.from3DDataValue(wire.getByte("side"));
        var support = context.pos().relative(side);
        return context.level().isLoaded(support)
                && PlacementLib.canPlaceWireOnSide(context.level(), support, side.getOpposite())
                ? null : MMIntegrationText.SKIP_PROJECTRED_NO_WIRE_SURFACE.text();
    }

    static CompoundTag routed(CompoundTag template, Set<Direction> neighbours, HolderLookup.Provider registries) {
        int index = wire(template);
        if (index < 0) {
            return template;
        }
        var saved = template.getList("parts", Tag.TAG_COMPOUND).getCompound(index);
        var type = WIRES.get(saved.getString("id"));
        var wire = MultiPartRegistries.savePart(new CompoundTag(), type.newPart(), registries);
        if (type.isCenterPart()) {
            if (saved.contains("mat", Tag.TAG_STRING)) {
                wire.putString("mat", saved.getString("mat"));
            }
            wire.putInt("connMap", WireConnections.centre(neighbours));
        } else {
            wire.putByte("side", saved.getByte("side"));
            wire.putInt("connMap", WireConnections.face(saved.getByte("side"), neighbours));
        }
        var parts = new ListTag();
        parts.add(wire);
        var payload = new CompoundTag();
        payload.put("parts", parts);
        return payload;
    }

    private static int wire(CompoundTag template) {
        var parts = template.getList("parts", Tag.TAG_COMPOUND);
        for (int i = 0; i < parts.size(); i++) {
            if (WIRES.containsKey(parts.getCompound(i).getString("id"))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean holds(@Nullable BlockEntity blockEntity, CompoundTag wire) {
        if (!(blockEntity instanceof TileMultipart tile)) {
            return false;
        }
        var id = wire.getString("id");
        for (var part : tile.getPartList()) {
            var type = MultiPartRegistries.multipartTypes().getKey(part.getType());
            if (type != null && type.toString().equals(id)
                    && (!(part instanceof BaseFaceWirePart face) || face.getSide() == wire.getByte("side"))) {
                return true;
            }
        }
        return false;
    }
}
