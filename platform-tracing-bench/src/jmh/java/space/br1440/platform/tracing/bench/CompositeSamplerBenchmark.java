package space.br1440.platform.tracing.bench;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.infra.Blackhole;
import space.br1440.platform.tracing.api.propagation.control.PlatformTraceContextKeys;
import space.br1440.platform.tracing.api.propagation.control.PlatformTraceControl;
import space.br1440.platform.tracing.otel.extension.sampler.CompositeSampler;
import space.br1440.platform.tracing.otel.extension.sampler.SamplerStateHolder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Микробюджет hot-path сэмплера (Фаза 17, PR-1; REQ-SAMPLING-001, инвариант P5).
 * <p>
 * {@code CompositeSampler.shouldSample(...)} вызывается на старте КАЖДОГО span'а —
 * это самый горячий участок платформенного кода после самого SDK. Бенчмарк измеряет
 * три репрезентативные ветки цепочки правил (см. порядок в {@code CompositeSampler}):
 * <ul>
 *   <li><b>force-header</b> — {@code X-Trace-On: on} в {@code PlatformTraceControl}
 *       (ранний выход через {@code ForceHeaderRule});</li>
 *   <li><b>parent-sampled</b> — решение наследуется от удалённого родителя
 *       ({@code ParentDecisionRule}, типичный путь внутреннего сервиса);</li>
 *   <li><b>ratio-drop</b> — полный проход цепочки до {@code DefaultRatioRule}
 *       с ratio 0.0 (худший случай по длине пути без записи).</li>
 * </ul>
 * Контеншн: {@code @Threads(1)} и {@code @Threads(8)} — {@code SamplerStateHolder.current()}
 * обязан оставаться lock-free (atomic snapshot, Фаза 14); деградация p99 под потоками
 * означала бы нарушение инварианта P5.
 * <p>
 * Режимы: {@code AverageTime} — для сравнения с baseline ({@code jmhCompareBaseline});
 * {@code SampleTime} — p50/p95/p99 для микробюджета (статпротокол ADR-performance-model).
 */
@State(Scope.Benchmark)
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class CompositeSamplerBenchmark {

    /** Валидные W3C идентификаторы: нулевые ID дисквалифицируют SpanContext. */
    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String SPAN_ID = "b7ad6b7169203331";

    private CompositeSampler sampler;
    private Context forceHeaderContext;
    private Context parentSampledContext;
    private Context rootContext;
    private Attributes attributes;

    @Setup(Level.Trial)
    public void setUp() {
        // Production-like состояние: enabled, force-значение "on", default ratio 0.0 —
        // гарантирует, что ветка ratio-drop детерминированно доходит до DROP
        // (при 0.1 решение зависело бы от traceId и бенчмарк мерил бы смесь веток).
        SamplerStateHolder holder = new SamplerStateHolder(
                true, List.of(), List.of("on"), Map.of(), 0.0);
        sampler = new CompositeSampler(holder);

        // Ветка 1: форсированная запись через X-Trace-On (см. CompositeSamplerTest.withControl).
        forceHeaderContext = Context.root().with(
                PlatformTraceContextKeys.TRACE_CONTROL,
                new PlatformTraceControl(true, false, null, "x_trace_on", "on"));

        // Ветка 2: удалённый sampled-родитель — ParentDecisionRule наследует решение.
        parentSampledContext = Context.root().with(
                Span.wrap(SpanContext.createFromRemoteParent(
                        TRACE_ID, SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault())));

        // Ветка 3: корневой запрос без управляющих заголовков — полный проход цепочки.
        rootContext = Context.root();

        attributes = Attributes.builder()
                .put("http.request.method", "GET")
                .put("url.path", "/api/v1/orders")
                .build();
    }

    // -- Однопоточный микробюджет --------------------------------------------------------

    @Benchmark
    @Threads(1)
    public void forceHeader(Blackhole bh) {
        bh.consume(sampler.shouldSample(forceHeaderContext, TRACE_ID, "GET /api/v1/orders",
                SpanKind.SERVER, attributes, List.of()));
    }

    @Benchmark
    @Threads(1)
    public void parentSampled(Blackhole bh) {
        bh.consume(sampler.shouldSample(parentSampledContext, TRACE_ID, "GET /api/v1/orders",
                SpanKind.SERVER, attributes, List.of()));
    }

    @Benchmark
    @Threads(1)
    public void ratioDrop(Blackhole bh) {
        bh.consume(sampler.shouldSample(rootContext, TRACE_ID, "GET /api/v1/orders",
                SpanKind.SERVER, attributes, List.of()));
    }

    // -- Контеншн: 8 потоков на общем SamplerStateHolder ---------------------------------

    @Benchmark
    @Threads(8)
    public void forceHeaderContended(Blackhole bh) {
        bh.consume(sampler.shouldSample(forceHeaderContext, TRACE_ID, "GET /api/v1/orders",
                SpanKind.SERVER, attributes, List.of()));
    }

    @Benchmark
    @Threads(8)
    public void ratioDropContended(Blackhole bh) {
        bh.consume(sampler.shouldSample(rootContext, TRACE_ID, "GET /api/v1/orders",
                SpanKind.SERVER, attributes, List.of()));
    }
}
