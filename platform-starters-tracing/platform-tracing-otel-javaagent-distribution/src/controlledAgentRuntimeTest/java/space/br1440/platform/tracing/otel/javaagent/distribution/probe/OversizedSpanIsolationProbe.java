package space.br1440.platform.tracing.otel.javaagent.distribution.probe;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import javax.management.MBeanServer;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;

public final class OversizedSpanIsolationProbe {

    public static final String NAME_PREFIX = "oversized-isolation-";
    public static final String LEFT_SPAN = NAME_PREFIX + "left";
    public static final String RIGHT_SPAN = NAME_PREFIX + "right";
    public static final String OVERSIZED_SPAN = NAME_PREFIX + "oversized";
    public static final String FOLLOW_UP_PREFIX = NAME_PREFIX + "follow-up-";
    public static final String TRUNCATED_NAME = NAME_PREFIX + "s".repeat(1024 - NAME_PREFIX.length());

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    private OversizedSpanIsolationProbe() {
    }

    public static void main(String[] arguments) throws InterruptedException {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        awaitReady(server);
        requireMetricsOwnerAbsent(server);

        Tracer tracer = GlobalOpenTelemetry.getTracer("controlled-agent-oversized-isolation-probe");
        List<Span> mixedBatch = prepareMixedBatch(tracer);
        Snapshot baseline = primeWorker(server, tracer);

        mixedBatch.forEach(Span::end);
        Snapshot first = awaitExport(server, baseline, 3L);
        if (first.batches() - baseline.batches() != 1L) {
            throw new IllegalStateException("Mixed probe spans must belong to one logical export batch");
        }

        for (int index = 0; index < 4; index++) {
            tracer.spanBuilder(FOLLOW_UP_PREFIX + index).startSpan().end();
        }

        awaitExport(server, baseline, 7L);

        System.out.println("OVERSIZED_SPAN_ISOLATION_PROBE_OK oversized=1 shape=1 transportDropped=0 failures=0");
    }

    private static List<Span> prepareMixedBatch(Tracer tracer) {
        Span oversized = tracer.spanBuilder(OVERSIZED_SPAN).startSpan();
        List<String> values = Collections.nCopies(128, "界".repeat(1000));
        for (int index = 0; index < 50; index++) {
            oversized.setAttribute(AttributeKey.stringArrayKey("payload." + index), values);
        }

        Span left = tracer.spanBuilder(LEFT_SPAN).startSpan();
        Span right = tracer.spanBuilder(RIGHT_SPAN).startSpan();
        Span shaped = tracer.spanBuilder(TRUNCATED_NAME + "s").startSpan();
        shaped.setStatus(StatusCode.ERROR, "d".repeat(1025));

        return List.of(left, oversized, right, shaped);
    }

    private static Snapshot primeWorker(MBeanServer server, Tracer tracer) throws InterruptedException {
        Snapshot baseline = Snapshot.read(server);
        for (int index = 0; index < 4; index++) {
            tracer.spanBuilder("isolation-warmup-" + index).startSpan().end();
        }

        MonotonicDeadline deadline = MonotonicDeadline.after(TIMEOUT);
        while (deadline.hasRemaining()) {
            Snapshot latest = Snapshot.read(server);
            if (latest.exported() - baseline.exported() >= 4L) {
                return latest;
            }

            Thread.sleep(POLL_INTERVAL);
        }

        throw new IllegalStateException("Warmup export did not complete within " + TIMEOUT);
    }

    private static void awaitReady(MBeanServer server) throws InterruptedException {
        MonotonicDeadline deadline = MonotonicDeadline.after(TIMEOUT);
        while (deadline.hasRemaining()) {
            if (server.isRegistered(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME)) {
                CompositeData readiness = JmxValues.attribute(server,
                        JmxWireContract.EXTENSION_READINESS_OBJECT_NAME,
                        JmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT,
                        JmxOpenTypes.READINESS_SNAPSHOT);

                String lifecycle = JmxValues.token(readiness, JmxWireContract.FIELD_READINESS_SNAPSHOT_LIFECYCLE,
                        JmxWireContract.VALID_READINESS_LIFECYCLES);

                if ("READY".equals(lifecycle)) {
                    return;
                }

                if ("FAILED".equals(lifecycle)) {
                    throw new IllegalStateException("Platform extension readiness failed");
                }
            }

            Thread.sleep(POLL_INTERVAL);
        }

        throw new IllegalStateException("Platform extension did not become ready within " + TIMEOUT);
    }

    private static void requireMetricsOwnerAbsent(MBeanServer server) {
        CompositeData observation = JmxValues.attribute(server,
                JmxWireContract.PROCESSOR_METRICS_OBSERVATION_OBJECT_NAME,
                JmxWireContract.ATTRIBUTE_PROCESSOR_METRICS_OBSERVATION_PROCESSOR_METRICS_OBSERVATION,
                JmxOpenTypes.PROCESSOR_METRICS_OBSERVATION);

        CompositeData metrics = JmxValues.composite(observation, JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_METRICS);
        if (JmxValues.bool(metrics, JmxWireContract.FIELD_PROCESSOR_METRICS_VALUES_OWNER_PRESENT)) {
            throw new IllegalStateException("Processor metrics owner must be absent when metrics are disabled");
        }
    }

    private static Snapshot awaitExport(MBeanServer server, Snapshot baseline, long expectedExported) throws InterruptedException {
        MonotonicDeadline deadline = MonotonicDeadline.after(TIMEOUT);
        Snapshot latest = baseline;
        while (deadline.hasRemaining()) {
            latest = Snapshot.read(server);
            if (!latest.agentInstanceId().equals(baseline.agentInstanceId())
                    || latest.ownerGeneration() != baseline.ownerGeneration()) {
                throw new IllegalStateException("Safe exporter owner changed during oversized-span probe");
            }

            if (latest.transportDropped() != baseline.transportDropped() || latest.failures() != baseline.failures()) {
                throw new IllegalStateException("Oversized isolation caused a transport failure: " + latest);
            }

            if (latest.oversized() - baseline.oversized() == 1L
                    && latest.shape() - baseline.shape() == 1L
                    && latest.exported() - baseline.exported() >= expectedExported) {
                return latest;
            }

            Thread.sleep(POLL_INTERVAL);
        }

        throw new IllegalStateException(
                "Oversized isolation was not observed within %s; baseline=%s, latest=%s"
                .formatted(TIMEOUT, baseline, latest)
        );
    }

    private record Snapshot(String agentInstanceId, long ownerGeneration, long batches, long oversized,
                            long shape, long exported, long transportDropped, long failures) {

        static Snapshot read(MBeanServer server) {
            CompositeData observation = JmxValues.attribute(server,
                    JmxWireContract.EXPORT_OBSERVATION_OBJECT_NAME,
                    JmxWireContract.ATTRIBUTE_EXPORT_OBSERVATION_EXPORT_OBSERVATION,
                    JmxOpenTypes.EXPORT_OBSERVATION);

            CompositeData exporter = JmxValues.composite(observation, JmxWireContract.FIELD_EXPORT_OBSERVATION_EXPORTER);
            if (!JmxValues.bool(exporter, JmxWireContract.FIELD_SAFE_EXPORTER_VALUES_OWNER_PRESENT)) {
                throw new IllegalStateException("Safe exporter owner is absent");
            }

            TabularData metrics = JmxValues.tabular(exporter, JmxWireContract.FIELD_SAFE_EXPORTER_VALUES_SAFE_EXPORTER_METRICS);
            return new Snapshot(
                    JmxValues.nonBlankString(observation, JmxWireContract.FIELD_EXPORT_OBSERVATION_AGENT_INSTANCE_ID),
                    JmxValues.longValue(exporter, JmxWireContract.FIELD_SAFE_EXPORTER_VALUES_OWNER_GENERATION),
                    counter(metrics, JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORT_BATCHES),
                    counter(metrics, JmxWireContract.KEY_SAFE_EXPORTER_METRICS_OVERSIZED_SPANS_DROPPED),
                    counter(metrics, JmxWireContract.KEY_SAFE_EXPORTER_METRICS_SHAPE_TRUNCATIONS_TOTAL),
                    counter(metrics, JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORTED_SPANS),
                    counter(metrics, JmxWireContract.KEY_SAFE_EXPORTER_METRICS_TRANSPORT_DROPPED_SPANS),
                    counter(metrics, JmxWireContract.KEY_SAFE_EXPORTER_METRICS_EXPORT_BATCH_FAILURES));
        }

        private static long counter(TabularData metrics, String key) {
            CompositeData row = JmxValues.asComposite(metrics.get(new Object[] {key}), key);

            long value = JmxValues.longValue(row, JmxWireContract.FIELD_SAFE_EXPORTER_METRICS_VALUE);
            if (value < 0L) {
                throw new IllegalStateException("Safe exporter counter must not be negative: " + key);
            }

            return value;
        }
    }
}
