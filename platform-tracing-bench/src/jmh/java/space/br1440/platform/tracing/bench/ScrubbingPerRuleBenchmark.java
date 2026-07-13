package space.br1440.platform.tracing.bench;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.extension.scrubbing.BuiltInSpanAttributeScrubbingRules;
import space.br1440.platform.tracing.otel.extension.scrubbing.ScrubbingSnapshot;
import space.br1440.platform.tracing.otel.extension.scrubbing.engine.MergeEngine;
import space.br1440.platform.tracing.otel.extension.scrubbing.engine.RuleExecutionWrapper;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Per-rule стоимость scrubbing-правил (Фаза 17, PR-1/PR-2; REQ-DATA-001).
 * <p>
 * {@code @Param} по имени built-in правила: стоимость ОДНОГО правила на значении, которое
 * оно гарантированно матчит (hit-путь). Цель — ранжирование правил по стоимости для
 * decision tree ADR-scrubbing-cost: топ-дорогие правила — кандидаты на оптимизацию
 * (key-first отсечение, упрощение regex) или off-by-default в performance-профиле.
 * <p>
 * Намеренно отдельный класс от {@code ScrubbingEngineBenchmark}: full-set сценарии не
 * зависят от {@code @Param} и не должны гоняться 12 раз (длительность сюиты).
 * <p>
 * Контракт fixtures (hit, не miss) охраняется {@code ScrubbingBenchmarkFixtureContractTest}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ScrubbingPerRuleBenchmark {

    /**
     * Полный реестр built-in правил (configName из {@code BuiltInSpanAttributeScrubbingRules}).
     * Литералы обязаны совпадать с enum'ом — расхождение упадёт в {@code setUp} fail-fast.
     */
    @Param({
            "oauth-header", "x-auth-header", "jwt", "webhook-token", "password",
            "infra-credential", "ssh-credential", "location", "hardware-identity",
            "user-identity", "email", "ip-address"
    })
    public String ruleName;

    private List<RuleExecutionWrapper> singleRuleWrappers;
    private String hitKey;
    private Object hitValue;

    @Setup(Level.Trial)
    public void setUp() {
        SpanAttributeScrubbingRule rule = BuiltInSpanAttributeScrubbingRules.resolve(ruleName);
        if (rule == null) {
            throw new IllegalStateException("Неизвестное имя правила в @Param: " + ruleName);
        }
        singleRuleWrappers = ScrubbingSnapshot.compileWrappers(List.of(rule));
        ScrubbingFixtures.HitFixture fixture = ScrubbingFixtures.hitFixtureFor(ruleName);
        // Нормализация ключа как в ScrubbingSpanProcessor.normalizeForSpi (lowercase+trim) —
        // метод package-private, поэтому дублируем контракт здесь.
        hitKey = fixture.key().toLowerCase(Locale.ROOT).trim();
        hitValue = fixture.value();
    }

    /** Одно правило на матчащемся значении — поиск дорогих правил. */
    @Benchmark
    public void perRuleHit(Blackhole bh) {
        bh.consume(MergeEngine.evaluate(singleRuleWrappers, hitKey, hitValue));
    }
}
