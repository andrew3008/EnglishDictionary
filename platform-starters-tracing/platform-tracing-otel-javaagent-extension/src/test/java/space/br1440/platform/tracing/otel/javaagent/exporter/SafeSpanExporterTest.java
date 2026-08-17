package space.br1440.platform.tracing.otel.javaagent.exporter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.AbstractCollection;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.control.AcceptedPolicyMutationAccess;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntime;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntimeTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

class SafeSpanExporterTest {

    /** Список из {@code n} элементов-заглушек (SafeSpanExporter не разыменовывает содержимое). */
    private static Collection<SpanData> spansOfSize(int n) {
        return Collections.nCopies(n, (SpanData) null);
    }

    private static SafeSpanExporter safeExporter(SpanExporter delegate) {
        return safeExporter(delegate, TracingSettingsSource.isolatedDefaults(),
                ScrubbingRuntimeTestFixtures.disabled());
    }

    private static SafeSpanExporter safeExporter(SpanExporter delegate, TracingSettingsSource acceptedPolicy) {
        return safeExporter(delegate, acceptedPolicy, ScrubbingRuntimeTestFixtures.disabled());
    }

    private static SafeSpanExporter safeExporter(SpanExporter delegate,
                                                 TracingSettingsSource acceptedPolicy,
                                                 ScrubbingRuntime runtime) {
        return new SafeSpanExporter(delegate, acceptedPolicy, runtime);
    }

    @Test
    @DisplayName("Успешный export: exported += size, failures и dropped не растут")
    void successfulExportCountsExportedSpans() {
        SafeSpanExporter safe = safeExporter(delegate(CompletableResultCode.ofSuccess()));

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
        SafeSpanExporter safe = safeExporter(delegate(CompletableResultCode.ofFailure()));

        CompletableResultCode result = safe.export(spansOfSize(5));

        assertThat(result.isSuccess()).isFalse();
        assertThat(safe.getExportFailures()).as("один упавший batch").isEqualTo(1);
        assertThat(safe.getDroppedSpans()).as("пять потерянных span'ов").isEqualTo(5);
        assertThat(safe.getExportedSpans()).isZero();
    }

    @Test
    @DisplayName("Исключение делегата изолировано: наружу ofFailure(), исключение не пробрасывается")
    void delegateExceptionIsIsolated() {
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
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
        SafeSpanExporter safe = safeExporter(delegate(null));

        CompletableResultCode result = safe.export(spansOfSize(2));

        assertThat(result.isSuccess()).isFalse();
        assertThat(safe.getExportFailures()).isEqualTo(1);
        assertThat(safe.getDroppedSpans()).isEqualTo(2);
    }

    @Test
    @DisplayName("Семантика счётчиков: два failed-batch'а по 3 → failures=2, dropped=6")
    void countersDistinguishBatchesFromSpans() {
        SafeSpanExporter safe = safeExporter(delegate(CompletableResultCode.ofFailure()));

        safe.export(spansOfSize(3));
        safe.export(spansOfSize(3));

        assertThat(safe.getExportFailures()).as("failures — это batches").isEqualTo(2);
        assertThat(safe.getDroppedSpans()).as("dropped — это spans").isEqualTo(6);
    }

    @Test
    @DisplayName("flush(): исключение делегата изолировано, инкремент flushFailures")
    void flushExceptionIsIsolated() {
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
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
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
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
        SafeSpanExporter safe = safeExporter(delegate(CompletableResultCode.ofSuccess()));
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
        AtomicInteger delegateCalls = new AtomicInteger();
        TracingSettingsSource access = TracingSettingsSource.isolatedDefaults();
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                delegateCalls.incrementAndGet();
                return CompletableResultCode.ofSuccess();
            }
            @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        }, access);

        AcceptedPolicyMutationAccess.applyExporter(access, false);
        CompletableResultCode gated = safe.export(spansOfSize(4));

        assertThat(gated.isSuccess()).as("gate возвращает success, BSP не ретраит").isTrue();
        assertThat(delegateCalls.get()).as("делегат не вызывается").isZero();
        assertThat(safe.getGatedSpans()).isEqualTo(4);
        assertThat(safe.metricsSnapshot().get("suppressed_spans_export_disabled")).isEqualTo(4L);
        assertThat(safe.getExportBatches()).as("gated не считается батчем экспорта").isZero();
        assertThat(safe.getExportFailures()).as("gate — не отказ транспорта").isZero();
        assertThat(safe.getDroppedSpans()).isZero();

        AcceptedPolicyMutationAccess.applyExporter(access, true);
        safe.export(spansOfSize(2));
        assertThat(delegateCalls.get()).as("после включения делегат снова вызывается").isEqualTo(1);
        assertThat(safe.getExportedSpans()).isEqualTo(2);
    }

    @Test
    @DisplayName("exporterEnabled=false does not run boundary scrubbing and accounts the original gated batch")
    void exportGateDoesNotInvokeBoundaryWhenDisabled() {
        ScrubbingRuntime runtime = enabledRuntime("password");
        TracingSettingsSource access = TracingSettingsSource.isolatedDefaults();
        AtomicInteger delegateCalls = new AtomicInteger();
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                delegateCalls.incrementAndGet();
                return CompletableResultCode.ofSuccess();
            }
            @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        }, access, runtime);

        AcceptedPolicyMutationAccess.applyExporter(access, false);
        SpanData span = ExporterBoundaryTestSupport.baseSpan("op")
                .setEvents(List.of(io.opentelemetry.sdk.trace.data.EventData.create(
                        1L, "db.query", Attributes.of(AttributeKey.stringKey("db.password"), "supersecret"))))
                .build();

        CompletableResultCode gated = safe.export(List.of(span, span));

        assertThat(gated.isSuccess()).isTrue();
        assertThat(delegateCalls.get()).isZero();
        assertThat(safe.getGatedSpans()).isEqualTo(2);
        assertThat(runtime.boundaryMetrics().exporterInvocations())
                .as("gated export must not enter the boundary")
                .isZero();
        assertThat(runtime.boundaryMetrics().inputSpans()).isZero();
    }

    @Test
    @DisplayName("SP-04: InterruptedException — флаг прерывания восстановлен, возвращается failure")
    void restoresInterruptFlagWhenDelegateThrowsInterruptedException() {
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
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
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
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
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
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
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
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
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
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

    // -------------------------------------------------------------------------
    // P5 — exporter-boundary integration: batch accounting and fail-closed drops
    // (ADR-scrubbing-hybrid-processor-export-boundary.md)
    // -------------------------------------------------------------------------

    private static ScrubbingRuntime enabledRuntime(String... ruleNames) {
        List<SpanAttributeScrubbingRule> rules = new ArrayList<>();
        for (String name : ruleNames) {
            rules.add(BuiltInSpanAttributeScrubbingRules.resolve(name));
        }
        return ExporterBoundaryTestSupport.runtimeOf(rules);
    }

    @Test
    @DisplayName("Boundary scrubbing runs before the delegate sees the batch: event attributes are already scrubbed")
    void delegateReceivesTheBoundaryScrubbedBatch() {
        ScrubbingRuntime runtime = enabledRuntime("password");
        List<SpanData> captured = new ArrayList<>();
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                captured.addAll(spans);
                return CompletableResultCode.ofSuccess();
            }
            @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        }, TracingSettingsSource.isolatedDefaults(), runtime);

        SpanData span = ExporterBoundaryTestSupport.baseSpan("op")
                .setEvents(List.of(io.opentelemetry.sdk.trace.data.EventData.create(
                        1L, "db.query", Attributes.of(AttributeKey.stringKey("db.password"), "supersecret"))))
                .build();

        safe.export(List.of(span));

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).getEvents().get(0).getAttributes().get(AttributeKey.stringKey("db.password")))
                .isNull();
        assertThat(safe.getExportedSpans()).isEqualTo(1);
    }

    @Test
    @DisplayName("Batch-accounting: a fail-closed boundary drop shrinks the effective batch without a transport failure")
    void failClosedBoundaryDropShrinksEffectiveBatchWithoutTransportFailure() {
        ScrubbingRuntime runtime = enabledRuntime("email");
        AtomicInteger delegateBatchSize = new AtomicInteger(-1);
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                delegateBatchSize.set(spans.size());
                return CompletableResultCode.ofSuccess();
            }
            @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        }, TracingSettingsSource.isolatedDefaults(), runtime);

        SpanData healthy = ExporterBoundaryTestSupport.baseSpan("op-1").build();
        SpanData poisoned = new io.opentelemetry.sdk.trace.data.DelegatingSpanData(
                ExporterBoundaryTestSupport.baseSpan("op-2").build()) {
            @Override
            public Attributes getAttributes() {
                throw new IllegalStateException("simulated corruption");
            }
        };

        CompletableResultCode result = safe.export(List.of(healthy, poisoned));

        assertThat(result.isSuccess()).as("boundary drop is not a transport failure").isTrue();
        assertThat(delegateBatchSize.get()).as("only the healthy span reaches the delegate").isEqualTo(1);
        assertThat(safe.getExportFailures()).isZero();
        assertThat(safe.getDroppedSpans()).isZero();
        assertThat(runtime.boundaryMetrics().collapseFailures()).isEqualTo(1L);
    }

    @Test
    @DisplayName("A hostile non-List batch that throws while being snapshotted fails closed: delegate is never called")
    void hostileBatchThrowingDuringBoundaryPreparationFailsClosedWithoutCallingDelegate() {
        ScrubbingRuntime runtime = enabledRuntime("email");
        AtomicInteger delegateCalls = new AtomicInteger();
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                delegateCalls.incrementAndGet();
                return CompletableResultCode.ofSuccess();
            }
            @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        }, TracingSettingsSource.isolatedDefaults(), runtime);

        // applyBoundaryScrubbing() must snapshot a non-List batch via new ArrayList<>(batch), which
        // drains this hostile Collection's iterator() before a single SpanData is ever scrubbed.
        Collection<SpanData> hostileBatch = new AbstractCollection<>() {
            @Override
            public Iterator<SpanData> iterator() {
                throw new IllegalStateException("simulated hostile boundary adapter failure");
            }
            @Override
            public int size() {
                return 1;
            }
        };

        CompletableResultCode result = safe.export(hostileBatch);

        assertThat(result.isSuccess())
                .as("boundary preparation failure must never fall through to delegate.export(rawBatch)")
                .isFalse();
        assertThat(delegateCalls.get()).as("delegate must never be called with an unscrubbed batch").isZero();
        // Sanitizer-boundary failures are classified separately from transport failures: the
        // delegate was never invoked, so exportFailures/droppedSpans (transport-oriented) must stay
        // at zero, and the dedicated boundary counters must record the drop instead.
        assertThat(safe.getExportFailures()).as("boundary failure is not a transport failure").isZero();
        assertThat(safe.getDroppedSpans()).as("boundary failure is not a transport drop").isZero();
        assertThat(runtime.boundaryMetrics().batchPreparationFailures()).isEqualTo(1L);
        assertThat(runtime.boundaryMetrics().spansDroppedFailClosed()).isEqualTo(1L);
    }

    @Test
    @DisplayName("A hostile batch that throws from size() itself fails closed: delegate is never called")
    void hostileBatchThrowingFromSizeFailsClosedWithoutCallingDelegate() {
        ScrubbingRuntime runtime = enabledRuntime("email");
        AtomicInteger delegateCalls = new AtomicInteger();
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                delegateCalls.incrementAndGet();
                return CompletableResultCode.ofSuccess();
            }
            @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        }, TracingSettingsSource.isolatedDefaults(), runtime);

        // size() is called before the exporterEnabled() gate and before boundary scrubbing even
        // starts; a hostile Collection failing here must be just as fail-closed as one failing from
        // iterator(). Because size() itself threw, the original span count is unknown (-1 sentinel),
        // so the fail-closed drop count cannot be attributed and must not be incremented.
        Collection<SpanData> hostileBatch = new AbstractCollection<>() {
            @Override
            public Iterator<SpanData> iterator() {
                return List.<SpanData>of().iterator();
            }
            @Override
            public int size() {
                throw new IllegalStateException("simulated hostile size() failure");
            }
        };

        CompletableResultCode result = safe.export(hostileBatch);

        assertThat(result.isSuccess())
                .as("a hostile size() must fail closed exactly like a hostile iterator()")
                .isFalse();
        assertThat(delegateCalls.get()).as("delegate must never be called").isZero();
        assertThat(safe.getExportFailures()).isZero();
        assertThat(safe.getDroppedSpans()).isZero();
        assertThat(runtime.boundaryMetrics().batchPreparationFailures()).isEqualTo(1L);
        assertThat(runtime.boundaryMetrics().spansDroppedFailClosed())
                .as("original count is unknown when size() itself throws; nothing countable was dropped")
                .isZero();
    }

    @Test
    @DisplayName("A boundary preparation failure never logs the raw exception message/toString()")
    void boundaryPreparationFailureNeverLogsSensitiveThrowableMessage() {
        String secret = "secret-canary-4111111111111111";
        ScrubbingRuntime runtime = enabledRuntime("email");
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                throw new AssertionError("delegate must never be invoked");
            }
            @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        }, TracingSettingsSource.isolatedDefaults(), runtime);

        Collection<SpanData> hostileBatch = new AbstractCollection<>() {
            @Override
            public Iterator<SpanData> iterator() {
                throw new IllegalStateException("Cannot process customer token=" + secret);
            }
            @Override
            public int size() {
                return 1;
            }
        };

        AgentLogCapture capture = new AgentLogCapture();
        capture.install();
        try {
            CompletableResultCode result = safe.export(hostileBatch);

            assertThat(result.isSuccess()).isFalse();
            assertThat(capture.events()).isNotEmpty();
            assertThat(capture.events()).allSatisfy(event -> {
                assertThat(event.fields().toString())
                        .doesNotContain(secret, "Cannot process customer token");
                if (event.throwableDigest() != null) {
                    assertThat(event.throwableDigest())
                            .doesNotContain(secret, "Cannot process customer token");
                }
            });
        } finally {
            capture.restore();
        }
    }

    @Test
    @DisplayName("A hostile batch whose second size() call (post-boundary-scrubbing) throws fails closed")
    void hostileBatchWhoseSecondSizeCallThrowsFailsClosed() {
        ScrubbingRuntime runtime = enabledRuntime("email");
        AtomicInteger delegateCalls = new AtomicInteger();
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                delegateCalls.incrementAndGet();
                return CompletableResultCode.ofSuccess();
            }
            @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        }, TracingSettingsSource.isolatedDefaults(), runtime);

        // export() calls batch.size() at least twice for an unchanged List batch: once for
        // originalCount, once via applyBoundaryScrubbing()'s isEmpty() check, and once more for
        // effectiveCount after applyBoundaryScrubbing() returns the same (unchanged) List reference.
        // iterator() is overridden to a fixed, size()-independent iterator so the exact call that
        // fails is the third one — the standalone effectiveCount = batch.size() line — and not some
        // incidental call from a default AbstractList iterator implementation.
        SpanData span = ExporterBoundaryTestSupport.baseSpan("op").build();
        AtomicInteger sizeCalls = new AtomicInteger();
        List<SpanData> hostileBatch = new AbstractList<>() {
            @Override
            public SpanData get(int index) {
                return span;
            }
            @Override
            public Iterator<SpanData> iterator() {
                return List.of(span).iterator();
            }
            @Override
            public int size() {
                if (sizeCalls.incrementAndGet() <= 2) {
                    return 1;
                }
                throw new IllegalStateException("simulated hostile size() failure on the 3rd call");
            }
        };

        CompletableResultCode result = safe.export(hostileBatch);

        assertThat(sizeCalls.get())
                .as("the fixture must actually reach the post-boundary-scrubbing size() call")
                .isGreaterThanOrEqualTo(3);
        assertThat(result.isSuccess())
                .as("a hostile size() call after boundary scrubbing must fail closed exactly like the first")
                .isFalse();
        assertThat(delegateCalls.get()).as("delegate must never be called").isZero();
        assertThat(safe.getExportFailures()).isZero();
        assertThat(safe.getDroppedSpans()).isZero();
        assertThat(runtime.boundaryMetrics().batchPreparationFailures()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Batch-accounting: when every span is boundary-dropped, the delegate is never invoked (success)")
    void wholeBatchBoundaryDroppedNeverCallsDelegate() {
        ScrubbingRuntime runtime = enabledRuntime("email");
        AtomicInteger delegateCalls = new AtomicInteger();
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                delegateCalls.incrementAndGet();
                return CompletableResultCode.ofSuccess();
            }
            @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        }, TracingSettingsSource.isolatedDefaults(), runtime);

        SpanData poisoned = new io.opentelemetry.sdk.trace.data.DelegatingSpanData(
                ExporterBoundaryTestSupport.baseSpan("op").build()) {
            @Override
            public Attributes getAttributes() {
                throw new IllegalStateException("simulated corruption");
            }
        };

        CompletableResultCode result = safe.export(List.of(poisoned));

        assertThat(result.isSuccess()).isTrue();
        assertThat(delegateCalls.get()).as("empty effective batch never reaches the delegate").isZero();
        assertThat(safe.getExportBatches()).isZero();
    }

    @Test
    @DisplayName("metricsSnapshot exposes boundary counters only when scrubbing is enabled")
    void metricsSnapshotExposesBoundaryCountersOnlyWhenEnabled() {
        ScrubbingRuntime disabled = ScrubbingRuntimeTestFixtures.disabled();
        SafeSpanExporter withDisabledRuntime = new SafeSpanExporter(
                delegate(CompletableResultCode.ofSuccess()), TracingSettingsSource.isolatedDefaults(), disabled);
        assertThat(withDisabledRuntime.metricsSnapshot()).doesNotContainKey("boundary.input_spans");

        ScrubbingRuntime enabled = enabledRuntime("email");
        SafeSpanExporter withEnabledRuntime = new SafeSpanExporter(
                delegate(CompletableResultCode.ofSuccess()), TracingSettingsSource.isolatedDefaults(), enabled);
        withEnabledRuntime.export(List.of(ExporterBoundaryTestSupport.baseSpan("op").build()));
        assertThat(withEnabledRuntime.metricsSnapshot()).containsKey("boundary.input_spans");
    }

    @Test
    @DisplayName("No-op constructors are package-private: an external customizer cannot build a disabled boundary")
    void noOpConstructorsAreNotPartOfThePublicSurface() {
        for (Constructor<?> constructor : SafeSpanExporter.class.getDeclaredConstructors()) {
            boolean carriesRuntime = List.of(constructor.getParameterTypes()).contains(ScrubbingRuntime.class);
            assertThat(Modifier.isPublic(constructor.getModifiers()))
                    .as("constructor %s must be public only when it requires an explicit ScrubbingRuntime",
                            constructor)
                    .isEqualTo(carriesRuntime);
        }
    }

    @Test
    @DisplayName("Clean batch is forwarded as the same Collection instance (no defensive copy)")
    void cleanBatchIsForwardedWithoutCopying() {
        ScrubbingRuntime enabled = enabledRuntime("email");
        List<Collection<SpanData>> seen = new ArrayList<>();
        SafeSpanExporter safe = safeExporter(new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                seen.add(spans);
                return CompletableResultCode.ofSuccess();
            }
            @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        }, TracingSettingsSource.isolatedDefaults(), enabled);

        Collection<SpanData> batch = List.of(ExporterBoundaryTestSupport.baseSpan("clean").build());
        safe.export(batch);

        assertThat(seen).hasSize(1);
        assertThat(seen.get(0)).isSameAs(batch);
    }
}
