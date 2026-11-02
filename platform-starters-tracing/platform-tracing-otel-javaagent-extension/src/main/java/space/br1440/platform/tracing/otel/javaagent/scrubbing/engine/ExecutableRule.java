package space.br1440.platform.tracing.otel.javaagent.scrubbing.engine;

import space.br1440.platform.tracing.api.spi.ScrubbingAction;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker.Admission;

import io.opentelemetry.api.common.AttributeType;
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

    RuleOutcome executeDetailed(String key, Object value, AttributeType type) {
        if (rule instanceof UrlCredentialMaskingRule && rule.isExcluded(key)) {
            return RuleOutcome.keep();
        }

        if (rule instanceof StringValueRule && type != AttributeType.STRING && type != AttributeType.STRING_ARRAY) {
            return RuleOutcome.keep();
        }

        Admission admission = breaker.acquireExecution();
        return switch (admission.state()) {
            case OPEN -> rule.critical()
                    ? criticalFailure()
                    : RuleOutcome.skippedCircuitOpen();
            case HALF_OPEN, CLOSED -> executeAdmitted(admission, key, value);
        };
    }

    private RuleOutcome executeAdmitted(Admission admission, String key, Object value) {
        try {
            RuleOutcome result = rule.isExcluded(key) ? RuleOutcome.keep() : evaluateRule(key, value);
            breaker.recordSuccess(admission);
            return result;
        } catch (Throwable t) {
            PlatformThrowables.propagateIfFatal(t);
            breaker.recordFailure(admission, t);
            return rule.critical()
                    ? criticalFailure()
                    : RuleOutcome.nonCriticalFailure();
        }
    }

    private RuleOutcome evaluateRule(String key, Object value) {
        if (rule instanceof UrlCredentialMaskingRule urlRule && value instanceof String text) {
            String transformed = urlRule.mask(text);
            if (transformed == null) {
                return RuleOutcome.decision(ScrubbingDecision.drop(rule.name()));
            }

            return text.equals(transformed) ? RuleOutcome.keep() : RuleOutcome.maskedText(transformed);
        }

        ScrubbingDecision result = rule.evaluate(key, value);
        requireNonNull(result);
        return (result.action() == ScrubbingAction.KEEP)
                ? RuleOutcome.keep()
                : RuleOutcome.decision(result);
    }

    private RuleOutcome criticalFailure() {
        ScrubbingDecision decision = rule instanceof UrlCredentialMaskingRule
                ? ScrubbingDecision.drop(ScrubbingDecision.SCRUBBING_FAILED_REASON)
                : ScrubbingDecision.scrubbingFailed();
        return RuleOutcome.criticalFailure(decision);
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
