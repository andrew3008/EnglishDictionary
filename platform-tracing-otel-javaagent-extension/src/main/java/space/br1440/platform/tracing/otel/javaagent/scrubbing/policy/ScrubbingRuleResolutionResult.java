package space.br1440.platform.tracing.otel.javaagent.scrubbing.policy;

import java.util.List;

/**
 * Result of pure built-in scrubbing rule-name resolution (PR-9D).
 * <p>
 * {@code resolvedConfigNames} — canonical config names in input order (unknown names omitted).
 * {@code skippedUnknownNames} — input names that did not match a built-in descriptor (same order).
 */
public record ScrubbingRuleResolutionResult(
        List<String> resolvedConfigNames,
        List<String> skippedUnknownNames
) {
}
