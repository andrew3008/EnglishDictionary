package space.br1440.platform.tracing.otel.extension.scrubbing.engine;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SensitiveDataRule;
import space.br1440.platform.tracing.otel.extension.scrubbing.circuitbreaker.RuleCircuitBreaker;

/**
 * Обёртка над {@link SensitiveDataRule}: связывает правило с его {@link RuleCircuitBreaker} и
 * effective-приоритетом (с учётом clamp в {@link PriorityHardening}).
 * <p>
 * Circuit breaker намеренно вынесен <b>наружу</b> правила — SPI-контракт остаётся чистым и
 * декларативным, а отказоустойчивость/изоляция ошибок — забота платформы.
 *
 * <h3>Поведение в состоянии OPEN</h3>
 * <ul>
 *   <li><b>critical OPEN:</b> возвращается {@link ScrubbingDecision#scrubbingFailed()} —
 *       fail-closed: атрибут маскируется, секрет не утекает;</li>
 *   <li><b>custom OPEN:</b> возвращается {@code null} (skip) — merge-движок игнорирует {@code null}.</li>
 * </ul>
 *
 * <h3>Изоляция ошибок</h3>
 * Исключения {@code supports()}/{@code evaluate()} перехватываются здесь, считаются в circuit
 * breaker и переводятся в fail-closed (critical) либо skip (custom). Падение одного правила не
 * прерывает обработку остальных правил span'а.
 */
public final class RuleExecutionWrapper {

    private final SensitiveDataRule rule;
    private final RuleCircuitBreaker breaker;
    private final int effectivePriority;

    public RuleExecutionWrapper(SensitiveDataRule rule, RuleCircuitBreaker breaker) {
        this(rule, breaker, rule.priority());
    }

    private RuleExecutionWrapper(SensitiveDataRule rule, RuleCircuitBreaker breaker, int effectivePriority) {
        this.rule = rule;
        this.breaker = breaker;
        this.effectivePriority = effectivePriority;
    }

    /**
     * Исполняет правило для нормализованного ключа/значения через circuit breaker.
     *
     * @return решение правила, либо {@code null} если правило пропущено (custom в состоянии OPEN)
     */
    public ScrubbingDecision execute(String key, Object value) {
        // acquireExecutionState() вызывается РОВНО ОДИН РАЗ на цикл (контракт CQS, см. Javadoc метода).
        RuleCircuitBreaker.State state = breaker.acquireExecutionState();
        return switch (state) {
            case OPEN -> rule.critical() ? ScrubbingDecision.scrubbingFailed() : null;
            case HALF_OPEN -> executeProbe(key, value);
            case CLOSED -> executeNormal(key, value);
        };
    }

    private ScrubbingDecision executeProbe(String key, Object value) {
        try {
            if (rule.isExcluded(key)) {
                breaker.recordSuccess();
                return ScrubbingDecision.keep();
            }

            ScrubbingDecision result = rule.evaluate(key, value);
            requireNonNull(result);
            breaker.recordSuccess();
            return result;
        } catch (Throwable t) {
            breaker.recordFailure(t);
            return rule.critical() ? ScrubbingDecision.scrubbingFailed() : null;
        }
    }

    private ScrubbingDecision executeNormal(String key, Object value) {
        try {
            if (rule.isExcluded(key)) {
                return ScrubbingDecision.keep();
            }

            ScrubbingDecision result = rule.evaluate(key, value);
            requireNonNull(result);
            return result;
        } catch (Throwable t) {
            breaker.recordFailure(t);
            return rule.critical() ? ScrubbingDecision.scrubbingFailed() : null;
        }
    }

    private void requireNonNull(ScrubbingDecision result) {
        if (result == null) {
            throw new IllegalStateException("Правило " + rule.getClass().getName() + " вернуло null ScrubbingDecision");
        }
    }

    public Class<?> getRuleClass() {
        return rule.getClass();
    }

    public String getRuleName() {
        return rule.name();
    }

    public int getEffectivePriority() {
        return effectivePriority;
    }

    public boolean isCritical() {
        return rule.critical();
    }

    public RuleCircuitBreaker getBreaker() {
        return breaker;
    }

    /** Возвращает новую обёртку с переопределённым effective-приоритетом (для clamp). */
    public RuleExecutionWrapper withEffectivePriority(int newPriority) {
        return new RuleExecutionWrapper(rule, breaker, newPriority);
    }
}
