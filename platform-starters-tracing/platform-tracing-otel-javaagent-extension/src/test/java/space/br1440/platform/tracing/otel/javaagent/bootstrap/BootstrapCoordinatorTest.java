package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.AutoConfiguredSdkIsolationExtension;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.Registration;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.ShutdownHookRegistry;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSessionFactory;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.verification.VerificationFailureCode;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxAssessorTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxDenialReason;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxMutationRegistrationAssessor;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.CapturedLogEvent;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionLifecycle;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadinessSnapshot;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly.PlatformScrubberFactory;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly.ScrubberFactoryTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.SanitizerBootstrapException;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
@SuppressWarnings("resource")
class BootstrapCoordinatorTest {

    private BootstrapCoordinator ownedCoordinator;

    @AfterEach
    void closeOwnedCoordinator() {
        if (ownedCoordinator != null) {
            ownedCoordinator.close();
        }
    }

    @Test
    void activate_without_prior_prepare_classified_as_PLATFORM_ACP_NOT_EXECUTED() {
        // given
        BootstrapCoordinator coordinator = new BootstrapCoordinator(
                new CapturingShutdownHookRegistry(),
                new PlatformScrubberFactory(),
                RuntimeEvidenceTestFixtures.trustedVerifier(),
                AutoconfigureSessionFactory::currentProcessRemoteJmxAssessor
        );

        // when/then
        assertThatThrownBy(coordinator::activate)
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("PLATFORM_ACP_NOT_EXECUTED");
    }

    @Test
    void repeated_activate_after_successful_activation_classified_as_DUPLICATE_ACTIVATION() {
        // given
        CapturingShutdownHookRegistry hooks = new CapturingShutdownHookRegistry();
        BootstrapCoordinator coordinator = new BootstrapCoordinator(
                hooks,
                new PlatformScrubberFactory(),
                RuntimeEvidenceTestFixtures.trustedVerifier(),
                ignored -> RemoteJmxAssessorTestFixtures.allowingAssessor()
        );
        this.ownedCoordinator = coordinator;

        AutoconfigureSession session = coordinator.prepare(config(Map.of()));
        satisfyReadinessBarrier(session);
        coordinator.activate();

        // when
        assertThatThrownBy(coordinator::activate)
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("DUPLICATE_ACTIVATION");

        // then
        assertThat(coordinator.state()).isEqualTo(BootstrapState.ACTIVE);
        assertThat(coordinator.currentWire().lifecycle()).isEqualTo("READY");
        hooks.runHook();
        assertThat(coordinator.currentWire().lifecycle()).isEqualTo("FAILED");
        assertThat(coordinator.currentWire().failureCode()).isEqualTo("JMX_TRANSPORT_STALE");
    }

    @Test
    void linkageErrorDuringRuntimeAttestationPublishesFailedBeforePropagation() {
        // given
        BootstrapCoordinator coordinator = new BootstrapCoordinator(
                new CapturingShutdownHookRegistry(),
                new PlatformScrubberFactory(),
                extensionVersion -> {
                    throw new NoClassDefFoundError("simulated runtime evidence linkage failure");
                },
                ignored -> RemoteJmxAssessorTestFixtures.allowingAssessor()
        );

        try (coordinator) {
            this.ownedCoordinator = coordinator;
            AutoconfigureSession session = coordinator.prepare(config(Map.of()));
            // when
            assertThatThrownBy(coordinator::activate)
                    .isInstanceOf(NoClassDefFoundError.class)
                    .hasMessage("simulated runtime evidence linkage failure");

            // then
            assertThat(coordinator.state()).isEqualTo(BootstrapState.FAILED);
            assertThat(coordinator.currentWire().lifecycle()).isEqualTo("FAILED");
            assertThat(coordinator.currentWire().failureCode())
                    .isEqualTo(VerificationFailureCode.EXTENSION_LINKAGE_ERROR.name());
            assertThat(session.readiness().snapshot().lifecycle()).isEqualTo(PlatformExtensionLifecycle.FAILED);
            assertThat(session.readiness().snapshot().failureCode())
                    .isEqualTo(VerificationFailureCode.EXTENSION_LINKAGE_ERROR.name());
        }
    }

    @Test
    void activationRollbackFailurePublishesRollbackIncompleteAndPreservesOriginalCause() {
        // given
        IllegalStateException cleanupFailure = new IllegalStateException("simulated shutdown hook removal failure");
        ShutdownHookRegistry hooks = (threadName, action) -> () -> {
            throw cleanupFailure;
        };

        BootstrapCoordinator coordinator = new BootstrapCoordinator(
                hooks,
                new PlatformScrubberFactory(),
                RuntimeEvidenceTestFixtures.trustedVerifier(),
                ignored -> RemoteJmxAssessorTestFixtures.allowingAssessor()
        );

        try (coordinator) {
            this.ownedCoordinator = coordinator;
            AutoconfigureSession session = coordinator.prepare(config(Map.of()));
            // when
            assertThatThrownBy(coordinator::activate)
                    .isInstanceOf(BootstrapException.class)
                    .satisfies(failure -> {
                        BootstrapException bootstrapFailure = (BootstrapException) failure;
                        assertThat(bootstrapFailure.failure().code())
                                .isEqualTo("FAILED_ROLLBACK_INCOMPLETE");
                        assertThat(bootstrapFailure.getCause().getSuppressed()).hasSize(1);
                        assertThat(bootstrapFailure.getCause().getSuppressed()[0].getCause())
                                .isSameAs(cleanupFailure);
                    });

            // then
            assertThat(coordinator.state()).isEqualTo(BootstrapState.FAILED);
            assertThat(coordinator.currentWire().failureCode())
                    .isEqualTo("FAILED_ROLLBACK_INCOMPLETE");
            assertThat(session.readiness().snapshot().failureCode())
                    .isEqualTo("FAILED_ROLLBACK_INCOMPLETE");
        }
    }

    @Test
    void diagnosticCleanupFailureOverridesTransportStaleDuringShutdown() throws Exception {
        // given
        CapturingShutdownHookRegistry hooks = new CapturingShutdownHookRegistry();
        BootstrapCoordinator coordinator = new BootstrapCoordinator(
                hooks,
                new PlatformScrubberFactory(),
                RuntimeEvidenceTestFixtures.trustedVerifier(),
                ignored -> RemoteJmxAssessorTestFixtures.allowingAssessor()
        );
        this.ownedCoordinator = coordinator;

        AutoconfigureSession session = coordinator.prepare(config(Map.of()));
        satisfyReadinessBarrier(session);
        coordinator.activate();

        IllegalStateException diagnosticFailure = new IllegalStateException("simulated diagnostic readiness cleanup failure");

        try (AutoCloseable ignored = replaceDiagnosticHandle(
                coordinator,
                () -> {
                    throw diagnosticFailure;
                }
        )) {
            // when
            hooks.runHook();

            // then
            assertThat(coordinator.state()).isEqualTo(BootstrapState.FAILED);
            assertThat(coordinator.currentWire().failureCode())
                    .isEqualTo("FAILED_ROLLBACK_INCOMPLETE");
            assertThat(coordinator.currentWire().evidenceGeneration()).isEqualTo(3L);
            assertThat(session.readiness().snapshot().failureCode())
                    .isEqualTo("FAILED_ROLLBACK_INCOMPLETE");
        }
    }

    @Test
    void second_prepare_with_identical_configuration_classified_as_SECOND_SDK_BUILD() {
        // given
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator();
        coordinator.prepare(config(Map.of()));

        // when/then
        assertThatThrownBy(() -> coordinator.prepare(config(Map.of())))
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("SECOND_SDK_BUILD");
    }

    @Test
    void second_prepare_with_different_configuration_classified_as_CONFIGURATION_SPLIT_BRAIN() {
        // given
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator();
        coordinator.prepare(config(Map.of()));

        // when/then
        assertThatThrownBy(() -> coordinator.prepare(config(Map.of(ExtensionPropertyNames.SAMPLING_RATIO, "0.42"))))
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("CONFIGURATION_SPLIT_BRAIN");
    }

    @Test
    void failure_mid_activate_rolls_back_LIFO_and_keeps_diagnostic_MBean_with_FAILED() {
        // given
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator();
        this.ownedCoordinator = coordinator;
        AutoconfigureSession session = coordinator.prepare(config(Map.of()));

        try (PlatformTracingMBeanRegistrar.RuntimeRegistration ignored = session.jmxRegistrar().registerRuntimeControls(session.policy())) {
            // when
            assertThatThrownBy(coordinator::activate)
                    .isInstanceOf(BootstrapException.class)
                    .extracting(failure -> ((BootstrapException) failure).failure().code())
                    .isEqualTo("ACTIVATION_FAILED");

            // then
            assertThat(session.readiness().snapshot().lifecycle()).isEqualTo(PlatformExtensionLifecycle.FAILED);
            assertThat(session.readiness().snapshot().failureCode()).isEqualTo("ACTIVATION_FAILED");
        }
    }

    @Test
    void remoteJmxGateDenialPublishesFailedDiagnosticSnapshotBeforeRuntimeRegistration() {
        // given
        // when
        RemoteJmxDenialOutcome outcome = activateAndExpectRemoteJmxFailure(
                RemoteJmxAssessorTestFixtures.managementAgentAssessor(),
                RemoteJmxDenialReason.REMOTE_JMX_MANAGEMENT_AGENT_CONFIGURED);

        // then
        assertThat(outcome.readiness().failureCode())
                .isEqualTo(RemoteJmxDenialReason.REMOTE_JMX_MANAGEMENT_AGENT_CONFIGURED.name());
        assertThat(singleRemoteJmxDenialEvent(outcome).fields())
                .containsEntry(
                        "failureCode",
                        RemoteJmxDenialReason.REMOTE_JMX_MANAGEMENT_AGENT_CONFIGURED.name())
                .containsEntry("remediation", RemoteJmxAssessorTestFixtures.REMEDIATION_MANAGEMENT_AGENT)
                .containsEntry("offendingArguments", "-Dcom.sun.management.jmxremote.port");
    }

    @Test
    void remoteJmxCaptureFailureIsPublishedAsClassifiedEvidenceUnknownDenial() {
        // given
        // when
        RemoteJmxDenialOutcome outcome = activateAndExpectRemoteJmxFailure(
                RemoteJmxAssessorTestFixtures.captureUnavailableAssessor(),
                RemoteJmxDenialReason.REMOTE_JMX_EVIDENCE_UNKNOWN);

        // then
        assertThat(outcome.readiness().failureCode())
                .isEqualTo(RemoteJmxDenialReason.REMOTE_JMX_EVIDENCE_UNKNOWN.name());
        assertThat(singleRemoteJmxDenialEvent(outcome).fields())
                .containsEntry("failureCode", RemoteJmxDenialReason.REMOTE_JMX_EVIDENCE_UNKNOWN.name())
                .containsEntry("remediation", RemoteJmxAssessorTestFixtures.REMEDIATION_CAPTURE_UNAVAILABLE)
                .containsEntry("offendingArguments", "");
    }

    @Test
    void remoteJmxDenialEventReportsArgumentNamesWithoutValues() {
        // given
        // when
        RemoteJmxDenialOutcome outcome = activateAndExpectRemoteJmxFailure(
                RemoteJmxAssessorTestFixtures.managementAgentAssessor(),
                RemoteJmxDenialReason.REMOTE_JMX_MANAGEMENT_AGENT_CONFIGURED
        );

        // then
        CapturedLogEvent denial = singleRemoteJmxDenialEvent(outcome);
        assertThat(denial.fields())
                .containsEntry("offendingArguments", "-Dcom.sun.management.jmxremote.port");
        assertThat(denial.fields().values())
                .as("argument values must never reach the diagnostic event")
                .noneMatch(value -> value.contains("9010"));
    }

    @Test
    void unhandledRemoteJmxPhaseErrorPublishesEvidenceUnknownWithoutRegisteringMutation() {
        // given
        // when
        RemoteJmxDenialOutcome outcome = activateAndExpectRemoteJmxFailure(
                RemoteJmxAssessorTestFixtures.explodingAssessor(),
                RemoteJmxDenialReason.REMOTE_JMX_EVIDENCE_UNKNOWN);

        // then
        assertThat(outcome.readiness().failureCode())
                .isEqualTo(RemoteJmxDenialReason.REMOTE_JMX_EVIDENCE_UNKNOWN.name());
        assertThat(eventsWithCode(outcome, AgentEventCode.BOOT_REMOTE_JMX_DENIED))
                .as("the exception path is not a classified assessment denial")
                .isEmpty();
        assertThat(eventsWithCode(outcome, AgentEventCode.BOOT_CLASSIFIED_FAILURE)).isNotEmpty();
    }

    private RemoteJmxDenialOutcome activateAndExpectRemoteJmxFailure(RemoteJmxMutationRegistrationAssessor assessor,
                                                                     RemoteJmxDenialReason expectedReason) {
        BootstrapCoordinator coordinator = new BootstrapCoordinator(
                new CapturingShutdownHookRegistry(),
                new PlatformScrubberFactory(),
                RuntimeEvidenceTestFixtures.trustedVerifier(),
                ignored -> assessor
        );

        try (coordinator) {
            this.ownedCoordinator = coordinator;
            AutoconfigureSession session = coordinator.prepare(config(Map.of()));

            AgentLogCapture logs = new AgentLogCapture();
            logs.install();

            List<CapturedLogEvent> events;
            try {
                assertThatThrownBy(coordinator::activate)
                        .isInstanceOf(BootstrapException.class)
                        .extracting(failure -> ((BootstrapException) failure).failure().code())
                        .isEqualTo(expectedReason.name());
                events = logs.events();
            } finally {
                logs.restore();
            }

            assertThat(coordinator.currentWire().lifecycle()).isEqualTo("FAILED");
            assertThat(coordinator.currentWire().batchId()).isEmpty();
            assertThat(coordinator.currentWire().failureCode()).isEqualTo(expectedReason.name());
            assertThat(session.readiness().snapshot().failureCode()).isEqualTo(expectedReason.name());
            assertThat(ManagementFactory.getPlatformMBeanServer()
                    .isRegistered(DomainObjectNames.SETTINGS_MUTATION))
                    .as("mutation surface must stay unregistered after gate denial")
                    .isFalse();
            assertThat(ManagementFactory.getPlatformMBeanServer()
                    .isRegistered(DomainObjectNames.EXTENSION_READINESS))
                    .as("diagnostic readiness must remain available after gate denial")
                    .isTrue();
            return new RemoteJmxDenialOutcome(session.readiness().snapshot(), events);
        }
    }

    private static CapturedLogEvent singleRemoteJmxDenialEvent(RemoteJmxDenialOutcome outcome) {
        List<CapturedLogEvent> denials = eventsWithCode(outcome, AgentEventCode.BOOT_REMOTE_JMX_DENIED);
        assertThat(denials).hasSize(1);
        return denials.getFirst();
    }

    private static List<CapturedLogEvent> eventsWithCode(RemoteJmxDenialOutcome outcome, AgentEventCode eventCode) {
        return outcome.events()
                .stream()
                .filter(event -> eventCode.equals(event.eventCode()))
                .toList();
    }

    @Test
    void terminal_coordinator_repeats_same_code_for_any_subsequent_operation() {
        // given
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator();
        assertThatThrownBy(coordinator::activate);

        // when/then
        assertThatThrownBy(() -> coordinator.prepare(config(Map.of())))
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("PLATFORM_ACP_NOT_EXECUTED");
    }

    @Test
    void state_and_wire_never_visible_to_reader_in_inconsistent_combination_during_activate() throws InterruptedException {
        // given
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator();
        this.ownedCoordinator = coordinator;
        AutoconfigureSession session = coordinator.prepare(config(Map.of()));
        satisfyReadinessBarrier(session);

        Set<String> observedPairs = new CopyOnWriteArraySet<>();
        AtomicBoolean pollerRunning = new AtomicBoolean(true);
        CountDownLatch pollerStarted = new CountDownLatch(1);

        Thread poller = new Thread(() -> {
            pollerStarted.countDown();
            while (pollerRunning.get()) {
                observedPairs.add(coordinator.state().name() + "/" + coordinator.currentWire().lifecycle());
            }
        }, "snapshot-poller");
        poller.start();
        boolean pollerReady = pollerStarted.await(5, TimeUnit.SECONDS);
        assertThat(pollerReady).as("snapshot-poller thread failed to start within 5s").isTrue();

        try {
            // when
            coordinator.activate();

            // then
            Set<String> validPairs = Set.of(
                    "PREPARED/INITIALIZING",
                    "ACTIVATING/INITIALIZING",
                    "ACTIVATING/READY",
                    "ACTIVE/READY"
            );
            assertThat(observedPairs).isSubsetOf(validPairs);
            assertThat(coordinator.state()).isEqualTo(BootstrapState.ACTIVE);
            assertThat(coordinator.currentWire().lifecycle()).isEqualTo("READY");
        } finally {
            pollerRunning.set(false);
            poller.join(5_000);
        }
    }

    @Test
    void shutdownPublishesTransportStaleBeforeClosingRegisteredResources() {
        // given
        CapturingShutdownHookRegistry hooks = new CapturingShutdownHookRegistry();
        BootstrapCoordinator coordinator = new BootstrapCoordinator(
                hooks,
                new PlatformScrubberFactory(),
                RuntimeEvidenceTestFixtures.trustedVerifier(),
                ignored -> RemoteJmxAssessorTestFixtures.allowingAssessor()
        );
        this.ownedCoordinator = coordinator;

        AutoconfigureSession session = coordinator.prepare(config(Map.of()));
        satisfyReadinessBarrier(session);
        coordinator.activate();
        assertThat(coordinator.currentWire().lifecycle()).isEqualTo("READY");

        // when
        hooks.runHook();

        // then
        assertThat(coordinator.currentWire().lifecycle()).isEqualTo("FAILED");
        assertThat(coordinator.currentWire().failureCode()).isEqualTo("JMX_TRANSPORT_STALE");
        assertThat(coordinator.currentWire().evidenceGeneration()).isEqualTo(2L);
    }

    @Test
    void successfulActivationEmitsExactlyOneInstalledMarker() {
        // given
        AgentLogCapture logs = new AgentLogCapture();
        logs.install();

        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator();
        this.ownedCoordinator = coordinator;

        AutoconfigureSession session = coordinator.prepare(config(Map.of()));
        satisfyReadinessBarrier(session);

        try {
            // when
            coordinator.activate();
            assertThatThrownBy(coordinator::activate).isInstanceOf(BootstrapException.class);

            // then
            List<CapturedLogEvent> installed = logs.events()
                    .stream()
                    .filter(event -> AgentEventCode.BOOT_INSTALLED.equals(event.eventCode()))
                    .toList();
            assertThat(installed).hasSize(1);
            assertThat(installed.getFirst().fields())
                    .containsEntry("lifecycle", "READY")
                    .containsEntry("profile", "platform-agent-secure-v1")
                    .containsEntry("protocolVersion", "1")
                    .containsEntry("extensionVersion", session.readiness().snapshot().extensionVersion());
        } finally {
            logs.restore();
        }
    }

    @Test
    void failedActivationDoesNotEmitInstalledMarker() {
        // given
        AgentLogCapture logs = new AgentLogCapture();
        logs.install();

        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator();
        this.ownedCoordinator = coordinator;

        AutoconfigureSession session = coordinator.prepare(config(Map.of()));
        markAllCapabilitiesExcept(session, PlatformExtensionCapability.SANITIZER_INSTALLED);

        try {
            // when
            assertThatThrownBy(coordinator::activate).isInstanceOf(BootstrapException.class);

            // then
            assertThat(logs.events())
                    .noneMatch(event -> AgentEventCode.BOOT_INSTALLED.equals(event.eventCode()));
        } finally {
            logs.restore();
        }
    }

    @Test
    void missing_processor_acknowledgement_fails_activation_and_never_publishes_READY() {
        // given
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator();
        this.ownedCoordinator = coordinator;

        AutoconfigureSession session = coordinator.prepare(config(Map.of()));
        markAllCapabilitiesExcept(session, PlatformExtensionCapability.SANITIZER_INSTALLED);
        session.scrubbingRuntime().acknowledgeExportModeResolved();
        session.scrubbingRuntime().acknowledgeExporterWrapped();
        session.scrubbingRuntime().acknowledgeExportPathProtected();

        // when/then
        assertThatThrownBy(coordinator::activate)
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("ACTIVATION_FAILED");

        assertThat(session.readiness().snapshot().lifecycle()).isEqualTo(PlatformExtensionLifecycle.FAILED);
        assertThat(session.readiness().snapshot().has(PlatformExtensionCapability.SANITIZER_INSTALLED)).isFalse();
        assertThat(coordinator.currentWire().lifecycle()).isEqualTo("FAILED");
        assertThat(coordinator.state()).isNotEqualTo(BootstrapState.ACTIVE);
    }

    @Test
    void zero_wrapped_exporters_fails_activation_and_never_publishes_READY() {
        // given
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator();
        this.ownedCoordinator = coordinator;
        AutoconfigureSession session = coordinator.prepare(config(Map.of()));
        markAllCapabilitiesExcept(session, PlatformExtensionCapability.SANITIZER_INSTALLED);
        session.scrubbingRuntime().acknowledgeProcessorInstalled();
        session.scrubbingRuntime().acknowledgeExportModeResolved();
        session.scrubbingRuntime().acknowledgeExportPathProtected();

        // when/then
        assertThatThrownBy(coordinator::activate)
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("ACTIVATION_FAILED");

        // then
        assertThat(coordinator.currentWire().lifecycle()).isEqualTo("FAILED");
        assertThat(session.readiness().snapshot().has(PlatformExtensionCapability.SANITIZER_INSTALLED)).isFalse();
    }

    @Test
    void unprotected_export_path_fails_activation_and_never_publishes_READY() {
        // given
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator();
        this.ownedCoordinator = coordinator;
        AutoconfigureSession session = coordinator.prepare(config(Map.of()));
        markAllCapabilitiesExcept(session, PlatformExtensionCapability.SANITIZER_INSTALLED);
        session.scrubbingRuntime().acknowledgeProcessorInstalled();
        session.scrubbingRuntime().acknowledgeExportModeResolved();
        session.scrubbingRuntime().acknowledgeExporterWrapped();

        // when/then
        assertThatThrownBy(coordinator::activate)
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("ACTIVATION_FAILED");

        // then
        assertThat(coordinator.currentWire().lifecycle()).isEqualTo("FAILED");
    }

    @Test
    void missing_non_sanitizer_capability_fails_activation_after_barrier() {
        // given
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator();
        this.ownedCoordinator = coordinator;
        AutoconfigureSession session = coordinator.prepare(config(Map.of()));
        for (PlatformExtensionCapability capability : PlatformExtensionCapability.values()) {
            if (capability != PlatformExtensionCapability.PROPAGATION_HOOKS_INSTALLED
                    && capability != PlatformExtensionCapability.SANITIZER_INSTALLED) {
                session.readiness().markInstalled(capability);
            }
        }
        session.scrubbingRuntime().acknowledgeProcessorInstalled();
        session.scrubbingRuntime().acknowledgeExportModeResolved();
        session.scrubbingRuntime().acknowledgeExporterWrapped();
        session.scrubbingRuntime().acknowledgeExportPathProtected();

        // when/then
        assertThatThrownBy(coordinator::activate)
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("ACTIVATION_FAILED");

        // then
        assertThat(coordinator.currentWire().lifecycle()).isEqualTo("FAILED");
        assertThat(session.readiness().snapshot().has(PlatformExtensionCapability.SANITIZER_INSTALLED)).isTrue();
        assertThat(session.readiness().snapshot().lifecycle()).isEqualTo(PlatformExtensionLifecycle.FAILED);
    }

    @Test
    void unknown_span_attribute_validation_level_classified_as_CONFIGURATION_INITIALIZATION_FAILED() {
        // given
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator();

        // when/then
        assertThatThrownBy(() -> coordinator.prepare(config(Map.of(
                ExtensionPropertyNames.SPAN_ATTRIBUTES_VALIDATION_LEVEL,
                "STRICT"
        ))))
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("CONFIGURATION_INITIALIZATION_FAILED");
        assertThat(coordinator.state()).isEqualTo(BootstrapState.FAILED);
    }

    @Test
    void empty_built_in_rule_set_preserves_SANITIZER_RULE_SET_EMPTY() {
        // given
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator(
                ScrubberFactoryTestFixtures.factory(List::of)
        );

        // when/then
        assertThatThrownBy(() -> coordinator.prepare(emptyBuiltInRulesConfig()))
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("SANITIZER_RULE_SET_EMPTY");
    }

    @Test
    void fail_fast_missing_hmac_key_preserves_SANITIZER_HMAC_INITIALIZATION_FAILED() {
        // given
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator();

        // when/then
        assertThatThrownBy(() -> coordinator.prepare(config(Map.of(
                ExtensionPropertyNames.SCRUBBING_MISSING_KEY_POLICY, "fail-fast"))))
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("SANITIZER_HMAC_INITIALIZATION_FAILED");
    }

    @Test
    void rule_source_failure_preserves_SANITIZER_RULE_LOADING_FAILED() {
        // given
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator(ScrubberFactoryTestFixtures.factory(() -> {
            throw new IllegalStateException("rule source unavailable");
        }));

        // when/then
        assertThatThrownBy(() -> coordinator.prepare(config(Map.of())))
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("SANITIZER_RULE_LOADING_FAILED");
    }

    @Test
    void invalid_bundled_rule_metadata_preserves_SANITIZER_RULE_METADATA_INVALID() {
        // given
        SpanAttributeScrubbingRule brokenMetadata = new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override
            public String name() {
                throw new IllegalStateException("metadata accessor blew up");
            }

            @Nonnull
            @Override
            public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
                return ScrubbingDecision.keep();
            }
        };
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator(
                ScrubberFactoryTestFixtures.factory(() -> List.of(brokenMetadata)));

        // when/then
        assertThatThrownBy(() -> coordinator.prepare(config(Map.of())))
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("SANITIZER_RULE_METADATA_INVALID");
    }

    @Test
    void linkage_error_from_rule_source_is_not_classified_as_a_sanitizer_failure() {
        // given
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator(ScrubberFactoryTestFixtures.factory(() -> {
            throw new NoClassDefFoundError("simulated missing SPI class");
        }));

        // when/then
        assertThatThrownBy(() -> coordinator.prepare(config(Map.of())))
                .isInstanceOf(NoClassDefFoundError.class)
                .hasMessage("simulated missing SPI class");
    }

    @Test
    void execute_preserves_SANITIZER_FOREIGN_SAFE_EXPORTER_and_fails_readiness() {
        // given
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator();
        AutoconfigureSession session = coordinator.prepare(config(Map.of()));

        // when/then
        assertThatThrownBy(() -> coordinator.execute("EXPORTER_INITIALIZATION_FAILED", () -> {
            throw new SanitizerBootstrapException(
                    SanitizerBootstrapException.FOREIGN_SAFE_EXPORTER,
                    "Foreign SafeSpanExporter is not protected by the session scrubbing runtime "
                            + "(space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter)");
        }))
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo(SanitizerBootstrapException.FOREIGN_SAFE_EXPORTER);

        // then
        assertThat(session.readiness().snapshot().lifecycle()).isEqualTo(PlatformExtensionLifecycle.FAILED);
        assertThat(session.readiness().snapshot().failureCode())
                .isEqualTo(SanitizerBootstrapException.FOREIGN_SAFE_EXPORTER);
        assertThat(session.readiness().snapshot().has(PlatformExtensionCapability.SANITIZER_INSTALLED)).isFalse();
        assertThat(coordinator.currentWire().lifecycle()).isEqualTo("FAILED");
    }

    private static void satisfyReadinessBarrier(AutoconfigureSession session) {
        for (PlatformExtensionCapability capability : PlatformExtensionCapability.values()) {
            session.readiness().markInstalled(capability);
        }

        session.scrubbingRuntime().acknowledgeProcessorInstalled();
        session.scrubbingRuntime().acknowledgeExportModeResolved();
        session.scrubbingRuntime().acknowledgeExporterWrapped();
        session.scrubbingRuntime().acknowledgeExportPathProtected();
    }

    @SuppressWarnings("SameParameterValue")
    private static void markAllCapabilitiesExcept(AutoconfigureSession session,
                                                  PlatformExtensionCapability excluded) {
        for (PlatformExtensionCapability capability : PlatformExtensionCapability.values()) {
            if (capability != excluded) {
                session.readiness().markInstalled(capability);
            }
        }
    }

    private static ConfigProperties emptyBuiltInRulesConfig() {
        return config(Map.of(ExtensionPropertyNames.SCRUBBING_BUILT_IN_RULES, ""));
    }

    private static AutoCloseable replaceDiagnosticHandle(BootstrapCoordinator coordinator,
                                                         AutoCloseable replacement) {
        try {
            Field field = BootstrapCoordinator.class.getDeclaredField("diagnosticHandle");
            field.setAccessible(true);
            DiagnosticReadinessHandle handle = (DiagnosticReadinessHandle) field.get(coordinator);
            return handle.replace(replacement);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot replace diagnostic readiness handle for test", failure);
        }
    }

    private static ConfigProperties config(Map<String, String> data) {
        return new MapConfigProperties(new HashMap<>(data));
    }

    private static final class MapConfigProperties implements ConfigProperties {
        private final Map<String, String> data;

        MapConfigProperties(Map<String, String> data) {
            this.data = new HashMap<>(data);
        }

        @Override
        public String getString(String name) {
            return data.get(name);
        }

        @Override
        public Boolean getBoolean(String name) {
            String value = data.get(name);
            return value == null ? null : Boolean.parseBoolean(value);
        }

        @Override
        public Integer getInt(String name) {
            String value = data.get(name);
            return value == null ? null : Integer.valueOf(value);
        }

        @Override
        public Long getLong(String name) {
            String value = data.get(name);
            return value == null ? null : Long.valueOf(value);
        }

        @Override
        public Double getDouble(String name) {
            String value = data.get(name);
            return value == null ? null : Double.valueOf(value);
        }

        @Override
        public Duration getDuration(String name) {
            return null;
        }

        @Override
        public java.util.List<String> getList(String name) {
            String value = data.get(name);
            if (value == null) {
                return null;
            }
            if (value.isBlank()) {
                return java.util.List.of();
            }
            return java.util.List.of(value.split("\\s*,\\s*"));
        }

        @Override
        public java.util.Map<String, String> getMap(String name) {
            return java.util.Map.of();
        }
    }

    private record RemoteJmxDenialOutcome(PlatformExtensionReadinessSnapshot readiness,
                                          List<CapturedLogEvent> events) {
    }

    private static final class CapturingShutdownHookRegistry implements ShutdownHookRegistry {

        private final AtomicReference<Runnable> action = new AtomicReference<>();

        @Override
        public Registration register(String threadName, Runnable registeredAction) {
            if (!action.compareAndSet(null, registeredAction)) {
                throw new IllegalStateException("Shutdown hook is already registered");
            }

            return () -> {
            };
        }

        void runHook() {
            Runnable registeredAction = action.get();
            if (registeredAction == null) {
                throw new IllegalStateException("Shutdown hook is not registered");
            }

            registeredAction.run();
        }
    }
}
