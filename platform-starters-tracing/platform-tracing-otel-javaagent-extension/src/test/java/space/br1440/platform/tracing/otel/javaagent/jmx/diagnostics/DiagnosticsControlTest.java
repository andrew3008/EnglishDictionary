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
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.GeneratedJmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.propagation.PlatformPropagationGate;

class DiagnosticsControlTest {

    private static final UUID AGENT_INSTANCE_ID = UUID.randomUUID();

    @Test
    void observationEncodesCompleteDiagnosticsSnapshot() {
        long beforeCapture = System.currentTimeMillis();
        LongAdder invalidConfigCounter = new LongAdder();
        invalidConfigCounter.add(3L);
        DiagnosticsControl control = new DiagnosticsControl(invalidConfigCounter, AGENT_INSTANCE_ID);

        Map<String, Long> expectedSafeWrapperMetrics = TracingDiagnostics.shared().snapshot();
        String expectedLogLevel = PlatformLogControl.shared().getLevel().name();
        boolean expectedPropagationEnabled = PlatformPropagationGate.shared().isEnabled();
        CompositeData observation = control.getDiagnosticsObservation();

        long afterCapture = System.currentTimeMillis();
        long startedAt = (Long) observation.get(
                GeneratedJmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_CAPTURE_STARTED_AT);
        long completedAt = (Long) observation.get(
                GeneratedJmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_CAPTURE_COMPLETED_AT);

        assertThat(observation.getCompositeType().getTypeName())
                .isEqualTo(GeneratedJmxWireContract.OPEN_TYPE_DIAGNOSTICS_OBSERVATION_TYPE_NAME);
        assertThat((Integer) observation.get(
                GeneratedJmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_SCHEMA_VERSION))
                .isEqualTo(GeneratedJmxWireContract.OBSERVATION_SCHEMA_VERSION);
        assertThat((Long) observation.get(
                GeneratedJmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_CAPTURE_SEQUENCE)).isEqualTo(1L);
        assertThat((String) observation.get(
                GeneratedJmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_AGENT_INSTANCE_ID))
                .isEqualTo(AGENT_INSTANCE_ID.toString());
        assertThat(startedAt).isBetween(beforeCapture, afterCapture);
        assertThat(completedAt).isBetween(startedAt, afterCapture);
        assertThat((Long) observation.get(
                GeneratedJmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_INVALID_CONFIG_COUNT))
                .isEqualTo(3L);
        assertThat(stringLongMap(
                observation,
                GeneratedJmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_SAFE_WRAPPER_METRICS))
                .isEqualTo(expectedSafeWrapperMetrics);
        assertThat((String) observation.get(
                GeneratedJmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_PLATFORM_LOG_LEVEL))
                .isEqualTo(expectedLogLevel);
        assertThat((Boolean) observation.get(
                GeneratedJmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_PROPAGATION_ENABLED))
                .isEqualTo(expectedPropagationEnabled);
    }

    @Test
    void getterReturnsPublishedSnapshotAndExplicitPublishAdvancesSequence() {
        // given
        DiagnosticsControl control = new DiagnosticsControl(new LongAdder(), AGENT_INSTANCE_ID);

        // when
        CompositeData first = control.getDiagnosticsObservation();
        CompositeData repeated = control.getDiagnosticsObservation();
        control.publishObservationSnapshot();
        CompositeData refreshed = control.getDiagnosticsObservation();

        // then
        assertThat(repeated).isSameAs(first);
        assertThat(refreshed).isNotSameAs(first);
        assertThat((Long) refreshed.get(
                GeneratedJmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_CAPTURE_SEQUENCE))
                .isEqualTo((Long) first.get(
                        GeneratedJmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_CAPTURE_SEQUENCE) + 1L);
    }

    @Test
    void constructorRejectsMissingDependencies() {
        assertThatThrownBy(() -> new DiagnosticsControl(null, AGENT_INSTANCE_ID))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DiagnosticsControl(new LongAdder(), null))
                .isInstanceOf(NullPointerException.class);
    }

    private static Map<String, Long> stringLongMap(CompositeData observation, String itemName) {
        TabularData table = (TabularData) observation.get(itemName);
        Map<String, Long> result = new LinkedHashMap<>(table.size());
        for (Object rowObject : table.values()) {
            CompositeData row = (CompositeData) rowObject;
            result.put((String) row.get("key"), (Long) row.get("value"));
        }

        return result;
    }
}
