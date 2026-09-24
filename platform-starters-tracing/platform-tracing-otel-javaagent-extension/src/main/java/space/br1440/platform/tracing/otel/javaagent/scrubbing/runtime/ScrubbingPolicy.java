package space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime;

import java.util.List;

import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ExecutableRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuleOrigin;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuntimeRule;

record ScrubbingPolicy(List<ExecutableRule> attributeExecutableRules,
                       List<ExecutableRule> builtInTextRules,
                       long version) {

    static ScrubbingPolicy create(List<RuntimeRule> rules, long version) {
        List<ExecutableRule> attributes = rules.stream().map(RuntimeRule::executable).toList();
        List<ExecutableRule> builtIn = rules.stream()
                .filter(rule -> rule.origin() == RuleOrigin.BUILT_IN)
                .map(RuntimeRule::executable)
                .toList();
        return new ScrubbingPolicy(attributes, builtIn, version);
    }
}
