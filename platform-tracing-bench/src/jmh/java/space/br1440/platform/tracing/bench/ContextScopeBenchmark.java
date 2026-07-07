package space.br1440.platform.tracing.bench;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Стоимость Context/Scope-операций (Фаза 17, PR-1; вход в микробюджет span.start/end).
 * <p>
 * {@code Context.current()} и {@code span.makeCurrent()} выполняются на каждом запросе
 * (часто многократно: фильтры, аспекты, propagation-фасад). ThreadLocal-механика OTel
 * Context — скрытая статья расхода hot-path, не видимая в span-бенчмарках напрямую.
 * <ul>
 *   <li><b>contextCurrent</b> — чистый {@code Context.current()} (ThreadLocal read);</li>
 *   <li><b>makeCurrentNested</b> — вложенные {@code makeCurrent()} глубиной
 *       {@code @Param 1/3/10}: типичный стек фильтр → аспект → builder.
 *       Глубина 10 — проверка отсутствия нелинейной деградации.</li>
 * </ul>
 */
@State(org.openjdk.jmh.annotations.Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ContextScopeBenchmark {

    @Param({"1", "3", "10"})
    public int depth;

    private Span span;

    @Setup(Level.Trial)
    public void setUp() {
        // Невалидный recording не нужен: makeCurrent() оперирует Context'ом, а не SDK-спаном.
        span = Span.wrap(SpanContext.create(
                "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331",
                TraceFlags.getSampled(), TraceState.getDefault()));
    }

    /** ThreadLocal-чтение текущего контекста. */
    @Benchmark
    public void contextCurrent(Blackhole bh) {
        bh.consume(Context.current());
    }

    /** Вложенные scope'ы глубиной depth: вход и выход в прямом/обратном порядке. */
    @Benchmark
    public void makeCurrentNested(Blackhole bh) {
        nest(depth, bh);
    }

    private void nest(int remaining, Blackhole bh) {
        if (remaining == 0) {
            bh.consume(Context.current());
            return;
        }
        try (Scope scope = span.makeCurrent()) {
            nest(remaining - 1, bh);
        }
    }
}
