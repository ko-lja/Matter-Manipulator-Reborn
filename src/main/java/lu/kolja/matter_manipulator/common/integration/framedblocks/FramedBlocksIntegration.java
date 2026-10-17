package lu.kolja.matter_manipulator.common.integration.framedblocks;

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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;
import org.jetbrains.annotations.Nullable;
import xfacthd.framedblocks.api.block.FramedProperties;
import xfacthd.framedblocks.api.block.IFramedBlock;
import xfacthd.framedblocks.api.block.blockentity.FramedBlockEntity;
import xfacthd.framedblocks.api.block.blockentity.IFramedDoubleBlockEntity;
import xfacthd.framedblocks.api.camo.CamoContainer;
import xfacthd.framedblocks.api.camo.CamoContainerHelper;
import xfacthd.framedblocks.api.util.ConfigView;
import xfacthd.framedblocks.api.util.Utils;
import xfacthd.framedblocks.common.FBContent;
import xfacthd.framedblocks.common.block.cube.FramedCollapsibleBlock;
import xfacthd.framedblocks.common.block.cube.FramedCollapsibleCopycatBlock;
import xfacthd.framedblocks.common.block.slab.FramedAdjustableDoubleBlock;
import xfacthd.framedblocks.common.block.slab.FramedAdjustableDoubleSlabBlock;
import xfacthd.framedblocks.common.block.slab.FramedCheckeredCubeBlock;
import xfacthd.framedblocks.common.block.slab.FramedCheckeredPanelBlock;
import xfacthd.framedblocks.common.block.slab.FramedCheckeredSlabBlock;
import xfacthd.framedblocks.common.block.slab.FramedDoubleSlabBlock;
import xfacthd.framedblocks.common.blockentity.doubled.slab.FramedAdjustableDoubleBlockEntity;
import xfacthd.framedblocks.common.blockentity.special.FramedChiseledBookshelfBlockEntity;
import xfacthd.framedblocks.common.blockentity.special.FramedCollapsibleBlockEntity;
import xfacthd.framedblocks.common.blockentity.special.FramedCollapsibleCopycatBlockEntity;
import xfacthd.framedblocks.common.blockentity.special.FramedOwnableBlockEntity;
import xfacthd.framedblocks.common.blockentity.special.FramedStorageBlockEntity;
import xfacthd.framedblocks.common.data.BlockType;
import xfacthd.framedblocks.common.data.PropertyHolder;
import xfacthd.framedblocks.common.data.property.NullableDirection;

import java.util.List;

public final class FramedBlocksIntegration {

    public static final ResourceLocation CAMO_ID = ResourceLocation.fromNamespaceAndPath("framedblocks", "camo");

    public static final String CAMO = FramedBlockEntity.CAMO_NBT_KEY;
    public static final String CAMO_TWO = IFramedDoubleBlockEntity.CAMO_TWO_NBT_KEY;
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

    public static CompoundTag payloadOf(FramedBlockEntity framed) {
        var payload = new CompoundTag();
        putCamo(payload, CAMO, framed.getCamo());
        if (framed instanceof IFramedDoubleBlockEntity doubleBlock) {
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
            var saved = framed.saveCustomOnly(framed.getLevel().registryAccess());
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
                    tag.putInt(FIRST_HEIGHT, 16 - tag.getInt(FIRST_HEIGHT));
                }
            }
        } else if (block instanceof FramedCollapsibleBlock) {
            var face = state.getValue(PropertyHolder.NULLABLE_FACE).toDirection();
            if (tag.contains(OFFSETS) && face != null) {
                tag.putInt(OFFSETS, FramedShapeTransforms.permuteVertexOffsets(tag.getInt(OFFSETS), face, transform));
            }
        } else if (block instanceof FramedCollapsibleCopycatBlock) {
            if (tag.contains(OFFSETS)) {
                tag.putInt(OFFSETS, FramedShapeTransforms.permuteFaceOffsets(
                        tag.getInt(OFFSETS), state.getValue(PropertyHolder.COPYCAT_ROTATION), transform));
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

    private static boolean hasShape(FramedBlockEntity framed) {
        return framed instanceof FramedCollapsibleBlockEntity || framed instanceof FramedCollapsibleCopycatBlockEntity
                || framed instanceof FramedAdjustableDoubleBlockEntity;
    }

    private static void putCamo(CompoundTag payload, String key, CamoContainer<?, ?> camo) {
        if (!camo.isEmpty()) {
            payload.put(key, CamoContainerHelper.writeToDisk(camo));
        }
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
            if (payload.contains(key) && !CamoContainerHelper.readFromDisk(payload.getCompound(key)).canTriviallyConvertToItemStack()) {
                return true;
            }
        }
        return false;
    }

    private static final class CamoIntegration implements BlockIntegration {

        @Override
        public CompoundTag capture(IntegrationContext.Capture context) {
            return context.blockEntity() instanceof FramedBlockEntity framed ? payloadOf(framed) : new CompoundTag();
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
                if (base.contains(key) && !validCamo(CamoContainerHelper.readFromDisk(base.getCompound(key)))) {
                    base.remove(key);
                }
            }
            var plan = PlacementPreparationPlan.builder(base);
            if (config.shouldConsumeCamoItem()) {
                for (var key : CAMO_KEYS) {
                    if (!base.contains(key)) {
                        continue;
                    }
                    var camo = CamoContainerHelper.readFromDisk(base.getCompound(key));
                    if (camo.canTriviallyConvertToItemStack()) {
                        component(plan, key, CamoContainerHelper.dropCamo(camo));
                    }
                }
            }
            if (base.getBoolean(GLOWING)) {
                component(plan, GLOWING, ItemRequirement.tag(Tags.Items.DUSTS_GLOWSTONE, 1));
            }
            if (base.getBoolean(INTANGIBLE)) {
                component(plan, INTANGIBLE, new ItemStack(Utils.PHANTOM_PASTE));
            }
            if (base.getBoolean(REINFORCED)) {
                component(plan, REINFORCED, new ItemStack(Utils.FRAMED_REINFORCEMENT));
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
            if (!(context.blockEntity() instanceof FramedBlockEntity framed)) {
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
                framed.setCamo(CamoContainerHelper.readFromDisk(payload.getCompound(CAMO)), false);
            }
            if (payload.contains(CAMO_TWO) && framed instanceof IFramedDoubleBlockEntity) {
                framed.setCamo(CamoContainerHelper.readFromDisk(payload.getCompound(CAMO_TWO)), true);
            }
            framed.setGlowing(payload.getBoolean(GLOWING));
            framed.setIntangible(payload.getBoolean(INTANGIBLE));
            framed.setReinforced(payload.getBoolean(REINFORCED));
            if (framed instanceof FramedOwnableBlockEntity ownable) {
                ownable.setOwner(context.player().getUUID(), true);
            }
            framed.checkCamoSolid();
            framed.setChanged();
            var state = framed.getBlockState();
            context.level().sendBlockUpdated(context.pos(), state, state, Block.UPDATE_ALL);
            return ApplyResult.APPLIED;
        }

        private static void applyShape(IntegrationContext.Apply context, FramedBlockEntity framed, CompoundTag payload) {
            if (!hasShape(framed)) {
                return;
            }
            var registries = context.level().registryAccess();
            var tag = framed.saveCustomOnly(registries);
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
            framed.loadCustomOnly(tag, registries);
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
                    storage.getDrops().forEach(stack -> context.dropSink().accept(stack.copy()));
                }
                storage.clearContent();
            } else if (blockEntity instanceof FramedChiseledBookshelfBlockEntity bookshelf) {
                if (context.collectDrops()) {
                    bookshelf.getDrops().forEach(stack -> context.dropSink().accept(stack.copy()));
                }
                bookshelf.clearContents();
            }
        }
    }
}
