package space.br1440.platform.tracing.gradle;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

record PluginRelease(String version, Path pluginJar, Path agentJar, Path publishedRepository, Path testKitDirectory) {

    static final String PLUGIN_ID = "platform.tracing-jib";
    static final String PLUGIN_GROUP = "space.br1440.platform.starters.tracing";
    static final String PLUGIN_ARTIFACT = "platform-tracing-jib-plugin";
    static final String AGENT_ARTIFACT = "platform-tracing-javaagent";
    static final String PIN_RESOURCE = "/META-INF/platform-tracing/controlled-agent-pin.properties";

    private static final String VERSION_PROPERTY = "platformTracing.pluginVersion";
    private static final String PLUGIN_JAR_PROPERTY = "platformTracing.pluginJar";
    private static final String AGENT_JAR_PROPERTY = "platformTracing.controlledAgentJar";
    private static final String PUBLISHED_REPOSITORY_PROPERTY = "platformTracing.publishedRepository";
    private static final String TEST_KIT_DIRECTORY_PROPERTY = "platformTracing.testKitDirectory";

    static PluginRelease fromSystemProperties() {
        return new PluginRelease(
                requiredProperty(VERSION_PROPERTY),
                requiredPath(PLUGIN_JAR_PROPERTY),
                requiredPath(AGENT_JAR_PROPERTY),
                requiredDirectory(PUBLISHED_REPOSITORY_PROPERTY),
                Path.of(requiredProperty(TEST_KIT_DIRECTORY_PROPERTY))
        );
    }

    Path publishedArtifactDirectory(String group, String artifact) {
        return publishedRepository
                .resolve(group.replace('.', '/'))
                .resolve(artifact)
                .resolve(version);
    }

    String agentFileName() {
        return AGENT_ARTIFACT + "-" + version + ".jar";
    }

    Properties pin() {
        return loadProperties(requiredResource(PIN_RESOURCE));
    }

    String pinnedSha256() {
        return pin().getProperty("agent.sha256");
    }

    static URL requiredResource(String name) {
        URL resource = PluginRelease.class.getResource(name);
        if (resource == null) {
            throw new IllegalStateException("Plugin release is missing the resource: " + name);
        }

        return resource;
    }

    static Properties loadProperties(URL resource) {
        Properties properties = new Properties();
        try (InputStream stream = resource.openStream()) {
            properties.load(stream);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read plugin release resource " + resource, failure);
        }

        return properties;
    }

    @SuppressWarnings("SameParameterValue")
    private static Path requiredDirectory(String name) {
        Path path = Path.of(requiredProperty(name));
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException("Required test directory is missing: " + path);
        }

        return path;
    }

    private static Path requiredPath(String name) {
        Path path = Path.of(requiredProperty(name));
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Required test file is missing: " + path);
        }

        return path;
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required test system property is missing: " + name);
        }

        return value;
    }
}
