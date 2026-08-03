package space.br1440.platform.tracing.autoconfigure.diagnostics.health.startup;

import java.util.Objects;

record TracingStartupContractSnapshot(boolean tracingEnabled,
                                      String runtimeMode,
                                      String agentState,
                                      String failureCode,
                                      TracingStartupContractOutcome outcome) {

    static final String WIRE_RUNTIME_MODE_ENABLED = "ENABLED";
    static final String WIRE_RUNTIME_MODE_DISABLED_BY_CONFIGURATION = "DISABLED_BY_CONFIGURATION";
    static final String WIRE_AGENT_STATE_READY = "AGENT_READY";
    static final String WIRE_AGENT_STATE_DISABLED = "DISABLED";
    static final String WIRE_FAILURE_CODE_UNSPECIFIED_VIOLATION = "STARTUP_CONTRACT_VIOLATION_UNSPECIFIED";

    TracingStartupContractSnapshot {
        Objects.requireNonNull(runtimeMode, "runtimeMode");
        Objects.requireNonNull(agentState, "agentState");
        Objects.requireNonNull(failureCode, "failureCode");
        Objects.requireNonNull(outcome, "outcome");

        switch (outcome) {
            case ENABLED_CONTRACT_SATISFIED -> {
                require(tracingEnabled, "ENABLED_CONTRACT_SATISFIED requires tracingEnabled=true");

                require(WIRE_RUNTIME_MODE_ENABLED.equals(runtimeMode),
                        "ENABLED_CONTRACT_SATISFIED requires runtimeMode=" + WIRE_RUNTIME_MODE_ENABLED);

                require(WIRE_AGENT_STATE_READY.equals(agentState),
                        "ENABLED_CONTRACT_SATISFIED requires agentState=" + WIRE_AGENT_STATE_READY);

                require(failureCode.isBlank(), "ENABLED_CONTRACT_SATISFIED must not carry a failureCode");
            }

            case DISABLED_CONTRACT_SATISFIED -> {
                require(!tracingEnabled, "DISABLED_CONTRACT_SATISFIED requires tracingEnabled=false");

                require(WIRE_RUNTIME_MODE_DISABLED_BY_CONFIGURATION.equals(runtimeMode),
                        "DISABLED_CONTRACT_SATISFIED requires runtimeMode="
                                + WIRE_RUNTIME_MODE_DISABLED_BY_CONFIGURATION);

                require(WIRE_AGENT_STATE_DISABLED.equals(agentState),
                        "DISABLED_CONTRACT_SATISFIED requires agentState=" + WIRE_AGENT_STATE_DISABLED);

                require(failureCode.isBlank(), "DISABLED_CONTRACT_SATISFIED must not carry a failureCode");
            }

            case STARTUP_CONTRACT_VIOLATED -> {
                if (failureCode.isBlank()) {
                    failureCode = WIRE_FAILURE_CODE_UNSPECIFIED_VIOLATION;
                }
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
