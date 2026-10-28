package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class SpanTimeoutScannerStopTimeoutLogTest {

    @RegisterExtension
    final AgentLogCapture capture = new AgentLogCapture();

    @Test
    void stopTimeoutLogCarriesTimeoutMillis() throws Exception {
        // given
        SpanTimeoutScanner scanner = new SpanTimeoutScanner(mock(SpanTimeoutScan.class), () -> 0L);
        ScheduledFuture<?> task = mock(ScheduledFuture.class);
        when(task.cancel(false)).thenReturn(true);
        when(task.isCancelled()).thenReturn(false);
        when(task.isDone()).thenReturn(false);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.awaitTermination(2000L, TimeUnit.MILLISECONDS)).thenReturn(false);
        set(scanner, "scheduler", scheduler);
        set(scanner, "scanTask", task);

        // when
        boolean stopped = scanner.stop();

        // then
        assertThat(stopped).isFalse();
        assertThat(capture.events())
                .filteredOn(event -> "SPAN_TIMEOUT_SCANNER_STOP_TIMED_OUT".equals(event.eventName()))
                .singleElement()
                .satisfies(event -> assertThat(event.fields()).containsEntry("timeoutMillis", "2000"));
    }

    private static void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
