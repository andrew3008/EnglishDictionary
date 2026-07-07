package space.br1440.platform.tracing.semconv.lint;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Движок линтера: применяет набор {@link LintRule} к коллекции {@link SpanRecord} и возвращает
 * {@link LintReport}.
 * <p>
 * Один экземпляр линтера потоко-безопасен и может переиспользоваться между прогонами.
 */
public final class Linter {

    private final List<LintRule> rules;

    public Linter(Collection<LintRule> rules) {
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
    }

    /** Список зарегистрированных правил — для отчётности. */
    public List<LintRule> rules() {
        return rules;
    }

    /**
     * Применяет все правила к каждому span'у и возвращает сводный отчёт.
     */
    public LintReport lint(Collection<SpanRecord> spans) {
        Objects.requireNonNull(spans, "spans");
        List<LintViolation> violations = new ArrayList<>();
        for (SpanRecord span : spans) {
            for (LintRule rule : rules) {
                rule.apply(span).ifPresent(violations::add);
            }
        }
        return new LintReport(spans.size(), violations);
    }
}
