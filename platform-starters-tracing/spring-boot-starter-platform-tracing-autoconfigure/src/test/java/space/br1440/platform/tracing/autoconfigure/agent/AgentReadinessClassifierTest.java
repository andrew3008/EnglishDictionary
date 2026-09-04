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
    void preservesFailedLifecycleCodeAsStickyFailure() {
        // given
        AgentReadinessSnapshot snapshot = ReadinessTestFixtures.failedSnapshot("DISTRIBUTION_MISMATCH");

        // when
        AgentReadinessClassifier.Decision decision =
                AgentReadinessClassifier.decide(snapshot, ReadinessTestFixtures.APPLICATION_VERSION);

        // then
        assertDecision(decision, AgentRuntimeState.EXTENSION_FAILED, "DISTRIBUTION_MISMATCH");
        assertThat(AgentReadinessClassifier.sticky(decision)).isTrue();
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
                        "READY", "", ReadinessTestFixtures.COMPLETE_CAPABILITIES, Set.of("SAFE_EXPORTER_INSTALLED")))
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
                        "READY", "", capabilities, Set.of("SAFE_EXPORTER_INSTALLED")))
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
                .anchorIdentity(new AgentReadinessSnapshot.AnchorIdentity("", 0L, ""))
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
    void rejectsDevelopmentExtensionVersion() {
        // given
        AgentReadinessSnapshot snapshot = builder()
                .distributionIdentity(new AgentReadinessSnapshot.DistributionIdentity(
                        ReadinessTestFixtures.SHA256, "2.30.0", "development"))
                .build();

        // when
        AgentReadinessClassifier.Decision decision =
                AgentReadinessClassifier.decide(snapshot, "development");

        // then
        assertDecision(
                decision,
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                "DEVELOPMENT_VERSION_FORBIDDEN");
    }

    @Test
    void rejectsUnknownLifecycle() {
        // given
        AgentReadinessSnapshot snapshot = builder()
                .lifecycleState(new AgentReadinessSnapshot.LifecycleState(
                        "UNKNOWN", "", ReadinessTestFixtures.COMPLETE_CAPABILITIES, Set.of()))
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
