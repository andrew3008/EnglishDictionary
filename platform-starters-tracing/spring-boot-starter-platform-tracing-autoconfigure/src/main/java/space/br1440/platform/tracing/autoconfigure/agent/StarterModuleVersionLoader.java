package space.br1440.platform.tracing.autoconfigure.agent;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Properties;

import space.br1440.platform.errorhandling.startup.PlatformConfigurationException;

import lombok.experimental.UtilityClass;

@UtilityClass
final class StarterModuleVersionLoader {

    private static final String RESOURCE_PATH = "/META-INF/platform-tracing/module-version/spring-boot-starter-platform-tracing-autoconfigure.properties";

    private static String cachedStarterModuleVersion;

    static synchronized String starterModuleVersion() {
        if (cachedStarterModuleVersion == null) {
            cachedStarterModuleVersion = readModuleVersion(AgentStartupVerifier.class.getResource(RESOURCE_PATH));
        }

        return cachedStarterModuleVersion;
    }

    static String readModuleVersion(URL resource) {
        if (resource == null) {
            throw new PlatformConfigurationException(
                    "Missing starter compatibility metadata resource: " + RESOURCE_PATH,
                    """
                    Rebuild spring-boot-starter-platform-tracing-autoconfigure so that \
                    processResources writes %s.""".formatted(RESOURCE_PATH)
            );
        }

        Properties properties = new Properties();
        try (InputStream stream = resource.openStream()) {
            properties.load(stream);
        } catch (IOException | IllegalArgumentException readFailure) {
            throw new PlatformConfigurationException(
                    "Cannot read starter compatibility metadata resource: " + resource,
                    """
                    Verify that %s is a valid properties file \
                    on the spring-boot-starter-platform-tracing-autoconfigure classpath.""".formatted(RESOURCE_PATH),
                    readFailure
            );
        }

        String moduleVersion = properties.getProperty("module.version");
        if (moduleVersion == null || moduleVersion.isBlank()) {
            throw new PlatformConfigurationException(
                    "Missing module.version in starter compatibility metadata: " + resource,
                    """
                    Rebuild spring-boot-starter-platform-tracing-autoconfigure so that \
                    %s contains a non-blank module.version.""".formatted(RESOURCE_PATH)
            );
        }

        return moduleVersion;
    }
}
