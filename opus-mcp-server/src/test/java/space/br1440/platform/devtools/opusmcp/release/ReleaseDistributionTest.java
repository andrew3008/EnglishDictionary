package space.br1440.platform.devtools.opusmcp.release;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.testsupport.ProjectPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase 6C: validates the release/versioning/distribution workflow (build wiring, release-notes
 * template, rollback/Cursor/secret docs, and — when present — the generated manifest + checksum).
 * Deterministic and network-free; generated-artifact checks are skipped when not yet built.
 */
class ReleaseDistributionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String read(String relative) throws IOException {
        Path p = ProjectPaths.resolve(relative);
        assertThat(Files.exists(p)).as("expected file %s", p).isTrue();
        return Files.readString(p);
    }

    @Test
    void buildDeclaresReleaseDistributionTasks() throws IOException {
        String build = read("build.gradle");
        assertThat(build).contains("tasks.register('generateReleaseChecksums')");
        assertThat(build).contains("tasks.register('generateReleaseManifest')");
        assertThat(build).contains("tasks.register('releasePackageCheck')");
    }

    @Test
    void releaseNotesTemplateHasRequiredSections() throws IOException {
        String t = read("docs/RELEASE-NOTES-TEMPLATE.md");
        for (String section : new String[] {
                "Version", "Date", "Status", "Compatibility", "Added", "Changed", "Fixed",
                "Security", "Operational notes", "Known limitations", "Verification commands",
                "Artifacts", "Checksums", "Rollback"}) {
            assertThat(t).as("template must mention %s", section).contains(section);
        }
    }

    @Test
    void releaseDocsCoverRollbackCursorAndSecretHygiene() throws IOException {
        String release = read("docs/RELEASE.md");
        assertThat(release.toLowerCase()).contains("rollback");
        // Cursor config is updated manually, never automatically.
        assertThat(release).contains("mcp.json");
        assertThat(release.toLowerCase()).contains("not");
        // OPUS_API_KEY must never be committed.
        assertThat(release).contains("OPUS_API_KEY");
        String installScript = read("scripts/install-local-release.ps1");
        assertThat(installScript.toLowerCase()).contains("not modified automatically");
    }

    @Test
    void releaseManifestHasExpectedShapeWhenPresent() throws IOException {
        Path manifest = ProjectPaths.resolve("build/distributions/release-manifest.json");
        assumeTrue(Files.exists(manifest), "manifest not generated yet (run releasePackageCheck)");

        String raw = Files.readString(manifest);
        JsonNode root = MAPPER.readTree(raw);
        assertThat(root.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(root.path("project").asText()).isEqualTo("opus-mcp-server");
        assertThat(root.path("version").asText()).isEqualTo("0.1.0-SNAPSHOT");
        assertThat(root.path("artifact").asText()).isEqualTo("opus-mcp-server-0.1.0-SNAPSHOT-all.jar");
        assertThat(root.path("mainClass").asText())
                .isEqualTo("space.br1440.platform.devtools.opusmcp.Main");
        assertThat(root.path("mcp").path("serverName").asText()).isEqualTo("java-mcp-opus-server");
        assertThat(root.path("mcp").path("transport").asText()).isEqualTo("stdio");

        JsonNode tools = root.path("mcp").path("tools");
        assertThat(tools.isArray()).isTrue();
        assertThat(tools.toString()).contains("echo_mcp_connection").contains("generate_code_with_opus");
        assertThat(root.path("checksums").path("sha256").asText()).hasSize(64);

        // No local path / username / env leakage in the manifest.
        assertThat(raw).doesNotContain("\\Users\\").doesNotContain("/home/");
        assertThat(raw).doesNotContain("OPUS_API_KEY");
        assertThat(raw).doesNotContain(System.getProperty("user.name"));
    }

    @Test
    void checksumMatchesJarWhenPresent() throws IOException {
        Path jar = ProjectPaths.resolve("build/libs/opus-mcp-server-0.1.0-SNAPSHOT-all.jar");
        Path checksum = ProjectPaths.resolve(
                "build/distributions/checksums/opus-mcp-server-0.1.0-SNAPSHOT-all.jar.sha256");
        assumeTrue(Files.exists(jar) && Files.exists(checksum),
                "jar/checksum not generated yet (run releasePackageCheck)");

        String declared = Files.readString(checksum).trim().split("\\s+")[0];
        assertThat(declared).hasSize(64);
        assertThat(sha256Hex(jar)).isEqualTo(declared);
    }

    private static String sha256Hex(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(Files.readAllBytes(file)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
