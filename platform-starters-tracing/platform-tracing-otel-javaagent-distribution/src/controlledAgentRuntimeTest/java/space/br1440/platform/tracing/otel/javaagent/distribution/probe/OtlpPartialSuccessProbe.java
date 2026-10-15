package space.br1440.platform.tracing.otel.javaagent.distribution.probe;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Locale;
import javax.management.MBeanServer;
import javax.management.openmbean.CompositeData;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;

public final class OtlpPartialSuccessProbe {

    public static final String PROBE_SPAN_NAME = "otlp-partial-success-span";

    private static final Duration READINESS_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration OBSERVATION_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    private static final String LIFECYCLE_READY = "READY";
    private static final String LIFECYCLE_FAILED = "FAILED";

    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("controlled-agent-otlp-partial-success-probe");

    private OtlpPartialSuccessProbe() {
    }

    public static void main(String[] arguments) throws InterruptedException {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();

        awaitExtensionReadiness(server);
        ExporterSnapshot baseline = ExporterSnapshot.read(server);
        ExporterSnapshot.MetricsDelta delta = awaitPartialSuccess(server, baseline);

        System.out.printf(
                Locale.ROOT,
                "OTLP_PARTIAL_SUCCESS_PROBE_OK partialSuccessBatchesDelta=%d rejectedSpansDelta=%d exportedSpansDelta=%d%n",
                delta.partialSuccessBatches(),
                delta.rejectedSpans(),
                delta.exportedSpans());
    }

    private static void awaitExtensionReadiness(MBeanServer server) throws InterruptedException {
        MonotonicDeadline deadline = MonotonicDeadline.after(READINESS_TIMEOUT);
        String lastLifecycle = "<mbean-not-registered>";

        while (deadline.hasRemaining()) {
            if (server.isRegistered(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME)) {
                CompositeData snapshot = JmxValues.attribute(
                        server,
                        JmxWireContract.EXTENSION_READINESS_OBJECT_NAME,
                        JmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT,
                        JmxOpenTypes.READINESS_SNAPSHOT
                );

                lastLifecycle = JmxValues.token(
                        snapshot,
                        JmxWireContract.FIELD_READINESS_SNAPSHOT_LIFECYCLE,
                        JmxWireContract.VALID_READINESS_LIFECYCLES
                );

                if (LIFECYCLE_READY.equals(lastLifecycle)) {
                    return;
                }

                if (LIFECYCLE_FAILED.equals(lastLifecycle)) {
                    String message = "Platform extension readiness failed; failureCode="
                            + JmxValues.nonBlankString(snapshot, JmxWireContract.FIELD_READINESS_SNAPSHOT_FAILURE_CODE);
                    throw new IllegalStateException(message);
                }
            }

            Thread.sleep(POLL_INTERVAL);
        }

        throw new IllegalStateException("Platform extension did not become READY within %s; lastLifecycle=%s"
                .formatted(READINESS_TIMEOUT, lastLifecycle));
    }

    private static ExporterSnapshot.MetricsDelta awaitPartialSuccess(MBeanServer server, ExporterSnapshot baseline) throws InterruptedException {
        MonotonicDeadline deadline = MonotonicDeadline.after(OBSERVATION_TIMEOUT);
        ExporterSnapshot latest = baseline;

        while (deadline.hasRemaining()) {
            emitSpanPair();
            Thread.sleep(POLL_INTERVAL);

            latest = ExporterSnapshot.read(server);
            ExporterSnapshot.MetricsDelta delta = latest.deltaFrom(baseline);
            if (delta.satisfiesPartialSuccess()) {
                return delta;
            }
        }

        throw new IllegalStateException(
                "OTLP partial success was not observed within %s; required: %s; baseline=%s, latest=%s, delta=%s"
                        .formatted(
                                OBSERVATION_TIMEOUT,
                                ExporterSnapshot.MetricsDelta.PARTIAL_SUCCESS_CRITERION,
                                baseline,
                                latest,
                                latest.deltaFrom(baseline)
                        )
        );
    }

    private static void emitSpanPair() {
        TRACER.spanBuilder(PROBE_SPAN_NAME).startSpan().end();
        TRACER.spanBuilder(PROBE_SPAN_NAME).startSpan().end();
    }
}
