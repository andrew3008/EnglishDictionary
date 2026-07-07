package space.br1440.platform.tracing.autoconfigure.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.span.SpanCategory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты {@link PlatformTracingMetrics}.
 * <p>
 * До Slice 1B manual-tracing self-metrics собирал {@code MeteredPlatformTracing}; после Slice 1B
 * публичный фасадный декоратор удалён. Durable manual-tracing metrics вернутся через
 * {@code MeteredTracingImplementation} на границе {@code TracingImplementation} (Slice 2/6).
 */
class PlatformTracingMetricsTest {

    @Test
    void инкрементируетСчётчикSpansStartedСТэгомКатегории() {
        MeterRegistry registry = new SimpleMeterRegistry();
        PlatformTracingMetrics metrics = new PlatformTracingMetrics(registry);

        metrics.incrementSpansStarted(SpanCategory.HTTP_SERVER);
        metrics.incrementSpansStarted(SpanCategory.HTTP_SERVER);
        metrics.incrementSpansStarted(SpanCategory.DATABASE);

        assertThat(registry.find(PlatformTracingMetrics.SPANS_STARTED)
                .tag("category", SpanCategory.HTTP_SERVER.value())
                .counter()
                .count()).isEqualTo(2.0);
        assertThat(registry.find(PlatformTracingMetrics.SPANS_STARTED)
                .tag("category", SpanCategory.DATABASE.value())
                .counter()
                .count()).isEqualTo(1.0);
    }

    @Test
    void инкрементируетСчётчикИсключений() {
        MeterRegistry registry = new SimpleMeterRegistry();
        PlatformTracingMetrics metrics = new PlatformTracingMetrics(registry);

        metrics.incrementExceptionsRecorded();
        metrics.incrementExceptionsRecorded();

        assertThat(registry.find(PlatformTracingMetrics.EXCEPTIONS_RECORDED)
                .counter()
                .count()).isEqualTo(2.0);
    }

    @Test
    void инкрементируетСчётчикЗакрытыхScope() {
        MeterRegistry registry = new SimpleMeterRegistry();
        PlatformTracingMetrics metrics = new PlatformTracingMetrics(registry);

        metrics.incrementScopeClosed();
        metrics.incrementScopeClosed();
        metrics.incrementScopeClosed();

        assertThat(registry.find(PlatformTracingMetrics.SCOPE_CLOSED_TOTAL)
                .counter()
                .count()).isEqualTo(3.0);
    }

    @Test
    void инкрементируетСчётчикПовторныхЗакрытий() {
        MeterRegistry registry = new SimpleMeterRegistry();
        PlatformTracingMetrics metrics = new PlatformTracingMetrics(registry);

        metrics.incrementScopeDoubleClose();

        assertThat(registry.find(PlatformTracingMetrics.SCOPE_DOUBLE_CLOSE_TOTAL)
                .counter()
                .count()).isEqualTo(1.0);
    }

    @Test
    void инкрементируетСчётчикWatchdogПринудительныхЗакрытийСТэгомReason() {
        MeterRegistry registry = new SimpleMeterRegistry();
        PlatformTracingMetrics metrics = new PlatformTracingMetrics(registry);

        metrics.incrementWatchdogForcedClosed("span-timeout");
        metrics.incrementWatchdogForcedClosed("span-timeout");
        metrics.incrementWatchdogForcedClosed("trace-timeout");

        assertThat(registry.find(PlatformTracingMetrics.WATCHDOG_FORCED_CLOSED_TOTAL)
                .tag("reason", "span-timeout")
                .counter()
                .count()).isEqualTo(2.0);
        assertThat(registry.find(PlatformTracingMetrics.WATCHDOG_FORCED_CLOSED_TOTAL)
                .tag("reason", "trace-timeout")
                .counter()
                .count()).isEqualTo(1.0);
    }
}
