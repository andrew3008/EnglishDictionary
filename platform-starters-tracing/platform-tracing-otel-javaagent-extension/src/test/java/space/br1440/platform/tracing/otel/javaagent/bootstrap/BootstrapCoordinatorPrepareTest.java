package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.DiagnosticReadinessMBeanAssert.assertThatDiagnosticMBean;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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

    @ParameterizedTest
    @EnumSource(
            value = VerificationFailureCode.class,
            names = {"DISTRIBUTION_MISMATCH", "DEVELOPMENT_VERSION_FORBIDDEN"}
    )
    void verification_failure_during_prepare_publishes_failed_diagnostic_mbean(VerificationFailureCode failureCode) {
        // given
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
                assertThat(coordinator.session()).isSameAs(session);
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
    void reentrant_prepare_publishes_REENTRANT_OR_OUT_OF_ORDER_to_wire_and_diagnostic_mbean() {
        // given
        ConfigProperties configuration = config();
        AtomicReference<BootstrapCoordinator> coordinatorReference = new AtomicReference<>();
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        ExtensionRuntimeVerifier verifier = extensionVersion -> {
            coordinatorReference.get().prepare(configuration);
            return RuntimeEvidenceTestFixtures.evidence(extensionVersion);
        };
        BootstrapCoordinator coordinator = coordinator().server(server).verifier(verifier).build();
        coordinatorReference.set(coordinator);

        try (coordinator) {
            // when
            assertBootstrapFailure(() -> coordinator.prepare(configuration), REENTRANT_OR_OUT_OF_ORDER);

            // then
            assertThatCoordinator(coordinator).isTerminallyFailedWith(REENTRANT_OR_OUT_OF_ORDER);
            assertThatDiagnosticMBean(server).isFailedWith(REENTRANT_OR_OUT_OF_ORDER);
        }
    }

    @Test
    void activate_during_prepare_publishes_REENTRANT_OR_OUT_OF_ORDER_to_wire_and_diagnostic_mbean() {
        // given
        ConfigProperties configuration = config();
        AtomicReference<BootstrapCoordinator> coordinatorReference = new AtomicReference<>();
        MBeanServer server = MBeanServerFactory.newMBeanServer();

        try (SdkTracerProvider tracerProvider = SdkTracerProvider.builder().build()) {
            ExtensionRuntimeVerifier verifier = extensionVersion -> {
                coordinatorReference.get().activate(tracerProvider);
                return RuntimeEvidenceTestFixtures.evidence(extensionVersion);
            };
            BootstrapCoordinator coordinator = coordinator().server(server).verifier(verifier).build();
            coordinatorReference.set(coordinator);

            try (coordinator) {
                // when
                assertBootstrapFailure(() -> coordinator.prepare(configuration), REENTRANT_OR_OUT_OF_ORDER);

                // then
                assertThatCoordinator(coordinator).isTerminallyFailedWith(REENTRANT_OR_OUT_OF_ORDER);
                assertThatDiagnosticMBean(server).isFailedWith(REENTRANT_OR_OUT_OF_ORDER);
            }
        }
    }
}
