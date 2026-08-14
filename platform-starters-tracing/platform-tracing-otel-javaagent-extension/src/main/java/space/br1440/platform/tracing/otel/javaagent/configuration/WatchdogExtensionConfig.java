package space.br1440.platform.tracing.otel.javaagent.configuration;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.time.Duration;

@Getter
@Accessors(fluent = true)
public final class WatchdogExtensionConfig {

    private final boolean enabled;
    private final Duration spanTimeout;
    private final Duration traceTimeout;
    private final Duration scanInterval;

    WatchdogExtensionConfig(ExtensionConfigReader reader) {
        this.enabled = reader.booleanValue(
                ExtensionPropertyNames.WATCHDOG_ENABLED,
                ExtensionDefaults.DEFAULT_WATCHDOG_ENABLED
        );

        this.spanTimeout = requirePositive(
                reader.durationValue(
                        ExtensionPropertyNames.WATCHDOG_SPAN_TIMEOUT,
                        ExtensionDefaults.DEFAULT_WATCHDOG_SPAN_TIMEOUT
                ),
                ExtensionPropertyNames.WATCHDOG_SPAN_TIMEOUT
        );

        this.traceTimeout = requirePositive(
                reader.durationValue(
                        ExtensionPropertyNames.WATCHDOG_TRACE_TIMEOUT,
                        ExtensionDefaults.DEFAULT_WATCHDOG_TRACE_TIMEOUT
                ),
                ExtensionPropertyNames.WATCHDOG_TRACE_TIMEOUT);

        this.scanInterval = requirePositive(
                reader.durationValue(
                        ExtensionPropertyNames.WATCHDOG_SCAN_INTERVAL,
                        ExtensionDefaults.DEFAULT_WATCHDOG_SCAN_INTERVAL
                ),
                ExtensionPropertyNames.WATCHDOG_SCAN_INTERVAL
        );

        if (traceTimeout.compareTo(spanTimeout) < 0) {
            throw new IllegalArgumentException(
                    ExtensionPropertyNames.WATCHDOG_TRACE_TIMEOUT + " must be greater than or equal to "
                            + ExtensionPropertyNames.WATCHDOG_SPAN_TIMEOUT
            );
        }
    }

    private static Duration requirePositive(Duration value, String propertyName) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(propertyName + " must be greater than zero");
        }

        return value;
    }
}
