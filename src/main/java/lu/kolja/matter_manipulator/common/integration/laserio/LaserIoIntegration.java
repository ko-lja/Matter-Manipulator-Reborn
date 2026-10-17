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
import com.direwolf20.laserio.setup.LaserIORegistration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public final class LaserIoIntegration {

    public static final Identifier NODE_ID = Identifier.fromNamespaceAndPath("laserio", "node");
    public static final Identifier LINKS_ID = Identifier.fromNamespaceAndPath("laserio", "links");

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
    private static final String STACKS = "stacks";
    private static final String BLOCKPOS = "blockpos";
    private static final int OVERCLOCKER_SLOT = 9;

    private LaserIoIntegration() {}

    public static void register(IntegrationRegistrar registrar) {
        var node = LaserIORegistration.LaserNode.get();
        var connector = LaserIORegistration.LaserConnector.get();
        var advancedConnector = LaserIORegistration.LaserConnectorAdv.get();
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
            var sides = payload.getCompoundOrEmpty(SIDES);
            var turned = new CompoundTag();
            for (var key : sides.keySet()) {
                var direction = Direction.byName(key);
                var side = sides.getCompoundOrEmpty(key).copy();
                var cards = side.getCompoundOrEmpty(CARDS);
                for (var slot : cards.keySet()) {
                    remapSneaky(cards.getCompoundOrEmpty(slot).getCompoundOrEmpty(CARD), context.transform());
                }
                turned.put(direction == null ? key : context.transform().transform(direction).getName(), side);
            }
            transformed.put(SIDES, turned);
            return transformed;
        }

        @Override
        public boolean transformsBlockEntity(BlockState state) {
            return state.is(LaserIORegistration.LaserNode.get());
        }

        @Override
        public CompoundTag transformBlockEntity(IntegrationContext.Transform context, CompoundTag blockEntity) {
            var inventories = new CompoundTag[6];
            for (var direction : Direction.values()) {
                var key = INVENTORY + direction.ordinal();
                var inventory = blockEntity.getCompound(key);
                if (inventory.isPresent()) {
                    inventories[direction.ordinal()] = inventory.get();
                    blockEntity.remove(key);
                }
            }
            for (var direction : Direction.values()) {
                var inventory = inventories[direction.ordinal()];
                if (inventory == null) {
                    continue;
                }
                var stacks = inventory.getListOrEmpty(STACKS);
                for (int i = 0; i < stacks.size(); i++) {
                    remapSneaky(stacks.getCompoundOrEmpty(i), context.transform());
                }
                blockEntity.put(INVENTORY + context.transform().transform(direction).ordinal(), inventory);
            }
            return blockEntity;
        }

        @Override
        public PlacementPreparationPlan preparation(IntegrationContext.Preparation context, CompoundTag payload) {
            var sides = payload.getCompoundOrEmpty(SIDES);
            if (sides.isEmpty()) {
                return PlacementPreparationPlan.unchanged(payload);
            }
            var registries = ServerLifecycleHooks.getCurrentServer().registryAccess();
            var unfunded = payload.copy();
            unfunded.remove(SIDES);
            var plan = PlacementPreparationPlan.builder(unfunded);
            for (var side : sides.keySet()) {
                var sideTag = sides.getCompoundOrEmpty(side);
                var cards = sideTag.getCompoundOrEmpty(CARDS);
                for (var slot : cards.keySet()) {
                    var entry = cards.getCompoundOrEmpty(slot);
                    var card = stack(registries, entry, CARD);
                    if (card.isEmpty()) {
                        continue;
                    }
                    var parent = plan.component(ItemRequirement.withFallback(card, new ItemStack(card.getItem())),
                            (prepared, acquired) -> {
                                if (!acquired.isEmpty()) {
                                    var funded = new CompoundTag();
                                    funded.put(CARD, entry.getCompoundOrEmpty(CARD).copy());
                                    child(child(child(prepared, SIDES), side), CARDS).put(slot, funded);
                                }
                            });
                    var filter = stack(registries, entry, FILTER);
                    if (!filter.isEmpty()) {
                        parent.component(ItemRequirement.withFallback(filter, new ItemStack(filter.getItem()))
                                        .withVariants(LaserIoIntegration::isBlankFilter),
                                (prepared, acquired) -> {
                                    if (!acquired.isEmpty()) {
                                        cardEntry(prepared, side, slot).put(FILTER, entry.getCompoundOrEmpty(FILTER).copy());
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
                if (reconfigure) {
                    for (var install : installs) {
                        var stack = install.stack();
                        node.nodeSideCaches[install.side().ordinal()].itemHandler
                                .set(install.slot(), ItemResource.of(stack), stack.getCount());
                    }
                } else {
                    try (var transaction = Transaction.openRoot()) {
                        for (var install : installs) {
                            var stack = install.stack();
                            var handler = node.nodeSideCaches[install.side().ordinal()].itemHandler;
                            if (handler.insert(install.slot(), ItemResource.of(stack), stack.getCount(), transaction)
                                    < stack.getCount()) {
                                return ApplyResult.REJECTED_UNCHANGED;
                            }
                        }
                        transaction.commit();
                    }
                }
            }
            if (payload.contains(COLOR)) {
                laser.setColor(new Color(payload.getIntOr(COLOR, 0), true), payload.getIntOr(WRENCH_ALPHA, 0));
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
                blockEntity.getIntArray(key).ifPresent(packed -> blockEntity.putIntArray(key,
                        pack(unpack(packed).stream().map(context.transform()::transformOffset).toList())));
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
                blockEntity.getIntArray(key).ifPresent(packed -> blockEntity.putIntArray(key, pack(unpack(packed).stream()
                        .filter(offset -> context.destinationOf(context.source().offset(offset)) != null)
                        .toList())));
            }
            var partner = blockEntity.getCompound(PARTNER).orElse(null);
            if (partner != null
                    && partner.getStringOr("dimension", "").equals(context.dimension().identifier().toString())) {
                var destination = partner.read(BLOCKPOS, BlockPos.CODEC).map(context::destinationOf).orElse(null);
                if (destination == null) {
                    blockEntity.remove(PARTNER);
                } else {
                    partner.store(BLOCKPOS, BlockPos.CODEC, destination);
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
                side.store(OVERCLOCKERS, ItemStack.CODEC, ops(registries), overclockers);
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
        for (int i = 0; i < inventory.size(); i++) {
            var held = inventory.getResource(i).toStack(inventory.getAmountAsInt(i));
            if (held.getItem() instanceof BaseFilter) {
                entry.store(FILTER, ItemStack.CODEC, ops(registries), held);
            } else if (held.getItem() instanceof OverclockerCard) {
                entry.store(OVERCLOCKERS, ItemStack.CODEC, ops(registries), held);
            }
        }
        var card = stack.copy();
        card.remove(LaserIODataComponents.ITEMSTACK_HANDLER);
        entry.store(CARD, ItemStack.CODEC, ops(registries), card);
        return entry;
    }

    private record Install(Direction side, int slot, ItemStack stack) {}

    private static List<Install> installs(HolderLookup.Provider registries, CompoundTag payload) {
        var installs = new ArrayList<Install>();
        var sides = payload.getCompoundOrEmpty(SIDES);
        for (var key : sides.keySet()) {
            var direction = Direction.byName(key);
            if (direction == null) {
                continue;
            }
            var side = sides.getCompoundOrEmpty(key);
            var cards = side.getCompoundOrEmpty(CARDS);
            for (var slot : cards.keySet()) {
                var entry = cards.getCompoundOrEmpty(slot);
                var card = stack(registries, entry, CARD);
                if (card.isEmpty()) {
                    continue;
                }
                var overclockers = stack(registries, entry, OVERCLOCKERS);
                var inventory = BaseCard.getInventory(card);
                if (entry.contains(FILTER)) {
                    var filter = stack(registries, entry, FILTER);
                    inventory.set(0, ItemResource.of(filter), filter.getCount());
                }
                if (!overclockers.isEmpty()) {
                    inventory.set(1, ItemResource.of(overclockers), overclockers.getCount());
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
        var sides = payload.getCompoundOrEmpty(SIDES);
        for (var side : sides.keySet()) {
            var sideTag = sides.getCompoundOrEmpty(side);
            var sideItems = new CompoundTag();
            sideItems.putInt(OVERCLOCKERS, count(sideTag, OVERCLOCKERS));
            var cards = sideTag.getCompoundOrEmpty(CARDS);
            for (var slot : cards.keySet()) {
                var entry = cards.getCompoundOrEmpty(slot);
                var card = new CompoundTag();
                card.putString(CARD, entry.getCompoundOrEmpty(CARD).getStringOr("id", ""));
                card.putString(FILTER, entry.getCompoundOrEmpty(FILTER).getStringOr("id", ""));
                card.putInt(OVERCLOCKERS, count(entry, OVERCLOCKERS));
                sideItems.put(slot, card);
            }
            items.put(side, sideItems);
        }
        return items;
    }

    private static void remapSneaky(CompoundTag item, DirectionTransform transform) {
        var components = item.getCompoundOrEmpty(COMPONENTS);
        if (!(components.get(SNEAKY) instanceof ByteTag(byte sneaky))) {
            return;
        }
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

    private static List<BlockPos> unpack(int[] packed) {
        var positions = new ArrayList<BlockPos>();
        for (int i = 0; i + 2 < packed.length; i += 3) {
            positions.add(new BlockPos(packed[i], packed[i + 1], packed[i + 2]));
        }
        return positions;
    }

    private static int[] pack(List<BlockPos> positions) {
        var packed = new int[positions.size() * 3];
        for (int i = 0; i < positions.size(); i++) {
            var pos = positions.get(i);
            packed[i * 3] = pos.getX();
            packed[i * 3 + 1] = pos.getY();
            packed[i * 3 + 2] = pos.getZ();
        }
        return packed;
    }

    private static List<BlockPos> offsets(CompoundTag payload, String key) {
        var offsets = new ArrayList<BlockPos>();
        for (long packed : payload.getLongArray(key).orElse(new long[0])) {
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
                blank = value instanceof ItemContainerContents contents
                        && contents.nonEmptyItemCopyStream().findAny().isEmpty();
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
        holder.store(OVERCLOCKERS, ItemStack.CODEC, ops(registries),
                held.isEmpty() ? acquired : held.copyWithCount(held.getCount() + 1));
    }

    private static ItemStack stack(HolderLookup.Provider registries, CompoundTag holder, String key) {
        return holder.read(key, ItemStack.OPTIONAL_CODEC, ops(registries)).orElse(ItemStack.EMPTY);
    }

    private static RegistryOps<Tag> ops(HolderLookup.Provider registries) {
        return registries.createSerializationContext(NbtOps.INSTANCE);
    }

    private static int count(CompoundTag holder, String key) {
        return holder.getCompoundOrEmpty(key).getIntOr("count", 0);
    }

    private static CompoundTag child(CompoundTag parent, String key) {
        if (parent.getCompound(key).isEmpty()) {
            parent.put(key, new CompoundTag());
        }
        return parent.getCompoundOrEmpty(key);
    }
}
