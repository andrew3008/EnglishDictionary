package space.br1440.platform.tracing.otel.extension.scrubbing.policy;

import space.br1440.platform.tracing.otel.extension.scrubbing.BuiltInSpanAttributeScrubbingRules;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure scrubbing rule-name validation and built-in resolution (PR-9D preparation).
 * <p>
 * Separates policy selection (input names → known built-in identifiers) from agent-side
 * execution ({@code SpanAttributeScrubbingRule} instantiation, {@code RuleExecutionWrapper} compile).
 * Intended for future move to {@code core.scrubbing} with a JDK-only built-in name registry.
 * <p>
 * No OTel, Spring, JMX, or logging dependencies.
 */
public final class ScrubbingRuleResolution {

    public static final int MAX_RULES = 200;

    private ScrubbingRuleResolution() {
    }

    /**
     * Validates runtime rule-name domain. {@code null} array is allowed (toggle {@code enabled} only).
     */
    public static void validateRuleNames(String[] ruleNames) {
        if (ruleNames == null) {
            return;
        }
        if (ruleNames.length > MAX_RULES) {
            throw new IllegalArgumentException("Too many scrubbing rules: " + ruleNames.length);
        }
        for (String name : ruleNames) {
            if (name == null) {
                throw new IllegalArgumentException("Rule name must not be null");
            }
        }
    }

    /**
     * Resolves built-in rule config names from input names. Unknown names are skipped;
     * order and duplicates are preserved (startup/JMX parity, PR-7A/7B).
     *
     * @param ruleNames non-null array (call {@link #validateRuleNames} first when validating domain)
     */
    public static ScrubbingRuleResolutionResult resolveRuleNames(String[] ruleNames) {
        List<String> resolved = new ArrayList<>(ruleNames.length);
        List<String> skippedUnknown = new ArrayList<>();
        for (String name : ruleNames) {
            if (name == null) {
                throw new IllegalArgumentException("Rule name must not be null");
            }
            BuiltInSpanAttributeScrubbingRules descriptor = BuiltInSpanAttributeScrubbingRules.lookup(name);
            if (descriptor != null) {
                resolved.add(descriptor.configName());
            } else {
                skippedUnknown.add(name);
            }
        }
        return new ScrubbingRuleResolutionResult(List.copyOf(resolved), List.copyOf(skippedUnknown));
    }
}
