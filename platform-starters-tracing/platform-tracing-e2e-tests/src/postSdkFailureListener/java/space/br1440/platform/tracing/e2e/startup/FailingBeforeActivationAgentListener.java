package space.br1440.platform.tracing.e2e.startup;

import java.util.Objects;

import io.opentelemetry.javaagent.extension.AgentListener;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;

public final class FailingBeforeActivationAgentListener implements AgentListener {

    @Override
    public void afterAgent(AutoConfiguredOpenTelemetrySdk autoConfiguredSdk) {
        Objects.requireNonNull(autoConfiguredSdk.getOpenTelemetrySdk().getSdkTracerProvider(),
                "SDK tracer provider must exist before the test listener failure");
        System.out.println("SDK_CREATED_BEFORE_PLATFORM_ACTIVATION");
        throw new IllegalStateException("Test listener failed after SDK creation and before platform activation");
    }

    @Override
    public int order() {
        return 50;
    }
}
