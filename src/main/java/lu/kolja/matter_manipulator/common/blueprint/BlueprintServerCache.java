package lu.kolja.matter_manipulator.common.blueprint;

import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.common.config.MMConfig;
import lu.kolja.matter_manipulator.common.item.ManipulatorItem;
import lu.kolja.matter_manipulator.common.localization.MMMessage;
import lu.kolja.matter_manipulator.common.manipulator.ManipulatorMode;
import lu.kolja.matter_manipulator.common.network.BlueprintRequestPacket;
import lu.kolja.matter_manipulator.common.network.BlueprintUploadPacket;
import lu.kolja.matter_manipulator.common.network.NetworkUtil;
import lu.kolja.matter_manipulator.common.network.ToolStateSyncPacket;
import lu.kolja.matter_manipulator.common.state.ToolState;
import lu.kolja.matter_manipulator.common.util.ChatUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

public final class BlueprintServerCache {

    static final int REQUEST_INTERVAL_TICKS = 100;
    static final int UPLOAD_INTERVAL_TICKS = 20;
    static final int UPLOAD_TIMEOUT_TICKS = 200;
    static final int ITEM_USE_INTERVAL_TICKS = 20;

    private static final Map<UUID, Blueprint> LOADED = new HashMap<>();
    private static final Map<UUID, Upload> UPLOADS = new HashMap<>();
    private static final Map<UUID, Long> REQUESTED = new HashMap<>();
    private static final Map<UUID, Long> COMPLETED = new HashMap<>();
    private static final Map<UUID, Long> ITEM_USES = new HashMap<>();

    private static final Executor WORKER = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "Matter Manipulator server blueprints");
        thread.setDaemon(true);
        return thread;
    });
    private static final Queue<Runnable> RESULTS = new ConcurrentLinkedQueue<>();
    static BlueprintServerTasks tasks = new BlueprintServerTasks(WORKER, RESULTS::add);

    private BlueprintServerCache() {}

    public static boolean busy(UUID player) {
        return tasks.busy(player);
    }

    public static void submit(ServerPlayer player, String kind, BlueprintServerTasks.Work work,
                              BiConsumer<ServerPlayer, IOException> failed) {
        tasks.submit(player, kind, work, failed);
    }

    @Nullable
    public static Blueprint get(UUID player, String hash, BlockPos size) {
        var blueprint = LOADED.get(player);
        return blueprint != null && blueprint.header().matches(hash, size) ? blueprint : null;
    }

    @Nullable
    public static Blueprint require(ServerPlayer player, String hash, BlockPos size) {
        var blueprint = get(player.getUUID(), hash, size);
        if (blueprint != null) {
            return blueprint;
        }
        if (tasks.busy(player.getUUID())) {
            return null;
        }
        long now = (long) player.level().getServer().getTickCount();
        var last = REQUESTED.get(player.getUUID());
        if (last == null || now - last >= REQUEST_INTERVAL_TICKS || now < last) {
            REQUESTED.put(player.getUUID(), now);
            PacketDistributor.sendToPlayer(player, new BlueprintRequestPacket(hash, size));
        }
        return null;
    }

    public static void receive(ServerPlayer player, BlueprintUploadPacket packet) {
        var id = player.getUUID();
        var hand = packet.hand();
        var tool = hand == null ? NetworkUtil.heldManipulator(player) : player.getItemInHand(hand);
        if (!(tool.getItem() instanceof ManipulatorItem)) {
            UPLOADS.remove(id);
            return;
        }
        long now = (long) player.level().getServer().getTickCount();
        int maxBytes = MMConfig.maxBlueprintBytes();
        if (packet.index() == 0) {
            UPLOADS.remove(id);
            var last = COMPLETED.get(id);
            if (tasks.busy(id) || last != null && now - last < UPLOAD_INTERVAL_TICKS && now >= last) {
                reject(player, MMMessage.BLUEPRINT_UPLOAD_TOO_SOON.text());
                return;
            }
            if (packet.count() < 1 || packet.count() > maxChunks(maxBytes)) {
                reject(player, MMMessage.BLUEPRINT_UPLOAD_TOO_LARGE.text(
                        count(maxBytes)));
                return;
            }
            UPLOADS.put(id, new Upload(packet.transferId(), packet.count(), hand));
        }
        var upload = UPLOADS.get(id);
        if (upload == null || upload.transferId != packet.transferId() || upload.count != packet.count()
                || upload.hand != hand || upload.received != packet.index()) {
            UPLOADS.remove(id);
            return;
        }
        var chunk = packet.chunk();
        if (chunk.length > BlueprintUploadPacket.CHUNK_BYTES || upload.bytes.size() + chunk.length > maxBytes) {
            UPLOADS.remove(id);
            reject(player, MMMessage.BLUEPRINT_UPLOAD_TOO_LARGE.text(
                    count(maxBytes)));
            return;
        }
        upload.bytes.writeBytes(chunk);
        upload.lastTick = now;
        if (++upload.received < upload.count) {
            return;
        }
        UPLOADS.remove(id);
        if (tasks.busy(id)) {
            reject(player, MMMessage.BLUEPRINT_UPLOAD_TOO_SOON.text());
            return;
        }
        var bytes = upload.bytes.toByteArray();
        var maxVolume = MMConfig.MAX_REGION_VOLUME.get();
        tasks.submit(player, "upload", dropped -> {
            var blueprint = Blueprint.decode(bytes, maxBytes, maxVolume).withPreview(null);
            MatterManipulator.LOGGER.debug("Blueprint upload \"{}\" from {}: {} bytes", blueprint.header().name(),
                    player.getGameProfile().name(), bytes.length);
            return target -> {
                COMPLETED.put(id, (long) player.level().getServer().getTickCount());
                LOADED.put(id, blueprint);
                if (hand != null && target == player && player.getItemInHand(hand) == tool
                        && tool.getItem() instanceof ManipulatorItem item
                        && item.tier().canUse(ManipulatorMode.BLUEPRINT)) {
                    var header = blueprint.header();
                    var state = ToolState.get(tool);
                    state.setBlueprintRef(new BlueprintRef(header.hash(), header.name(), header.size()));
                    state.clearCoord("C");
                    ToolStateSyncPacket.send(player, hand);
                    ChatUtil.info(player, MMMessage.BLUEPRINT_LOADED.text(
                            header.name()));
                }
            };
        }, (target, failure) -> {
            COMPLETED.put(id, (long) player.level().getServer().getTickCount());
            reject(target, Component.literal(String.valueOf(failure.getMessage())));
        });
    }

    public static boolean admitItemUse(ServerPlayer player) {
        if (tasks.busy(player.getUUID())) {
            return false;
        }
        long now = (long) player.level().getServer().getTickCount();
        var last = ITEM_USES.get(player.getUUID());
        return last == null || now - last >= ITEM_USE_INTERVAL_TICKS || now < last;
    }

    public static void submitItemUse(ServerPlayer player, String kind, BlueprintServerTasks.Work work,
                                     BiConsumer<ServerPlayer, IOException> failed) {
        var id = player.getUUID();
        tasks.submit(player, kind, dropped -> {
            var result = work.run(dropped);
            return target -> {
                ITEM_USES.put(id, (long) player.level().getServer().getTickCount());
                result.accept(target);
            };
        }, (target, failure) -> {
            ITEM_USES.put(id, (long) player.level().getServer().getTickCount());
            failed.accept(target, failure);
        });
    }

    public static void tick(long now) {
        for (Runnable result; (result = RESULTS.poll()) != null; ) {
            result.run();
        }
        UPLOADS.values().removeIf(upload -> now - upload.lastTick > UPLOAD_TIMEOUT_TICKS || now < upload.lastTick);
    }

    public static void forget(UUID player) {
        LOADED.remove(player);
        UPLOADS.remove(player);
        REQUESTED.remove(player);
        COMPLETED.remove(player);
        ITEM_USES.remove(player);
        tasks.forget(player);
    }

    public static void clear() {
        LOADED.clear();
        UPLOADS.clear();
        REQUESTED.clear();
        COMPLETED.clear();
        ITEM_USES.clear();
        tasks.clear();
        RESULTS.clear();
    }

    static int maxChunks(int maxBytes) {
        return (maxBytes + BlueprintUploadPacket.CHUNK_BYTES - 1) / BlueprintUploadPacket.CHUNK_BYTES + 1;
    }

    private static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static void reject(ServerPlayer player, Component reason) {
        ChatUtil.error(player, MMMessage.BLUEPRINT_REJECTED.text(reason));
    }

    private static final class Upload {
        private final int transferId;
        private final int count;
        @Nullable
        private final InteractionHand hand;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int received;
        private long lastTick;

        private Upload(int transferId, int count, @Nullable InteractionHand hand) {
            this.transferId = transferId;
            this.count = count;
            this.hand = hand;
        }
    }
}
