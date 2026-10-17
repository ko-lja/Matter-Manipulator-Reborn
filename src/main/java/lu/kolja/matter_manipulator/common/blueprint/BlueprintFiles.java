package lu.kolja.matter_manipulator.common.blueprint;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class BlueprintFiles {

    public static final String EXTENSION = ".mmbp";

    private static final int MAX_FILE_NAME = 64;
    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9 _.-]");
    private static final Set<String> RESERVED = Set.of("CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private BlueprintFiles() {}

    public static void write(Path path, byte[] stream) throws IOException {
        var directory = path.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        var temp = Files.createTempFile(directory, path.getFileName().toString(), ".tmp");
        try {
            try (var output = new GZIPOutputStream(Files.newOutputStream(temp))) {
                output.write(stream);
            }
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public static byte[] read(Path path, int maxBytes) throws IOException {
        return unzip(Files.newInputStream(path), maxBytes, path.getFileName().toString());
    }

    public static byte[] zip(byte[] stream) {
        var bytes = new ByteArrayOutputStream();
        try (var output = new GZIPOutputStream(bytes)) {
            output.write(stream);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return bytes.toByteArray();
    }

    public static byte[] unzip(byte[] zipped, int maxBytes) throws IOException {
        return unzip(new ByteArrayInputStream(zipped), maxBytes, "The data");
    }

    private static byte[] unzip(InputStream zipped, int maxBytes, String source) throws IOException {
        try (var input = new GZIPInputStream(zipped)) {
            var stream = input.readNBytes(maxBytes);
            if (input.read() != -1) {
                throw new TooLargeException(source + " holds more than " + maxBytes + " bytes");
            }
            return stream;
        }
    }

    public static Blueprint.Header readHeader(Path path) throws IOException {
        try (var input = new DataInputStream(new GZIPInputStream(Files.newInputStream(path)))) {
            return Blueprint.readHeader(input);
        }
    }

    public static String fileName(String name) {
        var safe = UNSAFE.matcher(name).replaceAll("_").trim();
        safe = safe.substring(0, Math.min(safe.length(), MAX_FILE_NAME)).trim();
        if (RESERVED.contains(safe.split("\\.", 2)[0].trim().toUpperCase(Locale.ROOT))) {
            safe = "_" + safe.substring(0, Math.min(safe.length(), MAX_FILE_NAME - 1));
        }
        return safe.isEmpty() ? "blueprint" : safe;
    }

    public static final class TooLargeException extends IOException {
        private TooLargeException(String message) {
            super(message);
        }
    }
}
