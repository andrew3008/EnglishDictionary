package space.br1440.platform.tracing.otel.javaagent.configuration;

import java.time.Duration;
import java.util.Objects;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import lombok.experimental.UtilityClass;

@UtilityClass
public final class BspExportTimeout {

    public static Duration read(ConfigProperties config) {
        Duration configured = Objects.requireNonNull(config, "config")
                .getDuration(OtelSdkPropertyNames.BSP_EXPORT_TIMEOUT);

        Duration timeout = configured == null ? OtelSdkDefaults.DEFAULT_BSP_EXPORT_TIMEOUT : configured;
        if (timeout.isNegative() || timeout.toNanos() == 0) {
            throw new IllegalArgumentException("Export timeout must be positive");
        }

        return timeout;
    }
}
