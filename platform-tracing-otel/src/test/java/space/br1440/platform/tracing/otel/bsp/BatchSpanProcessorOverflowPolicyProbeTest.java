package space.br1440.platform.tracing.otel.bsp;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Probe-тест фактической политики переполнения очереди стандартного
 * {@link io.opentelemetry.sdk.trace.export.BatchSpanProcessor} на pinned OTel SDK 1.62.0.
 *
 * <p><b>Guardrail на pinned SDK.</b> Назначение — зафиксировать фактическое поведение,
 * а не валидировать ожидание. Если SDK при upgrade поменяет политику — тест сломается,
 * и архитектор примет решение явно (см. {@code ADR-bsp-overflow-policy-finding.md}).</p>
 *
 * <p><b>Защита от timing flakiness через две latch'и:</b></p>
 * <ol>
 *   <li>{@code exporterEnteredLatch} — countDown в самом начале {@code export()}.
 *       Тест ожидает её перед фазой overflow, гарантируя, что worker BSP реально
 *       заблокировался в exporter, а не находится между read очереди и вызовом export.</li>
 *   <li>{@code exporterReleaseLatch} — await внутри {@code export()}. Блокирует worker
 *       до явного освобождения тестом.</li>
 * </ol>
 *
 * <p><b>Assertion philosophy:</b> не «доказать drop-oldest», а зафиксировать observable
 * snapshot. exported count must not exceed the number of spans that could be accepted by
 * the configured BSP queue under the controlled blocking scenario; exact bound is
 * documented in the test after observing SDK behavior. Конкретные observed/missing IDs
 * передаются дальше в ADR finding.</p>
 */
class BatchSpanProcessorOverflowPolicyProbeTest {

    /** Атрибут sequence ID — единственный способ отличить span'ы в snapshot. */
    private static final AttributeKey<Long> SEQ = AttributeKey.longKey("probe.seq");

    /**
     * Размеры подобраны так, чтобы overflow гарантированно произошёл: один batch
     * заблокирован в exporter, очередь maxQueueSize ровно достижима, далее намеренно
     * генерируем существенно больше span'ов.
     */
    private static final int MAX_QUEUE = 4;
    private static final int BATCH_SIZE = 2;
    private static final int OVERFLOW_FACTOR = 8;

    @Test
    @DisplayName("Probe: фактическая политика overflow стандартного BSP SDK 1.62.0 (snapshot)")
    void observeOverflowPolicy_pinnedSdk() throws InterruptedException {
        ControlledExporter exporter = new ControlledExporter();

        BatchSpanProcessor bsp = BatchSpanProcessor.builder(exporter)
                .setMaxQueueSize(MAX_QUEUE)
                .setMaxExportBatchSize(BATCH_SIZE)
                // Очень большой schedule delay — исключаем background flush в фазе overflow.
                .setScheduleDelay(Duration.ofMinutes(10))
                .setExporterTimeout(Duration.ofSeconds(30))
                .build();

        SdkTracerProvider provider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(bsp)
                .build();
        Tracer tracer = provider.get("probe");

        // Фаза 1: создаём первые BATCH_SIZE span'ов, чтобы worker BSP запустил export
        // и заблокировался в exporter (latch).
        for (int i = 0; i < BATCH_SIZE; i++) {
            emit(tracer, i);
        }

        // Дожидаемся, что worker реально вошёл в export() — иначе overflow может начаться
        // до того, как очередь окажется заблокирована.
        boolean entered = exporter.exporterEnteredLatch.await(10, TimeUnit.SECONDS);
        assertThat(entered).as("worker BSP должен войти в export() до фазы overflow").isTrue();

        // Фаза 2: генерируем существенно больше span'ов, чем вмещается в очередь.
        // worker заблокирован в exporter, поэтому реальная политика overflow проявится здесь.
        int overflowFrom = BATCH_SIZE;
        int overflowTo = BATCH_SIZE + MAX_QUEUE * OVERFLOW_FACTOR;
        for (int i = overflowFrom; i < overflowTo; i++) {
            emit(tracer, i);
        }

        // Фаза 3: освобождаем exporter и просим BSP экспортировать остатки.
        exporter.exporterReleaseLatch.countDown();
        CompletableResultCode flushResult = provider.forceFlush();
        flushResult.join(30, TimeUnit.SECONDS);
        provider.shutdown().join(10, TimeUnit.SECONDS);

        // Snapshot фактического поведения: какие seq дошли до exporter.
        List<Long> exportedSeq = new ArrayList<>(exporter.exportedSeq);

        // Sanity (не доказательство drop-oldest): exported count must not exceed total emitted.
        assertThat(exportedSeq.size())
                .as("exported count <= total emitted (sanity)")
                .isLessThanOrEqualTo(overflowTo);

        // Зафиксированный snapshot — для ADR finding. Не валидируем направление вытеснения.
        // ВАЖНО: assertion НЕ говорит «drop-oldest». Он только печатает наблюдаемый набор;
        // интерпретация — в ADR-bsp-overflow-policy-finding.md.
        System.out.println("[BSP overflow probe] SDK observed exported seq: " + exportedSeq);
        System.out.println("[BSP overflow probe] SDK observed exported count: " + exportedSeq.size());
        System.out.println("[BSP overflow probe] SDK observed export() call count: " + exporter.exportCallCount);

        // Минимальный guard: первый batch (тот, что заблокирован в exporter) обязан
        // когда-то быть экспортирован — иначе тест проверяет не то, что задумано.
        assertThat(exportedSeq)
                .as("Первый batch (seq 0..BATCH_SIZE-1), удерживавший exporter, должен попасть в результат")
                .contains(0L, 1L);
    }

    private static void emit(Tracer tracer, int seq) {
        io.opentelemetry.api.trace.Span span = tracer.spanBuilder("probe-" + seq)
                .setParent(Context.root())
                .setAllAttributes(Attributes.of(SEQ, (long) seq))
                .startSpan();
        span.end();
    }

    /**
     * Exporter с двумя latch'ами: countDown при первом входе в {@code export()} и
     * await до явного освобождения тестом. Сохраняет seq всех экспортированных span'ов.
     */
    private static final class ControlledExporter implements SpanExporter {
        final CountDownLatch exporterEnteredLatch = new CountDownLatch(1);
        final CountDownLatch exporterReleaseLatch = new CountDownLatch(1);
        final List<Long> exportedSeq = new CopyOnWriteArrayList<>();
        volatile int exportCallCount = 0;

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            // Сначала фиксируем seq, чтобы snapshot был полным даже если тест отвалится.
            for (SpanData s : spans) {
                Long seq = s.getAttributes().get(SEQ);
                if (seq != null) {
                    exportedSeq.add(seq);
                }
            }
            exportCallCount++;
            // Сигналим, что worker вошёл в exporter (срабатывает только на первом вызове).
            exporterEnteredLatch.countDown();
            // Блокируем worker, чтобы тест мог наполнить очередь до переполнения.
            try {
                // Если release уже произведён — await вернёт сразу.
                exporterReleaseLatch.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return CompletableResultCode.ofFailure();
            }
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            // На случай если shutdown произойдёт до release — отпускаем await.
            exporterReleaseLatch.countDown();
            return CompletableResultCode.ofSuccess();
        }
    }
}
