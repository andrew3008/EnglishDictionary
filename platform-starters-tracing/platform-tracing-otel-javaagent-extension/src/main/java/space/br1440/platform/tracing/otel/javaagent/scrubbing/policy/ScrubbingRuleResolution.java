package space.br1440.platform.tracing.otel.javaagent.scrubbing.policy;

import java.util.ArrayList;
import java.util.List;

import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class ScrubbingRuleResolution {

    public static final int MAX_RULES = 200;

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
