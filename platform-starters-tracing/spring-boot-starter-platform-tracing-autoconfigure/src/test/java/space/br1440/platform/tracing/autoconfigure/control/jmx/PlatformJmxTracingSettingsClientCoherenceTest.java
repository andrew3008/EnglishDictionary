package space.br1440.platform.tracing.autoconfigure.control.jmx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanServer;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceException;
import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceTestSupport;
import space.br1440.platform.tracing.autoconfigure.control.authority.TracingConfigurationGuardException;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.ExpectedSettingsRevision;
import space.br1440.platform.tracing.autoconfigure.control.settings.delivery.TracingSettingsDeliverySource;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxMBeanInfoShapeViolationException;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.autoconfigure.support.ReadinessJmxTestFixtures;
import space.br1440.platform.tracing.autoconfigure.support.SettingsJmxTestFixtures;

final class PlatformJmxTracingSettingsClientCoherenceTest {

    @Test
    void acceptedSnapshotCrossingReadinessGenerationIsDiscardedAndTrustRemainsInvalid() throws Exception {
        // given
        MBeanServer server = mock(MBeanServer.class);
        when(server.getMBeanInfo(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME))
                .thenReturn(ReadinessJmxTestFixtures.metadata());

        when(server.getAttribute(
                JmxWireContract.EXTENSION_READINESS_OBJECT_NAME,
                JmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT))
                .thenReturn(
                        ReadinessJmxTestFixtures.readySnapshot(),
                        ReadinessJmxTestFixtures.readySnapshot(),
                        ReadinessJmxTestFixtures.readySnapshot(),
                        ReadinessJmxTestFixtures.readySnapshot(),
                        ReadinessJmxTestFixtures.readySnapshot("next-runtime-epoch", 2L, "next-batch"));

        when(server.getMBeanInfo(JmxWireContract.SETTINGS_STATUS_OBJECT_NAME))
                .thenReturn(SettingsJmxTestFixtures.statusInfo());

        when(server.getMBeanInfo(JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME))
                .thenReturn(SettingsJmxTestFixtures.mutationInfo());

        when(server.getAttribute(
                JmxWireContract.SETTINGS_STATUS_OBJECT_NAME,
                JmxWireContract.ATTRIBUTE_SETTINGS_STATUS_ACCEPTED_POLICY_SNAPSHOT))
                .thenReturn("must-not-be-decoded");

        AgentReadinessCoherenceGuard coherenceGuard = new AgentReadinessCoherenceGuard(server);
        AgentReadinessCoherenceTestSupport.pinCurrent(coherenceGuard);
        PlatformJmxTracingSettingsClient client =
                new PlatformJmxTracingSettingsClient(server, coherenceGuard);
        client.initialize();

        // when
        // then
        assertThatThrownBy(client::currentComplete)
                .isInstanceOf(AgentReadinessCoherenceException.class)
                .satisfies(failure -> assertThat(
                        ((AgentReadinessCoherenceException) failure).failureCode())
                        .isEqualTo(AgentReadinessCoherenceGuard.MIXED_GENERATION));

        verify(server, times(1)).getAttribute(
                JmxWireContract.SETTINGS_STATUS_OBJECT_NAME,
                JmxWireContract.ATTRIBUTE_SETTINGS_STATUS_ACCEPTED_POLICY_SNAPSHOT);

        assertThatThrownBy(client::currentComplete)
                .isInstanceOf(TracingConfigurationGuardException.class)
                .hasMessageContaining("JMX_CLIENT_TERMINALLY_INVALIDATED")
                .hasMessageContaining(AgentReadinessCoherenceGuard.MIXED_GENERATION);

        assertThatThrownBy(client::initialize)
                .isInstanceOf(TracingConfigurationGuardException.class)
                .hasMessageContaining("JMX_CLIENT_TERMINALLY_INVALIDATED")
                .hasMessageContaining(AgentReadinessCoherenceGuard.MIXED_GENERATION);
    }

    @Test
    void mutationCrossingReadinessGenerationIsDiscardedAndTrustRemainsInvalid() throws Exception {
        // given
        MBeanServer server = mock(MBeanServer.class);
        when(server.getMBeanInfo(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME))
                .thenReturn(ReadinessJmxTestFixtures.metadata());

        when(server.getAttribute(
                JmxWireContract.EXTENSION_READINESS_OBJECT_NAME,
                JmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT))
                .thenReturn(
                        ReadinessJmxTestFixtures.readySnapshot(),
                        ReadinessJmxTestFixtures.readySnapshot(),
                        ReadinessJmxTestFixtures.readySnapshot(),
                        ReadinessJmxTestFixtures.readySnapshot(),
                        ReadinessJmxTestFixtures.readySnapshot("next-runtime-epoch", 2L, "next-batch"));

        when(server.getMBeanInfo(JmxWireContract.SETTINGS_STATUS_OBJECT_NAME))
                .thenReturn(SettingsJmxTestFixtures.statusInfo());

        when(server.getMBeanInfo(JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME))
                .thenReturn(SettingsJmxTestFixtures.mutationInfo());

        when(server.invoke(
                eq(JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME),
                eq(JmxWireContract.OPERATION_SETTINGS_MUTATION_APPLY_SNAPSHOT),
                any(Object[].class),
                any(String[].class)))
                .thenReturn("must-not-be-decoded");

        AgentReadinessCoherenceGuard coherenceGuard = new AgentReadinessCoherenceGuard(server);
        AgentReadinessCoherenceTestSupport.pinCurrent(coherenceGuard);
        PlatformJmxTracingSettingsClient client =
                new PlatformJmxTracingSettingsClient(server, coherenceGuard);
        client.initialize();

        // when
        // then
        assertThatThrownBy(() -> client.applySnapshot(
                new ExpectedSettingsRevision("runtime-1", 0L),
                settings(),
                TracingSettingsDeliverySource.CONFIG_SERVER))
                .isInstanceOf(AgentReadinessCoherenceException.class)
                .satisfies(failure -> assertThat(
                        ((AgentReadinessCoherenceException) failure).failureCode())
                        .isEqualTo(AgentReadinessCoherenceGuard.MIXED_GENERATION));

        verify(server, times(1)).invoke(
                eq(JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME),
                eq(JmxWireContract.OPERATION_SETTINGS_MUTATION_APPLY_SNAPSHOT),
                any(Object[].class),
                any(String[].class));

        assertThatThrownBy(() -> client.applySnapshot(
                new ExpectedSettingsRevision("runtime-1", 0L),
                settings(),
                TracingSettingsDeliverySource.CONFIG_SERVER))
                .isInstanceOf(TracingConfigurationGuardException.class)
                .hasMessageContaining("JMX_CLIENT_TERMINALLY_INVALIDATED")
                .hasMessageContaining(AgentReadinessCoherenceGuard.MIXED_GENERATION);
    }

    @Test
    void statusEndpointDisappearanceAfterReadyTerminallyInvalidatesTrust() throws Exception {
        // given
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
        InstanceNotFoundException disappeared = new InstanceNotFoundException("removed by test");
        when(server.getAttribute(
                JmxWireContract.SETTINGS_STATUS_OBJECT_NAME,
                JmxWireContract.ATTRIBUTE_SETTINGS_STATUS_ACCEPTED_POLICY_SNAPSHOT))
                .thenThrow(disappeared);
        AgentReadinessCoherenceGuard coherenceGuard = new AgentReadinessCoherenceGuard(server);
        AgentReadinessCoherenceTestSupport.pinCurrent(coherenceGuard);
        PlatformJmxTracingSettingsClient client =
                new PlatformJmxTracingSettingsClient(server, coherenceGuard);
        client.initialize();

        // when
        // then
        assertThatThrownBy(client::currentComplete)
                .isInstanceOf(TracingConfigurationGuardException.class)
                .hasMessageStartingWith("JMX_CLIENT_STATUS_ENDPOINT_DISAPPEARED:")
                .hasCause(disappeared);
        assertThatThrownBy(client::currentComplete)
                .isInstanceOf(TracingConfigurationGuardException.class)
                .hasMessageContaining("JMX_CLIENT_TERMINALLY_INVALIDATED")
                .hasMessageContaining("JMX_CLIENT_STATUS_ENDPOINT_DISAPPEARED");
        verify(server, times(1)).getAttribute(
                JmxWireContract.SETTINGS_STATUS_OBJECT_NAME,
                JmxWireContract.ATTRIBUTE_SETTINGS_STATUS_ACCEPTED_POLICY_SNAPSHOT);
    }

    @Test
    void initializeDeniedMetadataAccessTerminallyInvalidatesTrust() throws Exception {
        // given
        MBeanServer server = mock(MBeanServer.class);
        stubStableReadiness(server);
        SecurityException denied = new SecurityException("denied by test policy");
        when(server.getMBeanInfo(JmxWireContract.SETTINGS_STATUS_OBJECT_NAME)).thenThrow(denied);
        PlatformJmxTracingSettingsClient client = client(server);

        // when
        // then
        assertThatThrownBy(client::initialize)
                .isInstanceOf(TracingConfigurationGuardException.class)
                .hasMessage(
                        "JMX_CLIENT_METADATA_TRANSPORT_SECURITY_FAILED: "
                                + "Platform tracing MBean metadata access was denied")
                .hasCause(denied);
        assertThatThrownBy(client::initialize)
                .isInstanceOf(TracingConfigurationGuardException.class)
                .hasMessageContaining("JMX_CLIENT_TERMINALLY_INVALIDATED")
                .hasMessageContaining("JMX_CLIENT_METADATA_TRANSPORT_SECURITY_FAILED");
    }

    @Test
    void initializeWritableStatusSnapshotCannotRestoreTrust() throws Exception {
        // given
        MBeanServer server = mock(MBeanServer.class);
        stubStableReadiness(server);
        when(server.getMBeanInfo(JmxWireContract.SETTINGS_STATUS_OBJECT_NAME))
                .thenReturn(SettingsJmxTestFixtures.statusInfo(true));
        PlatformJmxTracingSettingsClient client = client(server);
        assertThatThrownBy(client::initialize)
                .isInstanceOf(TracingConfigurationGuardException.class)
                .hasMessage("JMX_CLIENT_METADATA_GATE_FAILED: Platform tracing MBean metadata is incompatible")
                .satisfies(failure -> assertThat(failure.getCause())
                        .isInstanceOf(JmxMBeanInfoShapeViolationException.class));
        when(server.getMBeanInfo(JmxWireContract.SETTINGS_STATUS_OBJECT_NAME))
                .thenReturn(SettingsJmxTestFixtures.statusInfo());
        when(server.getMBeanInfo(JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME))
                .thenReturn(SettingsJmxTestFixtures.mutationInfo());

        // when
        // then
        assertThatThrownBy(client::initialize)
                .isInstanceOf(TracingConfigurationGuardException.class)
                .hasMessageContaining("JMX_CLIENT_TERMINALLY_INVALIDATED")
                .hasMessageContaining("JMX_CLIENT_METADATA_GATE_FAILED");
    }

    private static PlatformJmxTracingSettingsClient client(MBeanServer server) {
        AgentReadinessCoherenceGuard coherenceGuard = new AgentReadinessCoherenceGuard(server);
        AgentReadinessCoherenceTestSupport.pinCurrent(coherenceGuard);
        return new PlatformJmxTracingSettingsClient(server, coherenceGuard);
    }

    private static void stubStableReadiness(MBeanServer server) throws Exception {
        when(server.getMBeanInfo(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME))
                .thenReturn(ReadinessJmxTestFixtures.metadata());
                
        when(server.getAttribute(
                JmxWireContract.EXTENSION_READINESS_OBJECT_NAME,
                JmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT))
                .thenReturn(ReadinessJmxTestFixtures.readySnapshot());
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
}
