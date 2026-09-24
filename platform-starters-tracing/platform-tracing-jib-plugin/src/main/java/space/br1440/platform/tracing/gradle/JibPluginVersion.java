package space.br1440.platform.tracing.gradle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import lombok.experimental.UtilityClass;

import org.gradle.api.GradleException;

@UtilityClass
final class JibPluginVersion {

    static final String RESOURCE_PATH = "/META-INF/platform-tracing/jib-plugin-version.txt";

    static String load() {
        try (InputStream stream = JibPluginVersion.class.getResourceAsStream(RESOURCE_PATH)) {
            if (stream == null) {
                throw new GradleException("""
                        Platform tracing Jib plugin version resource %s \
                        is missing from the plugin JAR; the plugin release is broken"""
                        .formatted(RESOURCE_PATH));
            }

            String version = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (version.isEmpty()) {
                throw new GradleException("""
                        Platform tracing Jib plugin version resource %s is blank"""
                        .formatted(RESOURCE_PATH));
            }

            return version;
        } catch (IOException failure) {
            throw new GradleException("""
                    Cannot read platform tracing Jib plugin version resource %s"""
                    .formatted(RESOURCE_PATH), failure);
        }
    }
}
