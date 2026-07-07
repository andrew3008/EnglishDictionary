package space.br1440.platform.tracing.api.control.protocol.validation;

import space.br1440.platform.tracing.api.control.protocol.result.TracingControlProtocolValidationResult;
import space.br1440.platform.tracing.api.control.protocol.result.TracingControlProtocolViolation;
import space.br1440.platform.tracing.api.control.protocol.schema.*;

import java.util.*;

public final class TracingControlProtocolValidator {

    private static final List<String> RUNTIME_MUTATION_OPERATIONS = List.of(
            TracingControlProtocolKeys.OPERATION_APPLY_RUNTIME_POLICY,
            TracingControlProtocolKeys.OPERATION_VALIDATE_RUNTIME_POLICY
    );

    private static final List<String> READ_OPERATIONS = List.of(
            TracingControlProtocolKeys.OPERATION_READ_APPLIED_STATE,
            TracingControlProtocolKeys.OPERATION_READ_SCHEMA
    );

    private final TracingControlProtocolSchema schema;

    public TracingControlProtocolValidator(TracingControlProtocolSchema schema) {
        this.schema = Objects.requireNonNull(schema, "schema");
    }

    public TracingControlProtocolValidationResult validateRuntimePolicy(Map<String, Object> payload) {
        return validatePayload(
                payload,
                schema.requiredKeysFor(TracingControlProtocolOperation.APPLY_RUNTIME_POLICY),
                RUNTIME_MUTATION_OPERATIONS,
                true);
    }

    public TracingControlProtocolValidationResult validateReadRequest(Map<String, Object> payload) {
        return validatePayload(
                payload,
                schema.requiredKeysFor(TracingControlProtocolOperation.READ_APPLIED_STATE),
                READ_OPERATIONS,
                false);
    }

    private TracingControlProtocolValidationResult validatePayload(Map<String, Object> payload,
                                                                   Set<String> requiredKeys,
                                                                   List<String> allowedOperations,
                                                                   boolean allowRuntimePolicyFields) {
        if (payload == null) {
            return missingRequiredKeys(requiredKeys);
        }

        List<TracingControlProtocolViolation> violations = new ArrayList<>();
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

            if (!schema.isKnownKey(key)) {
                violations.add(FieldTypeSupport.violation(
                        key,
                        "unknown key rejected (strict v1)",
                        "known wire key",
                        FieldTypeSupport.typeName(entry.getValue()),
                        TracingControlProtocolViolationCode.UNKNOWN_KEY));
                continue;
            }

            processEntry(key, entry.getValue(), schema.descriptorOf(key),
                    allowedOperations, allowRuntimePolicyFields, violations, normalized);
        }

        addMissingRequiredKeyViolations(payload, requiredKeys, violations);

        if (violations.isEmpty()) {
            return TracingControlProtocolValidationResult.valid(normalized);
        } else {
            return TracingControlProtocolValidationResult.invalid(violations);
        }
    }

    private void processEntry(String key,
                              Object value,
                              TracingControlProtocolFieldDescriptor descriptor,
                              List<String> allowedOperations,
                              boolean allowRuntimePolicyFields,
                              List<TracingControlProtocolViolation> violations,
                              Map<String, Object> normalized) {
        if (OperationSemanticsValidator.validateCategoryPolicy(key, descriptor, allowRuntimePolicyFields, violations)) {
            return;
        }

        if (TracingControlProtocolKeys.CONTRACT_VERSION.equals(key)) {
            ContractVersionValidator.validate(key, value, violations, normalized);
            return;
        }

        if (TracingControlProtocolKeys.OPERATION.equals(key)) {
            OperationSemanticsValidator.validateOperation(key, value, allowedOperations, violations, normalized);
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
        if (descriptor.type() == TracingControlProtocolTypes.ROUTE_RATIOS_MAP) {
            normalizedValue = RouteRatiosValidator.validate(key, value, violations);
        } else {
            normalizedValue = FieldTypeSupport.validateAndNormalize(key, descriptor.type(), value, violations);
        }

        if (normalizedValue != null) {
            normalized.put(key, normalizedValue);
        }
    }

    private void addMissingRequiredKeyViolations(Map<String, Object> payload,
                                                 Set<String> requiredKeys,
                                                 List<TracingControlProtocolViolation> violations) {
        for (String required : requiredKeys) {
            if (!payload.containsKey(required)) {
                violations.add(FieldTypeSupport.violation(
                        required,
                        "required key missing",
                        String.valueOf(schema.typeOf(required)),
                        "absent",
                        TracingControlProtocolViolationCode.MISSING_REQUIRED_KEY));
            }
        }
    }

    private TracingControlProtocolValidationResult missingRequiredKeys(Set<String> requiredKeys) {
        List<TracingControlProtocolViolation> violations = new ArrayList<>();
        for (String required : requiredKeys) {
            violations.add(FieldTypeSupport.violation(
                    required,
                    "required key missing",
                    String.valueOf(schema.typeOf(required)),
                    "absent",
                    TracingControlProtocolViolationCode.MISSING_REQUIRED_KEY));
        }

        return TracingControlProtocolValidationResult.invalid(violations);
    }
}
