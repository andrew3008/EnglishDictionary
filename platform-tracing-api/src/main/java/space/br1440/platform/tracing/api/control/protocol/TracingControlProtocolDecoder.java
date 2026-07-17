package space.br1440.platform.tracing.api.control.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class TracingControlProtocolDecoder {

    private final int expectedMajor;
    private final TracingControlProtocolSchema schema;

    TracingControlProtocolDecoder(int expectedMajor, TracingControlProtocolSchema schema) {
        this.expectedMajor = expectedMajor;
        this.schema = schema;
    }

    TracingControlProtocolDecodeResult decode(Map<String, Object> payload) {
        if (payload == null) {
            return TracingControlProtocolDecodeResult.failure(Optional.empty(), missingEnvelopeViolations());
        }

        List<TracingControlProtocolViolation> envelopeViolations = new ArrayList<>();
        Optional<TracingControlProtocolOperation> operation = decodeOperation(payload, envelopeViolations);
        if (operation.isEmpty()) {
            return TracingControlProtocolDecodeResult.failure(Optional.empty(), envelopeViolations);
        }

        TracingControlProtocolSchema.RequestSchema requestSchema = schema.requestFor(operation.get());
        List<TracingControlProtocolViolation> violations = new ArrayList<>(envelopeViolations);
        Map<String, Object> normalized = new LinkedHashMap<>();

        for (Map.Entry<?, ?> entry : payload.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                violations.add(FieldTypeSupport.violation(
                        "<map>",
                        "map keys must be String",
                        "String",
                        FieldTypeSupport.typeName(entry.getKey()),
                        TracingControlProtocolViolationCode.TYPE_MISMATCH));
                continue;
            }

            TracingControlProtocolFieldDescriptor descriptor = requestSchema.descriptorOf(key);
            if (descriptor == null) {
                violations.add(FieldTypeSupport.violation(
                        key,
                        "unknown key rejected for operation",
                        "known wire key for " + operation.get().wireValue(),
                        FieldTypeSupport.typeName(entry.getValue()),
                        TracingControlProtocolViolationCode.UNKNOWN_KEY));
                continue;
            }

            processEntry(key, entry.getValue(), descriptor, operation.get(), violations, normalized);
        }

        addMissingRequiredKeyViolations(payload, requestSchema, violations);

        if (violations.isEmpty()) {
            return TracingControlProtocolDecodeResult.success(operation.get(), normalized);
        }
        return TracingControlProtocolDecodeResult.failure(operation, violations);
    }

    private Optional<TracingControlProtocolOperation> decodeOperation(
            Map<String, Object> payload,
            List<TracingControlProtocolViolation> violations) {
        if (!payload.containsKey(TracingControlProtocolKeys.OPERATION)) {
            violations.add(FieldTypeSupport.violation(
                    TracingControlProtocolKeys.OPERATION,
                    "required key missing",
                    TracingControlProtocolFieldType.STRING.name(),
                    "absent",
                    TracingControlProtocolViolationCode.MISSING_REQUIRED_KEY));
            return Optional.empty();
        }

        Object rawOperation = payload.get(TracingControlProtocolKeys.OPERATION);
        if (!(rawOperation instanceof String wireOperation)) {
            violations.add(FieldTypeSupport.violation(
                    TracingControlProtocolKeys.OPERATION,
                    "operation must be String",
                    "String",
                    FieldTypeSupport.typeName(rawOperation),
                    TracingControlProtocolViolationCode.TYPE_MISMATCH));
            return Optional.empty();
        }

        Optional<TracingControlProtocolOperation> operation = TracingControlProtocolOperation.parse(wireOperation);
        if (operation.isEmpty()) {
            violations.add(FieldTypeSupport.violation(
                    TracingControlProtocolKeys.OPERATION,
                    "unsupported operation",
                    "APPLY_RUNTIME_POLICY|VALIDATE_RUNTIME_POLICY|READ_APPLIED_STATE",
                    wireOperation,
                    TracingControlProtocolViolationCode.OPERATION_NOT_ALLOWED));
        }
        return operation;
    }

    private void processEntry(String key,
                              Object value,
                              TracingControlProtocolFieldDescriptor descriptor,
                              TracingControlProtocolOperation operation,
                              List<TracingControlProtocolViolation> violations,
                              Map<String, Object> normalized) {
        if (TracingControlProtocolKeys.CONTRACT_VERSION.equals(key)) {
            ContractVersionValidator.validate(key, value, expectedMajor, violations, normalized);
            return;
        }

        if (TracingControlProtocolKeys.OPERATION.equals(key)) {
            normalized.put(key, operation.wireValue());
            return;
        }

        if (value == null) {
            violations.add(FieldTypeSupport.violation(
                    key,
                    "null value rejected",
                    descriptor.type().name(),
                    "null",
                    TracingControlProtocolViolationCode.TYPE_MISMATCH));
            return;
        }

        Object normalizedValue;
        if (descriptor.type() == TracingControlProtocolFieldType.ROUTE_RATIOS_MAP) {
            normalizedValue = RouteRatiosNormalizer.normalize(key, value, violations);
        } else {
            normalizedValue = FieldTypeSupport.validateAndNormalize(key, descriptor.type(), value, violations);
        }

        if (normalizedValue != null) {
            normalized.put(key, normalizedValue);
        }
    }

    private void addMissingRequiredKeyViolations(Map<String, Object> payload,
                                                 TracingControlProtocolSchema.RequestSchema requestSchema,
                                                 List<TracingControlProtocolViolation> violations) {
        for (String required : requestSchema.requiredKeys()) {
            if (!payload.containsKey(required)) {
                TracingControlProtocolFieldDescriptor descriptor = requestSchema.descriptorOf(required);
                violations.add(FieldTypeSupport.violation(
                        required,
                        "required key missing",
                        descriptor.type().name(),
                        "absent",
                        TracingControlProtocolViolationCode.MISSING_REQUIRED_KEY));
            }
        }
    }

    private static List<TracingControlProtocolViolation> missingEnvelopeViolations() {
        return List.of(
                FieldTypeSupport.violation(
                        TracingControlProtocolKeys.CONTRACT_VERSION,
                        "required key missing",
                        TracingControlProtocolFieldType.INTEGER.name(),
                        "absent",
                        TracingControlProtocolViolationCode.MISSING_REQUIRED_KEY),
                FieldTypeSupport.violation(
                        TracingControlProtocolKeys.OPERATION,
                        "required key missing",
                        TracingControlProtocolFieldType.STRING.name(),
                        "absent",
                        TracingControlProtocolViolationCode.MISSING_REQUIRED_KEY)
        );
    }
}
