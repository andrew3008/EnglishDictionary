package space.br1440.platform.tracing.otel.javaagent.scrubbing;

import space.br1440.platform.tracing.otel.runtime.versioned.VersionedState;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ExecutableRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.PriorityHardening;

import java.util.ArrayList;
import java.util.List;

public final class ScrubbingSnapshot implements VersionedState {

    private final boolean enabled;
    private final List<ExecutableRule> executableRules;
    private final long version;
    private final String source;

    public ScrubbingSnapshot(boolean enabled,
                             List<ExecutableRule> executableRules,
                             long version,
                             String source) {
        this.enabled = enabled;
        this.executableRules = executableRules == null ? List.of() : List.copyOf(executableRules);
        this.version = version;
        this.source = source;
    }

    public static ScrubbingSnapshot fromRules(boolean enabled,
                                              List<SpanAttributeScrubbingRule> rules,
                                              long version,
                                              String source) {
        if (rules == null) {
            throw new NullPointerException("rules");
        }

        for (SpanAttributeScrubbingRule rule : rules) {
            if (rule == null) {
                throw new NullPointerException("rules must not contain null entries");
            }
        }

        return new ScrubbingSnapshot(enabled, toExecutableRules(rules), version, source);
    }

    public static List<ExecutableRule> toExecutableRules(List<SpanAttributeScrubbingRule> rules) {
        List<ExecutableRule> raw = new ArrayList<>(rules.size());
        for (SpanAttributeScrubbingRule rule : rules) {
            raw.add(
                    new ExecutableRule(
                            rule,
                            new RuleCircuitBreaker(rule.name())
                    )
            );
        }

        return PriorityHardening.sortAndClamp(raw);
    }

    public boolean enabled() {
        return enabled;
    }

    public List<ExecutableRule> executableRules() {
        return executableRules;
    }

    @Override
    public long version() {
        return version;
    }

    public String source() {
        return source;
    }
}
