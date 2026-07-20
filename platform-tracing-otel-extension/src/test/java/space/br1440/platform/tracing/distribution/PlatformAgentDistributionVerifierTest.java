package space.br1440.platform.tracing.distribution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformAgentDistributionVerifierTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsCompleteControlledDistribution() throws IOException {
        Path distribution = copyDistribution();

        assertThat(run(distribution, Map.of())).isEqualTo(PlatformAgentDistributionVerifier.OK);
    }

    @Test
    void rejectsCorruptManifest() throws IOException {
        Path distribution = copyDistribution();
        Files.writeString(distribution.resolve("manifest.json"), "{broken", StandardCharsets.UTF_8);

        assertThat(run(distribution, Map.of())).isEqualTo(PlatformAgentDistributionVerifier.MANIFEST_ERROR);
    }

    @Test
    void rejectsChangedAgentJar() throws IOException {
        Path distribution = copyDistribution();
        Files.writeString(distribution.resolve("opentelemetry-javaagent.jar"), "tampered",
                StandardCharsets.UTF_8);

        assertThat(run(distribution, Map.of())).isEqualTo(PlatformAgentDistributionVerifier.CHECKSUM_MISMATCH);
    }

    @Test
    void rejectsChangedExtensionJar() throws IOException {
        Path distribution = copyDistribution();
        Files.writeString(distribution.resolve("platform-tracing-otel-javaagent-extension.jar"), "tampered",
                StandardCharsets.UTF_8);

        assertThat(run(distribution, Map.of())).isEqualTo(PlatformAgentDistributionVerifier.CHECKSUM_MISMATCH);
    }

    @Test
    void rejectsMissingExtension() throws IOException {
        Path distribution = copyDistribution();
        Files.delete(distribution.resolve("platform-tracing-otel-javaagent-extension.jar"));

        assertThat(run(distribution, Map.of())).isEqualTo(PlatformAgentDistributionVerifier.ARTIFACT_MISSING);
    }

    @Test
    void rejectsIncompatibleReadinessProtocol() throws IOException {
        Path distribution = copyDistribution();
        Path manifest = distribution.resolve("manifest.json");
        String content = Files.readString(manifest, StandardCharsets.UTF_8)
                .replace("\"readinessProtocolVersion\": \"1\"", "\"readinessProtocolVersion\": \"999\"");
        Files.writeString(manifest, content, StandardCharsets.UTF_8);

        assertThat(run(distribution, Map.of())).isEqualTo(PlatformAgentDistributionVerifier.COMPATIBILITY_ERROR);
    }

    @Test
    void rejectsExternalExtensionConfiguration() throws IOException {
        Path distribution = copyDistribution();

        assertThat(run(distribution, Map.of("OTEL_JAVAAGENT_EXTENSIONS", "/tmp/foreign.jar")))
                .isEqualTo(PlatformAgentDistributionVerifier.LAUNCH_CONFLICT);
    }

    @Test
    void rejectsDuplicateAgentArgumentWithoutPrintingIt() throws IOException {
        Path distribution = copyDistribution();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int result = PlatformAgentDistributionVerifier.run(
                new String[]{"verify", distribution.toString(), "--", "-javaagent:/secret/path/stock-agent.jar"},
                Map.of(), new PrintStream(OutputStream.nullOutputStream()), new PrintStream(errors));

        assertThat(result).isEqualTo(PlatformAgentDistributionVerifier.LAUNCH_CONFLICT);
        assertThat(errors.toString(StandardCharsets.UTF_8)).doesNotContain("/secret/path");
    }

    private Path copyDistribution() throws IOException {
        Path source = Path.of(System.getProperty("platform.agent.distribution.dir"));
        Path target = temporaryDirectory.resolve("distribution");
        Files.createDirectories(target);
        try (var files = Files.list(source)) {
            for (Path file : files.toList()) {
                Files.copy(file, target.resolve(file.getFileName()));
            }
        }
        return target;
    }

    private static int run(Path distribution, Map<String, String> environment) {
        return PlatformAgentDistributionVerifier.run(
                new String[]{"verify", distribution.toString()}, environment,
                new PrintStream(OutputStream.nullOutputStream()), new PrintStream(OutputStream.nullOutputStream()));
    }
}
