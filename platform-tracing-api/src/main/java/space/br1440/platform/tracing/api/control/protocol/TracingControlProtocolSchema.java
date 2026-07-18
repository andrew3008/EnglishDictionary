package space.br1440.platform.tracing.api.control.protocol;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class TracingControlProtocolSchema {

    private final Map<TracingControlProtocolOperation, RequestSchema> requestsByOperation;

    private TracingControlProtocolSchema(Map<TracingControlProtocolOperation, RequestSchema> requestsByOperation) {
        this.requestsByOperation = Collections.unmodifiableMap(new EnumMap<>(requestsByOperation));
    }

    static TracingControlProtocolSchema v1() {
        Map<String, TracingControlProtocolFieldDescriptor> envelope = envelopeFields();
        Map<String, TracingControlProtocolFieldDescriptor> runtimePolicy = runtimePolicyFields();
        Map<String, TracingControlProtocolFieldDescriptor> diagnostics = diagnosticFields();

        Map<TracingControlProtocolOperation, RequestSchema> requests = new EnumMap<>(TracingControlProtocolOperation.class);

        requests.put(TracingControlProtocolOperation.APPLY_RUNTIME_POLICY,
                new RequestSchema(merge(envelope, runtimePolicy, diagnostics),
                        Set.of(TracingControlProtocolKeys.CONTRACT_VERSION, TracingControlProtocolKeys.OPERATION)));

        requests.put(TracingControlProtocolOperation.VALIDATE_RUNTIME_POLICY,
                new RequestSchema(merge(envelope, runtimePolicy, diagnostics),
                        Set.of(TracingControlProtocolKeys.CONTRACT_VERSION, TracingControlProtocolKeys.OPERATION)));

        requests.put(TracingControlProtocolOperation.READ_APPLIED_STATE,
                new RequestSchema(merge(envelope, diagnostics),
                        Set.of(TracingControlProtocolKeys.CONTRACT_VERSION, TracingControlProtocolKeys.OPERATION)));

        return new TracingControlProtocolSchema(requests);
    }

    RequestSchema requestFor(TracingControlProtocolOperation operation) {
        return requestsByOperation.get(operation);
    }

    private static Map<String, TracingControlProtocolFieldDescriptor> envelopeFields() {
        Map<String, TracingControlProtocolFieldDescriptor> fields = new LinkedHashMap<>();
        put(fields, TracingControlProtocolKeys.CONTRACT_VERSION, TracingControlProtocolFieldType.INTEGER);
        put(fields, TracingControlProtocolKeys.OPERATION, TracingControlProtocolFieldType.STRING);
        put(fields, TracingControlProtocolKeys.SOURCE, TracingControlProtocolFieldType.STRING);
        return fields;
    }

    private static Map<String, TracingControlProtocolFieldDescriptor> runtimePolicyFields() {
        Map<String, TracingControlProtocolFieldDescriptor> fields = new LinkedHashMap<>();
        put(fields, TracingControlProtocolKeys.SAMPLING_RATIO, TracingControlProtocolFieldType.DOUBLE);
        put(fields, TracingControlProtocolKeys.SAMPLING_ROUTE_RATIOS, TracingControlProtocolFieldType.ROUTE_RATIOS_MAP);
        put(fields, TracingControlProtocolKeys.SAMPLING_KILL_SWITCH_ENABLED, TracingControlProtocolFieldType.BOOLEAN);
        put(fields, TracingControlProtocolKeys.SAMPLING_QA_TRACE_ENABLED, TracingControlProtocolFieldType.BOOLEAN);
        put(fields, TracingControlProtocolKeys.SAMPLING_FORCE_HEADER_ENABLED, TracingControlProtocolFieldType.BOOLEAN);
        put(fields, TracingControlProtocolKeys.SAMPLING_FORCE_HEADER_VALUES, TracingControlProtocolFieldType.STRING_ARRAY);
        put(fields, TracingControlProtocolKeys.SAMPLING_DROP_PATH_PREFIXES, TracingControlProtocolFieldType.STRING_ARRAY);
        put(fields, TracingControlProtocolKeys.SCRUBBING_ENABLED, TracingControlProtocolFieldType.BOOLEAN);
        put(fields, TracingControlProtocolKeys.SCRUBBING_MODE, TracingControlProtocolFieldType.STRING);
        put(fields, TracingControlProtocolKeys.SCRUBBING_RULE_NAMES, TracingControlProtocolFieldType.STRING_ARRAY);
        put(fields, TracingControlProtocolKeys.VALIDATION_ENABLED, TracingControlProtocolFieldType.BOOLEAN);
        put(fields, TracingControlProtocolKeys.VALIDATION_MODE, TracingControlProtocolFieldType.STRING);
        put(fields, TracingControlProtocolKeys.VALIDATION_STRICT, TracingControlProtocolFieldType.BOOLEAN);
        put(fields, TracingControlProtocolKeys.ENRICHING_ENABLED, TracingControlProtocolFieldType.BOOLEAN);
        put(fields, TracingControlProtocolKeys.EXPORT_ENABLED, TracingControlProtocolFieldType.BOOLEAN);
        put(fields, TracingControlProtocolKeys.PROPAGATION_ENABLED, TracingControlProtocolFieldType.BOOLEAN);
        return fields;
    }

    private static Map<String, TracingControlProtocolFieldDescriptor> diagnosticFields() {
        Map<String, TracingControlProtocolFieldDescriptor> fields = new LinkedHashMap<>();
        put(fields, TracingControlProtocolKeys.DIAGNOSTICS_REQUEST_ID, TracingControlProtocolFieldType.STRING);
        put(fields, TracingControlProtocolKeys.DIAGNOSTICS_TIMESTAMP, TracingControlProtocolFieldType.LONG);
        return fields;
    }

    private static Map<String, TracingControlProtocolFieldDescriptor> merge(Map<String, TracingControlProtocolFieldDescriptor> first,
                                                                            Map<String, TracingControlProtocolFieldDescriptor> second) {
        Map<String, TracingControlProtocolFieldDescriptor> merged = new LinkedHashMap<>(first);
        merged.putAll(second);
        return merged;
    }

    private static Map<String, TracingControlProtocolFieldDescriptor> merge(Map<String, TracingControlProtocolFieldDescriptor> first,
                                                                            Map<String, TracingControlProtocolFieldDescriptor> second,
                                                                            Map<String, TracingControlProtocolFieldDescriptor> third) {
        Map<String, TracingControlProtocolFieldDescriptor> merged = merge(first, second);
        merged.putAll(third);
        return merged;
    }

    private static void put(Map<String, TracingControlProtocolFieldDescriptor> fields,
                            String key,
                            TracingControlProtocolFieldType type) {
        fields.put(key, new TracingControlProtocolFieldDescriptor(key, type));
    }

    record RequestSchema(Map<String, TracingControlProtocolFieldDescriptor> descriptorsByKey,
                         Set<String> requiredKeys) {

        RequestSchema {
            descriptorsByKey = Collections.unmodifiableMap(new LinkedHashMap<>(descriptorsByKey));
            requiredKeys = Set.copyOf(requiredKeys);
        }

        TracingControlProtocolFieldDescriptor descriptorOf(String key) {
            return descriptorsByKey.get(key);
        }
    }
}
