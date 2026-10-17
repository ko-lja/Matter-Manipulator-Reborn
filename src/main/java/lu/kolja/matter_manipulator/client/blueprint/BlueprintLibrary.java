package lu.kolja.matter_manipulator.client.blueprint;

import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.client.ClientEvents;
import lu.kolja.matter_manipulator.common.blueprint.Blueprint;
import lu.kolja.matter_manipulator.common.blueprint.BlueprintFiles;
import lu.kolja.matter_manipulator.common.config.MMConfig;
import lu.kolja.matter_manipulator.common.localization.MMMessage;
import lu.kolja.matter_manipulator.common.network.BlueprintDownloadPacket;
import lu.kolja.matter_manipulator.common.network.BlueprintUploadPacket;
import lu.kolja.matter_manipulator.common.state.ToolState;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@EventBusSubscriber(modid = MatterManipulator.MOD_ID, value = Dist.CLIENT)
public final class BlueprintLibrary {

    public static final String CLIPBOARD_PREFIX = "mmbp1:";
    public static final int CLIPBOARD_CHARS = 1 << 20;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Executor WORKER = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "Matter Manipulator blueprints");
        thread.setDaemon(true);
        return thread;
    });

    static BlueprintTasks tasks = new BlueprintTasks(WORKER, task -> Minecraft.getInstance().execute(task));
    private static final long BAKE_BUDGET_NANOS = 4_000_000L;
    private static final ArrayDeque<PendingBake> BAKES = new ArrayDeque<>();
    @Nullable
    private static Download download;
    private static List<Entry> entries = List.of();
    private static long scannedModified = Long.MIN_VALUE;
    private static final Set<Path> UNREADABLE = new HashSet<>();
    private static final Set<String> UNAVAILABLE_FOR_PREVIEW = new HashSet<>();
    @Nullable
    private static BlueprintTasks.Load previewLoad;
    private static int nextTransfer;
    private static final MissingBlueprintReports MISSING = new MissingBlueprintReports();
    private static final LibraryPage PAGE = new LibraryPage();
    private static long ticks;
    private static long checkedTick = -1;
    private static int choice;

    private BlueprintLibrary() {}

    public record Entry(Path path, Blueprint.Header header, long modified, long bytes) {}

    public static Path folder() {
        return FMLPaths.CONFIGDIR.get().resolve("matter_manipulator/blueprints");
    }

    public static List<Entry> entries() {
        if (checkedTick == ticks) {
            return entries;
        }
        checkedTick = ticks;
        var folder = folder();
        long modified;
        try {
            modified = Files.isDirectory(folder) ? Files.getLastModifiedTime(folder).toMillis() : Long.MIN_VALUE;
        } catch (IOException exception) {
            modified = Long.MIN_VALUE;
        }
        if (modified == Long.MIN_VALUE) {
            entries = List.of();
            scannedModified = Long.MIN_VALUE;
            return entries;
        }
        if (modified != scannedModified) {
            entries = scan(folder);
            scannedModified = modified;
            UNAVAILABLE_FOR_PREVIEW.clear();
        }
        return entries;
    }

    public static void refresh() {
        scannedModified = Long.MIN_VALUE;
        checkedTick = -1;
        MISSING.reset();
    }

    public static Optional<Entry> find(String hash, BlockPos size) {
        return entries().stream().filter(entry -> entry.header().matches(hash, size)).findFirst();
    }

    static Blueprint read(Path path) throws IOException {
        var stream = BlueprintFiles.read(path, MMConfig.BLUEPRINT_BYTES_CEILING + Blueprint.PREVIEW_BYTES_CAP);
        return Blueprint.decode(stream, MMConfig.BLUEPRINT_BYTES_CEILING, MMConfig.REGION_VOLUME_CEILING,
                Blueprint.CLIENT_DECODE_QUOTA).withPreview(null);
    }

    public static BlueprintTasks.Load loadAsync(Entry entry) {
        var header = entry.header();
        return tasks.load(new BlueprintTasks.Key(entry.path(), header.hash(), header.size()), () -> read(entry.path()));
    }

    @Nullable
    public static Blueprint loadedForPreview(String hash, BlockPos size) {
        var current = previewLoad;
        if (current != null && current.matches(hash, size) && (current.blueprint() != null || !current.done())) {
            return current.blueprint();
        }
        entries();
        var key = key(hash, size);
        if (UNAVAILABLE_FOR_PREVIEW.contains(key)) {
            return null;
        }
        var entry = find(hash, size);
        if (entry.isEmpty()) {
            UNAVAILABLE_FOR_PREVIEW.add(key);
            return null;
        }
        var load = loadAsync(entry.get());
        previewLoad = load;
        tasks.whenDone(load, () -> {
            if (load.failure() != null) {
                UNAVAILABLE_FOR_PREVIEW.add(key);
                MatterManipulator.LOGGER.warn("Could not read the blueprint {} for the preview", entry.get().path(),
                        load.failure());
            }
        });
        return load.blueprint();
    }

    public static boolean upload(@Nullable InteractionHand hand, Blueprint blueprint) {
        var refusal = sizeRefusal(blueprint);
        if (refusal != null) {
            message(refusal);
            return false;
        }
        var stream = blueprint.stream();
        int transferId = nextTransfer++;
        int chunkBytes = BlueprintUploadPacket.CHUNK_BYTES;
        int count = Math.max(1, (stream.length + chunkBytes - 1) / chunkBytes);
        for (int index = 0; index < count; index++) {
            int from = index * chunkBytes;
            var chunk = Arrays.copyOfRange(stream, from, Math.min(stream.length, from + chunkBytes));
            ClientPacketDistributor.sendToServer(new BlueprintUploadPacket(transferId, index, count, hand, chunk));
        }
        return true;
    }

    public static boolean canChoose() {
        return PAGE.canUpload(ticks);
    }

    public static boolean choose(InteractionHand hand, Entry entry, Runnable sent, Consumer<Component> failed) {
        if (!PAGE.canUpload(ticks)) {
            return false;
        }
        var header = entry.header();
        PAGE.loading(header.name());
        int current = ++choice;
        var load = loadAsync(entry);
        tasks.whenDone(load, () -> {
            if (choice != current) {
                return;
            }
            PAGE.loading(null);
            var blueprint = load.blueprint();
            if (blueprint == null) {
                MatterManipulator.LOGGER.warn("Could not read the blueprint {}", entry.path(), load.failure());
                failed.accept(unreadable(entry, load.failure()));
                return;
            }
            var refusal = sizeRefusal(blueprint);
            if (refusal != null) {
                failed.accept(refusal);
                return;
            }
            upload(hand, blueprint);
            PAGE.uploaded(key(header.hash(), header.size()), header.name(), ticks);
            sent.run();
        });
        return true;
    }

    public static void cancelChoice() {
        choice++;
        PAGE.loading(null);
    }

    @Nullable
    public static String uploading() {
        return PAGE.uploading();
    }

    public static List<String> missingMods(Blueprint.Header header) {
        return LibraryPage.missingMods(header.mods(), ModList.get()::isLoaded);
    }

    public static void uploadRequested(String hash, BlockPos size) {
        var entry = find(hash, size);
        if (entry.isEmpty()) {
            if (MISSING.report(key(hash, size))) {
                message(MMMessage.BLUEPRINT_NOT_IN_LIBRARY.text(
                        hash.substring(0, Math.min(8, hash.length()))).withStyle(ChatFormatting.RED));
            }
            return;
        }
        var load = loadAsync(entry.get());
        tasks.whenDone(load, () -> {
            var blueprint = load.blueprint();
            if (blueprint == null) {
                MatterManipulator.LOGGER.warn("Could not read the blueprint {}", entry.get().path(), load.failure());
                message(unreadable(entry.get(), load.failure()));
                return;
            }
            upload(null, blueprint);
        });
    }

    @Nullable
    private static Component sizeRefusal(Blueprint blueprint) {
        int bytes = blueprint.stream().length;
        int maxBytes = MMConfig.maxBlueprintBytes();
        return bytes <= maxBytes ? null : MMMessage.BLUEPRINT_UPLOAD_REFUSED.text(
                blueprint.header().name(), count(bytes), count(maxBytes)).withStyle(ChatFormatting.RED);
    }

    public static Component unreadable(Entry entry, IOException exception) {
        return MMMessage.BLUEPRINT_FILE_UNREADABLE.text(
                entry.path().getFileName().toString(), exception.getMessage()).withStyle(ChatFormatting.RED);
    }

    @Nullable
    static Path existing(Path folder, Blueprint.Header header) throws IOException {
        if (!Files.isDirectory(folder)) {
            return null;
        }
        Path found = null;
        try (var files = Files.list(folder)) {
            for (var path : (Iterable<Path>) files::iterator) {
                if (!path.getFileName().toString().endsWith(BlueprintFiles.EXTENSION) || !Files.isRegularFile(path)) {
                    continue;
                }
                Blueprint.Header stored;
                try {
                    stored = BlueprintFiles.readHeader(path);
                } catch (IOException exception) {
                    continue;
                }
                if (stored.matches(header.hash(), header.size()) && stored.name().equals(header.name())
                        && (found == null || path.compareTo(found) < 0)) {
                    found = path;
                }
            }
        }
        return found;
    }

    private static void saveWithPreview(Blueprint blueprint, Consumer<Path> saved, Consumer<Path> already,
            Consumer<IOException> failed) {
        bake(blueprint, bake -> tasks.run(() -> {
            try {
                var header = blueprint.header();
                var existing = existing(folder(), header);
                if (existing != null) {
                    return () -> already.accept(existing);
                }
                var path = save(header, bake.fileStream());
                return () -> {
                    refresh();
                    saved.accept(path);
                };
            } finally {
                bake.close();
            }
        }, failed));
    }

    private static void bake(Blueprint blueprint, Consumer<PreviewBake> then) {
        var bake = PreviewBake.start(Minecraft.getInstance(), blueprint);
        if (bake.done() && BAKES.isEmpty()) {
            then.accept(bake);
            return;
        }
        BAKES.add(new PendingBake(bake, then));
    }

    private record PendingBake(PreviewBake bake, Consumer<PreviewBake> then) {}

    @SubscribeEvent
    public static void onRenderTick(RenderFrameEvent.Pre event) {
        long deadline = System.nanoTime() + BAKE_BUDGET_NANOS;
        while (!BAKES.isEmpty() && System.nanoTime() < deadline) {
            var pending = BAKES.peek();
            pending.bake().advance(deadline);
            if (!pending.bake().done()) {
                return;
            }
            BAKES.poll();
            pending.then().accept(pending.bake());
        }
    }

    private static Path save(Blueprint.Header header, byte[] stream) throws IOException {
        var stem = BlueprintFiles.fileName(header.name());
        var folder = folder();
        var path = folder.resolve(stem + BlueprintFiles.EXTENSION);
        for (int copy = 2; Files.exists(path); copy++) {
            path = folder.resolve(stem + " (" + copy + ")" + BlueprintFiles.EXTENSION);
        }
        BlueprintFiles.write(path, stream);
        return path;
    }

    public static void toClipboard(Entry entry, Consumer<String> clipboard, Consumer<Component> copied,
                                   Consumer<Component> refused) {
        var load = loadAsync(entry);
        tasks.whenDone(load, () -> {
            var blueprint = load.blueprint();
            if (blueprint == null) {
                MatterManipulator.LOGGER.warn("Could not read the blueprint {}", entry.path(), load.failure());
                refused.accept(unreadable(entry, load.failure()));
                return;
            }
            var name = entry.header().name();
            tasks.run(() -> {
                var zipped = BlueprintFiles.zip(blueprint.stream());
                long length = CLIPBOARD_PREFIX.length() + 4L * ((zipped.length + 2) / 3);
                if (length > CLIPBOARD_CHARS) {
                    var refusal = MMMessage.BLUEPRINT_CLIPBOARD_COPY_TOO_LONG.text(name, count(length),
                            count(CLIPBOARD_CHARS));
                    return () -> refused.accept(refusal);
                }
                var text = CLIPBOARD_PREFIX + Base64.getEncoder().encodeToString(zipped);
                return () -> {
                    clipboard.accept(text);
                    copied.accept(MMMessage.BLUEPRINT_CLIPBOARD_COPIED.text(name, count(text.length())));
                };
            }, failure -> refused.accept(unreadable(entry, failure)));
        });
    }

    public static void fromClipboard(String text, Consumer<Component> saved, Consumer<Component> refused) {
        Consumer<IOException> failed = failure -> {
            MatterManipulator.LOGGER.warn("Could not save a pasted blueprint", failure);
            refused.accept(MMMessage.BLUEPRINT_FILE_FAILED.text(
                    failure.getMessage()));
        };
        tasks.runPastLogout(() -> {
            var refusals = new ArrayList<Component>(1);
            var blueprint = clipboardBlueprint(text, MMConfig.BLUEPRINT_BYTES_CEILING, MMConfig.REGION_VOLUME_CEILING,
                    refusals::add);
            if (blueprint == null) {
                return () -> refused.accept(refusals.get(0));
            }
            var header = blueprint.header();
            Consumer<Path> already = path -> saved.accept(alreadyInLibrary(header.name(),
                    Component.literal(path.getFileName().toString())));
            var existing = existing(folder(), header);
            if (existing != null) {
                return () -> already.accept(existing);
            }
            return () -> saveWithPreview(blueprint,
                    path -> saved.accept(MMMessage.BLUEPRINT_CLIPBOARD_SAVED.text(path.getFileName().toString())),
                    already, failed);
        }, failed);
    }

    @Nullable
    static byte[] clipboardStream(String text, int maxBytes, long maxVolume, Consumer<Component> refused) {
        var blueprint = clipboardBlueprint(text, maxBytes, maxVolume, refused);
        return blueprint == null ? null : blueprint.stream();
    }

    @Nullable
    private static Blueprint clipboardBlueprint(String text, int maxBytes, long maxVolume, Consumer<Component> refused) {
        var trimmed = text.strip();
        if (!trimmed.startsWith(CLIPBOARD_PREFIX)) {
            refused.accept(MMMessage.BLUEPRINT_CLIPBOARD_NOT_BLUEPRINT.text(CLIPBOARD_PREFIX));
            return null;
        }
        if (trimmed.length() > CLIPBOARD_CHARS) {
            refused.accept(MMMessage.BLUEPRINT_CLIPBOARD_TOO_LONG.text(count(trimmed.length()),
                    count(CLIPBOARD_CHARS)));
            return null;
        }
        byte[] stream;
        try {
            var base64 = WHITESPACE.matcher(trimmed.substring(CLIPBOARD_PREFIX.length())).replaceAll("");
            stream = BlueprintFiles.unzip(Base64.getDecoder().decode(base64), maxBytes);
        } catch (BlueprintFiles.TooLargeException exception) {
            refused.accept(MMMessage.BLUEPRINT_CLIPBOARD_TOO_LARGE.text(count(maxBytes)));
            return null;
        } catch (IllegalArgumentException | IOException exception) {
            refused.accept(MMMessage.BLUEPRINT_CLIPBOARD_CORRUPT.text(Objects.requireNonNullElse(exception.getMessage(),
                    exception.getClass().getSimpleName())));
            return null;
        }
        try {
            return Blueprint.decode(stream, maxBytes, maxVolume, Blueprint.CLIENT_DECODE_QUOTA);
        } catch (IOException exception) {
            refused.accept(MMMessage.BLUEPRINT_CLIPBOARD_UNREADABLE.text(exception.getMessage()));
            return null;
        }
    }

    public static void receive(BlueprintDownloadPacket packet) {
        int maxBytes = MMConfig.maxBlueprintBytes();
        if (packet.index() == 0) {
            download = packet.count() <= maxBytes / BlueprintDownloadPacket.CHUNK_BYTES + 1
                    ? new Download(packet.transferId(), packet.count())
                    : null;
        }
        var current = download;
        if (current == null || current.transferId != packet.transferId() || current.count != packet.count()
                || current.received != packet.index() || current.bytes.size() + packet.chunk().length > maxBytes) {
            download = null;
            return;
        }
        current.bytes.writeBytes(packet.chunk());
        if (++current.received < current.count) {
            return;
        }
        download = null;
        var stream = current.bytes.toByteArray();
        var maxVolume = MMConfig.MAX_REGION_VOLUME.get();
        Consumer<IOException> failed = failure -> {
            MatterManipulator.LOGGER.warn("Could not save a downloaded blueprint", failure);
            message(MMMessage.BLUEPRINT_FILE_FAILED.text(failure.getMessage())
                    .withStyle(ChatFormatting.RED));
        };
        tasks.runPastLogout(() -> {
            var blueprint = Blueprint.decode(stream, maxBytes, maxVolume, Blueprint.CLIENT_DECODE_QUOTA);
            var header = blueprint.header();
            Consumer<Path> already = path -> message(alreadyInLibrary(header.name(), fileLink(path)));
            var existing = existing(folder(), header);
            if (existing != null) {
                return () -> already.accept(existing);
            }
            return () -> saveWithPreview(blueprint,
                    path -> message(MMMessage.BLUEPRINT_FILE_SAVED.text(fileLink(path))), already, failed);
        }, failed);
    }

    private static Component fileLink(Path path) {
        var folder = folder().toAbsolutePath().toString();
        return Component.literal(path.getFileName().toString()).withStyle(style -> style
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenFile(folder)));
    }

    private static Component alreadyInLibrary(String name, Component file) {
        return MMMessage.BLUEPRINT_ALREADY_IN_LIBRARY.text(name, file);
    }

    public static void openFolder() {
        var folder = folder();
        try {
            Files.createDirectories(folder);
        } catch (IOException exception) {
            MatterManipulator.LOGGER.warn("Could not create the blueprint folder {}", folder, exception);
            return;
        }
        Util.getPlatform().openFile(folder.toFile());
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        advanceTick();
        var player = Minecraft.getInstance().player;
        var hand = ClientEvents.manipulatorHand();
        if (player != null && hand != null) {
            var key = ToolState.get(player.getItemInHand(hand)).blueprintRef()
                    .map(ref -> key(ref.hash(), ref.size()))
                    .orElse(null);
            MISSING.toolBlueprint(key);
            PAGE.toolBlueprint(key, ticks);
        }
    }

    private static String key(String hash, BlockPos size) {
        return hash + "-" + size.getX() + "x" + size.getY() + "x" + size.getZ();
    }

    static void advanceTick() {
        ticks++;
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        download = null;
        for (var pending = BAKES.poll(); pending != null; pending = BAKES.poll()) {
            pending.bake().abandon();
            pending.then().accept(pending.bake());
        }
        tasks.clear();
        previewLoad = null;
        PAGE.loading(null);
    }

    private static List<Entry> scan(Path folder) {
        var found = new ArrayList<Entry>();
        try (var files = Files.list(folder)) {
            for (var path : (Iterable<Path>) files::iterator) {
                if (!path.getFileName().toString().endsWith(BlueprintFiles.EXTENSION) || !Files.isRegularFile(path)) {
                    continue;
                }
                try {
                    found.add(new Entry(path, BlueprintFiles.readHeader(path),
                            Files.getLastModifiedTime(path).toMillis(), Files.size(path)));
                } catch (IOException exception) {
                    if (UNREADABLE.add(path)) {
                        MatterManipulator.LOGGER.warn("Skipping the unreadable blueprint {}", path, exception);
                    }
                }
            }
        } catch (IOException exception) {
            MatterManipulator.LOGGER.warn("Could not list the blueprint folder {}", folder, exception);
        }
        found.sort(Comparator.comparing((Entry entry) -> entry.header().name().toLowerCase(Locale.ROOT))
                .thenComparing(Entry::path));
        return List.copyOf(found);
    }

    private static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static void message(Component message) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.sendSystemMessage(message);
        }
    }

    private static final class Download {
        private final int transferId;
        private final int count;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int received;

        private Download(int transferId, int count) {
            this.transferId = transferId;
            this.count = count;
        }
    }
}
