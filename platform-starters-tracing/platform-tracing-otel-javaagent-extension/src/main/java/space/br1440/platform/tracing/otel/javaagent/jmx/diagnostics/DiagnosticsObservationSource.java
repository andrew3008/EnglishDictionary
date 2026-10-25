package space.br1440.platform.tracing.otel.javaagent.jmx.diagnostics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

import space.br1440.platform.tracing.otel.javaagent.diagnostics.TracingDiagnostics;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxOpenTypeSupport;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.ObservationCaptureEnvelope;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.ObservationSnapshotStore;

public final class DiagnosticsObservationSource {

    private final LongAdder invalidConfigCounter;
    private final ObservationCaptureEnvelope envelope;
    private final ObservationSnapshotStore snapshotStore = new ObservationSnapshotStore(JmxOpenTypes.DIAGNOSTICS_OBSERVATION);

    public DiagnosticsObservationSource(LongAdder invalidConfigCounter, AgentInstanceId agentInstanceId) {
        this.invalidConfigCounter = Objects.requireNonNull(invalidConfigCounter, "invalidConfigCounter");
        this.envelope = new ObservationCaptureEnvelope(agentInstanceId);
        publishObservationSnapshot();
    }

    public CompositeData getDiagnosticsObservation() {
        return snapshotStore.current();
    }

    public synchronized void publishObservationSnapshot() {
        long invalidConfigCount = invalidConfigCounter.sum();

        TabularData safeWrapperMetrics = JmxOpenTypeSupport.stringLongMap(
                JmxOpenTypes.SAFE_WRAPPER_METRICS,
                JmxWireContract.FIELD_SAFE_WRAPPER_METRICS_KEY,
                JmxWireContract.FIELD_SAFE_WRAPPER_METRICS_VALUE,
                TracingDiagnostics.shared().snapshot()
        );

        Map<String, Object> values = new LinkedHashMap<>();
        envelope.putInto(values);
        values.put(JmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_INVALID_CONFIG_COUNT, invalidConfigCount);
        values.put(JmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_SAFE_WRAPPER_METRICS, safeWrapperMetrics);
        snapshotStore.publish(values);
    }
}
