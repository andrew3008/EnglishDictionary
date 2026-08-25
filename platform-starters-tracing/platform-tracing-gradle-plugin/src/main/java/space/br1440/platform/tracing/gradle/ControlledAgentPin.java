package space.br1440.platform.tracing.gradle;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.regex.Pattern;

import org.gradle.api.GradleException;

import space.br1440.platform.common.util.StringUtils;

final class ControlledAgentPin {

    private static final String RESOURCE_PATH = "/META-INF/platform-tracing/controlled-agent-pin.properties";
    private static final String VERSION_KEY = "agent.version";
    private static final String SHA256_KEY = "agent.sha256";
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    private final String version;
    private final String sha256;

    private ControlledAgentPin(String version, String sha256) {
        this.version = version;
        this.sha256 = sha256;
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
                requireVersion(properties.getProperty(VERSION_KEY)),
                requireSha256(properties.getProperty(SHA256_KEY))
        );
    }

    private static String requireVersion(String value) {
        if (StringUtils.isNullOrBlank(value) || !value.equals(value.trim())) {
            throw new GradleException("""
                    Controlled agent pin %s must be a trimmed non-blank value"""
                    .formatted(VERSION_KEY));
        }

        return value;
    }

    private static String requireSha256(String value) {
        if (value == null || !SHA256_HEX.matcher(value).matches()) {
            throw new GradleException("""
                    Controlled agent pin %s must be a lowercase 64-character SHA-256 hex value"""
                    .formatted(SHA256_KEY));
        }

        return value;
    }

    String version() {
        return version;
    }

    String sha256() {
        return sha256;
    }
}
