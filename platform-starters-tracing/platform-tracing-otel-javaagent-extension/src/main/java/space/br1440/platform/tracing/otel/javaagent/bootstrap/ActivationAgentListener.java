package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import java.util.Objects;

import io.opentelemetry.javaagent.extension.AgentListener;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;

public final class ActivationAgentListener implements AgentListener {

    private static final int PLATFORM_LISTENER_ORDER = 100;

    private final BootstrapCoordinator bootstrap;

    /**
     * Требуется для {@link java.util.ServiceLoader}: агент создаёт эту точку входа SPI
     * через публичный конструктор без аргументов. Делегирует получение координатора
     * classloader-локальному holder'у, чтобы обе точки входа bootstrap SPI использовали
     * ровно один общий {@link BootstrapCoordinator}.
     */
    @SuppressWarnings("unused")
    public ActivationAgentListener() {
        this(ExtensionRuntime.bootstrap());
    }

    ActivationAgentListener(BootstrapCoordinator bootstrap) {
        this.bootstrap = Objects.requireNonNull(bootstrap, "bootstrap");
    }

    @Override
    public void afterAgent(AutoConfiguredOpenTelemetrySdk autoConfiguredSdk) {
        bootstrap.activate();
    }

    @Override
    public int order() {
        return PLATFORM_LISTENER_ORDER;
    }
}
