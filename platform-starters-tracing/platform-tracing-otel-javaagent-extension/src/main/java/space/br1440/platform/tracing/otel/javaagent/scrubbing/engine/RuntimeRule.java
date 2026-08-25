package space.br1440.platform.tracing.otel.javaagent.scrubbing.engine;

import java.util.Objects;

public record RuntimeRule(ExecutableRule executable, RuleOrigin origin) {

    public RuntimeRule {
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(origin, "origin");
    }
}
