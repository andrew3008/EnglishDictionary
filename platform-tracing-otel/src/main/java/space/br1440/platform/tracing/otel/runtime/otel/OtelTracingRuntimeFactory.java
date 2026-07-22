package space.br1440.platform.tracing.otel.runtime.otel;

import io.opentelemetry.api.OpenTelemetry;
import jakarta.annotation.Nonnull;
import lombok.experimental.UtilityClass;
import space.br1440.platform.tracing.otel.exception.ExceptionRecorder;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.semconv.policy.AttributePolicy;

import java.util.Objects;

@UtilityClass
public final class OtelTracingRuntimeFactory {

    @Nonnull
    public static TracingRuntime create(@Nonnull OpenTelemetry openTelemetry) {
        Objects.requireNonNull(openTelemetry, "openTelemetry");
        return new OtelTracingRuntime(openTelemetry, new AttributePolicy(), ExceptionRecorder.secureDefault());
    }

    @Nonnull
    public static TracingRuntime create(@Nonnull OpenTelemetry openTelemetry,
                                        @Nonnull AttributePolicy policy) {
        Objects.requireNonNull(openTelemetry, "openTelemetry");
        Objects.requireNonNull(policy, "policy");
        return new OtelTracingRuntime(openTelemetry, policy, ExceptionRecorder.secureDefault());
    }

    @Nonnull
    public static TracingRuntime create(@Nonnull OpenTelemetry openTelemetry,
                                        @Nonnull ExceptionRecorder exceptionRecorder) {
        Objects.requireNonNull(openTelemetry, "openTelemetry");
        Objects.requireNonNull(exceptionRecorder, "exceptionRecorder");
        return new OtelTracingRuntime(openTelemetry, new AttributePolicy(), exceptionRecorder);
    }

    @Nonnull
    public static TracingRuntime create(@Nonnull OpenTelemetry openTelemetry,
                                        @Nonnull AttributePolicy policy,
                                        @Nonnull ExceptionRecorder exceptionRecorder) {
        Objects.requireNonNull(openTelemetry, "openTelemetry");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(exceptionRecorder, "exceptionRecorder");
        return new OtelTracingRuntime(openTelemetry, policy, exceptionRecorder);
    }
}
