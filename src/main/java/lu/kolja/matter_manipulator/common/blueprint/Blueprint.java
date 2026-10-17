package lu.kolja.matter_manipulator.common.blueprint;

import lu.kolja.matter_manipulator.common.state.CapturedBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.TreeSet;

public final class Blueprint {

    public static final int FORMAT = 2;
    public static final int LEGACY_FORMAT = 1;
    private static final int LEGACY_DATA_VERSION = 3465;
    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_PALETTE = 65_536;

    private static final long HEADER_QUOTA = 1 << 20;
    public static final long BODY_QUOTA_PER_BYTE = 16;
    public static final long CLIENT_DECODE_QUOTA = 128L << 20;
    private static final long BODY_QUOTA_FLOOR = 64 << 10;
    private static final long BODY_QUOTA_PER_BODY_BYTE = 32;
    public static final int PREVIEW_BYTES_CAP = 16 << 20;
    private static final int MAX_NBT_DEPTH = 512;

    private static final String TAG_FORMAT = "format";
    private static final String TAG_NAME = "name";
    private static final String TAG_SIZE = "size";
    private static final String TAG_HASH = "hash";
    private static final String TAG_MODS = "mods";
    private static final String TAG_PALETTE = "palette";
    private static final String TAG_BLOCKS = "blocks";
    private static final String TAG_PREVIEW = "preview";

    private final Header header;
    private final List<@Nullable CapturedBlock> palette;
    private final int[] blocks;
    private final byte[] body;
    @Nullable
    private final byte[] preview;

    private Blueprint(Header header, List<@Nullable CapturedBlock> palette, int[] blocks, byte[] body,
            @Nullable byte[] preview) {
        this.header = header;
        this.palette = Collections.unmodifiableList(palette);
        this.blocks = blocks;
        this.body = body;
        this.preview = preview;
    }

    public static Blueprint encode(String name, BlockPos size, @Nullable CapturedBlock[] dense) {
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Blueprint name is longer than " + MAX_NAME_LENGTH + " characters");
        }
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0
                || Math.multiplyExact(Math.multiplyExact((long) size.getX(), size.getY()), size.getZ()) != dense.length) {
            throw new IllegalArgumentException("Blueprint size " + size.toShortString() + " does not hold "
                    + dense.length + " positions");
        }
        var indices = new IdentityHashMap<CapturedBlock, Integer>();
        var palette = new ArrayList<CapturedBlock>();
        var blocks = new int[dense.length];
        for (int i = 0; i < dense.length; i++) {
            var capture = dense[i];
            if (capture == null) {
                continue;
            }
            var index = indices.get(capture);
            if (index == null) {
                if (capture.hasBlockEntity()) {
                    throw new IllegalArgumentException("A blueprint never carries block entity data");
                }
                if (palette.size() == MAX_PALETTE) {
                    throw new IllegalArgumentException("A blueprint holds at most " + MAX_PALETTE + " distinct blocks");
                }
                palette.add(capture);
                index = palette.size();
                indices.put(capture, index);
            }
            blocks[i] = index;
        }
        var paletteTag = new ListTag();
        for (var capture : palette) {
            paletteTag.add(capture.save());
        }
        var bodyTag = new CompoundTag();
        bodyTag.put(TAG_PALETTE, paletteTag);
        bodyTag.putIntArray(TAG_BLOCKS, blocks);
        var body = write(bodyTag);
        var header = new Header(FORMAT, name, size, hash(size, body), mods(palette));
        return new Blueprint(header, palette, blocks, body, null);
    }

    public static Blueprint decode(byte[] stream, int maxBytes, long maxVolume) throws IOException {
        return decode(stream, maxBytes, maxVolume, BODY_QUOTA_PER_BYTE * maxBytes);
    }

    public static Blueprint decode(byte[] stream, int maxBytes, long maxVolume, long bodyQuota) throws IOException {
        if (stream.length > (long) maxBytes + PREVIEW_BYTES_CAP) {
            throw new IOException("Blueprint is " + stream.length + " bytes; the limit is " + maxBytes
                    + " for its header and body and " + PREVIEW_BYTES_CAP + " for a preview");
        }
        var input = new ByteArrayInputStream(stream);
        var header = readHeader(new DataInputStream(input));
        var size = header.size();
        long limit = Math.min(maxVolume, Integer.MAX_VALUE);
        long area = (long) size.getX() * size.getY();
        if (area > limit || area * size.getZ() > limit) {
            throw new IOException("Blueprint size " + size.toShortString() + " exceeds the limit of " + maxVolume
                    + " blocks");
        }
        int volume = (int) (area * size.getZ());
        int bodyStart = stream.length - input.available();
        int bodyEnd = bodyEnd(stream, bodyStart);
        if (bodyEnd > maxBytes) {
            throw new IOException("Blueprint is " + bodyEnd + " bytes; the limit is " + maxBytes);
        }
        var body = Arrays.copyOfRange(stream, bodyStart, bodyEnd);
        if (!hash(size, body).equals(header.hash())) {
            throw new IOException("Blueprint body does not match its hash");
        }
        var preview = bodyEnd < stream.length ? readPreview(stream, bodyEnd) : null;
        var bodyInput = new ByteArrayInputStream(body);
        long quota = Math.min(bodyQuota, BODY_QUOTA_FLOOR + BODY_QUOTA_PER_BODY_BYTE * body.length);
        var bodyTag = read(new DataInputStream(bodyInput), NbtAccounter.create(quota));
        if (bodyInput.available() > 0) {
            throw new IOException("Blueprint has data after its body");
        }
        if (!bodyTag.contains(TAG_BLOCKS, Tag.TAG_INT_ARRAY)) {
            throw new IOException("Blueprint has no block list");
        }
        var blocks = bodyTag.getIntArray(TAG_BLOCKS);
        if (blocks.length != volume) {
            throw new IOException("Blueprint holds " + blocks.length + " positions for a size of "
                    + size.toShortString());
        }
        var paletteTag = bodyTag.getList(TAG_PALETTE, Tag.TAG_COMPOUND);
        if (paletteTag.size() > MAX_PALETTE) {
            throw new IOException("Blueprint palette holds " + paletteTag.size() + " entries; the limit is "
                    + MAX_PALETTE);
        }
        boolean legacy = header.format() == LEGACY_FORMAT;
        var palette = new CapturedBlock[paletteTag.size()];
        for (int i = palette.length - 1; i >= 0; i--) {
            var entry = CapturedBlock.fromTag(paletteTag.getCompound(i)).orElse(null);
            paletteTag.remove(i);
            if (entry != null && entry.hasBlockEntity()) {
                throw new IOException("Blueprint palette entry " + i + " carries block entity data");
            }
            if (entry != null && legacy) {
                if (entry.hasCompat()) {
                    throw new IOException("Blueprint palette entry " + i + " was saved on Minecraft 1.20.1 with mod data"
                            + " for " + String.join(", ", entry.compat().getAllKeys()) + ", which cannot be upgraded");
                }
                entry = new CapturedBlock(entry.state().upgraded(LEGACY_DATA_VERSION), new CompoundTag(), new CompoundTag());
            }
            palette[i] = entry;
        }
        for (int index : blocks) {
            if (index < 0 || index > palette.length) {
                throw new IOException("Blueprint block index " + index + " is outside its palette of "
                        + palette.length);
            }
        }
        return new Blueprint(header, Arrays.asList(palette), blocks, body, preview);
    }

    private static int bodyEnd(byte[] stream, int start) {
        var buffer = ByteBuffer.wrap(stream).position(start);
        try {
            if (buffer.get() != Tag.TAG_COMPOUND) {
                return stream.length;
            }
            skip(buffer, buffer.getShort() & 0xFFFF);
            skipPayload(buffer, Tag.TAG_COMPOUND, 0);
        } catch (BufferUnderflowException | IOException exception) {
            return stream.length;
        }
        return buffer.position();
    }

    private static void skipPayload(ByteBuffer buffer, byte type, int depth) throws IOException {
        if ((type == Tag.TAG_LIST || type == Tag.TAG_COMPOUND) && depth >= MAX_NBT_DEPTH) {
            throw new IOException("Malformed blueprint: tags nested deeper than " + MAX_NBT_DEPTH);
        }
        switch (type) {
            case Tag.TAG_BYTE -> skip(buffer, 1);
            case Tag.TAG_SHORT -> skip(buffer, 2);
            case Tag.TAG_INT, Tag.TAG_FLOAT -> skip(buffer, 4);
            case Tag.TAG_LONG, Tag.TAG_DOUBLE -> skip(buffer, 8);
            case Tag.TAG_BYTE_ARRAY -> skip(buffer, length(buffer));
            case Tag.TAG_INT_ARRAY -> skip(buffer, 4L * length(buffer));
            case Tag.TAG_LONG_ARRAY -> skip(buffer, 8L * length(buffer));
            case Tag.TAG_STRING -> skip(buffer, buffer.getShort() & 0xFFFF);
            case Tag.TAG_LIST -> {
                byte elementType = buffer.get();
                int count = length(buffer);
                for (int i = 0; i < count; i++) {
                    skipPayload(buffer, elementType, depth + 1);
                }
            }
            case Tag.TAG_COMPOUND -> {
                for (byte entryType = buffer.get(); entryType != Tag.TAG_END; entryType = buffer.get()) {
                    skip(buffer, buffer.getShort() & 0xFFFF);
                    skipPayload(buffer, entryType, depth + 1);
                }
            }
            default -> throw new IOException("Malformed blueprint: unknown tag type " + type);
        }
    }

    private static int length(ByteBuffer buffer) throws IOException {
        int length = buffer.getInt();
        if (length < 0) {
            throw new IOException("Malformed blueprint: negative length " + length);
        }
        return length;
    }

    private static void skip(ByteBuffer buffer, long bytes) {
        if (bytes > buffer.remaining()) {
            throw new BufferUnderflowException();
        }
        buffer.position(buffer.position() + (int) bytes);
    }

    private static byte[] readPreview(byte[] stream, int start) throws IOException {
        int length = stream.length - start;
        if (length > PREVIEW_BYTES_CAP) {
            throw new IOException("Blueprint preview is " + length + " bytes; the limit is " + PREVIEW_BYTES_CAP);
        }
        var buffer = ByteBuffer.wrap(stream).position(start);
        int format = 0;
        try {
            if (buffer.get() != Tag.TAG_COMPOUND) {
                throw new IOException("Blueprint has data after its body that is not a preview");
            }
            var name = string(buffer);
            if (!name.equals(TAG_PREVIEW)) {
                throw new IOException("Blueprint has a section \"" + name + "\" after its body");
            }
            for (byte type = buffer.get(); type != Tag.TAG_END; type = buffer.get()) {
                var key = string(buffer);
                if (type == Tag.TAG_INT && key.equals(TAG_FORMAT)) {
                    format = buffer.getInt();
                } else {
                    skipPayload(buffer, type, 1);
                }
            }
        } catch (BufferUnderflowException exception) {
            throw new IOException("Blueprint preview ends early", exception);
        }
        if (buffer.hasRemaining()) {
            throw new IOException("Blueprint has data after its preview");
        }
        if (format != FORMAT && format != LEGACY_FORMAT) {
            throw new IOException("Unsupported blueprint preview format " + format);
        }
        return Arrays.copyOfRange(stream, start, stream.length);
    }

    private static String string(ByteBuffer buffer) {
        var bytes = new byte[buffer.getShort() & 0xFFFF];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static byte[] previewSection(CompoundTag preview) {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeByte(Tag.TAG_COMPOUND);
            output.writeUTF(TAG_PREVIEW);
            preview.write(output);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return bytes.toByteArray();
    }

    public static Header readHeader(DataInput input) throws IOException {
        var tag = read(input, NbtAccounter.create(HEADER_QUOTA));
        int format = tag.getInt(TAG_FORMAT);
        if (format != FORMAT && format != LEGACY_FORMAT) {
            throw new IOException("Unsupported blueprint format " + format);
        }
        var name = tag.getString(TAG_NAME);
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IOException("Blueprint name is longer than " + MAX_NAME_LENGTH + " characters");
        }
        var sizeTag = tag.getCompound(TAG_SIZE);
        var size = new BlockPos(sizeTag.getInt("x"), sizeTag.getInt("y"), sizeTag.getInt("z"));
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {
            throw new IOException("Blueprint size " + size.toShortString() + " is empty");
        }
        var hash = tag.getString(TAG_HASH);
        if (!BlueprintRef.isHash(hash)) {
            throw new IOException("Blueprint header has no valid hash");
        }
        var modsTag = tag.getList(TAG_MODS, Tag.TAG_STRING);
        var mods = new ArrayList<String>(modsTag.size());
        for (int i = 0; i < modsTag.size(); i++) {
            mods.add(modsTag.getString(i));
        }
        return new Header(format, name, size, hash, mods);
    }

    public static byte[] renamed(byte[] stream, String name) throws IOException {
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Blueprint name is longer than " + MAX_NAME_LENGTH + " characters");
        }
        var input = new ByteArrayInputStream(stream);
        var header = readHeader(new DataInputStream(input));
        var headerBytes = write(new Header(header.format(), name, header.size(), header.hash(), header.mods()).save());
        int bodyLength = input.available();
        var renamed = Arrays.copyOf(headerBytes, headerBytes.length + bodyLength);
        System.arraycopy(stream, stream.length - bodyLength, renamed, headerBytes.length, bodyLength);
        return renamed;
    }

    private static CompoundTag read(DataInput input, NbtAccounter accounter) throws IOException {
        try {
            return NbtIo.read(input, accounter);
        } catch (RuntimeException exception) {
            var cause = exception.getCause() != null ? exception.getCause() : exception;
            throw new IOException("Malformed blueprint: " + cause.getMessage(), exception);
        }
    }

    private static byte[] write(CompoundTag tag) {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            NbtIo.write(tag, output);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return bytes.toByteArray();
    }

    private static List<String> mods(List<CapturedBlock> palette) {
        var mods = new TreeSet<String>();
        for (var capture : palette) {
            mods.add(capture.state().blockId().getNamespace());
            for (var key : capture.compat().getAllKeys()) {
                var id = ResourceLocation.tryParse(key);
                if (id != null) {
                    mods.add(id.getNamespace());
                }
            }
        }
        mods.remove(ResourceLocation.DEFAULT_NAMESPACE);
        return List.copyOf(mods);
    }

    public static String hash(BlockPos size, byte[] body) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Every Java runtime provides SHA-256", exception);
        }
        digest.update(ByteBuffer.allocate(12).putInt(size.getX()).putInt(size.getY()).putInt(size.getZ()).array());
        return HexFormat.of().formatHex(digest.digest(body));
    }

    public Header header() {
        return header;
    }

    public List<@Nullable CapturedBlock> palette() {
        return palette;
    }

    public int volume() {
        return blocks.length;
    }

    public boolean isEmpty(int index) {
        return blocks[index] == 0;
    }

    @Nullable
    public CapturedBlock at(int index) {
        int entry = blocks[index];
        return entry == 0 ? null : palette.get(entry - 1);
    }

    public byte[] body() {
        return body.clone();
    }

    public byte[] stream() {
        var headerBytes = write(header.save());
        var stream = Arrays.copyOf(headerBytes, headerBytes.length + body.length);
        System.arraycopy(body, 0, stream, headerBytes.length, body.length);
        return stream;
    }

    public byte[] fileStream() {
        var stream = stream();
        if (preview == null) {
            return stream;
        }
        var file = Arrays.copyOf(stream, stream.length + preview.length);
        System.arraycopy(preview, 0, file, stream.length, preview.length);
        return file;
    }

    public boolean hasPreview() {
        return preview != null;
    }

    @Nullable
    public byte[] preview() {
        return preview == null ? null : preview.clone();
    }

    public Blueprint withPreview(@Nullable byte[] section) {
        return new Blueprint(header, palette, blocks, body, section == null ? null : section.clone());
    }

    public record Header(int format, String name, BlockPos size, String hash, List<String> mods) {

        public Header {
            size = size.immutable();
            mods = List.copyOf(mods);
        }

        public boolean matches(String hash, BlockPos size) {
            return this.hash.equals(hash) && this.size.equals(size);
        }

        private CompoundTag save() {
            var tag = new CompoundTag();
            tag.putInt(TAG_FORMAT, format);
            tag.putString(TAG_NAME, name);
            var sizeTag = new CompoundTag();
            sizeTag.putInt("x", size.getX());
            sizeTag.putInt("y", size.getY());
            sizeTag.putInt("z", size.getZ());
            tag.put(TAG_SIZE, sizeTag);
            tag.putString(TAG_HASH, hash);
            var modsTag = new ListTag();
            for (var mod : mods) {
                modsTag.add(StringTag.valueOf(mod));
            }
            tag.put(TAG_MODS, modsTag);
            return tag;
        }
    }
}
