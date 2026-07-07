package space.br1440.platform.devtools.opusmcp.release;

import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.testsupport.ProjectPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6A release/packaging hygiene checks (deterministic, no network). Validates build metadata and
 * the committed dependency lock file without changing any runtime behavior.
 */
class ReleasePackagingTest {

    private String read(String relative) throws IOException {
        Path p = ProjectPaths.resolve(relative);
        assertThat(Files.exists(p)).as("expected file %s", p).isTrue();
        return Files.readString(p);
    }

    @Test
    void buildDeclaresStableVersionAndArtifactName() throws IOException {
        String build = read("build.gradle");
        assertThat(build).contains("version = '0.1.0-SNAPSHOT'");
        String settings = read("settings.gradle");
        assertThat(settings).contains("rootProject.name = 'opus-mcp-server'");
    }

    @Test
    void shadowJarDeclaresManifestMetadataWithoutLeakage() throws IOException {
        String build = read("build.gradle");
        assertThat(build).contains("'Implementation-Title'");
        assertThat(build).contains("'Implementation-Version'");
        assertThat(build).contains("'Main-Class'");
        // No username/host/path/timestamp leakage in manifest config.
        assertThat(build).doesNotContain("Built-By");
    }

    @Test
    void dependencyLockingIsEnabledAndLockfileExists() throws IOException {
        String build = read("build.gradle");
        assertThat(build).contains("dependencyLocking");
        String lock = read("gradle.lockfile");
        assertThat(lock).contains("io.modelcontextprotocol.sdk:mcp:2.0.0");
        assertThat(lock).contains("com.fasterxml.jackson.core:jackson-databind:2.18.2");
    }

    @Test
    void releaseAndSupplyChainTasksAreDeclared() throws IOException {
        String build = read("build.gradle");
        assertThat(build).contains("tasks.register('releaseCheck')");
        assertThat(build).contains("tasks.register('dependencySecurityReport')");
        assertThat(build).contains("tasks.register('verifyJarExists')");
        assertThat(build).contains("tasks.register('resolveAndLockAll')");
    }
}
