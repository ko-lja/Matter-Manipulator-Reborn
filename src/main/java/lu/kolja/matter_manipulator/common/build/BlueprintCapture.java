package lu.kolja.matter_manipulator.common.build;

import lu.kolja.matter_manipulator.common.blueprint.Blueprint;
import lu.kolja.matter_manipulator.common.blueprint.BlueprintServerCache;
import lu.kolja.matter_manipulator.common.config.MMConfig;
import lu.kolja.matter_manipulator.common.integration.IntegrationDispatcher;
import lu.kolja.matter_manipulator.common.localization.MMMessage;
import lu.kolja.matter_manipulator.common.network.BlueprintDownloadPacket;
import lu.kolja.matter_manipulator.common.state.CapturedBlock;
import lu.kolja.matter_manipulator.common.util.ChatUtil;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;

public final class BlueprintCapture {

    public static long clientDecodeQuota = Blueprint.CLIENT_DECODE_QUOTA;

    private final ServerPlayer player;
    private final String name;
    private final CopyCompilation.Source source;
    @Nullable
    private byte[] stream;

    public BlueprintCapture(ServerPlayer player, String name, CopyCompilation.Source source) {
        this.player = player;
        this.name = name;
        this.source = source;
    }

    ServerPlayer player() {
        return player;
    }

    ResourceKey<Level> dimension() {
        return source.key.dimension();
    }

    @Nullable
    public byte[] stream() {
        return stream;
    }

    boolean advance(ServerLevel level, PreparationBudget.Slice slice) {
        if (source.read < source.volume) {
            if (!BuildPlanner.loadedCheck(level).test(source.min, source.max)) {
                ChatUtil.error(player, MMMessage.REGION_NOT_LOADED.text());
                return true;
            }
            slice.open();
            try {
                while (source.read < source.volume && slice.take()) {
                    source.read(level, source.read);
                    source.read++;
                }
            } finally {
                slice.close();
            }
            if (source.read < source.volume) {
                return false;
            }
        }
        return save();
    }

    public boolean save() {
        if (BlueprintServerCache.busy(player.getUUID())) {
            return false;
        }
        var portable = new IdentityHashMap<CapturedBlock, CapturedBlock>();
        var failures = new IdentityHashMap<CapturedBlock, Integer>();
        var distinct = new HashMap<CapturedBlock, CapturedBlock>();
        var dense = new CapturedBlock[source.volume];
        long dropped = 0L;
        for (int i = 0; i < source.volume; i++) {
            var capture = source.captures[i];
            if (capture == null) {
                continue;
            }
            var entry = portable.get(capture);
            if (entry == null) {
                entry = capture;
                if (capture.hasCompat()) {
                    var failed = new int[1];
                    var payloads = IntegrationDispatcher.portable(capture.state().resolve(), capture.compat(), () -> failed[0]++);
                    if (!capture.compatEquals(payloads)) {
                        entry = new CapturedBlock(capture.state(), capture.blockEntity(), payloads);
                    }
                    failures.put(capture, failed[0]);
                }
                var shared = distinct.putIfAbsent(entry, entry);
                entry = shared != null ? shared : entry;
                if (distinct.size() > Blueprint.MAX_PALETTE) {
                    ChatUtil.error(player, MMMessage.BLUEPRINT_TOO_MANY_BLOCKS.text(
                            count(Blueprint.MAX_PALETTE)));
                    return true;
                }
                portable.put(capture, entry);
            }
            dense[i] = entry;
            dropped += failures.getOrDefault(capture, 0);
        }

        var size = source.max.subtract(source.min).offset(1, 1, 1);
        int maxBytes = MMConfig.maxBlueprintBytes();
        var maxVolume = MMConfig.MAX_REGION_VOLUME.get();
        int captured = source.captured;
        long payloadsDropped = dropped;
        long clientQuota = clientDecodeQuota;
        BlueprintServerCache.submit(player, "save", jobDropped -> {
            var encoded = Blueprint.encode(name, size, dense).stream();
            if (encoded.length > maxBytes) {
                return target -> ChatUtil.error(target,
                        MMMessage.BLUEPRINT_TOO_LARGE.text(count(encoded.length), count(maxBytes)));
            }
            try {
                Blueprint.decode(encoded, maxBytes, maxVolume);
            } catch (IOException exception) {
                return target -> unreadable(target, exception);
            }
            if (Blueprint.BODY_QUOTA_PER_BYTE * maxBytes > clientQuota) {
                try {
                    Blueprint.decode(encoded, maxBytes, maxVolume, clientQuota);
                } catch (IOException exception) {
                    return target -> ChatUtil.error(target,
                            MMMessage.BLUEPRINT_CLIENT_QUOTA.text(count(clientQuota >> 20)));
                }
            }
            return target -> {
                stream = encoded;
                BlueprintDownloadPacket.send(target, encoded);
                var message = MMMessage.BLUEPRINT_SAVED.text(name,
                        count(captured));
                if (payloadsDropped > 0) {
                    message.append(" ").append(MMMessage.BLUEPRINT_PAYLOADS_DROPPED.text(count(payloadsDropped)));
                }
                ChatUtil.info(target, message);
            };
        }, BlueprintCapture::unreadable);
        return true;
    }

    private static void unreadable(ServerPlayer target, IOException exception) {
        ChatUtil.error(target, MMMessage.BLUEPRINT_UNREADABLE.text(
                exception.getMessage()));
    }

    private static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
