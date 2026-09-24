package space.br1440.platform.tracing.otel.javaagent.jmx.diagnostics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

import space.br1440.platform.tracing.otel.javaagent.diagnostics.PlatformLogControl;
import space.br1440.platform.tracing.otel.javaagent.diagnostics.TracingDiagnostics;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxOpenTypeSupport;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.ObservationCaptureEnvelope;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.ObservationSnapshotStore;
import space.br1440.platform.tracing.otel.javaagent.propagation.PlatformPropagationGate;

public final class DiagnosticsObservationSource {

    private final LongAdder invalidConfigCounter;
    private final ObservationCaptureEnvelope envelope;
    private final ObservationSnapshotStore snapshotStore = new ObservationSnapshotStore(JmxOpenTypes.DIAGNOSTICS_OBSERVATION);

    public DiagnosticsObservationSource(LongAdder invalidConfigCounter, UUID agentInstanceId) {
        this.invalidConfigCounter = Objects.requireNonNull(invalidConfigCounter, "invalidConfigCounter");
        this.envelope = new ObservationCaptureEnvelope(agentInstanceId);
        publishObservationSnapshot();
    }

    public CompositeData getDiagnosticsObservation() {
        return snapshotStore.current();
    }

    public synchronized void publishObservationSnapshot() {
        ObservationCaptureEnvelope.OpenCapture openCapture = envelope.open();
        long invalidConfigCount = invalidConfigCounter.sum();

        TabularData safeWrapperMetrics = JmxOpenTypeSupport.stringLongMap(
                JmxOpenTypes.SAFE_WRAPPER_METRICS,
                JmxWireContract.FIELD_SAFE_WRAPPER_METRICS_KEY,
                JmxWireContract.FIELD_SAFE_WRAPPER_METRICS_VALUE,
                TracingDiagnostics.shared().snapshot()
        );

        String platformLogLevel = PlatformLogControl.shared().getLevel().name();
        boolean propagationEnabled = PlatformPropagationGate.shared().isEnabled();

        Map<String, Object> values = new LinkedHashMap<>();
        openCapture.sealInto(values);
        values.put(JmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_INVALID_CONFIG_COUNT, invalidConfigCount);
        values.put(JmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_SAFE_WRAPPER_METRICS, safeWrapperMetrics);
        values.put(JmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_PLATFORM_LOG_LEVEL, platformLogLevel);
        values.put(JmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_PROPAGATION_ENABLED, propagationEnabled);
        snapshotStore.publish(values);
    }
}
