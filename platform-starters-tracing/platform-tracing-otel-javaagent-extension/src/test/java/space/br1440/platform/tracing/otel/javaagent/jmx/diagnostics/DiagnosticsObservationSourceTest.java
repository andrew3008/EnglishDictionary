package space.br1440.platform.tracing.otel.javaagent.jmx.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.diagnostics.PlatformLogControl;
import space.br1440.platform.tracing.otel.javaagent.diagnostics.TracingDiagnostics;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.propagation.PlatformPropagationGate;

class DiagnosticsObservationSourceTest {

    private static final UUID AGENT_INSTANCE_ID = UUID.randomUUID();

    @Test
    void observationEncodesCompleteDiagnosticsSnapshot() {
        long beforeCapture = System.currentTimeMillis();
        LongAdder invalidConfigCounter = new LongAdder();
        invalidConfigCounter.add(3L);
        DiagnosticsObservationSource source = new DiagnosticsObservationSource(invalidConfigCounter, AGENT_INSTANCE_ID);

        Map<String, Long> expectedSafeWrapperMetrics = TracingDiagnostics.shared().snapshot();
        String expectedLogLevel = PlatformLogControl.shared().getLevel().name();
        boolean expectedPropagationEnabled = PlatformPropagationGate.shared().isEnabled();
        CompositeData observation = source.getDiagnosticsObservation();

        long afterCapture = System.currentTimeMillis();
        long startedAt = (Long) observation.get(
                JmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_CAPTURE_STARTED_AT);
        long completedAt = (Long) observation.get(
                JmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_CAPTURE_COMPLETED_AT);

        assertThat(observation.getCompositeType().getTypeName())
                .isEqualTo(JmxWireContract.OPEN_TYPE_DIAGNOSTICS_OBSERVATION_TYPE_NAME);
        assertThat((Long) observation.get(
                JmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_CAPTURE_SEQUENCE)).isEqualTo(1L);
        assertThat((String) observation.get(
                JmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_AGENT_INSTANCE_ID))
                .isEqualTo(AGENT_INSTANCE_ID.toString());
        assertThat(startedAt).isBetween(beforeCapture, afterCapture);
        assertThat(completedAt).isBetween(startedAt, afterCapture);
        assertThat((Long) observation.get(
                JmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_INVALID_CONFIG_COUNT))
                .isEqualTo(3L);
        assertThat(stringLongMap(observation)).isEqualTo(expectedSafeWrapperMetrics);
        assertThat((String) observation.get(
                JmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_PLATFORM_LOG_LEVEL))
                .isEqualTo(expectedLogLevel);
        assertThat((Boolean) observation.get(
                JmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_PROPAGATION_ENABLED))
                .isEqualTo(expectedPropagationEnabled);
    }

    @Test
    void getterReturnsPublishedSnapshotAndExplicitPublishAdvancesSequence() {
        // given
        DiagnosticsObservationSource source = new DiagnosticsObservationSource(new LongAdder(), AGENT_INSTANCE_ID);

        // when
        CompositeData first = source.getDiagnosticsObservation();
        CompositeData repeated = source.getDiagnosticsObservation();
        source.publishObservationSnapshot();
        CompositeData refreshed = source.getDiagnosticsObservation();

        // then
        assertThat(repeated).isSameAs(first);
        assertThat(refreshed).isNotSameAs(first);
        assertThat((Long) refreshed.get(
                JmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_CAPTURE_SEQUENCE))
                .isEqualTo((Long) first.get(
                        JmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_CAPTURE_SEQUENCE) + 1L);
    }

    @Test
    void constructorRejectsMissingDependencies() {
        assertThatThrownBy(() -> new DiagnosticsObservationSource(null, AGENT_INSTANCE_ID))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DiagnosticsObservationSource(new LongAdder(), null))
                .isInstanceOf(NullPointerException.class);
    }

    private static Map<String, Long> stringLongMap(CompositeData observation) {
        TabularData table = (TabularData) observation.get(
                JmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_SAFE_WRAPPER_METRICS
        );

        Map<String, Long> result = new LinkedHashMap<>(table.size());
        for (Object rowObject : table.values()) {
            CompositeData row = (CompositeData) rowObject;
            result.put((String) row.get("key"), (Long) row.get("value"));
        }

        return result;
    }
}
