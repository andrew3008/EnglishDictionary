package space.br1440.platform.tracing.autoconfigure.mdc;

import io.micrometer.context.ContextSnapshotFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционный тест распространения MDC ({@code traceId}) в {@code @Async}-методах.
 *
 * <h3>C8 — обязательная test-конфигурация</h3>
 * Spring Boot 3.x не настраивает {@code TaskDecorator} с {@code ContextSnapshot} автоматически.
 * Без декоратора {@code @Async} запускает задачу на «голом» пуле — {@code ThreadLocal}'ы
 * (включая MDC и OTel {@code Context}) не пробрасываются. Этот тест проверяет именно
 * propagation MDC через {@code ContextSnapshot.captureAll().wrap(...)} в {@code TaskDecorator}.
 * Для устранения зависимости от поднятой MDC через Micrometer Tracing bridge ставим MDC
 * напрямую — это не меняет суть проверки (пробрасывается ли {@code ThreadLocal}-MDC через
 * {@code TaskDecorator}).
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(
        classes = {
                MdcPropagationAsyncIntegrationTest.AsyncTestApp.class,
                MdcPropagationAsyncIntegrationTest.AsyncPropagationTestConfig.class
        },
        properties = {
                "spring.application.name=mdc-async-propagation-test",
                "spring.autoconfigure.exclude="
                        + "space.br1440.platform.tracing.autoconfigure.TracingCoreAutoConfiguration",
                "platform.tracing.enabled=false",
                "platform.tracing.sdk.mode=DISABLED"
        })
class MdcPropagationAsyncIntegrationTest {

    @Autowired
    private AsyncBean asyncBean;

    @Test
    void traceIdPropagatedFromCallerToAsyncWorker() throws ExecutionException, InterruptedException, TimeoutException {
        String expectedTraceId = "abcdef0123456789abcdef0123456789";
        MDC.put("traceId", expectedTraceId);
        try {
            CompletableFuture<String> future = asyncBean.fetchMdcTraceId();
            String actualTraceId = future.get(5, TimeUnit.SECONDS);

            assertThat(actualTraceId)
                    .as("MDC traceId в @Async-потоке должен совпадать с traceId caller'а — без TaskDecorator "
                            + "с ContextSnapshot.captureAll().wrap() этот тест валится null'ом")
                    .isEqualTo(expectedTraceId);
        } finally {
            MDC.remove("traceId");
        }
    }

    @Service
    static class AsyncBean {
        @Async
        public CompletableFuture<String> fetchMdcTraceId() {
            return CompletableFuture.completedFuture(MDC.get("traceId"));
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(AsyncBean.class)
    static class AsyncTestApp {
    }

    /**
     * C8: явная регистрация {@code TaskDecorator}, оборачивающего runnable в
     * {@code ContextSnapshot} — без него ни OTel Context, ни MDC не пробрасываются в
     * worker-поток {@code @Async}.
     */
    @TestConfiguration
    @EnableAsync
    static class AsyncPropagationTestConfig implements AsyncConfigurer {
        @Override
        public Executor getAsyncExecutor() {
            // Регистрируем Slf4j MDC accessor в локальном ContextRegistry (Micrometer
            // Context Propagation). В production это делает интеграция micrometer-tracing-bridge-otel
            // через Slf4JEventListener, здесь — явно для изолированного теста.
            io.micrometer.context.ContextRegistry contextRegistry = new io.micrometer.context.ContextRegistry();
            contextRegistry.registerThreadLocalAccessor(
                    new io.micrometer.context.integration.Slf4jThreadLocalAccessor());

            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(1);
            executor.setQueueCapacity(8);
            executor.setThreadNamePrefix("async-mdc-test-");
            ContextSnapshotFactory snapshotFactory = ContextSnapshotFactory.builder()
                    .contextRegistry(contextRegistry).build();
            executor.setTaskDecorator(runnable -> snapshotFactory.captureAll().wrap(runnable));
            executor.initialize();
            return executor;
        }
    }
}
