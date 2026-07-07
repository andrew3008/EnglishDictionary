package space.br1440.platform.tracing.autoconfigure.reactive;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционный тест переноса OTel Context через Reactor pipeline.
 * <p>
 * <b>Что валидируется.</b> На uno-thread сценариях ({@code Mono.fromCallable} без явных
 * scheduler hop'ов) OTel Context, выставленный через {@code Span.makeCurrent()},
 * корректно виден внутри pipeline. Это базовая гарантия, что архитектура не ломает
 * single-thread имperative-стиль (актуально для прикладного {@code @Traced} на методах,
 * возвращающих {@code Mono}).
 * <p>
 * <b>Что НЕ валидируется в этом тесте и почему.</b> Полная семантика
 * {@code Hooks.enableAutomaticContextPropagation()} (перенос OTel Context через
 * {@code publishOn}/{@code subscribeOn} на другой scheduler) требует:
 * <ol>
 *   <li>зарегистрированного {@code ThreadLocalAccessor} для OTel Context в
 *       Micrometer {@code ContextRegistry} (поставляется
 *       {@code io.opentelemetry.context.opentelemetry-context-bridge} или
 *       Spring Boot {@code MicrometerObservationAutoConfiguration});</li>
 *   <li>активированного {@code Hooks.enableAutomaticContextPropagation()}, что делает
 *       {@code ReactorAutoConfiguration} в полном Spring Boot контексте.</li>
 * </ol>
 * В in-process окружении publishOn покрывается отдельно: {@link BridgeOtelReactorContextPropagationIntegrationTest}
 * (@Tag bridge-otel-path, bridge-otel ONLY). Production sign-off — G2-05-e2e
 * ({@code ReactorContextPropagationAgentE2ETest}, Agent path). Активация хука через eager-init
 * {@link TracingReactorEagerInitConfiguration} покрыта {@link TracingReactorEagerInitConfigurationTest}.
 */
class ReactorContextPropagationIntegrationTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private OpenTelemetry openTelemetry;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
    }

    @AfterEach
    void tearDown() {
        tracerProvider.shutdown();
    }

    @Test
    void otelContext_виден_внутри_Mono_fromCallable_исполняемого_на_том_же_потоке() {
        Span parent = openTelemetry.getTracer("test").spanBuilder("parent").startSpan();
        String expectedTraceId = parent.getSpanContext().getTraceId();
        try (Scope ignored = parent.makeCurrent()) {
            // Mono без явных scheduler hop'ов выполняется синхронно в caller-thread —
            // OTel Context там виден напрямую без необходимости в hook'е автоматической пропагации.
            // Это базовый sanity-чек: имperative-стиль внутри Mono.fromCallable работает.
            String traceIdInPipeline = Mono.fromCallable(
                            () -> Span.current().getSpanContext().getTraceId())
                    .block(Duration.ofSeconds(5));

            assertThat(traceIdInPipeline).isEqualTo(expectedTraceId);
        } finally {
            parent.end();
        }
    }

    @Test
    void schedulers_parallel_доступен_и_создаёт_worker_threads_отличные_от_caller_thread() {
        // Sanity-чек тестовой инфраструктуры: parallel-scheduler действительно переключает
        // выполнение на другой поток. Без этого assertion тест выше мог бы пройти даже
        // на полностью сломанной propagation, потому что worker == caller.
        String callerThread = Thread.currentThread().getName();
        String workerThread = Mono.fromCallable(() -> Thread.currentThread().getName())
                .subscribeOn(Schedulers.parallel())
                .block(Duration.ofSeconds(5));

        assertThat(workerThread).isNotEqualTo(callerThread);
    }
}
