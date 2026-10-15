package space.br1440.platform.tracing.otel.javaagent.distribution.probe;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Locale;
import javax.management.MBeanServer;
import javax.management.openmbean.CompositeData;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

public final class RequestIdPropagationProbe {

    public static final String OK_PREFIX = "REQUEST_ID_PROPAGATION_OK";
    public static final String PARENT_SPAN_NAME = "request-id-parent";
    public static final String CHILD_SPAN_NAME = "request-id-child";
    public static final String REQUEST_ID = "req-propagated";
    public static final String REQUEST_ID_KEY = "platform.request_id";

    private static final Duration READY_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);
    private static final String LIFECYCLE_READY = "READY";
    private static final String LIFECYCLE_FAILED = "FAILED";

    private RequestIdPropagationProbe() {
    }

    public static void main(String[] arguments) throws Exception {
        waitForReady(ManagementFactory.getPlatformMBeanServer());
        emitParentAndChild();
        System.out.printf(Locale.ROOT, "%s requestId=%s%n", OK_PREFIX, REQUEST_ID);
    }

    private static void emitParentAndChild() {
        Tracer tracer = GlobalOpenTelemetry.getTracer("controlled-agent-request-id-propagation-probe");
        AttributeKey<String> requestId = AttributeKey.stringKey(REQUEST_ID_KEY);

        Span parent = tracer.spanBuilder(PARENT_SPAN_NAME).startSpan();
        parent.setAttribute(requestId, REQUEST_ID);
        try (Scope ignored = parent.makeCurrent()) {
            Span child = tracer.spanBuilder(CHILD_SPAN_NAME).startSpan();
            child.end();
        }
        parent.end();
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
                    throw new IllegalStateException("Platform extension readiness failed before request id propagation");
                }
            }

            awaitNextPoll();
        }

        throw new IllegalStateException("Platform extension did not become READY before request id propagation");
    }

    private static void awaitNextPoll() throws InterruptedException {
        Thread.sleep(POLL_INTERVAL.toMillis());
    }
}
