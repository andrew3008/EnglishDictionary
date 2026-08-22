package space.br1440.platform.tracing.autoconfigure.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.span.SpanCategory;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformTracingMetricsTest {

    @Test
    void incrementsSpansStartedCounterWithCategoryTag() {
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
    void incrementsExceptionCounter() {
        MeterRegistry registry = new SimpleMeterRegistry();
        PlatformTracingMetrics metrics = new PlatformTracingMetrics(registry);

        metrics.incrementExceptionsRecorded();
        metrics.incrementExceptionsRecorded();

        assertThat(registry.find(PlatformTracingMetrics.EXCEPTIONS_RECORDED)
                .counter()
                .count()).isEqualTo(2.0);
    }

    @Test
    void incrementsClosedScopeCounter() {
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
    void incrementsDoubleCloseCounter() {
        MeterRegistry registry = new SimpleMeterRegistry();
        PlatformTracingMetrics metrics = new PlatformTracingMetrics(registry);

        metrics.incrementScopeDoubleClose();

        assertThat(registry.find(PlatformTracingMetrics.SCOPE_DOUBLE_CLOSE_TOTAL)
                .counter()
                .count()).isEqualTo(1.0);
    }
}
