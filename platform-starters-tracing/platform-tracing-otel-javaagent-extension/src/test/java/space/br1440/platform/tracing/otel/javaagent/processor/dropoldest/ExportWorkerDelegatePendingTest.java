package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mockito.Mockito;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.CapturedLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ExporterLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

@Timeout(5)
@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class ExportWorkerDelegatePendingTest {

    @RegisterExtension
    final AgentLogCapture capture = new AgentLogCapture();

    @Test
    void secondExportStartsOnlyAfterTheTimedOutDelegateCompletes() throws InterruptedException {
        // given
        CompletableResultCode pending = new CompletableResultCode();
        AtomicInteger exports = new AtomicInteger();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        SpanExporter delegate = new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                int call = exports.incrementAndGet();
                if (call == 1) {
                    firstEntered.countDown();
                    return pending;
                }

                secondEntered.countDown();
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };
        DropOldestExportMetrics metrics = new DropOldestExportMetrics();
        ExportBuffer buffer = new ExportBuffer(8, 1, metrics);
        TimedSpanExporter exporter = new TimedSpanExporter(
                delegate,
                TimeUnit.MILLISECONDS.toNanos(20),
                TimeUnit.SECONDS.toNanos(2),
                metrics
        );
        ExportWorker worker = new ExportWorker(
                buffer,
                exporter,
                Duration.ofMillis(5).toNanos(),
                Duration.ofSeconds(2).toNanos()
        );
        buffer.admit(spanData());
        Thread thread = new Thread(worker, ExportWorker.THREAD_NAME);
        thread.start();
        AtomicBoolean laterActionRan = new AtomicBoolean();

        // when
        assertThat(firstEntered.await(3, TimeUnit.SECONDS)).isTrue();
        buffer.admit(spanData());
        assertThat(secondEntered.await(250, TimeUnit.MILLISECONDS)).isFalse();
        pending.whenComplete(() -> laterActionRan.set(true));
        pending.succeed();

        // then
        assertThat(laterActionRan).isTrue();
        assertThat(secondEntered.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(exports).hasValue(2);
        assertThat(worker.shutdown().join(3, TimeUnit.SECONDS).isSuccess()).isTrue();
        assertThat(thread.join(Duration.ofSeconds(3))).isTrue();
        assertThat(thread.isAlive()).isFalse();

        List<CapturedLogEvent> events = capture.events();
        assertThat(events)
                .filteredOn(event -> event.eventName().equals(ExporterLogEvent.TIMED_DELEGATE_SLOT_HELD.name()))
                .isNotEmpty()
                .allSatisfy(event -> {
                    assertThat(event.level()).isEqualTo(AgentLogLevel.WARN);
                    assertThat(event.fields()).containsKey("heldMillis");
                });
        assertThat(events)
                .filteredOn(event -> event.eventName().equals(ExporterLogEvent.TIMED_DELEGATE_SLOT_RELEASED.name()))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.level()).isEqualTo(AgentLogLevel.INFO);
                    assertThat(event.fields()).containsEntry("outcome", "SUCCESS").containsKey("heldMillis");
                });
    }

    private static SpanData spanData() {
        SpanData spanData = Mockito.mock(SpanData.class);
        Mockito.when(spanData.getAttributes()).thenReturn(Attributes.empty());
        return spanData;
    }
}
