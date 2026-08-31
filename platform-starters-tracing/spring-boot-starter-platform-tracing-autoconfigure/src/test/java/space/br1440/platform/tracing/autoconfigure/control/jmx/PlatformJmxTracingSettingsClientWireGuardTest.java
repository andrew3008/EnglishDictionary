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
import space.br1440.platform.tracing.autoconfigure.control.authority.TracingConfigurationGuardException;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.ExpectedSettingsRevision;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsContractViolationException;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsDeliverySource;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.autoconfigure.support.FakeAgentPlatformJmxPlane;
import space.br1440.platform.tracing.autoconfigure.support.ReadinessJmxTestFixtures;
import space.br1440.platform.tracing.autoconfigure.support.SettingsJmxTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsSourceAuthority;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsUpdateTrigger;

final class PlatformJmxTracingSettingsClientWireGuardTest {

    @Test
    void statusRejectsNonNeutralValueBeforeSnapshotDecoder() throws Exception {
        // given
        MBeanServer server = initializedServer();
        when(server.getAttribute(
                JmxWireContract.SETTINGS_STATUS_OBJECT_NAME,
                JmxWireContract.ATTRIBUTE_SETTINGS_STATUS_ACCEPTED_POLICY_SNAPSHOT))
                .thenReturn(new ForeignPayload("extension-owned"));
        PlatformJmxTracingSettingsClient client = initializedClient(server);

        // when
        // then
        assertThatThrownBy(client::currentComplete)
                .isInstanceOf(TracingSettingsContractViolationException.class)
                .hasMessageStartingWith("JMX_CLIENT_STATUS_JMX_WIRE_TYPE_VIOLATION:");
        assertThatThrownBy(client::currentComplete)
                .isInstanceOf(TracingConfigurationGuardException.class)
                .hasMessageContaining("JMX_CLIENT_TERMINALLY_INVALIDATED")
                .hasMessageContaining("JMX_CLIENT_STATUS_JMX_WIRE_TYPE_VIOLATION");
        assertThatThrownBy(client::initialize)
                .isInstanceOf(TracingConfigurationGuardException.class)
                .hasMessageContaining("JMX_CLIENT_TERMINALLY_INVALIDATED")
                .hasMessageContaining("JMX_CLIENT_STATUS_JMX_WIRE_TYPE_VIOLATION");
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
                new ExpectedSettingsRevision("runtime-1", 0L),
                settings(),
                TracingSettingsDeliverySource.CONFIG_SERVER))
                .isInstanceOf(TracingSettingsContractViolationException.class)
                .hasMessageStartingWith("JMX_CLIENT_MUTATION_JMX_WIRE_TYPE_VIOLATION:");
        assertThatThrownBy(() -> client.applySnapshot(
                new ExpectedSettingsRevision("runtime-1", 0L),
                settings(),
                TracingSettingsDeliverySource.CONFIG_SERVER))
                .isInstanceOf(TracingConfigurationGuardException.class)
                .hasMessageContaining("JMX_CLIENT_TERMINALLY_INVALIDATED")
                .hasMessageContaining("JMX_CLIENT_MUTATION_JMX_WIRE_TYPE_VIOLATION");
    }

    @Test
    void mutationRejectsForeignCompositeTypeResponseAsContractViolationNotDeliveryFailure() throws Exception {
        // given
        String runtimeInstanceId = UUID.randomUUID().toString();
        try (FakeAgentPlatformJmxPlane plane =
                     FakeAgentPlatformJmxPlane.malformedApplySnapshotResponse(runtimeInstanceId)) {
            PlatformJmxTracingSettingsClient client = plane.client();
            ExpectedSettingsRevision expectedBase = new ExpectedSettingsRevision(runtimeInstanceId, 0L);

            // when
            // then
            assertThatThrownBy(() -> client.applySnapshot(expectedBase, settings(), TracingSettingsDeliverySource.CONFIG_SERVER))
                    .as("a foreign CompositeType response MUST be a contract violation, not a plain FAILED result")
                    .isInstanceOf(TracingSettingsContractViolationException.class)
                    .hasMessageStartingWith("JMX_CLIENT_MUTATION_RESPONSE_DECODE_FAILED:");

            assertThatThrownBy(() -> client.applySnapshot(expectedBase, settings(), TracingSettingsDeliverySource.CONFIG_SERVER))
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
                new ExpectedSettingsRevision("runtime-1", 0L),
                settings(),
                TracingSettingsDeliverySource.CONFIG_SERVER))
                .isInstanceOf(TracingSettingsContractViolationException.class)
                .hasMessageStartingWith("JMX_CLIENT_MUTATION_RESPONSE_TYPE_INVALID:");
                
        assertThatThrownBy(() -> client.applySnapshot(
                new ExpectedSettingsRevision("runtime-1", 0L),
                settings(),
                TracingSettingsDeliverySource.CONFIG_SERVER))
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
                new ExpectedSettingsRevision("runtime-1", 0L),
                null,
                TracingSettingsDeliverySource.CONFIG_SERVER))
                .isInstanceOf(TracingSettingsContractViolationException.class)
                .hasMessageStartingWith("JMX_CLIENT_MUTATION_ENCODING_FAILED:")
                .hasCauseInstanceOf(NullPointerException.class);

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
        String runtimeInstanceId = UUID.randomUUID().toString();
        try (FakeAgentPlatformJmxPlane plane = FakeAgentPlatformJmxPlane.compliant(runtimeInstanceId, 0L)) {
            plane.setNextApplyOutcome(outcome);
            PlatformJmxTracingSettingsClient client = plane.client();
            ExpectedSettingsRevision expectedBase = new ExpectedSettingsRevision(runtimeInstanceId, 0L);

            // when
            TracingSettingsDeliveryResult result =
                    client.applySnapshot(expectedBase, settings(), TracingSettingsDeliverySource.CONFIG_SERVER);

            // then
            assertThat(result).isEqualTo(TracingSettingsDeliveryResult.valueOf(outcome));
        }
    }

    @ParameterizedTest
    @EnumSource(TracingSettingsDeliverySource.class)
    void mutationEncodesSourceAuthorityAndApplyTriggerAcceptedByRealAgentDecoder(
            TracingSettingsDeliverySource source) throws Exception {
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

        // when
        // then
        assertThatThrownBy(() -> client.applySnapshot(
                new ExpectedSettingsRevision("runtime-1", 0L), settings(), source))
                .isInstanceOf(TracingSettingsContractViolationException.class);

        CompositeData request = (CompositeData) requestCaptor.getValue()[0];
        String sourceAuthority = (String) request.get(
                JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_SOURCE_AUTHORITY);
        String applyTrigger = (String) request.get(
                JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_APPLY_TRIGGER);

        SettingsSourceAuthority expectedAuthority = switch (source) {
            case CONFIG_SERVER -> SettingsSourceAuthority.CONFIG_SERVER;
            case ACTUATOR -> SettingsSourceAuthority.DEV_ACTUATOR;
        };

        SettingsUpdateTrigger expectedTrigger = switch (source) {
            case CONFIG_SERVER -> SettingsUpdateTrigger.REFRESH;
            case ACTUATOR -> SettingsUpdateTrigger.DEVELOPER_COMMAND;
        };

        assertThat(SettingsSourceAuthority.valueOf(sourceAuthority)).isEqualTo(expectedAuthority);
        assertThat(SettingsUpdateTrigger.valueOf(applyTrigger)).isEqualTo(expectedTrigger);
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
                .validationEnabled(true)
                .validationStrict(false)
                .exporterEnabled(true)
                .traceControlHeadersEnabled(true)
                .diagnosticsLogLevel("INFO")
                .build();
    }

    private record ForeignPayload(String value) {
    }
}
