package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorAssert.assertThatCoordinator;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.config;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.coordinator;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.assertBootstrapFailure;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.BootstrapCoordinatorTestSupport.providerFor;
import static space.br1440.platform.tracing.otel.javaagent.bootstrap.DiagnosticReadinessMBeanAssert.assertThatDiagnosticMBean;
import static space.br1440.platform.tracing.otel.javaagent.readiness.ReadinessSnapshotAssertions.failureOf;

import java.util.List;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.AutoConfiguredSdkIsolationExtension;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxAssessorTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxDenialReason;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxMutationRegistrationAssessor;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.CapturedLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadinessSnapshot;

import io.opentelemetry.sdk.trace.SdkTracerProvider;

@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class BootstrapCoordinatorRemoteJmxGateTest {

    @RegisterExtension
    final AgentLogCapture logs = new AgentLogCapture();

    @Test
    void remote_jmx_gate_denial_publishes_failed_diagnostic_snapshot_before_runtime_registration() {
        // when
        RemoteJmxDenialOutcome outcome = activateAndExpectRemoteJmxFailure(
                RemoteJmxAssessorTestFixtures.managementAgentAssessor(),
                RemoteJmxDenialReason.REMOTE_JMX_MANAGEMENT_AGENT_CONFIGURED
        );

        // then
        assertThat(failureOf(outcome.readiness()).effectiveCode())
                .isEqualTo(RemoteJmxDenialReason.REMOTE_JMX_MANAGEMENT_AGENT_CONFIGURED.name());
        assertThat(singleRemoteJmxDenialEvent(outcome).fields())
                .containsEntry("failureCode", RemoteJmxDenialReason.REMOTE_JMX_MANAGEMENT_AGENT_CONFIGURED.name())
                .containsEntry("remediation", RemoteJmxAssessorTestFixtures.REMEDIATION_MANAGEMENT_AGENT)
                .containsEntry("offendingArguments", "-Dcom.sun.management.jmxremote.port");
    }

    @Test
    void remote_jmx_capture_failure_is_published_as_classified_evidence_unknown_denial() {
        // when
        RemoteJmxDenialOutcome outcome = activateAndExpectRemoteJmxFailure(
                RemoteJmxAssessorTestFixtures.captureUnavailableAssessor(),
                RemoteJmxDenialReason.REMOTE_JMX_EVIDENCE_UNKNOWN
        );

        // then
        assertThat(failureOf(outcome.readiness()).effectiveCode())
                .isEqualTo(RemoteJmxDenialReason.REMOTE_JMX_EVIDENCE_UNKNOWN.name());
        assertThat(singleRemoteJmxDenialEvent(outcome).fields())
                .containsEntry("failureCode", RemoteJmxDenialReason.REMOTE_JMX_EVIDENCE_UNKNOWN.name())
                .containsEntry("remediation", RemoteJmxAssessorTestFixtures.REMEDIATION_CAPTURE_UNAVAILABLE)
                .containsEntry("offendingArguments", "");
    }

    @Test
    void remote_jmx_denial_event_reports_argument_names_without_values() {
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
    void unhandled_remote_jmx_phase_error_publishes_evidence_unknown_without_registering_mutation() {
        // when
        RemoteJmxDenialOutcome outcome = activateAndExpectRemoteJmxFailure(
                RemoteJmxAssessorTestFixtures.explodingAssessor(),
                RemoteJmxDenialReason.REMOTE_JMX_EVIDENCE_UNKNOWN
        );

        // then
        assertThat(failureOf(outcome.readiness()).effectiveCode())
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
        MBeanServer server = MBeanServerFactory.newMBeanServer();

        try (BootstrapCoordinator coordinator = coordinator().server(server).assessor(assessor).build()) {
            AutoconfigureSession session = coordinator.prepare(config());

            try (SdkTracerProvider tracerProvider = providerFor(session)) {
                assertBootstrapFailure(() -> coordinator.activate(tracerProvider), expectedReason.name());
            }

            assertThatCoordinator(coordinator).hasWireFailure(expectedReason.name());
            assertThat(coordinator.currentWire().batchId()).isEmpty();
            assertThat(failureOf(session.readiness().snapshot()).effectiveCode()).isEqualTo(expectedReason.name());
            assertThat(server.isRegistered(DomainObjectNames.SETTINGS_MUTATION))
                    .as("mutation surface must stay unregistered after gate denial")
                    .isFalse();
            assertThatDiagnosticMBean(server).isRegistered();
            return new RemoteJmxDenialOutcome(session.readiness().snapshot(), logs.events());
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

    private record RemoteJmxDenialOutcome(PlatformExtensionReadinessSnapshot readiness,
                                          List<CapturedLogEvent> events) {
    }
}
