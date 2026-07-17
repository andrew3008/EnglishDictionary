package space.br1440.platform.tracing.api.control.protocol;

import java.util.List;
import java.util.Map;
import java.util.Optional;

final class ContractVersionValidator {

    private ContractVersionValidator() {
    }

    static void validate(String key,
                         Object value,
                         int expectedMajor,
                         List<TracingControlProtocolViolation> violations,
                         Map<String, Object> normalized) {
        Optional<TracingControlProtocolVersion> parsed = TracingControlProtocolVersion.parse(value);
        if (parsed.isEmpty()) {
            violations.add(FieldTypeSupport.violation(
                    key,
                    "invalid contractVersion",
                    "Integer",
                    FieldTypeSupport.typeName(value),
                    TracingControlProtocolViolationCode.INVALID_VALUE));
            return;
        }

        if (parsed.get().major() != expectedMajor) {
            violations.add(FieldTypeSupport.violation(
                    key,
                    "unsupported contractVersion",
                    String.valueOf(expectedMajor),
                    FieldTypeSupport.typeName(value),
                    TracingControlProtocolViolationCode.UNSUPPORTED_VERSION));
            return;
        }

        normalized.put(key, parsed.get().major());
    }
}
