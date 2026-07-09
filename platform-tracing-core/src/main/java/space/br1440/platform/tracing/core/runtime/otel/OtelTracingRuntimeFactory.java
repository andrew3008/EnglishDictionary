package space.br1440.platform.tracing.core.runtime.otel;

import io.opentelemetry.api.OpenTelemetry;
import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.core.exception.ExceptionRecorder;
import space.br1440.platform.tracing.core.semconv.policy.AttributePolicy;

import java.util.Objects;

/**
 * Factory for {@link OtelTracingRuntime} instances.
 * <p>
 * Exists to keep OpenTelemetry-specific wiring out of the facade
 * ({@code DefaultPlatformTracing}) and autoconfigure beans. Callers that only need a
 * {@code TracingRuntime} over an existing {@link OpenTelemetry} instance use this factory
 * instead of constructing {@code DefaultPlatformTracing} with OTel arguments directly.
 *
 * <p><b>ADR reference:</b> ADR-platform-tracing-core-otel-api-exposure, Outcome B —
 * {@code opentelemetry-api} remains on the {@code api} configuration of
 * {@code platform-tracing-core}, but exposure through the <em>facade</em> is replaced
 * by this factory. Spring Boot autoconfigure continues to use
 * {@code OtelTracingRuntimeFactory.create(...)} before passing the result to
 * {@code new DefaultPlatformTracing(tracingRuntime)}.
 */
public final class OtelTracingRuntimeFactory {

    private OtelTracingRuntimeFactory() {
    }

    /**
     * Creates a runtime with a permissive (default) {@link AttributePolicy} and the
     * default {@link ExceptionRecorder}.
     */
    @Nonnull
    public static OtelTracingRuntime create(@Nonnull OpenTelemetry openTelemetry) {
        Objects.requireNonNull(openTelemetry, "openTelemetry");
        return new OtelTracingRuntime(openTelemetry, new AttributePolicy(),
                ExceptionRecorder.secureDefault());
    }

    /**
     * Creates a runtime with a custom {@link AttributePolicy} and the default
     * {@link ExceptionRecorder}.
     */
    @Nonnull
    public static OtelTracingRuntime create(@Nonnull OpenTelemetry openTelemetry,
                                            @Nonnull AttributePolicy policy) {
        Objects.requireNonNull(openTelemetry, "openTelemetry");
        Objects.requireNonNull(policy, "policy");
        return new OtelTracingRuntime(openTelemetry, policy,
                ExceptionRecorder.secureDefault());
    }

    /**
     * Creates a runtime with a custom {@link AttributePolicy} and a custom
     * {@link ExceptionRecorder}.
     */
    @Nonnull
    public static OtelTracingRuntime create(@Nonnull OpenTelemetry openTelemetry,
                                            @Nonnull AttributePolicy policy,
                                            @Nonnull ExceptionRecorder exceptionRecorder) {
        Objects.requireNonNull(openTelemetry, "openTelemetry");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(exceptionRecorder, "exceptionRecorder");
        return new OtelTracingRuntime(openTelemetry, policy, exceptionRecorder);
    }
}
