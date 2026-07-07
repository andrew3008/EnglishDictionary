package space.br1440.platform.tracing.bench;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;
import space.br1440.platform.tracing.api.spi.SensitiveDataRule;
import space.br1440.platform.tracing.otel.extension.scrubbing.BuiltInSensitiveDataRules;
import space.br1440.platform.tracing.otel.extension.scrubbing.ScrubbingSnapshot;
import space.br1440.platform.tracing.otel.extension.scrubbing.engine.MergeEngine;
import space.br1440.platform.tracing.otel.extension.scrubbing.engine.RuleExecutionWrapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Декомпозиция стоимости scrubbing-движка на полном default-наборе правил
 * (Фаза 17, PR-1/PR-2; REQ-DATA-001, инвариант P4).
 * <p>
 * Wave B показала: scrubbing — доминирующая статья расхода span-pipeline
 * (+11.7 µs / +15.7 KB на span сверх H1-slice, см. h1-composite-pipeline-jmh-baseline.md).
 * Сценарии отвечают на вопросы ADR-scrubbing-cost:
 * <ul>
 *   <li><b>cleanKeysFullSet</b> — полный default-набор правил на «чистом» ключе/значении
 *       (ничего не матчится). Это hot-path подавляющего большинства атрибутов: если он
 *       не близок к allocation-free — key-first отсечение до regex не работает;</li>
 *   <li><b>jwtHitFullSet</b> — полный набор на JWT-значении (типичный hit);</li>
 *   <li><b>longValueCleanFullSet</b> — «чистое» значение длиной ~2000 символов: цена
 *       сканирования длинных строк (decision tree: truncate ДО regex, защита P4
 *       от catastrophic backtracking).</li>
 * </ul>
 * Движок вызывается напрямую ({@code MergeEngine.evaluate} над wrappers из
 * {@code ScrubbingSnapshot.compileWrappers}) — без SDK/span'а, чтобы изолировать
 * стоимость правил от {@code toSpanData()} (последний уже измерен в
 * {@code CompositePipelineBenchmark}). Per-rule ранжирование — в
 * {@code ScrubbingPerRuleBenchmark} (отдельный класс, чтобы full-set сценарии
 * не гонялись по разу на каждый {@code @Param}).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ScrubbingEngineBenchmark {

    private List<RuleExecutionWrapper> fullSetWrappers;
    private String cleanKey;
    private String cleanValue;
    private String longCleanValue;
    private String jwtKey;
    private String jwtValue;

    @Setup(Level.Trial)
    public void setUp() {
        List<SensitiveDataRule> defaults = new ArrayList<>();
        for (String name : BuiltInSensitiveDataRules.defaultConfigNames()) {
            SensitiveDataRule r = BuiltInSensitiveDataRules.resolve(name);
            if (r != null) {
                defaults.add(r);
            }
        }
        fullSetWrappers = ScrubbingSnapshot.compileWrappers(defaults);

        cleanKey = "http.route";
        cleanValue = "/api/v1/orders/{id}";
        // ~2000 символов «чистого» текста: цена сканирования длинной строки полным набором.
        longCleanValue = "orders payload segment ".repeat(90);
        jwtKey = "http.request.header.authorization";
        jwtValue = "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
                + ".eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6ImJlbmNoIn0"
                + ".SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJVadQssw5c";
    }

    /** Hot-path большинства атрибутов: полный набор, ничего не матчится. */
    @Benchmark
    public void cleanKeysFullSet(Blackhole bh) {
        bh.consume(MergeEngine.evaluate(fullSetWrappers, cleanKey, cleanValue));
    }

    /** Типичный hit: JWT в authorization-заголовке, полный набор. */
    @Benchmark
    public void jwtHitFullSet(Blackhole bh) {
        bh.consume(MergeEngine.evaluate(fullSetWrappers, jwtKey, jwtValue));
    }

    /** Длинное «чистое» значение (~2000 chars): стоимость сканирования без hit'а. */
    @Benchmark
    public void longValueCleanFullSet(Blackhole bh) {
        bh.consume(MergeEngine.evaluate(fullSetWrappers, cleanKey, longCleanValue));
    }
}
