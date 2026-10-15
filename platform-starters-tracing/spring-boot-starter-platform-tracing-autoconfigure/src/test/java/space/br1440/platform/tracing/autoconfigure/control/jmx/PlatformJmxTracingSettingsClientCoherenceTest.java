package space.br1440.platform.tracing.autoconfigure.control.jmx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanServer;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.TabularDataSupport;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceException;
import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceTestSupport;
import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;
import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsRevision;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsWriter;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxMBeanInfoShapeViolationException;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxWireGraphValidator;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.autoconfigure.support.ReadinessJmxTestFixtures;
import space.br1440.platform.tracing.autoconfigure.support.SettingsJmxTestFixtures;

final class PlatformJmxTracingSettingsClientCoherenceTest {

    @Test
    void statusSnapshotUsesOnlySurroundingReadinessBracket() throws Exception {
        // given
        MBeanServer server = mock(MBeanServer.class);
        stubStableReadiness(server);
        when(server.getAttribute(
                JmxWireContract.SETTINGS_STATUS_OBJECT_NAME,
                JmxWireContract.ATTRIBUTE_SETTINGS_STATUS_ACCEPTED_POLICY_SNAPSHOT))
                .thenReturn(acceptedPolicySnapshot());
        AgentReadinessCoherenceGuard coherenceGuard = new AgentReadinessCoherenceGuard(server);
        AgentReadinessCoherenceTestSupport.pinCurrent(coherenceGuard);
        JmxAcceptedSettingsReader reader = new JmxAcceptedSettingsReader(
                server,
                coherenceGuard,
                new JmxWireGraphValidator()
        );
        clearInvocations(server);

        // when
        Optional<AcceptedSettings> accepted = coherenceGuard.readCoherently(reader::currentForStatusSnapshot);

        // then
        assertThat(accepted).isPresent();
        verify(server, times(2)).getAttribute(
                JmxWireContract.EXTENSION_READINESS_OBJECT_NAME,
                JmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT);
        verify(server, times(1)).getAttribute(
                JmxWireContract.SETTINGS_STATUS_OBJECT_NAME,
                JmxWireContract.ATTRIBUTE_SETTINGS_STATUS_ACCEPTED_POLICY_SNAPSHOT);
    }

    private static final String NEXT_AGENT_INSTANCE_ID = "075f4743-6500-4025-947d-af2ca49bdb76";

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
                        ReadinessJmxTestFixtures.readySnapshot(NEXT_AGENT_INSTANCE_ID, 2L, "next-batch"));

        when(server.getMBeanInfo(JmxWireContract.SETTINGS_STATUS_OBJECT_NAME))
                .thenReturn(SettingsJmxTestFixtures.statusInfo());

        when(server.getMBeanInfo(JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME))
                .thenReturn(SettingsJmxTestFixtures.mutationInfo());

        when(server.getAttribute(
                JmxWireContract.SETTINGS_STATUS_OBJECT_NAME,
                JmxWireContract.ATTRIBUTE_SETTINGS_STATUS_ACCEPTED_POLICY_SNAPSHOT))
                .thenReturn(acceptedPolicySnapshot());

        AgentReadinessCoherenceGuard coherenceGuard = new AgentReadinessCoherenceGuard(server);
        AgentReadinessCoherenceTestSupport.pinCurrent(coherenceGuard);
        PlatformJmxTracingSettingsClient client =
                new PlatformJmxTracingSettingsClient(server, coherenceGuard);
        client.initialize();

        // when
        // then
        assertThatThrownBy(client::current)
                .isInstanceOf(AgentReadinessCoherenceException.class)
                .satisfies(failure -> assertThat(
                        ((AgentReadinessCoherenceException) failure).failureCode())
                        .isEqualTo(AgentReadinessCoherenceGuard.MIXED_GENERATION));

        verify(server, times(1)).getAttribute(
                JmxWireContract.SETTINGS_STATUS_OBJECT_NAME,
                JmxWireContract.ATTRIBUTE_SETTINGS_STATUS_ACCEPTED_POLICY_SNAPSHOT);

        assertThatThrownBy(client::current)
                .isInstanceOf(AgentReadinessCoherenceException.class)
                .satisfies(failure -> assertThat(
                        ((AgentReadinessCoherenceException) failure).failureCode())
                        .isEqualTo(AgentReadinessCoherenceGuard.MIXED_GENERATION));

        assertThatThrownBy(() -> client.applySnapshot(
                new SettingsRevision(ReadinessJmxTestFixtures.AGENT_INSTANCE_ID, 0L),
                settings(),
                SettingsWriter.CONFIG_SERVER))
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
                        ReadinessJmxTestFixtures.readySnapshot(NEXT_AGENT_INSTANCE_ID, 2L, "next-batch"));

        when(server.getMBeanInfo(JmxWireContract.SETTINGS_STATUS_OBJECT_NAME))
                .thenReturn(SettingsJmxTestFixtures.statusInfo());

        when(server.getMBeanInfo(JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME))
                .thenReturn(SettingsJmxTestFixtures.mutationInfo());

        when(server.invoke(
                eq(JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME),
                eq(JmxWireContract.OPERATION_SETTINGS_MUTATION_APPLY_SNAPSHOT),
                any(Object[].class),
                any(String[].class)))
                .thenAnswer(invocation -> {
                    Object[] parameters = invocation.getArgument(2);
                    return appliedResponse((CompositeData) parameters[0]);
                });

        AgentReadinessCoherenceGuard coherenceGuard = new AgentReadinessCoherenceGuard(server);
        AgentReadinessCoherenceTestSupport.pinCurrent(coherenceGuard);
        PlatformJmxTracingSettingsClient client =
                new PlatformJmxTracingSettingsClient(server, coherenceGuard);
        client.initialize();

        // when
        // then
        assertThatThrownBy(() -> client.applySnapshot(
                new SettingsRevision(ReadinessJmxTestFixtures.AGENT_INSTANCE_ID, 0L),
                settings(),
                SettingsWriter.CONFIG_SERVER))
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
                new SettingsRevision(ReadinessJmxTestFixtures.AGENT_INSTANCE_ID, 0L),
                settings(),
                SettingsWriter.CONFIG_SERVER))
                .isInstanceOf(TracingConfigurationGuardException.class)
                .hasMessageContaining("JMX_CLIENT_TERMINALLY_INVALIDATED")
                .hasMessageContaining(AgentReadinessCoherenceGuard.MIXED_GENERATION);
    }

    @Test
    void acceptedSnapshotDriftingDuringDecodeIsDiscardedAndTrustRemainsInvalid() throws Exception {
        // given
        AtomicBoolean drifted = new AtomicBoolean();
        MBeanServer server = serverWithReadinessControlledBy(drifted);
        when(server.getAttribute(
                JmxWireContract.SETTINGS_STATUS_OBJECT_NAME,
                JmxWireContract.ATTRIBUTE_SETTINGS_STATUS_ACCEPTED_POLICY_SNAPSHOT))
                .thenReturn(acceptedPolicySnapshot());

        AgentReadinessCoherenceGuard coherenceGuard = new AgentReadinessCoherenceGuard(server);
        AgentReadinessCoherenceTestSupport.pinCurrent(coherenceGuard);
        JmxWireGraphValidator wireValidator = mock(JmxWireGraphValidator.class);
        doAnswer(invocation -> {
            drifted.set(true);
            return null;
        }).when(wireValidator).validate(any());
        JmxAcceptedSettingsReader reader = new JmxAcceptedSettingsReader(
                server,
                coherenceGuard,
                wireValidator
        );

        // when
        // then
        assertThatThrownBy(reader::current)
                .isInstanceOf(AgentReadinessCoherenceException.class)
                .satisfies(failure -> assertThat(
                        ((AgentReadinessCoherenceException) failure).failureCode())
                        .isEqualTo(AgentReadinessCoherenceGuard.MIXED_GENERATION));
        assertThat(coherenceGuard.terminalFailureCode())
                .isEqualTo(AgentReadinessCoherenceGuard.MIXED_GENERATION);
        verify(server, times(1)).getAttribute(
                JmxWireContract.SETTINGS_STATUS_OBJECT_NAME,
                JmxWireContract.ATTRIBUTE_SETTINGS_STATUS_ACCEPTED_POLICY_SNAPSHOT);
    }

    @Test
    void mutationResponseDriftingDuringDecodeIsDiscardedAndTrustRemainsInvalid() throws Exception {
        // given
        AtomicBoolean drifted = new AtomicBoolean();
        MBeanServer server = serverWithReadinessControlledBy(drifted);
        when(server.invoke(
                eq(JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME),
                eq(JmxWireContract.OPERATION_SETTINGS_MUTATION_APPLY_SNAPSHOT),
                any(Object[].class),
                any(String[].class)))
                .thenAnswer(invocation -> {
                    Object[] parameters = invocation.getArgument(2);
                    return appliedResponse((CompositeData) parameters[0]);
                });

        AgentReadinessCoherenceGuard coherenceGuard = new AgentReadinessCoherenceGuard(server);
        AgentReadinessCoherenceTestSupport.pinCurrent(coherenceGuard);
        PlatformJmxTracingSettingsClient client = new PlatformJmxTracingSettingsClient(server, coherenceGuard);
        JmxWireGraphValidator wireValidator = mock(JmxWireGraphValidator.class);
        doAnswer(invocation -> {
            drifted.set(true);
            return null;
        }).when(wireValidator).validate(any());
        JmxSettingsMutationTransport transport = new JmxSettingsMutationTransport(
                server,
                coherenceGuard,
                wireValidator,
                client.failureSink()
        );

        // when
        // then
        assertThatThrownBy(() -> transport.applySnapshot(
                UUID.randomUUID().toString(),
                new SettingsRevision(ReadinessJmxTestFixtures.AGENT_INSTANCE_ID, 0L),
                settings(),
                SettingsWriter.CONFIG_SERVER))
                .isInstanceOf(AgentReadinessCoherenceException.class)
                .satisfies(failure -> assertThat(
                        ((AgentReadinessCoherenceException) failure).failureCode())
                        .isEqualTo(AgentReadinessCoherenceGuard.MIXED_GENERATION)
                );
        assertThat(coherenceGuard.terminalFailureCode())
                .isEqualTo(AgentReadinessCoherenceGuard.MIXED_GENERATION);
        verify(server, times(1)).invoke(
                eq(JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME),
                eq(JmxWireContract.OPERATION_SETTINGS_MUTATION_APPLY_SNAPSHOT),
                any(Object[].class),
                any(String[].class)
        );
    }

    @Test
    void statusEndpointDisappearanceDoesNotInvalidateTrust() throws Exception {
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
        assertThatThrownBy(client::current)
                .isInstanceOf(TracingConfigurationGuardException.class)
                .hasMessageStartingWith("JMX_CLIENT_STATUS_ENDPOINT_DISAPPEARED:")
                .hasCause(disappeared);
        assertThatThrownBy(client::current)
                .isInstanceOf(TracingConfigurationGuardException.class)
                .hasMessageStartingWith("JMX_CLIENT_STATUS_ENDPOINT_DISAPPEARED:")
                .hasCause(disappeared);
        assertThat(coherenceGuard.terminalFailureCode()).isNull();
        verify(server, times(2)).getAttribute(
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

    private static MBeanServer serverWithReadinessControlledBy(AtomicBoolean drifted) throws Exception {
        MBeanServer server = mock(MBeanServer.class);
        when(server.getMBeanInfo(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME))
                .thenReturn(ReadinessJmxTestFixtures.metadata());
        when(server.getAttribute(
                JmxWireContract.EXTENSION_READINESS_OBJECT_NAME,
                JmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT))
                .thenAnswer(invocation -> drifted.get()
                        ? ReadinessJmxTestFixtures.readySnapshot(NEXT_AGENT_INSTANCE_ID, 2L, "next-batch")
                        : ReadinessJmxTestFixtures.readySnapshot());
        return server;
    }

    private static CompositeData acceptedPolicySnapshot() throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_ACCEPTED_SEQUENCE, 0L);
        fields.put(JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_AGENT_INSTANCE_ID,
                ReadinessJmxTestFixtures.AGENT_INSTANCE_ID);
        fields.put(JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SOURCE_AUTHORITY, "BOOTSTRAP");
        fields.put(JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_ENABLED, true);
        fields.put(JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_RATIO, 0.5d);
        fields.put(JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_ROUTE_RATIOS,
                new TabularDataSupport(JmxOpenTypes.ROUTE_RATIOS));
        fields.put(JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_VALIDATION_LEVEL, "WARNING");
        fields.put(JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_EXPORTER_ENABLED, true);
        fields.put(JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_TRACE_CONTROL_HEADERS_ENABLED, true);
        fields.put(JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_DIAGNOSTICS_LOG_LEVEL, "INFO");
        return new CompositeDataSupport(JmxOpenTypes.ACCEPTED_POLICY_SNAPSHOT, fields);
    }

    private static CompositeData appliedResponse(CompositeData request) throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_ACCEPTED_SEQUENCE, 1L);
        fields.put(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_ATTEMPT_ID,
                request.get(JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_ATTEMPT_ID));
        fields.put(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_MESSAGE_CODE, "NONE");
        fields.put(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_OUTCOME, "APPLIED");
        fields.put(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_AGENT_INSTANCE_ID,
                ReadinessJmxTestFixtures.AGENT_INSTANCE_ID);
        fields.put(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_VIOLATION_CODES, new String[0]);
        return new CompositeDataSupport(JmxOpenTypes.APPLY_SNAPSHOT_RESPONSE, fields);
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
                .validationLevel("WARNING")
                .exporterEnabled(true)
                .traceControlHeadersEnabled(true)
                .diagnosticsLogLevel("INFO")
                .build();
    }
}
