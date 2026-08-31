package space.br1440.platform.tracing.e2e;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import io.opentelemetry.sdk.OpenTelemetrySdk;

@SuppressWarnings("unused")
class ApplicationOwnedOpenTelemetryInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        context.getBeanFactory().registerSingleton(
                "applicationOwnedOpenTelemetry",
                OpenTelemetrySdk.builder().build());
    }
}
