package space.br1440.platform.tracing.otel.extension.scrubbing;

import space.br1440.platform.tracing.api.spi.SensitiveDataRule;
import space.br1440.platform.tracing.otel.extension.scrubbing.policy.ScrubbingRuleResolution;
import space.br1440.platform.tracing.otel.extension.scrubbing.policy.ScrubbingRuleResolutionResult;
import space.br1440.platform.tracing.otel.extension.utils.Strings;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates and builds the next {@link ScrubbingSnapshot} for atomic runtime policy updates (PR-7B).
 * Side-effect-free validation; rule/regex compile happens in {@link #buildNext} / {@link ScrubbingSnapshot#fromRules}.
 * <p>
 * Rule-name domain validation and built-in selection delegate to pure {@link ScrubbingRuleResolution} (PR-9D).
 */
final class ScrubbingPolicyUpdate {

    static final int MAX_RULES = ScrubbingRuleResolution.MAX_RULES;

    private ScrubbingPolicyUpdate() {
    }

    static void validateDomain(String[] ruleNames) {
        ScrubbingRuleResolution.validateRuleNames(ruleNames);
    }

    static ScrubbingSnapshot buildNext(
            ScrubbingSnapshot previous,
            boolean enabled,
            String[] ruleNames,
            String source) {
        if (ruleNames == null) {
            return new ScrubbingSnapshot(
                    enabled,
                    previous.wrappers(),
                    previous.version() + 1,
                    Instant.now(),
                    normalizeSource(source));
        }
        if (ruleNames.length > MAX_RULES) {
            throw new IllegalArgumentException("Too many scrubbing rules: " + ruleNames.length);
        }
        List<SensitiveDataRule> rules = resolveRules(ruleNames);
        return ScrubbingSnapshot.fromRules(
                enabled, rules, previous.version() + 1, Instant.now(), normalizeSource(source));
    }

    static String normalizeSource(String source) {
        if (Strings.isBlank(source)) {
            return "JMX";
        }
        return source.trim();
    }

    /**
     * Resolves built-in rule names. Unknown names are skipped (startup/JMX parity, PR-7A).
     */
    static List<SensitiveDataRule> resolveRules(String[] ruleNames) {
        ScrubbingRuleResolutionResult resolution = ScrubbingRuleResolution.resolveRuleNames(ruleNames);
        List<SensitiveDataRule> rules = new ArrayList<>(resolution.resolvedConfigNames().size());
        for (String configName : resolution.resolvedConfigNames()) {
            SensitiveDataRule rule = BuiltInSensitiveDataRules.resolve(configName);
            if (rule != null) {
                rules.add(rule);
            }
        }
        return rules;
    }
}
