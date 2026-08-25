package space.br1440.platform.tracing.otel.javaagent.jmx.export;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxObservationWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.processor.dropoldest.PlatformDropOldestExportSpanProcessor;

import javax.management.openmbean.CompositeData;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
        CompositeData processor = (CompositeData) observation.get(JmxObservationWireContract.EXPORT_ITEM_PROCESSOR);
        CompositeData exporter = (CompositeData) observation.get(JmxObservationWireContract.EXPORT_ITEM_EXPORTER);

        assertThat((Integer) observation.get(JmxObservationWireContract.ITEM_SCHEMA_VERSION))
                .isEqualTo(JmxObservationWireContract.SCHEMA_VERSION);
        assertThat((String) observation.get(JmxObservationWireContract.ITEM_AGENT_INSTANCE_ID))
                .isEqualTo(AGENT_INSTANCE_ID.toString());
        assertThat((Boolean) processor.get(JmxObservationWireContract.ITEM_OWNER_PRESENT)).isFalse();
        assertThat((Long) processor.get(JmxObservationWireContract.ITEM_OWNER_GENERATION)).isEqualTo(0L);
        assertThat((Integer) processor.get(JmxObservationWireContract.EXPORT_PROCESSOR_ITEM_QUEUE_CAPACITY))
                .isEqualTo(0);
        assertThat((Boolean) exporter.get(JmxObservationWireContract.ITEM_OWNER_PRESENT)).isFalse();
        assertThat((Long) exporter.get(JmxObservationWireContract.ITEM_OWNER_GENERATION)).isEqualTo(0L);
    }

    @Test
    void captureSequenceIsStrictlyMonotonicAcrossSequentialCaptures() {
        ExportControl control = new ExportControl(
                new OwnerRegistrationSlot<PlatformDropOldestExportSpanProcessor>(),
                new OwnerRegistrationSlot<SafeSpanExporter>(),
                AGENT_INSTANCE_ID);

        long first = (Long) control.getExportObservation().get(JmxObservationWireContract.ITEM_CAPTURE_SEQUENCE);
        long second = (Long) control.getExportObservation().get(JmxObservationWireContract.ITEM_CAPTURE_SEQUENCE);

        assertThat(second).isEqualTo(first + 1);
    }
}
