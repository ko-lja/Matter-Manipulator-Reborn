package lu.kolja.matter_manipulator.common.integration.laserio;

import lu.kolja.matter_manipulator.api.BlockIntegration;
import lu.kolja.matter_manipulator.api.DirectionTransform;
import lu.kolja.matter_manipulator.api.IntegrationContext;
import lu.kolja.matter_manipulator.api.IntegrationRegistrar;
import lu.kolja.matter_manipulator.api.ItemRequirement;
import lu.kolja.matter_manipulator.api.PlacementPreparationPlan;
import lu.kolja.matter_manipulator.api.PlacementPurpose;

import com.direwolf20.laserio.common.blockentities.LaserNodeBE;
import com.direwolf20.laserio.common.blockentities.basebe.BaseLaserBE;
import com.direwolf20.laserio.common.containers.LaserNodeContainer;
import com.direwolf20.laserio.common.items.cards.BaseCard;
import com.direwolf20.laserio.common.items.cards.CardEnergy;
import com.direwolf20.laserio.common.items.cards.CardFluid;
import com.direwolf20.laserio.common.items.cards.CardItem;
import com.direwolf20.laserio.common.items.filters.BaseFilter;
import com.direwolf20.laserio.common.items.upgrades.OverclockerCard;
import com.direwolf20.laserio.common.items.upgrades.OverclockerNode;
import com.direwolf20.laserio.setup.Config;
import com.direwolf20.laserio.setup.LaserIODataComponents;
import com.direwolf20.laserio.setup.Registration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public final class LaserIoIntegration {

    public static final ResourceLocation NODE_ID = ResourceLocation.fromNamespaceAndPath("laserio", "node");
    public static final ResourceLocation LINKS_ID = ResourceLocation.fromNamespaceAndPath("laserio", "links");

    static final String COLOR = "laserColor";
    static final String WRENCH_ALPHA = "wrenchAlpha";
    static final String SIDES = "sides";
    static final String CARDS = "cards";
    static final String CARD = "card";
    static final String FILTER = "filter";
    static final String OVERCLOCKERS = "overclockers";
    static final String LINKS = "links";
    static final String RENDERED = "rendered";

    private static final String CONNECTIONS = "connections";
    private static final String RENDERED_CONNECTIONS = "renderedConnections";
    private static final String MY_WORLD_POS = "myWorldPos";
    private static final String PARTNER = "partnerDimPos";
    private static final String COMPONENTS = "components";
    private static final String SNEAKY = "laserio:card_sneaky";
    private static final String INVENTORY = "Inventory";
    private static final int OVERCLOCKER_SLOT = 9;

    private LaserIoIntegration() {}

    public static void register(IntegrationRegistrar registrar) {
        var node = Registration.LaserNode.get();
        var connector = Registration.LaserConnector.get();
        var advancedConnector = Registration.LaserConnectorAdv.get();
        registrar.block(NODE_ID, new NodeIntegration(), node, connector, advancedConnector);
        registrar.block(LINKS_ID, new LinksIntegration(), node, connector, advancedConnector);
    }

    private static final class NodeIntegration implements BlockIntegration {

        @Override
        public CompoundTag capture(IntegrationContext.Capture context) {
            return context.blockEntity() instanceof BaseLaserBE laser
                    ? captureNode(laser, context.level().registryAccess()) : new CompoundTag();
        }

        @Override
        public CompoundTag transform(IntegrationContext.Transform context, CompoundTag payload) {
            var transformed = payload.copy();
            if (!payload.contains(SIDES)) {
                return transformed;
            }
            var sides = payload.getCompound(SIDES);
            var turned = new CompoundTag();
            for (var key : sides.getAllKeys()) {
                var direction = Direction.byName(key);
                var side = sides.getCompound(key).copy();
                var cards = side.getCompound(CARDS);
                for (var slot : cards.getAllKeys()) {
                    remapSneaky(cards.getCompound(slot).getCompound(CARD), context.transform());
                }
                turned.put(direction == null ? key : context.transform().transform(direction).getName(), side);
            }
            transformed.put(SIDES, turned);
            return transformed;
        }

        @Override
        public boolean transformsBlockEntity(BlockState state) {
            return state.is(Registration.LaserNode.get());
        }

        @Override
        public CompoundTag transformBlockEntity(IntegrationContext.Transform context, CompoundTag blockEntity) {
            var inventories = new CompoundTag[6];
            for (var direction : Direction.values()) {
                var key = INVENTORY + direction.ordinal();
                if (blockEntity.contains(key, Tag.TAG_COMPOUND)) {
                    inventories[direction.ordinal()] = blockEntity.getCompound(key);
                    blockEntity.remove(key);
                }
            }
            for (var direction : Direction.values()) {
                var inventory = inventories[direction.ordinal()];
                if (inventory == null) {
                    continue;
                }
                var items = inventory.getList("Items", Tag.TAG_COMPOUND);
                for (int i = 0; i < items.size(); i++) {
                    remapSneaky(items.getCompound(i), context.transform());
                }
                blockEntity.put(INVENTORY + context.transform().transform(direction).ordinal(), inventory);
            }
            return blockEntity;
        }

        @Override
        public PlacementPreparationPlan preparation(IntegrationContext.Preparation context, CompoundTag payload) {
            var sides = payload.getCompound(SIDES);
            if (sides.isEmpty()) {
                return PlacementPreparationPlan.unchanged(payload);
            }
            var registries = ServerLifecycleHooks.getCurrentServer().registryAccess();
            var unfunded = payload.copy();
            unfunded.remove(SIDES);
            var plan = PlacementPreparationPlan.builder(unfunded);
            for (var side : sides.getAllKeys()) {
                var sideTag = sides.getCompound(side);
                var cards = sideTag.getCompound(CARDS);
                for (var slot : cards.getAllKeys()) {
                    var entry = cards.getCompound(slot);
                    var card = stack(registries, entry, CARD);
                    if (card.isEmpty()) {
                        continue;
                    }
                    var parent = plan.component(ItemRequirement.withFallback(card, new ItemStack(card.getItem())),
                            (prepared, acquired) -> {
                                if (!acquired.isEmpty()) {
                                    var funded = new CompoundTag();
                                    funded.put(CARD, entry.getCompound(CARD).copy());
                                    child(child(child(prepared, SIDES), side), CARDS).put(slot, funded);
                                }
                            });
                    var filter = stack(registries, entry, FILTER);
                    if (!filter.isEmpty()) {
                        parent.component(ItemRequirement.withFallback(filter, new ItemStack(filter.getItem()))
                                        .withVariants(LaserIoIntegration::isBlankFilter),
                                (prepared, acquired) -> {
                                    if (!acquired.isEmpty()) {
                                        cardEntry(prepared, side, slot).put(FILTER, entry.getCompound(FILTER).copy());
                                    }
                                });
                    }
                    var overclockers = stack(registries, entry, OVERCLOCKERS);
                    for (int i = 0; i < overclockers.getCount(); i++) {
                        parent.component(ItemRequirement.exact(overclockers.copyWithCount(1)),
                                (prepared, acquired) -> addOne(registries, cardEntry(prepared, side, slot), acquired));
                    }
                }
                var overclockers = stack(registries, sideTag, OVERCLOCKERS);
                for (int i = 0; i < overclockers.getCount(); i++) {
                    plan.component(ItemRequirement.exact(overclockers.copyWithCount(1)),
                            (prepared, acquired) -> addOne(registries, child(child(prepared, SIDES), side), acquired));
                }
            }
            return plan.build();
        }

        @Override
        public ApplyResult apply(IntegrationContext.Apply context, CompoundTag payload) {
            if (!(context.blockEntity() instanceof BaseLaserBE laser)) {
                return ApplyResult.REJECTED_UNCHANGED;
            }
            var registries = context.level().registryAccess();
            if (context.purpose() == PlacementPurpose.MOVE_TRANSFER) {
                return captureNode(laser, registries).equals(payload)
                        ? ApplyResult.APPLIED : ApplyResult.REJECTED_UNCHANGED;
            }
            if (laser instanceof LaserNodeBE node) {
                boolean reconfigure = context.purpose() == PlacementPurpose.RECONFIGURE;
                if (reconfigure && !sameItems(captureNode(node, registries), payload)) {
                    return ApplyResult.REJECTED_UNCHANGED;
                }
                var installs = installs(registries, payload);
                if (!reconfigure) {
                    for (var install : installs) {
                        var handler = node.nodeSideCaches[install.side().ordinal()].itemHandler;
                        if (!handler.insertItem(install.slot(), install.stack(), true).isEmpty()) {
                            return ApplyResult.REJECTED_UNCHANGED;
                        }
                    }
                }
                for (var install : installs) {
                    var handler = node.nodeSideCaches[install.side().ordinal()].itemHandler;
                    if (reconfigure) {
                        handler.setStackInSlot(install.slot(), install.stack());
                    } else {
                        handler.insertItem(install.slot(), install.stack(), false);
                    }
                }
            }
            if (payload.contains(COLOR)) {
                laser.setColor(new Color(payload.getInt(COLOR), true), payload.getInt(WRENCH_ALPHA));
            }
            return ApplyResult.APPLIED;
        }

        @Override
        public boolean canReconfigure(IntegrationContext.Placement context, CompoundTag existing, CompoundTag desired) {
            return sameItems(existing, desired);
        }
    }

    private static final class LinksIntegration implements BlockIntegration {

        @Override
        public CompoundTag capture(IntegrationContext.Capture context) {
            if (!(context.blockEntity() instanceof BaseLaserBE laser)) {
                return new CompoundTag();
            }
            Predicate<BlockPos> captured = offset -> context.selection() == null
                    || context.inSelection(context.pos().offset(offset));
            return links(laser.getConnections().stream().filter(captured).toList(),
                    laser.getRenderedConnections().stream()
                            .filter(offset -> laser.getConnections().contains(offset) && captured.test(offset))
                            .toList());
        }

        @Override
        public CompoundTag transform(IntegrationContext.Transform context, CompoundTag payload) {
            if (payload.isEmpty()) {
                return payload.copy();
            }
            var transform = context.transform();
            return links(offsets(payload, LINKS).stream().map(transform::transformOffset).toList(),
                    offsets(payload, RENDERED).stream().map(transform::transformOffset).toList());
        }

        @Override
        public CompoundTag transformBlockEntity(IntegrationContext.Transform context, CompoundTag blockEntity) {
            for (var key : List.of(CONNECTIONS, RENDERED_CONNECTIONS)) {
                var list = blockEntity.getList(key, Tag.TAG_COMPOUND);
                for (int i = 0; i < list.size(); i++) {
                    var entry = list.getCompound(i);
                    var offset = NbtUtils.readBlockPos(entry, "pos").orElse(BlockPos.ZERO);
                    entry.put("pos", NbtUtils.writeBlockPos(context.transform().transformOffset(offset)));
                }
            }
            return blockEntity;
        }

        @Override
        public boolean relocatesBlockEntity(BlockState state) {
            return true;
        }

        @Override
        public CompoundTag relocateBlockEntity(IntegrationContext.Relocation context, CompoundTag blockEntity) {
            blockEntity.remove(MY_WORLD_POS);
            for (var key : List.of(CONNECTIONS, RENDERED_CONNECTIONS)) {
                var list = blockEntity.getList(key, Tag.TAG_COMPOUND);
                list.removeIf(entry -> context.destinationOf(context.source().offset(
                        NbtUtils.readBlockPos((CompoundTag) entry, "pos").orElse(BlockPos.ZERO))) == null);
            }
            var partner = blockEntity.getCompound(PARTNER);
            if (blockEntity.contains(PARTNER, Tag.TAG_COMPOUND)
                    && partner.getString("dimension").equals(context.dimension().location().toString())) {
                var destination = NbtUtils.readBlockPos(partner, "blockpos").map(context::destinationOf).orElse(null);
                if (destination == null) {
                    blockEntity.remove(PARTNER);
                } else {
                    partner.put("blockpos", NbtUtils.writeBlockPos(destination));
                }
            }
            return blockEntity;
        }

        @Override
        public ApplyResult apply(IntegrationContext.Apply context, CompoundTag payload) {
            if (!(context.blockEntity() instanceof BaseLaserBE laser)) {
                return ApplyResult.REJECTED_UNCHANGED;
            }
            if (context.purpose() == PlacementPurpose.MOVE_TRANSFER) {
                var restored = capture(new IntegrationContext.Capture(context.level(), context.pos(), context.state()));
                return restored.equals(payload) ? ApplyResult.APPLIED : ApplyResult.REJECTED_UNCHANGED;
            }
            var rendered = new HashSet<>(offsets(payload, RENDERED));
            for (var offset : offsets(payload, LINKS)) {
                var peerPos = context.pos().offset(offset);
                if (!context.level().isLoaded(peerPos) || laser.isNodeConnected(peerPos)
                        || !(context.level().getBlockEntity(peerPos) instanceof BaseLaserBE peer)) {
                    continue;
                }
                if (rendered.contains(offset)) {
                    laser.addConnection(peerPos, peer);
                } else {
                    peer.addConnection(context.pos(), laser);
                }
            }
            return ApplyResult.APPLIED;
        }

        @Override
        public boolean canReconfigure(IntegrationContext.Placement context, CompoundTag existing, CompoundTag desired) {
            return true;
        }

        @Override
        public Set<BlockPos> additionalChangedPositions(IntegrationContext.SideEffects context, CompoundTag payload) {
            var live = context.blockEntity() instanceof BaseLaserBE laser ? laser : null;
            var changed = new HashSet<BlockPos>();
            for (var offset : offsets(payload, LINKS)) {
                var peerPos = context.pos().offset(offset);
                if (!context.level().isLoaded(peerPos)) {
                    changed.add(peerPos);
                } else if (context.level().getBlockEntity(peerPos) instanceof BaseLaserBE
                        && (live == null || !live.isNodeConnected(peerPos))) {
                    changed.add(peerPos);
                }
            }
            return changed;
        }
    }

    private static CompoundTag captureNode(BaseLaserBE laser, HolderLookup.Provider registries) {
        var tag = new CompoundTag();
        tag.putInt(COLOR, laser.getColor().getRGB());
        tag.putInt(WRENCH_ALPHA, laser.getWrenchAlpha());
        if (!(laser instanceof LaserNodeBE node)) {
            return tag;
        }
        var sides = new CompoundTag();
        for (var direction : Direction.values()) {
            var handler = node.nodeSideCaches[direction.ordinal()].itemHandler;
            var side = new CompoundTag();
            var cards = new CompoundTag();
            for (int slot = 0; slot < LaserNodeContainer.CARDSLOTS; slot++) {
                var stack = handler.getStackInSlot(slot);
                if (stack.getItem() instanceof BaseCard) {
                    cards.put(Integer.toString(slot), captureCard(registries, stack));
                }
            }
            if (!cards.isEmpty()) {
                side.put(CARDS, cards);
            }
            var overclockers = handler.getStackInSlot(OVERCLOCKER_SLOT);
            if (overclockers.getItem() instanceof OverclockerNode) {
                side.put(OVERCLOCKERS, overclockers.save(registries));
            }
            if (!side.isEmpty()) {
                sides.put(direction.getName(), side);
            }
        }
        if (!sides.isEmpty()) {
            tag.put(SIDES, sides);
        }
        return tag;
    }

    private static CompoundTag captureCard(HolderLookup.Provider registries, ItemStack stack) {
        var entry = new CompoundTag();
        var inventory = BaseCard.getInventory(stack);
        for (int i = 0; i < inventory.getSlots(); i++) {
            var held = inventory.getStackInSlot(i);
            if (held.getItem() instanceof BaseFilter) {
                entry.put(FILTER, held.save(registries));
            } else if (held.getItem() instanceof OverclockerCard) {
                entry.put(OVERCLOCKERS, held.save(registries));
            }
        }
        var card = stack.copy();
        card.remove(LaserIODataComponents.ITEMSTACK_HANDLER);
        entry.put(CARD, card.save(registries));
        return entry;
    }

    private record Install(Direction side, int slot, ItemStack stack) {}

    private static List<Install> installs(HolderLookup.Provider registries, CompoundTag payload) {
        var installs = new ArrayList<Install>();
        var sides = payload.getCompound(SIDES);
        for (var key : sides.getAllKeys()) {
            var direction = Direction.byName(key);
            if (direction == null) {
                continue;
            }
            var side = sides.getCompound(key);
            var cards = side.getCompound(CARDS);
            for (var slot : cards.getAllKeys()) {
                var entry = cards.getCompound(slot);
                var card = stack(registries, entry, CARD);
                if (card.isEmpty()) {
                    continue;
                }
                var overclockers = stack(registries, entry, OVERCLOCKERS);
                var inventory = BaseCard.getInventory(card);
                if (entry.contains(FILTER)) {
                    inventory.setStackInSlot(0, stack(registries, entry, FILTER));
                }
                if (!overclockers.isEmpty()) {
                    inventory.setStackInSlot(1, overclockers);
                }
                clamp(card, overclockers.getCount());
                installs.add(new Install(direction, Integer.parseInt(slot), card));
            }
            var overclockers = stack(registries, side, OVERCLOCKERS);
            if (!overclockers.isEmpty()) {
                installs.add(new Install(direction, OVERCLOCKER_SLOT, overclockers));
            }
        }
        return installs;
    }

    private static void clamp(ItemStack card, int overclockers) {
        int minimumTicks = Math.max(20 - overclockers * 5, 1);
        if (card.getItem() instanceof CardItem) {
            int maximum = Math.max(overclockers * 16, 8);
            if (CardItem.getItemExtractAmt(card) > maximum) {
                CardItem.setItemExtractAmt(card, (byte) maximum);
            }
            if (BaseCard.getExtractSpeed(card) < minimumTicks) {
                BaseCard.setExtractSpeed(card, minimumTicks);
            }
        } else if (card.getItem() instanceof CardFluid) {
            int maximum = Math.max(overclockers * Config.MULTIPLIER_MILLI_BUCKETS_FLUID.get(),
                    Config.BASE_MILLI_BUCKETS_FLUID.get());
            if (CardFluid.getFluidExtractAmt(card) > maximum) {
                CardFluid.setFluidExtractAmt(card, maximum);
            }
            if (BaseCard.getExtractSpeed(card) < minimumTicks) {
                BaseCard.setExtractSpeed(card, minimumTicks);
            }
        } else if (card.getItem() instanceof CardEnergy) {
            int maximum = Config.MAX_FE_TICK.get();
            if (CardEnergy.getEnergyExtractAmt(card) > maximum) {
                CardEnergy.setEnergyExtractAmt(card, maximum);
            }
        }
    }

    private static boolean sameItems(CompoundTag a, CompoundTag b) {
        return items(a).equals(items(b));
    }

    private static CompoundTag items(CompoundTag payload) {
        var items = new CompoundTag();
        var sides = payload.getCompound(SIDES);
        for (var side : sides.getAllKeys()) {
            var sideTag = sides.getCompound(side);
            var sideItems = new CompoundTag();
            sideItems.putInt(OVERCLOCKERS, count(sideTag, OVERCLOCKERS));
            var cards = sideTag.getCompound(CARDS);
            for (var slot : cards.getAllKeys()) {
                var entry = cards.getCompound(slot);
                var card = new CompoundTag();
                card.putString(CARD, entry.getCompound(CARD).getString("id"));
                card.putString(FILTER, entry.getCompound(FILTER).getString("id"));
                card.putInt(OVERCLOCKERS, count(entry, OVERCLOCKERS));
                sideItems.put(slot, card);
            }
            items.put(side, sideItems);
        }
        return items;
    }

    private static void remapSneaky(CompoundTag item, DirectionTransform transform) {
        var components = item.getCompound(COMPONENTS);
        if (!components.contains(SNEAKY, Tag.TAG_BYTE)) {
            return;
        }
        byte sneaky = components.getByte(SNEAKY);
        if (sneaky >= 0 && sneaky < Direction.values().length) {
            components.putByte(SNEAKY, (byte) transform.transform(Direction.values()[sneaky]).ordinal());
        }
    }

    private static CompoundTag links(List<BlockPos> links, List<BlockPos> rendered) {
        var tag = new CompoundTag();
        if (links.isEmpty()) {
            return tag;
        }
        tag.putLongArray(LINKS, links.stream().mapToLong(BlockPos::asLong).sorted().toArray());
        if (!rendered.isEmpty()) {
            tag.putLongArray(RENDERED, rendered.stream().mapToLong(BlockPos::asLong).sorted().toArray());
        }
        return tag;
    }

    private static List<BlockPos> offsets(CompoundTag payload, String key) {
        var offsets = new ArrayList<BlockPos>();
        for (long packed : payload.getLongArray(key)) {
            offsets.add(BlockPos.of(packed));
        }
        return offsets;
    }

    static boolean isBlankFilter(ItemStack filter) {
        for (var component : filter.getComponentsPatch().entrySet()) {
            var type = component.getKey();
            var value = component.getValue().orElse(null);
            boolean blank;
            if (type == LaserIODataComponents.FILTER_ALLOW.get()) {
                blank = Boolean.TRUE.equals(value);
            } else if (type == LaserIODataComponents.FILTER_COMPARE.get()) {
                blank = Boolean.FALSE.equals(value);
            } else if (type == LaserIODataComponents.ITEMSTACK_HANDLER.get()) {
                blank = value instanceof ItemContainerContents contents && contents.nonEmptyStream().findAny().isEmpty();
            } else {
                blank = value instanceof List<?> list && list.stream().allMatch(entry -> entry.equals(0));
            }
            if (!blank) {
                return false;
            }
        }
        return true;
    }

    private static CompoundTag cardEntry(CompoundTag prepared, String side, String slot) {
        return child(child(child(child(prepared, SIDES), side), CARDS), slot);
    }

    private static void addOne(HolderLookup.Provider registries, CompoundTag holder, ItemStack acquired) {
        if (acquired.isEmpty()) {
            return;
        }
        var held = stack(registries, holder, OVERCLOCKERS);
        holder.put(OVERCLOCKERS, (held.isEmpty() ? acquired : held.copyWithCount(held.getCount() + 1))
                .save(registries));
    }

    private static ItemStack stack(HolderLookup.Provider registries, CompoundTag holder, String key) {
        return holder.contains(key, Tag.TAG_COMPOUND)
                ? ItemStack.parseOptional(registries, holder.getCompound(key)) : ItemStack.EMPTY;
    }

    private static int count(CompoundTag holder, String key) {
        return holder.getCompound(key).getInt("count");
    }

    private static CompoundTag child(CompoundTag parent, String key) {
        if (!parent.contains(key, Tag.TAG_COMPOUND)) {
            parent.put(key, new CompoundTag());
        }
        return parent.getCompound(key);
    }
}
