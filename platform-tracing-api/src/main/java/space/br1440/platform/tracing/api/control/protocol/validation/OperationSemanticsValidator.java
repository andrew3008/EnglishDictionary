package space.br1440.platform.tracing.api.control.protocol.validation;

import lombok.experimental.UtilityClass;
import space.br1440.platform.tracing.api.control.protocol.result.TracingControlProtocolViolation;
import space.br1440.platform.tracing.api.control.protocol.schema.TracingControlProtocolFieldCategory;
import space.br1440.platform.tracing.api.control.protocol.schema.TracingControlProtocolFieldDescriptor;

import java.util.List;
import java.util.Map;

@UtilityClass
final class OperationSemanticsValidator {

    static boolean validateCategoryPolicy(String key,
                                          TracingControlProtocolFieldDescriptor descriptor,
                                          boolean allowRuntimePolicyFields,
                                          List<TracingControlProtocolViolation> violations) {

        if (descriptor.category() == TracingControlProtocolFieldCategory.STARTUP_TOPOLOGY) {
            violations.add(FieldTypeSupport.violation(
                    key,
                    "startup topology field rejected for wire control path",
                    "runtime policy or envelope key",
                    descriptor.type().name(),
                    TracingControlProtocolViolationCode.OPERATION_NOT_ALLOWED));
            return true;
        }

        if (!allowRuntimePolicyFields
                && descriptor.category() == TracingControlProtocolFieldCategory.RUNTIME_POLICY) {
            violations.add(FieldTypeSupport.violation(
                    key,
                    "runtime policy field not allowed in read request",
                    "envelope or diagnostic key",
                    descriptor.type().name(),
                    TracingControlProtocolViolationCode.OPERATION_NOT_ALLOWED));
            return true;
        }

        return false;
    }

    static boolean validateOperation(String key,
                                     Object value,
                                     List<String> allowedOperations,
                                     List<TracingControlProtocolViolation> violations,
                                     Map<String, Object> normalized) {

        if (!(value instanceof String operation)) {
            violations.add(FieldTypeSupport.violation(
                    key,
                    "operation must be String",
                    "String",
                    FieldTypeSupport.typeName(value),
                    TracingControlProtocolViolationCode.TYPE_MISMATCH));
            return true;
        }

        if (!allowedOperations.contains(operation)) {
            violations.add(FieldTypeSupport.violation(
                    key,
                    "unsupported operation for this validation entry point",
                    String.join("|", allowedOperations),
                    operation,
                    TracingControlProtocolViolationCode.OPERATION_NOT_ALLOWED));
            return true;
        }

        normalized.put(key, operation);
        return true;
    }
}
