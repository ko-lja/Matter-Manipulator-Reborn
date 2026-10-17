package lu.kolja.matter_manipulator.common.integration.framedblocks;

import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.api.BlockIntegration;
import lu.kolja.matter_manipulator.api.DirectionTransform;
import lu.kolja.matter_manipulator.api.IntegrationContext;
import lu.kolja.matter_manipulator.api.IntegrationRegistrar;
import lu.kolja.matter_manipulator.api.ItemRequirement;
import lu.kolja.matter_manipulator.api.PlacementPreparationPlan;
import lu.kolja.matter_manipulator.api.PlacementPurpose;
import lu.kolja.matter_manipulator.api.PlacementRequirements;
import lu.kolja.matter_manipulator.common.localization.MMIntegrationText;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.neoforge.common.Tags;
import org.jetbrains.annotations.Nullable;
import io.github.xfacthd.framedblocks.api.block.FramedProperties;
import io.github.xfacthd.framedblocks.api.block.IFramedBlock;
import io.github.xfacthd.framedblocks.api.block.blockentity.FramedBlockEntity;
import io.github.xfacthd.framedblocks.api.block.blockentity.FramedDoubleBlockEntity;
import io.github.xfacthd.framedblocks.api.block.blockentity.IFramedBlockEntity;
import io.github.xfacthd.framedblocks.api.camo.CamoContainer;
import io.github.xfacthd.framedblocks.api.camo.CamoContainerHelper;
import io.github.xfacthd.framedblocks.api.camo.empty.EmptyCamoContainer;
import io.github.xfacthd.framedblocks.api.util.ConfigView;
import io.github.xfacthd.framedblocks.api.util.FramedConstants;
import io.github.xfacthd.framedblocks.common.FBContent;
import io.github.xfacthd.framedblocks.common.block.cube.FramedCollapsibleBlock;
import io.github.xfacthd.framedblocks.common.block.cube.FramedCollapsibleCopycatBlock;
import io.github.xfacthd.framedblocks.common.block.slab.FramedAdjustableDoubleBlock;
import io.github.xfacthd.framedblocks.common.block.slab.FramedAdjustableDoubleSlabBlock;
import io.github.xfacthd.framedblocks.common.block.slab.FramedCheckeredCubeBlock;
import io.github.xfacthd.framedblocks.common.block.slab.FramedCheckeredPanelBlock;
import io.github.xfacthd.framedblocks.common.block.slab.FramedCheckeredSlabBlock;
import io.github.xfacthd.framedblocks.common.block.slab.FramedDoubleSlabBlock;
import io.github.xfacthd.framedblocks.common.blockentity.doubled.slab.FramedAdjustableDoubleBlockEntity;
import io.github.xfacthd.framedblocks.common.blockentity.special.FramedChiseledBookshelfBlockEntity;
import io.github.xfacthd.framedblocks.common.blockentity.special.FramedCollapsibleBlockEntity;
import io.github.xfacthd.framedblocks.common.blockentity.special.FramedCollapsibleCopycatBlockEntity;
import io.github.xfacthd.framedblocks.common.blockentity.special.FramedOwnableBlockEntity;
import io.github.xfacthd.framedblocks.common.blockentity.special.FramedStorageBlockEntity;
import io.github.xfacthd.framedblocks.common.data.BlockType;
import io.github.xfacthd.framedblocks.common.data.PropertyHolder;
import io.github.xfacthd.framedblocks.common.data.property.NullableDirection;

import java.util.List;

public final class FramedBlocksIntegration {

    public static final Identifier CAMO_ID = Identifier.fromNamespaceAndPath("framedblocks", "camo");

    public static final String CAMO = FramedBlockEntity.CAMO_NBT_KEY;
    public static final String CAMO_TWO = FramedDoubleBlockEntity.CAMO_TWO_NBT_KEY;
    public static final String GLOWING = "glowing";
    public static final String INTANGIBLE = "intangible";
    public static final String REINFORCED = "reinforced";
    public static final String OFFSETS = "offsets";
    public static final String FIRST_HEIGHT = "first_height";

    private static final List<String> CAMO_KEYS = List.of(CAMO, CAMO_TWO);
    private static final List<String> SHAPE_KEYS = List.of(OFFSETS, FIRST_HEIGHT);

    private FramedBlocksIntegration() {}

    public static void register(IntegrationRegistrar registrar) {
        registrar.block(CAMO_ID, new CamoIntegration(), state -> state.getBlock() instanceof IFramedBlock);
    }

    public static CompoundTag payloadOf(IFramedBlockEntity framed) {
        var payload = new CompoundTag();
        putCamo(payload, CAMO, framed.getCamo());
        if (framed instanceof FramedDoubleBlockEntity doubleBlock) {
            putCamo(payload, CAMO_TWO, doubleBlock.getCamoTwo());
        }
        if (framed.isGlowing()) {
            payload.putBoolean(GLOWING, true);
        }
        if (framed.isMarkedIntangible()) {
            payload.putBoolean(INTANGIBLE, true);
        }
        if (framed.isReinforced()) {
            payload.putBoolean(REINFORCED, true);
        }
        if (hasShape(framed)) {
            var shaped = framed.unwrap();
            var saved = shaped.saveCustomOnly(shaped.getLevel().registryAccess());
            for (var key : SHAPE_KEYS) {
                if (saved.contains(key)) {
                    payload.put(key, saved.get(key).copy());
                }
            }
        }
        return payload;
    }

    public static CompoundTag transformTag(BlockState state, CompoundTag tag, DirectionTransform transform) {
        var block = state.getBlock();
        if (isCheckered(block)) {
            if (FramedShapeTransforms.flipsParity(transform)) {
                swapCamos(tag);
            }
        } else if (block instanceof FramedDoubleSlabBlock || block instanceof FramedAdjustableDoubleSlabBlock) {
            if (transform.transform(Direction.UP) == Direction.DOWN) {
                swapCamos(tag);
                if (tag.contains(FIRST_HEIGHT)) {
                    tag.putInt(FIRST_HEIGHT, 16 - tag.getIntOr(FIRST_HEIGHT, 0));
                }
            }
        } else if (block instanceof FramedCollapsibleBlock) {
            var face = state.getValue(PropertyHolder.NULLABLE_FACE).toDirection();
            if (tag.contains(OFFSETS) && face != null) {
                tag.putInt(OFFSETS, FramedShapeTransforms.permuteVertexOffsets(tag.getIntOr(OFFSETS, 0), face, transform));
            }
        } else if (block instanceof FramedCollapsibleCopycatBlock) {
            if (tag.contains(OFFSETS)) {
                tag.putInt(OFFSETS, FramedShapeTransforms.permuteFaceOffsets(tag.getIntOr(OFFSETS, 0), transform));
            }
        }
        return tag;
    }

    private static boolean transformsTag(Block block) {
        return isCheckered(block) || block instanceof FramedDoubleSlabBlock || block instanceof FramedAdjustableDoubleSlabBlock
                || block instanceof FramedCollapsibleBlock || block instanceof FramedCollapsibleCopycatBlock;
    }

    private static boolean isCheckered(Block block) {
        return block instanceof FramedCheckeredCubeBlock || block instanceof FramedCheckeredSlabBlock
                || block instanceof FramedCheckeredPanelBlock;
    }

    private static boolean hasShape(IFramedBlockEntity framed) {
        return framed instanceof FramedCollapsibleBlockEntity || framed instanceof FramedCollapsibleCopycatBlockEntity
                || framed instanceof FramedAdjustableDoubleBlockEntity;
    }

    private static void putCamo(CompoundTag payload, String key, CamoContainer<?, ?> camo) {
        if (!camo.isEmpty()) {
            payload.store(key, CamoContainerHelper.CODEC, camo);
        }
    }

    public static CamoContainer<?, ?> camo(CompoundTag payload, String key) {
        return payload.read(key, CamoContainerHelper.CODEC).orElse(EmptyCamoContainer.EMPTY);
    }

    private static void swapCamos(CompoundTag tag) {
        var first = tag.get(CAMO);
        var second = tag.get(CAMO_TWO);
        tag.remove(CAMO);
        tag.remove(CAMO_TWO);
        if (second != null) {
            tag.put(CAMO, second);
        }
        if (first != null) {
            tag.put(CAMO_TWO, first);
        }
    }

    private static boolean validCamo(CamoContainer<?, ?> camo) {
        return !camo.isEmpty() && CamoContainerHelper.validateCamo(camo);
    }

    private static boolean hasUnpayableCamo(CompoundTag payload) {
        for (var key : CAMO_KEYS) {
            if (payload.contains(key) && !camo(payload, key).canTriviallyConvertToItemStack()) {
                return true;
            }
        }
        return false;
    }

    private static final class CamoIntegration implements BlockIntegration {

        @Override
        public CompoundTag capture(IntegrationContext.Capture context) {
            return context.blockEntity() instanceof IFramedBlockEntity framed ? payloadOf(framed) : new CompoundTag();
        }

        @Override
        public PlacementPreparationPlan preparation(IntegrationContext.Preparation context, CompoundTag payload) {
            if (!(context.target().getBlock() instanceof IFramedBlock framedBlock)) {
                return PlacementPreparationPlan.unchanged(payload);
            }
            var config = ConfigView.Server.INSTANCE;
            var base = payload.copy();
            if (!config.enableIntangibility() || !framedBlock.getBlockType().allowMakingIntangible()) {
                base.remove(INTANGIBLE);
            }
            for (var key : CAMO_KEYS) {
                if (base.contains(key) && !validCamo(camo(base, key))) {
                    base.remove(key);
                }
            }
            var plan = PlacementPreparationPlan.builder(base);
            if (config.shouldConsumeCamoItem()) {
                for (var key : CAMO_KEYS) {
                    if (!base.contains(key)) {
                        continue;
                    }
                    var camo = camo(base, key);
                    if (camo.canTriviallyConvertToItemStack()) {
                        component(plan, key, CamoContainerHelper.dropCamo(camo));
                    }
                }
            }
            if (base.getBooleanOr(GLOWING, false)) {
                component(plan, GLOWING, ItemRequirement.tag(Tags.Items.DUSTS_GLOWSTONE, 1));
            }
            if (base.getBooleanOr(INTANGIBLE, false)) {
                component(plan, INTANGIBLE, new ItemStack(FramedConstants.Objects.PHANTOM_PASTE));
            }
            if (base.getBooleanOr(REINFORCED, false)) {
                component(plan, REINFORCED, new ItemStack(FramedConstants.Objects.FRAMED_REINFORCEMENT));
            }
            return plan.build();
        }

        private static void component(PlacementPreparationPlan.Builder plan, String key, ItemStack item) {
            if (!item.isEmpty()) {
                component(plan, key, ItemRequirement.exact(item));
            }
        }

        private static void component(PlacementPreparationPlan.Builder plan, String key, ItemRequirement requirement) {
            plan.component(requirement, (prepared, acquired) -> {
                if (acquired.isEmpty()) {
                    prepared.remove(key);
                }
            });
        }

        @Override
        public PlacementRequirements placementRequirements(IntegrationContext.Placement context, CompoundTag payload) {
            if (!(context.target().getBlock() instanceof IFramedBlock framedBlock)) {
                return PlacementRequirements.defaults();
            }
            if (ConfigView.Server.INSTANCE.shouldConsumeCamoItem() && hasUnpayableCamo(payload)) {
                return PlacementRequirements.unsupported(MMIntegrationText.BLOCK_COST_FRAMEDBLOCKS_FLUID_CAMO.text());
            }
            var type = framedBlock.getBlockType();
            if (type == BlockType.FRAMED_DOUBLE_SLAB) {
                return PlacementRequirements.replaceWith(new ItemStack(FBContent.BLOCK_FRAMED_SLAB.value(), 2));
            }
            if (type == BlockType.FRAMED_DOUBLE_PANEL) {
                return PlacementRequirements.replaceWith(new ItemStack(FBContent.BLOCK_FRAMED_PANEL.value(), 2));
            }
            return PlacementRequirements.defaults();
        }

        @Override
        public ApplyResult apply(IntegrationContext.Apply context, CompoundTag payload) {
            if (!(context.blockEntity() instanceof IFramedBlockEntity framed)) {
                return ApplyResult.REJECTED_UNCHANGED;
            }
            if (context.purpose() == PlacementPurpose.MOVE_TRANSFER) {
                return payloadOf(framed).equals(payload) ? ApplyResult.APPLIED : ApplyResult.REJECTED_UNCHANGED;
            }
            if (context.purpose() == PlacementPurpose.RECONFIGURE) {
                return ApplyResult.REJECTED_UNCHANGED;
            }
            applyShape(context, framed, payload);
            if (payload.contains(CAMO)) {
                framed.setCamo(camo(payload, CAMO), false);
            }
            if (payload.contains(CAMO_TWO) && framed instanceof FramedDoubleBlockEntity) {
                framed.setCamo(camo(payload, CAMO_TWO), true);
            }
            framed.setGlowing(payload.getBooleanOr(GLOWING, false));
            framed.setIntangible(payload.getBooleanOr(INTANGIBLE, false));
            framed.setReinforced(payload.getBooleanOr(REINFORCED, false));
            if (framed instanceof FramedOwnableBlockEntity ownable) {
                ownable.setOwner(context.player().getUUID(), true);
            }
            context.blockEntity().setChanged();
            var state = framed.getBlockState();
            context.level().sendBlockUpdated(context.pos(), state, state, Block.UPDATE_ALL);
            return ApplyResult.APPLIED;
        }

        private static void applyShape(IntegrationContext.Apply context, IFramedBlockEntity framed, CompoundTag payload) {
            if (!hasShape(framed)) {
                return;
            }
            var shaped = framed.unwrap();
            var registries = context.level().registryAccess();
            var tag = shaped.saveCustomOnly(registries);
            boolean changed = false;
            for (var key : SHAPE_KEYS) {
                if (payload.contains(key)) {
                    tag.put(key, payload.get(key).copy());
                    changed = true;
                }
            }
            if (!changed) {
                return;
            }
            try (var reporter = new ProblemReporter.ScopedCollector(shaped.problemPath(), MatterManipulator.LOGGER)) {
                shaped.loadCustomOnly(TagValueInput.create(reporter, registries, tag));
            }
            if (framed instanceof FramedCollapsibleCopycatBlockEntity copycat) {
                copycat.updateFaceSolidity();
            }
        }

        @Override
        public CompoundTag transform(IntegrationContext.Transform context, CompoundTag payload) {
            return transformTag(context.state(), payload.copy(), context.transform());
        }

        @Override
        public CompoundTag transformBlockEntity(IntegrationContext.Transform context, CompoundTag blockEntity) {
            return transformTag(context.state(), blockEntity, context.transform());
        }

        @Override
        public boolean transformsBlockEntity(BlockState state) {
            return transformsTag(state.getBlock());
        }

        @Override
        @Nullable
        public BlockState transformState(IntegrationContext.Transform context, @Nullable BlockState transformed) {
            var state = context.state();
            var transform = context.transform();
            var block = state.getBlock();
            if (block instanceof FramedCollapsibleBlock) {
                var face = state.getValue(PropertyHolder.NULLABLE_FACE).toDirection();
                if (face == null) {
                    return transformed;
                }
                var result = state.setValue(PropertyHolder.NULLABLE_FACE,
                        NullableDirection.fromDirection(transform.transform(face)));
                return FramedShapeTransforms.splitLineFlips(face, transform)
                        ? result.cycle(PropertyHolder.ROTATE_SPLIT_LINE) : result;
            }
            if (block instanceof FramedCollapsibleCopycatBlock) {
                return state.setValue(PropertyHolder.SOLID_FACES,
                        FramedShapeTransforms.permuteFaceMask(state.getValue(PropertyHolder.SOLID_FACES), transform));
            }
            if (block instanceof FramedAdjustableDoubleBlock adjustable) {
                var turned = transform.transform(adjustable.getFacing(state));
                if (block instanceof FramedAdjustableDoubleSlabBlock) {
                    return turned.getAxis().isVertical() ? state : null;
                }
                return turned.getAxis().isHorizontal() ? state.setValue(FramedProperties.FACING_HOR, turned) : null;
            }
            return transformed;
        }

        @Override
        public void beforeRemove(IntegrationContext.Removal context) {
            var blockEntity = context.blockEntity();
            if (blockEntity instanceof FramedStorageBlockEntity storage) {
                if (context.collectDrops()) {
                    var handler = storage.getItemHandler();
                    for (int slot = 0; slot < handler.size(); slot++) {
                        int amount = handler.getAmountAsInt(slot);
                        if (amount > 0) {
                            context.dropSink().accept(handler.getResource(slot).toStack(amount));
                        }
                    }
                }
                storage.clearContent();
            } else if (blockEntity instanceof FramedChiseledBookshelfBlockEntity bookshelf) {
                if (context.collectDrops()) {
                    for (int slot = 0; slot < bookshelf.getContainerSize(); slot++) {
                        if (!bookshelf.getItem(slot).isEmpty()) {
                            context.dropSink().accept(bookshelf.getItem(slot).copy());
                        }
                    }
                }
                bookshelf.clearContent();
            }
        }
    }
}
