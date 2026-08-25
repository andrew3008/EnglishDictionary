package space.br1440.platform.tracing.autoconfigure.mdc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.TracingObservationHandler;

@Tag("bridge-otel-path")
class MicrometerTracingMdcBridgeSmokeTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(
                    AutoConfigurations.of(
                            OpenTelemetryAutoConfiguration.class,
                            ObservationAutoConfiguration.class,
                            OpenTelemetryTracingAutoConfiguration.class,
                            MicrometerTracingAutoConfiguration.class
                    )
            )
            .withPropertyValues("spring.application.name=platform-tracing-bridge-smoke");

    @Test
    void micrometerTracerBridgeIsRegisteredFromOtelBridge() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(Tracer.class);
        });
    }

    @Test
    void tracingObservationHandlerIsRegisteredAndConsumedByObservationRegistry() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ObservationRegistry.class);
            assertThat(context.getBeansOfType(TracingObservationHandler.class))
                    .as("Должен быть зарегистрирован хотя бы один TracingObservationHandler от bridge-otel")
                    .isNotEmpty();
        });
    }
}
