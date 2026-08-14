package space.br1440.platform.tracing.autoconfigure.reactive;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationRegistryCustomizer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientRequestObservationContext;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты Reactive-suppressor'а: предикат подавляет именно реактивные HTTP-обсервации
 * (пакет {@code http.server.reactive.observation} и {@code web.reactive.function.client}),
 * не трогая прочие observation-контексты.
 *
 * <h3>§ 1.3.1 — instanceof-test discipline (W9)</h3>
 * Используются реальные экземпляры контекстов через конструкторы; mock-классы не применяются —
 * {@code instanceof} проверяет runtime-класс, а Mockito subclass-proxy совпадение реализуется
 * через ByteBuddy, что является implementation detail. Реальный экземпляр гарантирует
 * корректную работу проверки в production.
 */
class WebFluxSuppressMicrometerTracingAutoConfigurationTest {

    private final ReactiveWebApplicationContextRunner contextRunner = new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ObservationAutoConfiguration.class,
                    WebFluxSuppressMicrometerTracingAutoConfiguration.class));

    @Test
    void suppressorBeanRegisteredOnlyWhenPropertyTrue() {
        contextRunner
                .withPropertyValues("platform.tracing.suppression.suppress-micrometer-tracing=true")
                .run(ctx -> assertThat(ctx).hasBean("platformWebFluxHttpObservationSuppressor"));
    }

    @Test
    void suppressorBeanAbsentByDefault() {
        contextRunner.run(ctx -> assertThat(ctx).doesNotHaveBean("platformWebFluxHttpObservationSuppressor"));
    }

    @Test
    void predicateSuppressesReactiveServerObservationContext() {
        contextRunner
                .withPropertyValues("platform.tracing.suppression.suppress-micrometer-tracing=true")
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
                .withPropertyValues("platform.tracing.suppression.suppress-micrometer-tracing=true")
                .run(ctx -> {
                    ObservationRegistry registry = applyCustomizer(ctx);

                    ClientRequestObservationContext clientCtx = new ClientRequestObservationContext(
                            ClientRequest.create(org.springframework.http.HttpMethod.GET,
                                    java.net.URI.create("http://example.com/test")));

                    Observation observation = Observation.createNotStarted("http.client.requests", () -> clientCtx, registry);
                    assertThat(observation.isNoop()).isTrue();
                });
    }

    /**
     * Negative-кейс (W9): прочие observation-контексты не подавляются — защита от over-suppression.
     */
    @Test
    void predicateDoesNotSuppressUnrelatedObservationContext() {
        contextRunner
                .withPropertyValues("platform.tracing.suppression.suppress-micrometer-tracing=true")
                .run(ctx -> {
                    ObservationRegistry registry = applyCustomizer(ctx);

                    Observation.Context unrelated = new Observation.Context();
                    Observation observation = Observation.createNotStarted("custom.business.op", () -> unrelated, registry);
                    assertThat(observation.isNoop()).isFalse();
                });
    }

    @SuppressWarnings("unchecked")
    private ObservationRegistry applyCustomizer(
            org.springframework.boot.test.context.assertj.AssertableReactiveWebApplicationContext ctx) {
        ObservationRegistryCustomizer<ObservationRegistry> customizer = (ObservationRegistryCustomizer<ObservationRegistry>)
                ctx.getBean("platformWebFluxHttpObservationSuppressor");
        ObservationRegistry registry = ObservationRegistry.create();
        // Регистрируем no-op handler с supportsContext=true: без хотя бы одного handler'а
        // Observation.createNotStarted всегда возвращает NOOP — и проверка предиката потеряет смысл.
        registry.observationConfig().observationHandler(new io.micrometer.observation.ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });
        customizer.customize(registry);
        return registry;
    }
}
