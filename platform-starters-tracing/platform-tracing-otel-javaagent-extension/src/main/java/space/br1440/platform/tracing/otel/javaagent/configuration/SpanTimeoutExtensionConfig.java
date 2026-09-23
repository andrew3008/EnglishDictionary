package space.br1440.platform.tracing.otel.javaagent.configuration;

import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_SPAN_TIMEOUT_ENABLED;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_SPAN_TIMEOUT_MAX_TRACKED_SPANS;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_SPAN_TIMEOUT_MAX_TRACKED_SPANS_PER_TRACE;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_SPAN_TIMEOUT_MAX_TRACKED_TRACES;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_SPAN_TIMEOUT_SCAN_INTERVAL;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_SPAN_TIMEOUT_SPAN_TIMEOUT;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_SPAN_TIMEOUT_TRACE_TIMEOUT;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.SPAN_TIMEOUT_ENABLED;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.SPAN_TIMEOUT_MAX_TRACKED_SPANS;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.SPAN_TIMEOUT_MAX_TRACKED_SPANS_PER_TRACE;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.SPAN_TIMEOUT_MAX_TRACKED_TRACES;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.SPAN_TIMEOUT_SCAN_INTERVAL;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.SPAN_TIMEOUT_SPAN_TIMEOUT;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.SPAN_TIMEOUT_TRACE_TIMEOUT;

import java.time.Duration;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;

import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public final class SpanTimeoutExtensionConfig {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(SpanTimeoutExtensionConfig.class);

    private final boolean enabled;
    private final Duration spanTimeout;
    private final Duration traceTimeout;
    private final Duration scanInterval;
    private final int maxTrackedTraces;
    private final int maxTrackedSpansPerTrace;
    private final int maxTrackedSpans;

    SpanTimeoutExtensionConfig(ExtensionConfigReader reader) {
        this.enabled = reader.booleanValue(SPAN_TIMEOUT_ENABLED, DEFAULT_SPAN_TIMEOUT_ENABLED);
        this.spanTimeout = reader.positiveDuration(SPAN_TIMEOUT_SPAN_TIMEOUT, DEFAULT_SPAN_TIMEOUT_SPAN_TIMEOUT);
        this.traceTimeout = reader.positiveDuration(SPAN_TIMEOUT_TRACE_TIMEOUT, DEFAULT_SPAN_TIMEOUT_TRACE_TIMEOUT);
        this.scanInterval = reader.positiveDuration(SPAN_TIMEOUT_SCAN_INTERVAL, DEFAULT_SPAN_TIMEOUT_SCAN_INTERVAL);
        this.maxTrackedTraces = reader.positiveInt(SPAN_TIMEOUT_MAX_TRACKED_TRACES, DEFAULT_SPAN_TIMEOUT_MAX_TRACKED_TRACES);
        this.maxTrackedSpansPerTrace = reader.positiveInt(SPAN_TIMEOUT_MAX_TRACKED_SPANS_PER_TRACE, DEFAULT_SPAN_TIMEOUT_MAX_TRACKED_SPANS_PER_TRACE);
        this.maxTrackedSpans = reader.positiveInt(SPAN_TIMEOUT_MAX_TRACKED_SPANS, DEFAULT_SPAN_TIMEOUT_MAX_TRACKED_SPANS);

        ExtensionConfigReader.requireOrdered(spanTimeout, SPAN_TIMEOUT_SPAN_TIMEOUT, traceTimeout, SPAN_TIMEOUT_TRACE_TIMEOUT);
        warnIfGlobalBudgetBelowPerTraceCapacity();
    }

    private void warnIfGlobalBudgetBelowPerTraceCapacity() {
        if (maxTrackedSpans >= maxTrackedSpansPerTrace) {
            return;
        }

        String message = """
                Global tracked-span capacity is below per-trace capacity; effective single-trace \
                capacity is bounded by the global budget\
                """;

        log.warn(AgentEventCode.EXPORT_SPAN_TIMEOUT,
                message,
                "maxTrackedSpans", String.valueOf(maxTrackedSpans),
                "maxTrackedSpansPerTrace", String.valueOf(maxTrackedSpansPerTrace));
    }
}
