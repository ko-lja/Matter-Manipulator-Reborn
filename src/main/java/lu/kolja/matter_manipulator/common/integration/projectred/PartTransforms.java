package lu.kolja.matter_manipulator.common.integration.projectred;

import codechicken.lib.vec.Rotation;
import codechicken.multipart.init.MultiPartRegistries;
import codechicken.multipart.minecraft.McSidedStatePart;
import codechicken.multipart.util.PartMap;
import lu.kolja.matter_manipulator.api.DirectionTransform;
import lu.kolja.matter_manipulator.common.manipulator.ToolTransform;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

public final class PartTransforms {

    private static final HolderLookup.Provider BLOCK_STATE_REGISTRIES =
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    public enum Outcome {
        TURNED,
        UNMIRRORED,
        CANNOT_FACE
    }

    private PartTransforms() {}

    static boolean isIdentity(DirectionTransform transform) {
        return transform.transform(Direction.EAST) == Direction.EAST
                && transform.transform(Direction.UP) == Direction.UP
                && transform.transform(Direction.SOUTH) == Direction.SOUTH;
    }

    public static boolean mirrors(DirectionTransform transform) {
        var east = transform.transform(Direction.EAST).getNormal();
        var up = transform.transform(Direction.UP).getNormal();
        return !east.cross(up).equals(transform.transform(Direction.SOUTH).getNormal());
    }

    public static Outcome transform(CompoundTag part, DirectionTransform transform) {
        var id = ResourceLocation.tryParse(part.getString("id"));
        if (id == null) {
            return Outcome.TURNED;
        }
        if (id.getNamespace().equals("cb_microblock")) {
            transformMicroblock(part, id.getPath(), transform);
            return Outcome.TURNED;
        }
        if (id.getNamespace().equals("minecraft") && part.contains("state", Tag.TAG_COMPOUND)) {
            return transformMinecraftPart(part, transform);
        }
        if (!id.getNamespace().startsWith("projectred_")) {
            return Outcome.TURNED;
        }
        if (part.contains("connMap", Tag.TAG_INT)) {
            part.putInt("connMap", 0);
        }
        for (var saved : part.getList("payloads", Tag.TAG_COMPOUND)) {
            var payload = (CompoundTag) saved;
            for (var key : new String[]{"input_dir", "output_dir"}) {
                int side = payload.getInt(key);
                if (side >= 0) {
                    payload.putInt(key, side(transform, side));
                }
            }
        }
        if (part.contains("orient", Tag.TAG_BYTE)) {
            return transformGate(part, id, transform);
        }
        if (part.contains("side", Tag.TAG_BYTE)) {
            part.putByte("side", (byte) side(transform, part.getByte("side")));
        }
        return Outcome.TURNED;
    }

    private static void transformMicroblock(CompoundTag part, String kind, DirectionTransform transform) {
        int shape = part.getByte("shape") & 0xFF;
        int slot = shape & 0xF;
        int turned = switch (kind) {
            case "face", "hollow" -> side(transform, slot);
            case "corner" -> corner(transform, slot);
            case "edge" -> edge(transform, slot);
            case "post" -> postAxis(transform, slot);
            default -> slot;
        };
        part.putByte("shape", (byte) (shape & 0xF0 | turned));
    }

    private static int corner(DirectionTransform transform, int corner) {
        var turned = transform.transformOffset(new Vec3i(
                (corner & 4) != 0 ? 1 : -1, (corner & 1) != 0 ? 1 : -1, (corner & 2) != 0 ? 1 : -1));
        return (turned.getX() > 0 ? 4 : 0) | (turned.getY() > 0 ? 1 : 0) | (turned.getZ() > 0 ? 2 : 0);
    }

    private static int edge(DirectionTransform transform, int edge) {
        int axis = edge >> 2;
        int bits = PartMap.unpackEdgeBits(edge);
        var turned = transform.transformOffset(new Vec3i(
                axis == 2 ? 0 : (bits & 4) != 0 ? 1 : -1,
                axis == 0 ? 0 : (bits & 1) != 0 ? 1 : -1,
                axis == 1 ? 0 : (bits & 2) != 0 ? 1 : -1));
        int turnedAxis = turned.getY() == 0 ? 0 : turned.getZ() == 0 ? 1 : 2;
        int turnedBits = (turned.getX() > 0 ? 4 : 0) | (turned.getY() > 0 ? 1 : 0) | (turned.getZ() > 0 ? 2 : 0);
        return PartMap.packEdgeBits(turnedAxis << 2, turnedBits);
    }

    private static int postAxis(DirectionTransform transform, int axis) {
        return side(transform, axis << 1) >> 1;
    }

    private static Outcome transformGate(CompoundTag part, ResourceLocation id, DirectionTransform transform) {
        int orient = part.getByte("orient") & 0xFF;
        int side = orient >> 2;
        int front = Rotation.rotateSide(side, ((orient & 3) + 2) % 4);
        int turnedSide = side(transform, side);
        int turnedFront = side(transform, front);
        part.putByte("orient", (byte) (turnedSide << 2 | (Rotation.rotationTo(turnedSide, turnedFront) + 2) % 4));
        if (!mirrors(transform)) {
            return Outcome.TURNED;
        }
        int shape = part.getByte("shape") & 0xFF;
        boolean stateBySide = true;
        switch (id.getPath()) {
            case "or_gate", "nor_gate", "and_gate", "nand_gate", "not_gate", "randomizer_gate" ->
                    part.putByte("shape", (byte) swapBits(shape, 0, 2));
            case "buffer_gate" -> part.putByte("shape", (byte) swapBits(shape, 0, 1));
            case "transparent_latch_gate", "sequencer_gate", "state_cell_gate", "bus_transceiver_gate" ->
                    part.putByte("shape", (byte) (shape ^ 1));
            case "counter_gate" -> {
                part.putByte("shape", (byte) (shape ^ 1));
                stateBySide = false;
            }
            case "sr_latch_gate" -> {
                part.putByte("shape", (byte) (shape ^ 1));
                part.putByte("state2", (byte) swapBits(part.getByte("state2") & 0xFF, 1, 3));
            }
            case "synchronizer_gate" -> part.putByte("state2", (byte) swapBits(part.getByte("state2") & 0xFF, 0, 1));
            case "comparator_gate" -> {
                int levels = part.getShort("state2") & 0xFFFF;
                part.putShort("state2", (short) (levels & 0x0F0F | (levels & 0xF0) << 8 | (levels & 0xF000) >> 8));
            }
            case "dec_randomizer_gate" -> {
                if (shape != 0) {
                    return withoutMirrorImage();
                }
            }
            case "segment_display_gate", "null_cell_gate", "invert_cell_gate", "buffer_cell_gate" -> stateBySide = false;
            case "xor_gate", "xnor_gate", "pulse_gate", "repeater_gate", "toggle_latch_gate", "light_sensor_gate",
                 "rain_sensor_gate", "timer_gate", "and_cell_gate", "stacking_latch_gate", "bus_randomizer_gate",
                 "bus_converter_gate", "bus_input_panel_gate" -> {}
            default -> {
                return withoutMirrorImage();
            }
        }
        if (stateBySide && part.contains("state", Tag.TAG_BYTE)) {
            part.putByte("state", (byte) swapBits(swapBits(part.getByte("state") & 0xFF, 1, 3), 5, 7));
        }
        return Outcome.TURNED;
    }

    private static Outcome withoutMirrorImage() {
        return Outcome.UNMIRRORED;
    }

    private static Outcome transformMinecraftPart(CompoundTag part, DirectionTransform transform) {
        if (!(transform instanceof ToolTransform tool)
                || !(MultiPartRegistries.loadPart(part.copy(), BLOCK_STATE_REGISTRIES) instanceof McSidedStatePart before)) {
            return Outcome.CANNOT_FACE;
        }
        var turned = tool.transformApproximately(before.state).orElse(null);
        if (turned == null) {
            return Outcome.CANNOT_FACE;
        }
        part.put("state", NbtUtils.writeBlockState(turned));
        return MultiPartRegistries.loadPart(part.copy(), BLOCK_STATE_REGISTRIES) instanceof McSidedStatePart after
                && after.getSide() == transform.transform(before.getSide()) ? Outcome.TURNED : Outcome.CANNOT_FACE;
    }

    static int side(DirectionTransform transform, int side) {
        return transform.transform(Direction.from3DDataValue(side)).get3DDataValue();
    }

    private static int swapBits(int value, int a, int b) {
        int bitA = value >> a & 1;
        int bitB = value >> b & 1;
        return bitA == bitB ? value : value ^ (1 << a | 1 << b);
    }
}
