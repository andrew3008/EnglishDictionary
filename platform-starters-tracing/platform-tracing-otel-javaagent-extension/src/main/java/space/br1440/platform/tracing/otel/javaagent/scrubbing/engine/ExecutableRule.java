package space.br1440.platform.tracing.otel.javaagent.scrubbing.engine;

import space.br1440.platform.tracing.api.spi.ScrubbingAction;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker.Admission;

import lombok.Getter;

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

    RuleOutcome executeDetailed(String key, Object value) {
        Admission admission = breaker.acquireExecution();
        return switch (admission.state()) {
            case OPEN -> rule.critical()
                    ? RuleOutcome.criticalFailure(ScrubbingDecision.scrubbingFailed())
                    : RuleOutcome.skippedCircuitOpen();
            case HALF_OPEN -> executeProbeDetailed(admission, key, value);
            case CLOSED -> executeNormalDetailed(admission, key, value);
        };
    }

    private RuleOutcome executeProbeDetailed(Admission admission, String key, Object value) {
        try {
            if (rule.isExcluded(key)) {
                breaker.recordSuccess(admission);
                return RuleOutcome.keep();
            }

            ScrubbingDecision result = rule.evaluate(key, value);
            requireNonNull(result);
            breaker.recordSuccess(admission);
            return classify(result);
        } catch (Throwable t) {
            PlatformThrowables.propagateIfFatal(t);
            breaker.recordFailure(admission, t);
            return rule.critical()
                    ? RuleOutcome.criticalFailure(ScrubbingDecision.scrubbingFailed())
                    : RuleOutcome.nonCriticalFailure();
        }
    }

    private RuleOutcome executeNormalDetailed(Admission admission, String key, Object value) {
        try {
            if (rule.isExcluded(key)) {
                return RuleOutcome.keep();
            }

            ScrubbingDecision result = rule.evaluate(key, value);
            requireNonNull(result);
            return classify(result);
        } catch (Throwable t) {
            PlatformThrowables.propagateIfFatal(t);
            breaker.recordFailure(admission, t);
            return rule.critical()
                    ? RuleOutcome.criticalFailure(ScrubbingDecision.scrubbingFailed())
                    : RuleOutcome.nonCriticalFailure();
        }
    }

    private static RuleOutcome classify(ScrubbingDecision result) {
        return (result.action() == ScrubbingAction.KEEP)
                ? RuleOutcome.keep()
                : RuleOutcome.decision(result);
    }

    private void requireNonNull(ScrubbingDecision result) {
        if (result == null) {
            throw new IllegalStateException("Rule " + rule.getClass().getName() + " returned a null ScrubbingDecision");
        }
    }

    public Class<?> getRuleClass() {
        return rule instanceof StableScrubbingRule stable
                ? stable.implementationClass()
                : rule.getClass();
    }

    public boolean isCritical() {
        return rule.critical();
    }

    public ExecutableRule withEffectivePriority(int newPriority) {
        return new ExecutableRule(rule, breaker, newPriority);
    }
}
