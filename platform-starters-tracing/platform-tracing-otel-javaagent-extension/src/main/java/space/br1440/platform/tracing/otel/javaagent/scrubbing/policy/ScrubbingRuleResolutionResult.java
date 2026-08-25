package space.br1440.platform.tracing.otel.javaagent.scrubbing.policy;

import java.util.List;

public record ScrubbingRuleResolutionResult(List<String> resolvedConfigNames,
                                            List<String> skippedUnknownNames) {
}
