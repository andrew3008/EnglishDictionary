package space.br1440.platform.tracing.otel.javaagent.exporter.boundary;

import java.util.List;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ScrubbingKeyNormalizer;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ExecutableRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.MergeEngine;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.MergeOutcome;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;

import lombok.experimental.UtilityClass;

@UtilityClass
final class BoundaryTextPolicy {

    static final String REDACTED = "<redacted>";
    static final String SPAN_NAME_KEY = "span.name";
    static final String EVENT_NAME_KEY = "event.name";
    static final String STATUS_DESCRIPTION_KEY = "status.description";

    record TextResult(String value, boolean criticalFailure) {
    }

    static TextResult scrubSpanName(String name, ScrubbingRuntime runtime) {
        return scrubName(name, SPAN_NAME_KEY, runtime);
    }

    static TextResult scrubEventName(String name, ScrubbingRuntime runtime) {
        return scrubName(name, EVENT_NAME_KEY, runtime);
    }

    private static TextResult scrubName(String name, String ruleKey, ScrubbingRuntime runtime) {
        TextResult result = scrubText(name, ruleKey, runtime);
        if (!StringUtils.isNullOrEmpty(name) && StringUtils.isNullOrEmpty(result.value())) {
            return new TextResult(REDACTED, result.criticalFailure());
        }
        return result;
    }

    static TextResult scrubStatusDescription(String description, ScrubbingRuntime runtime) {
        return scrubText(description, STATUS_DESCRIPTION_KEY, runtime);
    }

    private static TextResult scrubText(String value, String ruleKey, ScrubbingRuntime runtime) {
        if (StringUtils.isNullOrEmpty(value)) {
            return new TextResult(value, false);
        }

        List<ExecutableRule> rules = runtime.builtInTextRules();
        if (rules.isEmpty()) {
            return new TextResult(value, false);
        }

        String normalizedKey = ScrubbingKeyNormalizer.normalize(ruleKey);
        MergeOutcome outcome = MergeEngine.evaluateDetailed(rules, normalizedKey, value);
        boolean criticalFailure = recordOutcome(outcome, runtime);

        String masked = (outcome.maskedValue() != null) ? outcome.maskedValue() : value;
        return new TextResult(apply(masked, outcome.decision(), runtime), criticalFailure);
    }

    private static boolean recordOutcome(MergeOutcome outcome, ScrubbingRuntime runtime) {
        if (outcome.skippedCircuitOpen() > 0L) {
            runtime.recordBoundarySkippedCircuitOpen(outcome.skippedCircuitOpen());
        }

        if (outcome.nonCriticalFailures() > 0L) {
            runtime.recordBoundaryNonCriticalFailures(outcome.nonCriticalFailures());
        }

        if (outcome.criticalFailures() > 0L) {
            runtime.recordBoundaryCriticalFailures(outcome.criticalFailures());
            return true;
        }

        return false;
    }

    private static String apply(String value, ScrubbingDecision decision, ScrubbingRuntime runtime) {
        return switch (decision.action()) {
            case MASK -> REDACTED;
            case DROP -> "";
            case HASH -> {
                runtime.recordBoundaryHashDowngradedToMask();
                yield REDACTED;
            }
            case TRUNCATE -> truncate(value, decision, runtime);
            default -> value;
        };
    }

    private static String truncate(String value, ScrubbingDecision decision, ScrubbingRuntime runtime) {
        if (BuiltInSpanAttributeScrubbingRules.IP_ADDRESS.configName().equals(decision.reason())) {
            runtime.recordBoundaryTruncateDowngradedToMask();
            return REDACTED;
        }

        int max = decision.maxLength();
        if (max > 0 && value.length() > max) {
            return value.substring(0, max);
        }

        if (max <= 0) {
            runtime.recordBoundaryTruncateDowngradedToMask();
            return REDACTED;
        }

        return value;
    }
}
