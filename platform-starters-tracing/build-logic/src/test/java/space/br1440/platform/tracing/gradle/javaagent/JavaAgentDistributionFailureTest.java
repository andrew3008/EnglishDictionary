package space.br1440.platform.tracing.gradle.javaagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaAgentDistributionFailureTest {

    private static final String PIN_ENTRY = "META-INF/platform-tracing/otel-javaagent-acp-pin.properties";
    private static final String IDENTITY_ENTRY = "META-INF/platform-tracing/javaagent-distribution.properties";

    @Test
    void rejects_missing_upstream_pin(@TempDir Path directory) throws IOException {
        // given
        Path upstream = writeFile(directory.resolve("upstream.jar"), "upstream");
        Path extension = writeJar(directory.resolve("extension.jar"), Map.of(
                PIN_ENTRY,
                "format.version=1\n"
        ));

        // expect
        assertThatThrownBy(() -> JavaAgentDistribution.requireReviewedUpstreamSha256(upstream, extension))
                .isInstanceOf(GradleException.class)
                .hasMessageContaining("otel.javaagent.sha256 is missing");
    }

    @Test
    void rejects_invalid_pin_after_stripping_whitespace(@TempDir Path directory) throws IOException {
        // given
        Path upstream = writeFile(directory.resolve("upstream.jar"), "upstream");
        Path extension = writeJar(directory.resolve("extension.jar"), Map.of(
                PIN_ENTRY,
                "otel.javaagent.sha256= not-a-sha256 \n"
        ));

        // expect
        assertThatThrownBy(() -> JavaAgentDistribution.requireReviewedUpstreamSha256(upstream, extension))
                .isInstanceOf(GradleException.class)
                .hasMessageContaining("must be 64 lowercase hex characters");
    }

    @Test
    void rejects_sha_mismatch(@TempDir Path directory) throws IOException {
        // given
        Path upstream = writeFile(directory.resolve("upstream.jar"), "upstream");
        Path extension = writeJar(directory.resolve("extension.jar"), Map.of(
                PIN_ENTRY,
                "otel.javaagent.sha256=" + "0".repeat(64) + "\n"
        ));

        // expect
        assertThatThrownBy(() -> JavaAgentDistribution.requireReviewedUpstreamSha256(upstream, extension))
                .isInstanceOf(GradleException.class)
                .hasMessageContaining("does not match reviewed pin");
    }

    @Test
    void rejects_platform_entries_in_upstream_agent(@TempDir Path directory) throws IOException {
        // given
        Path upstream = writeJar(directory.resolve("upstream.jar"), Map.of(
                "extensions/unapproved.jar",
                "payload"
        ));

        // expect
        assertThatThrownBy(() -> JavaAgentDistribution.requireNoPlatformEntries(upstream, Set.of()))
                .isInstanceOf(GradleException.class)
                .hasMessageContaining("already contains platform content");
    }

    @Test
    void rejects_configured_reserved_entry_in_upstream_agent(@TempDir Path directory) throws IOException {
        // given
        Path upstream = writeJar(directory.resolve("upstream.jar"), Map.of(
                IDENTITY_ENTRY,
                "unapproved"
        ));

        // expect
        assertThatThrownBy(() -> JavaAgentDistribution.requireNoPlatformEntries(upstream, Set.of(IDENTITY_ENTRY)))
                .isInstanceOf(GradleException.class)
                .hasMessageContaining(IDENTITY_ENTRY);
    }

    @Test
    void keeps_existing_target_when_staged_file_is_missing(@TempDir Path directory) throws IOException {
        // given
        Path target = writeFile(directory.resolve("agent.jar"), "existing");
        Path missing = directory.resolve("missing.jar");

        // when
        var failure = org.assertj.core.api.Assertions.catchThrowable(
                () -> DistributionFiles.publishAtomically(missing, target)
        );

        // then
        assertThat(failure)
                .isInstanceOf(GradleException.class)
                .hasMessageContaining("Cannot atomically replace")
                .hasCauseInstanceOf(NoSuchFileException.class);
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("existing");
        try (var files = Files.list(directory)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .containsExactly("agent.jar");
        }
    }

    private static Path writeFile(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    private static Path writeJar(Path path, Map<String, String> entries) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return path;
    }
}
