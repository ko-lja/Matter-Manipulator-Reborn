package lu.kolja.matter_manipulator.common.integration.projectred;

import codechicken.multipart.api.part.MultiPart;
import codechicken.multipart.block.TileMultipart;
import codechicken.multipart.init.MultiPartRegistries;
import codechicken.multipart.minecraft.McStatePart;
import codechicken.multipart.network.MultiPartSPH;
import codechicken.multipart.util.MultipartHelper;
import codechicken.multipart.util.MultipartLoadHandler;
import lu.kolja.matter_manipulator.api.BlockIntegration;
import lu.kolja.matter_manipulator.api.IntegrationContext;
import lu.kolja.matter_manipulator.api.ItemRequirement;
import lu.kolja.matter_manipulator.api.PlacementPreparationPlan;
import lu.kolja.matter_manipulator.api.PlacementRequirements;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class MultipartIntegration implements BlockIntegration {

    public static final Identifier ID = Identifier.fromNamespaceAndPath("cb_multipart", "parts");
    static final String FIRST_PART_UNPAID = "matter_manipulator.block_cost.cb_multipart.first_part";
    static final String FIRST_PART_UNREADABLE = "matter_manipulator.skip.cb_multipart.first_part_unreadable";
    public static final String FIRST_PART_OBSTRUCTED = "matter_manipulator.skip.cb_multipart.first_part_obstructed";
    private static final String[] RUN_TIME_KEYS = {"pelapsed", "sim_time", "schedTime"};
    private static final String PNEUMATIC_TUBE = "projectred_expansion:pneumatic_tube";

    @Override
    public CompoundTag capture(IntegrationContext.Capture context) {
        var parts = new ListTag();
        var drops = new ListTag();
        var registries = context.level().registryAccess();
        if (context.blockEntity() instanceof TileMultipart tile) {
            for (var part : tile.getPartList()) {
                parts.add(withoutTransit(MultiPartRegistries.savePart(new CompoundTag(), part, registries)));
                drops.add(saveDrops(part, registries));
            }
        } else if (context.blockEntity() instanceof MultipartLoadHandler.TileNBTContainer placeholder && placeholder.tag != null) {
            for (var saved : placeholder.tag.getList("parts", Tag.TAG_COMPOUND)) {
                parts.add(withoutTransit(((CompoundTag) saved).copy()));
                var part = MultiPartRegistries.loadPart(((CompoundTag) saved).copy(), registries);
                drops.add(part == null ? new ListTag() : saveDrops(part, registries));
            }
        }
        var payload = new CompoundTag();
        if (!parts.isEmpty()) {
            payload.put("parts", parts);
            payload.put("drops", drops);
        }
        return payload;
    }

    @Override
    public PlacementRequirements placementRequirements(IntegrationContext.Placement context, CompoundTag payload) {
        var registries = registries();
        var refusal = refusal(payload, registries);
        if (refusal != null) {
            return PlacementRequirements.unsupported(refusal);
        }
        var drops = payload.getList("drops", Tag.TAG_LIST);
        var first = drops.isEmpty() ? List.<ItemStack>of() : stacks(drops.getList(0), registries);
        if (first.isEmpty()) {
            return PlacementRequirements.unsupported(Component.translatable(FIRST_PART_UNPAID));
        }
        return new PlacementRequirements(false, first.stream().map(drop -> requirement(drop, registries)).toList(), null);
    }

    @Override
    public PlacementPreparationPlan preparation(IntegrationContext.Preparation context, CompoundTag payload) {
        var drops = payload.getList("drops", Tag.TAG_LIST);
        if (drops.size() < 2) {
            return PlacementPreparationPlan.unchanged(payload);
        }
        var registries = registries();
        var initial = payload.copy();
        var paid = new ListTag();
        for (int i = 0; i < drops.size(); i++) {
            var slots = new ListTag();
            for (int j = 0; j < (i == 0 ? 0 : drops.getList(i).size()); j++) {
                slots.add(new CompoundTag());
            }
            paid.add(slots);
        }
        initial.put("paid", paid);
        var plan = PlacementPreparationPlan.builder(initial);
        for (int i = 1; i < drops.size(); i++) {
            var partDrops = stacks(drops.getList(i), registries);
            if (partDrops.isEmpty()) {
                continue;
            }
            int part = i;
            var parent = plan.component(requirement(partDrops.get(0), registries), (prepared, acquired) ->
                    recordPaid(prepared, part, 0, acquired, registries));
            for (int j = 1; j < partDrops.size(); j++) {
                int slot = j;
                parent.component(requirement(partDrops.get(j), registries), (prepared, acquired) ->
                        recordPaid(prepared, part, slot, acquired, registries));
            }
        }
        return plan.build();
    }

    @Override
    public ApplyResult apply(IntegrationContext.Apply context, CompoundTag payload) {
        var result = switch (context.purpose()) {
            case MOVE_TRANSFER -> context.blockEntity() instanceof TileMultipart tile
                    && sameParts(tile, payload.getList("parts", Tag.TAG_COMPOUND))
                    ? ApplyResult.APPLIED : ApplyResult.REJECTED_UNCHANGED;
            case STANDARD, COPY_TEMPLATE -> rebuild(context, payload);
            case RECONFIGURE -> ApplyResult.REJECTED_UNCHANGED;
        };
        if (result != ApplyResult.REJECTED_UNCHANGED) {
            for (int i = 0; i < payload.getInt("unmirrored"); i++) {
                context.warn(PartTransforms.UNMIRRORED);
            }
        }
        return result;
    }

    @Override
    public CompoundTag transform(IntegrationContext.Transform context, CompoundTag payload) {
        if (PartTransforms.isIdentity(context.transform())) {
            return payload;
        }
        var parts = payload.getList("parts", Tag.TAG_COMPOUND);
        int unmirrored = 0;
        for (int i = 0; i < parts.size(); i++) {
            var outcome = PartTransforms.transform(parts.getCompound(i), context.transform());
            if (outcome == PartTransforms.Outcome.UNMIRRORED) {
                unmirrored++;
            } else if (outcome == PartTransforms.Outcome.CANNOT_FACE && !payload.contains("refused")) {
                var refused = new CompoundTag();
                refused.putString("reason", PartTransforms.CANNOT_FACE);
                refused.putInt("part", i);
                payload.put("refused", refused);
            }
        }
        if (unmirrored > 0) {
            payload.putInt("unmirrored", unmirrored);
        }
        return payload;
    }

    @Override
    public @Nullable Component moveRefusal(IntegrationContext.Capture context) {
        if (context.transform() == null || PartTransforms.isIdentity(context.transform())) {
            return null;
        }
        return refusal(transform(new IntegrationContext.Transform(context.state(), context.transform()), capture(context)),
                context.level().registryAccess());
    }

    @Override
    public CompoundTag transformBlockEntity(IntegrationContext.Transform context, CompoundTag blockEntity) {
        for (var saved : blockEntity.getList("parts", Tag.TAG_COMPOUND)) {
            var part = (CompoundTag) saved;
            if (PartTransforms.transform(part, context.transform()) == PartTransforms.Outcome.CANNOT_FACE) {
                throw new IllegalStateException("The part " + part.getString("id") + " cannot face that way");
            }
        }
        return blockEntity;
    }

    @Override
    public boolean transformsBlockEntity(BlockState state) {
        return true;
    }

    @Override
    public boolean createsTransferredBlockEntity(BlockState state) {
        return true;
    }

    @Override
    public @Nullable BlockEntity createTransferredBlockEntity(IntegrationContext.Transfer context, CompoundTag blockEntity) {
        return TileMultipart.fromNBT(blockEntity, context.pos(), context.level().registryAccess());
    }

    @Override
    public void afterTransfer(IntegrationContext.Transferred context) {
        if (!(context.blockEntity() instanceof TileMultipart tile)) {
            return;
        }
        tile.onChunkLoad(context.level().getChunkAt(context.pos()));
        tile.notifyPartChange(null);
        tile.notifyShapeChange();
        MultiPartSPH.sendDescUpdate(tile);
    }

    private static ApplyResult rebuild(IntegrationContext.Apply context, CompoundTag payload) {
        var level = context.level();
        var pos = context.pos();
        var registries = level.registryAccess();
        var parts = payload.getList("parts", Tag.TAG_COMPOUND);
        if (parts.isEmpty()) {
            return context.reject(Component.translatable(FIRST_PART_UNREADABLE));
        }
        var first = freshPart(parts.getCompound(0), registries);
        if (first == null) {
            return context.reject(Component.translatable(FIRST_PART_UNREADABLE));
        }
        if (!unobstructed(level, pos, first)) {
            return context.reject(Component.translatable(FIRST_PART_OBSTRUCTED));
        }
        TileMultipart.addPart(level, pos, first);

        var drops = payload.getList("drops", Tag.TAG_LIST);
        var paid = payload.getList("paid", Tag.TAG_LIST);
        boolean refunded = false;
        for (int i = 1; i < parts.size(); i++) {
            var paidStacks = stacks(paid.getList(i), registries);
            boolean funded = !paidStacks.isEmpty() && paidStacks.size() == drops.getList(i).size();
            var part = funded ? freshPart(parts.getCompound(i), registries) : null;
            var tile = MultipartHelper.getOrConvertTile(level, pos);
            if (part != null && tile != null && tile.canAddPart(part) && unobstructed(level, pos, part)) {
                TileMultipart.addPart(level, pos, part);
                continue;
            }
            for (var stack : paidStacks) {
                context.refund(stack);
                refunded = true;
            }
        }
        return refunded ? ApplyResult.PARTIAL_RESOURCES_SETTLED : ApplyResult.APPLIED;
    }

    private static boolean unobstructed(ServerLevel level, BlockPos pos, MultiPart part) {
        var shape = part instanceof McStatePart vanilla
                ? vanilla.state.getCollisionShape(level, pos)
                : part.getCollisionShape(CollisionContext.empty());
        return level.isUnobstructed(null, shape.move(pos.getX(), pos.getY(), pos.getZ()));
    }

    private static CompoundTag withoutTransit(CompoundTag part) {
        if (part.getString("id").equals(PNEUMATIC_TUBE)) {
            part.remove("payloads");
        }
        return part;
    }

    private static @Nullable MultiPart freshPart(CompoundTag saved, HolderLookup.Provider registries) {
        var tag = saved.copy();
        for (var key : RUN_TIME_KEYS) {
            if (tag.contains(key)) {
                tag.putLong(key, -1L);
            }
        }
        return MultiPartRegistries.loadPart(tag, registries);
    }

    private static @Nullable Component refusal(CompoundTag payload, HolderLookup.Provider registries) {
        if (!payload.contains("refused", Tag.TAG_COMPOUND)) {
            return null;
        }
        var refused = payload.getCompound("refused");
        int part = refused.getInt("part");
        var drops = payload.getList("drops", Tag.TAG_LIST);
        var partDrops = part < drops.size() ? stacks(drops.getList(part), registries) : List.<ItemStack>of();
        var name = partDrops.isEmpty()
                ? Component.literal(payload.getList("parts", Tag.TAG_COMPOUND).getCompound(part).getString("id"))
                : partDrops.get(0).getHoverName();
        return Component.translatable(refused.getString("reason"), name);
    }

    private static void recordPaid(CompoundTag prepared, int part, int slot, ItemStack acquired,
                                   HolderLookup.Provider registries) {
        prepared.getList("paid", Tag.TAG_LIST).getList(part)
                .set(slot, acquired.isEmpty() ? new CompoundTag() : acquired.save(registries));
    }

    private static boolean sameParts(TileMultipart tile, ListTag parts) {
        var live = tile.getPartList();
        if (live.size() != parts.size()) {
            return false;
        }
        for (int i = 0; i < parts.size(); i++) {
            var type = MultiPartRegistries.multipartTypes().getKey(live.get(i).getType());
            if (type == null || !type.toString().equals(parts.getCompound(i).getString("id"))) {
                return false;
            }
        }
        return true;
    }

    private static ListTag saveDrops(MultiPart part, HolderLookup.Provider registries) {
        var drops = new ListTag();
        for (var drop : part.getDrops()) {
            if (!drop.isEmpty()) {
                drops.add(drop.save(registries));
            }
        }
        return drops;
    }

    private static ItemRequirement requirement(ItemStack drop, HolderLookup.Provider registries) {
        var saved = drop.save(registries);
        return ItemRequirement.exact(drop)
                .withVariants(variant -> variant.copyWithCount(drop.getCount()).save(registries).equals(saved));
    }

    private static HolderLookup.Provider registries() {
        return ServerLifecycleHooks.getCurrentServer().registryAccess();
    }

    static List<ItemStack> stacks(ListTag saved, HolderLookup.Provider registries) {
        var stacks = new ArrayList<ItemStack>(saved.size());
        for (int i = 0; i < saved.size(); i++) {
            var stack = ItemStack.parseOptional(registries, saved.getCompound(i));
            if (!stack.isEmpty()) {
                stacks.add(stack);
            }
        }
        return stacks;
    }
}
