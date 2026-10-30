package space.br1440.platform.tracing.otel.javaagent.configuration;

import java.time.Duration;
import java.util.Objects;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

public final class BspExportTimeout {

    private BspExportTimeout() {
    }

    public static Duration read(ConfigProperties config) {
        Duration configured = Objects.requireNonNull(config, "config")
                .getDuration(OtelSdkPropertyNames.BSP_EXPORT_TIMEOUT);

        Duration timeout = configured == null ? OtelSdkDefaults.DEFAULT_BSP_EXPORT_TIMEOUT : configured;
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Export timeout must be positive");
        }

        timeout.toNanos();
        return timeout;
    }
}
