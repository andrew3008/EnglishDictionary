package space.br1440.platform.tracing.otel.javaagent.jmx.diagnostics;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularType;

import space.br1440.platform.tracing.otel.javaagent.diagnostics.PlatformLogControl;
import space.br1440.platform.tracing.otel.javaagent.diagnostics.TracingDiagnostics;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxObservationWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxOpenTypeSupport;
import space.br1440.platform.tracing.otel.javaagent.propagation.PlatformPropagationGate;

public final class DiagnosticsControl implements DiagnosticsControlMBean {

    private static final TabularType SAFE_WRAPPER_METRICS_TYPE = JmxOpenTypeSupport.stringLongMapType(
            "space.br1440.platform.tracing.SafeWrapperMetrics", "SafeWrapperMetricsEntry"
    );
    private static final JmxOpenTypeSupport.CompositeSchema OBSERVATION_TYPE = buildObservationType();

    private final LongAdder invalidConfigCounter;
    private final UUID agentInstanceId;
    private final AtomicLong captureSequence = new AtomicLong();

    public DiagnosticsControl(LongAdder invalidConfigCounter, UUID agentInstanceId) {
        this.invalidConfigCounter = Objects.requireNonNull(invalidConfigCounter, "invalidConfigCounter");
        this.agentInstanceId = Objects.requireNonNull(agentInstanceId, "agentInstanceId");
    }

    @Override
    public CompositeData getDiagnosticsObservation() {
        long sequence = captureSequence.incrementAndGet();
        long startedAt = Instant.now().toEpochMilli();
        long invalidConfigCount = invalidConfigCounter.sum();

        TabularData safeWrapperMetrics = JmxOpenTypeSupport.stringLongMap(SAFE_WRAPPER_METRICS_TYPE, TracingDiagnostics.shared().snapshot());
        String platformLogLevel = PlatformLogControl.shared().getLevel().name();
        boolean propagationEnabled = PlatformPropagationGate.shared().isEnabled();

        long completedAt = Instant.now().toEpochMilli();
        return JmxOpenTypeSupport.compositeData(
                OBSERVATION_TYPE,
                JmxObservationWireContract.SCHEMA_VERSION,
                sequence,
                agentInstanceId.toString(),
                startedAt,
                completedAt,
                invalidConfigCount,
                safeWrapperMetrics,
                platformLogLevel,
                propagationEnabled
        );
    }

    private static JmxOpenTypeSupport.CompositeSchema buildObservationType() {
        LinkedHashMap<String, OpenType<?>> items = JmxOpenTypeSupport.observationFrameItems();
        items.put(JmxObservationWireContract.DIAGNOSTICS_ITEM_INVALID_CONFIG_COUNT, SimpleType.LONG);
        items.put(JmxObservationWireContract.DIAGNOSTICS_ITEM_SAFE_WRAPPER_METRICS, SAFE_WRAPPER_METRICS_TYPE);
        items.put(JmxObservationWireContract.DIAGNOSTICS_ITEM_PLATFORM_LOG_LEVEL, SimpleType.STRING);
        items.put(JmxObservationWireContract.DIAGNOSTICS_ITEM_PROPAGATION_ENABLED, SimpleType.BOOLEAN);
        return JmxOpenTypeSupport.compositeType(JmxObservationWireContract.DIAGNOSTICS_COMPOSITE_TYPE_NAME, items);
    }
}
