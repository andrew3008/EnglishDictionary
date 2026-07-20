package space.br1440.platform.tracing.autoconfigure.mdc;

import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционный тест распространения MDC ({@code traceId}) через
 * {@link CompletableFuture#runAsync(Runnable, java.util.concurrent.Executor)} с явной обёрткой
 * задач в {@link ContextSnapshot} на caller-стороне.
 *
 * <h3>C8 — обязательная wrapping-стратегия</h3>
 * Без {@code ContextSnapshot.captureAll().wrap(...)} вокруг задачи MDC и OTel Context
 * не пробрасываются в worker-поток. Это not-a-bug бриджа, а ожидаемое поведение Spring Boot 3.x.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(
        classes = {
                MdcPropagationCompletableFutureTest.CfTestApp.class,
                MdcPropagationCompletableFutureTest.CompletableFuturePropagationConfig.class
        },
        properties = {
                "spring.application.name=mdc-cf-propagation-test",
                "spring.autoconfigure.exclude="
                        + "space.br1440.platform.tracing.autoconfigure.TracingCoreAutoConfiguration",
                "platform.tracing.enabled=false",
                "platform.tracing.sdk.mode=DISABLED"
        })
class MdcPropagationCompletableFutureTest {

    @Autowired
    private ExecutorService wrappingExecutor;

    @Test
    void traceIdPropagatedThroughContextSnapshotWrap() throws Exception {
        String expectedTraceId = "fedcba9876543210fedcba9876543210";
        MDC.put("traceId", expectedTraceId);
        try {
            io.micrometer.context.ContextRegistry contextRegistry = new io.micrometer.context.ContextRegistry();
            contextRegistry.registerThreadLocalAccessor(
                    new io.micrometer.context.integration.Slf4jThreadLocalAccessor());
            ContextSnapshotFactory factory = ContextSnapshotFactory.builder()
                    .contextRegistry(contextRegistry).build();
            ContextSnapshot snapshot = factory.captureAll();

            AtomicReference<String> captured = new AtomicReference<>();
            CompletableFuture<Void> future = CompletableFuture.runAsync(
                    snapshot.wrap(() -> captured.set(MDC.get("traceId"))),
                    wrappingExecutor);
            future.get(5, TimeUnit.SECONDS);
            String actualTraceId = captured.get();

            assertThat(actualTraceId)
                    .as("MDC traceId в runAsync должен совпадать с traceId caller'а — обязательна "
                            + "обёртка ContextSnapshot.wrap(...) либо её эквивалент")
                    .isEqualTo(expectedTraceId);
        } finally {
            MDC.remove("traceId");
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class CfTestApp {
    }

    @TestConfiguration
    static class CompletableFuturePropagationConfig {

        @Bean(destroyMethod = "shutdown")
        public ExecutorService wrappingExecutor() {
            return Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "cf-mdc-test");
                t.setDaemon(true);
                return t;
            });
        }
    }
}
