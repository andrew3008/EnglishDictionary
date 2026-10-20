package space.br1440.platform.tracing.otel.javaagent.logging;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import lombok.experimental.UtilityClass;

@UtilityClass
final class PlatformLogSchemaHash {

    static final String ALGORITHM_ID = "PLATFORM_LOG_SCHEMA_SHA256_V1";
    private static final String SCHEMA_RESOURCE = "/platform-log-event.schema.json";

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

    static String sha256OfNormalizedSchema(Class<?> owner) throws IOException {
        try (InputStream input = owner.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (input == null) {
                throw new IOException("Schema resource is missing: " + SCHEMA_RESOURCE);
            }
            return sha256Hex(normalize(input.readAllBytes()));
        }
    }
}
