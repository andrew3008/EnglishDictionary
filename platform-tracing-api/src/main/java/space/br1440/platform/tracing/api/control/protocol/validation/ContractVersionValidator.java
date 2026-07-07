package space.br1440.platform.tracing.api.control.protocol.validation;

import lombok.experimental.UtilityClass;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocol;
import space.br1440.platform.tracing.api.control.protocol.result.TracingControlProtocolViolation;
import space.br1440.platform.tracing.api.control.protocol.version.TracingControlProtocolVersion;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@UtilityClass
final class ContractVersionValidator {

    static void validate(String key,
                         Object value,
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

        if (!TracingControlProtocol.isSupported(parsed.get())) {
            violations.add(FieldTypeSupport.violation(
                    key,
                    "unsupported contractVersion",
                    String.valueOf(TracingControlProtocol.current().version().major()),
                    FieldTypeSupport.typeName(value),
                    TracingControlProtocolViolationCode.UNSUPPORTED_VERSION));
            return;
        }

        normalized.put(key, parsed.get().major());
    }
}
