package space.br1440.platform.tracing.autoconfigure.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationRegistryCustomizer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableReactiveWebApplicationContext;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientRequestObservationContext;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

class WebFluxHttpObservationSuppressionAutoConfigurationTest {

    private final ReactiveWebApplicationContextRunner contextRunner = new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ObservationAutoConfiguration.class,
                    WebFluxHttpObservationSuppressionAutoConfiguration.class));

    @Test
    void suppressorBeanRegisteredOnlyWhenPropertyTrue() {
        contextRunner
                .withPropertyValues("platform.tracing.suppression.suppress-micrometer-http-observations=true")
                .run(ctx -> assertThat(ctx).hasBean("platformWebFluxHttpObservationSuppressor"));
    }

    @Test
    void suppressorBeanAbsentByDefault() {
        contextRunner.run(ctx -> assertThat(ctx).doesNotHaveBean("platformWebFluxHttpObservationSuppressor"));
    }

    @Test
    void suppressorBeanAbsentWhenTracingDisabledEvenIfSuppressTrue() {
        contextRunner
                .withPropertyValues(
                        "platform.tracing.enabled=false",
                        "platform.tracing.suppression.suppress-micrometer-http-observations=true")
                .run(ctx -> assertThat(ctx).doesNotHaveBean("platformWebFluxHttpObservationSuppressor"));
    }

    @Test
    void predicateSuppressesReactiveServerObservationContext() {
        contextRunner
                .withPropertyValues("platform.tracing.suppression.suppress-micrometer-http-observations=true")
                .run(ctx -> {
                    ObservationRegistry registry = applyCustomizer(ctx);

                    ServerRequestObservationContext serverCtx = new ServerRequestObservationContext(
                            MockServerHttpRequest.get("/test").build(),
                            new MockServerHttpResponse(),
                            new HashMap<>());

                    Observation observation = Observation.createNotStarted("http.server.requests", () -> serverCtx, registry);
                    assertThat(observation.isNoop()).isTrue();
                });
    }

    @Test
    void predicateSuppressesReactiveClientObservationContext() {
        contextRunner
                .withPropertyValues("platform.tracing.suppression.suppress-micrometer-http-observations=true")
                .run(ctx -> {
                    ObservationRegistry registry = applyCustomizer(ctx);

                    ClientRequestObservationContext clientCtx = new ClientRequestObservationContext(
                            ClientRequest.create(org.springframework.http.HttpMethod.GET,
                                    java.net.URI.create("http://example.com/test")));

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
    private ObservationRegistry applyCustomizer(AssertableReactiveWebApplicationContext ctx) {
        ObservationRegistryCustomizer<ObservationRegistry> customizer = (ObservationRegistryCustomizer<ObservationRegistry>)
                ctx.getBean("platformWebFluxHttpObservationSuppressor");

        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(context -> true);
        customizer.customize(registry);
        return registry;
    }
}
