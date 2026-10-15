package space.br1440.platform.tracing.otel.javaagent.distribution.probe;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Locale;
import javax.management.MBeanServer;
import javax.management.openmbean.CompositeData;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;

public final class SpanAttributeValidationDiagnosticProbe {

    private static final Duration READY_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration OBSERVATION_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    private static final String LIFECYCLE_READY = "READY";
    private static final String LIFECYCLE_FAILED = "FAILED";

    public static final String INVALID_SPAN_NAME = "span-attribute-validation-invalid";

    private SpanAttributeValidationDiagnosticProbe() {
    }

    public static void main(String[] arguments) throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        waitForReady(server);

        CompositeData validationBefore = validationObservation(server);
        String validationLevel = (String) validationBefore.get(
                JmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_LEVEL
        );
        if (!"ERROR".equals(validationLevel)) {
            throw new IllegalStateException("""
                    Process ERROR did not become the accepted validation state: level=%s\
                    """.formatted(validationLevel)
            );
        }

        long violationsBefore = (Long) validationBefore.get(
                JmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_VIOLATIONS_TOTAL
        );

        SpanIdentity stimulus = emitInvalidSpan();
        long violationsAfter = waitForViolationGrowth(server, violationsBefore);

        String message = """
                SPAN_ATTRIBUTE_VALIDATION_DIAGNOSTIC_OK level=%s \
                violationsBefore=%d violationsAfter=%d traceId=%s spanId=%s%n\
                """;
        System.out.printf(
                Locale.ROOT,
                message,
                validationLevel,
                violationsBefore,
                violationsAfter,
                stimulus.traceId(),
                stimulus.spanId()
        );
    }

    private static SpanIdentity emitInvalidSpan() {
        Tracer tracer = GlobalOpenTelemetry.getTracer("controlled-agent-span-attribute-validation-probe");
        Span span = tracer.spanBuilder(INVALID_SPAN_NAME).startSpan();
        SpanContext spanContext = span.getSpanContext();
        span.end();

        return new SpanIdentity(spanContext.getTraceId(), spanContext.getSpanId());
    }

    private static long waitForViolationGrowth(MBeanServer server, long violationsBefore) throws Exception {
        MonotonicDeadline deadline = MonotonicDeadline.after(OBSERVATION_TIMEOUT);
        long lastViolations = violationsBefore;

        while (deadline.hasRemaining()) {
            lastViolations = (Long) validationObservation(server).get(
                    JmxWireContract.FIELD_VALIDATION_OBSERVATION_VALIDATION_VIOLATIONS_TOTAL
            );

            if (lastViolations > violationsBefore) {
                return lastViolations;
            }

            awaitNextPoll();
        }

        throw new IllegalStateException("""
                Diagnostic ERROR stimulus did not increase validationViolationsTotal within %d seconds; \
                violationsBefore=%d lastViolations=%d\
                """.formatted(OBSERVATION_TIMEOUT.toSeconds(), violationsBefore, lastViolations)
        );
    }

    private static void waitForReady(MBeanServer server) throws Exception {
        MonotonicDeadline deadline = MonotonicDeadline.after(READY_TIMEOUT);

        while (deadline.hasRemaining()) {
            if (server.isRegistered(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME)) {
                CompositeData snapshot = (CompositeData) server.getAttribute(
                        JmxWireContract.EXTENSION_READINESS_OBJECT_NAME,
                        JmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT
                );

                if (!JmxOpenTypes.READINESS_SNAPSHOT.equals(snapshot.getCompositeType())) {
                    throw new IllegalStateException(
                            "ReadinessSnapshot OpenType differs from the generated application contract");
                }

                String lifecycle = (String) snapshot.get(JmxWireContract.FIELD_READINESS_SNAPSHOT_LIFECYCLE);

                if (LIFECYCLE_READY.equals(lifecycle)) {
                    return;
                }

                if (LIFECYCLE_FAILED.equals(lifecycle)) {
                    throw new IllegalStateException(
                            "Platform extension readiness failed before the diagnostic scenario could run");
                }
            }

            awaitNextPoll();
        }

        throw new IllegalStateException(
                "Platform extension did not become READY within %d seconds"
                        .formatted(READY_TIMEOUT.toSeconds())
        );
    }

    private static void awaitNextPoll() throws InterruptedException {
        Thread.sleep(POLL_INTERVAL.toMillis());
    }

    private static CompositeData validationObservation(MBeanServer server) throws Exception {
        CompositeData observation = (CompositeData) server.getAttribute(
                JmxWireContract.VALIDATION_OBSERVATION_OBJECT_NAME,
                JmxWireContract.ATTRIBUTE_VALIDATION_OBSERVATION_VALIDATION_OBSERVATION
        );

        if (!JmxOpenTypes.VALIDATION_OBSERVATION.equals(observation.getCompositeType())) {
            throw new IllegalStateException("ValidationObservation OpenType differs from the generated application contract");
        }

        return observation;
    }

    private record SpanIdentity(String traceId, String spanId) {
    }
}
