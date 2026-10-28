package space.br1440.platform.tracing.autoconfigure.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.spec.DatabaseSpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MeteredDatabaseSpanHandleTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PlatformTracingMetrics metrics = new PlatformTracingMetrics(registry);

    @Test
    void responseStatusCode_doesNotIncrementExceptionsRecorded() {
        // given
        DatabaseSpanHandle delegate = mock(DatabaseSpanHandle.class);
        MeteredDatabaseSpanHandle handle = new MeteredDatabaseSpanHandle(delegate, metrics);

        // when
        handle.responseStatusCode("23505");

        // then
        assertThat(exceptionsRecordedCount()).isEqualTo(0.0);
        verify(delegate, times(1)).responseStatusCode("23505");
    }

    @Test
    void recordException_thenSpi_incrementsExactlyOnce() {
        // given
        DatabaseSpanHandle delegate = mock(DatabaseSpanHandle.class);
        MeteredDatabaseSpanHandle handle = new MeteredDatabaseSpanHandle(delegate, metrics);
        TracingRuntime spiDelegate = mock(TracingRuntime.class);
        MeteredTracingRuntime implementation = new MeteredTracingRuntime(spiDelegate, metrics);

        // when
        handle.recordException(new IllegalStateException("duplicate key"));
        implementation.recordException(handle, new IllegalStateException("again"));

        // then
        assertThat(exceptionsRecordedCount()).isEqualTo(1.0);
        verify(delegate, times(1)).recordException(any());
        verify(spiDelegate, times(1)).recordException(eq(handle), any());
    }

    @Test
    void startSpan_preservesDatabaseHandleType() {
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
        assertThat(database).isInstanceOf(MeteredDatabaseSpanHandle.class);
        assertThat(internal).isInstanceOf(MeteredSpanHandle.class);
        assertThat(internal).isNotInstanceOf(DatabaseSpanHandle.class);
    }

    private double exceptionsRecordedCount() {
        Counter counter = registry.find(PlatformTracingMetrics.EXCEPTIONS_RECORDED).counter();
        return (counter == null) ? 0.0 : counter.count();
    }
}
