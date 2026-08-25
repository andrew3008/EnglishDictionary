package space.br1440.platform.tracing.otel.javaagent.scrubbing.engine;

import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;

record RuleOutcome(RuleExecutionOutcome outcome, @Nullable ScrubbingDecision decision) {

    static RuleOutcome skippedCircuitOpen() {
        return new RuleOutcome(RuleExecutionOutcome.SKIPPED_CIRCUIT_OPEN, null);
    }

    static RuleOutcome nonCriticalFailure() {
        return new RuleOutcome(RuleExecutionOutcome.NON_CRITICAL_FAILURE, null);
    }

    static RuleOutcome criticalFailure(ScrubbingDecision decision) {
        return new RuleOutcome(RuleExecutionOutcome.CRITICAL_FAILURE, decision);
    }

    static RuleOutcome decision(ScrubbingDecision decision) {
        return new RuleOutcome(RuleExecutionOutcome.DECISION, decision);
    }

    static RuleOutcome keep() {
        return new RuleOutcome(RuleExecutionOutcome.KEEP, ScrubbingDecision.keep());
    }
}
