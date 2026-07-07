package space.br1440.platform.tracing.bench;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
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
import space.br1440.platform.tracing.otel.extension.sampler.CompositeSampler;
import space.br1440.platform.tracing.otel.extension.sampler.SamplerStateHolder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Микробюджет policy-веток сэмплера под <b>заполненной</b> production-like политикой
 * (Фаза 17, PR-E; ADR-runtime-sampling-policy, разделы 2.1/3).
 * <p>
 * Дополняет {@link CompositeSamplerBenchmark} (тот измеряет ветки при пустых
 * dropPaths/routeRatios — настройка по умолчанию): здесь dropPaths и routeRatios
 * <b>непусты</b>, поэтому {@code HardDropRule}/{@code RouteRatioRule} реально сканируют
 * префиксы — это стоимость, которую платит каждый span при включённых route-политиках:
 * <ul>
 *   <li><b>dropPath</b> — ранний DROP по префиксу {@code url.path} (приоритет P-2 ADR:
 *       стоит до force header); худший случай — последний префикс списка;</li>
 *   <li><b>routeRatioSample / routeRatioDrop</b> — детерминированные исходы
 *       per-route ratio (1.0 / 0.0), скомпилированные сэмплеры из снимка (инвариант C-3);</li>
 *   <li><b>defaultRatioFullChain</b> — полный проход цепочки до {@code DefaultRatioRule}
 *       при непустой политике: верхняя граница стоимости «обычного» запроса,
 *       не попавшего ни под одно правило.</li>
 * </ul>
 * Отдельный класс (а не {@code @Param} в существующем): добавление dropPaths/routeRatios
 * в общий holder изменило бы измеряемые пути существующих бенчей и сломало бы
 * сравнимость с committed baseline ({@code jmhCompareBaseline}).
 */
@State(Scope.Benchmark)
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class CompositeSamplerPolicyBranchesBenchmark {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

    private CompositeSampler sampler;
    private Context rootContext;

    private Attributes dropPathAttributes;
    private Attributes routeSampleAttributes;
    private Attributes routeDropAttributes;
    private Attributes unmatchedAttributes;

    @Setup(Level.Trial)
    public void setUp() {
        // Production-like политика: 3 drop-префикса (worst case — матч по последнему),
        // 2 route-ratio с детерминированными исходами (1.0 — всегда sample, 0.0 — всегда drop),
        // глобальный ratio 0.0 — полный проход цепочки детерминированно заканчивается DROP.
        SamplerStateHolder holder = new SamplerStateHolder(
                true,
                List.of("/metrics", "/health", "/actuator"),
                List.of("on"),
                Map.of("/api/v1/critical", 1.0, "/api/v1/noisy", 0.0),
                0.0);
        sampler = new CompositeSampler(holder);
        rootContext = Context.root();

        dropPathAttributes = httpAttributes("/actuator/health");
        routeSampleAttributes = httpAttributes("/api/v1/critical/checkout");
        routeDropAttributes = httpAttributes("/api/v1/noisy/search");
        unmatchedAttributes = httpAttributes("/api/v1/orders");
    }

    private static Attributes httpAttributes(String urlPath) {
        return Attributes.builder()
                .put("http.request.method", "GET")
                .put("url.path", urlPath)
                .build();
    }

    // -- Однопоточный микробюджет --------------------------------------------------------

    @Benchmark
    @Threads(1)
    public void dropPath(Blackhole bh) {
        bh.consume(sampler.shouldSample(rootContext, TRACE_ID, "GET /actuator/health",
                SpanKind.SERVER, dropPathAttributes, List.of()));
    }

    @Benchmark
    @Threads(1)
    public void routeRatioSample(Blackhole bh) {
        bh.consume(sampler.shouldSample(rootContext, TRACE_ID, "GET /api/v1/critical/checkout",
                SpanKind.SERVER, routeSampleAttributes, List.of()));
    }

    @Benchmark
    @Threads(1)
    public void routeRatioDrop(Blackhole bh) {
        bh.consume(sampler.shouldSample(rootContext, TRACE_ID, "GET /api/v1/noisy/search",
                SpanKind.SERVER, routeDropAttributes, List.of()));
    }

    @Benchmark
    @Threads(1)
    public void defaultRatioFullChain(Blackhole bh) {
        bh.consume(sampler.shouldSample(rootContext, TRACE_ID, "GET /api/v1/orders",
                SpanKind.SERVER, unmatchedAttributes, List.of()));
    }

    // -- Контеншн: 8 потоков, та же заполненная политика ---------------------------------

    @Benchmark
    @Threads(8)
    public void routeRatioSampleContended(Blackhole bh) {
        bh.consume(sampler.shouldSample(rootContext, TRACE_ID, "GET /api/v1/critical/checkout",
                SpanKind.SERVER, routeSampleAttributes, List.of()));
    }

    @Benchmark
    @Threads(8)
    public void defaultRatioFullChainContended(Blackhole bh) {
        bh.consume(sampler.shouldSample(rootContext, TRACE_ID, "GET /api/v1/orders",
                SpanKind.SERVER, unmatchedAttributes, List.of()));
    }
}
