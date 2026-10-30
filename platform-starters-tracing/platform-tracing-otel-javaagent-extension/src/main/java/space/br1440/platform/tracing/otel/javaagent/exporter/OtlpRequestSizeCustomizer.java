package space.br1440.platform.tracing.otel.javaagent.exporter;

import space.br1440.platform.tracing.otel.javaagent.configuration.BspExportTimeout;
import space.br1440.platform.tracing.otel.javaagent.configuration.OtelSdkDefaults;
import space.br1440.platform.tracing.otel.javaagent.configuration.OtelSdkPropertyNames;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporterBuilder;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporterBuilder;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import lombok.experimental.UtilityClass;

@UtilityClass
public final class OtlpRequestSizeCustomizer {

    public static SpanExporter customize(SpanExporter exporter, ConfigProperties config) {
        SpanExporter replacement;
        if (exporter instanceof OtlpGrpcSpanExporter grpc) {
            OtlpGrpcSpanExporterBuilder builder = grpc.toBuilder()
                    .setMaxRequestMessageSize(OtelSdkDefaults.MAX_OTLP_REQUEST_SIZE_BYTES);

            if (config.getBoolean(OtelSdkPropertyNames.OTLP_RETRY_DISABLED, false)) {
                builder.setRetryPolicy(null);
            }

            replacement = builder.build();
        } else if (exporter instanceof OtlpHttpSpanExporter http) {
            OtlpHttpSpanExporterBuilder builder = http.toBuilder()
                    .setMaxRequestBodySize(OtelSdkDefaults.MAX_OTLP_REQUEST_SIZE_BYTES);

            if (config.getBoolean(OtelSdkPropertyNames.OTLP_RETRY_DISABLED, false)) {
                builder.setRetryPolicy(null);
            }

            replacement = builder.build();
        } else {
            return exporter;
        }

        try {
            SpanExporter isolated = new OtlpRequestSplittingSpanExporter(
                    replacement,
                    Math.toIntExact(OtelSdkDefaults.MAX_OTLP_REQUEST_SIZE_BYTES),
                    BspExportTimeout.read(config).toNanos()
            );

            exporter.shutdown();
            return isolated;
        } catch (Throwable failure) {
            try {
                replacement.shutdown();
            } catch (Throwable cleanupFailure) {
                if (cleanupFailure != failure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }

            throw failure;
        }
    }
}
