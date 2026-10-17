package lu.kolja.matter_manipulator.client.integration.projectred;

import codechicken.lib.render.CCRenderState;
import codechicken.lib.render.buffer.TransformingVertexConsumer;
import codechicken.multipart.api.MultipartClientRegistry;
import codechicken.multipart.api.part.MultiPart;
import codechicken.multipart.block.TileMultipart;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import lu.kolja.matter_manipulator.api.client.ClientIntegrationContext;
import lu.kolja.matter_manipulator.api.client.ClientPreviewIntegration;
import lu.kolja.matter_manipulator.api.client.PreviewDetail;
import lu.kolja.matter_manipulator.api.BlockSnapshot;
import lu.kolja.matter_manipulator.common.integration.projectred.MultipartPreviewParts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

final class MultipartClientIntegration implements ClientPreviewIntegration {

    private static final int CACHED_TILES = 256;

    private final Map<CompoundTag, Optional<TileMultipart>> tiles = new LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CompoundTag, Optional<TileMultipart>> eldest) {
            return size() > CACHED_TILES;
        }
    };
    private WeakReference<ClientIntegrationContext.StaticPreview> lastContext = new WeakReference<>(null);
    private TileMultipart lastTile;

    @Override
    public boolean renderStatic(ClientIntegrationContext.StaticPreview context, PoseStack pose, VertexConsumer out,
            RenderType layer) {
        if (context.captured() == null && MultipartPreviewParts.isIdentity(context.transform())) {
            return context.sourceBlockEntity() instanceof TileMultipart live && draw(live, context, pose, out, layer);
        }
        if (context != lastContext.get()) {
            lastContext = new WeakReference<>(context);
            lastTile = detached(context);
        }
        if (lastTile == null) {
            return false;
        }
        lastTile.setLevel(context.level());
        try {
            return draw(lastTile, context, pose, out, layer);
        } finally {
            lastTile.setLevel(null);
        }
    }

    @Override
    public ItemStack previewIcon(BlockSnapshot captured) {
        var level = Minecraft.getInstance().level;
        return level == null ? ItemStack.EMPTY
                : MultipartPreviewParts.icon(captured.payload(MultipartPreviewParts.PAYLOAD), level.registryAccess());
    }

    private static boolean draw(TileMultipart tile, ClientIntegrationContext.StaticPreview context, PoseStack pose,
            VertexConsumer out, RenderType layer) {
        if (tile.getPartList().isEmpty()) {
            return false;
        }
        var ccrs = CCRenderState.instance();
        ccrs.reset();
        ccrs.bind(new TransformingVertexConsumer(out, pose), DefaultVertexFormat.BLOCK);
        ccrs.lightMatrix.locate(context.view(), context.pos());
        for (var part : tile.getPartList()) {
            var renderer = MultipartClientRegistry.<MultiPart>getRenderer(part.getType());
            if (renderer != null) {
                renderer.renderStatic(part, layer, ccrs);
            }
        }
        return true;
    }

    private @Nullable TileMultipart detached(ClientIntegrationContext.StaticPreview context) {
        var registries = context.level().registryAccess();
        CompoundTag payload;
        if (context.captured() != null) {
            payload = context.payload(MultipartPreviewParts.PAYLOAD);
            if (!context.lineDirections().isEmpty()) {
                payload = MultipartPreviewParts.routed(payload, context.lineDirections(), registries);
            }
        } else if (context.detail() == PreviewDetail.FULL && context.sourceBlockEntity() instanceof TileMultipart live) {
            payload = MultipartPreviewParts.turnedParts(live, context.transform(), registries);
        } else {
            return null;
        }
        if (payload == null) {
            return null;
        }
        payload.remove("drops");
        payload.remove("paid");
        var key = payload;
        return tiles.computeIfAbsent(key, ignored -> Optional.ofNullable(MultipartPreviewParts.tile(key, registries))).orElse(null);
    }
}
