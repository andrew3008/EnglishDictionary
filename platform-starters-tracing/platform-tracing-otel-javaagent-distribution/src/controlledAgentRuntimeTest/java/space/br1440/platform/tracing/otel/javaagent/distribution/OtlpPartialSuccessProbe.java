package space.br1440.platform.tracing.otel.javaagent.distribution;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.management.MBeanServer;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;

public final class OtlpPartialSuccessProbe {

    private static final Duration READY_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration EXPORT_OBSERVATION_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    private static final String LIFECYCLE_READY = "READY";
    private static final String LIFECYCLE_FAILED = "FAILED";

    private static final String PARTIAL_SUCCESS_BATCHES_KEY = "partial_success_batches";
    private static final String REJECTED_SPANS_KEY = "rejected_spans";
    private static final String EXPORTED_SPANS_KEY = "exported_spans";

    private OtlpPartialSuccessProbe() {
    }

    public static void main(String[] arguments) throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();

        waitForReady(server);
        Map<String, Long> metrics = emitSpansUntilPartialSuccessWithAcceptanceObserved(server);

        System.out.printf(Locale.ROOT,
                "OTLP_PARTIAL_SUCCESS_PROBE_OK partialSuccessBatches=%d rejectedSpans=%d exportedSpans=%d%n",
                metrics.getOrDefault(PARTIAL_SUCCESS_BATCHES_KEY, 0L),
                metrics.getOrDefault(REJECTED_SPANS_KEY, 0L),
                metrics.getOrDefault(EXPORTED_SPANS_KEY, 0L));
    }

    private static Map<String, Long> emitSpansUntilPartialSuccessWithAcceptanceObserved(MBeanServer server) throws Exception {
        long deadlineNanos = System.nanoTime() + EXPORT_OBSERVATION_TIMEOUT.toNanos();
        Map<String, Long> lastMetrics = Map.of();

        while (System.nanoTime() < deadlineNanos) {
            emitSpanPair();
            Thread.sleep(POLL_INTERVAL.toMillis());

            lastMetrics = readSafeExporterMetrics(server);
            if (lastMetrics.getOrDefault(PARTIAL_SUCCESS_BATCHES_KEY, 0L) > 0L
                    && lastMetrics.getOrDefault(EXPORTED_SPANS_KEY, 0L) > 0L) {
                return lastMetrics;
            }
        }

        throw new IllegalStateException(
                ("OTLP partial-success with a nonzero accepted-span count was not observed via JMX "
                        + "within %d seconds; last observed metrics=%s")
                        .formatted(EXPORT_OBSERVATION_TIMEOUT.toSeconds(), lastMetrics));
    }

    private static void emitSpanPair() {
        Tracer tracer = GlobalOpenTelemetry.getTracer("controlled-agent-otlp-partial-success-probe");
        tracer.spanBuilder("otlp-partial-success-span-1").startSpan().end();
        tracer.spanBuilder("otlp-partial-success-span-2").startSpan().end();
    }

    private static void waitForReady(MBeanServer server) throws Exception {
        long deadlineNanos = System.nanoTime() + READY_TIMEOUT.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            if (server.isRegistered(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME)) {
                CompositeData snapshot = (CompositeData) server.getAttribute(
                        JmxWireContract.EXTENSION_READINESS_OBJECT_NAME,
                        JmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT
                );
                String lifecycle = (String) snapshot.get(JmxWireContract.FIELD_READINESS_SNAPSHOT_LIFECYCLE);

                if (LIFECYCLE_READY.equals(lifecycle)) {
                    return;
                }

                if (LIFECYCLE_FAILED.equals(lifecycle)) {
                    throw new IllegalStateException(
                            "Platform extension readiness failed before the OTLP scenario could run");
                }
            }

            Thread.sleep(POLL_INTERVAL.toMillis());
        }

        throw new IllegalStateException(
                "Platform extension did not become READY within %d seconds"
                        .formatted(READY_TIMEOUT.toSeconds()));
    }

    private static Map<String, Long> readSafeExporterMetrics(MBeanServer server) throws Exception {
        CompositeData exportObservation = (CompositeData) server.getAttribute(
                JmxWireContract.EXPORT_OBSERVATION_OBJECT_NAME,
                JmxWireContract.ATTRIBUTE_EXPORT_OBSERVATION_EXPORT_OBSERVATION
        );

        CompositeData exporterValues = (CompositeData) exportObservation.get(
                JmxWireContract.FIELD_EXPORT_OBSERVATION_EXPORTER
        );

        TabularData safeExporterMetrics = (TabularData) exporterValues.get(
                JmxWireContract.FIELD_SAFE_EXPORTER_VALUES_SAFE_EXPORTER_METRICS
        );

        Map<String, Long> metrics = new LinkedHashMap<>();
        for (Object row : safeExporterMetrics.values()) {
            CompositeData entry = (CompositeData) row;
            String key = (String) entry.get(JmxWireContract.FIELD_SAFE_EXPORTER_METRICS_KEY);
            Long value = (Long) entry.get(JmxWireContract.FIELD_SAFE_EXPORTER_METRICS_VALUE);
            metrics.put(key, value);
        }

        return metrics;
    }
}
