package space.br1440.platform.tracing.otel.javaagent.bootstrap.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ControlledAgentArtifactLocatorTest {

    private static final String VERSION = "1.2.0";

    @TempDir
    Path temporaryDirectory;

    @Test
    void locatesTheAgentThatCarriesTheIdentityResource() throws IOException {
        // given
        Path agent = controlledAgent("controlled-agent.jar");

        // when
        ControlledAgentArtifact artifact = ControlledAgentArtifactLocator.locate(List.of("-javaagent:" + agent));

        // then
        assertThat(artifact.path()).isEqualTo(agent.toRealPath());
        assertThat(artifact.identity().get("distribution.version")).isEqualTo(VERSION);
        assertThatThrownBy(() -> artifact.identity().put("distribution.version", "mutated"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void agentsWithoutTheIdentityResourceAreIgnored() throws IOException {
        // given
        Path foreignAgent = foreignAgent("jacocoagent.jar");
        Path agent = controlledAgent("controlled-agent.jar");

        // when
        ControlledAgentArtifact artifact = ControlledAgentArtifactLocator.locate(List.of(
                "-javaagent:" + foreignAgent,
                "-javaagent:" + agent));

        // then
        assertThat(artifact.path()).isEqualTo(agent.toRealPath());
    }

    @Test
    void nullAndUnrelatedArgumentsAreIgnored() throws IOException {
        // given
        Path agent = controlledAgent("controlled-agent.jar");
        List<String> inputArguments = Arrays.asList(
                null,
                "-Xmx512m",
                "-Dplatform.tracing.enabled=true",
                "-javaagent:" + agent);

        // when
        ControlledAgentArtifact artifact = ControlledAgentArtifactLocator.locate(inputArguments);

        // then
        assertThat(artifact.path()).isEqualTo(agent.toRealPath());
    }

    @Test
    void agentOptionsSuffixIsNotPartOfThePath() throws IOException {
        // given
        Path agent = controlledAgent("controlled-agent.jar");

        // when
        ControlledAgentArtifact artifact = ControlledAgentArtifactLocator.locate(List.of(
                "-javaagent:" + agent + "=destfile=/tmp/report.exec"));

        // then
        assertThat(artifact.path()).isEqualTo(agent.toRealPath());
    }

    @Test
    void optionsMayContainAdditionalEqualsSigns() throws IOException {
        // given
        Path agent = controlledAgent("controlled-agent.jar");

        // when
        ControlledAgentArtifact artifact = ControlledAgentArtifactLocator.locate(List.of(
                "-javaagent:" + agent + "=endpoint=http://localhost:4317"));

        // then
        assertThat(artifact.path()).isEqualTo(agent.toRealPath());
    }

    @Test
    void quotedPathIsAccepted() throws IOException {
        // given
        Path agent = controlledAgent("controlled agent.jar");

        // when
        ControlledAgentArtifact artifact = ControlledAgentArtifactLocator.locate(List.of(
                "-javaagent:\"" + agent + "\""));

        // then
        assertThat(artifact.path()).isEqualTo(agent.toRealPath());
    }

    @Test
    void quotedPathWithOptionsIsAccepted() throws IOException {
        // given
        Path agent = controlledAgent("controlled agent.jar");

        // when
        ControlledAgentArtifact artifact = ControlledAgentArtifactLocator.locate(List.of(
                "-javaagent:\"" + agent + "\"=debug"));

        // then
        assertThat(artifact.path()).isEqualTo(agent.toRealPath());
    }

    @Test
    void emptyArtifactPathFailsWithEvidenceMissing() {
        // given
        // when
        VerificationException failure = locateFailure(List.of("-javaagent:"));

        // then
        assertThat(failure.code()).isEqualTo(VerificationFailureCode.DISTRIBUTION_EVIDENCE_MISSING);
        assertThat(failure).hasMessage("A -javaagent argument contains an empty artifact path");
    }

    @Test
    void unmatchedQuoteFailsWithEvidenceMissing() {
        // given
        // when
        VerificationException failure = locateFailure(List.of("-javaagent:\"controlled-agent.jar"));

        // then
        assertThat(failure.code()).isEqualTo(VerificationFailureCode.DISTRIBUTION_EVIDENCE_MISSING);
        assertThat(failure).hasMessage("A -javaagent artifact path contains an unmatched quote");
    }

    @Test
    void absenceOfAnIdentityBearingAgentFailsWithEvidenceMissing() throws IOException {
        // given
        Path foreignAgent = foreignAgent("jacocoagent.jar");

        // when
        VerificationException failure = locateFailure(List.of("-javaagent:" + foreignAgent));

        // then
        assertThat(failure.code()).isEqualTo(VerificationFailureCode.DISTRIBUTION_EVIDENCE_MISSING);
    }

    @Test
    void launchWithoutAnyJavaAgentFailsWithEvidenceMissing() {
        // given
        List<String> inputArguments = List.of("-Xmx512m", "-Dplatform.tracing.enabled=true");

        // when
        VerificationException failure = locateFailure(inputArguments);

        // then
        assertThat(failure.code()).isEqualTo(VerificationFailureCode.DISTRIBUTION_EVIDENCE_MISSING);
    }

    @Test
    void twoIdentityBearingAgentsFailWithDistributionMismatch() throws IOException {
        // given
        Path pinnedAgent = controlledAgent("controlled-agent.jar");
        Path shadowAgent = controlledAgent("controlled-agent-copy.jar");

        // when
        VerificationException failure = locateFailure(List.of(
                "-javaagent:" + pinnedAgent,
                "-javaagent:" + shadowAgent));

        // then
        assertThat(failure.code()).isEqualTo(VerificationFailureCode.DISTRIBUTION_MISMATCH);
    }

    @Test
    void missingAgentFileFailsWithEvidenceMissing() {
        // given
        Path absent = temporaryDirectory.resolve("absent-agent.jar");

        // when
        VerificationException failure = locateFailure(List.of("-javaagent:" + absent));

        // then
        assertThat(failure.code()).isEqualTo(VerificationFailureCode.DISTRIBUTION_EVIDENCE_MISSING);
        assertThat(failure).hasCauseInstanceOf(IOException.class);
    }

    @Test
    void unreadableArchiveFailsWithEvidenceMissing() throws IOException {
        // given
        Path corrupted = temporaryDirectory.resolve("corrupted-agent.jar");
        Files.writeString(corrupted, "not a zip archive", StandardCharsets.UTF_8);

        // when
        VerificationException failure = locateFailure(List.of("-javaagent:" + corrupted));

        // then
        assertThat(failure.code()).isEqualTo(VerificationFailureCode.DISTRIBUTION_EVIDENCE_MISSING);
        assertThat(failure).hasCauseInstanceOf(IOException.class);
    }

    @Test
    void unreadableForeignAgentFailsClosed() throws IOException {
        // given
        Path corrupted = temporaryDirectory.resolve("corrupted-agent.jar");
        Files.writeString(corrupted, "not a zip archive", StandardCharsets.UTF_8);
        Path agent = controlledAgent("controlled-agent.jar");

        // when
        VerificationException failure = locateFailure(List.of(
                "-javaagent:" + corrupted,
                "-javaagent:" + agent));

        // then
        assertThat(failure.code()).isEqualTo(VerificationFailureCode.DISTRIBUTION_EVIDENCE_MISSING);
    }

    @Test
    void oversizedIdentityResourceFailsWithEvidenceMissing() throws IOException {
        // given
        byte[] oversized = new byte[(16 * 1024) + 1];
        Path agent = archive("oversized-agent.jar", ControlledAgentArtifactLocator.IDENTITY_ENTRY, oversized);

        // when
        VerificationException failure = locateFailure(List.of("-javaagent:" + agent));

        // then
        assertThat(failure.code()).isEqualTo(VerificationFailureCode.DISTRIBUTION_EVIDENCE_MISSING);
        assertThat(failure.getCause())
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    void malformedUnicodeEscapeFailsWithEvidenceMissing() throws IOException {
        // given
        byte[] identity = "distribution.version=\\uZZZZ\n".getBytes(StandardCharsets.ISO_8859_1);
        Path agent = archive("malformed-identity.jar", ControlledAgentArtifactLocator.IDENTITY_ENTRY, identity);

        // when
        VerificationException failure = locateFailure(List.of("-javaagent:" + agent));

        // then
        assertThat(failure.code()).isEqualTo(VerificationFailureCode.DISTRIBUTION_EVIDENCE_MISSING);
        assertThat(failure).hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unicodeEscapeIsDecodedByThePropertiesContract() throws IOException {
        // given
        byte[] identity = "distribution.artifact=caf\\u00e9\n".getBytes(StandardCharsets.ISO_8859_1);
        Path agent = archive("escaped-identity.jar", ControlledAgentArtifactLocator.IDENTITY_ENTRY, identity);

        // when
        ControlledAgentArtifact artifact = ControlledAgentArtifactLocator.locate(List.of("-javaagent:" + agent));

        // then
        assertThat(artifact.identity().get("distribution.artifact")).isEqualTo("café");
    }

    private Path controlledAgent(String fileName) throws IOException {
        Properties identity = new Properties();
        identity.setProperty("format.version", "1");
        identity.setProperty("distribution.group", "space.br1440.platform.starters.tracing");
        identity.setProperty("distribution.artifact", "platform-tracing-javaagent");
        identity.setProperty("distribution.version", VERSION);

        ByteArrayOutputStream identityBytes = new ByteArrayOutputStream();
        identity.store(identityBytes, null);
        return archive(fileName, ControlledAgentArtifactLocator.IDENTITY_ENTRY, identityBytes.toByteArray());
    }

    @SuppressWarnings("SameParameterValue")
    private Path foreignAgent(String fileName) throws IOException {
        return archive(fileName, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
    }

    private Path archive(String fileName, String entryName, byte[] bytes) throws IOException {
        Path agent = temporaryDirectory.resolve(fileName);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(agent))) {
            output.putNextEntry(new ZipEntry(entryName));
            output.write(bytes);
            output.closeEntry();
        }
        return agent;
    }

    private static VerificationException locateFailure(List<String> inputArguments) {
        Throwable failure = catchThrowable(() -> ControlledAgentArtifactLocator.locate(inputArguments));
        assertThat(failure).isInstanceOf(VerificationException.class);
        return (VerificationException) failure;
    }
}
