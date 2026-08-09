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

import space.br1440.platform.tracing.otel.javaagent.diagnostics.ConfigReloadDiagnostics;
import space.br1440.platform.tracing.otel.javaagent.diagnostics.PlatformLogControl;
import space.br1440.platform.tracing.otel.javaagent.diagnostics.TracingDiagnostics;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxObservationWireContract;
import space.br1440.platform.tracing.otel.javaagent.propagation.PlatformPropagationGate;

class PlatformDiagnosticsControlTest {

    private static final UUID AGENT_INSTANCE_ID = UUID.randomUUID();

    @Test
    void observationEncodesCompleteDiagnosticsSnapshot() {
        LongAdder invalidConfigCounter = new LongAdder();
        invalidConfigCounter.add(3L);
        PlatformDiagnosticsControl control = new PlatformDiagnosticsControl(invalidConfigCounter, AGENT_INSTANCE_ID);

        Map<String, Long> expectedConfigReloadMetrics = ConfigReloadDiagnostics.shared().snapshot();
        Map<String, Long> expectedSafeWrapperMetrics = TracingDiagnostics.shared().snapshot();
        String expectedLogLevel = PlatformLogControl.shared().getLevel().name();
        boolean expectedPropagationEnabled = PlatformPropagationGate.shared().isEnabled();
        long beforeCapture = System.currentTimeMillis();

        CompositeData observation = control.getDiagnosticsObservation();

        long afterCapture = System.currentTimeMillis();
        long startedAt = (Long) observation.get(JmxObservationWireContract.ITEM_CAPTURE_STARTED_AT);
        long completedAt = (Long) observation.get(JmxObservationWireContract.ITEM_CAPTURE_COMPLETED_AT);

        assertThat(observation.getCompositeType().getTypeName())
                .isEqualTo(JmxObservationWireContract.DIAGNOSTICS_COMPOSITE_TYPE_NAME);
        assertThat((Integer) observation.get(JmxObservationWireContract.ITEM_SCHEMA_VERSION))
                .isEqualTo(JmxObservationWireContract.SCHEMA_VERSION);
        assertThat((Long) observation.get(JmxObservationWireContract.ITEM_CAPTURE_SEQUENCE)).isEqualTo(1L);
        assertThat((String) observation.get(JmxObservationWireContract.ITEM_AGENT_INSTANCE_ID))
                .isEqualTo(AGENT_INSTANCE_ID.toString());
        assertThat(startedAt).isBetween(beforeCapture, afterCapture);
        assertThat(completedAt).isBetween(startedAt, afterCapture);
        assertThat((Long) observation.get(JmxObservationWireContract.DIAGNOSTICS_ITEM_INVALID_CONFIG_COUNT))
                .isEqualTo(3L);
        assertThat(stringLongMap(observation, JmxObservationWireContract.DIAGNOSTICS_ITEM_CONFIG_RELOAD_METRICS))
                .isEqualTo(expectedConfigReloadMetrics);
        assertThat(observation.get(JmxObservationWireContract.DIAGNOSTICS_ITEM_CONFIG_AUDIT_TRAIL))
                .isInstanceOf(String[].class);
        assertThat(stringLongMap(observation, JmxObservationWireContract.DIAGNOSTICS_ITEM_SAFE_WRAPPER_METRICS))
                .isEqualTo(expectedSafeWrapperMetrics);
        assertThat((String) observation.get(JmxObservationWireContract.DIAGNOSTICS_ITEM_PLATFORM_LOG_LEVEL))
                .isEqualTo(expectedLogLevel);
        assertThat((Boolean) observation.get(JmxObservationWireContract.DIAGNOSTICS_ITEM_PROPAGATION_ENABLED))
                .isEqualTo(expectedPropagationEnabled);
    }

    @Test
    void captureSequenceIsStrictlyMonotonicAcrossSequentialCaptures() {
        PlatformDiagnosticsControl control = new PlatformDiagnosticsControl(new LongAdder(), AGENT_INSTANCE_ID);

        long first = (Long) control.getDiagnosticsObservation()
                .get(JmxObservationWireContract.ITEM_CAPTURE_SEQUENCE);
        long second = (Long) control.getDiagnosticsObservation()
                .get(JmxObservationWireContract.ITEM_CAPTURE_SEQUENCE);

        assertThat(second).isEqualTo(first + 1L);
    }

    @Test
    void constructorRejectsMissingDependencies() {
        assertThatThrownBy(() -> new PlatformDiagnosticsControl(null, AGENT_INSTANCE_ID))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlatformDiagnosticsControl(new LongAdder(), null))
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
