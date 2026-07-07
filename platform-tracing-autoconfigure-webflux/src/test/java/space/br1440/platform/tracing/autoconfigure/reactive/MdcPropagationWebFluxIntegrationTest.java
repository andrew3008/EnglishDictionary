package space.br1440.platform.tracing.autoconfigure.reactive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.LazyInitializationExcludeFilter;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.reactor.ReactorAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционный тест поднятия {@link TracingReactorEagerInitConfiguration} в реактивном
 * приложении при {@code spring.main.lazy-initialization=false}.
 *
 * <h3>W2 — защита от lazy-init</h3>
 * Тест проверяет, что:
 * <ul>
 *   <li>{@link TracingReactorEagerInitConfiguration} зарегистрирован как auto-configuration в
 *       реактивном контексте;</li>
 *   <li>{@link LazyInitializationExcludeFilter}, исключающий {@link ReactorAutoConfiguration}
 *       из ленивой инициализации, поднялся как bean — это гарантирует, что
 *       {@code Hooks.enableAutomaticContextPropagation()} вызовется на старте JVM, а не при
 *       первом запросе. Иначе trace-контекст теряется в реактивных цепочках, и MDC
 *       {@code traceId} в логах остаётся пустым (известный баг Spring Boot 3.5).</li>
 * </ul>
 * Реальная проверка propagation MDC через {@code subscribeOn(Schedulers.boundedElastic)} требует
 * полной интеграции Micrometer Tracing bridge + Slf4JEventListener, что выходит за рамки
 * unit-теста; этот сценарий покрывается E2E-тестами с поднятым реактивным сервером и реальным
 * OTel Java Agent (см. {@code platform-tracing-e2e-tests}).
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(
        classes = MdcPropagationWebFluxIntegrationTest.WebFluxMdcTestApp.class,
        properties = {
                "spring.main.lazy-initialization=false",
                "spring.application.name=mdc-webflux-propagation-test",
                "spring.main.web-application-type=reactive"
        })
class MdcPropagationWebFluxIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void reactorEagerInitConfigurationIsActive() {
        assertThat(context.getBeansOfType(TracingReactorEagerInitConfiguration.class))
                .as("TracingReactorEagerInitConfiguration должен подниматься в реактивных приложениях")
                .isNotEmpty();
    }

    @Test
    void lazyInitExcludeFilterRegistered() {
        // Если filter подхвачен, ReactorAutoConfiguration инициализируется eagerly даже при
        // lazy-init=true — что и активирует Hooks.enableAutomaticContextPropagation.
        assertThat(context.getBeansOfType(LazyInitializationExcludeFilter.class))
                .as("LazyInitializationExcludeFilter для ReactorAutoConfiguration должен быть зарегистрирован")
                .isNotEmpty();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class WebFluxMdcTestApp {
    }
}
