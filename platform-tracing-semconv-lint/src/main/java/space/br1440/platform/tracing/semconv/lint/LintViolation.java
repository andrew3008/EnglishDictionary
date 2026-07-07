package space.br1440.platform.tracing.semconv.lint;

/**
 * Результат проверки одного правила на одном span'е.
 *
 * @param ruleId   уникальный идентификатор правила;
 * @param severity уровень нарушения;
 * @param spanName имя span'а, к которому относится нарушение;
 * @param message  человекочитаемое описание нарушения.
 */
public record LintViolation(String ruleId,
                            LintSeverity severity,
                            String spanName,
                            String message) {
}
