package space.br1440.platform.tracing.otel.javaagent.exporter;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-тесты {@link SafeSpanExporter}: изоляция исключений делегата и строгая семантика
 * счётчиков (failures считаются на batch, dropped — на span).
 */
// SafeSpanExporter реализует Closeable (через SpanExporter), но делегаты в тестах — no-op
// без реальных ресурсов, поэтому resource-leak предупреждения здесь неактуальны.
@SuppressWarnings("resource")
class SafeSpanExporterTest {

    /** Список из {@code n} элементов-заглушек (SafeSpanExporter не разыменовывает содержимое). */
    private static Collection<SpanData> spansOfSize(int n) {
        return Collections.nCopies(n, (SpanData) null);
    }

    @Test
    @DisplayName("Успешный export: exported += size, failures и dropped не растут")
    void successfulExportCountsExportedSpans() {
        SafeSpanExporter safe = new SafeSpanExporter(delegate(CompletableResultCode.ofSuccess()));

        CompletableResultCode result = safe.export(spansOfSize(3));

        assertThat(result.isSuccess()).isTrue();
        assertThat(safe.getExportedSpans()).isEqualTo(3);
        assertThat(safe.getExportFailures()).isZero();
        assertThat(safe.getDroppedSpans()).isZero();
        assertThat(safe.getExportBatches()).isEqualTo(1);
    }

    @Test
    @DisplayName("Failed-результат делегата: failures += 1 (batch), dropped += size (spans)")
    void failedResultIncrementsBatchFailureAndSpanDrops() {
        SafeSpanExporter safe = new SafeSpanExporter(delegate(CompletableResultCode.ofFailure()));

        CompletableResultCode result = safe.export(spansOfSize(5));

        assertThat(result.isSuccess()).isFalse();
        assertThat(safe.getExportFailures()).as("один упавший batch").isEqualTo(1);
        assertThat(safe.getDroppedSpans()).as("пять потерянных span'ов").isEqualTo(5);
        assertThat(safe.getExportedSpans()).isZero();
    }

    @Test
    @DisplayName("Исключение делегата изолировано: наружу ofFailure(), исключение не пробрасывается")
    void delegateExceptionIsIsolated() {
        SafeSpanExporter safe = new SafeSpanExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                throw new RuntimeException("simulated transport failure");
            }
            @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        });

        CompletableResultCode result = safe.export(spansOfSize(4));

        assertThat(result.isSuccess()).isFalse();
        assertThat(safe.getExportFailures()).isEqualTo(1);
        assertThat(safe.getDroppedSpans()).isEqualTo(4);
    }

    @Test
    @DisplayName("Делегат вернул null: трактуем как failure без NPE")
    void nullResultTreatedAsFailure() {
        SafeSpanExporter safe = new SafeSpanExporter(delegate(null));

        CompletableResultCode result = safe.export(spansOfSize(2));

        assertThat(result.isSuccess()).isFalse();
        assertThat(safe.getExportFailures()).isEqualTo(1);
        assertThat(safe.getDroppedSpans()).isEqualTo(2);
    }

    @Test
    @DisplayName("Семантика счётчиков: два failed-batch'а по 3 → failures=2, dropped=6")
    void countersDistinguishBatchesFromSpans() {
        SafeSpanExporter safe = new SafeSpanExporter(delegate(CompletableResultCode.ofFailure()));

        safe.export(spansOfSize(3));
        safe.export(spansOfSize(3));

        assertThat(safe.getExportFailures()).as("failures — это batches").isEqualTo(2);
        assertThat(safe.getDroppedSpans()).as("dropped — это spans").isEqualTo(6);
    }

    @Test
    @DisplayName("flush(): исключение делегата изолировано, инкремент flushFailures")
    void flushExceptionIsIsolated() {
        SafeSpanExporter safe = new SafeSpanExporter(new SpanExporter() {
            @Override public CompletableResultCode export(Collection<SpanData> spans) { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode flush() { throw new RuntimeException("flush boom"); }
            @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        });

        CompletableResultCode result = safe.flush();

        assertThat(result.isSuccess()).isFalse();
        assertThat(safe.getFlushFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("shutdown(): исключение делегата изолировано, инкремент shutdownFailures")
    void shutdownExceptionIsIsolated() {
        SafeSpanExporter safe = new SafeSpanExporter(new SpanExporter() {
            @Override public CompletableResultCode export(Collection<SpanData> spans) { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode shutdown() { throw new RuntimeException("shutdown boom"); }
        });

        CompletableResultCode result = safe.shutdown();

        assertThat(result.isSuccess()).isFalse();
        assertThat(safe.getShutdownFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("metricsSnapshot содержит стабильный набор ключей")
    void metricsSnapshotHasStableKeys() {
        SafeSpanExporter safe = new SafeSpanExporter(delegate(CompletableResultCode.ofSuccess()));
        safe.export(spansOfSize(1));

        Map<String, Long> snapshot = safe.metricsSnapshot();

        assertThat(snapshot).containsKeys(
                "export_batches", "export_batch_failures", "exported_spans", "transport_dropped_spans",
                "flush_failures", "shutdown_failures", "last_export_duration_nanos",
                "suppressed_spans_export_disabled", "export_enabled");
        assertThat(snapshot.get("exported_spans")).isEqualTo(1L);
    }

    @Test
    @DisplayName("Export-gate (Фаза 14): выключенный экспорт отбрасывает batch, делегат не вызывается, success")
    void exportGateDropsBatchWithoutCallingDelegate() {
        java.util.concurrent.atomic.AtomicInteger delegateCalls = new java.util.concurrent.atomic.AtomicInteger();
        SafeSpanExporter safe = new SafeSpanExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                delegateCalls.incrementAndGet();
                return CompletableResultCode.ofSuccess();
            }
            @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        });

        safe.setExportEnabled(false);
        CompletableResultCode gated = safe.export(spansOfSize(4));

        assertThat(gated.isSuccess()).as("gate возвращает success, BSP не ретраит").isTrue();
        assertThat(delegateCalls.get()).as("делегат не вызывается").isZero();
        assertThat(safe.getGatedSpans()).isEqualTo(4);
        assertThat(safe.metricsSnapshot().get("suppressed_spans_export_disabled")).isEqualTo(4L);
        assertThat(safe.getExportBatches()).as("gated не считается батчем экспорта").isZero();
        assertThat(safe.getExportFailures()).as("gate — не отказ транспорта").isZero();
        assertThat(safe.getDroppedSpans()).isZero();

        safe.setExportEnabled(true);
        safe.export(spansOfSize(2));
        assertThat(delegateCalls.get()).as("после включения делегат снова вызывается").isEqualTo(1);
        assertThat(safe.getExportedSpans()).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // SP-04 — Characterization: interrupt handling, fatal propagation, null results
    // -------------------------------------------------------------------------

    /**
     * SP-04 / Test 5.
     * Когда делегат бросает {@link InterruptedException} (через sneaky-throw, т.к. SPI не
     * объявляет checked-исключений), {@link SafeSpanExporter} восстанавливает флаг прерывания
     * через {@code PlatformThrowables.propagateIfFatal}.
     * Экспорт при этом возвращает {@code ofFailure()} — fail-open поведение сохраняется.
     */
    @Test
    @DisplayName("SP-04: InterruptedException — флаг прерывания восстановлен, возвращается failure")
    void restoresInterruptFlagWhenDelegateThrowsInterruptedException() {
        SafeSpanExporter safe = new SafeSpanExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                // Sneaky-throw: InterruptedException без объявления в сигнатуре.
                // Имитирует блокирующий делегат, чей поток прерван.
                sneakyThrow(new InterruptedException("simulated interrupt during export"));
                return null; // unreachable
            }
            @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        });

        Thread.interrupted(); // гарантируем чистый флаг до вызова

        try {
            CompletableResultCode result = safe.export(spansOfSize(1));

            assertThat(result).isNotNull();
            assertThat(result.isSuccess()).isFalse();
            assertThat(Thread.currentThread().isInterrupted())
                    .as("propagateIfFatal должен восстановить флаг прерывания")
                    .isTrue();
        } finally {
            Thread.interrupted(); // очищаем флаг, чтобы не загрязнять другие тесты
        }
    }

    /**
     * SP-04 / Test 6.
     * Фатальные {@link Throwable} (здесь — {@link LinkageError}, безопасный для тестов)
     * пробрасываются наружу через {@code PlatformThrowables.propagateIfFatal}.
     * Fail-open поведение применяется только к нефатальным исключениям.
     */
    @Test
    @DisplayName("SP-04: LinkageError (fatal) пробрасывается наружу, не поглощается")
    void propagatesFatalThrowableAccordingToPlatformThrowablesPolicy() {
        SafeSpanExporter safe = new SafeSpanExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                throw new LinkageError("simulated fatal linkage error");
            }
            @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        });

        assertThatThrownBy(() -> safe.export(spansOfSize(1)))
                .isInstanceOf(LinkageError.class)
                .hasMessage("simulated fatal linkage error");
    }

    /**
     * SP-04 / flush null-result.
     * Когда делегат {@code flush()} возвращает {@code null}, {@link SafeSpanExporter}
     * не бросает NPE и возвращает {@code ofFailure()}.
     * Примечание: {@code flushFailures} НЕ инкрементируется для null-результата
     * (только для выброшенных исключений) — это задокументированная семантика.
     */
    @Test
    @DisplayName("SP-04: flush() null-результат → ofFailure() без NPE, flushFailures не растёт")
    void flushNullResultTreatedAsFailureWithoutIncrementingFailureCounter() {
        SafeSpanExporter safe = new SafeSpanExporter(new SpanExporter() {
            @Override public CompletableResultCode export(Collection<SpanData> spans) { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode flush() { return null; }
            @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        });

        CompletableResultCode result = safe.flush();

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isFalse();
        // Нулевой результат — не failure транспорта; счётчик исключений не растёт.
        assertThat(safe.getFlushFailures()).as("null-результат не считается flush-исключением").isZero();
    }

    /**
     * SP-04 / shutdown null-result.
     * Аналогично flush: null из {@code shutdown()} возвращается как {@code ofFailure()} без NPE.
     * {@code shutdownFailures} НЕ инкрементируется.
     */
    @Test
    @DisplayName("SP-04: shutdown() null-результат → ofFailure() без NPE, shutdownFailures не растёт")
    void shutdownNullResultTreatedAsFailureWithoutIncrementingFailureCounter() {
        SafeSpanExporter safe = new SafeSpanExporter(new SpanExporter() {
            @Override public CompletableResultCode export(Collection<SpanData> spans) { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode shutdown() { return null; }
        });

        CompletableResultCode result = safe.shutdown();

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isFalse();
        assertThat(safe.getShutdownFailures()).as("null-результат не считается shutdown-исключением").isZero();
    }

    /**
     * SP-04 / metricsSnapshot — полный срез при смешанных сценариях.
     * Убеждаемся, что счётчики в {@code metricsSnapshot()} отражают реальный контракт:
     * export-исключение инкрементирует {@code export_batch_failures} и {@code transport_dropped_spans};
     * flush-исключение инкрементирует {@code flush_failures};
     * shutdown-исключение инкрементирует {@code shutdown_failures}.
     */
    @Test
    @DisplayName("SP-04: metricsSnapshot корректно отражает export/flush/shutdown failures")
    void metricsSnapshotReflectsAllFailureCounters() {
        SafeSpanExporter safe = new SafeSpanExporter(new SpanExporter() {
            @Override public CompletableResultCode export(Collection<SpanData> spans) { throw new RuntimeException("export boom"); }
            @Override public CompletableResultCode flush() { throw new RuntimeException("flush boom"); }
            @Override public CompletableResultCode shutdown() { throw new RuntimeException("shutdown boom"); }
        });

        safe.export(spansOfSize(3));
        safe.flush();
        safe.shutdown();

        java.util.Map<String, Long> snapshot = safe.metricsSnapshot();
        assertThat(snapshot.get(SpanExporterMetrics.EXPORT_BATCH_FAILURES))
                .as("export exception → export_batch_failures=1").isEqualTo(1L);
        assertThat(snapshot.get(SpanExporterMetrics.TRANSPORT_DROPPED_SPANS))
                .as("3 spans dropped on export failure").isEqualTo(3L);
        assertThat(snapshot.get(SpanExporterMetrics.FLUSH_FAILURES))
                .as("flush exception → flush_failures=1").isEqualTo(1L);
        assertThat(snapshot.get(SpanExporterMetrics.SHUTDOWN_FAILURES))
                .as("shutdown exception → shutdown_failures=1").isEqualTo(1L);
    }

    /**
     * Sneaky-throw хелпер: пробрасывает любой {@link Throwable} без объявления в сигнатуре.
     * Используется для симуляции InterruptedException из SPI-методов, которые не объявляют
     * checked-исключений.
     */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable e) throws E {
        throw (E) e;
    }

    /** Делегат, который для {@code export()} всегда возвращает заданный результат. */
    private static SpanExporter delegate(CompletableResultCode exportResult) {
        return new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                return exportResult;
            }
            @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        };
    }

    /** Заглушка на случай, если понадобится список реальных SpanData в дальнейшем. */
    @SuppressWarnings("unused")
    private static List<SpanData> emptySpans() {
        return List.of();
    }
}
