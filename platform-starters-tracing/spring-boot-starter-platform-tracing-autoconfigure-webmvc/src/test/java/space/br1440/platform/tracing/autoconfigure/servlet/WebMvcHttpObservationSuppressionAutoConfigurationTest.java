package space.br1440.platform.tracing.autoconfigure.servlet;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationRegistryCustomizer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

class WebMvcHttpObservationSuppressionAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(
                    AutoConfigurations.of(
                            ObservationAutoConfiguration.class,
                            WebMvcHttpObservationSuppressionAutoConfiguration.class
                    )
            );

    @Test
    void suppressorBeanRegisteredOnlyWhenPropertyTrue() {
        contextRunner
                .withPropertyValues("platform.tracing.suppression.suppress-micrometer-http-observations=true")
                .run(ctx -> assertThat(ctx).hasBean("platformMvcHttpObservationSuppressor"));
    }

    @Test
    void suppressorBeanAbsentByDefault() {
        contextRunner.run(ctx -> assertThat(ctx).doesNotHaveBean("platformMvcHttpObservationSuppressor"));
    }

    @Test
    void suppressorBeanAbsentWhenPropertyFalse() {
        contextRunner
                .withPropertyValues("platform.tracing.suppression.suppress-micrometer-http-observations=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean("platformMvcHttpObservationSuppressor"));
    }

    @Test
    void suppressorBeanAbsentWhenTracingDisabledEvenIfSuppressTrue() {
        contextRunner
                .withPropertyValues(
                        "platform.tracing.enabled=false",
                        "platform.tracing.suppression.suppress-micrometer-http-observations=true")
                .run(ctx -> assertThat(ctx).doesNotHaveBean("platformMvcHttpObservationSuppressor"));
    }

    @Test
    void predicateSuppressesServletServerObservationContext() {
        contextRunner
                .withPropertyValues("platform.tracing.suppression.suppress-micrometer-http-observations=true")
                .run(ctx -> {
                    ObservationRegistry registry = applyCustomizer(ctx);

                    ServerRequestObservationContext serverCtx = new ServerRequestObservationContext(
                            new MockHttpServletRequest(), new MockHttpServletResponse()
                    );

                    Observation observation = Observation.createNotStarted("http.server.requests", () -> serverCtx, registry);
                    assertThat(observation.isNoop()).isTrue();
                });
    }

    @Test
    void predicateSuppressesServletClientObservationContext() {
        contextRunner
                .withPropertyValues("platform.tracing.suppression.suppress-micrometer-http-observations=true")
                .run(ctx -> {
                    ObservationRegistry registry = applyCustomizer(ctx);

                    ClientRequestObservationContext clientCtx = new ClientRequestObservationContext(
                            new MockClientHttpRequest()
                    );

                    Observation observation = Observation.createNotStarted("http.client.requests", () -> clientCtx, registry);
                    assertThat(observation.isNoop()).isTrue();
                });
    }

    @Test
    void predicateDoesNotSuppressUnrelatedObservationContext() {
        contextRunner
                .withPropertyValues("platform.tracing.suppression.suppress-micrometer-http-observations=true")
                .run(ctx -> {
                    ObservationRegistry registry = applyCustomizer(ctx);

                    Observation.Context unrelated = new Observation.Context();
                    Observation observation = Observation.createNotStarted("custom.business.op", () -> unrelated, registry);
                    assertThat(observation.isNoop()).isFalse();
                });
    }

    @SuppressWarnings("unchecked")
    private ObservationRegistry applyCustomizer(AssertableWebApplicationContext ctx) {
        ObservationRegistryCustomizer<ObservationRegistry> customizer = (ObservationRegistryCustomizer<ObservationRegistry>)
                ctx.getBean("platformMvcHttpObservationSuppressor");

        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(context -> true);
        customizer.customize(registry);
        return registry;
    }
}
