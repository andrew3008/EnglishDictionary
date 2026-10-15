package space.br1440.platform.tracing.jmx.contract.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContractDocumentFactoryTest {

    @Test
    void loadsAndValidatesRealManifest() throws IOException, URISyntaxException {
        Path manifest = classpathManifest();
        ContractDocument contract = ContractDocumentFactory.load(manifest);
        assertThat(contract.schemaId()).isEqualTo("platform-tracing-jmx-wire-contract");
        assertThat(contract.schemaDigest()).hasSize(64);
        assertThat(contract.descriptorDigest()).hasSize(64);
    }

    @Test
    void rejectsEmptyYaml(@TempDir Path tempDir) throws IOException {
        Path empty = tempDir.resolve("empty.yaml");
        Files.writeString(empty, "");
        assertThatThrownBy(() -> ContractDocumentFactory.load(empty))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(exception ->
                        assertThat(((ContractValidationException) exception).code())
                                .isEqualTo("JMX_SCHEMA_EMPTY"));
    }

    @Test
    void rejectsInvalidObjectName(@TempDir Path tempDir) throws IOException, URISyntaxException {
        // given
        Path manifest = patchedManifest(
                tempDir,
                "settingsMutation: \"space.br1440.platform.tracing:type=Control,name=PlatformSettingsMutation,role=Mutation\"",
                "settingsMutation: \"not a valid object name!!\"");

        // when
        // then
        assertThatThrownBy(() -> ContractDocumentFactory.load(manifest))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(exception ->
                        assertThat(((ContractValidationException) exception).code())
                                .isEqualTo("JMX_SCHEMA_INVALID_OBJECT_NAME"));
    }

    @Test
    void rejectsDuplicateObjectNameValues(@TempDir Path tempDir) throws IOException, URISyntaxException {
        // given
        Path manifest = patchedManifest(
                tempDir,
                "settingsStatus: \"space.br1440.platform.tracing:type=Control,name=PlatformSettingsStatus,role=Observation\"",
                "settingsStatus: \"space.br1440.platform.tracing:type=Control,name=PlatformSettingsMutation,role=Mutation\""
        );

        // when
        // then
        assertThatThrownBy(() -> ContractDocumentFactory.load(manifest))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(exception ->
                        assertThat(((ContractValidationException) exception).code())
                                .isEqualTo("JMX_SCHEMA_OBJECT_NAME_COLLISION"));
    }

    @Test
    void rejectsReadinessEvidenceFieldMissingFromReadinessSnapshot(@TempDir Path tempDir)
            throws IOException, URISyntaxException {
        // given
        Path manifest = patchedManifest(
                tempDir,
                "  distributionFields:\n    - agentDistributionSha256\n    - upstreamAgentVersion\n    - extensionVersion",
                "  distributionFields:\n    - agentDistributionSha256\n    - unknownField"
        );

        // when
        // then
        assertThatThrownBy(() -> ContractDocumentFactory.load(manifest))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(exception ->
                        assertThat(((ContractValidationException) exception).code())
                                .isEqualTo("JMX_SCHEMA_READINESS_EVIDENCE_INVALID"));
    }

    @Test
    void rejectsLogicalByteBudgetReserve(@TempDir Path tempDir) throws IOException, URISyntaxException {
        // given
        Path manifest = patchedManifest(
                tempDir,
                "  maxLogicalBytes: 33492",
                "  maxLogicalBytes: 33493"
        );

        // when
        // then
        assertThatThrownBy(() -> ContractDocumentFactory.load(manifest))
                .isInstanceOf(ContractValidationException.class)
                .hasMessageContaining("must equal the minimum worst-case budget: 33492")
                .satisfies(exception ->
                        assertThat(((ContractValidationException) exception).code())
                                .isEqualTo("JMX_SCHEMA_LOGICAL_BUDGET_INVALID"));
    }

    private static Path classpathManifest() throws URISyntaxException {
        URL resource = ContractDocumentFactoryTest.class.getResource("/jmx-wire-contract.yaml");
        if (resource == null) {
            throw new IllegalStateException("Classpath resource /jmx-wire-contract.yaml is missing");
        }

        return Path.of(resource.toURI());
    }

    private static Path patchedManifest(Path tempDir, String from, String to) throws IOException, URISyntaxException {
        Path source = classpathManifest();
        String yaml = Files.readString(source).replace("\r\n", "\n");
        String patched = yaml.replace(from, to);
        if (patched.equals(yaml)) {
            throw new IllegalStateException("Manifest patch did not match: " + from);
        }

        Path target = tempDir.resolve("jmx-wire-contract.yaml");
        Files.writeString(target, patched);
        return target;
    }
}
