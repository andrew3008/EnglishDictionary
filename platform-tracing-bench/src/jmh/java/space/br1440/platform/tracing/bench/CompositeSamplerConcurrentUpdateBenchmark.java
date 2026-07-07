package space.br1440.platform.tracing.bench;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;
import space.br1440.platform.tracing.otel.extension.sampler.CompositeSampler;
import space.br1440.platform.tracing.otel.extension.sampler.SamplerState;
import space.br1440.platform.tracing.otel.extension.sampler.SamplerStateHolder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Асимметричный stress-бенчмарк: {@code shouldSample()} под непрерывными конкурентными
 * runtime-апдейтами конфигурации (Фаза 17, PR-E; ADR-runtime-sampling-policy, инварианты C-2/C-4/C-7).
 * <p>
 * JMH {@code @Group}: 7 reader-потоков гоняют hot-path, 1 writer-поток непрерывно публикует
 * новые снимки через {@code SamplerStateHolder.tryUpdate} (CAS). Это <b>жёстче</b> реальной
 * эксплуатации (операторские апдейты — единицы в минуту, не тысячи в секунду) — намеренный
 * верхний стресс-режим:
 * <ul>
 *   <li>метрика {@code sample} — латентность чтения под write-штормом; рост против
 *       {@code CompositeSamplerBenchmark.ratioDropContended} (read-only контеншн) означал бы,
 *       что апдейты стопорят hot-path — нарушение инварианта C-2;</li>
 *   <li>метрика {@code update} — стоимость построения и CAS-публикации снимка
 *       (компиляция ratio-сэмплеров, нормализация) — подтверждение, что «дорогая» часть
 *       reload'а лежит вне hot-path (инвариант C-3).</li>
 * </ul>
 * Macro-эквивалент: перф-сценарий M10c (config storm под HTTP-нагрузкой).
 */
@State(Scope.Group)
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class CompositeSamplerConcurrentUpdateBenchmark {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

    private SamplerStateHolder holder;
    private CompositeSampler sampler;
    private Context rootContext;
    private Attributes attributes;

    @Setup(Level.Trial)
    public void setUp() {
        holder = new SamplerStateHolder(true, List.of(), List.of("on"), Map.of(), 0.0);
        sampler = new CompositeSampler(holder);
        rootContext = Context.root();
        attributes = Attributes.builder()
                .put("http.request.method", "GET")
                .put("url.path", "/api/v1/orders")
                .build();
    }

    /** Hot-path: 7 потоков читают решение, пока writer публикует снимки. */
    @Benchmark
    @Group("readWhileUpdate")
    @GroupThreads(7)
    public void sample(Blackhole bh) {
        bh.consume(sampler.shouldSample(rootContext, TRACE_ID, "GET /api/v1/orders",
                SpanKind.SERVER, attributes, List.of()));
    }

    /**
     * Writer: построение валидного снимка + CAS-публикация. Меняется только
     * {@code forceRecordValues} (от чётности версии) — путь reader'ов при этом
     * идентичен на каждом вызове (control в контексте нет, ratio константен 0.0 →
     * детерминированный full-chain DROP), поэтому метрика {@code sample} не смешивает
     * ветки и сравнима с {@code ratioDropContended} read-only бенча.
     * Builder side-effect-free — контракт {@code VersionedStateHolder}
     * (при contention вызывается повторно).
     */
    @Benchmark
    @Group("readWhileUpdate")
    @GroupThreads(1)
    public void update(Blackhole bh) {
        boolean applied = holder.tryUpdate(prev -> new SamplerState(
                true,
                List.of(),
                (prev.version() % 2 == 0) ? Set.of("on") : Set.of("on", "debug"),
                Map.of(),
                0.0,
                prev.version() + 1,
                Instant.now(),
                "jmh"));
        bh.consume(applied);
    }
}
