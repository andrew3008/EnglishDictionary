package space.br1440.platform.tracing.autoconfigure.servlet;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationRegistryCustomizer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

class HttpObservationSuppressionMetricsTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ObservationAutoConfiguration.class,
                    WebMvcHttpObservationSuppressionAutoConfiguration.class));

    @Test
    void httpServerRequestsMetricAbsentWhenSuppressEnabled() {
        contextRunner
                .withPropertyValues("platform.tracing.suppression.suppress-micrometer-http-observations=true")
                .run(ctx -> {
                    ObservationRegistry registry = applyCustomizer(ctx);
                    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
                    registry.observationConfig().observationHandler(
                            new DefaultMeterObservationHandler(meterRegistry)
                    );

                    ServerRequestObservationContext serverCtx = new ServerRequestObservationContext(
                            new MockHttpServletRequest(), new MockHttpServletResponse()
                    );

                    Observation observation = Observation.createNotStarted("http.server.requests", () -> serverCtx, registry);
                    observation.start();
                    observation.stop();

                    assertThat(meterRegistry.find("http.server.requests").timer()).isNull();
                });
    }

    @SuppressWarnings("unchecked")
    private ObservationRegistry applyCustomizer(AssertableWebApplicationContext ctx) {
        ObservationRegistryCustomizer<ObservationRegistry> customizer = (ObservationRegistryCustomizer<ObservationRegistry>)
                ctx.getBean("platformMvcHttpObservationSuppressor");

        ObservationRegistry registry = ObservationRegistry.create();
        customizer.customize(registry);
        return registry;
    }
}
