package space.br1440.platform.tracing.otel.javaagent.processor.dropoldest;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Hardening-тесты терминального состояния {@code FAILED}: fatal/nonfatal bookkeeping и
 * propagation ровно на границе {@link ExportWorker} / {@link ExportBuffer}
 * (см. ADR-drop-oldest-export-processor-v2 §2).
 */
class ExportWorkerFailedStateTest {

    @Test
    @SuppressWarnings("removal")
    void fatalThreadDeathPerformsBookkeepingThenRethrowsOriginalThrowable() {
        // given: worker с зарегистрированным pending flush и exporter'ом, симулирующим fatal ThreadDeath
        DropOldestExportMetrics metrics = new DropOldestExportMetrics();
        ExportBuffer buffer = new ExportBuffer(16, 4, metrics);
        buffer.admit(spanData());
        CompletableResultCode flush = buffer.requestFlush();

        TimedSpanExporter exporter = Mockito.mock(TimedSpanExporter.class);
        when(exporter.export(any())).thenThrow(new ThreadDeath());
        ExportWorker worker = new ExportWorker(buffer, exporter, Duration.ofMinutes(10).toNanos());

        // when: run() выполняется синхронно на текущем потоке (без start()), чтобы поймать rethrow
        assertThatThrownBy(worker::run).isInstanceOf(ThreadDeath.class);

        // then: bookkeeping выполнен до rethrow — FAILED, pending flush завершён failure, latch освобождён
        assertThat(buffer.lifecycleState()).isEqualTo(LifecycleState.FAILED);
        assertThat(flush.isDone()).isTrue();
        assertThat(flush.isSuccess()).isFalse();
        assertThat(worker.awaitTermination(TimeUnit.SECONDS.toNanos(1))).isTrue();
    }

    @Test
    void fatalLinkageErrorPerformsBookkeepingThenRethrowsOriginalThrowable() {
        // given: NoClassDefFoundError — конкретный LinkageError, воспроизводимый без ThreadDeath
        DropOldestExportMetrics metrics = new DropOldestExportMetrics();
        ExportBuffer buffer = new ExportBuffer(16, 4, metrics);
        buffer.admit(spanData());
        CompletableResultCode flush = buffer.requestFlush();

        TimedSpanExporter exporter = Mockito.mock(TimedSpanExporter.class);
        when(exporter.export(any())).thenThrow(new NoClassDefFoundError("simulated fatal LinkageError"));
        ExportWorker worker = new ExportWorker(buffer, exporter, Duration.ofMinutes(10).toNanos());

        // when
        assertThatThrownBy(worker::run).isInstanceOf(NoClassDefFoundError.class);

        // then
        assertThat(buffer.lifecycleState()).isEqualTo(LifecycleState.FAILED);
        assertThat(flush.isDone()).isTrue();
        assertThat(flush.isSuccess()).isFalse();
    }

    @Test
    void nonfatalThrowableEscapingExporterTransitionsToFailedWithoutRethrow() {
        // given: гипотетический баг в collaborator'е — обычный RuntimeException, сбежавший из exporter
        // (в production exporter.export() уже перехватывается TimedSpanExporter; здесь мокается сама
        // граница ExportWorker/TimedSpanExporter, чтобы проверить defensive nonfatal-ветвь)
        DropOldestExportMetrics metrics = new DropOldestExportMetrics();
        ExportBuffer buffer = new ExportBuffer(16, 4, metrics);
        buffer.admit(spanData());
        CompletableResultCode flush = buffer.requestFlush();

        TimedSpanExporter exporter = Mockito.mock(TimedSpanExporter.class);
        when(exporter.export(any())).thenThrow(new IllegalStateException("simulated unexpected bug"));
        ExportWorker worker = new ExportWorker(buffer, exporter, Duration.ofMinutes(10).toNanos());

        // when: run() не пробрасывает nonfatal Throwable дальше — поглощается как terminal bookkeeping
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
    void failedStateClosesAdmissionAndDiscardsQueuedSpansExactlyOnce() {
        // given: очередь с непереданными exporter'у спанами на момент терминального failure
        DropOldestExportMetrics metrics = new DropOldestExportMetrics();
        ExportBuffer buffer = new ExportBuffer(16, 4, metrics);
        buffer.admit(spanData());
        buffer.admit(spanData());
        buffer.admit(spanData());

        // when: worker переходит в FAILED напрямую (симулируем bookkeeping без реального потока)
        buffer.failTerminal();

        // then: admission закрыт, вся ранее принятая (не в-полёте) очередь учтена как discardedQueuedTotal
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

    private static SpanData spanData() {
        SpanData spanData = Mockito.mock(SpanData.class);
        when(spanData.getAttributes()).thenReturn(Attributes.empty());
        return spanData;
    }
}
