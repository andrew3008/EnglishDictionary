package space.br1440.platform.tracing.otel.runtime.otel;

import java.util.Objects;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.otel.attributes.validation.SpanAttributeValidator;
import space.br1440.platform.tracing.otel.exception.ExceptionRecorder;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;

import io.opentelemetry.api.OpenTelemetry;
import lombok.experimental.UtilityClass;

@UtilityClass
public final class OtelTracingRuntimeFactory {

    @Nonnull
    public static TracingRuntime create(@Nonnull OpenTelemetry openTelemetry) {
        Objects.requireNonNull(openTelemetry, "openTelemetry");

        return new OtelTracingRuntime(
                openTelemetry,
                new SpanAttributeValidator(),
                ExceptionRecorder.secureDefault()
        );
    }
}
