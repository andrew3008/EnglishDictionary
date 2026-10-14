package space.br1440.platform.tracing.autoconfigure.control.jmx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;
import javax.management.MBeanServer;
import javax.management.openmbean.CompositeData;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceTestSupport;
import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsRevision;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsContractViolationException;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsDelivery;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsWriter;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.autoconfigure.support.FakeAgentPlatformJmxPlane;
import space.br1440.platform.tracing.autoconfigure.support.ReadinessJmxTestFixtures;
import space.br1440.platform.tracing.autoconfigure.support.SettingsJmxTestFixtures;

final class PlatformJmxTracingSettingsClientWireGuardTest {

    @Test
    void statusDecodeFailureDoesNotLatchAndNextMutationReachesTransport() throws Exception {
        // given
        String agentInstanceId = UUID.randomUUID().toString();
        try (FakeAgentPlatformJmxPlane plane =
                     FakeAgentPlatformJmxPlane.unknownAuthorityAcceptedPolicySnapshot(agentInstanceId)) {
            PlatformJmxTracingSettingsClient client = plane.client();

            // when
            assertThatThrownBy(client::current)
                    .isInstanceOf(TracingSettingsContractViolationException.class)
                    .hasMessageStartingWith("JMX_CLIENT_STATUS_RESPONSE_DECODE_FAILED:");
            SettingsDelivery delivery = client.applySnapshot(
                    new SettingsRevision(agentInstanceId, 0L),
                    settings(),
                    SettingsWriter.CONFIG_SERVER);

            // then
            assertThat(delivery.outcome()).isEqualTo(TracingSettingsDeliveryResult.APPLIED);
            assertThat(plane.applyInvocationCount()).isEqualTo(1);
        }
    }

    @Test
    void mutationRejectsNonNeutralResponseBeforeResponseDecoder() throws Exception {
        // given
        MBeanServer server = initializedServer();
        when(server.invoke(
                eq(JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME),
                eq(JmxWireContract.OPERATION_SETTINGS_MUTATION_APPLY_SNAPSHOT),
                any(Object[].class),
                any(String[].class)))
                .thenReturn(new IllegalStateException("foreign throwable"));
        PlatformJmxTracingSettingsClient client = initializedClient(server);

        // when
        // then
        assertThatThrownBy(() -> client.applySnapshot(
                new SettingsRevision(ReadinessJmxTestFixtures.AGENT_INSTANCE_ID, 0L),
                settings(),
                SettingsWriter.CONFIG_SERVER))
                .isInstanceOf(TracingSettingsContractViolationException.class)
                .hasMessageStartingWith("JMX_CLIENT_MUTATION_JMX_WIRE_TYPE_VIOLATION:");
        assertThatThrownBy(() -> client.applySnapshot(
                new SettingsRevision(ReadinessJmxTestFixtures.AGENT_INSTANCE_ID, 0L),
                settings(),
                SettingsWriter.CONFIG_SERVER))
                .isInstanceOf(TracingConfigurationGuardException.class)
                .hasMessageContaining("JMX_CLIENT_TERMINALLY_INVALIDATED")
                .hasMessageContaining("JMX_CLIENT_MUTATION_JMX_WIRE_TYPE_VIOLATION");
    }

    @Test
    void mutationRejectsForeignCompositeTypeResponseAsContractViolationNotDeliveryFailure() throws Exception {
        // given
        String agentInstanceId = UUID.randomUUID().toString();
        try (FakeAgentPlatformJmxPlane plane =
                     FakeAgentPlatformJmxPlane.malformedApplySnapshotResponse(agentInstanceId)) {
            PlatformJmxTracingSettingsClient client = plane.client();
            SettingsRevision expectedBase = new SettingsRevision(agentInstanceId, 0L);

            // when
            // then
            assertThatThrownBy(() -> client.applySnapshot(
                    expectedBase,
                    settings(),
                    SettingsWriter.CONFIG_SERVER))
                    .as("a foreign CompositeType response MUST be a contract violation, not a plain FAILED result")
                    .isInstanceOf(TracingSettingsContractViolationException.class)
                    .hasMessageStartingWith("JMX_CLIENT_MUTATION_RESPONSE_DECODE_FAILED:");

            assertThatThrownBy(() -> client.applySnapshot(
                    expectedBase,
                    settings(),
                    SettingsWriter.CONFIG_SERVER))
                    .as("trust MUST be terminally invalidated after a contract violation, without retry")
                    .isInstanceOf(TracingConfigurationGuardException.class)
                    .hasMessageContaining("JMX_CLIENT_TERMINALLY_INVALIDATED")
                    .hasMessageContaining("JMX_CLIENT_MUTATION_RESPONSE_CONTRACT_FAILED");
        }
    }

    @Test
    void mutationRejectsNeutralNonCompositeResponseAsResponseTypeInvalid() throws Exception {
        // given
        MBeanServer server = initializedServer();
        when(server.invoke(
                eq(JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME),
                eq(JmxWireContract.OPERATION_SETTINGS_MUTATION_APPLY_SNAPSHOT),
                any(Object[].class),
                any(String[].class)))
                .thenReturn("not-a-composite-response");
        PlatformJmxTracingSettingsClient client = initializedClient(server);

        // when
        // then
        assertThatThrownBy(() -> client.applySnapshot(
                new SettingsRevision(ReadinessJmxTestFixtures.AGENT_INSTANCE_ID, 0L),
                settings(),
                SettingsWriter.CONFIG_SERVER))
                .isInstanceOf(TracingSettingsContractViolationException.class)
                .hasMessageStartingWith("JMX_CLIENT_MUTATION_RESPONSE_TYPE_INVALID:");

        assertThatThrownBy(() -> client.applySnapshot(
                new SettingsRevision(ReadinessJmxTestFixtures.AGENT_INSTANCE_ID, 0L),
                settings(),
                SettingsWriter.CONFIG_SERVER))
                .isInstanceOf(TracingConfigurationGuardException.class)
                .hasMessageContaining("JMX_CLIENT_TERMINALLY_INVALIDATED")
                .hasMessageContaining("JMX_CLIENT_MUTATION_RESPONSE_CONTRACT_FAILED");
    }

    @Test
    void mutationNullSnapshotFailsEncodingWithoutInvokingProvider() throws Exception {
        // given
        MBeanServer server = initializedServer();
        PlatformJmxTracingSettingsClient client = initializedClient(server);

        // when
        // then
        assertThatThrownBy(() -> client.applySnapshot(
                new SettingsRevision(ReadinessJmxTestFixtures.AGENT_INSTANCE_ID, 0L),
                null,
                SettingsWriter.CONFIG_SERVER))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("snapshot");

        verify(server, never()).invoke(
                eq(JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME),
                eq(JmxWireContract.OPERATION_SETTINGS_MUTATION_APPLY_SNAPSHOT),
                any(Object[].class),
                any(String[].class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPLIED", "UNCHANGED", "REJECTED", "STALE", "FAILED"})
    void mutationOutcomeRoundTripsThroughRealAgentResponseDecoding(String outcome) throws Exception {
        // given
        String agentInstanceId = UUID.randomUUID().toString();
        try (FakeAgentPlatformJmxPlane plane = FakeAgentPlatformJmxPlane.compliant(agentInstanceId, 0L)) {
            plane.setNextApplyOutcome(outcome);
            PlatformJmxTracingSettingsClient client = plane.client();
            SettingsRevision expectedBase = new SettingsRevision(agentInstanceId, 0L);

            // when
            SettingsDelivery delivery =
                    client.applySnapshot(
                            expectedBase,
                            settings(),
                            SettingsWriter.CONFIG_SERVER);

            // then
            assertThat(delivery.outcome()).isEqualTo(TracingSettingsDeliveryResult.valueOf(outcome));
            assertThat(delivery.attemptId()).isNotBlank();
            assertThat(UUID.fromString(delivery.attemptId()).toString()).isEqualTo(delivery.attemptId());
            assertThat(delivery.messageCode()).isEqualTo(expectedMessageCode(outcome));
        }
    }

    @ParameterizedTest
    @EnumSource(SettingsWriter.class)
    void mutationEncodesWriterAndRevisionAcceptedByRealAgentTypes(SettingsWriter writer) throws Exception {
        // given
        MBeanServer server = initializedServer();
        ArgumentCaptor<Object[]> requestCaptor = ArgumentCaptor.forClass(Object[].class);
        when(server.invoke(
                eq(JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME),
                eq(JmxWireContract.OPERATION_SETTINGS_MUTATION_APPLY_SNAPSHOT),
                requestCaptor.capture(),
                any(String[].class)))
                .thenReturn(null);
        PlatformJmxTracingSettingsClient client = initializedClient(server);
        SettingsRevision expectedBase =
                new SettingsRevision(ReadinessJmxTestFixtures.AGENT_INSTANCE_ID, 0L);

        // when
        // then
        assertThatThrownBy(() -> client.applySnapshot(
                expectedBase,
                settings(),
                writer))
                .isInstanceOf(TracingSettingsContractViolationException.class);

        CompositeData request = (CompositeData) requestCaptor.getValue()[0];
        String encodedWriter = (String) request.get(JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_WRITER);
        var agentWriter =
                space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsWriter.valueOf(
                        encodedWriter
                );
        var agentRevision =
                new space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsRevision(
                        space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId.parse(
                                (String) request.get(
                                        JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_EXPECTED_AGENT_INSTANCE_ID
                                )
                        ),
                        (Long) request.get(
                                JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_EXPECTED_ACCEPTED_SEQUENCE
                        )
                );

        assertThat(agentWriter.name()).isEqualTo(writer.name());
        assertThat(agentRevision.agentInstanceId().toString()).isEqualTo(expectedBase.agentInstanceId());
        assertThat(agentRevision.acceptedSequence()).isEqualTo(expectedBase.acceptedSequence());
    }

    @Test
    void applyBeforeMetadataGateReturnsCorrelatedClientFailure() throws Exception {
        // given
        MBeanServer server = initializedServer();
        AgentReadinessCoherenceGuard coherenceGuard = new AgentReadinessCoherenceGuard(server);
        AgentReadinessCoherenceTestSupport.pinCurrent(coherenceGuard);
        PlatformJmxTracingSettingsClient client =
                new PlatformJmxTracingSettingsClient(server, coherenceGuard);

        // when
        SettingsDelivery delivery = client.applySnapshot(
                new SettingsRevision(ReadinessJmxTestFixtures.AGENT_INSTANCE_ID, 0L),
                settings(),
                SettingsWriter.CONFIG_SERVER
        );

        // then
        assertThat(delivery.outcome()).isEqualTo(TracingSettingsDeliveryResult.FAILED);
        assertThat(delivery.messageCode()).isEqualTo("JMX_CLIENT_METADATA_GATE_FAILED");
        assertThat(UUID.fromString(delivery.attemptId()).toString()).isEqualTo(delivery.attemptId());
        verify(server, never()).invoke(
                eq(JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME),
                eq(JmxWireContract.OPERATION_SETTINGS_MUTATION_APPLY_SNAPSHOT),
                any(Object[].class),
                any(String[].class)
        );
    }

    @Test
    void legacyMutationParameterTypeFailsMetadataGate() throws Exception {
        // given
        MBeanServer server = initializedServer();
        when(server.getMBeanInfo(JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME))
                .thenReturn(SettingsJmxTestFixtures.legacyMutationInfo());
        AgentReadinessCoherenceGuard coherenceGuard = new AgentReadinessCoherenceGuard(server);
        AgentReadinessCoherenceTestSupport.pinCurrent(coherenceGuard);
        PlatformJmxTracingSettingsClient client =
                new PlatformJmxTracingSettingsClient(server, coherenceGuard);

        // when
        // then
        assertThatThrownBy(client::initialize)
                .isInstanceOf(TracingConfigurationGuardException.class)
                .hasMessage("JMX_CLIENT_METADATA_GATE_FAILED: Platform tracing MBean metadata is incompatible");
    }

    private static String expectedMessageCode(String outcome) {
        return switch (outcome) {
            case "APPLIED", "UNCHANGED" -> "NONE";
            case "REJECTED" -> "INVALID_VALUE";
            case "STALE" -> "ACCEPTED_SEQUENCE_MISMATCH";
            case "FAILED" -> "INTERNAL_FAILURE";
            default -> throw new IllegalArgumentException("Unsupported outcome: " + outcome);
        };
    }

    private static MBeanServer initializedServer() throws Exception {
        MBeanServer server = mock(MBeanServer.class);
        when(server.getMBeanInfo(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME))
                .thenReturn(ReadinessJmxTestFixtures.metadata());
        when(server.getAttribute(
                JmxWireContract.EXTENSION_READINESS_OBJECT_NAME,
                JmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT))
                .thenReturn(ReadinessJmxTestFixtures.readySnapshot());
        when(server.getMBeanInfo(JmxWireContract.SETTINGS_STATUS_OBJECT_NAME))
                .thenReturn(SettingsJmxTestFixtures.statusInfo());
        when(server.getMBeanInfo(JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME))
                .thenReturn(SettingsJmxTestFixtures.mutationInfo());
        return server;
    }

    private static PlatformJmxTracingSettingsClient initializedClient(MBeanServer server) {
        AgentReadinessCoherenceGuard coherenceGuard = new AgentReadinessCoherenceGuard(server);
        AgentReadinessCoherenceTestSupport.pinCurrent(coherenceGuard);
        PlatformJmxTracingSettingsClient client =
                new PlatformJmxTracingSettingsClient(server, coherenceGuard);
        client.initialize();
        return client;
    }

    private static CompleteTracingSettings settings() {
        return CompleteTracingSettings.builder()
                .samplingEnabled(true)
                .samplingRatio(0.5d)
                .samplingRouteRatios(Map.of())
                .validationLevel("WARNING")
                .exporterEnabled(true)
                .traceControlHeadersEnabled(true)
                .diagnosticsLogLevel("INFO")
                .build();
    }
}
