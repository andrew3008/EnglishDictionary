package space.br1440.platform.tracing.gradle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class IsolatedMavenRepository {

    private static final String MARKER_ARTIFACT = PluginRelease.PLUGIN_ID + ".gradle.plugin";

    private final Path root;
    private final String version;

    IsolatedMavenRepository(Path root, String version) {
        this.root = root;
        this.version = version;
    }

    String uri() {
        return root.toAbsolutePath().normalize().toUri().toString();
    }

    @SuppressWarnings("SameParameterValue")
    void installRelease(PluginRelease release) throws IOException {
        installJar(PluginRelease.PLUGIN_GROUP, PluginRelease.PLUGIN_ARTIFACT, release.pluginJar());

        Path markerDirectory = mavenDirectory(PluginRelease.PLUGIN_ID, MARKER_ARTIFACT);
        Files.writeString(
                markerDirectory.resolve(MARKER_ARTIFACT + "-" + version + ".pom"),
                mavenPom(PluginRelease.PLUGIN_ID, MARKER_ARTIFACT, "pom", """
                          <dependencies>
                            <dependency>
                              <groupId>%s</groupId>
                              <artifactId>%s</artifactId>
                              <version>%s</version>
                            </dependency>
                          </dependencies>
                        """.formatted(PluginRelease.PLUGIN_GROUP, PluginRelease.PLUGIN_ARTIFACT, version)),
                StandardCharsets.UTF_8
        );
    }

    void installAgent(Path jar) throws IOException {
        installJar(PluginRelease.PLUGIN_GROUP, PluginRelease.AGENT_ARTIFACT, jar);
    }

    void installJar(String group, String artifact, Path jar) throws IOException {
        Path directory = mavenDirectory(group, artifact);
        Files.copy(jar, directory.resolve(artifact + "-" + version + ".jar"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(
                directory.resolve(artifact + "-" + version + ".pom"),
                mavenPom(group, artifact, "jar", ""),
                StandardCharsets.UTF_8
        );
    }

    private Path mavenDirectory(String group, String artifact) throws IOException {
        Path directory = root
                .resolve(group.replace('.', '/'))
                .resolve(artifact)
                .resolve(version);
        Files.createDirectories(directory);
        return directory;
    }

    private String mavenPom(String group, String artifact, String packaging, String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <packaging>%s</packaging>
                %s</project>
                """.formatted(group, artifact, version, packaging, body);
    }
}
