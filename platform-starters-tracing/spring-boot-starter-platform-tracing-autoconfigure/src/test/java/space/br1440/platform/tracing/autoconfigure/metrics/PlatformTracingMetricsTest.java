package space.br1440.platform.tracing.autoconfigure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.span.SpanCategory;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class PlatformTracingMetricsTest {

    @Test
    void constructorPreRegistersAllCounters() {
        // given
        MeterRegistry registry = new SimpleMeterRegistry();

        // when
        new PlatformTracingMetrics(registry);

        // then
        assertThat(registry.find(PlatformTracingMetrics.SPANS_STARTED).counters())
                .hasSize(SpanCategory.values().length)
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
        assertThat(registry.find(PlatformTracingMetrics.EXCEPTIONS_RECORDED).counter()).isNotNull();
        assertThat(registry.find(PlatformTracingMetrics.SCOPE_CLOSED_TOTAL).counter()).isNotNull();
        assertThat(registry.find(PlatformTracingMetrics.SCOPE_DOUBLE_CLOSE_TOTAL).counter()).isNotNull();
    }

    @Test
    void incrementsSpansStartedCounterWithCategoryTag() {
        MeterRegistry registry = new SimpleMeterRegistry();
        PlatformTracingMetrics metrics = new PlatformTracingMetrics(registry);

        metrics.incrementSpansStarted(SpanCategory.HTTP_SERVER);
        metrics.incrementSpansStarted(SpanCategory.HTTP_SERVER);
        metrics.incrementSpansStarted(SpanCategory.DATABASE);

        assertThat(Objects.requireNonNull(registry.find(PlatformTracingMetrics.SPANS_STARTED)
                .tag("category", SpanCategory.HTTP_SERVER.value())
                .counter())
                .count()).isEqualTo(2.0);
        assertThat(Objects.requireNonNull(registry.find(PlatformTracingMetrics.SPANS_STARTED)
                .tag("category", SpanCategory.DATABASE.value())
                .counter())
                .count()).isEqualTo(1.0);
    }

    @Test
    void incrementsExceptionCounter() {
        MeterRegistry registry = new SimpleMeterRegistry();
        PlatformTracingMetrics metrics = new PlatformTracingMetrics(registry);

        metrics.incrementExceptionsRecorded();
        metrics.incrementExceptionsRecorded();

        assertThat(Objects.requireNonNull(registry.find(PlatformTracingMetrics.EXCEPTIONS_RECORDED)
                .counter())
                .count()).isEqualTo(2.0);
    }

    @Test
    void incrementsClosedScopeCounter() {
        MeterRegistry registry = new SimpleMeterRegistry();
        PlatformTracingMetrics metrics = new PlatformTracingMetrics(registry);

        metrics.incrementScopeClosed();
        metrics.incrementScopeClosed();
        metrics.incrementScopeClosed();

        assertThat(Objects.requireNonNull(registry.find(PlatformTracingMetrics.SCOPE_CLOSED_TOTAL)
                .counter())
                .count()).isEqualTo(3.0);
    }

    @Test
    void incrementsDoubleCloseCounter() {
        MeterRegistry registry = new SimpleMeterRegistry();
        PlatformTracingMetrics metrics = new PlatformTracingMetrics(registry);

        metrics.incrementScopeDoubleClose();

        assertThat(Objects.requireNonNull(registry.find(PlatformTracingMetrics.SCOPE_DOUBLE_CLOSE_TOTAL)
                .counter())
                .count()).isEqualTo(1.0);
    }
}
