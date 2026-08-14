package space.br1440.platform.tracing.autoconfigure.servlet;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationRegistryCustomizer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты Servlet-suppressor'а: предикат подавляет именно Servlet-flavor HTTP-обсервации,
 * не трогая прочие observation-контексты.
 *
 * <h3>§ 1.3.1 — instanceof-test discipline (W9)</h3>
 * Для проверки {@code ObservationPredicate} используется <b>реальный</b> экземпляр контекста
 * через конструктор {@code new ServerRequestObservationContext(...)} / {@code new ClientRequestObservationContext()}
 * — не Mockito-mock. {@code ObservationPredicate} сравнивает контекст по {@code instanceof}
 * runtime-класса; Mockito subclass-proxy сейчас проходит {@code instanceof} (ByteBuddy подкласс),
 * но это implementation detail. Реальный экземпляр гарантирует корректную проверку.
 * <p>
 * Аргументы конструкторов (HTTP-request/response) — это Spring-предоставленные fakes
 * ({@code MockHttpServletRequest}/{@code Response}), не Mockito-proxy.
 */
class WebMvcSuppressMicrometerTracingAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ObservationAutoConfiguration.class,
                    WebMvcSuppressMicrometerTracingAutoConfiguration.class));

    @Test
    void suppressorBeanRegisteredOnlyWhenPropertyTrue() {
        contextRunner
                .withPropertyValues("platform.tracing.suppression.suppress-micrometer-tracing=true")
                .run(ctx -> assertThat(ctx).hasBean("platformMvcHttpObservationSuppressor"));
    }

    @Test
    void suppressorBeanAbsentByDefault() {
        contextRunner.run(ctx -> assertThat(ctx).doesNotHaveBean("platformMvcHttpObservationSuppressor"));
    }

    @Test
    void suppressorBeanAbsentWhenPropertyFalse() {
        contextRunner
                .withPropertyValues("platform.tracing.suppression.suppress-micrometer-tracing=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean("platformMvcHttpObservationSuppressor"));
    }

    @Test
    void predicateSuppressesServletServerObservationContext() {
        contextRunner
                .withPropertyValues("platform.tracing.suppression.suppress-micrometer-tracing=true")
                .run(ctx -> {
                    ObservationRegistry registry = applyCustomizer(ctx);

                    // instanceof requires runtime class — реальный экземпляр контекста, не mock.
                    ServerRequestObservationContext serverCtx = new ServerRequestObservationContext(
                            new MockHttpServletRequest(), new MockHttpServletResponse());

                    Observation observation = Observation.createNotStarted("http.server.requests", () -> serverCtx, registry);
                    assertThat(observation.isNoop()).isTrue();
                });
    }

    @Test
    void predicateSuppressesServletClientObservationContext() {
        contextRunner
                .withPropertyValues("platform.tracing.suppression.suppress-micrometer-tracing=true")
                .run(ctx -> {
                    ObservationRegistry registry = applyCustomizer(ctx);

                    // Реальный runtime-класс контекста (W9). MockClientHttpRequest — Spring fake.
                    ClientRequestObservationContext clientCtx = new ClientRequestObservationContext(
                            new MockClientHttpRequest());
                    Observation observation = Observation.createNotStarted("http.client.requests", () -> clientCtx, registry);
                    assertThat(observation.isNoop()).isTrue();
                });
    }

    /**
     * Negative-кейс (W9): предикат не подавляет {@code Observation.Context} другого типа —
     * предотвращает риск over-suppression, когда предикат случайно ловит общий контекст.
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

    /**
     * Достаёт зарегистрированный бин-customizer и применяет его к свежему {@link ObservationRegistry}
     * для проверки эффекта {@code observationPredicate}.
     */
    @SuppressWarnings("unchecked")
    private ObservationRegistry applyCustomizer(
            org.springframework.boot.test.context.assertj.AssertableWebApplicationContext ctx) {
        ObservationRegistryCustomizer<ObservationRegistry> customizer = (ObservationRegistryCustomizer<ObservationRegistry>)
                ctx.getBean("platformMvcHttpObservationSuppressor");
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
