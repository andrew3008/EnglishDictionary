package space.br1440.platform.tracing.gradle.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

        // when
        Throwable failure = catchThrowable(() -> JavaAgentDistribution.requireReviewedUpstreamSha256(upstream, extension));

        // then
        assertThat(failure)
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

        // when
        Throwable failure = catchThrowable(() -> JavaAgentDistribution.requireReviewedUpstreamSha256(upstream, extension));

        // then
        assertThat(failure)
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

        // when
        Throwable failure = catchThrowable(() -> JavaAgentDistribution.requireReviewedUpstreamSha256(upstream, extension));

        // then
        assertThat(failure)
                .isInstanceOf(GradleException.class)
                .hasMessageContaining("does not match reviewed pin");
    }

    @ParameterizedTest
    @ValueSource(strings = {"extensions", "extensions/", "extensions/unapproved.jar", "ExTeNsIoNs/unapproved.jar"})
    void rejects_extension_entries_in_upstream_agent(String entry, @TempDir Path directory) throws IOException {
        // given
        Path upstream = writeJar(directory.resolve("upstream.jar"), Map.of(
                entry,
                "payload"
        ));

        // when
        Throwable failure = catchThrowable(() -> JavaAgentDistribution.requireNoPlatformEntries(upstream));

        // then
        assertThat(failure)
                .isInstanceOf(GradleException.class)
                .hasMessageContaining("already contains platform content")
                .hasMessageContaining(entry);
    }

    @ParameterizedTest
    @ValueSource(strings = {IDENTITY_ENTRY, "MeTa-InF/PlAtFoRm-TrAcInG/JaVaAgEnT-DiStRiBuTiOn.PrOpErTiEs"})
    void rejects_reserved_identity_entry_in_upstream_agent(String entry, @TempDir Path directory) throws IOException {
        // given
        Path upstream = writeJar(directory.resolve("upstream.jar"), Map.of(
                entry,
                "unapproved"
        ));

        // when
        Throwable failure = catchThrowable(() -> JavaAgentDistribution.requireNoPlatformEntries(upstream));

        // then
        assertThat(failure)
                .isInstanceOf(GradleException.class)
                .hasMessageContaining(entry);
    }

    @SuppressWarnings("SameParameterValue")
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
