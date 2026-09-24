package space.br1440.platform.tracing.autoconfigure.arch.archfixture.invalid;

import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

public final class DependsOnScrubbingRuntime {

    private final ScrubbingRuntime runtime;

    public DependsOnScrubbingRuntime(ScrubbingRuntime runtime) {
        this.runtime = runtime;
    }

    public ScrubbingRuntime runtime() {
        return runtime;
    }
}
