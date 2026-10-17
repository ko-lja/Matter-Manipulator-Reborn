package lu.kolja.matter_manipulator.client.blueprint;

import java.nio.file.Files;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.client.preview.PreviewRenderer;
import lu.kolja.matter_manipulator.common.blueprint.Blueprint;
import lu.kolja.matter_manipulator.common.config.MMConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class PreviewBake {

    static final int PREVIEW_QUAD_CAP = 131_072;
    static final int ATLAS_MAX_SIDE = 2_048;
    private static final int MAX_SIDE_BLOCKS = GlbWriter.MAX_QUANTIZED / 16;
    private static final Logger LOGGER = LoggerFactory.getLogger("matter_manipulator.blueprint");
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();
    private static final Vector3f LIGHT_0;
    private static final Vector3f LIGHT_1;

    static {
        var lights = new Matrix4f().rotationYXZ(1.0821041F, 3.2375858F, 0.0F)
                .rotateYXZ(-(float) Math.PI / 8.0F, 2.3561945F, 0.0F);
        LIGHT_0 = lights.transformDirection(new Vector3f(0.2F, 1.0F, -0.7F).normalize()).normalize();
        LIGHT_1 = lights.transformDirection(new Vector3f(-0.2F, 1.0F, 0.7F).normalize()).normalize();
    }

    private final Blueprint blueprint;
    private final int maxPreviewInspections;
    private String generator = MatterManipulator.MOD_ID;
    @Nullable
    private String skipped;
    private boolean done;
    @Nullable
    private Minecraft minecraft;
    @Nullable
    private PreviewRenderer.BlueprintSurface surface;
    @Nullable
    private Recorder recorder;
    @Nullable
    private TextureAtlasSprite missingSprite;
    private int cursor;
    private int blocks;
    private int layerY = Integer.MIN_VALUE;
    private int layerQuads;
    private int layerDropped;
    private int layerBlocks;
    private boolean sampled;
    private int quads;
    private int dropped;
    private List<GlbWriter.Mesh> meshes = List.of();
    @Nullable
    private NativeImage atlas;
    private int sprites;
    private String atlasSize = "0x0";
    private long renderNanos;
    private long longestNanos;
    private int steps;

    private PreviewBake(Blueprint blueprint) {
        this.blueprint = blueprint;
        maxPreviewInspections = MMConfig.CLIENT_SPEC.isLoaded() ? MMConfig.MAX_PREVIEW_INSPECTIONS.get()
                : MMConfig.MAX_PREVIEW_INSPECTIONS.getDefault();
    }

    static PreviewBake start(@Nullable Minecraft minecraft, Blueprint blueprint) {
        var bake = new PreviewBake(blueprint);
        long started = System.nanoTime();
        var size = blueprint.header().size();
        if (minecraft == null || minecraft.level == null) {
            bake.skip("no_level");
        } else if (blueprint.volume() > bake.maxPreviewInspections) {
            bake.skip("volume");
        } else if (Math.max(size.getX(), Math.max(size.getY(), size.getZ())) > MAX_SIDE_BLOCKS) {
            bake.skip("span");
        } else {
            bake.minecraft = minecraft;
            bake.recorder = new Recorder();
            bake.generator = MatterManipulator.MOD_ID + " " + ModList.get().getModContainerById(MatterManipulator.MOD_ID)
                    .map(container -> container.getModInfo().getVersion().toString()).orElse("unknown");
            try {
                bake.missingSprite = missingSprite(minecraft);
                bake.surface = PreviewRenderer.blueprintSurface(minecraft, blueprint, bake.maxPreviewInspections);
            } catch (RuntimeException | LinkageError exception) {
                bake.fail(exception);
            }
        }
        bake.timed(started);
        return bake;
    }

    boolean done() {
        return done;
    }

    void advance(long deadline) {
        if (done) {
            return;
        }
        long started = System.nanoTime();
        try {
            if (!surface.find(deadline)) {
                timed(started);
                return;
            }
            var renderer = new ModelBlockRenderer(false, false, minecraft.getBlockColors());
            var pose = new PoseStack();
            while (cursor < surface.size()) {
                if (surface.y(cursor) != layerY) {
                    layerY = surface.y(cursor);
                    layerQuads = recorder.quads;
                    layerDropped = recorder.dropped;
                    layerBlocks = blocks;
                }
                recorder.begin();
                boolean drawn = true;
                pose.pushPose();
                try {
                    var block = surface.resolve(cursor);
                    var pos = block.pos();
                    pose.translate(pos.getX(), pos.getY(), pos.getZ());
                    recorder.blockY = pos.getY();
                    PreviewRenderer.renderPreviewBlock(renderer, pose, recorder, surface.view(), block, surface.hidden(cursor));
                    PreviewRenderer.renderItemRenderedBlock(pose, recorder, block);
                } catch (RuntimeException | LinkageError exception) {
                    recorder.rollback();
                    drawn = false;
                } finally {
                    pose.popPose();
                }
                cursor++;
                recorder.endBlock();
                if (recorder.quads > PREVIEW_QUAD_CAP) {
                    if (layerQuads > 0) {
                        recorder.truncate(layerQuads, layerDropped);
                        blocks = layerBlocks;
                    } else {
                        recorder.rollback();
                    }
                    sampled = true;
                    break;
                }
                if (drawn) {
                    blocks++;
                }
                if (System.nanoTime() >= deadline) {
                    timed(started);
                    return;
                }
            }
            finish();
        } catch (RuntimeException | LinkageError exception) {
            fail(exception);
        }
        timed(started);
    }

    byte[] fileStream() {
        long started = System.nanoTime();
        var png = new byte[0];
        var glb = new byte[0];
        var section = new byte[0];
        if (skipped == null) {
            try {
                png = atlas == null ? png : png(atlas);
                glb = GlbWriter.write(generator, blueprint.header().size().getY(), meshes, png);
                var tag = new CompoundTag();
                tag.putInt("format", Blueprint.FORMAT);
                tag.putByteArray("glb", glb);
                tag.putInt("quads", quads);
                tag.putInt("blocks", blocks);
                tag.putByte("sampled", (byte) (sampled ? 1 : 0));
                tag.putInt("dropped", dropped);
                tag.putInt("maxPreviewInspections", maxPreviewInspections);
                tag.putInt("quadCap", PREVIEW_QUAD_CAP);
                section = Blueprint.previewSection(tag);
                if (section.length > Blueprint.PREVIEW_BYTES_CAP) {
                    skip("bytes");
                }
            } catch (IOException | RuntimeException | OutOfMemoryError exception) {
                fail(exception);
            } finally {
                close();
            }
        }
        if (skipped != null) {
            var tag = new CompoundTag();
            tag.putInt("format", Blueprint.FORMAT);
            tag.putString("skipped", skipped);
            tag.putInt("quads", quads);
            tag.putInt("maxPreviewInspections", maxPreviewInspections);
            tag.putInt("quadCap", PREVIEW_QUAD_CAP);
            section = Blueprint.previewSection(tag);
        }
        var file = blueprint.withPreview(section).fileStream();
        var timing = String.format(Locale.ROOT, "%.1f ms on the render thread in %d steps (longest %.1f ms),"
                + " encoding %.1f ms", renderNanos / 1e6, steps, longestNanos / 1e6, (System.nanoTime() - started) / 1e6);
        var name = blueprint.header().name();
        if (skipped != null) {
            LOGGER.info("Blueprint preview \"{}\": skipped {}, {} quads, section {}, file {} (body {}), {}", name,
                    skipped, quads, section.length, file.length, file.length - section.length, timing);
        } else {
            LOGGER.info("Blueprint preview \"{}\": {} blocks{}, {} quads, {} dropped, {} sprites {}, mesh {}, png {},"
                            + " section {}, file {} (body {}), {}", name, blocks, sampled ? ", sampled" : "", quads,
                    dropped, sprites, atlasSize, glb.length - png.length, png.length, section.length, file.length,
                    file.length - section.length, timing);
        }
        return file;
    }

    void abandon() {
        if (!done) {
            skip("no_level");
        }
    }

    void close() {
        if (atlas != null) {
            atlas.close();
            atlas = null;
        }
    }

    static float lightAccum(float nx, float ny, float nz) {
        float light0 = Math.max(0.0F, LIGHT_0.x() * nx + LIGHT_0.y() * ny + LIGHT_0.z() * nz);
        float light1 = Math.max(0.0F, LIGHT_1.x() * nx + LIGHT_1.y() * ny + LIGHT_1.z() * nz);
        return Math.min(1.0F, (light0 + light1) * 0.6F + 0.4F);
    }

    record Packing(int width, int height, int[] x, int[] y) {}

    @Nullable
    static Packing pack(int[] widths, int[] heights, int maxSide) {
        var order = new Integer[widths.length];
        int widest = 1;
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
            widest = Math.max(widest, widths[i]);
        }
        Arrays.sort(order, Comparator.comparingInt((Integer i) -> -heights[i]).thenComparingInt(i -> -widths[i]));
        Packing best = null;
        for (int width = nextPowerOfTwo(widest); width <= maxSide; width *= 2) {
            var x = new int[widths.length];
            var y = new int[widths.length];
            int shelfX = 0;
            int shelfY = 0;
            int shelfHeight = 0;
            for (var i : order) {
                if (shelfX + widths[i] > width) {
                    shelfY += shelfHeight;
                    shelfX = 0;
                    shelfHeight = 0;
                }
                x[i] = shelfX;
                y[i] = shelfY;
                shelfX += widths[i];
                shelfHeight = Math.max(shelfHeight, heights[i]);
            }
            int height = nextPowerOfTwo(shelfY + shelfHeight);
            if (height > maxSide) {
                continue;
            }
            long area = (long) width * height;
            if (best == null || area < (long) best.width() * best.height()
                    || area == (long) best.width() * best.height()
                    && Math.max(width, height) < Math.max(best.width(), best.height())) {
                best = new Packing(width, height, x, y);
            }
        }
        return best;
    }

    private static int nextPowerOfTwo(int value) {
        return value <= 1 ? 1 : Integer.highestOneBit(value - 1) << 1;
    }

    private void finish() {
        if (missingSprite(minecraft) != missingSprite) {
            restart();
            return;
        }
        var indices = new HashMap<Identifier, SpriteIndex>();
        var used = new IdentityHashMap<TextureAtlasSprite, Integer>();
        var usedSprites = new ArrayList<TextureAtlasSprite>();
        var quadSprite = new int[recorder.quads];
        int kept = 0;
        for (int quad = 0; quad < recorder.quads; quad++) {
            var layer = recorder.layers[quad];
            float u = 0.0F;
            float v = 0.0F;
            for (int vertex = quad * 4; vertex < quad * 4 + 4; vertex++) {
                u += recorder.uvs[vertex * 2] / 4;
                v += recorder.uvs[vertex * 2 + 1] / 4;
            }
            var sprite = recorder.sprites[quad];
            if (sprite == null || !sprite.atlasLocation().equals(layer.atlas()) || u < sprite.getU0()
                    || u > sprite.getU1() || v < sprite.getV0() || v > sprite.getV1()) {
                sprite = indices.computeIfAbsent(layer.atlas(),
                        location -> new SpriteIndex((TextureAtlas) minecraft.getTextureManager().getTexture(location))).find(u, v);
            }
            if (sprite == null) {
                quadSprite[quad] = -1;
                continue;
            }
            quadSprite[quad] = used.computeIfAbsent(sprite, key -> {
                usedSprites.add(key);
                return usedSprites.size() - 1;
            });
            kept++;
        }
        quads = kept;
        dropped = recorder.dropped + recorder.quads - kept;
        sprites = usedSprites.size();

        var widths = new int[sprites];
        var heights = new int[sprites];
        for (int i = 0; i < sprites; i++) {
            widths[i] = usedSprites.get(i).contents().width();
            heights[i] = usedSprites.get(i).contents().height();
        }
        var packing = pack(widths, heights, ATLAS_MAX_SIDE);
        if (packing == null) {
            skip("atlas");
            return;
        }
        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        for (int quad = 0; quad < recorder.quads; quad++) {
            if (quadSprite[quad] >= 0) {
                for (int i = quad * 12; i < quad * 12 + 12; i++) {
                    min = Math.min(min, recorder.positions[i]);
                    max = Math.max(max, recorder.positions[i]);
                }
            }
        }
        if (kept > 0 && !GlbWriter.fits(min, max)) {
            skip("span");
            return;
        }
        if (sprites > 0) {
            atlasSize = packing.width() + "x" + packing.height();
            atlas = new NativeImage(packing.width(), packing.height(), true);
            for (int i = 0; i < sprites; i++) {
                var sprite = usedSprites.get(i);
                var frames = sprite.contents().getUniqueFrames();
                int frame = frames.isEmpty() ? 0 : frames.getInt(0);
                for (int y = 0; y < heights[i]; y++) {
                    for (int x = 0; x < widths[i]; x++) {
                        atlas.setPixelABGR(packing.x()[i] + x, packing.y()[i] + y, sprite.getPixelRGBA(frame, x, y));
                    }
                }
            }
        }
        meshes = List.of(mesh("opaque", false, quadSprite, usedSprites, packing),
                mesh("translucent", true, quadSprite, usedSprites, packing));
        recorder = null;
        surface = null;
        done = true;
    }

    private GlbWriter.Mesh mesh(String name, boolean translucent, int[] quadSprite, List<TextureAtlasSprite> sprites,
            Packing packing) {
        int count = 0;
        for (int quad = 0; quad < recorder.quads; quad++) {
            if (quadSprite[quad] >= 0 && recorder.layers[quad].translucent() == translucent) {
                count++;
            }
        }
        var positions = new float[count * 12];
        var uvs = new float[count * 8];
        var colors = new byte[count * 16];
        var blockY = new int[count];
        int out = 0;
        for (int quad = 0; quad < recorder.quads; quad++) {
            var layer = recorder.layers[quad];
            if (quadSprite[quad] < 0 || layer.translucent() != translucent) {
                continue;
            }
            int index = quadSprite[quad];
            var sprite = sprites.get(index);
            int width = sprite.contents().width();
            int height = sprite.contents().height();
            System.arraycopy(recorder.positions, quad * 12, positions, out * 12, 12);
            blockY[out] = recorder.blockY(quad);
            for (int corner = 0; corner < 4; corner++) {
                int vertex = quad * 4 + corner;
                int target = out * 4 + corner;
                float s = Math.max(0.0F, Math.min(1.0F,
                        (recorder.uvs[vertex * 2] - sprite.getU0()) / (sprite.getU1() - sprite.getU0())));
                float t = Math.max(0.0F, Math.min(1.0F,
                        (recorder.uvs[vertex * 2 + 1] - sprite.getV0()) / (sprite.getV1() - sprite.getV0())));
                uvs[target * 2] = (packing.x()[index] + s * width) / packing.width();
                uvs[target * 2 + 1] = (packing.y()[index] + t * height) / packing.height();
                float shade = layer.lit() ? lightAccum(recorder.normals[vertex * 3], recorder.normals[vertex * 3 + 1],
                        recorder.normals[vertex * 3 + 2]) : 1.0F;
                int argb = recorder.colors[vertex];
                colors[target * 4] = (byte) Math.min(255, Math.round((argb >> 16 & 255) * shade));
                colors[target * 4 + 1] = (byte) Math.min(255, Math.round((argb >> 8 & 255) * shade));
                colors[target * 4 + 2] = (byte) Math.min(255, Math.round((argb & 255) * shade));
                colors[target * 4 + 3] = (byte) (argb >>> 24);
            }
            out++;
        }
        return new GlbWriter.Mesh(name, translucent, count, positions, uvs, colors, blockY);
    }

    private void restart() {
        missingSprite = missingSprite(minecraft);
        surface = PreviewRenderer.blueprintSurface(minecraft, blueprint, maxPreviewInspections);
        recorder = new Recorder();
        cursor = 0;
        blocks = 0;
        sampled = false;
        layerY = Integer.MIN_VALUE;
    }

    private static TextureAtlasSprite missingSprite(Minecraft minecraft) {
        return ((TextureAtlas) minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS))
                .getSprite(MissingTextureAtlasSprite.getLocation());
    }

    private void skip(String reason) {
        skipped = reason;
        done = true;
        close();
        recorder = null;
        surface = null;
    }

    private void fail(Throwable exception) {
        if (FAILED.add(blueprint.header().hash())) {
            LOGGER.warn("Could not bake the preview of the blueprint \"{}\"", blueprint.header().name(), exception);
        }
        if (!done && recorder != null) {
            quads = recorder.quads;
        }
        skip("error");
    }

    private void timed(long started) {
        long elapsed = System.nanoTime() - started;
        renderNanos += elapsed;
        longestNanos = Math.max(longestNanos, elapsed);
        steps++;
    }

    private record Layer(Identifier atlas, boolean translucent, boolean lit) {}

    private static Map<RenderType, Layer> layers() {
        var layers = new HashMap<RenderType, Layer>();
        var blocks = TextureAtlas.LOCATION_BLOCKS;
        layers.put(RenderTypes.solidMovingBlock(), new Layer(blocks, false, false));
        layers.put(RenderTypes.cutoutMovingBlock(), new Layer(blocks, false, false));
        layers.put(RenderTypes.translucentMovingBlock(), new Layer(blocks, true, false));
        for (var atlas : List.of(blocks, Sheets.CHEST_SHEET, Sheets.BED_SHEET, Sheets.SIGN_SHEET, Sheets.SHULKER_SHEET,
                Sheets.BANNER_SHEET)) {
            for (var type : List.of(RenderTypes.entitySolid(atlas), RenderTypes.entityCutout(atlas),
                    RenderTypes.entityCutoutCull(atlas))) {
                layers.put(type, new Layer(atlas, false, true));
            }
            for (var type : List.of(RenderTypes.entityTranslucent(atlas), RenderTypes.itemTranslucent(atlas))) {
                layers.put(type, new Layer(atlas, true, true));
            }
        }
        return layers;
    }

    private static final class Recorder implements MultiBufferSource {
        private final Map<RenderType, Layer> layerOf = layers();
        private final Map<RenderType, Consumer> consumers = new HashMap<>();
        private int quads;
        private int dropped;
        private int quadsMark;
        private int droppedMark;
        private int blockY;
        private float[] positions = new float[12 * 1024];
        private float[] uvs = new float[8 * 1024];
        private int[] colors = new int[4 * 1024];
        private float[] normals = new float[12 * 1024];
        private Layer[] layers = new Layer[1024];
        private TextureAtlasSprite[] sprites = new TextureAtlasSprite[1024];
        private int[] blockYs = new int[1024];

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            return consumers.computeIfAbsent(type, key -> new Consumer(layerOf.get(key)));
        }

        int blockY(int quad) {
            return blockYs[quad];
        }

        void begin() {
            quadsMark = quads;
            droppedMark = dropped;
        }

        void rollback() {
            truncate(quadsMark, droppedMark);
        }

        void truncate(int quads, int dropped) {
            for (var consumer : consumers.values()) {
                consumer.pending = false;
            }
            this.quads = quads;
            this.dropped = dropped;
            endBlock();
        }

        void endBlock() {
            for (var consumer : consumers.values()) {
                consumer.commit();
                consumer.vertex = 0;
            }
        }

        private void grow() {
            int capacity = layers.length * 2;
            positions = Arrays.copyOf(positions, capacity * 12);
            uvs = Arrays.copyOf(uvs, capacity * 8);
            colors = Arrays.copyOf(colors, capacity * 4);
            normals = Arrays.copyOf(normals, capacity * 12);
            layers = Arrays.copyOf(layers, capacity);
            sprites = Arrays.copyOf(sprites, capacity);
            blockYs = Arrays.copyOf(blockYs, capacity);
        }

        private final class Consumer implements VertexConsumer {
            @Nullable
            private final Layer layer;
            @Nullable
            private TextureAtlasSprite sprite;
            private int vertex;
            private boolean pending;
            private float x;
            private float y;
            private float z;
            private float u;
            private float v;
            private int color = -1;
            private float normalX;
            private float normalY;
            private float normalZ;

            private Consumer(@Nullable Layer layer) {
                this.layer = layer;
            }

            @Override
            public VertexConsumer addVertex(float x, float y, float z) {
                commit();
                this.x = x;
                this.y = y;
                this.z = z;
                pending = true;
                return this;
            }

            @Override
            public VertexConsumer setColor(int red, int green, int blue, int alpha) {
                color = alpha << 24 | red << 16 | green << 8 | blue;
                return this;
            }

            @Override
            public VertexConsumer setUv(float u, float v) {
                this.u = u;
                this.v = v;
                return this;
            }

            @Override
            public VertexConsumer setUv1(int u, int v) {
                return this;
            }

            @Override
            public VertexConsumer setUv2(int u, int v) {
                return this;
            }

            @Override
            public VertexConsumer setNormal(float x, float y, float z) {
                normalX = x;
                normalY = y;
                normalZ = z;
                return this;
            }

            private void commit() {
                if (!pending) {
                    return;
                }
                pending = false;
                if (layer == null) {
                    if (++vertex == 4) {
                        vertex = 0;
                        dropped++;
                    }
                    return;
                }
                if (quads == layers.length) {
                    grow();
                }
                int index = quads * 4 + vertex;
                positions[index * 3] = x;
                positions[index * 3 + 1] = y;
                positions[index * 3 + 2] = z;
                uvs[index * 2] = u;
                uvs[index * 2 + 1] = v;
                colors[index] = color;
                normals[index * 3] = normalX;
                normals[index * 3 + 1] = normalY;
                normals[index * 3 + 2] = normalZ;
                color = -1;
                if (++vertex == 4) {
                    vertex = 0;
                    layers[quads] = layer;
                    sprites[quads] = sprite;
                    blockYs[quads] = blockY;
                    quads++;
                }
            }

            @Override
            public VertexConsumer setLineWidth(float width) {
                return this;
            }

            @Override
            public VertexConsumer setColor(int color) {
                return setColor(ARGB.red(color), ARGB.green(color), ARGB.blue(color), ARGB.alpha(color));
            }

            @Override
            public void putBlockBakedQuad(float x, float y, float z, BakedQuad quad, QuadInstance instance) {
                sprite = quad.materialInfo().sprite();
                try {
                    VertexConsumer.super.putBlockBakedQuad(x, y, z, quad, instance);
                    commit();
                } finally {
                    sprite = null;
                }
            }

            @Override
            public void putBakedQuad(PoseStack.Pose pose, BakedQuad quad, QuadInstance instance) {
                sprite = quad.materialInfo().sprite();
                try {
                    VertexConsumer.super.putBakedQuad(pose, quad, instance);
                    commit();
                } finally {
                    sprite = null;
                }
            }
        }
    }

    private static final class SpriteIndex {
        private static final int CELL = 16;
        private final int width;
        private final int height;
        private final Map<Long, List<TextureAtlasSprite>> cells = new HashMap<>();

        private SpriteIndex(TextureAtlas atlas) {
            int width = 1;
            int height = 1;
            var sprites = new ArrayList<TextureAtlasSprite>();
            for (var sprite : atlas.getTextures().values()) {
                sprites.add(sprite);
                width = Math.max(width, Math.round((sprite.getX() + sprite.contents().width()) / sprite.getU1()));
                height = Math.max(height, Math.round((sprite.getY() + sprite.contents().height()) / sprite.getV1()));
            }
            this.width = width;
            this.height = height;
            for (var sprite : sprites) {
                int lastX = (sprite.getX() + sprite.contents().width() - 1) / CELL;
                int lastY = (sprite.getY() + sprite.contents().height() - 1) / CELL;
                for (int cellY = sprite.getY() / CELL; cellY <= lastY; cellY++) {
                    for (int cellX = sprite.getX() / CELL; cellX <= lastX; cellX++) {
                        cells.computeIfAbsent(key(cellX, cellY), ignored -> new ArrayList<>(1)).add(sprite);
                    }
                }
            }
        }

        @Nullable
        private TextureAtlasSprite find(float u, float v) {
            int x = (int) Math.floor(u * width);
            int y = (int) Math.floor(v * height);
            var candidates = cells.get(key(Math.floorDiv(x, CELL), Math.floorDiv(y, CELL)));
            if (candidates == null) {
                return null;
            }
            for (var sprite : candidates) {
                if (x >= sprite.getX() && x < sprite.getX() + sprite.contents().width() && y >= sprite.getY()
                        && y < sprite.getY() + sprite.contents().height()) {
                    return sprite;
                }
            }
            return null;
        }

        private static long key(int x, int y) {
            return (long) x << 32 | y & 0xFFFFFFFFL;
        }
    }

    private static byte[] png(NativeImage image) throws IOException {
        var file = Files.createTempFile("matter_manipulator-atlas", ".png");
        try {
            image.writeToFile(file);
            return Files.readAllBytes(file);
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
