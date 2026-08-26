package space.br1440.platform.tracing.otel.javaagent.jmx.diagnostics;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

import space.br1440.platform.tracing.otel.javaagent.diagnostics.PlatformLogControl;
import space.br1440.platform.tracing.otel.javaagent.diagnostics.TracingDiagnostics;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.GeneratedJmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.GeneratedJmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxOpenTypeSupport;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.ObservationSnapshotControl;
import space.br1440.platform.tracing.otel.javaagent.propagation.PlatformPropagationGate;

public final class DiagnosticsControl implements DiagnosticsControlMBean, ObservationSnapshotControl {

    private final LongAdder invalidConfigCounter;
    private final UUID agentInstanceId;
    private final AtomicLong captureSequence = new AtomicLong();
    private final AtomicReference<CompositeData> published = new AtomicReference<>();

    public DiagnosticsControl(LongAdder invalidConfigCounter, UUID agentInstanceId) {
        this.invalidConfigCounter = Objects.requireNonNull(invalidConfigCounter, "invalidConfigCounter");
        this.agentInstanceId = Objects.requireNonNull(agentInstanceId, "agentInstanceId");
        publishObservationSnapshot();
    }

    @Override
    public CompositeData getDiagnosticsObservation() {
        return published.get();
    }

    @Override
    public synchronized void publishObservationSnapshot() {
        long sequence = captureSequence.incrementAndGet();
        long startedAt = Instant.now().toEpochMilli();
        long invalidConfigCount = invalidConfigCounter.sum();

        TabularData safeWrapperMetrics = JmxOpenTypeSupport.stringLongMap(
                GeneratedJmxOpenTypes.SAFE_WRAPPER_METRICS,
                GeneratedJmxWireContract.FIELD_SAFE_WRAPPER_METRICS_KEY,
                GeneratedJmxWireContract.FIELD_SAFE_WRAPPER_METRICS_VALUE,
                TracingDiagnostics.shared().snapshot());
        String platformLogLevel = PlatformLogControl.shared().getLevel().name();
        boolean propagationEnabled = PlatformPropagationGate.shared().isEnabled();

        Map<String, Object> values = new LinkedHashMap<>();
        values.put(GeneratedJmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_SCHEMA_VERSION,
                GeneratedJmxWireContract.OBSERVATION_SCHEMA_VERSION);
        values.put(GeneratedJmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_CAPTURE_SEQUENCE, sequence);
        values.put(GeneratedJmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_AGENT_INSTANCE_ID,
                agentInstanceId.toString());
        values.put(GeneratedJmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_CAPTURE_STARTED_AT, startedAt);
        values.put(GeneratedJmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_CAPTURE_COMPLETED_AT,
                Instant.now().toEpochMilli());
        values.put(GeneratedJmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_INVALID_CONFIG_COUNT,
                invalidConfigCount);
        values.put(GeneratedJmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_SAFE_WRAPPER_METRICS,
                safeWrapperMetrics);
        values.put(GeneratedJmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_PLATFORM_LOG_LEVEL, platformLogLevel);
        values.put(GeneratedJmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_PROPAGATION_ENABLED, propagationEnabled);
        published.set(JmxOpenTypeSupport.compositeData(GeneratedJmxOpenTypes.DIAGNOSTICS_OBSERVATION, values));
    }
}
