package space.br1440.platform.tracing.gradle;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.regex.Pattern;

import org.gradle.api.GradleException;

record ControlledAgentPin(String version, String sha256) {

    private static final String RESOURCE_PATH = "/META-INF/platform-tracing/controlled-agent-pin.properties";
    private static final String VERSION_KEY = "agent.version";
    private static final String SHA256_KEY = "agent.sha256";
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    ControlledAgentPin {
        if (version == null || version.isBlank() || !version.equals(version.trim())) {
            throw new GradleException("""
                    Controlled agent pin %s must be a trimmed non-blank value"""
                    .formatted(VERSION_KEY));
        }

        if (sha256 == null || !SHA256_HEX.matcher(sha256).matches()) {
            throw new GradleException("""
                    Controlled agent pin %s must be a lowercase 64-character SHA-256 hex value"""
                    .formatted(SHA256_KEY));
        }
    }

    static ControlledAgentPin load() {
        Properties properties = new Properties();
        try (InputStream stream = ControlledAgentPin.class.getResourceAsStream(RESOURCE_PATH)) {
            if (stream == null) {
                throw new GradleException("""
                        Controlled agent pin resource %s \
                        is missing from the plugin JAR; the plugin release is broken"""
                        .formatted(RESOURCE_PATH));
            }

            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        } catch (IOException failure) {
            throw new GradleException("""
                    Cannot read controlled agent pin resource %s"""
                    .formatted(RESOURCE_PATH), failure);
        }

        return new ControlledAgentPin(
                properties.getProperty(VERSION_KEY),
                properties.getProperty(SHA256_KEY)
        );
    }

    String agentFileName() {
        return PlatformTracingJibPlugin.AGENT_MODULE + "-" + version + ".jar";
    }
}
