package space.br1440.platform.tracing.otel.javaagent.logging;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Алгоритм {@code PLATFORM_LOG_SCHEMA_SET_SHA256_V1}.
 */
final class PlatformLogSchemaSetHash {

    static final String ALGORITHM_ID = "PLATFORM_LOG_SCHEMA_SET_SHA256_V1";
    static final List<String> FILE_NAMES = List.of(
            "platform-log-event-v1.schema.json"
    );

    private PlatformLogSchemaSetHash() {
    }

    static byte[] normalize(byte[] raw) {
        int offset = 0;
        if (raw.length >= 3
                && (raw[0] & 0xFF) == 0xEF
                && (raw[1] & 0xFF) == 0xBB
                && (raw[2] & 0xFF) == 0xBF) {
            offset = 3;
        }
        String text = new String(raw, offset, raw.length - offset, StandardCharsets.UTF_8);
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
        return normalized.getBytes(StandardCharsets.UTF_8);
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    static Map<String, byte[]> readNormalizedFromClasspath(Class<?> owner) throws IOException {
        return readNormalizedFromClasspath(owner, FILE_NAMES, "");
    }

    static Map<String, byte[]> readNormalizedFromClasspath(
            Class<?> owner,
            List<String> names,
            String resourceDirectory
    ) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        String prefix = resourceDirectory.isEmpty() ? "/" : "/" + resourceDirectory + "/";
        for (String name : names) {
            String resource = prefix + name;
            try (InputStream input = owner.getResourceAsStream(resource)) {
                if (input == null) {
                    throw new IOException("Schema resource is missing: " + resource);
                }
                files.put(name, normalize(input.readAllBytes()));
            }
        }
        return files;
    }

    static String setSha256(Map<String, byte[]> filesByName) {
        List<String> names = new ArrayList<>(filesByName.keySet());
        Collections.sort(names);
        ByteArrayOutputStream preimage = new ByteArrayOutputStream();
        try {
            for (String name : names) {
                byte[] normalized = filesByName.get(name);
                preimage.write(name.getBytes(StandardCharsets.UTF_8));
                preimage.write(0);
                preimage.write(Integer.toString(normalized.length).getBytes(StandardCharsets.UTF_8));
                preimage.write(0);
                preimage.write(normalized);
                preimage.write(0);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Schema set preimage must be writable", exception);
        }
        return sha256Hex(preimage.toByteArray());
    }
}
