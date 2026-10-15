package space.br1440.platform.tracing.otel.javaagent.distribution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

import space.br1440.platform.common.util.StringUtils;

record AgentTestFixture(Path controlledAgent,
                        String distributionVersion,
                        String expectedOtelJavaAgentSha,
                        String expectedExtensionSha,
                        String expectedControlledAgentSha) {

    static AgentTestFixture load() throws IOException {
        Path controlledAgent = requiredPath("platformTracing.controlledAgentJar");
        Path extension = requiredPath("platformTracing.extensionJar");
        String distributionVersion = requiredProperty("platformTracing.distributionVersion");
        String expectedOtelJavaAgentSha = sha256Hex(Files.readAllBytes(requiredPath("platformTracing.otelJavaAgentJar")));
        String expectedExtensionSha = sha256Hex(Files.readAllBytes(extension));
        String expectedControlledAgentSha = sha256Hex(Files.readAllBytes(controlledAgent));
        return new AgentTestFixture(
                controlledAgent,
                distributionVersion,
                expectedOtelJavaAgentSha,
                expectedExtensionSha,
                expectedControlledAgentSha
        );
    }

    static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (StringUtils.isNullOrBlank(value)) {
            throw new IllegalStateException("Required test system property is missing: " + name);
        }
        return value;
    }

    private static Path requiredPath(String name) {
        Path path = Path.of(requiredProperty(name));
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Required test file is missing: " + path);
        }
        return path;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format(Locale.ROOT, "%02x", value & 0xFF));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
