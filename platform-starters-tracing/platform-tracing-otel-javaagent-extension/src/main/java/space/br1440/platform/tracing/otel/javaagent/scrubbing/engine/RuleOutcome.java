package space.br1440.platform.tracing.otel.javaagent.scrubbing.engine;

import jakarta.annotation.Nullable;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;

record RuleOutcome(RuleExecutionOutcome outcome,
                   @Nullable ScrubbingDecision decision,
                   @Nullable String maskedValue) {

    static RuleOutcome skippedCircuitOpen() {
        return new RuleOutcome(RuleExecutionOutcome.SKIPPED_CIRCUIT_OPEN, null, null);
    }

    static RuleOutcome nonCriticalFailure() {
        return new RuleOutcome(RuleExecutionOutcome.NON_CRITICAL_FAILURE, null, null);
    }

    static RuleOutcome criticalFailure(ScrubbingDecision decision) {
        return new RuleOutcome(RuleExecutionOutcome.CRITICAL_FAILURE, decision, null);
    }

    static RuleOutcome decision(ScrubbingDecision decision) {
        return new RuleOutcome(RuleExecutionOutcome.DECISION, decision, null);
    }

    static RuleOutcome keep() {
        return new RuleOutcome(RuleExecutionOutcome.KEEP, ScrubbingDecision.keep(), null);
    }

    static RuleOutcome maskedText(String value) {
        return new RuleOutcome(RuleExecutionOutcome.KEEP, ScrubbingDecision.keep(), value);
    }
}
