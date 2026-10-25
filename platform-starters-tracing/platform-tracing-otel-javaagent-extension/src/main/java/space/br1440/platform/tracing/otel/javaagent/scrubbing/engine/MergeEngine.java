package space.br1440.platform.tracing.otel.javaagent.scrubbing.engine;

import java.util.List;

import space.br1440.platform.tracing.api.spi.ScrubbingAction;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class MergeEngine {

    public static MergeOutcome evaluateDetailed(List<ExecutableRule> wrappers, String key, Object value) {
        ScrubbingDecision accumulated = ScrubbingDecision.keep();
        long skippedCircuitOpen = 0L;
        long nonCriticalFailures = 0L;
        long criticalFailures = 0L;

        for (ExecutableRule wrapper : wrappers) {
            RuleOutcome outcome = wrapper.executeDetailed(key, value);
            switch (outcome.outcome()) {
                case SKIPPED_CIRCUIT_OPEN -> {
                    skippedCircuitOpen++;
                    continue;
                }

                case NON_CRITICAL_FAILURE -> {
                    nonCriticalFailures++;
                    continue;
                }

                case CRITICAL_FAILURE -> criticalFailures++;
                default -> {}
            }

            ScrubbingDecision candidate = outcome.decision();
            if (candidate == null) {
                continue;
            }

            boolean terminal = wrapper.isCritical() && (candidate.action() != ScrubbingAction.KEEP);
            accumulated = merge(accumulated, candidate, terminal);
            if (terminal) {
                break;
            }
        }

        return new MergeOutcome(accumulated, skippedCircuitOpen, nonCriticalFailures, criticalFailures);
    }

    static ScrubbingDecision merge(ScrubbingDecision current, ScrubbingDecision next, boolean nextIsTerminal) {
        if (next.action() == ScrubbingAction.KEEP) {
            return current;
        }

        int currentStrict = strictness(current.action());
        int nextStrict = strictness(next.action());
        if (nextStrict > currentStrict) {
            return promote(next, nextIsTerminal);
        }

        if (nextStrict == currentStrict && nextIsTerminal && !current.terminal()) {
            return promote(next, true);
        }

        return current;
    }

    private static ScrubbingDecision promote(ScrubbingDecision decision, boolean terminal) {
        if (terminal && !decision.terminal()) {
            return new ScrubbingDecision(decision.action(), decision.reason(), decision.maxLength(), true);
        }

        return decision;
    }

    private static int strictness(ScrubbingAction action) {
        return switch (action) {
            case KEEP -> 0;
            case TRUNCATE -> 1;
            case HASH -> 2;
            case MASK -> 3;
            case DROP -> 4;
        };
    }
}
