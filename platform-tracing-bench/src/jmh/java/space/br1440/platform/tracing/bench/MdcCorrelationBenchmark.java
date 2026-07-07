package space.br1440.platform.tracing.bench;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;
import org.slf4j.MDC;
import space.br1440.platform.tracing.api.mdc.RemoteServiceMdc;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;

import java.util.concurrent.TimeUnit;

/**
 * Стоимость MDC-корреляции (Фаза 17, PR-1; скрытый источник аллокаций hot-path).
 * <p>
 * MDC put/remove выполняется на каждом запросе (traceId/spanId/correlation_id) и при
 * enrichment'е ({@code platform.remote.service}). Реализация MDC (logback {@code LogbackMDCAdapter}
 * на runtime-classpath бенча, как в проде) копирует map при модификации — это
 * аллокационная статья, не видимая в span-бенчмарках.
 * <ul>
 *   <li><b>putRemoveTraceTriplet</b> — традиционный цикл запроса: put traceId/spanId/flags,
 *       затем remove (паттерн agent-инструментаций и logging-фильтров);</li>
 *   <li><b>remoteServiceMdcCycle</b> — платформенный {@code RemoteServiceMdc.putIfPresent}
 *       + {@code clear} (зеркала Wave 4, кандидат на проверку в M9 soak).</li>
 * </ul>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class MdcCorrelationBenchmark {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String SPAN_ID = "b7ad6b7169203331";

    /** Полный MDC-цикл запроса: put тройки идентификаторов + remove. */
    @Benchmark
    public void putRemoveTraceTriplet(Blackhole bh) {
        MDC.put(TracingMdcKeys.TRACE_ID, TRACE_ID);
        MDC.put(TracingMdcKeys.SPAN_ID, SPAN_ID);
        MDC.put(TracingMdcKeys.TRACE_FLAGS, "01");
        bh.consume(MDC.get(TracingMdcKeys.TRACE_ID));
        MDC.remove(TracingMdcKeys.TRACE_ID);
        MDC.remove(TracingMdcKeys.SPAN_ID);
        MDC.remove(TracingMdcKeys.TRACE_FLAGS);
    }

    /** Платформенное MDC-зеркало remote.service: putIfPresent + clear. */
    @Benchmark
    public void remoteServiceMdcCycle(Blackhole bh) {
        RemoteServiceMdc.putIfPresent("orders-service", TRACE_ID);
        bh.consume(MDC.get(TracingMdcKeys.REMOTE_SERVICE));
        RemoteServiceMdc.clearForTrace(TRACE_ID);
    }
}
