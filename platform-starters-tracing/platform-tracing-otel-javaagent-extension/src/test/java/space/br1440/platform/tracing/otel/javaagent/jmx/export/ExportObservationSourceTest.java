package space.br1440.platform.tracing.otel.javaagent.jmx.export;

import static org.assertj.core.api.Assertions.assertThat;

import javax.management.openmbean.CompositeData;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;

class ExportObservationSourceTest {

    private static final AgentInstanceId AGENT_INSTANCE_ID = AgentInstanceId.random();

    @Test
    void bothOwnersAbsentEncodePresenceFalseWithoutSentinelNegatives() {
        // given
        ExportObservationSource source = new ExportObservationSource(
                new OwnerRegistrationSlot<>(), new OwnerRegistrationSlot<>(), AGENT_INSTANCE_ID);

        // when
        CompositeData observation = source.getExportObservation();
        CompositeData processor = (CompositeData) observation.get(
                JmxWireContract.FIELD_EXPORT_OBSERVATION_PROCESSOR);
        CompositeData exporter = (CompositeData) observation.get(
                JmxWireContract.FIELD_EXPORT_OBSERVATION_EXPORTER);

        // then
        assertThat((String) observation.get(JmxWireContract.FIELD_EXPORT_OBSERVATION_AGENT_INSTANCE_ID))
                .isEqualTo(AGENT_INSTANCE_ID.toString());
        assertThat((Boolean) processor.get(
                JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_OWNER_PRESENT)).isFalse();
        assertThat((Long) processor.get(
                JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_OWNER_GENERATION)).isEqualTo(0L);
        assertThat((Integer) processor.get(
                JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_QUEUE_CAPACITY))
                .isEqualTo(0);
        assertThat((String) processor.get(
                JmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_LIFECYCLE_STATE)).isEmpty();
        assertThat((Boolean) exporter.get(
                JmxWireContract.FIELD_SAFE_EXPORTER_VALUES_OWNER_PRESENT)).isFalse();
        assertThat((Long) exporter.get(
                JmxWireContract.FIELD_SAFE_EXPORTER_VALUES_OWNER_GENERATION)).isEqualTo(0L);
    }

    @Test
    void getterReturnsPublishedSnapshotUntilExplicitPublication() {
        // given
        ExportObservationSource source = new ExportObservationSource(
                new OwnerRegistrationSlot<>(),
                new OwnerRegistrationSlot<>(),
                AGENT_INSTANCE_ID);

        // when
        CompositeData first = source.getExportObservation();
        CompositeData repeated = source.getExportObservation();
        source.publishObservationSnapshot();
        CompositeData refreshed = source.getExportObservation();

        // then
        assertThat(repeated).isSameAs(first);
        assertThat(refreshed).isNotSameAs(first);
    }
}
