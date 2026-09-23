package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;

class ExportWorkerFailedStateTest {

    @Test
    @SuppressWarnings("removal")
    void fatalThreadDeathPerformsBookkeepingThenRethrowsOriginalThrowable() {
        // given
        DropOldestExportMetrics metrics = new DropOldestExportMetrics();
        ExportBuffer buffer = new ExportBuffer(16, 4, metrics);
        buffer.admit(spanData());
        CompletableResultCode flush = buffer.requestFlush();

        TimedSpanExporter exporter = Mockito.mock(TimedSpanExporter.class);
        when(exporter.export(any())).thenThrow(new ThreadDeath());
        ExportWorker worker = new ExportWorker(
                buffer, exporter, Duration.ofMinutes(10).toNanos(), Duration.ofSeconds(2).toNanos()
        );

        // when
        assertThatThrownBy(worker::run).isInstanceOf(ThreadDeath.class);

        // then
        assertThat(buffer.lifecycleState()).isEqualTo(LifecycleState.FAILED);
        assertThat(flush.isDone()).isTrue();
        assertThat(flush.isSuccess()).isFalse();
        assertThat(worker.shutdown().isSuccess()).isFalse();
    }

    @Test
    void fatalLinkageErrorPerformsBookkeepingThenRethrowsOriginalThrowable() {
        // given
        DropOldestExportMetrics metrics = new DropOldestExportMetrics();
        ExportBuffer buffer = new ExportBuffer(16, 4, metrics);
        buffer.admit(spanData());
        CompletableResultCode flush = buffer.requestFlush();

        TimedSpanExporter exporter = Mockito.mock(TimedSpanExporter.class);
        when(exporter.export(any())).thenThrow(new NoClassDefFoundError("simulated fatal LinkageError"));
        ExportWorker worker = new ExportWorker(
                buffer, exporter, Duration.ofMinutes(10).toNanos(), Duration.ofSeconds(2).toNanos()
        );

        // when
        assertThatThrownBy(worker::run).isInstanceOf(NoClassDefFoundError.class);

        // then
        assertThat(buffer.lifecycleState()).isEqualTo(LifecycleState.FAILED);
        assertThat(flush.isDone()).isTrue();
        assertThat(flush.isSuccess()).isFalse();
    }

    @Test
    void nonfatalThrowableEscapingExporterTransitionsToFailedWithoutRethrow() {
        // given
        DropOldestExportMetrics metrics = new DropOldestExportMetrics();
        ExportBuffer buffer = new ExportBuffer(16, 4, metrics);
        buffer.admit(spanData());
        CompletableResultCode flush = buffer.requestFlush();

        TimedSpanExporter exporter = Mockito.mock(TimedSpanExporter.class);
        when(exporter.export(any())).thenThrow(new IllegalStateException("simulated unexpected bug"));
        ExportWorker worker = new ExportWorker(
                buffer, exporter, Duration.ofMinutes(10).toNanos(), Duration.ofSeconds(2).toNanos()
        );

        // when
        AtomicBoolean escaped = new AtomicBoolean(true);
        try {
            worker.run();
            escaped.set(false);
        } catch (RuntimeException unexpected) {
            escaped.set(true);
        }

        // then
        assertThat(escaped).as("nonfatal Throwable must not escape ExportWorker.run()").isFalse();
        assertThat(buffer.lifecycleState()).isEqualTo(LifecycleState.FAILED);
        assertThat(flush.isDone()).isTrue();
        assertThat(flush.isSuccess()).isFalse();
    }

    @Test
    void fatalShutdownFailureIsRethrownAfterNonfatalLoopFailureAndResultPublication() {
        DropOldestExportMetrics metrics = new DropOldestExportMetrics();
        ExportBuffer buffer = new ExportBuffer(16, 4, metrics);
        buffer.admit(spanData());

        CompletableResultCode flush = buffer.requestFlush();
        IllegalStateException loopFailure = new IllegalStateException("simulated worker bug");
        NoClassDefFoundError shutdownFailure = new NoClassDefFoundError("simulated fatal shutdown failure");

        TimedSpanExporter exporter = Mockito.mock(TimedSpanExporter.class);
        when(exporter.export(any())).thenThrow(loopFailure);
        when(exporter.shutdown()).thenThrow(shutdownFailure);
        ExportWorker worker = new ExportWorker(
                buffer, exporter, Duration.ofMinutes(10).toNanos(), Duration.ofSeconds(2).toNanos()
        );

        assertThatThrownBy(worker::run).isSameAs(shutdownFailure);

        CompletableResultCode shutdown = worker.shutdown();
        assertThat(shutdown.isDone()).isTrue();
        assertThat(shutdown.isSuccess()).isFalse();
        assertThat(flush.isDone()).isTrue();
        assertThat(flush.isSuccess()).isFalse();
        assertThat(buffer.lifecycleState()).isEqualTo(LifecycleState.FAILED);
        verify(exporter, times(1)).shutdown();
    }

    @Test
    void failedStateClosesAdmissionAndDiscardsQueuedSpansExactlyOnce() {
        // given
        DropOldestExportMetrics metrics = new DropOldestExportMetrics();
        ExportBuffer buffer = new ExportBuffer(16, 4, metrics);
        buffer.admit(spanData());
        buffer.admit(spanData());
        buffer.admit(spanData());

        // when
        buffer.failTerminal();

        // then
        assertThat(buffer.isAdmissionOpen()).isFalse();
        assertThat(metrics.discardedQueuedTotal()).isEqualTo(3);
        assertThat(buffer.snapshot().queueSize()).isZero();

        // and: новые спаны после FAILED учитываются отдельным счётчиком, не droppedAfterShutdownTotal
        buffer.admit(spanData());
        assertThat(metrics.droppedAfterFailureTotal()).isEqualTo(1);
        assertThat(metrics.droppedAfterShutdownTotal()).isZero();

        // and: повторный failTerminal идемпотентен — счётчик не двойной
        buffer.failTerminal();
        assertThat(metrics.discardedQueuedTotal()).isEqualTo(3);
    }

    @Test
    void shutdownBeforeAndAfterFailurePublicationClosesTheExporterOnce() throws InterruptedException {
        // given
        DropOldestExportMetrics metrics = new DropOldestExportMetrics();
        ExportBuffer buffer = new ExportBuffer(16, 4, metrics);
        buffer.admit(spanData());
        CompletableResultCode flush = buffer.requestFlush();

        CountDownLatch exportEntered = new CountDownLatch(1);
        CountDownLatch releaseExport = new CountDownLatch(1);

        TimedSpanExporter exporter = Mockito.mock(TimedSpanExporter.class);
        when(exporter.export(any())).thenAnswer(invocation -> {
            exportEntered.countDown();
            if (!releaseExport.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("export release timed out");
            }
            throw new IllegalStateException("simulated unexpected bug");
        });
        when(exporter.shutdown()).thenReturn(true);

        ExportWorker worker = new ExportWorker(
                buffer, exporter, Duration.ofMinutes(10).toNanos(), Duration.ofSeconds(2).toNanos()
        );
        Thread workerThread = new Thread(worker, "platform-tracing-drop-oldest-exporter");
        workerThread.start();
        assertThat(exportEntered.await(3, TimeUnit.SECONDS)).isTrue();

        // when
        CompletableResultCode shutdown = worker.shutdown();
        releaseExport.countDown();
        workerThread.join(3_000);
        CompletableResultCode repeated = worker.shutdown();

        // then
        assertThat(workerThread.isAlive()).isFalse();
        assertThat(repeated).isSameAs(shutdown);
        assertThat(shutdown.isDone()).isTrue();
        assertThat(shutdown.isSuccess()).isFalse();
        assertThat(flush.isDone()).isTrue();
        assertThat(flush.isSuccess()).isFalse();
        assertThat(buffer.lifecycleState()).isEqualTo(LifecycleState.FAILED);
        verify(exporter, times(1)).shutdown();
    }

    private static SpanData spanData() {
        SpanData spanData = Mockito.mock(SpanData.class);
        when(spanData.getAttributes()).thenReturn(Attributes.empty());
        return spanData;
    }
}
