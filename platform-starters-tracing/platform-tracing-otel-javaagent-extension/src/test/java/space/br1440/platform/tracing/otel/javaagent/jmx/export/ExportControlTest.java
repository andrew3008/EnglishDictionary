package space.br1440.platform.tracing.otel.javaagent.jmx.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import javax.management.openmbean.CompositeData;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.GeneratedJmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.processor.dropoldest.PlatformDropOldestExportSpanProcessor;

/**
 * Contract tests for {@link ExportControl}: два независимо заменяемых владельца
 * кодируют presence в sub-composite; placeholder'ы при {@code ownerPresent=false} не являются
 * доменными значениями (см. ADR-jmx-absent-owner-encoding).
 */
class ExportControlTest {

    private static final UUID AGENT_INSTANCE_ID = UUID.randomUUID();

    @Test
    void bothOwnersAbsentEncodePresenceFalseWithoutSentinelNegatives() {
        ExportControl control = new ExportControl(
                new OwnerRegistrationSlot<>(), new OwnerRegistrationSlot<>(), AGENT_INSTANCE_ID);

        CompositeData observation = control.getExportObservation();
        CompositeData processor = (CompositeData) observation.get(
                GeneratedJmxWireContract.FIELD_EXPORT_OBSERVATION_PROCESSOR);
        CompositeData exporter = (CompositeData) observation.get(
                GeneratedJmxWireContract.FIELD_EXPORT_OBSERVATION_EXPORTER);

        assertThat((Integer) observation.get(GeneratedJmxWireContract.FIELD_EXPORT_OBSERVATION_SCHEMA_VERSION))
                .isEqualTo(GeneratedJmxWireContract.OBSERVATION_SCHEMA_VERSION);
        assertThat((String) observation.get(GeneratedJmxWireContract.FIELD_EXPORT_OBSERVATION_AGENT_INSTANCE_ID))
                .isEqualTo(AGENT_INSTANCE_ID.toString());
        assertThat((Boolean) processor.get(
                GeneratedJmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_OWNER_PRESENT)).isFalse();
        assertThat((Long) processor.get(
                GeneratedJmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_OWNER_GENERATION)).isEqualTo(0L);
        assertThat((Integer) processor.get(
                GeneratedJmxWireContract.FIELD_EXPORT_PROCESSOR_VALUES_QUEUE_CAPACITY))
                .isEqualTo(0);
        assertThat((Boolean) exporter.get(
                GeneratedJmxWireContract.FIELD_SAFE_EXPORTER_VALUES_OWNER_PRESENT)).isFalse();
        assertThat((Long) exporter.get(
                GeneratedJmxWireContract.FIELD_SAFE_EXPORTER_VALUES_OWNER_GENERATION)).isEqualTo(0L);
    }

    @Test
    void getterReturnsPublishedSnapshotAndExplicitPublishAdvancesSequence() {
        // given
        ExportControl control = new ExportControl(
                new OwnerRegistrationSlot<PlatformDropOldestExportSpanProcessor>(),
                new OwnerRegistrationSlot<SafeSpanExporter>(),
                AGENT_INSTANCE_ID);

        // when
        CompositeData first = control.getExportObservation();
        CompositeData repeated = control.getExportObservation();
        control.publishObservationSnapshot();
        CompositeData refreshed = control.getExportObservation();

        // then
        assertThat(repeated).isSameAs(first);
        assertThat(refreshed).isNotSameAs(first);
        assertThat((Long) refreshed.get(GeneratedJmxWireContract.FIELD_EXPORT_OBSERVATION_CAPTURE_SEQUENCE))
                .isEqualTo((Long) first.get(
                        GeneratedJmxWireContract.FIELD_EXPORT_OBSERVATION_CAPTURE_SEQUENCE) + 1L);
    }
}
