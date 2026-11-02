package space.br1440.platform.tracing.otel.javaagent.configuration;

import java.time.Duration;
import java.util.Objects;

import space.br1440.platform.common.util.StringUtils;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;

public final class ExportConfigurationContract {

    private static final String TRACES_EXPORTER = "otel.traces.exporter";
    private static final String TRACES_PROTOCOL = "otel.exporter.otlp.traces.protocol";
    private static final String OTLP_PROTOCOL = "otel.exporter.otlp.protocol";
    private static final String TRACES_TIMEOUT = "otel.exporter.otlp.traces.timeout";
    private static final String OTLP_TIMEOUT = "otel.exporter.otlp.timeout";
    private static final Duration DEFAULT_OTLP_TIMEOUT = Duration.ofSeconds(10);

    private ExportConfigurationContract() {
    }

    public static void requireSupported(ConfigProperties config) {
        Objects.requireNonNull(config, "config");
        String exporter = config.getString(TRACES_EXPORTER);
        if (!StringUtils.isNullOrEmpty(exporter) && !"otlp".equals(exporter)) {
            throw invalid(config, TRACES_EXPORTER, "Only the otlp traces exporter is supported");
        }

        requireDisabledSignal(config, "otel.metrics.exporter");
        requireDisabledSignal(config, "otel.logs.exporter");
        requireSupportedProtocol(config);
        requireBoundedTimeout(config);
        requireBoundedBatch(config);
    }

    private static void requireDisabledSignal(ConfigProperties config, String propertyName) {
        String exporter = config.getString(propertyName);
        if (exporter != null && !"none".equals(exporter)) {
            throw invalid(config, propertyName, "The controlled agent supports traces only; exporter must be none");
        }
    }

    private static void requireSupportedProtocol(ConfigProperties config) {
        String propertyName = (config.getString(TRACES_PROTOCOL) == null) ? OTLP_PROTOCOL : TRACES_PROTOCOL;
        String protocol = config.getString(propertyName);
        if (protocol != null && !"grpc".equals(protocol) && !"http/protobuf".equals(protocol)) {
            throw invalid(config, propertyName, "Only grpc and http/protobuf OTLP protocols are supported");
        }
    }

    private static void requireBoundedTimeout(ConfigProperties config) {
        String propertyName = TRACES_TIMEOUT;
        Duration timeout = readDuration(config, propertyName);
        if (timeout == null) {
            propertyName = OTLP_TIMEOUT;
            timeout = readDuration(config, propertyName);
        }
        timeout = timeout == null ? DEFAULT_OTLP_TIMEOUT : timeout;

        Duration bspTimeout;
        try {
            bspTimeout = BspExportTimeout.read(config);
        } catch (ConfigurationException | IllegalArgumentException | ArithmeticException failure) {
            throw new ConfigurationPropertyException(OtelSdkPropertyNames.BSP_EXPORT_TIMEOUT,
                    new ConfigurationException(
                            "BSP export timeout must be a positive duration representable in nanoseconds", failure));
        }

        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(bspTimeout) > 0) {
            throw invalid(config, propertyName, "OTLP timeout must be positive and must not exceed BSP export timeout");
        }
    }

    private static Duration readDuration(ConfigProperties config, String propertyName) {
        try {
            return config.getDuration(propertyName);
        } catch (ConfigurationException failure) {
            throw new ConfigurationPropertyException(propertyName, failure);
        }
    }

    private static void requireBoundedBatch(ConfigProperties config) {
        int queueSize = positiveInt(
                config,
                OtelSdkPropertyNames.BSP_MAX_QUEUE_SIZE,
                OtelSdkDefaults.DEFAULT_BSP_MAX_QUEUE_SIZE
        );

        int batchSize = positiveInt(
                config,
                OtelSdkPropertyNames.BSP_MAX_EXPORT_BATCH_SIZE,
                OtelSdkDefaults.DEFAULT_BSP_MAX_EXPORT_BATCH_SIZE
        );

        if (batchSize > queueSize) {
            throw invalid(
                    config,
                    OtelSdkPropertyNames.BSP_MAX_EXPORT_BATCH_SIZE,
                    "BSP export batch size must not exceed queue size"
            );
        }
    }

    private static int positiveInt(ConfigProperties config, String propertyName, int defaultValue) {
        Integer configured;
        try {
            configured = config.getInt(propertyName);
        } catch (ConfigurationException failure) {
            throw new ConfigurationPropertyException(propertyName, failure);
        }

        int value = configured == null ? defaultValue : configured;
        if (value <= 0) {
            throw invalid(config, propertyName, "BSP size must be positive");
        }

        return value;
    }

    private static ConfigurationPropertyException invalid(ConfigProperties config, String propertyName, String message) {
        return new ConfigurationPropertyException(
                propertyName,
                new ConfigurationException(message + ": " + propertyName + "=" + config.getString(propertyName))
        );
    }
}
