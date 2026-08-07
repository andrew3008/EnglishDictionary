package space.br1440.platform.tracing.autoconfigure.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessSnapshot.InstallFlags;

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
                COMPLETE_CAPABILITIES, COMPLETE_CAPABILITIES, InstallFlags.complete()),
                APPLICATION_VERSION);

        assertThat(state).isEqualTo(AgentRuntimeState.AGENT_READY);
    }

    @Test
    void developmentUnitSeamIsAcceptedOnlyWhenBothSidesAreExplicitlyDevelopment() {
        AgentRuntimeState state = classify(snapshot(
                "development", 1, "platform-agent-secure-v1", "READY", "",
                COMPLETE_CAPABILITIES, COMPLETE_CAPABILITIES, InstallFlags.complete()),
                "development");

        assertThat(state).isEqualTo(AgentRuntimeState.AGENT_READY);
    }

    @Test
    void missingApplicationVersionIsFailClosedEvenWhenExtensionReportsDevelopment() {
        // No one-sided wildcard: a null application version rejects a "development" extension.
        AgentRuntimeState state = classify(snapshot(
                "development", 1, "platform-agent-secure-v1", "READY", "",
                COMPLETE_CAPABILITIES, COMPLETE_CAPABILITIES, InstallFlags.complete()),
                null);

        assertThat(state).isEqualTo(AgentRuntimeState.EXTENSION_INCOMPATIBLE);
    }

    @Test
    void mismatchedNonDevelopmentVersionsAreRejected() {
        AgentRuntimeState state = classify(snapshot(
                "9.9.9-not-matching", 1, "platform-agent-secure-v1", "READY", "",
                COMPLETE_CAPABILITIES, COMPLETE_CAPABILITIES, InstallFlags.complete()),
                APPLICATION_VERSION);

        assertThat(state).isEqualTo(AgentRuntimeState.EXTENSION_INCOMPATIBLE);
    }

    @Test
    void unsupportedProtocolVersionIsIncompatible() {
        AgentRuntimeState state = classify(snapshot(
                APPLICATION_VERSION, 999, "platform-agent-secure-v1", "READY", "",
                COMPLETE_CAPABILITIES, COMPLETE_CAPABILITIES, InstallFlags.complete()),
                APPLICATION_VERSION);

        assertThat(state).isEqualTo(AgentRuntimeState.EXTENSION_INCOMPATIBLE);
    }

    @Test
    void unsupportedProfileIsIncompatible() {
        AgentRuntimeState state = classify(snapshot(
                APPLICATION_VERSION, 1, "unknown-profile", "READY", "",
                COMPLETE_CAPABILITIES, COMPLETE_CAPABILITIES, InstallFlags.complete()),
                APPLICATION_VERSION);

        assertThat(state).isEqualTo(AgentRuntimeState.EXTENSION_INCOMPATIBLE);
    }

    @Test
    void initializingWithoutFailureCodeAndIncompleteCapabilitiesIsInitializing() {
        AgentRuntimeState state = classify(snapshot(
                APPLICATION_VERSION, 1, "platform-agent-secure-v1", "INITIALIZING", "",
                Set.of(), COMPLETE_CAPABILITIES, InstallFlags.absent()),
                APPLICATION_VERSION);

        assertThat(state).isEqualTo(AgentRuntimeState.EXTENSION_INITIALIZING);
    }

    @Test
    void initializingWithCompleteCapabilitiesIsIncompatibleRatherThanStuckInitializing() {
        AgentRuntimeState state = classify(snapshot(
                APPLICATION_VERSION, 1, "platform-agent-secure-v1", "INITIALIZING", "",
                COMPLETE_CAPABILITIES, COMPLETE_CAPABILITIES, InstallFlags.complete()),
                APPLICATION_VERSION);

        assertThat(state).isEqualTo(AgentRuntimeState.EXTENSION_INCOMPATIBLE);
    }

    @Test
    void failedWithFailureCodeIsExtensionFailed() {
        AgentRuntimeState state = classify(snapshot(
                APPLICATION_VERSION, 1, "platform-agent-secure-v1", "FAILED", "SANITIZER_FAILED",
                Set.of(), COMPLETE_CAPABILITIES, InstallFlags.absent()),
                APPLICATION_VERSION);

        assertThat(state).isEqualTo(AgentRuntimeState.EXTENSION_FAILED);
    }

    @Test
    void failedWithoutFailureCodeIsIncompatible() {
        AgentRuntimeState state = classify(snapshot(
                APPLICATION_VERSION, 1, "platform-agent-secure-v1", "FAILED", "",
                Set.of(), COMPLETE_CAPABILITIES, InstallFlags.absent()),
                APPLICATION_VERSION);

        assertThat(state).isEqualTo(AgentRuntimeState.EXTENSION_INCOMPATIBLE);
    }

    @Test
    void readyWithMismatchedCapabilitiesIsCapabilityMissing() {
        AgentRuntimeState state = classify(snapshot(
                APPLICATION_VERSION, 1, "platform-agent-secure-v1", "READY", "",
                Set.of("CONFIGURATION_LOADED"), COMPLETE_CAPABILITIES, InstallFlags.complete()),
                APPLICATION_VERSION);

        assertThat(state).isEqualTo(AgentRuntimeState.CAPABILITY_MISSING);
    }

    @Test
    void readyWithExactCapabilitiesButMissingComponentFlagIsCapabilityMissing() {
        AgentRuntimeState state = classify(snapshot(
                APPLICATION_VERSION, 1, "platform-agent-secure-v1", "READY", "",
                COMPLETE_CAPABILITIES, COMPLETE_CAPABILITIES,
                new InstallFlags(false, true, true, true, true)),
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
                superset, COMPLETE_CAPABILITIES, InstallFlags.complete()),
                APPLICATION_VERSION);

        assertThat(state).isEqualTo(AgentRuntimeState.CAPABILITY_MISSING);
    }

    @Test
    void readyWithNonBlankFailureCodeIsIncompatible() {
        AgentRuntimeState state = classify(snapshot(
                APPLICATION_VERSION, 1, "platform-agent-secure-v1", "READY", "UNEXPECTED",
                COMPLETE_CAPABILITIES, COMPLETE_CAPABILITIES, InstallFlags.complete()),
                APPLICATION_VERSION);

        assertThat(state).isEqualTo(AgentRuntimeState.EXTENSION_INCOMPATIBLE);
    }

    @Test
    void unknownLifecycleIsIncompatible() {
        AgentRuntimeState state = classify(snapshot(
                APPLICATION_VERSION, 1, "platform-agent-secure-v1", "UNKNOWN", "",
                COMPLETE_CAPABILITIES, COMPLETE_CAPABILITIES, InstallFlags.complete()),
                APPLICATION_VERSION);

        assertThat(state).isEqualTo(AgentRuntimeState.EXTENSION_INCOMPATIBLE);
    }

    private static AgentRuntimeState classify(AgentReadinessSnapshot snapshot, String applicationVersion) {
        return AgentReadinessClassifier.classify(snapshot, applicationVersion);
    }

    private static AgentReadinessSnapshot snapshot(
            String extensionVersion,
            int protocolVersion,
            String profile,
            String lifecycle,
            String failureCode,
            Set<String> advertisedCapabilities,
            Set<String> requiredCapabilities,
            InstallFlags installFlags) {
        return new AgentReadinessSnapshot(
                new AgentReadinessSnapshot.Protocol(extensionVersion, protocolVersion, profile),
                new AgentReadinessSnapshot.Status(lifecycle, failureCode),
                new AgentReadinessSnapshot.Capabilities(advertisedCapabilities, requiredCapabilities),
                installFlags);
    }
}
