package space.br1440.platform.tracing.otel.javaagent.configuration;

import java.time.Duration;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
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
        this.enabled = reader.booleanValue(
                ExtensionPropertyNames.SPAN_TIMEOUT_ENABLED,
                ExtensionDefaults.DEFAULT_SPAN_TIMEOUT_ENABLED
        );

        this.spanTimeout = requirePositive(
                reader.durationValue(
                        ExtensionPropertyNames.SPAN_TIMEOUT_SPAN_TIMEOUT,
                        ExtensionDefaults.DEFAULT_SPAN_TIMEOUT_SPAN_TIMEOUT
                ),
                ExtensionPropertyNames.SPAN_TIMEOUT_SPAN_TIMEOUT
        );

        this.traceTimeout = requirePositive(
                reader.durationValue(
                        ExtensionPropertyNames.SPAN_TIMEOUT_TRACE_TIMEOUT,
                        ExtensionDefaults.DEFAULT_SPAN_TIMEOUT_TRACE_TIMEOUT
                ),
                ExtensionPropertyNames.SPAN_TIMEOUT_TRACE_TIMEOUT);

        this.scanInterval = requirePositive(
                reader.durationValue(
                        ExtensionPropertyNames.SPAN_TIMEOUT_SCAN_INTERVAL,
                        ExtensionDefaults.DEFAULT_SPAN_TIMEOUT_SCAN_INTERVAL
                ),
                ExtensionPropertyNames.SPAN_TIMEOUT_SCAN_INTERVAL
        );

        this.maxTrackedTraces = requirePositive(
                reader.intValue(
                        ExtensionPropertyNames.SPAN_TIMEOUT_MAX_TRACKED_TRACES,
                        ExtensionDefaults.DEFAULT_SPAN_TIMEOUT_MAX_TRACKED_TRACES
                ),
                ExtensionPropertyNames.SPAN_TIMEOUT_MAX_TRACKED_TRACES
        );

        this.maxTrackedSpansPerTrace = requirePositive(
                reader.intValue(
                        ExtensionPropertyNames.SPAN_TIMEOUT_MAX_TRACKED_SPANS_PER_TRACE,
                        ExtensionDefaults.DEFAULT_SPAN_TIMEOUT_MAX_TRACKED_SPANS_PER_TRACE
                ),
                ExtensionPropertyNames.SPAN_TIMEOUT_MAX_TRACKED_SPANS_PER_TRACE
        );

        this.maxTrackedSpans = requirePositive(
                reader.intValue(
                        ExtensionPropertyNames.SPAN_TIMEOUT_MAX_TRACKED_SPANS,
                        ExtensionDefaults.DEFAULT_SPAN_TIMEOUT_MAX_TRACKED_SPANS
                ),
                ExtensionPropertyNames.SPAN_TIMEOUT_MAX_TRACKED_SPANS
        );

        if (traceTimeout.compareTo(spanTimeout) < 0) {
            throw new IllegalArgumentException(
                    ExtensionPropertyNames.SPAN_TIMEOUT_TRACE_TIMEOUT + " must be greater than or equal to "
                            + ExtensionPropertyNames.SPAN_TIMEOUT_SPAN_TIMEOUT
            );
        }

        if (maxTrackedSpans < maxTrackedSpansPerTrace) {
            log.warn(AgentEventCodes.EXPORT_WATCHDOG,
                    "Global tracked-span capacity is below per-trace capacity; effective single-trace "
                            + "capacity is bounded by the global budget",
                    "maxTrackedSpans", String.valueOf(maxTrackedSpans),
                    "maxTrackedSpansPerTrace", String.valueOf(maxTrackedSpansPerTrace));
        }
    }

    private static Duration requirePositive(Duration value, String propertyName) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(propertyName + " must be greater than zero");
        }

        return value;
    }

    private static int requirePositive(int value, String propertyName) {
        if (value <= 0) {
            throw new IllegalArgumentException(propertyName + " must be greater than zero");
        }

        return value;
    }
}
