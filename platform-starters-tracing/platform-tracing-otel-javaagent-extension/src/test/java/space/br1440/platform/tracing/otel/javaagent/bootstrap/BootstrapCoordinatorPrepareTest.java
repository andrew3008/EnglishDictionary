package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorAssert.assertThatCoordinator;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.WIRE_INITIALIZING;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.assertBootstrapFailure;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.catchBootstrapFailure;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.config;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.coordinator;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.invalidValidationLevelConfig;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.providerFor;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.recordingReadinessRegistration;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.satisfyReadinessBarrier;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.CONFIGURATION_INITIALIZATION_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.DIAGNOSTIC_MBEAN_REGISTRATION_FAILED;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapFailureCodes.FAILED_ROLLBACK_INCOMPLETE;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.DiagnosticReadinessMBeanAssert.assertThatDiagnosticMBean;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import space.br1440.platform.tracing.otel.javaagent.AutoConfiguredSdkIsolationExtension;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.verification.ExtensionRuntimeVerifier;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.verification.VerificationException;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.verification.VerificationFailureCode;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.verification.VerificationTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.control.AcceptedPolicyMutationAccess;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly.PlatformScrubberFactory;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
class BootstrapCoordinatorPrepareTest {

    private static final String SECOND_SDK_BUILD = "SECOND_SDK_BUILD";
    private static final String REENTRANT_OR_OUT_OF_ORDER = "REENTRANT_OR_OUT_OF_ORDER";

    @Test
    void separate_bootstrap_owners_receive_different_agent_instance_ids() {
        // given
        try (BootstrapCoordinator first = coordinator().build();
             BootstrapCoordinator second = coordinator().build()) {
            // when
            AgentInstanceId firstId = first.prepare(config()).agentInstanceId();
            AgentInstanceId secondId = second.prepare(config()).agentInstanceId();

            // then
            assertThat(firstId).isNotEqualTo(secondId);
        }
    }

    @Test
    void linkage_error_during_runtime_attestation_publishes_failed_before_propagation() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        BootstrapCoordinator coordinator = coordinator()
                .server(server)
                .verifier(extensionVersion -> {
                    throw new NoClassDefFoundError("simulated runtime evidence linkage failure");
                })
                .build();

        try (coordinator) {
            // when
            assertThatThrownBy(() -> coordinator.prepare(config()))
                    .isInstanceOf(NoClassDefFoundError.class)
                    .hasMessage("simulated runtime evidence linkage failure");

            // then
            assertThatCoordinator(coordinator).isTerminallyFailedWith(VerificationFailureCode.EXTENSION_LINKAGE_ERROR.name());
            assertThatDiagnosticMBean(server).isRegistered();
        }

        assertThatDiagnosticMBean(server).isNotRegistered();
    }

    @Test
    void fatal_initializing_publication_failure_remains_primary_when_diagnostic_registration_fails() {
        // given
        NoClassDefFoundError publicationFailure =
                new NoClassDefFoundError("Readiness initialization publication failed");
        NoClassDefFoundError registrationFailure =
                new NoClassDefFoundError("Diagnostic readiness registration failed");
        PlatformExtensionReadiness readiness = spy(PlatformExtensionReadiness.forCurrentExtension());
        doThrow(publicationFailure).when(readiness).snapshot();

        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        PlatformTracingMBeanRegistrar registrar = mock(PlatformTracingMBeanRegistrar.class);
        when(registrar.extensionReadiness()).thenReturn(readiness);
        when(registrar.agentInstanceId()).thenReturn(agentInstanceId);
        when(registrar.registerDiagnosticReadiness(any())).thenThrow(registrationFailure);

        try (BootstrapCoordinator coordinator = coordinator().registrar(registrar).build()) {
            // when
            Throwable thrown = catchThrowable(() -> coordinator.prepare(config()));

            // then
            assertThat(thrown).isSameAs(publicationFailure);
            assertThat(publicationFailure.getSuppressed()).containsExactly(registrationFailure);
            assertThatCoordinator(coordinator)
                    .isTerminallyFailedWith(VerificationFailureCode.EXTENSION_LINKAGE_ERROR.name());
            assertThat(coordinator.currentWire().primaryFailureCode())
                    .isEqualTo(VerificationFailureCode.EXTENSION_LINKAGE_ERROR.name());
            assertThat(coordinator.currentWire().failureCode())
                    .isEqualTo(VerificationFailureCode.EXTENSION_LINKAGE_ERROR.name());
        }
    }

    @Test
    void verificationFailureDuringPreparePublishesFailedDiagnosticMBean() {
        // given
        VerificationFailureCode failureCode = VerificationFailureCode.DISTRIBUTION_MISMATCH;
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        VerificationException verifierFailure = VerificationTestFixtures.failure(failureCode);
        BootstrapCoordinator coordinator = coordinator()
                .server(server)
                .agentInstanceId(agentInstanceId)
                .verifier(ignored -> {
                    throw verifierFailure;
                })
                .build();

        try (coordinator) {
            // when
            BootstrapException failure = catchBootstrapFailure(
                    () -> coordinator.prepare(config()),
                    failureCode.name()
            );

            // then
            assertThat(failure.getCause()).isSameAs(verifierFailure);
            assertThatCoordinator(coordinator).isTerminallyFailedWith(failureCode.name());
            assertThat(coordinator.currentWire().agentInstanceId()).isEqualTo(agentInstanceId.toString());
            assertThatDiagnosticMBean(server).isFailedWith(failureCode.name());
        }

        assertThatDiagnosticMBean(server).isNotRegistered();
    }

    @Test
    void prepare_failure_publishes_diagnostic_mbean_until_coordinator_close() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        AgentInstanceId agentInstanceId = AgentInstanceId.parse("2545d2be-9095-4c83-9b37-c7737976a9ed");
        BootstrapCoordinator coordinator = coordinator().server(server).agentInstanceId(agentInstanceId).build();

        try (coordinator) {
            // when
            assertBootstrapFailure(
                    () -> coordinator.prepare(invalidValidationLevelConfig()),
                    CONFIGURATION_INITIALIZATION_FAILED
            );

            // then
            assertThatCoordinator(coordinator).isTerminallyFailedWith(CONFIGURATION_INITIALIZATION_FAILED);
            assertThat(coordinator.currentWire().agentInstanceId()).isEqualTo(agentInstanceId.toString());
            assertThat(coordinator.currentWire().batchId()).isEmpty();
            assertThat(coordinator.currentWire().evidenceGeneration()).isZero();
            assertThat(coordinator.currentWire().evidence().complete()).isFalse();
            assertThatDiagnosticMBean(server)
                    .isFailedWith(CONFIGURATION_INITIALIZATION_FAILED)
                    .hasSnapshotEntry(
                            JmxWireContract.FIELD_READINESS_SNAPSHOT_AGENT_INSTANCE_ID,
                            agentInstanceId.toString()
                    )
                    .hasSnapshotEntry(JmxWireContract.FIELD_READINESS_SNAPSHOT_BATCH_ID, "")
                    .hasSnapshotEntry(JmxWireContract.FIELD_READINESS_SNAPSHOT_EVIDENCE_GENERATION, 0L);
        }

        assertThatDiagnosticMBean(server).isNotRegistered();
    }

    @Test
    void repeated_prepare_after_configuration_failure_preserves_primary_cause() {
        // given
        MBeanServer server = MBeanServerFactory.newMBeanServer();

        try (BootstrapCoordinator coordinator = coordinator().server(server).build()) {
            BootstrapException first = catchBootstrapFailure(
                    () -> coordinator.prepare(invalidValidationLevelConfig()),
                    CONFIGURATION_INITIALIZATION_FAILED
            );

            // when
            BootstrapException repeated = catchBootstrapFailure(
                    () -> coordinator.prepare(invalidValidationLevelConfig()),
                    CONFIGURATION_INITIALIZATION_FAILED
            );

            // then
            assertThat(repeated.getCause()).isSameAs(first.getCause());
            assertThat(coordinator.currentWire().failureCode()).isEqualTo(CONFIGURATION_INITIALIZATION_FAILED);
            assertThatDiagnosticMBean(server).isRegistered();
        }
    }

    @Test
    void unknown_span_attribute_validation_level_classified_as_CONFIGURATION_INITIALIZATION_FAILED() {
        // given
        try (BootstrapCoordinator coordinator = coordinator().build()) {
            // when
            assertBootstrapFailure(
                    () -> coordinator.prepare(invalidValidationLevelConfig()),
                    CONFIGURATION_INITIALIZATION_FAILED
            );

            // then
            assertThat(coordinator.state()).isEqualTo(BootstrapState.FAILED);
        }
    }

    @Test
    void oversized_startup_route_map_preserves_limit_message_in_bootstrap_exception() {
        // given
        Map<String, String> routeRatios = new HashMap<>();
        for (int index = 0; index < 101; index++) {
            routeRatios.put("/route-" + index, "0.5");
        }

        try (BootstrapCoordinator coordinator = coordinator().build()) {
            // when
            BootstrapException failure = catchBootstrapFailure(
                    () -> coordinator.prepare(config(Map.of(), routeRatios)),
                    CONFIGURATION_INITIALIZATION_FAILED
            );

            // then
            assertThat(failure.failure().cause())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("routeRatios must contain at most 100 entries, but had 101");
        }
    }

    @Test
    void oversized_startup_route_prefix_preserves_limit_message_in_bootstrap_exception() {
        // given
        Map<String, String> routeRatios = Map.of("a".repeat(256), "0.5");

        try (BootstrapCoordinator coordinator = coordinator().build()) {
            // when
            BootstrapException failure = catchBootstrapFailure(
                    () -> coordinator.prepare(config(Map.of(), routeRatios)),
                    CONFIGURATION_INITIALIZATION_FAILED
            );

            // then
            assertThat(failure.failure().cause())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("routePrefix must contain at most 255 UTF-8 bytes, but had 256");
        }
    }

    @Test
    void second_prepare_with_identical_configuration_classified_as_SECOND_SDK_BUILD() {
        // given
        try (BootstrapCoordinator coordinator = coordinator().build()) {
            coordinator.prepare(config());

            // when/then
            assertBootstrapFailure(() -> coordinator.prepare(config()), SECOND_SDK_BUILD);
        }
    }

    @Test
    void second_prepare_rejection_preserves_original_updated_policy_store() {
        // given
        try (BootstrapCoordinator coordinator = coordinator().build()) {
            AutoconfigureSession originalSession = coordinator.prepare(config());
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
            assertBootstrapFailure(() -> coordinator.prepare(config()), SECOND_SDK_BUILD);
            AutoconfigureSession retainedSession = coordinator.session();

            // then
            assertThat(retainedSession).isSameAs(originalSession);
            assertThat(retainedSession.policy()).isSameAs(originalSource);
            assertThat(retainedSession.policy().store()).isSameAs(originalStore);
            assertThat(retainedSession.agentInstanceId()).isEqualTo(originalAgentInstanceId);
            assertThat(retainedSession.policy().current()).isSameAs(acceptedRevision);
            assertThat(retainedSession.policy().current().agentInstanceId()).isEqualTo(originalAgentInstanceId);
            assertThat(retainedSession.policy().current().acceptedSequence()).isEqualTo(1L);
        }
    }

    @Test
    void second_prepare_is_rejected_as_SECOND_SDK_BUILD_regardless_of_configuration() {
        // given
        try (BootstrapCoordinator coordinator = coordinator().build()) {
            AutoconfigureSession originalSession = coordinator.prepare(config());

            // when
            assertBootstrapFailure(
                    () -> coordinator.prepare(config(Map.of(ExtensionPropertyNames.SAMPLING_RATIO, "0.42"))),
                    SECOND_SDK_BUILD
            );

            // then
            assertThat(coordinator.state()).isEqualTo(BootstrapState.PREPARED);
            assertThat(coordinator.currentWire().lifecycle()).isEqualTo(WIRE_INITIALIZING);
            assertThat(coordinator.session()).isSameAs(originalSession);

            // when
            satisfyReadinessBarrier(originalSession);
            try (SdkTracerProvider tracerProvider = providerFor(originalSession)) {
                coordinator.activate(tracerProvider);

                // then
                assertThatCoordinator(coordinator).isActiveAndReady();
            }
        }
    }

    @Test
    void second_prepare_after_activation_keeps_ACTIVE_and_READY() {
        // given
        try (BootstrapCoordinator coordinator = coordinator().build()) {
            AutoconfigureSession session = coordinator.prepare(config());
            satisfyReadinessBarrier(session);

            try (SdkTracerProvider tracerProvider = providerFor(session)) {
                coordinator.activate(tracerProvider);
                String batchId = coordinator.currentWire().batchId();
                long evidenceGeneration = coordinator.currentWire().evidenceGeneration();

                // when
                assertBootstrapFailure(() -> coordinator.prepare(config()), SECOND_SDK_BUILD);

                // then
                assertThatCoordinator(coordinator).isActiveAndReady();
                assertThat(coordinator.currentWire().batchId()).isEqualTo(batchId);
                assertThat(coordinator.currentWire().evidenceGeneration()).isEqualTo(evidenceGeneration);
                assertThat(coordinator.currentWire().evidence()).isEqualTo(session.evidence());
            }
        }
    }

    @Test
    void failed_lifecycle_precedes_diagnostic_readiness_registration_on_preparation_failure() {
        // given
        List<String> observations = new ArrayList<>();
        AtomicReference<BootstrapCoordinator> coordinatorReference = new AtomicReference<>();
        MBeanServer server = recordingReadinessRegistration(coordinatorReference, observations);
        VerificationException verifierFailure =
                VerificationTestFixtures.failure(VerificationFailureCode.DISTRIBUTION_MISMATCH);
        BootstrapCoordinator coordinator = coordinator()
                .server(server)
                .verifier(ignored -> {
                    throw verifierFailure;
                })
                .build();
        coordinatorReference.set(coordinator);

        try (coordinator) {
            // when
            BootstrapException failure = catchBootstrapFailure(
                    () -> coordinator.prepare(config()),
                    VerificationFailureCode.DISTRIBUTION_MISMATCH.name()
            );

            // then
            assertThat(observations).containsExactly("FAILED/FAILED/DISTRIBUTION_MISMATCH");
            assertThat(failure.getCause()).isSameAs(verifierFailure);
            assertThatDiagnosticMBean(server).isRegistered();
        }
    }

    @Test
    void reentrant_prepare_is_rejected_without_terminalizing_or_registering_diagnostics() {
        // given
        ConfigProperties configuration = config();
        AtomicReference<BootstrapCoordinator> coordinatorReference = new AtomicReference<>();
        AtomicReference<BootstrapException> nestedRejection = new AtomicReference<>();
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        ExtensionRuntimeVerifier verifier = extensionVersion -> {
            BootstrapException rejection = catchBootstrapFailure(
                    () -> {
                        //noinspection resource — повторный prepare отказывает до выдачи сессии; владение остаётся у координатора
                        coordinatorReference.get().prepare(configuration);
                    },
                    REENTRANT_OR_OUT_OF_ORDER
            );
            nestedRejection.set(rejection);
            throw rejection;
        };
        BootstrapCoordinator coordinator = coordinator().server(server).verifier(verifier).build();
        coordinatorReference.set(coordinator);

        try (coordinator) {
            // when
            BootstrapException propagated = catchBootstrapFailure(
                    () -> coordinator.prepare(configuration),
                    REENTRANT_OR_OUT_OF_ORDER
            );

            // then
            assertThat(propagated).isSameAs(nestedRejection.get());
            assertThat(propagated.getCause()).isSameAs(nestedRejection.get().getCause());
            assertThat(propagated.failure().primaryCode()).isEqualTo(REENTRANT_OR_OUT_OF_ORDER);
            assertThat(propagated.failure().effectiveCode()).isEqualTo(REENTRANT_OR_OUT_OF_ORDER);
            assertThat(coordinator.state()).isEqualTo(BootstrapState.PREPARING);
            assertThat(coordinator.currentWire().lifecycle()).isEqualTo(WIRE_INITIALIZING);
            assertThat(coordinator.currentWire().primaryFailureCode()).isEmpty();
            assertThat(coordinator.currentWire().failureCode()).isEmpty();
            assertThatDiagnosticMBean(server).isNotRegistered();
        }

        assertThatDiagnosticMBean(server).isNotRegistered();
    }

    @Test
    void activate_during_prepare_is_rejected_without_terminalizing_or_registering_diagnostics() {
        // given
        ConfigProperties configuration = config();
        AtomicReference<BootstrapCoordinator> coordinatorReference = new AtomicReference<>();
        AtomicReference<BootstrapException> nestedRejection = new AtomicReference<>();
        MBeanServer server = MBeanServerFactory.newMBeanServer();

        try (SdkTracerProvider tracerProvider = SdkTracerProvider.builder().build()) {
            ExtensionRuntimeVerifier verifier = extensionVersion -> {
                BootstrapException rejection = catchBootstrapFailure(
                        () -> coordinatorReference.get().activate(tracerProvider),
                        REENTRANT_OR_OUT_OF_ORDER
                );
                nestedRejection.set(rejection);
                throw rejection;
            };
            BootstrapCoordinator coordinator = coordinator().server(server).verifier(verifier).build();
            coordinatorReference.set(coordinator);

            try (coordinator) {
                // when
                BootstrapException propagated = catchBootstrapFailure(
                        () -> coordinator.prepare(configuration),
                        REENTRANT_OR_OUT_OF_ORDER
                );

                // then
                assertThat(propagated).isSameAs(nestedRejection.get());
                assertThat(propagated.getCause()).isSameAs(nestedRejection.get().getCause());
                assertThat(propagated.failure().primaryCode()).isEqualTo(REENTRANT_OR_OUT_OF_ORDER);
                assertThat(propagated.failure().effectiveCode()).isEqualTo(REENTRANT_OR_OUT_OF_ORDER);
                assertThat(coordinator.state()).isEqualTo(BootstrapState.PREPARING);
                assertThat(coordinator.currentWire().lifecycle()).isEqualTo(WIRE_INITIALIZING);
                assertThat(coordinator.currentWire().primaryFailureCode()).isEmpty();
                assertThat(coordinator.currentWire().failureCode()).isEmpty();
                assertThatDiagnosticMBean(server).isNotRegistered();
            }
        }

        assertThatDiagnosticMBean(server).isNotRegistered();
    }

    @Test
    void close_during_prepare_waits_for_session_ownership_and_closes_runtime() throws Exception {
        // given
        CountDownLatch runtimeCreated = new CountDownLatch(1);
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch continuePrepare = new CountDownLatch(1);
        PlatformScrubberFactory scrubberFactory = mock(PlatformScrubberFactory.class);
        ScrubbingRuntime scrubbingRuntime = mock(ScrubbingRuntime.class);

        //noinspection resource — buildRuntime в when() только регистрирует стаб и не создаёт runtime
        doAnswer(invocation -> {
            runtimeCreated.countDown();
            if (!continuePrepare.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Prepare continuation was not released");
            }

            return scrubbingRuntime;
        }).when(scrubberFactory).buildRuntime(any());

        BootstrapCoordinator coordinator = coordinator()
                .scrubberFactory(scrubberFactory)
                .build();

        FutureTask<Throwable> prepare = new FutureTask<>(
                () -> catchThrowable(() -> coordinator.prepare(config()))
        );

        Thread prepareThread = Thread.ofPlatform()
                .daemon()
                .name("bootstrap-prepare-close-race")
                .unstarted(prepare);

        FutureTask<Throwable> close = new FutureTask<>(() -> {
            closeStarted.countDown();
            return catchThrowable(coordinator::close);
        });

        Thread closeThread = Thread.ofPlatform()
                .daemon()
                .name("bootstrap-close-prepare-race")
                .unstarted(close);

        try (coordinator) {
            prepareThread.start();
            assertThat(runtimeCreated.await(5, TimeUnit.SECONDS)).isTrue();

            // when
            closeThread.start();
            assertThat(closeStarted.await(5, TimeUnit.SECONDS)).isTrue();
            await().pollDelay(Duration.ZERO)
                    .pollInterval(Duration.ofMillis(5))
                    .atMost(Duration.ofSeconds(5))
                    .until(() -> closeThread.getState() == Thread.State.WAITING);
            continuePrepare.countDown();
            prepareThread.join(TimeUnit.SECONDS.toMillis(5));
            closeThread.join(TimeUnit.SECONDS.toMillis(5));

            // then
            assertThat(prepareThread.isAlive()).isFalse();
            assertThat(closeThread.isAlive()).isFalse();
            assertThat(prepare.get(5, TimeUnit.SECONDS)).isNull();
            assertThat(close.get(5, TimeUnit.SECONDS)).isNull();
            assertThat(coordinator.state()).isEqualTo(BootstrapState.CLOSED);
            verify(scrubbingRuntime).close();
        } finally {
            continuePrepare.countDown();
        }
    }

    @Test
    void fatal_preparation_cleanup_failure_is_settled_before_propagation() {
        // given
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        PlatformTracingMBeanRegistrar registrar = spy(new PlatformTracingMBeanRegistrar(
                MBeanServerFactory.newMBeanServer(),
                agentInstanceId
        ));

        IllegalStateException registrationFailure = new IllegalStateException(
                "Simulated diagnostic registration failure"
        );

        //noinspection resource — registerDiagnosticReadiness в when() только регистрирует стаб и не создаёт регистрацию
        doThrow(registrationFailure).when(registrar).registerDiagnosticReadiness(any());

        PlatformScrubberFactory scrubberFactory = mock(PlatformScrubberFactory.class);
        ScrubbingRuntime scrubbingRuntime = mock(ScrubbingRuntime.class);
        when(scrubberFactory.buildRuntime(any())).thenReturn(scrubbingRuntime);
        NoClassDefFoundError cleanupFailure = new NoClassDefFoundError(
                "Fatal scrubbing runtime cleanup failure"
        );
        doThrow(cleanupFailure).when(scrubbingRuntime).close();

        try (BootstrapCoordinator coordinator = coordinator()
                .agentInstanceId(agentInstanceId)
                .registrar(registrar)
                .scrubberFactory(scrubberFactory)
                .build()) {
            // when
            assertThatThrownBy(() -> coordinator.prepare(config())).isSameAs(cleanupFailure);

            // then
            assertThat(coordinator.state()).isEqualTo(BootstrapState.FAILED);
            assertThat(coordinator.currentWire().primaryFailureCode())
                    .isEqualTo(DIAGNOSTIC_MBEAN_REGISTRATION_FAILED);
            assertThat(coordinator.currentWire().failureCode()).isEqualTo(FAILED_ROLLBACK_INCOMPLETE);
            assertThat(cleanupFailure.getSuppressed())
                    .anySatisfy(suppressed -> assertThat(suppressed)
                            .isInstanceOf(BootstrapException.class)
                            .hasCause(registrationFailure));
            verify(scrubbingRuntime).close();
        }
    }
}
