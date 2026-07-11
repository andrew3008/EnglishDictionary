package space.br1440.platform.tracing.autoconfigure.errorhandling;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.slf4j.MDC;
import space.br1440.platform.tracing.api.context.RequestTraceContextSnapshot;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты {@link RequestTraceContextSnapshotSupplier}.
 * <p>
 * Покрытие:
 * <ul>
 *   <li>активный валидный span → traceId/spanId непустые;</li>
 *   <li>отсутствие активного span'а → traceId/spanId равны null, correlationId из MDC;</li>
 *   <li>повреждённый OTel-контекст ({@code Span.current()} бросает RuntimeException) →
 *       graceful fallback без проброса исключения наружу.</li>
 * </ul>
 * <p>
 * Все взаимодействия с {@link MDC} и статическими методами {@link Span} оборачиваются
 * соответственно в try/finally и try-with-resources, чтобы исключить кросс-влияние тестов
 * при параллельном запуске Gradle Test Worker'ами.
 */
class RequestTraceContextSnapshotSupplierTest {

    private RequestTraceContextSnapshotSupplier supplier;
    private OpenTelemetrySdk sdk;

    @BeforeEach
    void setUp() {
        supplier = new RequestTraceContextSnapshotSupplier();
        // Полноценный SDK нужен только для теста с активным валидным span'ом.
        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().build())
                .build();
    }

    @AfterEach
    void tearDown() {
        // MDC живёт в ThreadLocal: чистим, чтобы тесты не влияли друг на друга.
        MDC.clear();
        if (sdk != null) {
            sdk.close();
        }
    }

    @Test
    void возвращает_traceId_и_spanId_при_активном_span() {
        Tracer tracer = sdk.getTracer("test");
        Span span = tracer.spanBuilder("test-op").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            MDC.put(TracingMdcKeys.CORRELATION_ID, "req-42");

            RequestTraceContextSnapshot ctx = supplier.get();

            assertThat(ctx.correlationId()).isEqualTo("req-42");
            assertThat(ctx.traceId()).isEqualTo(span.getSpanContext().getTraceId());
            assertThat(ctx.spanId()).isEqualTo(span.getSpanContext().getSpanId());
        } finally {
            span.end();
        }
    }

    @Test
    void traceId_и_spanId_равны_null_вне_активного_span() {
        // Без активного scope'а Span.current() возвращает invalid span с пустыми идентификаторами.
        MDC.put(TracingMdcKeys.CORRELATION_ID, "req-without-span");

        RequestTraceContextSnapshot ctx = supplier.get();

        assertThat(ctx.correlationId()).isEqualTo("req-without-span");
        assertThat(ctx.traceId()).isNull();
        assertThat(ctx.spanId()).isNull();
    }

    @Test
    void correlationId_равен_null_если_MDC_пуст() {
        RequestTraceContextSnapshot ctx = supplier.get();

        assertThat(ctx.correlationId()).isNull();
        assertThat(ctx.traceId()).isNull();
        assertThat(ctx.spanId()).isNull();
    }

    @Test
    void не_бросает_исключение_при_сбое_OpenTelemetry_контекста() {
        // Воспроизводим ситуацию повреждённого ContextStorage: Span.current() бросает RuntimeException.
        // try-with-resources обязателен — MockedStatic регистрируется для текущего потока,
        // и без явного закрытия параллельный запуск других тестов в этом же worker'е получит
        // мок вместо реального Span.current().
        MDC.put(TracingMdcKeys.CORRELATION_ID, "req-broken-storage");
        try (MockedStatic<Span> mocked = Mockito.mockStatic(Span.class)) {
            mocked.when(Span::current).thenThrow(new IllegalStateException("simulated context storage failure"));

            RequestTraceContextSnapshot ctx = supplier.get();

            // Контракт: исключения из tracing никогда не пробрасываются — fallback на null/MDC.
            assertThat(ctx.correlationId()).isEqualTo("req-broken-storage");
            assertThat(ctx.traceId()).isNull();
            assertThat(ctx.spanId()).isNull();
        }
    }

    @Test
    void возвращает_null_traceId_при_невалидном_SpanContext() {
        // Mock'аем Span.current() так, чтобы getSpanContext() вернул invalid context —
        // эмулируем сценарий, когда ContextStorage возвращает no-op span без trace.
        SpanContext invalid = SpanContext.create(
                "00000000000000000000000000000000",
                "0000000000000000",
                TraceFlags.getDefault(),
                TraceState.getDefault());
        Span fakeSpan = Mockito.mock(Span.class);
        Mockito.when(fakeSpan.getSpanContext()).thenReturn(invalid);

        try (MockedStatic<Span> mocked = Mockito.mockStatic(Span.class)) {
            mocked.when(Span::current).thenReturn(fakeSpan);

            RequestTraceContextSnapshot ctx = supplier.get();

            assertThat(ctx.traceId()).isNull();
            assertThat(ctx.spanId()).isNull();
        }
    }
}
