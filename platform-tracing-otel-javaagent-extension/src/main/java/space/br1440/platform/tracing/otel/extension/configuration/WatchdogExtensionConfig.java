package space.br1440.platform.tracing.otel.extension.configuration;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.time.Duration;

import static space.br1440.platform.tracing.otel.extension.configuration.ExtensionDefaults.*;
import static space.br1440.platform.tracing.otel.extension.configuration.ExtensionPropertyNames.*;

@Getter
@Accessors(fluent = true)
public final class WatchdogExtensionConfig {

    private final boolean enabled;
    private final Duration spanTimeout;
    private final Duration traceTimeout;
    private final Duration scanInterval;

    WatchdogExtensionConfig(ExtensionConfigReader reader) {
        this.enabled = reader.booleanValue(WATCHDOG_ENABLED, DEFAULT_WATCHDOG_ENABLED);
        this.spanTimeout = reader.durationValue(WATCHDOG_SPAN_TIMEOUT, DEFAULT_WATCHDOG_SPAN_TIMEOUT);
        this.traceTimeout = reader.durationValue(WATCHDOG_TRACE_TIMEOUT, DEFAULT_WATCHDOG_TRACE_TIMEOUT);
        this.scanInterval = reader.durationValue(WATCHDOG_SCAN_INTERVAL, DEFAULT_WATCHDOG_SCAN_INTERVAL);
    }
}
