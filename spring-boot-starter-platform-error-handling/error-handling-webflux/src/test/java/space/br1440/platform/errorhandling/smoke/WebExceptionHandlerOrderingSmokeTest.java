package space.br1440.platform.errorhandling.smoke;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;
import space.br1440.platform.errorhandling.core.handler.ReactiveGlobalExceptionHandler;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = WebExceptionHandlerOrderingSmokeTest.SmokeApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.application.name=webflux-ordering-smoke",
                "platform.error-handling.enabled=true",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration"
        })
@DisplayName("WebFlux H0 — WebExceptionHandler ordering audit (production-contract)")
class WebExceptionHandlerOrderingSmokeTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @DisplayName("ReactiveGlobalExceptionHandler зарегистрирован в Spring-контексте")
    void handlerBeanIsRegistered() {
        // when
        Map<String, ReactiveGlobalExceptionHandler> beans = applicationContext.getBeansOfType(ReactiveGlobalExceptionHandler.class);

        // then
        assertThat(beans)
                .as("ReactiveGlobalExceptionHandler должен быть зарегистрирован автоконфигурацией")
                .hasSize(1);
    }

    @Test
    @DisplayName("В цепочке WebExceptionHandler наш handler идёт раньше Boot DefaultErrorWebExceptionHandler")
    void ourHandlerComesBeforeBootDefault() {
        // given
        Map<String, WebExceptionHandler> handlersByName = applicationContext.getBeansOfType(WebExceptionHandler.class);

        // then
        assertThat(handlersByName.values())
                .as("Хотя бы один WebExceptionHandler должен быть зарегистрирован")
                .isNotEmpty();

        // when
        boolean hasOurs = handlersByName.values().stream()
                .anyMatch(ReactiveGlobalExceptionHandler.class::isInstance);

        // then
        assertThat(hasOurs)
                .as("ReactiveGlobalExceptionHandler должен быть среди WebExceptionHandler-бинов")
                .isTrue();

        // when
        List<WebExceptionHandler> sorted = handlersByName.values().stream()
                .sorted(AnnotationAwareOrderComparator.INSTANCE)
                .toList();

        int ourIndex = indexOf(sorted, ReactiveGlobalExceptionHandler.class);
        int bootIndex = indexOfByClassName(sorted,
                "org.springframework.boot.autoconfigure.web.reactive.error.DefaultErrorWebExceptionHandler");

        // then
        assertThat(ourIndex)
                .as("ReactiveGlobalExceptionHandler должен присутствовать в отсортированной цепочке")
                .isGreaterThanOrEqualTo(0);
        assertThat(bootIndex)
                .as("DefaultErrorWebExceptionHandler ожидается в WebFlux-контексте — sanity check")
                .isGreaterThanOrEqualTo(0);
        assertThat(ourIndex)
                .as("ReactiveGlobalExceptionHandler должен идти РАНЬШЕ Boot DefaultErrorWebExceptionHandler "
                        + "(меньший индекс = более ранний приоритет в OrderComparator)")
                .isLessThan(bootIndex);
    }

    @Test
    @DisplayName("Sanity-check @Order значения: наш = HIGHEST_PRECEDENCE и строго меньше Boot-default")
    void ourOrderValueIsHighestPrecedenceAndBeatsBootDefault() {
        // given
        ReactiveGlobalExceptionHandler ours =
                applicationContext.getBean(ReactiveGlobalExceptionHandler.class);

        Map<String, WebExceptionHandler> handlers =
                applicationContext.getBeansOfType(WebExceptionHandler.class);
        WebExceptionHandler bootDefault = handlers.values().stream()
                .filter(h -> h.getClass().getName().endsWith("DefaultErrorWebExceptionHandler"))
                .findFirst()
                .orElse(null);

        // when
        Integer oursOrder = OrderUtils.getOrder(ours.getClass());
        int bootOrderValue = (bootDefault instanceof Ordered ordered)
                ? ordered.getOrder()
                : Integer.MAX_VALUE;

        // then
        assertThat(oursOrder)
                .as("ReactiveGlobalExceptionHandler должен иметь @Order = Ordered.HIGHEST_PRECEDENCE")
                .isNotNull()
                .isEqualTo(Ordered.HIGHEST_PRECEDENCE);

        assertThat(bootDefault)
                .as("Boot DefaultErrorWebExceptionHandler должен присутствовать (sanity)")
                .isNotNull();

        assertThat(oursOrder)
                .as("Order нашего handler'а должен быть строго меньше Boot DefaultErrorWebExceptionHandler "
                        + "(было: ours=%d, boot=%d)", oursOrder, bootOrderValue)
                .isLessThan(bootOrderValue);
    }

    @Test
    @DisplayName("Behavioural proof: исключение обслуживается нашим handler'ом (problem+json + X-Correlation-Id)")
    void behaviouralProof_responseHandledByUs() {
        // when
        var response = webTestClient.get().uri("/h0/boom")
                .exchange();

        // then
        response.expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
                .expectHeader().contentTypeCompatibleWith(
                        org.springframework.http.MediaType.parseMediaType("application/problem+json"))
                .expectHeader().exists("X-Correlation-Id");
    }

    private static int indexOf(@NonNull List<?> list,
                               @NonNull Class<?> type) {
        for (int i = 0; i < list.size(); i++) {
            if (type.isInstance(list.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfByClassName(@NonNull List<?> list,
                                          @NonNull String fqcn) {
        for (int i = 0; i < list.size(); i++) {
            if (fqcn.equals(list.get(i).getClass().getName())) {
                return i;
            }
        }
        return -1;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            ReactiveSecurityAutoConfiguration.class,
            ReactiveUserDetailsServiceAutoConfiguration.class
    })
    static class SmokeApp {

        @RestController
        @RequestMapping("/h0")
        static class H0Controller {

            @GetMapping("/boom")
            Mono<String> boom() {
                return Mono.error(new RuntimeException("h0 ordering check"));
            }
        }
    }
}
