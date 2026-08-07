package space.br1440.platform.tracing.otel.javaagent.jmx.diagnostics;

import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxObservationWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxOpenTypeSupport;
import space.br1440.platform.tracing.otel.javaagent.propagation.PlatformPropagationGate;
import space.br1440.platform.tracing.otel.javaagent.diagnostics.ConfigReloadDiagnostics;
import space.br1440.platform.tracing.otel.javaagent.diagnostics.PlatformLogControl;
import space.br1440.platform.tracing.otel.javaagent.diagnostics.TracingDiagnostics;

import javax.management.openmbean.ArrayType;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class PlatformDiagnosticsControl implements PlatformDiagnosticsControlMBean {

    private static final TabularType CONFIG_RELOAD_METRICS_TYPE = JmxOpenTypeSupport.stringLongMapType(
            "space.br1440.platform.tracing.ConfigReloadMetrics", "ConfigReloadMetricsEntry"
    );

    private static final TabularType SAFE_WRAPPER_METRICS_TYPE = JmxOpenTypeSupport.stringLongMapType(
            "space.br1440.platform.tracing.SafeWrapperMetrics", "SafeWrapperMetricsEntry"
    );

    private static final JmxOpenTypeSupport.CompositeSchema OBSERVATION_TYPE = buildObservationType();

    private final LongAdder invalidConfigCounter;
    private final UUID agentInstanceId;
    private final AtomicLong captureSequence = new AtomicLong();

    public PlatformDiagnosticsControl(LongAdder invalidConfigCounter, UUID agentInstanceId) {
        this.invalidConfigCounter = Objects.requireNonNull(invalidConfigCounter, "invalidConfigCounter");
        this.agentInstanceId = Objects.requireNonNull(agentInstanceId, "agentInstanceId");
    }

    @Override
    public CompositeData getDiagnosticsObservation() {
        long sequence = captureSequence.incrementAndGet();
        long startedAt = Instant.now().toEpochMilli();

        long invalidConfigCount = invalidConfigCounter.sum();
        var configReloadMetrics = JmxOpenTypeSupport.stringLongMap(
                CONFIG_RELOAD_METRICS_TYPE, ConfigReloadDiagnostics.shared().snapshot());
        // JMX wire остаётся String[]: форматирование AuditEntry откладывается до границы экспорта.
        String[] configAuditTrail = ConfigReloadDiagnostics.shared().auditTrail().stream()
                .map(ConfigReloadDiagnostics.AuditEntry::toString)
                .toArray(String[]::new);
        var safeWrapperMetrics = JmxOpenTypeSupport.stringLongMap(
                SAFE_WRAPPER_METRICS_TYPE, TracingDiagnostics.shared().snapshot());
        String platformLogLevel = PlatformLogControl.shared().getLevel().name();
        boolean propagationEnabled = PlatformPropagationGate.shared().isEnabled();

        long completedAt = Instant.now().toEpochMilli();
        return JmxOpenTypeSupport.compositeData(OBSERVATION_TYPE,
                JmxObservationWireContract.SCHEMA_VERSION,
                sequence,
                agentInstanceId.toString(),
                startedAt,
                completedAt,
                invalidConfigCount,
                configReloadMetrics,
                configAuditTrail,
                safeWrapperMetrics,
                platformLogLevel,
                propagationEnabled);
    }

    private static JmxOpenTypeSupport.CompositeSchema buildObservationType() {
        LinkedHashMap<String, OpenType<?>> items = JmxOpenTypeSupport.observationFrameItems();
        items.put(JmxObservationWireContract.DIAGNOSTICS_ITEM_INVALID_CONFIG_COUNT, SimpleType.LONG);
        items.put(JmxObservationWireContract.DIAGNOSTICS_ITEM_CONFIG_RELOAD_METRICS, CONFIG_RELOAD_METRICS_TYPE);
        items.put(JmxObservationWireContract.DIAGNOSTICS_ITEM_CONFIG_AUDIT_TRAIL, stringArrayType());
        items.put(JmxObservationWireContract.DIAGNOSTICS_ITEM_SAFE_WRAPPER_METRICS, SAFE_WRAPPER_METRICS_TYPE);
        items.put(JmxObservationWireContract.DIAGNOSTICS_ITEM_PLATFORM_LOG_LEVEL, SimpleType.STRING);
        items.put(JmxObservationWireContract.DIAGNOSTICS_ITEM_PROPAGATION_ENABLED, SimpleType.BOOLEAN);
        return JmxOpenTypeSupport.compositeType(JmxObservationWireContract.DIAGNOSTICS_COMPOSITE_TYPE_NAME, items);
    }

    private static ArrayType<String[]> stringArrayType() {
        try {
            return ArrayType.getArrayType(SimpleType.STRING);
        } catch (OpenDataException e) {
            throw new IllegalStateException("Некорректный ArrayType для configAuditTrail", e);
        }
    }
}
