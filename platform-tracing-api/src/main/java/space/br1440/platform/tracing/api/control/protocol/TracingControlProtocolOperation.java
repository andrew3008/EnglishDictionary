package space.br1440.platform.tracing.api.control.protocol;

import java.util.Arrays;
import java.util.Optional;

public enum TracingControlProtocolOperation {

    APPLY_RUNTIME_POLICY("APPLY_RUNTIME_POLICY"),
    VALIDATE_RUNTIME_POLICY("VALIDATE_RUNTIME_POLICY"),
    READ_APPLIED_STATE("READ_APPLIED_STATE");

    private final String wireValue;

    TracingControlProtocolOperation(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<TracingControlProtocolOperation> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }

        return Arrays.stream(values())
                .filter(operation -> operation.wireValue.equals(raw))
                .findFirst();
    }
}
