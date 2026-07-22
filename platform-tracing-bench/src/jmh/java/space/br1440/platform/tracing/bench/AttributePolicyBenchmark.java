package space.br1440.platform.tracing.bench;

import io.opentelemetry.api.common.Attributes;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;
import space.br1440.platform.tracing.api.semconv.SemconvValidationMode;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.otel.semconv.policy.AttributePolicy;
import space.br1440.platform.tracing.otel.semconv.policy.SemconvMetrics;

import java.util.concurrent.TimeUnit;

/**
 * Изолированная стоимость {@code AttributePolicy.validateAndNormalize} (Фаза 17, PR-1).
 * <p>
 * Дополняет {@code TypedBuilderBenchmark}: там валидация измеряется в составе builder-пути,
 * здесь — сам вызов политики на готовом наборе атрибутов. Сценарии:
 * <ul>
 *   <li><b>warnValidSet</b> — WARN на валидном HTTP-server наборе (типичный production-путь
 *       без violations);</li>
 *   <li><b>warnMissingRequired</b> — WARN на пустом наборе (путь с violations: аллокации
 *       списка нарушений + safe defaults);</li>
 *   <li><b>disabledValidSet</b> — DISABLED baseline (ожидаемо ≈ passthrough).</li>
 * </ul>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class AttributePolicyBenchmark {

    private AttributePolicy warnPolicy;
    private AttributePolicy disabledPolicy;
    private Attributes validHttpServerAttributes;
    private Attributes emptyAttributes;

    @Setup(Level.Trial)
    public void setUp() {
        warnPolicy = new AttributePolicy(SemconvValidationMode.WARN, false, SemconvMetrics.NOOP);
        disabledPolicy = new AttributePolicy(SemconvValidationMode.DISABLED, false, SemconvMetrics.NOOP);

        validHttpServerAttributes = Attributes.builder()
                .put("http.request.method", "GET")
                .put("http.route", "/users/{id}")
                .put("http.response.status_code", 200L)
                .put("server.address", "orders.svc.cluster.local")
                .build();
        emptyAttributes = Attributes.empty();
    }

    /** Production-default путь: WARN, валидный набор, без violations. */
    @Benchmark
    public void warnValidSet(Blackhole bh) {
        bh.consume(warnPolicy.validateAndNormalize(
                SpanCategory.HTTP_SERVER, validHttpServerAttributes, "HttpServerSpanBuilder"));
    }

    /** Путь с нарушениями: required-атрибуты отсутствуют (аллокации violations + defaults). */
    @Benchmark
    public void warnMissingRequired(Blackhole bh) {
        bh.consume(warnPolicy.validateAndNormalize(
                SpanCategory.HTTP_SERVER, emptyAttributes, "HttpServerSpanBuilder"));
    }

    /** Baseline: DISABLED — ожидаемо близко к passthrough. */
    @Benchmark
    public void disabledValidSet(Blackhole bh) {
        bh.consume(disabledPolicy.validateAndNormalize(
                SpanCategory.HTTP_SERVER, validHttpServerAttributes, "HttpServerSpanBuilder"));
    }
}
