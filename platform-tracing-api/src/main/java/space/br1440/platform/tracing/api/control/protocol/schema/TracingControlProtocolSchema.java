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

    public TracingControlProtocolTypes typeOf(String key) {
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

        putRequired(fields, TracingControlProtocolKeys.CONTRACT_VERSION, TracingControlProtocolTypes.INTEGER,
                TracingControlProtocolFieldCategory.ENVELOPE);
        putRequired(fields, TracingControlProtocolKeys.OPERATION, TracingControlProtocolTypes.STRING,
                TracingControlProtocolFieldCategory.ENVELOPE);
        put(fields, TracingControlProtocolKeys.SOURCE, TracingControlProtocolTypes.STRING,
                TracingControlProtocolFieldCategory.ENVELOPE);

        put(fields, TracingControlProtocolKeys.SAMPLING_RATIO, TracingControlProtocolTypes.DOUBLE,
                TracingControlProtocolFieldCategory.RUNTIME_POLICY);
        put(fields, TracingControlProtocolKeys.SAMPLING_ROUTE_RATIOS, TracingControlProtocolTypes.ROUTE_RATIOS_MAP,
                TracingControlProtocolFieldCategory.RUNTIME_POLICY);
        put(fields, TracingControlProtocolKeys.SAMPLING_KILL_SWITCH_ENABLED, TracingControlProtocolTypes.BOOLEAN,
                TracingControlProtocolFieldCategory.RUNTIME_POLICY);
        put(fields, TracingControlProtocolKeys.SAMPLING_QA_TRACE_ENABLED, TracingControlProtocolTypes.BOOLEAN,
                TracingControlProtocolFieldCategory.RUNTIME_POLICY);
        put(fields, TracingControlProtocolKeys.SAMPLING_FORCE_HEADER_ENABLED, TracingControlProtocolTypes.BOOLEAN,
                TracingControlProtocolFieldCategory.RUNTIME_POLICY);
        put(fields, TracingControlProtocolKeys.SAMPLING_FORCE_HEADER_VALUES, TracingControlProtocolTypes.STRING_ARRAY,
                TracingControlProtocolFieldCategory.RUNTIME_POLICY);
        put(fields, TracingControlProtocolKeys.SAMPLING_DROP_PATH_PREFIXES, TracingControlProtocolTypes.STRING_ARRAY,
                TracingControlProtocolFieldCategory.RUNTIME_POLICY);

        put(fields, TracingControlProtocolKeys.SCRUBBING_ENABLED, TracingControlProtocolTypes.BOOLEAN,
                TracingControlProtocolFieldCategory.RUNTIME_POLICY);
        put(fields, TracingControlProtocolKeys.SCRUBBING_MODE, TracingControlProtocolTypes.STRING,
                TracingControlProtocolFieldCategory.RUNTIME_POLICY);
        put(fields, TracingControlProtocolKeys.SCRUBBING_RULE_NAMES, TracingControlProtocolTypes.STRING_ARRAY,
                TracingControlProtocolFieldCategory.RUNTIME_POLICY);

        put(fields, TracingControlProtocolKeys.VALIDATION_ENABLED, TracingControlProtocolTypes.BOOLEAN,
                TracingControlProtocolFieldCategory.RUNTIME_POLICY);
        put(fields, TracingControlProtocolKeys.VALIDATION_MODE, TracingControlProtocolTypes.STRING,
                TracingControlProtocolFieldCategory.RUNTIME_POLICY);
        put(fields, TracingControlProtocolKeys.VALIDATION_STRICT, TracingControlProtocolTypes.BOOLEAN,
                TracingControlProtocolFieldCategory.RUNTIME_POLICY);

        put(fields, TracingControlProtocolKeys.ENRICHING_ENABLED, TracingControlProtocolTypes.BOOLEAN,
                TracingControlProtocolFieldCategory.RUNTIME_POLICY);

        put(fields, TracingControlProtocolKeys.EXPORT_ENABLED, TracingControlProtocolTypes.BOOLEAN,
                TracingControlProtocolFieldCategory.RUNTIME_POLICY);
        put(fields, TracingControlProtocolKeys.PROPAGATION_ENABLED, TracingControlProtocolTypes.BOOLEAN,
                TracingControlProtocolFieldCategory.RUNTIME_POLICY);

        put(fields, TracingControlProtocolKeys.DIAGNOSTICS_REQUEST_ID, TracingControlProtocolTypes.STRING,
                TracingControlProtocolFieldCategory.DIAGNOSTIC);
        put(fields, TracingControlProtocolKeys.DIAGNOSTICS_TIMESTAMP, TracingControlProtocolTypes.LONG,
                TracingControlProtocolFieldCategory.DIAGNOSTIC);

        put(fields, TracingControlProtocolKeys.TOPOLOGY_EXPORTER_ENDPOINT, TracingControlProtocolTypes.STRING,
                TracingControlProtocolFieldCategory.STARTUP_TOPOLOGY);
        put(fields, TracingControlProtocolKeys.TOPOLOGY_EXPORTER_PROTOCOL, TracingControlProtocolTypes.STRING,
                TracingControlProtocolFieldCategory.STARTUP_TOPOLOGY);
        put(fields, TracingControlProtocolKeys.TOPOLOGY_EXPORTER_QUEUE_SIZE, TracingControlProtocolTypes.INTEGER,
                TracingControlProtocolFieldCategory.STARTUP_TOPOLOGY);
        put(fields, TracingControlProtocolKeys.TOPOLOGY_SDK_MODE, TracingControlProtocolTypes.STRING,
                TracingControlProtocolFieldCategory.STARTUP_TOPOLOGY);
        put(fields, TracingControlProtocolKeys.TOPOLOGY_QUEUE_SIZE, TracingControlProtocolTypes.INTEGER,
                TracingControlProtocolFieldCategory.STARTUP_TOPOLOGY);

        return fields;
    }

    @SuppressWarnings("SameParameterValue")
    private static void putRequired(Map<String, TracingControlProtocolFieldDescriptor> fields,
                                    String key,
                                    TracingControlProtocolTypes type,
                                    TracingControlProtocolFieldCategory category) {
        fields.put(key, new TracingControlProtocolFieldDescriptor(key, type, category, ALL_OPERATIONS));
    }

    private static void put(Map<String, TracingControlProtocolFieldDescriptor> fields,
                            String key,
                            TracingControlProtocolTypes type,
                            TracingControlProtocolFieldCategory category) {
        fields.put(key, new TracingControlProtocolFieldDescriptor(key, type, category, Set.of()));
    }
}
