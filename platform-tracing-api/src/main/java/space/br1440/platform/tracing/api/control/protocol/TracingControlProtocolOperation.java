package space.br1440.platform.tracing.api.control.protocol;

import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

import static lombok.AccessLevel.PACKAGE;

@RequiredArgsConstructor(access = PACKAGE)
public enum TracingControlProtocolOperation {

    APPLY_RUNTIME_POLICY("APPLY_RUNTIME_POLICY"),
    VALIDATE_RUNTIME_POLICY("VALIDATE_RUNTIME_POLICY"),
    READ_APPLIED_STATE("READ_APPLIED_STATE");

    private final String wireValue;

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
