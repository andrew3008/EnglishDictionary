package space.br1440.platform.tracing.otel.javaagent.distribution;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Optional;

import org.awaitility.core.ConditionTimeoutException;

final class ExportedSpans {

    private static final Duration EXPORT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration EXPORT_POLL_INTERVAL = Duration.ofMillis(100);

    private ExportedSpans() {
    }

    static OtlpTraceCollector.ReceivedSpan awaitByName(OtlpTraceCollector collector, String spanName) {
        try {
            return await("export of span " + spanName)
                    .atMost(EXPORT_TIMEOUT)
                    .pollInterval(EXPORT_POLL_INTERVAL)
                    .until(() -> collector.receivedSpans().stream()
                                    .filter(span -> spanName.equals(span.name()))
                                    .findFirst(),
                            Optional::isPresent)
                    .orElseThrow();
        } catch (ConditionTimeoutException timeout) {
            throw new AssertionError(
                    "The span was never exported: name=%s receivedSpans=%s"
                            .formatted(spanName, collector.receivedSpans()),
                    timeout
            );
        }
    }

    static OtlpTraceCollector.ReceivedSpan awaitBySpanId(OtlpTraceCollector collector, String spanId) {
        try {
            return await("export of span " + spanId)
                    .atMost(EXPORT_TIMEOUT)
                    .pollInterval(EXPORT_POLL_INTERVAL)
                    .until(() -> collector.receivedSpans().stream()
                                    .filter(span -> span.spanId().equals(spanId))
                                    .findFirst(),
                            Optional::isPresent)
                    .orElseThrow();
        } catch (ConditionTimeoutException timeout) {
            throw new AssertionError(
                    "The invalid span was never exported: spanId=%s receivedSpans=%s"
                            .formatted(spanId, collector.receivedSpans()),
                    timeout
            );
        }
    }
}
