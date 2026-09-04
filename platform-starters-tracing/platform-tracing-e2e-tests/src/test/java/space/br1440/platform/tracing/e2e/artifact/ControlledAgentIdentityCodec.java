package space.br1440.platform.tracing.e2e.artifact;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

final class ControlledAgentIdentityCodec {

    private static final String[] REQUIRED_KEYS = {
            "format.version",
            "distribution.group",
            "distribution.artifact",
            "distribution.version",
            "upstream.coordinate",
            "upstream.sha256",
            "extension.path",
            "extension.sha256",
            "minimum.java"
    };

    private ControlledAgentIdentityCodec() {
    }

    static Properties load(byte[] source) throws IOException {
        Properties identity = new Properties();
        try (InputStream input = new ByteArrayInputStream(source)) {
            identity.load(input);
        }
        return identity;
    }

    static byte[] canonicalize(Properties identity) {
        StringBuilder content = new StringBuilder();
        for (String key : REQUIRED_KEYS) {
            String value = identity.getProperty(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Controlled agent identity is missing " + key);
            }

            content.append(key).append('=').append(value).append('\n');
        }

        return content.toString().getBytes(StandardCharsets.ISO_8859_1);
    }
}
