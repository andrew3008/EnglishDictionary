package space.br1440.platform.tracing.semconv.lint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Сводный отчёт линтера: содержит проверенные span'ы и список найденных нарушений.
 */
public final class LintReport {

    private final int spansChecked;
    private final List<LintViolation> violations;

    public LintReport(int spansChecked, List<LintViolation> violations) {
        this.spansChecked = spansChecked;
        this.violations = Collections.unmodifiableList(new ArrayList<>(violations));
    }

    public int spansChecked() {
        return spansChecked;
    }

    public List<LintViolation> violations() {
        return violations;
    }

    /** Количество нарушений уровня {@link LintSeverity#ERROR}. */
    public long errorCount() {
        return violations.stream().filter(v -> v.severity() == LintSeverity.ERROR).count();
    }

    /** Количество нарушений уровня {@link LintSeverity#WARNING}. */
    public long warningCount() {
        return violations.stream().filter(v -> v.severity() == LintSeverity.WARNING).count();
    }

    /** Признак «отчёт чистый» (нет нарушений уровня ERROR). */
    public boolean isPassing() {
        return errorCount() == 0;
    }
}
