package space.br1440.platform.tracing.otel.javaagent.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.processor.composite.PlatformCompositeSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.dropoldest.PlatformDropOldestExportSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.spantimeout.SpanTimeoutProcessor;

/**
 * Characterization-тесты для дефектов, ранее выявленных в pipeline процессоров:
 * утечка чувствительных данных в диагностику, изоляция spanTimeout по trace,
 * и корректность отложенного shutdown у drop-oldest экспортёра при зависшем export().
 */
class ProcessorDefectCharacterizationTest {

    private static final String SENSITIVE_SPAN_NAME = "customer-secret-span-name";
    private static final String SENSITIVE_EXCEPTION_MESSAGE = "customer-secret-exception";
    private static final String TRACER_SCOPE = "characterization";

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration EXPORT_TIMEOUT = Duration.ofMillis(20);
    private static final Duration EXPORTER_SHUTDOWN_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(1);

    @RegisterExtension
    AgentLogCapture capture = new AgentLogCapture();

    @Test
    void compositeDiagnosticsDoNotDiscloseRawSpanOrExceptionText() {
        // given
        try (PlatformCompositeSpanProcessor composite = new PlatformCompositeSpanProcessor(
                List.of(new SensitiveFailureProcessor()));
             SdkTracerProvider provider = SdkTracerProvider.builder()
                     .addSpanProcessor(composite)
                     .build()) {

            // when
            provider.get(TRACER_SCOPE)
                    .spanBuilder(SENSITIVE_SPAN_NAME)
                    .startSpan()
                    .end();

            // then
            assertThat(capture.events())
                    .anySatisfy(event -> assertThat(event.eventCode())
                            .isEqualTo(AgentEventCodes.EXPORT_CALLBACK));

            assertThat(capture.rendered())
                    .doesNotContain(SENSITIVE_SPAN_NAME, SENSITIVE_EXCEPTION_MESSAGE);

            assertThat(capture.events()).allSatisfy(event -> {
                assertThat(event.fields().toString())
                        .doesNotContain(SENSITIVE_SPAN_NAME, SENSITIVE_EXCEPTION_MESSAGE);

                if (event.throwableDigest() != null) {
                    assertThat(event.throwableDigest())
                            .doesNotContain(SENSITIVE_SPAN_NAME, SENSITIVE_EXCEPTION_MESSAGE);
                }
            });
        }
    }

    @Test
    void spanTimeoutKeepsSameSpanIdIsolatedByTrace() {
        // given: лимиты по количеству спанов/трейсов не участвуют в этом сценарии,
        // поэтому выставлены заведомо недостижимыми — тест проверяет только
        // изоляцию по traceId при совпадающем spanId, а не поведение при переполнении.
        try (SpanTimeoutProcessor spanTimeout = SpanTimeoutProcessor.start(
                Duration.ofHours(1),
                Duration.ofHours(2),
                Duration.ofDays(365),
                10_000,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE
        )) {
            ReadWriteSpan first = mockSpanWithContext(
                    "00000000000000000000000000000001", "0000000000000001"
            );

            ReadWriteSpan second = mockSpanWithContext(
                    "00000000000000000000000000000002", "0000000000000001"
            );

            spanTimeout.onStart(Context.root(), first);
            // when
            spanTimeout.onStart(Context.root(), second);

            // then
            assertThat(spanTimeout.getActiveSpanCount()).isEqualTo(2);
            assertThat(spanTimeout.getActiveTraceCount()).isEqualTo(2);

            spanTimeout.onEnd(first);
            assertThat(spanTimeout.getActiveSpanCount()).isEqualTo(1);
            assertThat(spanTimeout.getActiveTraceCount()).isEqualTo(1);

            spanTimeout.onEnd(second);
            assertThat(spanTimeout.getActiveSpanCount()).isZero();
            assertThat(spanTimeout.getActiveTraceCount()).isZero();
        }
    }

    @Test
    void dropOldestDefersExporterShutdownUntilTimedOutExportCompletes() throws InterruptedException {
        // given
        PendingExporter exporter = new PendingExporter();
        PlatformDropOldestExportSpanProcessor processor =
                PlatformDropOldestExportSpanProcessor.builder(exporter)
                        .maxQueueSize(8)
                        .maxExportBatchSize(1)
                        .scheduleDelay(Duration.ofHours(1))
                        .exportTimeout(EXPORT_TIMEOUT)
                        .shutdownTimeout(EXPORTER_SHUTDOWN_TIMEOUT)
                        .build();

        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(processor)
                .build()) {

            Tracer tracer = provider.get(TRACER_SCOPE);

            // when
            tracer.spanBuilder("pending-export").startSpan().end();

            // then: экспорт начался и завис до истечения exportTimeout
            assertThat(exporter.exportStarted.await(AWAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            awaitExportTimeoutRecorded(processor);

            // shutdown не может завершиться успешно, пока export() всё ещё выполняется
            CompletableResultCode shutdownResult = processor.shutdown();
            assertThat(shutdownResult.join(AWAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS).isSuccess()).isFalse();
            assertThat(exporter.shutdownCalled.getCount()).isOne();

            // после завершения зависшего export() shutdown экспортёра вызывается ровно один раз
            exporter.pendingExport.succeed();
            assertThat(exporter.shutdownCalled.await(AWAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            assertThat(exporter.shutdownWhileExportPending).isFalse();
            assertThat(exporter.shutdownCalls.get()).isEqualTo(1);
        } finally {
            exporter.pendingExport.succeed();
        }
    }

    @SuppressWarnings("SameParameterValue")
    private static ReadWriteSpan mockSpanWithContext(String traceId, String spanId) {
        ReadWriteSpan span = mock(ReadWriteSpan.class);
        SpanContext context = SpanContext.create(traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault());
        when(span.getSpanContext()).thenReturn(context);
        return span;
    }

    private static void awaitExportTimeoutRecorded(PlatformDropOldestExportSpanProcessor processor) {
        long deadline = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
        while (processor.metricsSnapshot().timeoutsTotal() == 0 && System.nanoTime() < deadline) {
            LockSupport.parkNanos(POLL_INTERVAL.toNanos());
        }
        assertThat(processor.metricsSnapshot().timeoutsTotal()).isEqualTo(1);
    }

    /**
     * Делегат, гарантированно бросающий исключение с чувствительным текстом из onStart —
     * используется для проверки, что composite-диагностика не раскрывает span name
     * или сообщение исключения в логах/метаданных событий.
     */
    private static final class SensitiveFailureProcessor implements SpanProcessor, ProcessorDiagnosticIdentity {
        @Override
        public String diagnosticId() {
            return "test_sensitive_failure";
        }

        @Override
        public void onStart(Context parentContext, ReadWriteSpan span) {
            throw new IllegalStateException(SENSITIVE_EXCEPTION_MESSAGE);
        }

        @Override
        public boolean isStartRequired() {
            return true;
        }

        @Override
        public void onEnd(ReadableSpan span) {
        }

        @Override
        public boolean isEndRequired() {
            return false;
        }
    }

    /**
     * {@link SpanExporter}, чей {@link #export(Collection)} зависает до явного вызова
     * {@link CompletableResultCode#succeed()} на возвращённом future — используется, чтобы
     * детерминированно воспроизвести состояние "export ещё выполняется во время shutdown".
     * <p>
     * {@code shutdownCalls} — {@link AtomicInteger}, а не {@code volatile int}: {@code shutdown()}
     * потенциально может быть вызван из нескольких потоков (явный вызов в тесте и внутренний
     * вызов из {@code SdkTracerProvider#close()}), а инкремент {@code volatile int} не атомарен.
     */
    private static final class PendingExporter implements SpanExporter {
        private final CountDownLatch exportStarted = new CountDownLatch(1);
        private final CountDownLatch shutdownCalled = new CountDownLatch(1);
        private final CompletableResultCode pendingExport = new CompletableResultCode();
        private final AtomicBoolean exportPending = new AtomicBoolean();
        private final AtomicInteger shutdownCalls = new AtomicInteger();
        private volatile boolean shutdownWhileExportPending;

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            exportPending.set(true);
            exportStarted.countDown();
            pendingExport.whenComplete(() -> exportPending.set(false));
            return pendingExport;
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            shutdownCalls.incrementAndGet();
            shutdownWhileExportPending = exportPending.get();
            shutdownCalled.countDown();
            return CompletableResultCode.ofSuccess();
        }
    }
}
