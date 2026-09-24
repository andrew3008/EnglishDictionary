package space.br1440.platform.tracing.otel.javaagent.readiness;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Objects;
import java.util.Properties;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.otel.javaagent.artifact.PlatformExtensionArtifact;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;

import lombok.experimental.UtilityClass;

@UtilityClass
final class ExtensionVersionResolver {

    static final String DEVELOPMENT_VERSION = "development";

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(ExtensionVersionResolver.class);

    private static final String METADATA_RESOURCE = "platform-extension-build.properties";
    private static final String FORMAT_VERSION_KEY = "format.version";
    private static final String ARTIFACT_KEY = "artifact";
    private static final String VERSION_KEY = "version";
    private static final String SUPPORTED_FORMAT_VERSION = "1";

    @SuppressWarnings("SameParameterValue")
    static String resolve(Class<?> extensionClass) {
        Objects.requireNonNull(extensionClass, "extensionClass");
        return resolve(metadataResourceBeside(extensionClass));
    }

    static String resolve(URL metadata) {
        if (metadata == null) {
            warnInvalidMetadata("Extension build metadata is missing; using development", "reason", "missing");
            return DEVELOPMENT_VERSION;
        }

        Properties properties = new Properties();
        try (InputStream stream = metadata.openStream()) {
            properties.load(stream);
        } catch (IOException | IllegalArgumentException failure) {
            warnInvalidMetadata("Extension build metadata is unreadable; using development", "resource", metadata);
            return DEVELOPMENT_VERSION;
        }

        String resolved = resolve(properties);
        if (DEVELOPMENT_VERSION.equals(resolved)) {
            warnInvalidMetadata("Extension build metadata is invalid; using development", "resource", metadata);
        }
        return resolved;
    }

    static String resolve(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        if (!SUPPORTED_FORMAT_VERSION.equals(properties.getProperty(FORMAT_VERSION_KEY))) {
            return DEVELOPMENT_VERSION;
        }

        if (!PlatformExtensionArtifact.IMPLEMENTATION_TITLE.equals(properties.getProperty(ARTIFACT_KEY))) {
            return DEVELOPMENT_VERSION;
        }

        String version = properties.getProperty(VERSION_KEY);
        return StringUtils.hasText(version)
                ? version.trim()
                : DEVELOPMENT_VERSION;
    }

    private static URL metadataResourceBeside(Class<?> extensionClass) {
        return extensionClass.getResource(METADATA_RESOURCE);
    }

    private static void warnInvalidMetadata(String message, String field, Object value) {
        log.warn(
                AgentEventCode.BOOT_ARTIFACT_VERSION,
                message,
                field,
                String.valueOf(value)
        );
    }
}
