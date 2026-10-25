package space.br1440.platform.tracing.autoconfigure.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;
class AgentContractFailureMessagesTest {

    @ParameterizedTest
    @CsvSource({
            "1.2.0, 2.0.0",
            "2.0.0, 1.2.0"
    })
    void formatsVersionMismatch(String applicationVersion, String extensionVersion) {
        // given
        TracingDeploymentDiagnostics diagnostics = diagnostics(
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "EXTENSION_VERSION_MISMATCH",
                "READY",
                "contract",
                "digest",
                "5a3f3fe4-e8ea-4dc4-b040-5d355e906f3a",
                1L,
                "batch",
                extensionVersion
        );

        // when
        String message = AgentContractFailureMessages.reject(true, diagnostics, () -> applicationVersion);

        // then
        assertThat(message).isEqualTo(
                "Platform tracing startup rejected: runtimeState=EXTENSION_INCOMPATIBLE, reason=EXTENSION_VERSION_MISMATCH"
                        + ", applicationVersion=" + applicationVersion
                        + ", extensionVersion=" + extensionVersion
                        + ". "
                        + AgentContractFailureMessages.VERSION_MISMATCH_RELEASE_DETAIL
        );
    }

    @Test
    void formatsVersionMismatchWhenContractIdIsBlank() {
        // given
        TracingDeploymentDiagnostics diagnostics = diagnostics(
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "EXTENSION_VERSION_MISMATCH",
                "READY",
                "",
                "digest",
                "5a3f3fe4-e8ea-4dc4-b040-5d355e906f3a",
                1L,
                "batch",
                "9.9.9"
        );

        // when
        String message = AgentContractFailureMessages.reject(true, diagnostics, () -> "1.2.3");

        // then
        assertThat(message).isEqualTo(
                AgentContractFailureMessages.SUMMARY_TEMPLATE.formatted(
                        AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                        "EXTENSION_VERSION_MISMATCH")
                        + ", applicationVersion=1.2.3, extensionVersion=9.9.9. "
                        + AgentContractFailureMessages.VERSION_MISMATCH_RELEASE_DETAIL
        );
    }

    @Test
    void formatsGenerationInconsistentContextWithMissingBatch() {
        // given
        TracingDeploymentDiagnostics diagnostics = diagnostics(
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "READINESS_GENERATION_INCONSISTENT",
                "READY",
                "contract",
                "digest",
                ReadinessTestFixtures.AGENT_INSTANCE_ID,
                0L,
                "",
                "1.2.0"
        );

        // when
        String message = AgentContractFailureMessages.reject(true, diagnostics, () -> "1.2.0");

        // then
        assertThat(message).isEqualTo(
                AgentContractFailureMessages.SUMMARY_TEMPLATE.formatted(
                        AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                        "READINESS_GENERATION_INCONSISTENT")
                        + ", evidenceGeneration=0, batchId=<missing>. "
                        + AgentContractFailureMessages.READINESS_GENERATION_INVALID_EXPLANATION
        );
    }

    @Test
    void formatsGenerationInconsistentContextWithoutUuidsWhenAnchorsArePresent() {
        // given
        TracingDeploymentDiagnostics diagnostics = diagnostics(
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "READINESS_GENERATION_INCONSISTENT",
                "READY",
                "contract",
                "digest",
                "5a3f3fe4-e8ea-4dc4-b040-5d355e906f3a",
                0L,
                "batch",
                "1.2.0"
        );

        // when
        String message = AgentContractFailureMessages.reject(true, diagnostics, () -> "1.2.0");

        // then
        assertThat(message).isEqualTo(
                AgentContractFailureMessages.SUMMARY_TEMPLATE.formatted(
                        AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                        "READINESS_GENERATION_INCONSISTENT")
                        + ", evidenceGeneration=0. "
                        + AgentContractFailureMessages.READINESS_GENERATION_INVALID_EXPLANATION
        );
    }

    @Test
    void usesStarterFailureCodeAndDropsPrimaryWhenSnapshotFailureCodeDiffers() {
        // given
        AgentReadinessSnapshot snapshot = ReadinessTestFixtures.toBuilder(ReadinessTestFixtures.readySnapshot())
                .lifecycleState(new AgentReadinessSnapshot.LifecycleState(
                        "FAILED",
                        "FAILED_ROLLBACK_INCOMPLETE",
                        "ACTIVATION_FAILED",
                        Set.of(),
                        Set.of()))
                .build();
        TracingDeploymentDiagnostics diagnostics = TracingDeploymentDiagnostics.withAgentData(
                true,
                true,
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                snapshot,
                "READINESS_ANCHOR_CONTENT_DRIFT");

        // when
        String message = AgentContractFailureMessages.reject(true, diagnostics, () -> "1.2.0");

        // then
        assertThat(message).isEqualTo(
                AgentContractFailureMessages.SUMMARY_TEMPLATE.formatted(
                        AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                        "READINESS_ANCHOR_CONTENT_DRIFT")
                        + ", agentInstanceId=" + ReadinessTestFixtures.AGENT_INSTANCE_ID
                        + ", evidenceGeneration=1, batchId=" + ReadinessTestFixtures.BATCH_ID
                        + ". "
                        + AgentContractFailureMessages.DEFAULT_EXPLANATION_TEMPLATE.formatted("FAILED")
        );
    }

    @Test
    void formatsInitializingInconsistentContextWithUnexpectedBatch() {
        // given
        TracingDeploymentDiagnostics diagnostics = diagnostics(
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "EXTENSION_INITIALIZING_INCONSISTENT",
                "INITIALIZING",
                "contract",
                "digest",
                "5a3f3fe4-e8ea-4dc4-b040-5d355e906f3a",
                0L,
                "batch",
                "1.2.0"
        );

        // when
        String message = AgentContractFailureMessages.reject(true, diagnostics, () -> "1.2.0");

        // then
        assertThat(message).isEqualTo(
                AgentContractFailureMessages.SUMMARY_TEMPLATE.formatted(
                        AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                        "EXTENSION_INITIALIZING_INCONSISTENT")
                        + ", lifecycle=INITIALIZING, evidenceGeneration=0, batchId=<unexpected>. "
                        + AgentContractFailureMessages.EXTENSION_INITIALIZING_INCONSISTENT_EXPLANATION
        );
    }

    @Test
    void formatsInitializingInconsistentContextOmitsBatchWhenAbsent() {
        // given
        TracingDeploymentDiagnostics diagnostics = diagnostics(
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "EXTENSION_INITIALIZING_INCONSISTENT",
                "INITIALIZING",
                "contract",
                "digest",
                "5a3f3fe4-e8ea-4dc4-b040-5d355e906f3a",
                0L,
                "",
                "1.2.0"
        );

        // when
        String message = AgentContractFailureMessages.reject(true, diagnostics, () -> "1.2.0");

        // then
        assertThat(message).isEqualTo(
                AgentContractFailureMessages.SUMMARY_TEMPLATE.formatted(
                        AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                        "EXTENSION_INITIALIZING_INCONSISTENT")
                        + ", lifecycle=INITIALIZING, evidenceGeneration=0. "
                        + AgentContractFailureMessages.EXTENSION_INITIALIZING_INCONSISTENT_EXPLANATION
        );
    }

    @Test
    void formatsGenerationInvalidContextWithNegativeGenerationWithoutAnchors() {
        // given
        TracingDeploymentDiagnostics diagnostics = diagnostics(
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "READINESS_GENERATION_INVALID",
                "READY",
                "contract",
                "digest",
                "5a3f3fe4-e8ea-4dc4-b040-5d355e906f3a",
                -1L,
                "batch",
                "1.2.0"
        );

        // when
        String message = AgentContractFailureMessages.reject(true, diagnostics, () -> "1.2.0");

        // then
        assertThat(message).isEqualTo(
                AgentContractFailureMessages.SUMMARY_TEMPLATE.formatted(
                        AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                        "READINESS_GENERATION_INVALID")
                        + ", evidenceGeneration=-1. "
                        + AgentContractFailureMessages.READINESS_GENERATION_INVALID_EXPLANATION
        );
    }

    @Test
    void formatsAnchorDriftContextWithFullObservedAnchor() {
        // given
        TracingDeploymentDiagnostics diagnostics = diagnostics(
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "READINESS_ANCHOR_CONTENT_DRIFT",
                "READY",
                "contract",
                "digest",
                "5a3f3fe4-e8ea-4dc4-b040-5d355e906f3a",
                1L,
                "batch",
                "1.2.0"
        );

        // when
        String message = AgentContractFailureMessages.reject(true, diagnostics, () -> "1.2.0");

        // then
        assertThat(message).isEqualTo(
                AgentContractFailureMessages.SUMMARY_TEMPLATE.formatted(
                        AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                        "READINESS_ANCHOR_CONTENT_DRIFT")
                        + ", agentInstanceId=5a3f3fe4-e8ea-4dc4-b040-5d355e906f3a, evidenceGeneration=1, batchId=batch. "
                        + AgentContractFailureMessages.DEFAULT_EXPLANATION_TEMPLATE.formatted("READY")
        );
    }

    @Test
    void includesSchemaIdMismatchContext() {
        // given
        TracingDeploymentDiagnostics diagnostics = diagnostics(
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "READINESS_SCHEMA_ID_MISMATCH",
                "READY",
                "another-contract",
                "digest",
                "5a3f3fe4-e8ea-4dc4-b040-5d355e906f3a",
                1L,
                "batch",
                "1.2.0"
        );

        // when
        String message = AgentContractFailureMessages.reject(true, diagnostics, () -> "1.2.0");

        // then
        assertThat(message).isEqualTo(
                AgentContractFailureMessages.SUMMARY_TEMPLATE.formatted(
                        AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                        "READINESS_SCHEMA_ID_MISMATCH")
                        + ", expectedContractId=" + JmxWireContract.SCHEMA_ID
                        + ", observedContractId=another-contract. "
                        + AgentContractFailureMessages.READINESS_SCHEMA_MISMATCH_EXPLANATION
        );
    }

    @Test
    void includesSchemaDigestMismatchContext() {
        // given
        TracingDeploymentDiagnostics diagnostics = diagnostics(
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "READINESS_SCHEMA_DIGEST_MISMATCH",
                "READY",
                JmxWireContract.SCHEMA_ID,
                "0".repeat(64),
                "5a3f3fe4-e8ea-4dc4-b040-5d355e906f3a",
                1L,
                "batch",
                "1.2.0"
        );

        // when
        String message = AgentContractFailureMessages.reject(true, diagnostics, () -> "1.2.0");

        // then
        assertThat(message).isEqualTo(
                AgentContractFailureMessages.SUMMARY_TEMPLATE.formatted(
                        AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                        "READINESS_SCHEMA_DIGEST_MISMATCH")
                        + ", expectedSchemaDigest=" + JmxWireContract.SCHEMA_DIGEST_SHA256
                        + ", observedSchemaDigest=" + "0".repeat(64)
                        + ". "
                        + AgentContractFailureMessages.READINESS_SCHEMA_MISMATCH_EXPLANATION
        );
    }

    @Test
    void printsMissingObservedContractId() {
        // given
        TracingDeploymentDiagnostics diagnostics = diagnostics(
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "READINESS_SCHEMA_ID_MISMATCH",
                "READY",
                "",
                "digest",
                "5a3f3fe4-e8ea-4dc4-b040-5d355e906f3a",
                1L,
                "batch",
                "1.2.0"
        );

        // when
        String message = AgentContractFailureMessages.reject(true, diagnostics, () -> "1.2.0");

        // then
        assertThat(message).isEqualTo(
                AgentContractFailureMessages.SUMMARY_TEMPLATE.formatted(
                        AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                        "READINESS_SCHEMA_ID_MISMATCH")
                        + ", expectedContractId=" + JmxWireContract.SCHEMA_ID
                        + ", observedContractId=<missing>. "
                        + AgentContractFailureMessages.READINESS_SCHEMA_MISMATCH_EXPLANATION
        );
    }

    @Test
    void formatsMixedGenerationWithoutSnapshotAnchors() {
        // given
        TracingDeploymentDiagnostics diagnostics = TracingDeploymentDiagnostics.withoutAgentData(
                true,
                true,
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "READINESS_MIXED_GENERATION"
        );

        // when
        String message = AgentContractFailureMessages.reject(true, diagnostics, () -> "1.2.0");

        // then
        assertThat(message).isEqualTo(
                AgentContractFailureMessages.SUMMARY_TEMPLATE.formatted(
                        AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                        "READINESS_MIXED_GENERATION")
                        + ". "
                        + AgentContractFailureMessages.READINESS_MIXED_GENERATION_EXPLANATION
        );
    }

    @Test
    void keepsUnknownReasonAndLifecycleFallback() {
        // given
        TracingDeploymentDiagnostics diagnostics = diagnostics(
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "REMOTE_JMX_MANAGEMENT_AGENT_CONFIGURED",
                "FAILED",
                "contract",
                "digest",
                "5a3f3fe4-e8ea-4dc4-b040-5d355e906f3a",
                1L,
                "batch",
                "1.2.0"
        );

        // when
        String message = AgentContractFailureMessages.reject(true, diagnostics, () -> "1.2.0");

        // then
        assertThat(message).isEqualTo(
                AgentContractFailureMessages.SUMMARY_TEMPLATE.formatted(
                        AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                        "REMOTE_JMX_MANAGEMENT_AGENT_CONFIGURED")
                        + ", lifecycle=FAILED. "
                        + AgentContractFailureMessages.DEFAULT_EXPLANATION_TEMPLATE.formatted("FAILED")
        );
    }

    @Test
    void rejectOmitsContextWhenReadinessEndpointIsAbsent() {
        // given
        TracingDeploymentDiagnostics diagnostics = TracingDeploymentDiagnostics.withoutAgentData(
                true,
                false,
                AgentRuntimeState.AGENT_MISSING,
                "CONTROLLED_AGENT_MISSING"
        );

        // when
        String message = AgentContractFailureMessages.reject(true, diagnostics, () -> "1.2.3");

        // then
        assertThat(message).isEqualTo(
                AgentContractFailureMessages.SUMMARY_TEMPLATE.formatted(
                        AgentRuntimeState.AGENT_MISSING,
                        "CONTROLLED_AGENT_MISSING")
                        + ". "
                        + AgentContractFailureMessages.CONTROLLED_AGENT_MISSING_EXPLANATION
        );
    }

    @Test
    void rejectUsesUnspecifiedContractFailureWhenFailureCodeIsBlank() {
        // given
        TracingDeploymentDiagnostics diagnostics = TracingDeploymentDiagnostics.withoutAgentData(
                false,
                false,
                AgentRuntimeState.DISABLED,
                "");

        // when
        String message = AgentContractFailureMessages.reject(false, diagnostics, () -> "1.2.3");

        // then
        assertThat(message).isEqualTo(
                AgentContractFailureMessages.SUMMARY_TEMPLATE.formatted(
                        AgentRuntimeState.DISABLED,
                        "UNSPECIFIED_CONTRACT_FAILURE")
                        + ". "
                        + AgentContractFailureMessages.UNSPECIFIED_CONTRACT_FAILURE_TEMPLATE.formatted(
                                AgentRuntimeState.DISABLED,
                                false)
        );
    }

    @Test
    void includesPrimaryFailureCodeWhenEffectiveFailureCodeWasEscalated() {
        // given
        TracingDeploymentDiagnostics diagnostics = diagnostics(
                AgentRuntimeState.EXTENSION_FAILED,
                "FAILED_ROLLBACK_INCOMPLETE",
                "ACTIVATION_FAILED",
                "FAILED",
                "contract",
                "digest",
                "5a3f3fe4-e8ea-4dc4-b040-5d355e906f3a",
                1L,
                "batch",
                "1.2.0"
        );

        // when
        String message = AgentContractFailureMessages.reject(true, diagnostics, () -> "1.2.0");

        // then
        assertThat(message).isEqualTo(
                AgentContractFailureMessages.SUMMARY_TEMPLATE.formatted(
                        AgentRuntimeState.EXTENSION_FAILED,
                        "FAILED_ROLLBACK_INCOMPLETE")
                        + ", primaryFailureCode=ACTIVATION_FAILED, lifecycle=FAILED. "
                        + AgentContractFailureMessages.DEFAULT_EXPLANATION_TEMPLATE.formatted("FAILED")
        );
    }

    @Test
    void explainsShutdownCleanupFailure() {
        // given
        TracingDeploymentDiagnostics diagnostics = diagnostics(
                AgentRuntimeState.EXTENSION_FAILED,
                "SHUTDOWN_CLEANUP_FAILED",
                "FAILED",
                "contract",
                "digest",
                "5a3f3fe4-e8ea-4dc4-b040-5d355e906f3a",
                2L,
                "batch",
                "1.2.0"
        );

        // when
        String message = AgentContractFailureMessages.reject(true, diagnostics, () -> "1.2.0");

        // then
        assertThat(message).isEqualTo(
                AgentContractFailureMessages.SUMMARY_TEMPLATE.formatted(
                        AgentRuntimeState.EXTENSION_FAILED, "SHUTDOWN_CLEANUP_FAILED")
                        + ", lifecycle=FAILED. "
                        + AgentContractFailureMessages.SHUTDOWN_CLEANUP_FAILED_EXPLANATION
        );
    }

    @Test
    void reportsReadyFailureCodeWithoutInventingObservedValue() {
        // given
        TracingDeploymentDiagnostics diagnostics = diagnostics(
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "EXTENSION_READY_WITH_FAILURE_CODE",
                "READY",
                "contract",
                "digest",
                "5a3f3fe4-e8ea-4dc4-b040-5d355e906f3a",
                1L,
                "batch",
                "1.2.0"
        );

        // when
        String message = AgentContractFailureMessages.reject(true, diagnostics, () -> "1.2.0");

        // then
        assertThat(message).isEqualTo(
                AgentContractFailureMessages.SUMMARY_TEMPLATE.formatted(
                        AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                        "EXTENSION_READY_WITH_FAILURE_CODE")
                        + ", lifecycle=READY. "
                        + AgentContractFailureMessages.EXTENSION_READY_WITH_FAILURE_CODE_EXPLANATION
        );
    }

    @ParameterizedTest
    @MethodSource("classifierReasonCodes")
    void everyClassifierReasonCodeHasExplicitExplanation(Field reasonCodeField) throws IllegalAccessException {
        // given
        String reasonCode = (String) reasonCodeField.get(null);
        TracingDeploymentDiagnostics diagnostics = diagnostics(
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                reasonCode,
                "READY",
                "contract",
                "digest",
                "5a3f3fe4-e8ea-4dc4-b040-5d355e906f3a",
                1L,
                "batch",
                "1.2.0"
        );

        // when
        String message = AgentContractFailureMessages.reject(true, diagnostics, () -> "1.2.0");

        // then
        assertThat(reasonCode).isEqualTo(reasonCodeField.getName());
        assertThat(message).doesNotEndWith(AgentContractFailureMessages.DEFAULT_EXPLANATION_TEMPLATE.formatted("READY"));
    }

    private static Stream<Field> classifierReasonCodes() {
        return Arrays.stream(AgentNotReadyReasonCodes.class.getDeclaredFields())
                .filter(field -> Modifier.isStatic(field.getModifiers())
                        && Modifier.isFinal(field.getModifiers())
                        && field.getType() == String.class);
    }

    @SuppressWarnings("SameParameterValue")
    private static TracingDeploymentDiagnostics diagnostics(AgentRuntimeState runtimeState,
                                                            String failureCode,
                                                            String lifecycle,
                                                            String contractId,
                                                            String schemaDigest,
                                                            String agentInstanceId,
                                                            long evidenceGeneration,
                                                            String batchId,
                                                            String extensionVersion) {
        return diagnostics(
                runtimeState,
                failureCode,
                failureCode,
                lifecycle,
                contractId,
                schemaDigest,
                agentInstanceId,
                evidenceGeneration,
                batchId,
                extensionVersion
        );
    }

    @SuppressWarnings("SameParameterValue")
    private static TracingDeploymentDiagnostics diagnostics(AgentRuntimeState runtimeState,
                                                            String failureCode,
                                                            String primaryFailureCode,
                                                            String lifecycle,
                                                            String contractId,
                                                            String schemaDigest,
                                                            String agentInstanceId,
                                                            long evidenceGeneration,
                                                            String batchId,
                                                            String extensionVersion) {
        return TracingDeploymentDiagnostics.withAgentData(
                true,
                true,
                runtimeState,
                new AgentReadinessSnapshot(
                        new AgentReadinessSnapshot.ContractIdentity(contractId, schemaDigest),
                        new AgentReadinessSnapshot.AnchorIdentity(agentInstanceId, evidenceGeneration, batchId),
                        new AgentReadinessSnapshot.LifecycleState(
                                lifecycle,
                                failureCode,
                                primaryFailureCode,
                                Set.of(),
                                Set.of()
                        ),
                        new AgentReadinessSnapshot.DistributionIdentity(
                                "a".repeat(64),
                                "2.30.0",
                                extensionVersion
                        ),
                        new AgentReadinessSnapshot.ClassloaderTopology("agent-loader@1", "sdk-loader@2")
                ),
                failureCode
        );
    }
}
