package space.br1440.platform.tracing.autoconfigure.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.core.runtime.DelegatingTracingRuntime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class MeteredSpanHandleDoubleCountTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PlatformTracingMetrics metrics = new PlatformTracingMetrics(registry);

    @Test
    void recordException_throughSpanHandle_incrementsExactlyOnce() {
        SpanHandle delegate = mock(SpanHandle.class);
        MeteredSpanHandle handle = new MeteredSpanHandle(delegate, metrics);

        handle.recordException(new RuntimeException("boom"));

        assertThat(exceptionsRecordedCount()).isEqualTo(1.0);
        verify(delegate, times(1)).recordException(any());
    }

    private double exceptionsRecordedCount() {
        var counter = registry.find(PlatformTracingMetrics.EXCEPTIONS_RECORDED).counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    void recordException_viaSpiTwoArgPath_doesNotDoubleCount() {
        SpanHandle delegate = mock(SpanHandle.class);
        MeteredSpanHandle handle = new MeteredSpanHandle(delegate, metrics);
        DelegatingTracingRuntime spiDelegate = mock(DelegatingTracingRuntime.class);
        MeteredTracingRuntime implementation = new MeteredTracingRuntime(spiDelegate, metrics);

        handle.recordException(new RuntimeException("boom"));
        implementation.recordException(handle, new RuntimeException("boom-again"));

        assertThat(exceptionsRecordedCount()).isEqualTo(1.0);
        verify(spiDelegate, times(1)).recordException(eq(handle), any());
    }

    @Test
    void twoArgRecordExceptionWithPlainHandle_doesNotIncrement() {
        SpanHandle plainHandle = mock(SpanHandle.class);
        DelegatingTracingRuntime spiDelegate = mock(DelegatingTracingRuntime.class);
        MeteredTracingRuntime implementation = new MeteredTracingRuntime(spiDelegate, metrics);

        implementation.recordException(plainHandle, new RuntimeException("spi-only"));

        assertThat(exceptionsRecordedCount()).isEqualTo(0.0);
        verify(spiDelegate, times(1)).recordException(eq(plainHandle), any(RuntimeException.class));
        verify(plainHandle, times(0)).recordException(any());
    }

    @Test
    void twoArgRecordExceptionWithNullThrowable_doesNotIncrement() {
        SpanHandle plainHandle = mock(SpanHandle.class);
        DelegatingTracingRuntime spiDelegate = mock(DelegatingTracingRuntime.class);
        MeteredTracingRuntime implementation = new MeteredTracingRuntime(spiDelegate, metrics);

        implementation.recordException(plainHandle, null);

        assertThat(exceptionsRecordedCount()).isEqualTo(0.0);
        verify(spiDelegate, times(1)).recordException(eq(plainHandle), isNull());
    }

    @Test
    void recordException_nullThrowable_doesNotIncrement() {
        SpanHandle delegate = mock(SpanHandle.class);
        MeteredSpanHandle handle = new MeteredSpanHandle(delegate, metrics);

        handle.recordException(null);

        assertThat(registry.find(PlatformTracingMetrics.EXCEPTIONS_RECORDED).counter()).isNull();
        verify(delegate, times(1)).recordException(isNull());
    }
}
