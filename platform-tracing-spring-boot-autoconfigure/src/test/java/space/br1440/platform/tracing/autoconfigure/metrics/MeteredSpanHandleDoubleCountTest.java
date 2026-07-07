package space.br1440.platform.tracing.autoconfigure.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.core.impl.DelegatingTracingImplementation;

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

        assertThat(registry.find(PlatformTracingMetrics.EXCEPTIONS_RECORDED).counter().count()).isEqualTo(1.0);
        verify(delegate, times(1)).recordException(any());
    }

    @Test
    void recordException_viaSpiTwoArgPath_doesNotDoubleCount() {
        SpanHandle delegate = mock(SpanHandle.class);
        MeteredSpanHandle handle = new MeteredSpanHandle(delegate, metrics);
        DelegatingTracingImplementation spiDelegate = mock(DelegatingTracingImplementation.class);
        MeteredTracingImplementation implementation = new MeteredTracingImplementation(spiDelegate, metrics);

        handle.recordException(new RuntimeException("boom"));
        implementation.recordException(handle, new RuntimeException("boom-again"));

        assertThat(registry.find(PlatformTracingMetrics.EXCEPTIONS_RECORDED).counter().count()).isEqualTo(1.0);
        verify(spiDelegate, times(1)).recordException(eq(handle), any());
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
