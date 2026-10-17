package lu.kolja.matter_manipulator.client.blueprint;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class GlbWriter {

    static final int MAX_QUANTIZED = 65_535;

    private static final int STRIDE = 16;
    private static final int GLB_MAGIC = 0x46546C67;
    private static final int CHUNK_JSON = 0x4E4F534A;
    private static final int CHUNK_BIN = 0x004E4942;
    private static final int UNSIGNED_BYTE = 5121;
    private static final int UNSIGNED_SHORT = 5123;
    private static final int UNSIGNED_INT = 5125;
    private static final int ARRAY_BUFFER = 34962;
    private static final int ELEMENT_ARRAY_BUFFER = 34963;
    private static final int NEAREST = 9728;
    private static final int CLAMP_TO_EDGE = 33071;

    private GlbWriter() {}

    record Mesh(String name, boolean blend, int quads, float[] positions, float[] uvs, byte[] colors, int[] blockY) {}

    private static double quantize(float blocks) {
        return Math.floor(blocks * 16.0 + 0.5);
    }

    static boolean fits(float min, float max) {
        return quantize(max) - quantize(min) <= MAX_QUANTIZED;
    }

    static byte[] write(String generator, int sizeY, List<Mesh> meshes, byte[] png) {
        var drawn = meshes.stream().filter(mesh -> mesh.quads() > 0).toList();
        var low = new float[] {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
        var high = new float[] {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (var mesh : drawn) {
            for (int i = 0; i < mesh.quads() * 12; i++) {
                low[i % 3] = Math.min(low[i % 3], mesh.positions()[i]);
                high[i % 3] = Math.max(high[i % 3], mesh.positions()[i]);
            }
        }
        var min = new double[3];
        for (int axis = 0; axis < 3 && !drawn.isEmpty(); axis++) {
            if (!fits(low[axis], high[axis])) {
                throw new IllegalArgumentException("Preview spans more than " + MAX_QUANTIZED
                        + " sixteenths of a block, or has a position that is not a number");
            }
            min[axis] = quantize(low[axis]);
        }

        var json = new JsonObject();
        var asset = new JsonObject();
        asset.addProperty("version", "2.0");
        asset.addProperty("generator", generator);
        json.add("asset", asset);
        var scene = new JsonObject();
        json.add("scenes", array(scene));
        json.addProperty("scene", 0);
        if (drawn.isEmpty()) {
            return glb(json, new byte[0]);
        }
        var sceneNodes = new JsonArray();
        scene.add("nodes", sceneNodes);
        json.add("extensionsUsed", strings("KHR_mesh_quantization", "KHR_materials_unlit"));
        json.add("extensionsRequired", strings("KHR_mesh_quantization"));

        var bin = new ByteArrayOutputStream();
        var nodes = new JsonArray();
        var jsonMeshes = new JsonArray();
        var materials = new JsonArray();
        var accessors = new JsonArray();
        var bufferViews = new JsonArray();
        for (var mesh : drawn) {
            var order = byBlockY(mesh, sizeY);
            var vertices = ByteBuffer.allocate(mesh.quads() * 4 * STRIDE).order(ByteOrder.LITTLE_ENDIAN);
            var meshMin = new int[] {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
            var max = new int[3];
            for (int quad : order) {
                for (int vertex = quad * 4; vertex < quad * 4 + 4; vertex++) {
                    for (int axis = 0; axis < 3; axis++) {
                        int value = (int) (quantize(mesh.positions()[vertex * 3 + axis]) - min[axis]);
                        meshMin[axis] = Math.min(meshMin[axis], value);
                        max[axis] = Math.max(max[axis], value);
                        vertices.putShort((short) value);
                    }
                    vertices.putShort((short) 0);
                    vertices.putShort(unorm16(mesh.uvs()[vertex * 2]));
                    vertices.putShort(unorm16(mesh.uvs()[vertex * 2 + 1]));
                    vertices.put(mesh.colors(), vertex * 4, 4);
                }
            }
            var indices = ByteBuffer.allocate(mesh.quads() * 6 * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int quad = 0; quad < mesh.quads(); quad++) {
                int first = quad * 4;
                indices.putInt(first).putInt(first + 1).putInt(first + 2)
                        .putInt(first).putInt(first + 2).putInt(first + 3);
            }

            int vertexView = bufferView(bufferViews, bin, vertices.array());
            bufferViews.get(vertexView).getAsJsonObject().addProperty("byteStride", STRIDE);
            bufferViews.get(vertexView).getAsJsonObject().addProperty("target", ARRAY_BUFFER);
            int indexView = bufferView(bufferViews, bin, indices.array());
            bufferViews.get(indexView).getAsJsonObject().addProperty("target", ELEMENT_ARRAY_BUFFER);

            int vertexCount = mesh.quads() * 4;
            int position = accessor(accessors, vertexView, 0, UNSIGNED_SHORT, false, vertexCount, "VEC3");
            var positionAccessor = accessors.get(position).getAsJsonObject();
            positionAccessor.add("min", ints(meshMin[0], meshMin[1], meshMin[2]));
            positionAccessor.add("max", ints(max[0], max[1], max[2]));
            var attributes = new JsonObject();
            attributes.addProperty("POSITION", position);
            attributes.addProperty("TEXCOORD_0", accessor(accessors, vertexView, 8, UNSIGNED_SHORT, true, vertexCount,
                    "VEC2"));
            attributes.addProperty("COLOR_0", accessor(accessors, vertexView, 12, UNSIGNED_BYTE, true, vertexCount,
                    "VEC4"));
            var primitive = new JsonObject();
            primitive.add("attributes", attributes);
            primitive.addProperty("indices", accessor(accessors, indexView, 0, UNSIGNED_INT, false,
                    mesh.quads() * 6, "SCALAR"));
            primitive.addProperty("material", materials.size());
            primitive.addProperty("mode", 4);
            materials.add(material(mesh));

            var offsets = new JsonArray();
            int below = 0;
            int next = 0;
            for (int y = 0; y <= sizeY; y++) {
                while (next < order.length && layer(mesh, order[next], sizeY) < y) {
                    below++;
                    next++;
                }
                offsets.add(below * 6);
            }
            var extras = new JsonObject();
            extras.add("yOffsets", offsets);
            var jsonMesh = new JsonObject();
            jsonMesh.addProperty("name", mesh.name());
            jsonMesh.add("primitives", array(primitive));
            jsonMesh.add("extras", extras);

            var node = new JsonObject();
            node.addProperty("name", mesh.name());
            node.addProperty("mesh", jsonMeshes.size());
            node.add("translation", floats((float) (min[0] / 16), (float) (min[1] / 16), (float) (min[2] / 16)));
            node.add("scale", floats(1 / 16.0F, 1 / 16.0F, 1 / 16.0F));
            sceneNodes.add(nodes.size());
            nodes.add(node);
            jsonMeshes.add(jsonMesh);
        }

        var image = new JsonObject();
        image.addProperty("bufferView", bufferView(bufferViews, bin, png));
        image.addProperty("mimeType", "image/png");
        var sampler = new JsonObject();
        sampler.addProperty("magFilter", NEAREST);
        sampler.addProperty("minFilter", NEAREST);
        sampler.addProperty("wrapS", CLAMP_TO_EDGE);
        sampler.addProperty("wrapT", CLAMP_TO_EDGE);
        var texture = new JsonObject();
        texture.addProperty("sampler", 0);
        texture.addProperty("source", 0);

        var buffer = new JsonObject();
        buffer.addProperty("byteLength", bin.size());
        json.add("nodes", nodes);
        json.add("meshes", jsonMeshes);
        json.add("materials", materials);
        json.add("textures", array(texture));
        json.add("samplers", array(sampler));
        json.add("images", array(image));
        json.add("accessors", accessors);
        json.add("bufferViews", bufferViews);
        json.add("buffers", array(buffer));
        return glb(json, bin.toByteArray());
    }

    private static int[] byBlockY(Mesh mesh, int sizeY) {
        var counts = new int[sizeY + 1];
        for (int quad = 0; quad < mesh.quads(); quad++) {
            counts[layer(mesh, quad, sizeY) + 1]++;
        }
        for (int y = 0; y < sizeY; y++) {
            counts[y + 1] += counts[y];
        }
        var order = new int[mesh.quads()];
        for (int quad = 0; quad < mesh.quads(); quad++) {
            order[counts[layer(mesh, quad, sizeY)]++] = quad;
        }
        return order;
    }

    private static int layer(Mesh mesh, int quad, int sizeY) {
        return Math.max(0, Math.min(sizeY - 1, mesh.blockY()[quad]));
    }

    private static short unorm16(float value) {
        return (short) Math.round(Math.max(0.0F, Math.min(1.0F, value)) * 65_535.0F);
    }

    private static JsonObject material(Mesh mesh) {
        var baseColor = new JsonObject();
        baseColor.addProperty("index", 0);
        var pbr = new JsonObject();
        pbr.add("baseColorTexture", baseColor);
        pbr.addProperty("metallicFactor", 0);
        pbr.addProperty("roughnessFactor", 1);
        var extensions = new JsonObject();
        extensions.add("KHR_materials_unlit", new JsonObject());
        var material = new JsonObject();
        material.addProperty("name", mesh.name());
        material.add("pbrMetallicRoughness", pbr);
        if (mesh.blend()) {
            material.addProperty("alphaMode", "BLEND");
        } else {
            material.addProperty("alphaMode", "MASK");
            material.addProperty("alphaCutoff", 0.1);
        }
        material.addProperty("doubleSided", false);
        material.add("extensions", extensions);
        return material;
    }

    private static int bufferView(JsonArray bufferViews, ByteArrayOutputStream bin, byte[] data) {
        var view = new JsonObject();
        view.addProperty("buffer", 0);
        view.addProperty("byteOffset", bin.size());
        view.addProperty("byteLength", data.length);
        bin.writeBytes(data);
        while (bin.size() % 4 != 0) {
            bin.write(0);
        }
        bufferViews.add(view);
        return bufferViews.size() - 1;
    }

    private static int accessor(JsonArray accessors, int bufferView, int byteOffset, int componentType,
            boolean normalized, int count, String type) {
        var accessor = new JsonObject();
        accessor.addProperty("bufferView", bufferView);
        accessor.addProperty("byteOffset", byteOffset);
        accessor.addProperty("componentType", componentType);
        if (normalized) {
            accessor.addProperty("normalized", true);
        }
        accessor.addProperty("count", count);
        accessor.addProperty("type", type);
        accessors.add(accessor);
        return accessors.size() - 1;
    }

    private static byte[] glb(JsonObject json, byte[] bin) {
        var jsonBytes = json.toString().getBytes(StandardCharsets.UTF_8);
        int jsonLength = align4(jsonBytes.length);
        int binLength = align4(bin.length);
        int total = 12 + 8 + jsonLength + (bin.length > 0 ? 8 + binLength : 0);
        var glb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        glb.putInt(GLB_MAGIC).putInt(2).putInt(total);
        glb.putInt(jsonLength).putInt(CHUNK_JSON).put(jsonBytes);
        for (int i = jsonBytes.length; i < jsonLength; i++) {
            glb.put((byte) ' ');
        }
        if (bin.length > 0) {
            glb.putInt(binLength).putInt(CHUNK_BIN).put(bin);
        }
        return glb.array();
    }

    private static int align4(int length) {
        return (length + 3) & ~3;
    }

    private static JsonArray array(JsonObject element) {
        var array = new JsonArray();
        array.add(element);
        return array;
    }

    private static JsonArray strings(String... values) {
        var array = new JsonArray();
        for (var value : values) {
            array.add(value);
        }
        return array;
    }

    private static JsonArray ints(int... values) {
        var array = new JsonArray();
        for (int value : values) {
            array.add(value);
        }
        return array;
    }

    private static JsonArray floats(float... values) {
        var array = new JsonArray();
        for (float value : values) {
            array.add(value);
        }
        return array;
    }
}
