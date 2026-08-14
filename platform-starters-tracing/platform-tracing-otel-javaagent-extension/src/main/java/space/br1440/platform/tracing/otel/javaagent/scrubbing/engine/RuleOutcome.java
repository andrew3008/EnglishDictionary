package space.br1440.platform.tracing.otel.javaagent.scrubbing.engine;

import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;

public record RuleOutcome(RuleExecutionOutcome outcome, @Nullable ScrubbingDecision decision) {

    public static RuleOutcome skippedCircuitOpen() {
        return new RuleOutcome(RuleExecutionOutcome.SKIPPED_CIRCUIT_OPEN, null);
    }

    public static RuleOutcome nonCriticalFailure() {
        return new RuleOutcome(RuleExecutionOutcome.NON_CRITICAL_FAILURE, null);
    }

    public static RuleOutcome criticalFailure(ScrubbingDecision decision) {
        return new RuleOutcome(RuleExecutionOutcome.CRITICAL_FAILURE, decision);
    }

    public static RuleOutcome decision(ScrubbingDecision decision) {
        return new RuleOutcome(RuleExecutionOutcome.DECISION, decision);
    }

    public static RuleOutcome keep() {
        return new RuleOutcome(RuleExecutionOutcome.KEEP, ScrubbingDecision.keep());
    }
}
