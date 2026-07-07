package space.br1440.platform.tracing.bench;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanLimits;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Стоимость enforcement'а SpanLimits (Фаза 17, PR-1; REQ-DATA-001, §2.1–2.3 требований).
 * <p>
 * Платформенные дефолты (через {@code PlatformTracingDefaultsProvider}): 50 атрибутов,
 * 1000 символов значения, 10 событий. Бенчмарк измеряет цену соблюдения и превышения лимитов:
 * <ul>
 *   <li><b>attrs50AtLimit</b> — ровно на лимите (всё сохраняется);</li>
 *   <li><b>attrs51OverLimit</b> — лимит +1 (ветка отбрасывания лишнего атрибута);</li>
 *   <li><b>valueTruncation1000</b> — значение 2000 символов при лимите 1000
 *       (цена усечения строки на set);</li>
 *   <li><b>events10AtLimit / events11OverLimit</b> — событийный лимит.</li>
 * </ul>
 * Важно: превышение лимитов — НЕ потеря span'а (span доезжает усечённым), поэтому
 * эти ветки не входят в {@code DroppedSpanReason}-таксономию (PR-5).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class SpanLimitsBenchmark {

    private OpenTelemetrySdk sdk;
    private Tracer tracer;
    private String longValue;

    /** Заранее построенные ключи: исключаем конкатенацию строк из измеряемого пути. */
    private String[] attributeKeys;

    @Setup(Level.Trial)
    public void setUp() {
        // Платформенные дефолты §2.1–2.3 (см. TracingProperties.Limits / traceability.md).
        SpanLimits limits = SpanLimits.builder()
                .setMaxNumberOfAttributes(50)
                .setMaxAttributeValueLength(1000)
                .setMaxNumberOfEvents(10)
                .build();
        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .setSpanLimits(limits)
                        .build())
                .build();
        tracer = sdk.getTracer("bench");
        longValue = "x".repeat(2000);
        attributeKeys = new String[51];
        for (int i = 0; i < attributeKeys.length; i++) {
            attributeKeys[i] = "platform.bench.attr." + i;
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        sdk.close();
    }

    @Benchmark
    public void attrs50AtLimit(Blackhole bh) {
        Span span = tracer.spanBuilder("bench-op").startSpan();
        for (int i = 0; i < 50; i++) {
            span.setAttribute(attributeKeys[i], "value");
        }
        span.end();
        bh.consume(span);
    }

    @Benchmark
    public void attrs51OverLimit(Blackhole bh) {
        Span span = tracer.spanBuilder("bench-op").startSpan();
        for (int i = 0; i < 51; i++) {
            span.setAttribute(attributeKeys[i], "value");
        }
        span.end();
        bh.consume(span);
    }

    @Benchmark
    public void valueTruncation1000(Blackhole bh) {
        Span span = tracer.spanBuilder("bench-op").startSpan();
        span.setAttribute("platform.bench.payload", longValue);
        span.end();
        bh.consume(span);
    }

    @Benchmark
    public void events10AtLimit(Blackhole bh) {
        Span span = tracer.spanBuilder("bench-op").startSpan();
        for (int i = 0; i < 10; i++) {
            span.addEvent("bench-event");
        }
        span.end();
        bh.consume(span);
    }

    @Benchmark
    public void events11OverLimit(Blackhole bh) {
        Span span = tracer.spanBuilder("bench-op").startSpan();
        for (int i = 0; i < 11; i++) {
            span.addEvent("bench-event");
        }
        span.end();
        bh.consume(span);
    }
}
