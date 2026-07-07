package space.br1440.platform.tracing.autoconfigure.mdc;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.TracingObservationHandler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * In-process smoke для dev/staging path с {@code micrometer-tracing-bridge-otel}.
 * <p>
 * <b>Tests bridge-otel path ONLY.</b> Production sign-off — {@code AgentMdcPlatformLoggingAgentE2ETest}
 * (G2-MDC-e2e): Agent + {@code suppress-micrometer-tracing=true} + OpenTelemetryAppender.
 * <p>
 * Проверяет регистрацию bridge-bean'ов Spring Boot Actuator при явном opt-in зависимости
 * (см. {@code docs/MIGRATION.md}).
 */
@Tag("bridge-otel-path")
class MicrometerTracingMdcBridgeSmokeTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    // OpenTelemetryAutoConfiguration поднимает Resource (service.name + sdk-attributes),
                    // от которого зависит OpenTelemetryTracingAutoConfiguration#otelSdkTracerProvider.
                    OpenTelemetryAutoConfiguration.class,
                    ObservationAutoConfiguration.class,
                    OpenTelemetryTracingAutoConfiguration.class,
                    MicrometerTracingAutoConfiguration.class))
            .withPropertyValues("spring.application.name=platform-tracing-bridge-smoke");

    @Test
    void micrometerTracerBridgeIsRegisteredFromOtelBridge() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            // Tracer от Micrometer Tracing регистрируется только при наличии bridge-otel в classpath.
            assertThat(context).hasSingleBean(Tracer.class);
        });
    }

    @Test
    void tracingObservationHandlerIsRegisteredAndConsumedByObservationRegistry() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            // TracingObservationHandler — фундамент моста: именно он создаёт OTel-span на каждое
            // Observation и публикует traceId/spanId в MDC через Slf4JEventListener.
            assertThat(context).hasSingleBean(ObservationRegistry.class);
            assertThat(context.getBeansOfType(TracingObservationHandler.class))
                    .as("Должен быть зарегистрирован хотя бы один TracingObservationHandler от bridge-otel")
                    .isNotEmpty();
        });
    }
}
