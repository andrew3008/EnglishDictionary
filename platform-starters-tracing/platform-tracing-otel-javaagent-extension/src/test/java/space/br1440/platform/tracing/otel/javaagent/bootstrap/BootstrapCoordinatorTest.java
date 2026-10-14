package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

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
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.openmbean.CompositeData;
import javax.management.timer.Timer;
import jakarta.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.AutoConfiguredSdkIsolationExtension;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.Registration;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.resources.ShutdownHookRegistry;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSessionFactory;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.verification.VerificationFailureCode;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.control.AcceptedPolicyMutationAccess;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.JmxRegistrationException;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxAssessorTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxDenialReason;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxMutationRegistrationAssessor;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
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
@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
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
        String agentInstanceId = session.agentInstanceId().toString();
        satisfyReadinessBarrier(session);
        coordinator.activate();
        assertThat(coordinator.currentWire().agentInstanceId()).isEqualTo(agentInstanceId);

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
        assertThat(coordinator.currentWire().agentInstanceId()).isEqualTo(agentInstanceId);
    }

    @Test
    void separateBootstrapOwnersReceiveDifferentAgentInstanceIds() {
        // given
        BootstrapCoordinator first = RuntimeEvidenceTestFixtures.coordinator();
        BootstrapCoordinator second = RuntimeEvidenceTestFixtures.coordinator();

        try (first; second) {
            // when
            AgentInstanceId firstId = first.prepare(config(Map.of())).agentInstanceId();
            AgentInstanceId secondId = second.prepare(config(Map.of())).agentInstanceId();

            // then
            assertThat(firstId).isNotEqualTo(secondId);
        }
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
        IllegalArgumentException resourceCleanupFailure =
                new IllegalArgumentException("simulated shutdown hook removal failure");
        CapturingShutdownHookRegistry hooks = new CapturingShutdownHookRegistry(resourceCleanupFailure);
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

        UnsupportedOperationException diagnosticFailure =
                new UnsupportedOperationException("simulated diagnostic readiness cleanup failure");
        AgentLogCapture logs = new AgentLogCapture();
        logs.install();

        try {
            try (AutoCloseable ignored = replaceDiagnosticHandle(
                    coordinator,
                    () -> {
                        throw diagnosticFailure;
                    }
            )) {
                // when
                assertThatCode(hooks::runHook).doesNotThrowAnyException();

                // then
                assertThat(coordinator.state()).isEqualTo(BootstrapState.FAILED);
                assertThat(coordinator.currentWire().failureCode())
                        .isEqualTo("FAILED_ROLLBACK_INCOMPLETE");
                assertThat(coordinator.currentWire().evidenceGeneration()).isEqualTo(3L);
                assertThat(session.readiness().snapshot().failureCode())
                        .isEqualTo("FAILED_ROLLBACK_INCOMPLETE");
                assertThat(logs.events())
                        .filteredOn(event -> event.eventCode() == AgentEventCode.BOOT_CLASSIFIED_FAILURE)
                        .singleElement()
                        .satisfies(event -> {
                            assertThat(event.fields())
                                    .containsEntry("phase", "shutdown")
                                    .containsEntry("resourceLabel", "shutdown-completion");
                            assertThat(event.throwableDigest())
                                    .contains(IllegalArgumentException.class.getName())
                                    .contains(UnsupportedOperationException.class.getName());
                        });
            }
        } finally {
            logs.restore();
        }
    }

    @Test
    void second_prepare_with_identical_configuration_classified_as_SECOND_SDK_BUILD() {
        // given
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator();
        this.ownedCoordinator = coordinator;
        coordinator.prepare(config(Map.of()));

        // when/then
        assertThatThrownBy(() -> coordinator.prepare(config(Map.of())))
                .isInstanceOf(BootstrapException.class)
                .extracting(failure -> ((BootstrapException) failure).failure().code())
                .isEqualTo("SECOND_SDK_BUILD");
    }

    @Test
    void second_prepare_rejection_preserves_original_updated_policy_store() {
        // given
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator();
        this.ownedCoordinator = coordinator;
        AutoconfigureSession originalSession = coordinator.prepare(config(Map.of()));
        TracingSettingsSource originalSource = originalSession.policy();
        TracingSettingsStore originalStore = originalSource.store();
        AgentInstanceId originalAgentInstanceId = originalSource.current().agentInstanceId();

        assertThat(AcceptedPolicyMutationAccess.applyValidation(
                originalSource,
                SpanAttributeValidationLevel.ERROR
        )).isEqualTo(SettingsUpdateStatus.APPLIED);
        TracingSettingsRevision acceptedRevision = originalSource.current();
        assertThat(acceptedRevision.acceptedSequence()).isEqualTo(1L);

        // when
        BootstrapException failure = catchThrowableOfType(
                BootstrapException.class,
                () -> coordinator.prepare(config(Map.of()))
        );
        AutoconfigureSession retainedSession = coordinator.session();

        // then
        assertThat(failure.failure().code()).isEqualTo("SECOND_SDK_BUILD");
        assertThat(retainedSession).isSameAs(originalSession);
        assertThat(retainedSession.policy()).isSameAs(originalSource);
        assertThat(retainedSession.policy().store()).isSameAs(originalStore);
        assertThat(retainedSession.agentInstanceId()).isEqualTo(originalAgentInstanceId);
        assertThat(retainedSession.policy().current()).isSameAs(acceptedRevision);
        assertThat(retainedSession.policy().current().agentInstanceId()).isEqualTo(originalAgentInstanceId);
        assertThat(retainedSession.policy().current().acceptedSequence()).isEqualTo(1L);
    }

    @Test
    void second_prepare_is_rejected_as_SECOND_SDK_BUILD_regardless_of_configuration() {
        // given
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator();
        this.ownedCoordinator = coordinator;

        AutoconfigureSession originalSession = coordinator.prepare(config(Map.of()));

        // when
        BootstrapException failure = catchThrowableOfType(
                BootstrapException.class,
                () -> coordinator.prepare(config(Map.of(ExtensionPropertyNames.SAMPLING_RATIO, "0.42")))
        );

        // then
        assertThat(failure.failure().code()).isEqualTo("SECOND_SDK_BUILD");
        assertThat(coordinator.state()).isEqualTo(BootstrapState.FAILED);
        assertThat(coordinator.session()).isSameAs(originalSession);
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
        assertThat(eventsWithCode(outcome, AgentEventCode.BOOT_CLASSIFIED_FAILURE))
                .singleElement()
                .satisfies(event -> assertThat(event.fields())
                        .containsEntry("failureCode", RemoteJmxDenialReason.REMOTE_JMX_EVIDENCE_UNKNOWN.name())
                        .containsEntry("phase", "activate")
                        .containsEntry("subphase", "remote-jmx-assessment"));
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
    void prepare_failure_publishes_diagnostic_mbean_until_coordinator_close() throws Exception {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        AgentInstanceId agentInstanceId =
                AgentInstanceId.parse("2545d2be-9095-4c83-9b37-c7737976a9ed");
        BootstrapCoordinator coordinator = coordinator(server, agentInstanceId);

        try (coordinator) {
            // when
            BootstrapException failure = catchThrowableOfType(
                    BootstrapException.class,
                    () -> coordinator.prepare(invalidValidationLevelConfig())
            );

            // then
            assertThat(failure.failure().code()).isEqualTo("CONFIGURATION_INITIALIZATION_FAILED");
            assertThat(coordinator.state()).isEqualTo(BootstrapState.FAILED);
            assertThat(coordinator.currentWire().lifecycle()).isEqualTo("FAILED");
            assertThat(coordinator.currentWire().failureCode())
                    .isEqualTo("CONFIGURATION_INITIALIZATION_FAILED");
            assertThat(coordinator.currentWire().agentInstanceId()).isEqualTo(agentInstanceId.toString());
            assertThat(coordinator.currentWire().batchId()).isEmpty();
            assertThat(coordinator.currentWire().evidenceGeneration()).isZero();
            assertThat(coordinator.currentWire().evidence().complete()).isFalse();
            assertThat(server.isRegistered(DomainObjectNames.EXTENSION_READINESS)).isTrue();

            CompositeData diagnostic = (CompositeData) server.getAttribute(
                    DomainObjectNames.EXTENSION_READINESS,
                    JmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT
            );

            assertThat(diagnostic.get(JmxWireContract.FIELD_READINESS_SNAPSHOT_LIFECYCLE))
                    .isEqualTo("FAILED");

            assertThat(diagnostic.get(JmxWireContract.FIELD_READINESS_SNAPSHOT_FAILURE_CODE))
                    .isEqualTo("CONFIGURATION_INITIALIZATION_FAILED");

            assertThat(diagnostic.get(JmxWireContract.FIELD_READINESS_SNAPSHOT_AGENT_INSTANCE_ID))
                    .isEqualTo(agentInstanceId.toString());

            assertThat(diagnostic.get(JmxWireContract.FIELD_READINESS_SNAPSHOT_BATCH_ID))
                    .isEqualTo("");

            assertThat(diagnostic.get(JmxWireContract.FIELD_READINESS_SNAPSHOT_EVIDENCE_GENERATION))
                    .isEqualTo(0L);
        }

        assertThat(server.isRegistered(DomainObjectNames.EXTENSION_READINESS)).isFalse();
    }

    @Test
    void repeated_prepare_after_configuration_failure_preserves_primary_cause() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        BootstrapCoordinator coordinator = coordinator(server, AgentInstanceId.random());

        try (coordinator) {
            BootstrapException first = catchThrowableOfType(
                    BootstrapException.class,
                    () -> coordinator.prepare(invalidValidationLevelConfig())
            );

            // when
            BootstrapException repeated = catchThrowableOfType(
                    BootstrapException.class,
                    () -> coordinator.prepare(invalidValidationLevelConfig())
            );

            // then
            assertThat(repeated.failure().code()).isEqualTo(first.failure().code());
            assertThat(repeated.getCause()).isSameAs(first.getCause());
            assertThat(coordinator.currentWire().failureCode()).isEqualTo(first.failure().code());
            assertThat(server.isRegistered(DomainObjectNames.EXTENSION_READINESS)).isTrue();
        }
    }

    @Test
    void diagnostic_registration_failure_is_suppressed_on_primary_configuration_cause() throws Exception {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        server.registerMBean(new Timer(), DomainObjectNames.EXTENSION_READINESS);
        BootstrapCoordinator coordinator = coordinator(server, AgentInstanceId.random());
        AgentLogCapture logs = new AgentLogCapture();
        logs.install();

        try (coordinator) {
            // when
            BootstrapException first = catchThrowableOfType(
                    BootstrapException.class,
                    () -> coordinator.prepare(invalidValidationLevelConfig())
            );
            BootstrapException repeated = catchThrowableOfType(
                    BootstrapException.class,
                    () -> coordinator.prepare(invalidValidationLevelConfig())
            );

            // then
            assertThat(first.failure().code()).isEqualTo("CONFIGURATION_INITIALIZATION_FAILED");
            assertThat(first.getCause().getSuppressed()).hasSize(1);
            assertThat(first.getCause().getSuppressed()[0])
                    .hasMessageContaining("ownership collision");
            assertThat(repeated.getCause()).isSameAs(first.getCause());
            assertThat(repeated.getCause().getSuppressed())
                    .containsExactly(first.getCause().getSuppressed()[0]);
            assertThat(coordinator.state()).isEqualTo(BootstrapState.FAILED);
            assertThat(coordinator.currentWire().lifecycle()).isEqualTo("FAILED");
            assertThat(coordinator.currentWire().failureCode())
                    .isEqualTo("CONFIGURATION_INITIALIZATION_FAILED");
            assertThat(logs.events())
                    .filteredOn(event -> "BOOTSTRAP_PHASE_FAILED".equals(event.eventName()))
                    .singleElement()
                    .satisfies(event -> assertThat(event.throwableDigest())
                            .contains(JmxRegistrationException.class.getName()));
            assertThat(logs.rendered()).doesNotContain("ownership collision");
        } finally {
            logs.restore();
        }
    }

    @Test
    void knownConfigurationFailureLogsPropertyNameWithoutPropertyValue() {
        // given
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator();
        this.ownedCoordinator = coordinator;
        AgentLogCapture logs = new AgentLogCapture();
        logs.install();

        try {
            // when
            assertThatThrownBy(() -> coordinator.prepare(config(Map.of(
                    ExtensionPropertyNames.SCRUBBING_ENABLED,
                    "false"
            )))).isInstanceOf(BootstrapException.class);

            // then
            assertThat(logs.events())
                    .filteredOn(event -> "BOOTSTRAP_PHASE_FAILED".equals(event.eventName()))
                    .singleElement()
                    .satisfies(event -> assertThat(event.fields())
                            .containsEntry("failureCode", "CONFIGURATION_INITIALIZATION_FAILED")
                            .containsEntry("phase", "prepare")
                            .containsEntry("subphase", "configuration-validation")
                            .containsEntry("propertyName", ExtensionPropertyNames.SCRUBBING_ENABLED)
                            .doesNotContainValue("false"));
            assertThat(logs.rendered()).doesNotContain("=false");
        } finally {
            logs.restore();
        }
    }

    @Test
    void oversizedStartupRouteMapPreservesLimitMessageInBootstrapException() {
        // given
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator();
        this.ownedCoordinator = coordinator;
        Map<String, String> routeRatios = new HashMap<>();
        for (int index = 0; index < 101; index++) {
            routeRatios.put("/route-" + index, "0.5");
        }

        // when
        BootstrapException failure = catchThrowableOfType(
                BootstrapException.class,
                () -> coordinator.prepare(config(Map.of(), routeRatios))
        );

        // then
        assertThat(failure.failure().code()).isEqualTo("CONFIGURATION_INITIALIZATION_FAILED");
        assertThat(failure.failure().cause())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("routeRatios must contain at most 100 entries, but had 101");
    }

    @Test
    void oversizedStartupRoutePrefixPreservesLimitMessageInBootstrapException() {
        // given
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator();
        this.ownedCoordinator = coordinator;
        Map<String, String> routeRatios = Map.of("a".repeat(256), "0.5");

        // when
        BootstrapException failure = catchThrowableOfType(
                BootstrapException.class,
                () -> coordinator.prepare(config(Map.of(), routeRatios))
        );

        // then
        assertThat(failure.failure().code()).isEqualTo("CONFIGURATION_INITIALIZATION_FAILED");
        assertThat(failure.failure().cause())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("routePrefix must contain at most 255 UTF-8 bytes, but had 256");
    }

    @Test
    void unknown_span_attribute_validation_level_classified_as_CONFIGURATION_INITIALIZATION_FAILED() {
        // given
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator();
        this.ownedCoordinator = coordinator;

        // when/then
        assertThatThrownBy(() -> coordinator.prepare(invalidValidationLevelConfig()))
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
        this.ownedCoordinator = coordinator;

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
        this.ownedCoordinator = coordinator;

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
        IllegalStateException sourceFailure = new IllegalStateException("rule source unavailable");
        BootstrapCoordinator coordinator = RuntimeEvidenceTestFixtures.coordinator(ScrubberFactoryTestFixtures.factory(() -> {
            throw sourceFailure;
        }));
        this.ownedCoordinator = coordinator;
        AgentLogCapture logs = new AgentLogCapture();
        logs.install();

        try {
            // when
            BootstrapException failure = catchThrowableOfType(
                    BootstrapException.class,
                    () -> coordinator.prepare(config(Map.of()))
            );

            // then
            assertThat(failure.failure().code()).isEqualTo("SANITIZER_RULE_LOADING_FAILED");
            assertThat(failure.failure().cause()).isInstanceOf(SanitizerBootstrapException.class);
            SanitizerBootstrapException sanitizerFailure =
                    (SanitizerBootstrapException) failure.failure().cause();
            assertThat(sanitizerFailure.getCause()).isSameAs(sourceFailure);
            assertThat(logs.events().stream()
                    .filter(event -> "Scrubbing rule collection failed".equals(event.message()))
                    .findFirst()
                    .orElseThrow()
                    .throwableDigest())
                    .contains(IllegalStateException.class.getName());
        } finally {
            logs.restore();
        }
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
        this.ownedCoordinator = coordinator;

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

    private static BootstrapCoordinator coordinator(MBeanServer server, AgentInstanceId agentInstanceId) {
        return new BootstrapCoordinator(
                new CapturingShutdownHookRegistry(),
                new PlatformScrubberFactory(),
                RuntimeEvidenceTestFixtures.trustedVerifier(),
                ignored -> RemoteJmxAssessorTestFixtures.allowingAssessor(),
                new PlatformTracingMBeanRegistrar(server, agentInstanceId)
        );
    }

    private static ConfigProperties invalidValidationLevelConfig() {
        return config(Map.of(
                ExtensionPropertyNames.SPAN_ATTRIBUTES_VALIDATION_LEVEL,
                "STRICT"
        ));
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
        return config(data, Map.of());
    }

    private static ConfigProperties config(Map<String, String> data,
                                           Map<String, String> routeRatios) {
        return new MapConfigProperties(new HashMap<>(data), new HashMap<>(routeRatios));
    }

    private record MapConfigProperties(Map<String, String> data,
                                       Map<String, String> routeRatios) implements ConfigProperties {

        private MapConfigProperties(Map<String, String> data,
                                    Map<String, String> routeRatios) {
            this.data = new HashMap<>(data);
            this.routeRatios = new HashMap<>(routeRatios);
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
        public List<String> getList(String name) {
            String value = data.get(name);

            if (value == null) {
                return null;
            }

            if (value.isBlank()) {
                return List.of();
            }

            return List.of(value.split("\\s*,\\s*"));
        }

        @Override
        public Map<String, String> getMap(String name) {
            if (ExtensionPropertyNames.SAMPLING_ROUTE_RATIOS.equals(name)) {
                return routeRatios;
            }
            return Map.of();
        }
    }

    private record RemoteJmxDenialOutcome(PlatformExtensionReadinessSnapshot readiness,
                                          List<CapturedLogEvent> events) {
    }

    private static final class CapturingShutdownHookRegistry implements ShutdownHookRegistry {

        private final AtomicReference<Runnable> action = new AtomicReference<>();
        private final RuntimeException removalFailure;

        private CapturingShutdownHookRegistry() {
            this(null);
        }

        private CapturingShutdownHookRegistry(RuntimeException removalFailure) {
            this.removalFailure = removalFailure;
        }

        @Override
        public Registration register(String threadName, Runnable registeredAction) {
            if (!action.compareAndSet(null, registeredAction)) {
                throw new IllegalStateException("Shutdown hook is already registered");
            }

            return () -> {
                if (removalFailure != null) {
                    throw removalFailure;
                }
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
