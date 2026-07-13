package space.br1440.platform.tracing.api.control.protocol.schema;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class TracingControlProtocolSchema {

    private static final Set<TracingControlProtocolOperation> ALL_OPERATIONS = EnumSet.allOf(TracingControlProtocolOperation.class);

    private final int contractVersion;
    private final Map<String, TracingControlProtocolFieldDescriptor> fieldsByKey;

    TracingControlProtocolSchema(int contractVersion, Map<String, TracingControlProtocolFieldDescriptor> fieldsByKey) {
        this.contractVersion = contractVersion;
        this.fieldsByKey = Collections.unmodifiableMap(new LinkedHashMap<>(fieldsByKey));
    }

    public static TracingControlProtocolSchema forMajor(int major) {
        if (major != 1) {
            throw new IllegalArgumentException("Unsupported protocol major: " + major);
        }

        return new TracingControlProtocolSchema(1, buildV1Fields());
    }

    public int contractVersion() {
        return contractVersion;
    }

    public Set<String> knownKeys() {
        return Collections.unmodifiableSet(fieldsByKey.keySet());
    }

    public TracingControlProtocolFieldDescriptor descriptorOf(String key) {
        return fieldsByKey.get(key);
    }

    public boolean isKnownKey(String key) {
        return fieldsByKey.containsKey(key);
    }

    public TracingControlProtocolFieldCategory categoryOf(String key) {
        TracingControlProtocolFieldDescriptor descriptor = fieldsByKey.get(key);
        return (descriptor == null) ? null : descriptor.category();
    }

    public TracingControlProtocolFieldType typeOf(String key) {
        TracingControlProtocolFieldDescriptor descriptor = fieldsByKey.get(key);
        return (descriptor == null) ? null : descriptor.type();
    }

    public boolean isTopologyKey(String key) {
        return (categoryOf(key) == TracingControlProtocolFieldCategory.STARTUP_TOPOLOGY);
    }

    public boolean isRuntimePolicyKey(String key) {
        return (categoryOf(key) == TracingControlProtocolFieldCategory.RUNTIME_POLICY);
    }

    public boolean isDiagnosticKey(String key) {
        return (categoryOf(key) == TracingControlProtocolFieldCategory.DIAGNOSTIC);
    }

    public boolean isEnvelopeKey(String key) {
        return (categoryOf(key) == TracingControlProtocolFieldCategory.ENVELOPE);
    }

    public Set<String> requiredKeysFor(TracingControlProtocolOperation operation) {
        Objects.requireNonNull(operation, "operation");

        return fieldsByKey.values().stream()
                .filter(descriptor -> descriptor.requiredForOperations().contains(operation))
                .map(TracingControlProtocolFieldDescriptor::key)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Map<String, TracingControlProtocolFieldDescriptor> buildV1Fields() {
        Map<String, TracingControlProtocolFieldDescriptor> fields = new LinkedHashMap<>();

        putRequired(fields, TracingControlProtocolKeys.CONTRACT_VERSION, TracingControlProtocolFieldType.INTEGER,
                TracingControlProtocolFieldCategory.ENVELOPE);
        putRequired(fields, TracingControlProtocolKeys.OPERATION, TracingControlProtocolFieldType.STRING,
                TracingControlProtocolFieldCategory.ENVELOPE);
        put(fields, TracingControlProtocolKeys.SOURCE, TracingControlProtocolFieldType.STRING,
                TracingControlProtocolFieldCategory.ENVELOPE);

        put(fields, TracingControlProtocolKeys.SAMPLING_RATIO, TracingControlProtocolFieldType.DOUBLE,
                TracingControlProtocolFieldCategory.RUNTIME_POLICY);
        put(fields, TracingControlProtocolKeys.SAMPLING_ROUTE_RATIOS, TracingControlProtocolFieldType.ROUTE_RATIOS_MAP,
                TracingControlProtocolFieldCategory.RUNTIME_POLICY);
        put(fields, TracingControlProtocolKeys.SAMPLING_KILL_SWITCH_ENABLED, TracingControlProtocolFieldType.BOOLEAN,
                TracingControlProtocolFieldCategory.RUNTIME_POLICY);
        put(fields, TracingControlProtocolKeys.SAMPLING_QA_TRACE_ENABLED, TracingControlProtocolFieldType.BOOLEAN,
                TracingControlProtocolFieldCategory.RUNTIME_POLICY);
        put(fields, TracingControlProtocolKeys.SAMPLING_FORCE_HEADER_ENABLED, TracingControlProtocolFieldType.BOOLEAN,
                TracingControlProtocolFieldCategory.RUNTIME_POLICY);
        put(fields, TracingControlProtocolKeys.SAMPLING_FORCE_HEADER_VALUES, TracingControlProtocolFieldType.STRING_ARRAY,
                TracingControlProtocolFieldCategory.RUNTIME_POLICY);
        put(fields, TracingControlProtocolKeys.SAMPLING_DROP_PATH_PREFIXES, TracingControlProtocolFieldType.STRING_ARRAY,
                TracingControlProtocolFieldCategory.RUNTIME_POLICY);

        put(fields, TracingControlProtocolKeys.SCRUBBING_ENABLED, TracingControlProtocolFieldType.BOOLEAN,
                TracingControlProtocolFieldCategory.RUNTIME_POLICY);
        put(fields, TracingControlProtocolKeys.SCRUBBING_MODE, TracingControlProtocolFieldType.STRING,
                TracingControlProtocolFieldCategory.RUNTIME_POLICY);
        put(fields, TracingControlProtocolKeys.SCRUBBING_RULE_NAMES, TracingControlProtocolFieldType.STRING_ARRAY,
                TracingControlProtocolFieldCategory.RUNTIME_POLICY);

        put(fields, TracingControlProtocolKeys.VALIDATION_ENABLED, TracingControlProtocolFieldType.BOOLEAN,
                TracingControlProtocolFieldCategory.RUNTIME_POLICY);
        put(fields, TracingControlProtocolKeys.VALIDATION_MODE, TracingControlProtocolFieldType.STRING,
                TracingControlProtocolFieldCategory.RUNTIME_POLICY);
        put(fields, TracingControlProtocolKeys.VALIDATION_STRICT, TracingControlProtocolFieldType.BOOLEAN,
                TracingControlProtocolFieldCategory.RUNTIME_POLICY);

        put(fields, TracingControlProtocolKeys.ENRICHING_ENABLED, TracingControlProtocolFieldType.BOOLEAN,
                TracingControlProtocolFieldCategory.RUNTIME_POLICY);

        put(fields, TracingControlProtocolKeys.EXPORT_ENABLED, TracingControlProtocolFieldType.BOOLEAN,
                TracingControlProtocolFieldCategory.RUNTIME_POLICY);
        put(fields, TracingControlProtocolKeys.PROPAGATION_ENABLED, TracingControlProtocolFieldType.BOOLEAN,
                TracingControlProtocolFieldCategory.RUNTIME_POLICY);

        put(fields, TracingControlProtocolKeys.DIAGNOSTICS_REQUEST_ID, TracingControlProtocolFieldType.STRING,
                TracingControlProtocolFieldCategory.DIAGNOSTIC);
        put(fields, TracingControlProtocolKeys.DIAGNOSTICS_TIMESTAMP, TracingControlProtocolFieldType.LONG,
                TracingControlProtocolFieldCategory.DIAGNOSTIC);

        put(fields, TracingControlProtocolKeys.TOPOLOGY_EXPORTER_ENDPOINT, TracingControlProtocolFieldType.STRING,
                TracingControlProtocolFieldCategory.STARTUP_TOPOLOGY);
        put(fields, TracingControlProtocolKeys.TOPOLOGY_EXPORTER_PROTOCOL, TracingControlProtocolFieldType.STRING,
                TracingControlProtocolFieldCategory.STARTUP_TOPOLOGY);
        put(fields, TracingControlProtocolKeys.TOPOLOGY_EXPORTER_QUEUE_SIZE, TracingControlProtocolFieldType.INTEGER,
                TracingControlProtocolFieldCategory.STARTUP_TOPOLOGY);
        put(fields, TracingControlProtocolKeys.TOPOLOGY_SDK_MODE, TracingControlProtocolFieldType.STRING,
                TracingControlProtocolFieldCategory.STARTUP_TOPOLOGY);
        put(fields, TracingControlProtocolKeys.TOPOLOGY_QUEUE_SIZE, TracingControlProtocolFieldType.INTEGER,
                TracingControlProtocolFieldCategory.STARTUP_TOPOLOGY);

        return fields;
    }

    @SuppressWarnings("SameParameterValue")
    private static void putRequired(Map<String, TracingControlProtocolFieldDescriptor> fields,
                                    String key,
                                    TracingControlProtocolFieldType type,
                                    TracingControlProtocolFieldCategory category) {
        fields.put(key, new TracingControlProtocolFieldDescriptor(key, type, category, ALL_OPERATIONS));
    }

    private static void put(Map<String, TracingControlProtocolFieldDescriptor> fields,
                            String key,
                            TracingControlProtocolFieldType type,
                            TracingControlProtocolFieldCategory category) {
        fields.put(key, new TracingControlProtocolFieldDescriptor(key, type, category, Set.of()));
    }
}
