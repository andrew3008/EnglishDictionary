package space.br1440.platform.tracing.autoconfigure.agent;

import java.util.Objects;

public final class AgentReadinessCoherenceException extends RuntimeException {

    private final String failureCode;

    AgentReadinessCoherenceException(String failureCode) {
        super(Objects.requireNonNull(failureCode, "failureCode")
                + ": Trusted JMX read is not coherent with the pinned readiness anchor",
                null,
                false,
                false);
        this.failureCode = failureCode;
    }

    public String failureCode() {
        return failureCode;
    }
}
