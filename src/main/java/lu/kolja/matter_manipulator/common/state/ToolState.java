package lu.kolja.matter_manipulator.common.state;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lu.kolja.matter_manipulator.common.blueprint.BlueprintRef;
import lu.kolja.matter_manipulator.common.manipulator.BlockSlot;
import lu.kolja.matter_manipulator.common.manipulator.CableRouteMode;
import lu.kolja.matter_manipulator.common.manipulator.ManipulatorMode;
import lu.kolja.matter_manipulator.common.manipulator.ManipulatorTier;
import lu.kolja.matter_manipulator.common.manipulator.RemoveMode;
import lu.kolja.matter_manipulator.common.manipulator.ShapeType;
import lu.kolja.matter_manipulator.common.manipulator.StackAxis;
import lu.kolja.matter_manipulator.common.manipulator.ToolTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class ToolState {

    private static final String ROOT = "MatterManipulator";
    private static final String TAG_MODE = "Mode";
    private static final String TAG_SHAPE = "Shape";
    private static final String TAG_REMOVE = "Remove";
    private static final String TAG_DIMENSION = "Dimension";
    private static final String TAG_COORDS = "Coords";
    private static final String TAG_SLOTS = "Slots";
    private static final String TAG_SLOT_CAPTURES = "SlotCaptures";
    private static final String TAG_REPLACE = "ReplaceWhitelist";
    private static final String TAG_AE_LINK = "AeLink";
    private static final String TAG_UPLINK = "Uplink";
    private static final String TAG_TRANSFORM = "Transform";
    private static final String TAG_FLOATING_PLACEMENT = "FloatingPlacement";
    private static final String TAG_FLOATING_DISTANCE = "FloatingDistance";
    private static final String TAG_AUTO_CRAFT_MISSING = "AutoCraftMissing";
    private static final String TAG_CABLE_ROUTE_MODE = "CableRouteMode";
    private static final String TAG_CABLE_WAYPOINTS = "CableWaypoints";
    private static final String TAG_BLUEPRINT = "Blueprint";
    private static final String TAG_PLACEMENT_SPEED = "PlacementSpeed";
    public static final int MAX_CABLE_WAYPOINTS = 256;

    private final ItemStack stack;
    private CustomData read;
    private CompoundTag root;

    public static ToolState get(ItemStack stack) {
        return new ToolState(stack);
    }

    private CompoundTag root() {
        var data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        if (data != read) {
            read = data;
            root = data.copyTag().getCompoundOrEmpty(ROOT);
        }
        return root;
    }

    private void save() {
        var current = root();
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.put(ROOT, current));
        read = stack.get(DataComponents.CUSTOM_DATA);
    }

    public static @Nullable CompoundTag existingTag(ItemStack stack) {
        var tag = stackTag(stack);
        return tag.getCompound(ROOT).orElse(null);
    }

    public static CompoundTag stackTag(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    public static void setStackTag(ItemStack stack, CompoundTag tag) {
        CustomData.set(DataComponents.CUSTOM_DATA, stack, tag);
    }

    public int previewHash() {
        return root().hashCode();
    }

    public CompoundTag copyTag() {
        return root().copy();
    }

    public static int placementSpeed(ItemStack stack, ManipulatorTier tier) {
        int stored = stackTag(stack).getIntOr(TAG_PLACEMENT_SPEED, 0);
        return stored <= 0 ? tier.blocksPerSecond() : Math.min(stored, tier.blocksPerSecond());
    }

    public static void setPlacementSpeed(ItemStack stack, int blocksPerSecond) {
        var tag = stackTag(stack);
        tag.putInt(TAG_PLACEMENT_SPEED, blocksPerSecond);
        setStackTag(stack, tag);
    }

    public ManipulatorMode mode() {
        return ManipulatorMode.byName(root().getStringOr(TAG_MODE, ""));
    }

    public void setMode(ManipulatorMode mode) {
        root().putString(TAG_MODE, mode.getSerializedName());
        save();
    }

    public ShapeType shape() {
        return ShapeType.byName(root().getStringOr(TAG_SHAPE, ""));
    }

    public void setShape(ShapeType shape) {
        root().putString(TAG_SHAPE, shape.getSerializedName());
        save();
    }

    public RemoveMode removeMode() {
        return RemoveMode.byName(root().getStringOr(TAG_REMOVE, ""));
    }

    public void setRemoveMode(RemoveMode mode) {
        root().putString(TAG_REMOVE, mode.getSerializedName());
        save();
    }

    public CableRouteMode cableRouteMode() {
        return CableRouteMode.byName(root().getStringOr(TAG_CABLE_ROUTE_MODE, ""));
    }

    public void setCableRouteMode(CableRouteMode mode) {
        root().putString(TAG_CABLE_ROUTE_MODE, mode.getSerializedName());
        save();
    }

    public void cycleCableRouteMode() {
        setCableRouteMode(cableRouteMode().next());
    }

    public boolean floatingPlacement() {
        return root().getBooleanOr(TAG_FLOATING_PLACEMENT, false);
    }

    public void setFloatingPlacement(boolean floatingPlacement) {
        root().putBoolean(TAG_FLOATING_PLACEMENT, floatingPlacement);
        save();
    }

    public void toggleFloatingPlacement() {
        setFloatingPlacement(!floatingPlacement());
    }

    public int floatingDistance() {
        return root().getIntOr(TAG_FLOATING_DISTANCE, 0);
    }

    public void setFloatingDistance(int distance) {
        root().putInt(TAG_FLOATING_DISTANCE, distance);
        save();
    }

    public boolean autoCraftMissing() {
        return root().getBooleanOr(TAG_AUTO_CRAFT_MISSING, false);
    }

    public void setAutoCraftMissing(boolean autoCraftMissing) {
        root().putBoolean(TAG_AUTO_CRAFT_MISSING, autoCraftMissing);
        save();
    }

    public void toggleAutoCraftMissing() {
        setAutoCraftMissing(!autoCraftMissing());
    }

    public boolean enterDimension(ResourceKey<Level> dimension) {
        var id = dimension.identifier().toString();
        boolean cleared = root().contains(TAG_DIMENSION) && !root().getStringOr(TAG_DIMENSION, "").equals(id);
        if (cleared) {
            clearCableSelection();
        }
        root().putString(TAG_DIMENSION, id);
        save();
        return cleared;
    }

    public boolean isSameDimension(Level level) {
        if (!root().contains(TAG_DIMENSION)) {
            return true;
        }
        return root().getStringOr(TAG_DIMENSION, "").equals(level.dimension().identifier().toString());
    }

    public Optional<Identifier> dimensionId() {
        if (!root().contains(TAG_DIMENSION)) {
            return Optional.empty();
        }
        return Optional.ofNullable(Identifier.tryParse(root().getStringOr(TAG_DIMENSION, "")));
    }

    public Optional<BlockPos> coord(String key) {
        var coords = root().getCompoundOrEmpty(TAG_COORDS);
        if (!coords.contains(key)) {
            return Optional.empty();
        }
        var tag = coords.getCompoundOrEmpty(key);
        return Optional.of(new BlockPos(tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0)));
    }

    public Optional<BlockPos> coordA() {
        return coord("A");
    }

    public Optional<BlockPos> coordB() {
        return coord("B");
    }

    public Optional<BlockPos> coordC() {
        return coord("C");
    }

    public String storeNextCoord(Level level, BlockPos pos) {
        return storeNextCoord(level.dimension(), pos);
    }

    public String storeNextCoord(ResourceKey<Level> dimension, BlockPos pos) {
        enterDimension(dimension);
        if (mode() == ManipulatorMode.CABLE) {
            if (coordA().isEmpty()) {
                setCoord("A", pos);
                return "A";
            }
            if (coordB().isEmpty()) {
                setCoord("B", pos);
                return "B";
            }
            clearCoords();
            setCoord("A", pos);
            return "A";
        }
        if (coordA().isEmpty()) {
            setCoord("A", pos);
            return "A";
        }
        if (coordB().isEmpty()) {
            setCoord("B", pos);
            return "B";
        }
        if (coordC().isEmpty()) {
            setCoord("C", pos);
            return "C";
        }
        clearCoords();
        setCoord("A", pos);
        return "A";
    }

    public void setCoord(String key, BlockPos pos) {
        var coords = root().getCompoundOrEmpty(TAG_COORDS);
        var tag = new CompoundTag();
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        coords.put(key, tag);
        root().put(TAG_COORDS, coords);
        save();
    }

    public void setCoord(String key, BlockPos pos, ResourceKey<Level> dimension) {
        setCoord(key, pos);
        root().getCompoundOrEmpty(TAG_COORDS).getCompoundOrEmpty(key).putString(TAG_DIMENSION, dimension.identifier().toString());
        save();
    }

    public Optional<Identifier> coordDimension(String key) {
        var tag = root().getCompoundOrEmpty(TAG_COORDS).getCompoundOrEmpty(key);
        if (!tag.contains(TAG_DIMENSION)) {
            return Optional.empty();
        }
        return Optional.ofNullable(Identifier.tryParse(tag.getStringOr(TAG_DIMENSION, "")));
    }

    public void clearCoord(String key) {
        var coords = root().getCompoundOrEmpty(TAG_COORDS);
        coords.remove(key);
        if (coords.isEmpty()) {
            root().remove(TAG_COORDS);
        } else {
            root().put(TAG_COORDS, coords);
        }
        save();
    }

    public void clearCoords() {
        root().remove(TAG_COORDS);
        save();
    }

    public List<BlockPos> cableWaypoints() {
        var tags = root().getListOrEmpty(TAG_CABLE_WAYPOINTS);
        int count = Math.min(tags.size(), MAX_CABLE_WAYPOINTS);
        var waypoints = new ArrayList<BlockPos>(count);
        for (int i = 0; i < count; i++) {
            var tag = tags.getCompoundOrEmpty(i);
            waypoints.add(new BlockPos(tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0)));
        }
        return List.copyOf(waypoints);
    }

    public boolean appendCableWaypoint(Level level, BlockPos pos) {
        return appendCableWaypoint(level.dimension(), pos);
    }

    public boolean appendCableWaypoint(ResourceKey<Level> dimension, BlockPos pos) {
        enterDimension(dimension);
        var waypoints = cableWaypoints();
        if (!waypoints.isEmpty() && waypoints.get(waypoints.size() - 1).equals(pos)
                || waypoints.size() >= MAX_CABLE_WAYPOINTS) {
            return false;
        }
        var tags = root().getListOrEmpty(TAG_CABLE_WAYPOINTS);
        var tag = new CompoundTag();
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        tags.add(tag);
        root().put(TAG_CABLE_WAYPOINTS, tags);
        save();
        return true;
    }

    public void moveCableWaypoints(Direction direction, int amount) {
        var tags = root().getListOrEmpty(TAG_CABLE_WAYPOINTS);
        for (int i = 0; i < tags.size(); i++) {
            var tag = tags.getCompoundOrEmpty(i);
            tag.putInt("x", tag.getIntOr("x", 0) + direction.getStepX() * amount);
            tag.putInt("y", tag.getIntOr("y", 0) + direction.getStepY() * amount);
            tag.putInt("z", tag.getIntOr("z", 0) + direction.getStepZ() * amount);
        }
        save();
    }

    public void clearCableWaypoints() {
        root().remove(TAG_CABLE_WAYPOINTS);
        save();
    }

    public void clearCableSelection() {
        clearCoords();
        clearCableWaypoints();
    }

    public void clearSelection() {
        if (mode() == ManipulatorMode.BLUEPRINT) {
            clearCoord("C");
        } else {
            clearCableSelection();
        }
    }

    public Optional<BlockStateSpec> slot(BlockSlot slot) {
        var slots = root().getCompoundOrEmpty(TAG_SLOTS);
        if (!slots.contains(slot.key())) {
            return Optional.empty();
        }
        return BlockStateSpec.fromTag(slots.getCompoundOrEmpty(slot.key()));
    }

    public void setSlot(BlockSlot slot, BlockStateSpec spec) {
        var slots = root().getCompoundOrEmpty(TAG_SLOTS);
        slots.put(slot.key(), spec.save());
        root().put(TAG_SLOTS, slots);
        var capturedSlots = root().getCompoundOrEmpty(TAG_SLOT_CAPTURES);
        capturedSlots.remove(slot.key());
        if (capturedSlots.isEmpty()) {
            root().remove(TAG_SLOT_CAPTURES);
        } else {
            root().put(TAG_SLOT_CAPTURES, capturedSlots);
        }
        save();
    }

    public Optional<CapturedBlock> capturedSlot(BlockSlot slot) {
        var slots = root().getCompoundOrEmpty(TAG_SLOT_CAPTURES);
        if (!slots.contains(slot.key())) {
            return Optional.empty();
        }
        return CapturedBlock.fromTag(slots.getCompoundOrEmpty(slot.key()));
    }

    public void setCapturedSlot(BlockSlot slot, CapturedBlock captured) {
        setSlot(slot, captured.state());
        var slots = root().getCompoundOrEmpty(TAG_SLOT_CAPTURES);
        slots.put(slot.key(), captured.save());
        root().put(TAG_SLOT_CAPTURES, slots);
        save();
    }

    public Optional<BlockStateSpec> preferredSlot(BlockSlot slot) {
        var spec = slot(slot);
        if (spec.isPresent()) {
            return spec;
        }
        return switch (slot) {
            case CORNER, EDGE, FACE, CABLE, EXCHANGE_TARGET -> slot(BlockSlot.VOLUME);
            case VOLUME -> Optional.empty();
        };
    }

    public Optional<CapturedBlock> preferredCapturedSlot(BlockSlot slot) {
        if (slot(slot).isPresent()) {
            return capturedSlot(slot);
        }
        return switch (slot) {
            case CORNER, EDGE, FACE, CABLE, EXCHANGE_TARGET -> capturedSlot(BlockSlot.VOLUME);
            case VOLUME -> Optional.empty();
        };
    }

    public Optional<BlockStateSpec> exchangeTarget() {
        var target = slot(BlockSlot.EXCHANGE_TARGET);
        return target.isPresent() ? target : slot(BlockSlot.VOLUME);
    }

    public Optional<BlockStateSpec> replaceWhitelist() {
        if (!root().contains(TAG_REPLACE)) {
            return Optional.empty();
        }
        return BlockStateSpec.fromTag(root().getCompoundOrEmpty(TAG_REPLACE));
    }

    public void setReplaceWhitelist(BlockStateSpec spec) {
        root().put(TAG_REPLACE, spec.save());
        save();
    }

    public ToolTransform transform() {
        var tag = root().getCompoundOrEmpty(TAG_TRANSFORM);
        return new ToolTransform(
                tag.getIntOr("rotX", 0),
                tag.contains("rotY") ? tag.getIntOr("rotY", 0) : tag.getIntOr("rotation", 0),
                tag.getIntOr("rotZ", 0),
                tag.getBooleanOr("mirrorX", false),
                tag.getBooleanOr("mirrorY", false),
                tag.getBooleanOr("mirrorZ", false),
                tag.contains("stackCount") ? tag.getIntOr("stackCount", 0) : 1,
                StackAxis.byName(tag.getStringOr("stackAxis", ""))
        );
    }

    public void setTransform(ToolTransform transform) {
        var tag = new CompoundTag();
        tag.putInt("rotX", transform.rotationX());
        tag.putInt("rotY", transform.rotationY());
        tag.putInt("rotZ", transform.rotationZ());
        tag.putBoolean("mirrorX", transform.mirrorX());
        tag.putBoolean("mirrorY", transform.mirrorY());
        tag.putBoolean("mirrorZ", transform.mirrorZ());
        tag.putInt("stackCount", transform.stackCount());
        tag.putString("stackAxis", transform.stackAxis().name());
        root().put(TAG_TRANSFORM, tag);
        save();
    }

    public void rotateTransform(Direction.Axis axis) {
        setTransform(transform().rotate(axis));
    }

    public void toggleMirrorX() {
        setTransform(transform().toggleMirrorX());
    }

    public void toggleMirrorY() {
        setTransform(transform().toggleMirrorY());
    }

    public void toggleMirrorZ() {
        setTransform(transform().toggleMirrorZ());
    }

    public void changeStackCount(int delta) {
        var transform = transform();
        setTransform(transform.withStackCount(transform.stackCount() + delta));
    }

    public void cycleStackAxis() {
        setTransform(transform().cycleStackAxis());
    }

    public Optional<BlueprintRef> blueprintRef() {
        return BlueprintRef.fromTag(root().getCompoundOrEmpty(TAG_BLUEPRINT));
    }

    public void setBlueprintRef(BlueprintRef ref) {
        root().put(TAG_BLUEPRINT, ref.save());
        save();
    }

    public void clearBlueprintRef() {
        root().remove(TAG_BLUEPRINT);
        save();
    }

    public CompoundTag aeLinkTag() {
        return root().getCompoundOrEmpty(TAG_AE_LINK);
    }

    public void setAeLinkTag(CompoundTag tag) {
        root().put(TAG_AE_LINK, tag.copy());
        save();
    }

    public CompoundTag uplinkTag() {
        return root().getCompoundOrEmpty(TAG_UPLINK);
    }

    public void setUplinkTag(CompoundTag tag) {
        root().put(TAG_UPLINK, tag.copy());
        save();
    }
}
