package space.br1440.platform.tracing.autoconfigure.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.spec.DatabaseSpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MeteredTracingRuntimeTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PlatformTracingMetrics metrics = new PlatformTracingMetrics(registry);

    @Test
    void startSpan_countsCategoryAndReturnsDelegateHandle() {
        // given
        TracingRuntime delegate = mock(TracingRuntime.class);
        DatabaseSpanHandle databaseHandle = mock(DatabaseSpanHandle.class);
        SpanHandle plainHandle = mock(SpanHandle.class);
        when(delegate.startSpan(any(SpanSpec.class))).thenReturn(databaseHandle, plainHandle);
        MeteredTracingRuntime runtime = new MeteredTracingRuntime(delegate, metrics);

        // when
        SpanHandle database = runtime.startSpan(SpanSpec.builder("orders").category(SpanCategory.DATABASE).build());
        SpanHandle internal = runtime.startSpan(SpanSpec.builder("op").category(SpanCategory.INTERNAL).build());

        // then
        assertThat(database).isSameAs(databaseHandle);
        assertThat(internal).isSameAs(plainHandle);
        assertThat(startedCount(SpanCategory.DATABASE)).isEqualTo(1.0);
        assertThat(startedCount(SpanCategory.INTERNAL)).isEqualTo(1.0);
    }

    @Test
    void startedHandle_receivesExceptionAndStatusCodeDirectly() {
        // given
        TracingRuntime delegate = mock(TracingRuntime.class);
        DatabaseSpanHandle databaseHandle = mock(DatabaseSpanHandle.class);
        when(delegate.startSpan(any(SpanSpec.class))).thenReturn(databaseHandle);
        MeteredTracingRuntime runtime = new MeteredTracingRuntime(delegate, metrics);
        IllegalStateException failure = new IllegalStateException("duplicate key");

        // when
        SpanHandle handle = runtime.startSpan(SpanSpec.builder("orders").category(SpanCategory.DATABASE).build());
        ((DatabaseSpanHandle) handle).responseStatusCode("23505");
        handle.recordException(failure);
        handle.recordException(null);

        // then
        verify(databaseHandle).responseStatusCode("23505");
        verify(databaseHandle).recordException(failure);
        verify(databaseHandle).recordException(null);
        assertThat(registry.find("platform_tracing_exceptions_recorded_total").meters()).isEmpty();
    }

    private double startedCount(SpanCategory category) {
        return registry.get(PlatformTracingMetrics.SPANS_STARTED)
                .tag("category", category.value())
                .counter()
                .count();
    }
}
