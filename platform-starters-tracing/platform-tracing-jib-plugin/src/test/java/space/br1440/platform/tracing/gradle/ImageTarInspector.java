package space.br1440.platform.tracing.gradle;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

final class ImageTarInspector {

    private static final int TAR_BLOCK = 512;
    private static final String MANIFEST_ENTRY = "manifest.json";
    private static final Pattern LAYERS = Pattern.compile("\"Layers\"\\s*:\\s*\\[(.*?)]");
    private static final Pattern ENTRYPOINT = Pattern.compile("\"Entrypoint\"\\s*:\\s*\\[(.*?)]");
    private static final Pattern ENV = Pattern.compile("\"Env\"\\s*:\\s*\\[(.*?)]");
    private static final Pattern JSON_STRING = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    private final Map<String, byte[]> entries;

    private ImageTarInspector(Map<String, byte[]> entries) {
        this.entries = entries;
    }

    static ImageTarInspector read(Path imageTar) throws IOException {
        try (InputStream stream = Files.newInputStream(imageTar)) {
            return new ImageTarInspector(readTar(stream));
        }
    }

    List<String> entrypoint() {
        return jsonStrings(ENTRYPOINT, configJson());
    }

    List<String> environment() {
        return jsonStrings(ENV, configJson());
    }

    int layerCount() {
        return layers().size();
    }

    List<String> layers() {
        return jsonStrings(LAYERS, manifestJson());
    }

    String layerContaining(String absolutePath) throws IOException {
        String tarPath = absolutePath.startsWith("/") ? absolutePath.substring(1) : absolutePath;
        String found = null;
        for (String layerName : layers()) {
            byte[] gzip = entries.get(layerName);
            if (gzip == null) {
                throw new IllegalStateException("Manifest layer is missing from the tar: " + layerName);
            }

            try (InputStream layer = new GZIPInputStream(new ByteArrayInputStream(gzip))) {
                if (readTar(layer).containsKey(tarPath)) {
                    found = layerName;
                }
            }
        }

        if (found == null) {
            throw new IllegalStateException("No layer contains " + absolutePath);
        }

        return found;
    }

    Optional<byte[]> fileInLayers(String absolutePath) throws IOException {
        String tarPath = absolutePath.startsWith("/") ? absolutePath.substring(1) : absolutePath;
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (!entry.getKey().endsWith(".tar.gz")) {
                continue;
            }

            try (InputStream layer = new GZIPInputStream(new ByteArrayInputStream(entry.getValue()))) {
                byte[] content = readTar(layer).get(tarPath);
                if (content != null) {
                    return Optional.of(content);
                }
            }
        }

        return Optional.empty();
    }

    private String configJson() {
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String name = entry.getKey();
            if (name.endsWith(".json") && !MANIFEST_ENTRY.equals(name)) {
                return new String(entry.getValue(), StandardCharsets.UTF_8);
            }
        }

        throw new IllegalStateException("Image tar has no config json; entries: " + entries.keySet());
    }

    private String manifestJson() {
        byte[] manifest = entries.get(MANIFEST_ENTRY);
        if (manifest == null) {
            throw new IllegalStateException("Image tar has no manifest.json; entries: " + entries.keySet());
        }

        return new String(manifest, StandardCharsets.UTF_8);
    }

    private static List<String> jsonStrings(Pattern array, String json) {
        Matcher matcher = array.matcher(json);
        if (!matcher.find()) {
            return List.of();
        }

        List<String> values = new ArrayList<>();
        Matcher strings = JSON_STRING.matcher(matcher.group(1));
        while (strings.find()) {
            values.add(strings.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
        }

        return List.copyOf(values);
    }

    private static Map<String, byte[]> readTar(InputStream stream) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        byte[] header = new byte[TAR_BLOCK];
        while (readFully(stream, header) && header[0] != 0) {
            String name = asciiField(header, 0, 100);
            String prefix = asciiField(header, 345, 155);
            long size = Long.parseLong(asciiField(header, 124, 12).trim(), 8);
            byte type = header[156];

            byte[] content = stream.readNBytes(Math.toIntExact(size));
            long padding = (TAR_BLOCK - size % TAR_BLOCK) % TAR_BLOCK;
            stream.skipNBytes(padding);

            if (type == '0' || type == 0) {
                result.put(prefix.isEmpty() ? name : prefix + "/" + name, content);
            }
        }

        return result;
    }

    private static boolean readFully(InputStream stream, byte[] buffer) throws IOException {
        int read = stream.readNBytes(buffer, 0, buffer.length);
        return (read == buffer.length);
    }

    private static String asciiField(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }

        return new String(header, offset, end - offset, StandardCharsets.US_ASCII);
    }
}
