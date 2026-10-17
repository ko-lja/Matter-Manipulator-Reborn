package lu.kolja.matter_manipulator.common.blueprint;

import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.common.config.MMConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

public final class BlueprintWorldStore {

    private static final LevelResource FOLDER = new LevelResource("matter_manipulator/blueprints");

    private BlueprintWorldStore() {}

    public static void put(MinecraftServer server, Blueprint blueprint) throws IOException {
        var header = blueprint.header();
        var file = path(server, new BlueprintRef(header.hash(), header.name(), header.size()));
        put(file, blueprint, MMConfig.maxBlueprintBytes(), MMConfig.MAX_REGION_VOLUME.get(), () -> false);
    }

    public static void put(Path file, Blueprint blueprint, int maxBytes, long maxVolume, BooleanSupplier dropped)
            throws IOException {
        var header = blueprint.header();
        if (Files.exists(file)) {
            try {
                read(file, new BlueprintRef(header.hash(), header.name(), header.size()), maxBytes, maxVolume);
                return;
            } catch (IOException exception) {
                MatterManipulator.LOGGER.warn("Replacing {}, which does not hold its blueprint", file, exception);
            }
        }
        if (!dropped.getAsBoolean()) {
            BlueprintFiles.write(file, blueprint.stream());
        }
    }

    public static byte[] read(MinecraftServer server, BlueprintRef ref) throws IOException {
        return read(path(server, ref), ref, MMConfig.maxBlueprintBytes(), MMConfig.MAX_REGION_VOLUME.get());
    }

    public static byte[] read(Path file, BlueprintRef ref, int maxBytes, long maxVolume) throws IOException {
        var blueprint = Blueprint.decode(BlueprintFiles.read(file, maxBytes), maxBytes, maxVolume);
        var stored = blueprint.header();
        if (!stored.hash().equals(ref.hash()) || !stored.size().equals(ref.size())) {
            throw new IOException("The file for blueprint " + ref.hash().substring(0, 8) + " " + size(ref.size())
                    + " holds blueprint " + stored.hash().substring(0, 8) + " " + size(stored.size()));
        }
        return blueprint.stream();
    }

    public static Path folder(MinecraftServer server) {
        return server.getWorldPath(FOLDER);
    }

    public static Path path(MinecraftServer server, BlueprintRef ref) {
        var size = ref.size();
        if (!BlueprintRef.isHash(ref.hash()) || size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {
            throw new IllegalArgumentException("Not a stored blueprint: " + ref.hash() + " " + size(size));
        }
        return folder(server).resolve(ref.hash() + "-" + size(size) + BlueprintFiles.EXTENSION);
    }

    private static String size(BlockPos size) {
        return size.getX() + "x" + size.getY() + "x" + size.getZ();
    }
}
