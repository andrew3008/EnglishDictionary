package space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.openmbean.CompositeData;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.TracingSettingsUpdater;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

final class PlatformSettingsMutationBoundaryTest {

    private TracingSettingsStore store;
    private MBeanServer server;

    @BeforeEach
    void setUp() throws Exception {
        store = JmxSettingsMutationFixtures.store();
        TracingSettingsUpdater updater = new TracingSettingsUpdater(store, level -> { });
        server = MBeanServerFactory.createMBeanServer();
        server.registerMBean(
                new PlatformSettingsMutationMBean(updater),
                JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME);
    }

    @AfterEach
    void tearDown() {
        MBeanServerFactory.releaseMBeanServer(server);
    }

    @Test
    void appliesCompleteSnapshotAndRejectsStaleOrMalformedInput() throws Exception {
        // given
        String agentInstanceId = store.current().agentInstanceId().toString();
        TracingSettings candidate = JmxSettingsMutationFixtures.settings(0.25d);

        // when
        CompositeData applied = invoke(JmxSettingsMutationFixtures.request(
                agentInstanceId,
                0L,
                candidate,
                UUID.randomUUID().toString()));
        CompositeData stale = invoke(JmxSettingsMutationFixtures.request(
                agentInstanceId,
                0L,
                JmxSettingsMutationFixtures.settings(0.5d),
                UUID.randomUUID().toString()));
        CompositeData malformed = invoke(JmxSettingsMutationFixtures.malformedRequest());

        // then
        assertThat(applied.get(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_OUTCOME))
                .isEqualTo("APPLIED");
        assertThat(applied.get(
                JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_ACCEPTED_SEQUENCE))
                .isEqualTo(1L);
        assertThat(store.current().policy()).isEqualTo(candidate);
        assertThat(stale.get(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_OUTCOME))
                .isEqualTo("STALE");
        assertThat(stale.get(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_MESSAGE_CODE))
                .isEqualTo("ACCEPTED_SEQUENCE_MISMATCH");
        assertThat(malformed.get(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_OUTCOME))
                .isEqualTo("REJECTED");
        assertThat(malformed.get(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_MESSAGE_CODE))
                .isEqualTo("TYPE_MISMATCH");
        assertThat(store.current().acceptedSequence()).isEqualTo(1L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BOOTSTRAP", "FOO"})
    void rejectsUnsupportedWriterWithoutChangingAcceptedState(String writer) throws Exception {
        // given
        TracingSettingsRevision acceptedBefore = store.current();
        CompositeData request = JmxSettingsMutationFixtures.request(
                acceptedBefore.agentInstanceId().toString(),
                acceptedBefore.acceptedSequence(),
                JmxSettingsMutationFixtures.settings(0.25d),
                UUID.randomUUID().toString(),
                writer
        );

        // when
        CompositeData response = invoke(request);

        // then
        assertThat(response.get(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_OUTCOME))
                .isEqualTo("REJECTED");
        assertThat(response.get(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_MESSAGE_CODE))
                .isEqualTo("INVALID_VALUE");
        assertThat(store.current()).isSameAs(acceptedBefore);
    }

    @Test
    void rejectsMismatchedAgentInstanceWithoutChangingAcceptedState() throws Exception {
        // given
        TracingSettingsRevision acceptedBefore = store.current();
        CompositeData request = JmxSettingsMutationFixtures.request(
                "00000000-0000-0000-0000-000000000002",
                acceptedBefore.acceptedSequence(),
                JmxSettingsMutationFixtures.settings(0.25d),
                UUID.randomUUID().toString());

        // when
        CompositeData rejected = invoke(request);

        // then
        assertThat(rejected.get(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_OUTCOME))
                .isEqualTo("REJECTED");
        assertThat(rejected.get(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_MESSAGE_CODE))
                .isEqualTo("AGENT_INSTANCE_MISMATCH");
        assertThat(rejected.get(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_AGENT_INSTANCE_ID))
                .isEqualTo(acceptedBefore.agentInstanceId().toString());
        assertThat(store.current()).isSameAs(acceptedBefore);
    }

    @Test
    void acceptsMaximumConfigServerRequestAndSingleLeafActuatorMutation() throws Exception {
        // given
        TracingSettings configServerCandidate = maximumPolicy(0.25d);
        TracingSettings actuatorCandidate = maximumPolicy(0.5d);
        String agentInstanceId = store.current().agentInstanceId().toString();

        // when
        CompositeData configServerResponse = invoke(JmxSettingsMutationFixtures.request(
                agentInstanceId,
                0L,
                configServerCandidate,
                UUID.randomUUID().toString(),
                "CONFIG_SERVER"
        ));
        CompositeData actuatorResponse = invoke(JmxSettingsMutationFixtures.request(
                agentInstanceId,
                1L,
                actuatorCandidate,
                UUID.randomUUID().toString(),
                "ACTUATOR"
        ));

        // then
        assertThat(configServerResponse.get(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_OUTCOME))
                .isEqualTo("APPLIED");
        assertThat(actuatorResponse.get(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_OUTCOME))
                .isEqualTo("APPLIED");
        assertThat(store.current().acceptedSequence()).isEqualTo(2L);
        assertThat(store.current().policy()).isEqualTo(actuatorCandidate);
    }

    @Test
    void rejectsRequestExceedingLogicalByteBudget() throws Exception {
        // given
        TracingSettingsRevision acceptedBefore = store.current();
        CompositeData request = JmxSettingsMutationFixtures.request(
                acceptedBefore.agentInstanceId().toString(),
                acceptedBefore.acceptedSequence(),
                maximumPolicy(0.25d),
                "a".repeat(JmxWireContract.MAX_STRING_UTF8_BYTES),
                "CONFIG_SERVER"
        );

        // when
        CompositeData response = invoke(request);

        // then
        assertThat(response.get(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_OUTCOME))
                .isEqualTo("REJECTED");
        assertThat(response.get(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_MESSAGE_CODE))
                .isEqualTo("LIMIT_EXCEEDED");
        assertThat(store.current()).isSameAs(acceptedBefore);
    }

    private static TracingSettings maximumPolicy(double ratio) {
        Map<String, Double> routes = new LinkedHashMap<>();
        for (int index = 0; index < JmxWireContract.MAX_ROUTE_ENTRIES; index++) {
            routes.put("/" + "%02d".formatted(index) + "x".repeat(252), 0.5d);
        }
        return TracingSettings.of(
                true,
                ratio,
                routes,
                SpanAttributeValidationLevel.DISABLED,
                true,
                true,
                AgentLogLevel.TRACE
        );
    }

    private CompositeData invoke(CompositeData request) throws Exception {
        return (CompositeData) server.invoke(
                JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME,
                JmxWireContract.OPERATION_SETTINGS_MUTATION_APPLY_SNAPSHOT,
                new Object[]{request},
                new String[]{CompositeData.class.getName()});
    }
}
