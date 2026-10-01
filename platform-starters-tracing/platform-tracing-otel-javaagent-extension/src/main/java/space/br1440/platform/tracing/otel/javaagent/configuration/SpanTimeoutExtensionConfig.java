package space.br1440.platform.tracing.otel.javaagent.configuration;

import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_SPAN_TIMEOUT_ENABLED;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_SPAN_TIMEOUT_MAX_SPAN_AGE;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_SPAN_TIMEOUT_MAX_TRACE_AGE;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_SPAN_TIMEOUT_MAX_TRACKED_SPANS;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_SPAN_TIMEOUT_MAX_TRACKED_SPANS_PER_TRACE;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_SPAN_TIMEOUT_MAX_TRACKED_TRACES;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults.DEFAULT_SPAN_TIMEOUT_SCAN_INTERVAL;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.SPAN_TIMEOUT_ENABLED;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.SPAN_TIMEOUT_MAX_SPAN_AGE;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.SPAN_TIMEOUT_MAX_TRACE_AGE;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.SPAN_TIMEOUT_MAX_TRACKED_SPANS;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.SPAN_TIMEOUT_MAX_TRACKED_SPANS_PER_TRACE;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.SPAN_TIMEOUT_MAX_TRACKED_TRACES;
import static space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames.SPAN_TIMEOUT_SCAN_INTERVAL;

import java.time.Duration;

import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.SpanTimeoutLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;

import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(fluent = true)
public final class SpanTimeoutExtensionConfig {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(SpanTimeoutExtensionConfig.class);

    private final boolean enabled;
    private final Duration maxSpanAge;
    private final Duration maxTraceAge;
    private final Duration scanInterval;
    private final int maxTrackedTraces;
    private final int maxTrackedSpansPerTrace;
    private final int maxTrackedSpans;

    SpanTimeoutExtensionConfig(ExtensionConfigReader reader) {
        this.enabled = reader.booleanValue(SPAN_TIMEOUT_ENABLED, DEFAULT_SPAN_TIMEOUT_ENABLED);
        this.maxSpanAge = reader.positiveDuration(SPAN_TIMEOUT_MAX_SPAN_AGE, DEFAULT_SPAN_TIMEOUT_MAX_SPAN_AGE);
        this.maxTraceAge = reader.positiveDuration(SPAN_TIMEOUT_MAX_TRACE_AGE, DEFAULT_SPAN_TIMEOUT_MAX_TRACE_AGE);
        this.scanInterval = reader.positiveDuration(SPAN_TIMEOUT_SCAN_INTERVAL, DEFAULT_SPAN_TIMEOUT_SCAN_INTERVAL);
        this.maxTrackedTraces = reader.positiveInt(SPAN_TIMEOUT_MAX_TRACKED_TRACES, DEFAULT_SPAN_TIMEOUT_MAX_TRACKED_TRACES);
        this.maxTrackedSpansPerTrace = reader.positiveInt(SPAN_TIMEOUT_MAX_TRACKED_SPANS_PER_TRACE, DEFAULT_SPAN_TIMEOUT_MAX_TRACKED_SPANS_PER_TRACE);
        this.maxTrackedSpans = reader.positiveInt(SPAN_TIMEOUT_MAX_TRACKED_SPANS, DEFAULT_SPAN_TIMEOUT_MAX_TRACKED_SPANS);

        ExtensionConfigReader.requireOrdered(maxSpanAge, SPAN_TIMEOUT_MAX_SPAN_AGE, maxTraceAge, SPAN_TIMEOUT_MAX_TRACE_AGE);
        warnIfGlobalBudgetBelowPerTraceCapacity();
    }

    private void warnIfGlobalBudgetBelowPerTraceCapacity() {
        if (maxTrackedSpans >= maxTrackedSpansPerTrace) {
            return;
        }

        log.log(SpanTimeoutLogEvent.GLOBAL_TRACKED_SPAN_CAPACITY_BELOW_PER_TRACE_CAPACITY, fields -> fields
                .putLong(AgentLogField.MAX_TRACKED_SPANS, maxTrackedSpans)
                .putLong(AgentLogField.MAX_TRACKED_SPANS_PER_TRACE, maxTrackedSpansPerTrace));
    }
}
