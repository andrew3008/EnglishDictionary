package space.br1440.platform.tracing.otel.extension.scrubbing;

import space.br1440.platform.tracing.core.runtime.versioned.VersionedState;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.extension.scrubbing.circuitbreaker.RuleCircuitBreaker;
import space.br1440.platform.tracing.otel.extension.scrubbing.engine.PriorityHardening;
import space.br1440.platform.tracing.otel.extension.scrubbing.engine.RuleExecutionWrapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Иммутабельный снимок политики scrubbing'а (Фаза 14).
 * <p>
 * Несёт runtime-флаг {@code enabled} и <b>скомпилированные</b> обёртки правил
 * ({@link RuleExecutionWrapper}, отсортированные/clamp'нутые через {@link PriorityHardening}).
 * Регулярные выражения правил компилируются при построении снимка (в конструкторах правил), а не
 * на hot-path {@code onEnding} — это и оптимизация, и точка валидации (битый/катастрофический
 * regex отлавливается до публикации → last-known-good).
 * <p>
 * Реализует {@link VersionedState}: {@code version} — монотонное поле, публикуется через CAS.
 */
public final class ScrubbingSnapshot implements VersionedState {

    private final boolean enabled;
    private final List<RuleExecutionWrapper> wrappers;
    private final long version;
    private final Instant updatedAt;
    private final String source;

    public ScrubbingSnapshot(boolean enabled,
                             List<RuleExecutionWrapper> wrappers,
                             long version,
                             Instant updatedAt,
                             String source) {
        this.enabled = enabled;
        this.wrappers = wrappers == null ? List.of() : List.copyOf(wrappers);
        this.version = version;
        this.updatedAt = updatedAt;
        this.source = source;
    }

    /**
     * Startup / reload path (PR-7A): validate rule list, compile wrappers (regex compile happens in
     * rule constructors / {@link #compileWrappers}), return immutable snapshot. Throws on null rule
     * entries (startup fail-fast, same as invalid rule construction).
     */
    public static ScrubbingSnapshot fromRules(boolean enabled,
                                              List<SpanAttributeScrubbingRule> rules,
                                              long version,
                                              Instant updatedAt,
                                              String source) {
        if (rules == null) {
            throw new NullPointerException("rules");
        }
        for (SpanAttributeScrubbingRule rule : rules) {
            if (rule == null) {
                throw new NullPointerException("rules must not contain null entries");
            }
        }
        return new ScrubbingSnapshot(enabled, compileWrappers(rules), version, updatedAt, source);
    }

    /**
     * Компилирует обёртки правил из набора {@link SpanAttributeScrubbingRule}: каждое правило получает свой
     * {@link RuleCircuitBreaker}, список clamp'ится и детерминированно сортируется. Side-effect-free
     * (создаёт новые объекты), пригодна для CAS-цикла {@code tryUpdate}.
     */
    public static List<RuleExecutionWrapper> compileWrappers(List<SpanAttributeScrubbingRule> rules) {
        List<RuleExecutionWrapper> raw = new ArrayList<>(rules.size());
        for (SpanAttributeScrubbingRule rule : rules) {
            raw.add(new RuleExecutionWrapper(rule, new RuleCircuitBreaker(rule.name())));
        }
        return PriorityHardening.sortAndClamp(raw);
    }

    public boolean enabled() {
        return enabled;
    }

    public List<RuleExecutionWrapper> wrappers() {
        return wrappers;
    }

    @Override
    public long version() {
        return version;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public String source() {
        return source;
    }
}
