package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Objects;

import io.opentelemetry.javaagent.extension.AgentListener;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

public final class ActivationAgentListener implements AgentListener {

    private static final int PLATFORM_LISTENER_ORDER = 100;

    private final BootstrapCoordinator bootstrap;

    @SuppressWarnings("unused")
    public ActivationAgentListener() {
        this(ExtensionRuntime.bootstrap());
    }

    ActivationAgentListener(BootstrapCoordinator bootstrap) {
        this.bootstrap = Objects.requireNonNull(bootstrap, "bootstrap");
    }

    @Override
    public void afterAgent(AutoConfiguredOpenTelemetrySdk autoConfiguredSdk) {
        SdkTracerProvider sdkTracerProvider = autoConfiguredSdk.getOpenTelemetrySdk().getSdkTracerProvider();
        bootstrap.activate(sdkTracerProvider);
    }

    @Override
    public int order() {
        return PLATFORM_LISTENER_ORDER;
    }
}
