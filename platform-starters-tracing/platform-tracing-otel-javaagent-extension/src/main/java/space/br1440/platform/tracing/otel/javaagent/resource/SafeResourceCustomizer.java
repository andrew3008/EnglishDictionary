package space.br1440.platform.tracing.otel.javaagent.resource;

import java.util.Objects;

import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.diagnostics.TracingDiagnostics;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.resources.Resource;

public final class SafeResourceCustomizer {

    private final PlatformResourceCustomizer delegate;
    private final TracingDiagnostics diagnostics;

    public SafeResourceCustomizer() {
        this(new PlatformResourceCustomizer(), TracingDiagnostics.shared());
    }

    SafeResourceCustomizer(PlatformResourceCustomizer delegate, TracingDiagnostics diagnostics) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public Resource customize(Resource existing, ConfigProperties config) {
        try {
            return delegate.customize(existing, config);
        } catch (Throwable failure) {
            PlatformThrowables.propagateIfFatal(failure);
            if (isStrictMode(config)) {
                throw (failure instanceof RuntimeException runtimeFailure)
                        ? runtimeFailure
                        : new IllegalStateException("Resource customization failed", failure);
            }

            diagnostics.recordResourceFailure();
            return existing;
        }
    }

    private static boolean isStrictMode(ConfigProperties config) {
        ResourceValidationMode mode = ResourceValidationMode.fromConfig(
                config.getString(ExtensionPropertyNames.RESOURCE_VALIDATION_MODE)
        );
        return (mode == ResourceValidationMode.STRICT);
    }
}
