package space.br1440.platform.tracing.autoconfigure.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Pure classifier tests: no JMX, no clock, application version supplied explicitly by the caller.
 */
class AgentReadinessClassifierTest {

    private static final String APPLICATION_VERSION = "1.2.3";

    private static final Set<String> COMPLETE_CAPABILITIES = Set.of(
            "CONFIGURATION_LOADED",
            "PLATFORM_SAMPLER_INSTALLED",
            "REQUIRED_SPAN_PROCESSORS_INSTALLED",
            "SANITIZER_INSTALLED",
            "PROPAGATION_HOOKS_INSTALLED",
            "SAFE_EXPORTER_INSTALLED",
            "EXPORT_PATH_PROTECTED");

    @Test
    void readyRequiresCompleteConsistentSecureProfileAndExactVersionEquality() {
        AgentRuntimeState state = classify(snapshot(
                APPLICATION_VERSION, 1, "platform-agent-secure-v1", "READY", "",
                COMPLETE_CAPABILITIES),
                APPLICATION_VERSION);

        assertThat(state).isEqualTo(AgentRuntimeState.AGENT_READY);
    }

    @Test
    void developmentUnitSeamIsAcceptedOnlyWhenBothSidesAreExplicitlyDevelopment() {
        AgentRuntimeState state = classify(snapshot(
                "development", 1, "platform-agent-secure-v1", "READY", "",
                COMPLETE_CAPABILITIES),
                "development");

        assertThat(state).isEqualTo(AgentRuntimeState.AGENT_READY);
    }

    @Test
    void missingApplicationVersionIsFailClosedEvenWhenExtensionReportsDevelopment() {
        // No one-sided wildcard: a null application version rejects a "development" extension.
        AgentRuntimeState state = classify(snapshot(
                "development", 1, "platform-agent-secure-v1", "READY", "",
                COMPLETE_CAPABILITIES),
                null);

        assertThat(state).isEqualTo(AgentRuntimeState.EXTENSION_INCOMPATIBLE);
    }

    @Test
    void mismatchedNonDevelopmentVersionsAreRejected() {
        AgentReadinessClassifier.Decision decision = decide(snapshot(
                "9.9.9-not-matching", 1, "platform-agent-secure-v1", "READY", "",
                COMPLETE_CAPABILITIES),
                APPLICATION_VERSION);

        assertThat(decision.state()).isEqualTo(AgentRuntimeState.EXTENSION_INCOMPATIBLE);
        assertThat(decision.reasonCode()).isEqualTo("EXTENSION_VERSION_MISMATCH");
    }

    @Test
    void developmentExtensionAgainstPackagedApplicationVersionIsVersionMismatch() {
        AgentReadinessClassifier.Decision decision = decide(snapshot(
                "development", 1, "platform-agent-secure-v1", "READY", "",
                COMPLETE_CAPABILITIES),
                "1.2.0-SNAPSHOT");

        assertThat(decision.state()).isEqualTo(AgentRuntimeState.EXTENSION_INCOMPATIBLE);
        assertThat(decision.reasonCode()).isEqualTo("EXTENSION_VERSION_MISMATCH");
    }

    @Test
    void unsupportedProtocolVersionIsIncompatible() {
        AgentRuntimeState state = classify(snapshot(
                APPLICATION_VERSION, 999, "platform-agent-secure-v1", "READY", "",
                COMPLETE_CAPABILITIES),
                APPLICATION_VERSION);

        assertThat(state).isEqualTo(AgentRuntimeState.EXTENSION_INCOMPATIBLE);
    }

    @Test
    void unsupportedProfileIsIncompatible() {
        AgentRuntimeState state = classify(snapshot(
                APPLICATION_VERSION, 1, "unknown-profile", "READY", "",
                COMPLETE_CAPABILITIES),
                APPLICATION_VERSION);

        assertThat(state).isEqualTo(AgentRuntimeState.EXTENSION_INCOMPATIBLE);
    }

    @Test
    void initializingWithoutFailureCodeAndIncompleteCapabilitiesIsInitializing() {
        AgentRuntimeState state = classify(snapshot(
                APPLICATION_VERSION, 1, "platform-agent-secure-v1", "INITIALIZING", "",
                Set.of()),
                APPLICATION_VERSION);

        assertThat(state).isEqualTo(AgentRuntimeState.EXTENSION_INITIALIZING);
    }

    @Test
    void initializingWithCompleteCapabilitiesIsIncompatibleRatherThanStuckInitializing() {
        AgentRuntimeState state = classify(snapshot(
                APPLICATION_VERSION, 1, "platform-agent-secure-v1", "INITIALIZING", "",
                COMPLETE_CAPABILITIES),
                APPLICATION_VERSION);

        assertThat(state).isEqualTo(AgentRuntimeState.EXTENSION_INCOMPATIBLE);
    }

    @Test
    void failedWithFailureCodeIsExtensionFailed() {
        AgentRuntimeState state = classify(snapshot(
                APPLICATION_VERSION, 1, "platform-agent-secure-v1", "FAILED", "SANITIZER_FAILED",
                Set.of()),
                APPLICATION_VERSION);

        assertThat(state).isEqualTo(AgentRuntimeState.EXTENSION_FAILED);
    }

    @Test
    void failedWithoutFailureCodeIsIncompatible() {
        AgentReadinessClassifier.Decision decision = decide(snapshot(
                APPLICATION_VERSION, 1, "platform-agent-secure-v1", "FAILED", "",
                Set.of()),
                APPLICATION_VERSION);

        assertThat(decision.state()).isEqualTo(AgentRuntimeState.EXTENSION_INCOMPATIBLE);
        assertThat(decision.reasonCode()).isEqualTo("EXTENSION_FAILED_WITHOUT_CODE");
    }

    @Test
    void readyWithMismatchedCapabilitiesIsCapabilityMissing() {
        AgentReadinessClassifier.Decision decision = decide(snapshot(
                APPLICATION_VERSION, 1, "platform-agent-secure-v1", "READY", "",
                Set.of("CONFIGURATION_LOADED")),
                APPLICATION_VERSION);

        assertThat(decision.state()).isEqualTo(AgentRuntimeState.CAPABILITY_MISSING);
        assertThat(decision.reasonCode()).isEqualTo("CAPABILITY_SET_MISMATCH");
    }

    @Test
    void readyWithoutSafeExporterCapabilityIsCapabilityMissing() {
        AgentRuntimeState state = classify(snapshot(
                APPLICATION_VERSION, 1, "platform-agent-secure-v1", "READY", "",
                Set.of(
                        "CONFIGURATION_LOADED",
                        "PLATFORM_SAMPLER_INSTALLED",
                        "REQUIRED_SPAN_PROCESSORS_INSTALLED",
                        "SANITIZER_INSTALLED",
                        "PROPAGATION_HOOKS_INSTALLED",
                        "EXPORT_PATH_PROTECTED")),
                APPLICATION_VERSION);

        assertThat(state).isEqualTo(AgentRuntimeState.CAPABILITY_MISSING);
    }

    @Test
    void readyWithSupersetOfCapabilitiesIsCapabilityMissingUnderExactEqualityRule() {
        // Slice 3 tightens containsAll -> exact set equality; a superset no longer satisfies READY.
        Set<String> superset = Set.of(
                "CONFIGURATION_LOADED", "PLATFORM_SAMPLER_INSTALLED", "REQUIRED_SPAN_PROCESSORS_INSTALLED",
                "SANITIZER_INSTALLED", "PROPAGATION_HOOKS_INSTALLED", "SAFE_EXPORTER_INSTALLED",
                "EXPORT_PATH_PROTECTED", "UNEXPECTED_EXTRA_CAPABILITY");
        AgentRuntimeState state = classify(snapshot(
                APPLICATION_VERSION, 1, "platform-agent-secure-v1", "READY", "",
                superset),
                APPLICATION_VERSION);

        assertThat(state).isEqualTo(AgentRuntimeState.CAPABILITY_MISSING);
    }

    @Test
    void readyWithNonBlankFailureCodeIsIncompatible() {
        AgentRuntimeState state = classify(snapshot(
                APPLICATION_VERSION, 1, "platform-agent-secure-v1", "READY", "UNEXPECTED",
                COMPLETE_CAPABILITIES),
                APPLICATION_VERSION);

        assertThat(state).isEqualTo(AgentRuntimeState.EXTENSION_INCOMPATIBLE);
    }

    @Test
    void unknownLifecycleIsIncompatible() {
        AgentReadinessClassifier.Decision decision = decide(snapshot(
                APPLICATION_VERSION, 1, "platform-agent-secure-v1", "UNKNOWN", "",
                COMPLETE_CAPABILITIES),
                APPLICATION_VERSION);

        assertThat(decision.state()).isEqualTo(AgentRuntimeState.EXTENSION_INCOMPATIBLE);
        assertThat(decision.reasonCode()).isEqualTo("EXTENSION_LIFECYCLE_UNEXPECTED");
    }

    private static AgentRuntimeState classify(AgentReadinessSnapshot snapshot, String applicationVersion) {
        return AgentReadinessClassifier.classify(snapshot, applicationVersion);
    }

    private static AgentReadinessClassifier.Decision decide(AgentReadinessSnapshot snapshot, String applicationVersion) {
        return AgentReadinessClassifier.decide(snapshot, applicationVersion);
    }

    private static AgentReadinessSnapshot snapshot(
            String extensionVersion,
            int protocolVersion,
            String profile,
            String lifecycle,
            String failureCode,
            Set<String> capabilities) {
        return new AgentReadinessSnapshot(
                new AgentReadinessSnapshot.Protocol(extensionVersion, protocolVersion, profile),
                new AgentReadinessSnapshot.Status(lifecycle, failureCode),
                capabilities);
    }
}
