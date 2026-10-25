package space.br1440.platform.tracing.autoconfigure.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

class AgentReadinessClassifierTest {

    @Test
    void acceptsReadySnapshotWithCompleteGeneratedEvidence() {
        // given
        AgentReadinessSnapshot snapshot = ReadinessTestFixtures.readySnapshot();

        // when
        AgentReadinessClassifier.Decision decision =
                AgentReadinessClassifier.decide(snapshot, ReadinessTestFixtures.APPLICATION_VERSION);

        // then
        assertDecision(decision, AgentRuntimeState.AGENT_READY, "");
    }

    @Test
    void acceptsCoherentInitializingSnapshot() {
        // given
        AgentReadinessSnapshot snapshot = ReadinessTestFixtures.initializingSnapshot();

        // when
        AgentReadinessClassifier.Decision decision =
                AgentReadinessClassifier.decide(snapshot, ReadinessTestFixtures.APPLICATION_VERSION);

        // then
        assertDecision(decision, AgentRuntimeState.EXTENSION_INITIALIZING, "");
    }

    @Test
    void rejectsInitializingSnapshotWithEffectiveFailureCode() {
        // given
        AgentReadinessSnapshot initializing = ReadinessTestFixtures.initializingSnapshot();
        AgentReadinessSnapshot snapshot = ReadinessTestFixtures.toBuilder(initializing)
                .lifecycleState(new AgentReadinessSnapshot.LifecycleState(
                        "INITIALIZING",
                        "PREPARATION_FAILED",
                        "",
                        initializing.lifecycleState().capabilities(),
                        initializing.lifecycleState().missingRequired()))
                .build();

        // when
        AgentReadinessClassifier.Decision decision =
                AgentReadinessClassifier.decide(snapshot, ReadinessTestFixtures.APPLICATION_VERSION);

        // then
        assertDecision(
                decision,
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "EXTENSION_INITIALIZING_INCONSISTENT");
    }

    @Test
    void rejectsInitializingSnapshotWithPrimaryFailureCode() {
        // given
        AgentReadinessSnapshot initializing = ReadinessTestFixtures.initializingSnapshot();
        AgentReadinessSnapshot snapshot = ReadinessTestFixtures.toBuilder(initializing)
                .lifecycleState(new AgentReadinessSnapshot.LifecycleState(
                        "INITIALIZING",
                        "",
                        "PREPARATION_FAILED",
                        initializing.lifecycleState().capabilities(),
                        initializing.lifecycleState().missingRequired()))
                .build();

        // when
        AgentReadinessClassifier.Decision decision =
                AgentReadinessClassifier.decide(snapshot, ReadinessTestFixtures.APPLICATION_VERSION);

        // then
        assertDecision(
                decision,
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "EXTENSION_INITIALIZING_INCONSISTENT");
    }

    @Test
    void preservesFailedLifecycleCodeAsStickyFailure() {
        // given
        AgentReadinessSnapshot snapshot = ReadinessTestFixtures.failedSnapshot("DISTRIBUTION_MISMATCH");

        // when
        AgentReadinessClassifier.Decision decision =
                AgentReadinessClassifier.decide(snapshot, ReadinessTestFixtures.APPLICATION_VERSION);

        // then
        assertDecision(decision, AgentRuntimeState.EXTENSION_FAILED, "DISTRIBUTION_MISMATCH");
        assertThat(AgentReadinessClassifier.sticky(decision.state())).isTrue();
    }

    @Test
    void acceptsRollbackEscalationAndUsesEffectiveFailureCode() {
        // given
        AgentReadinessSnapshot snapshot = ReadinessTestFixtures.failedSnapshot(
                "ACTIVATION_FAILED",
                "FAILED_ROLLBACK_INCOMPLETE");

        // when
        AgentReadinessClassifier.Decision decision =
                AgentReadinessClassifier.decide(snapshot, ReadinessTestFixtures.APPLICATION_VERSION);

        // then
        assertDecision(
                decision,
                AgentRuntimeState.EXTENSION_FAILED,
                "FAILED_ROLLBACK_INCOMPLETE");
    }

    @Test
    void rejectsFailedSnapshotWithoutBothFailureCodesBeforePairValidation() {
        // given
        AgentReadinessSnapshot snapshot = ReadinessTestFixtures.failedSnapshot("", "");

        // when
        AgentReadinessClassifier.Decision decision =
                AgentReadinessClassifier.decide(snapshot, ReadinessTestFixtures.APPLICATION_VERSION);

        // then
        assertDecision(
                decision,
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "EXTENSION_FAILED_WITHOUT_CODE");
    }

    @Test
    void rejectsFailedSnapshotWithoutPrimaryFailureCode() {
        // given
        AgentReadinessSnapshot snapshot = ReadinessTestFixtures.failedSnapshot("", "ACTIVATION_FAILED");

        // when
        AgentReadinessClassifier.Decision decision =
                AgentReadinessClassifier.decide(snapshot, ReadinessTestFixtures.APPLICATION_VERSION);

        // then
        assertDecision(
                decision,
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "EXTENSION_FAILED_CODE_PAIR_INCONSISTENT");
    }

    @Test
    void rejectsFailedSnapshotWithoutEffectiveFailureCode() {
        // given
        AgentReadinessSnapshot snapshot = ReadinessTestFixtures.failedSnapshot("ACTIVATION_FAILED", "");

        // when
        AgentReadinessClassifier.Decision decision =
                AgentReadinessClassifier.decide(snapshot, ReadinessTestFixtures.APPLICATION_VERSION);

        // then
        assertDecision(
                decision,
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "EXTENSION_FAILED_CODE_PAIR_INCONSISTENT");
    }

    @Test
    void rejectsFailedSnapshotWithUnrelatedEffectiveFailureCode() {
        // given
        AgentReadinessSnapshot snapshot = ReadinessTestFixtures.failedSnapshot(
                "ACTIVATION_FAILED",
                "DISTRIBUTION_MISMATCH");

        // when
        AgentReadinessClassifier.Decision decision =
                AgentReadinessClassifier.decide(snapshot, ReadinessTestFixtures.APPLICATION_VERSION);

        // then
        assertDecision(
                decision,
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "EXTENSION_FAILED_CODE_PAIR_INCONSISTENT");
    }

    @Test
    void rejectsReversedRollbackEscalation() {
        // given
        AgentReadinessSnapshot snapshot = ReadinessTestFixtures.failedSnapshot(
                "FAILED_ROLLBACK_INCOMPLETE",
                "ACTIVATION_FAILED");

        // when
        AgentReadinessClassifier.Decision decision =
                AgentReadinessClassifier.decide(snapshot, ReadinessTestFixtures.APPLICATION_VERSION);

        // then
        assertDecision(
                decision,
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "EXTENSION_FAILED_CODE_PAIR_INCONSISTENT");
    }

    @Test
    void acceptsFailedSnapshotAfterBatchPublicationWithCompleteAnchor() {
        // given
        AgentReadinessSnapshot snapshot = ReadinessTestFixtures.toBuilder(ReadinessTestFixtures.failedSnapshot("ACTIVATION_FAILED"))
                .anchorIdentity(new AgentReadinessSnapshot.AnchorIdentity(
                        ReadinessTestFixtures.AGENT_INSTANCE_ID, 2L, ReadinessTestFixtures.BATCH_ID))
                .build();

        // when
        AgentReadinessClassifier.Decision decision =
                AgentReadinessClassifier.decide(snapshot, ReadinessTestFixtures.APPLICATION_VERSION);

        // then
        assertDecision(decision, AgentRuntimeState.EXTENSION_FAILED, "ACTIVATION_FAILED");
    }

    @Test
    void rejectsFailedSnapshotCarryingBatchBeforePublication() {
        // given
        AgentReadinessSnapshot snapshot = ReadinessTestFixtures.toBuilder(ReadinessTestFixtures.failedSnapshot("ACTIVATION_FAILED"))
                .anchorIdentity(new AgentReadinessSnapshot.AnchorIdentity(
                        ReadinessTestFixtures.AGENT_INSTANCE_ID, 0L, ReadinessTestFixtures.BATCH_ID))
                .build();

        // when
        AgentReadinessClassifier.Decision decision =
                AgentReadinessClassifier.decide(snapshot, ReadinessTestFixtures.APPLICATION_VERSION);

        // then
        assertDecision(
                decision,
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "READINESS_GENERATION_INCONSISTENT");
    }

    @Test
    void rejectsFailedSnapshotAfterPublicationWithoutBatchId() {
        // given
        AgentReadinessSnapshot snapshot = ReadinessTestFixtures.toBuilder(ReadinessTestFixtures.failedSnapshot("ACTIVATION_FAILED"))
                .anchorIdentity(new AgentReadinessSnapshot.AnchorIdentity(
                        ReadinessTestFixtures.AGENT_INSTANCE_ID, 2L, ""))
                .build();

        // when
        AgentReadinessClassifier.Decision decision =
                AgentReadinessClassifier.decide(snapshot, ReadinessTestFixtures.APPLICATION_VERSION);

        // then
        assertDecision(
                decision,
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "READINESS_GENERATION_INCONSISTENT");
    }

    @Test
    void rejectsSchemaDigestMismatch() {
        // given
        AgentReadinessSnapshot snapshot = builder()
                .contractIdentity(new AgentReadinessSnapshot.ContractIdentity(
                        JmxWireContract.SCHEMA_ID, "0".repeat(64)))
                .build();

        // when
        AgentReadinessClassifier.Decision decision =
                AgentReadinessClassifier.decide(snapshot, ReadinessTestFixtures.APPLICATION_VERSION);

        // then
        assertDecision(
                decision,
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "READINESS_SCHEMA_DIGEST_MISMATCH");
    }

    @Test
    void rejectsProducerMissingRequiredThatDiffersFromIndependentRecomputation() {
        // given
        AgentReadinessSnapshot snapshot = builder()
                .lifecycleState(new AgentReadinessSnapshot.LifecycleState(
                        "READY",
                        "",
                        "",
                        ReadinessTestFixtures.COMPLETE_CAPABILITIES,
                        Set.of("SAFE_EXPORTER_INSTALLED")))
                .build();

        // when
        AgentReadinessClassifier.Decision decision =
                AgentReadinessClassifier.decide(snapshot, ReadinessTestFixtures.APPLICATION_VERSION);

        // then
        assertDecision(
                decision,
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "READINESS_MISSING_REQUIRED_MISMATCH");
    }

    @Test
    void reportsMissingGeneratedCapability() {
        // given
        Set<String> capabilities = new LinkedHashSet<>(ReadinessTestFixtures.COMPLETE_CAPABILITIES);
        capabilities.remove("SAFE_EXPORTER_INSTALLED");
        AgentReadinessSnapshot snapshot = builder()
                .lifecycleState(new AgentReadinessSnapshot.LifecycleState(
                        "READY", "", "", capabilities, Set.of("SAFE_EXPORTER_INSTALLED")))
                .build();

        // when
        AgentReadinessClassifier.Decision decision =
                AgentReadinessClassifier.decide(snapshot, ReadinessTestFixtures.APPLICATION_VERSION);

        // then
        assertDecision(decision, AgentRuntimeState.CAPABILITY_MISSING, "REQUIRED_CAPABILITY_MISSING");
    }

    @Test
    void rejectsReadySnapshotWithMixedGenerationIdentity() {
        // given
        AgentReadinessSnapshot snapshot = builder()
                .anchorIdentity(new AgentReadinessSnapshot.AnchorIdentity(
                        ReadinessTestFixtures.AGENT_INSTANCE_ID,
                        0L,
                        ""
                ))
                .build();

        // when
        AgentReadinessClassifier.Decision decision =
                AgentReadinessClassifier.decide(snapshot, ReadinessTestFixtures.APPLICATION_VERSION);

        // then
        assertDecision(
                decision,
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "READINESS_GENERATION_INCONSISTENT");
    }

    @Test
    void rejectsReadySnapshotWithoutPublishedBatchId() {
        // given
        AgentReadinessSnapshot snapshot = builder()
                .anchorIdentity(new AgentReadinessSnapshot.AnchorIdentity(
                        ReadinessTestFixtures.AGENT_INSTANCE_ID, 1L, ""))
                .build();

        // when
        AgentReadinessClassifier.Decision decision =
                AgentReadinessClassifier.decide(snapshot, ReadinessTestFixtures.APPLICATION_VERSION);

        // then
        assertDecision(
                decision,
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "READINESS_GENERATION_INCONSISTENT");
    }

    @Test
    void rejectsMalformedDistributionSha() {
        // given
        AgentReadinessSnapshot snapshot = builder()
                .distributionIdentity(new AgentReadinessSnapshot.DistributionIdentity(
                        "not-a-sha", "2.30.0", ReadinessTestFixtures.APPLICATION_VERSION))
                .build();

        // when
        AgentReadinessClassifier.Decision decision =
                AgentReadinessClassifier.decide(snapshot, ReadinessTestFixtures.APPLICATION_VERSION);

        // then
        assertDecision(
                decision,
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "DISTRIBUTION_EVIDENCE_INVALID");
    }

    @Test
    void rejectsSdkDefinedByTheRealApplicationLoader() {
        // given
        AgentReadinessSnapshot snapshot = builder()
                .classloaderTopology(new AgentReadinessSnapshot.ClassloaderTopology(
                        "agent-loader@1", loaderDescription(AgentReadinessClassifier.class.getClassLoader())))
                .build();

        // when
        AgentReadinessClassifier.Decision decision =
                AgentReadinessClassifier.decide(snapshot, ReadinessTestFixtures.APPLICATION_VERSION);

        // then
        assertDecision(decision, AgentRuntimeState.DUAL_SDK_DETECTED, "DUAL_SDK_COLLISION");
    }

    @Test
    void rejectsExtensionDefinedByTheRealApplicationLoader() {
        // given
        AgentReadinessSnapshot snapshot = builder()
                .classloaderTopology(new AgentReadinessSnapshot.ClassloaderTopology(
                        loaderDescription(AgentReadinessClassifier.class.getClassLoader()), "sdk-loader@2"))
                .build();

        // when
        AgentReadinessClassifier.Decision decision =
                AgentReadinessClassifier.decide(snapshot, ReadinessTestFixtures.APPLICATION_VERSION);

        // then
        assertDecision(decision, AgentRuntimeState.EXTENSION_INCOMPATIBLE, "EXTENSION_LINKAGE_ERROR");
    }

    @Test
    void rejectsLoaderDescriptionThatDoesNotFollowTheWireShape() {
        // given
        AgentReadinessSnapshot snapshot = builder()
                .classloaderTopology(new AgentReadinessSnapshot.ClassloaderTopology(
                        "agent-loader@1", "sdk-loader-without-identity"))
                .build();

        // when
        AgentReadinessClassifier.Decision decision =
                AgentReadinessClassifier.decide(snapshot, ReadinessTestFixtures.APPLICATION_VERSION);

        // then
        assertDecision(
                decision,
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "CLASSLOADER_TOPOLOGY_INVALID");
    }

    @Test
    void rejectsBlankDistributionVersions() {
        // given
        AgentReadinessSnapshot blankUpstream = builder()
                .distributionIdentity(new AgentReadinessSnapshot.DistributionIdentity(
                        ReadinessTestFixtures.SHA256, "", ReadinessTestFixtures.APPLICATION_VERSION))
                .build();
        AgentReadinessSnapshot blankExtension = builder()
                .distributionIdentity(new AgentReadinessSnapshot.DistributionIdentity(
                        ReadinessTestFixtures.SHA256, "2.30.0", ""))
                .build();

        // when
        AgentReadinessClassifier.Decision upstreamDecision =
                AgentReadinessClassifier.decide(blankUpstream, ReadinessTestFixtures.APPLICATION_VERSION);
        AgentReadinessClassifier.Decision extensionDecision =
                AgentReadinessClassifier.decide(blankExtension, ReadinessTestFixtures.APPLICATION_VERSION);

        // then
        assertDecision(upstreamDecision, AgentRuntimeState.EXTENSION_INCOMPATIBLE, "DISTRIBUTION_EVIDENCE_INVALID");
        assertDecision(extensionDecision, AgentRuntimeState.EXTENSION_INCOMPATIBLE, "DISTRIBUTION_EVIDENCE_INVALID");
    }

    @Test
    void rejectsUnknownLifecycle() {
        // given
        AgentReadinessSnapshot snapshot = builder()
                .lifecycleState(new AgentReadinessSnapshot.LifecycleState(
                        "UNKNOWN", "", "", ReadinessTestFixtures.COMPLETE_CAPABILITIES, Set.of()))
                .build();

        // when
        AgentReadinessClassifier.Decision decision =
                AgentReadinessClassifier.decide(snapshot, ReadinessTestFixtures.APPLICATION_VERSION);

        // then
        assertDecision(
                decision,
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "EXTENSION_LIFECYCLE_UNEXPECTED");
    }

    @Test
    void generatedLifecycleValuesAreRecognized() {
        // given
        Set<String> lifecycles = JmxWireContract.VALID_READINESS_LIFECYCLES;

        // when
        Set<String> unexpectedLifecycles = new LinkedHashSet<>();
        for (String lifecycle : lifecycles) {
            AgentReadinessSnapshot snapshot = builder()
                    .lifecycleState(new AgentReadinessSnapshot.LifecycleState(
                            lifecycle, "", "", ReadinessTestFixtures.COMPLETE_CAPABILITIES, Set.of()))
                    .build();

            AgentReadinessClassifier.Decision decision = AgentReadinessClassifier.decide(
                    snapshot,
                    ReadinessTestFixtures.APPLICATION_VERSION
            );

            if ("EXTENSION_LIFECYCLE_UNEXPECTED".equals(decision.reasonCode())) {
                unexpectedLifecycles.add(lifecycle);
            }
        }

        // then
        assertThat(unexpectedLifecycles).isEmpty();
    }

    @Test
    void rejectsReadySnapshotWithEffectiveFailureCode() {
        // given
        AgentReadinessSnapshot snapshot = builder()
                .lifecycleState(new AgentReadinessSnapshot.LifecycleState(
                        "READY",
                        "ACTIVATION_FAILED",
                        "",
                        ReadinessTestFixtures.COMPLETE_CAPABILITIES,
                        Set.of()))
                .build();

        // when
        AgentReadinessClassifier.Decision decision =
                AgentReadinessClassifier.decide(snapshot, ReadinessTestFixtures.APPLICATION_VERSION);

        // then
        assertDecision(
                decision,
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "EXTENSION_READY_WITH_FAILURE_CODE");
    }

    @Test
    void rejectsReadySnapshotWithPrimaryFailureCode() {
        // given
        AgentReadinessSnapshot snapshot = builder()
                .lifecycleState(new AgentReadinessSnapshot.LifecycleState(
                        "READY",
                        "",
                        "ACTIVATION_FAILED",
                        ReadinessTestFixtures.COMPLETE_CAPABILITIES,
                        Set.of()))
                .build();

        // when
        AgentReadinessClassifier.Decision decision =
                AgentReadinessClassifier.decide(snapshot, ReadinessTestFixtures.APPLICATION_VERSION);

        // then
        assertDecision(
                decision,
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "EXTENSION_READY_WITH_FAILURE_CODE");
    }

    private static ReadinessTestFixtures.Builder builder() {
        return ReadinessTestFixtures.toBuilder(ReadinessTestFixtures.readySnapshot());
    }

    private static void assertDecision(AgentReadinessClassifier.Decision decision,
                                       AgentRuntimeState state,
                                       String reasonCode) {
        assertThat(decision.state()).isEqualTo(state);
        assertThat(decision.reasonCode()).isEqualTo(reasonCode);
    }

    private static String loaderDescription(ClassLoader loader) {
        if (loader == null) {
            return "bootstrap";
        }

        String name = loader.getName();
        String stableName = (name == null || name.isBlank()) ? loader.getClass().getName() : name;
        return stableName + "@" + Integer.toHexString(System.identityHashCode(loader));
    }
}
