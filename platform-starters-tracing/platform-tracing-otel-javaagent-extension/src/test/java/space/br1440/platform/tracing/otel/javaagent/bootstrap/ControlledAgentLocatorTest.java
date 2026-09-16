package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
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

class ControlledAgentLocatorTest {

    private static final String IDENTITY_ENTRY = "META-INF/platform-tracing/javaagent-distribution.properties";
    private static final String VERSION = "1.2.0";

    @TempDir
    Path temporaryDirectory;

    @Test
    void locatesTheAgentThatCarriesTheIdentityResource() throws IOException {
        // given
        Path agent = controlledAgent("controlled-agent.jar");

        // when
        ControlledAgentArtifact artifact = ControlledAgentLocator.locate(List.of("-javaagent:" + agent));

        // then
        assertThat(artifact.path()).isEqualTo(agent.toRealPath());
        assertThat(artifact.identity().getProperty("distribution.version")).isEqualTo(VERSION);
    }

    @Test
    void agentsWithoutTheIdentityResourceAreIgnored() throws IOException {
        // given
        Path foreignAgent = foreignAgent("jacocoagent.jar");
        Path agent = controlledAgent("controlled-agent.jar");

        // when
        ControlledAgentArtifact artifact = ControlledAgentLocator.locate(List.of(
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
        ControlledAgentArtifact artifact = ControlledAgentLocator.locate(inputArguments);

        // then
        assertThat(artifact.path()).isEqualTo(agent.toRealPath());
    }

    @Test
    void agentOptionsSuffixIsNotPartOfThePath() throws IOException {
        // given
        Path agent = controlledAgent("controlled-agent.jar");

        // when
        ControlledAgentArtifact artifact =
                ControlledAgentLocator.locate(List.of("-javaagent:" + agent + "=destfile=/tmp/report.exec"));

        // then
        assertThat(artifact.path()).isEqualTo(agent.toRealPath());
    }

    @Test
    void quotedPathIsAccepted() throws IOException {
        // given
        Path agent = controlledAgent("controlled agent.jar");

        // when
        ControlledAgentArtifact artifact =
                ControlledAgentLocator.locate(List.of("-javaagent:\"" + agent + "\""));

        // then
        assertThat(artifact.path()).isEqualTo(agent.toRealPath());
    }

    @Test
    void absenceOfAnIdentityBearingAgentFailsWithEvidenceMissing() throws IOException {
        // given
        Path foreignAgent = foreignAgent("jacocoagent.jar");

        // when
        RuntimeEvidenceAttestationException failure =
                locateFailure(List.of("-javaagent:" + foreignAgent));

        // then
        assertThat(failure.code()).isEqualTo(AttestationFailureCode.DISTRIBUTION_EVIDENCE_MISSING);
    }

    @Test
    void launchWithoutAnyJavaAgentFailsWithEvidenceMissing() {
        // given
        List<String> inputArguments = List.of("-Xmx512m", "-Dplatform.tracing.enabled=true");

        // when
        RuntimeEvidenceAttestationException failure = locateFailure(inputArguments);

        // then
        assertThat(failure.code()).isEqualTo(AttestationFailureCode.DISTRIBUTION_EVIDENCE_MISSING);
    }

    @Test
    void twoIdentityBearingAgentsFailWithDistributionMismatch() throws IOException {
        // given
        Path pinnedAgent = controlledAgent("controlled-agent.jar");
        Path shadowAgent = controlledAgent("controlled-agent-copy.jar");

        // when
        RuntimeEvidenceAttestationException failure = locateFailure(List.of(
                "-javaagent:" + pinnedAgent,
                "-javaagent:" + shadowAgent));

        // then
        assertThat(failure.code()).isEqualTo(AttestationFailureCode.DISTRIBUTION_MISMATCH);
    }

    @Test
    void missingAgentFileFailsWithEvidenceMissing() {
        // given
        Path absent = temporaryDirectory.resolve("absent-agent.jar");

        // when
        RuntimeEvidenceAttestationException failure = locateFailure(List.of("-javaagent:" + absent));

        // then
        assertThat(failure.code()).isEqualTo(AttestationFailureCode.DISTRIBUTION_EVIDENCE_MISSING);
        assertThat(failure).hasCauseInstanceOf(IOException.class);
    }

    @Test
    void unreadableArchiveFailsWithEvidenceMissing() throws IOException {
        // given
        Path corrupted = temporaryDirectory.resolve("corrupted-agent.jar");
        Files.writeString(corrupted, "not a zip archive", StandardCharsets.UTF_8);

        // when
        RuntimeEvidenceAttestationException failure = locateFailure(List.of("-javaagent:" + corrupted));

        // then
        assertThat(failure.code()).isEqualTo(AttestationFailureCode.DISTRIBUTION_EVIDENCE_MISSING);
        assertThat(failure).hasCauseInstanceOf(IOException.class);
    }

    private Path controlledAgent(String fileName) throws IOException {
        Properties identity = new Properties();
        identity.setProperty("format.version", "1");
        identity.setProperty("distribution.group", "space.br1440.platform.starters.tracing");
        identity.setProperty("distribution.artifact", "platform-tracing-javaagent");
        identity.setProperty("distribution.version", VERSION);

        ByteArrayOutputStream identityBytes = new ByteArrayOutputStream();
        identity.store(identityBytes, null);

        Path agent = temporaryDirectory.resolve(fileName);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(agent))) {
            writeEntry(output, IDENTITY_ENTRY, identityBytes.toByteArray());
        }
        return agent;
    }

    private Path foreignAgent(String fileName) throws IOException {
        Path agent = temporaryDirectory.resolve(fileName);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(agent))) {
            writeEntry(output, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
        }
        return agent;
    }

    private static RuntimeEvidenceAttestationException locateFailure(List<String> inputArguments) {
        Throwable failure = catchThrowable(() -> ControlledAgentLocator.locate(inputArguments));
        assertThat(failure).isInstanceOf(RuntimeEvidenceAttestationException.class);
        return (RuntimeEvidenceAttestationException) failure;
    }

    private static void writeEntry(ZipOutputStream output, String name, byte[] bytes) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(bytes);
        output.closeEntry();
    }
}
