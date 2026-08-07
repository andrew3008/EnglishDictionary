package space.br1440.platform.tracing.otel.javaagent.scrubbing.engine;

import lombok.Getter;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker;

@Getter
public final class ExecutableRule {

    private final SpanAttributeScrubbingRule rule;
    private final RuleCircuitBreaker breaker;
    private final int effectivePriority;

    public ExecutableRule(SpanAttributeScrubbingRule rule, RuleCircuitBreaker breaker) {
        this(rule, breaker, rule.priority());
    }

    private ExecutableRule(SpanAttributeScrubbingRule rule, RuleCircuitBreaker breaker, int effectivePriority) {
        this.rule = rule;
        this.breaker = breaker;
        this.effectivePriority = effectivePriority;
    }

    public ScrubbingDecision execute(String key, Object value) {
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
            throw new IllegalStateException("Rule " + rule.getClass().getName() + " returned a null ScrubbingDecision");
        }
    }

    public Class<?> getRuleClass() {
        return rule.getClass();
    }

    public String getRuleName() {
        return rule.name();
    }

    public boolean isCritical() {
        return rule.critical();
    }

    public ExecutableRule withEffectivePriority(int newPriority) {
        return new ExecutableRule(rule, breaker, newPriority);
    }
}
