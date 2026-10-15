package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedSettingsAuthority;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsRevision;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class AcceptedPolicySnapshotDecoder {

    private static final String CONTRACT_POINT = JmxWireContract.ATTRIBUTE_SETTINGS_STATUS_ACCEPTED_POLICY_SNAPSHOT;
    private static final String ACCEPTED_SEQUENCE = JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_ACCEPTED_SEQUENCE;
    private static final String AGENT_INSTANCE_ID = JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_AGENT_INSTANCE_ID;
    private static final String SOURCE_AUTHORITY = JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SOURCE_AUTHORITY;
    private static final String SAMPLING_ENABLED = JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_ENABLED;
    private static final String SAMPLING_RATIO = JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_RATIO;
    private static final String SAMPLING_ROUTE_RATIOS = JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_ROUTE_RATIOS;
    private static final String VALIDATION_LEVEL = JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_VALIDATION_LEVEL;
    private static final String EXPORTER_ENABLED = JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_EXPORTER_ENABLED;
    private static final String TRACE_CONTROL_HEADERS_ENABLED = JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_TRACE_CONTROL_HEADERS_ENABLED;
    private static final String DIAGNOSTICS_LOG_LEVEL = JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_DIAGNOSTICS_LOG_LEVEL;
    private static final String ROUTE_PREFIX = JmxWireContract.FIELD_ROUTE_RATIO_ROUTE_PREFIX;
    private static final String ROUTE_RATIO = JmxWireContract.FIELD_ROUTE_RATIO_RATIO;

    public static AcceptedSettings decode(Object raw) {
        CompositeData snapshot = requireSnapshot(raw);

        SettingsRevision revision = decodeRevision(snapshot);
        AcceptedSettingsAuthority authority = authority(field(snapshot, SOURCE_AUTHORITY, String.class));
        CompleteTracingSettings policy = decodePolicy(snapshot);

        return new AcceptedSettings(revision, policy, authority);
    }

    private static SettingsRevision decodeRevision(CompositeData snapshot) {
        String agentInstanceId = field(snapshot, AGENT_INSTANCE_ID, String.class);
        long acceptedSequence = field(snapshot, ACCEPTED_SEQUENCE, Long.class);
        return construct("revision", () -> new SettingsRevision(agentInstanceId, acceptedSequence));
    }

    private static CompleteTracingSettings decodePolicy(CompositeData snapshot) {
        boolean samplingEnabled = field(snapshot, SAMPLING_ENABLED, Boolean.class);
        double samplingRatio = field(snapshot, SAMPLING_RATIO, Double.class);
        Map<String, Double> routeRatios = routeRatios(field(snapshot, SAMPLING_ROUTE_RATIOS, TabularData.class));
        String validationLevel = field(snapshot, VALIDATION_LEVEL, String.class);
        boolean exporterEnabled = field(snapshot, EXPORTER_ENABLED, Boolean.class);
        boolean traceControlHeadersEnabled = field(snapshot, TRACE_CONTROL_HEADERS_ENABLED, Boolean.class);
        String diagnosticsLogLevel = field(snapshot, DIAGNOSTICS_LOG_LEVEL, String.class);

        if (!JmxWireContract.VALID_VALIDATION_LEVELS.contains(validationLevel)) {
            throw invalid(VALIDATION_LEVEL + " has unsupported value");
        }

        if (!JmxWireContract.VALID_LOG_LEVELS.contains(diagnosticsLogLevel)) {
            throw invalid(DIAGNOSTICS_LOG_LEVEL + " has unsupported value");
        }

        return construct("policy", () -> CompleteTracingSettings.builder()
                .samplingEnabled(samplingEnabled)
                .samplingRatio(samplingRatio)
                .samplingRouteRatios(routeRatios)
                .validationLevel(validationLevel)
                .exporterEnabled(exporterEnabled)
                .traceControlHeadersEnabled(traceControlHeadersEnabled)
                .diagnosticsLogLevel(diagnosticsLogLevel)
                .build());
    }

    private static CompositeData requireSnapshot(Object raw) {
        if (!(raw instanceof CompositeData snapshot)) {
            throw invalid("expected CompositeData, got " + typeName(raw));
        }

        if (!JmxOpenTypes.ACCEPTED_POLICY_SNAPSHOT.equals(snapshot.getCompositeType())) {
            throw invalid("unexpected CompositeType %s; expected %s".formatted(
                    snapshot.getCompositeType().getTypeName(),
                    JmxOpenTypes.ACCEPTED_POLICY_SNAPSHOT.getTypeName()));
        }

        return snapshot;
    }

    private static AcceptedSettingsAuthority authority(String value) {
        try {
            return AcceptedSettingsAuthority.valueOf(value);
        } catch (IllegalArgumentException unsupported) {
            throw invalid("%s has unsupported value '%s'".formatted(SOURCE_AUTHORITY, value), unsupported);
        }
    }

    private static Map<String, Double> routeRatios(TabularData table) {
        Map<String, Double> routeRatios = new LinkedHashMap<>();

        for (Object rawRow : table.values()) {
            if (!(rawRow instanceof CompositeData row)) {
                throw invalid("%s row has unexpected type %s".formatted(SAMPLING_ROUTE_RATIOS, typeName(rawRow)));
            }

            String prefix = field(row, ROUTE_PREFIX, String.class);
            if (routeRatios.put(prefix, field(row, ROUTE_RATIO, Double.class)) != null) {
                throw invalid("duplicate route prefix in " + SAMPLING_ROUTE_RATIOS);
            }
        }

        return Collections.unmodifiableMap(routeRatios);
    }

    private static <T> T field(CompositeData data, String name, Class<T> javaType) {
        Object value = data.get(name);
        if (value == null) {
            throw invalid("field '%s' is null".formatted(name));
        }

        if (!javaType.isInstance(value)) {
            throw invalid("field '%s' has unexpected type %s".formatted(name, value.getClass().getName()));
        }

        return javaType.cast(value);
    }

    private static <T> T construct(String part, Supplier<T> constructor) {
        try {
            return constructor.get();
        } catch (IllegalArgumentException violation) {
            throw invalid(part + " violates domain invariant: " + violation.getMessage(), violation);
        }
    }

    private static String typeName(Object value) {
        return (value == null) ? "null" : value.getClass().getName();
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException(CONTRACT_POINT + ": " + reason);
    }

    private static IllegalArgumentException invalid(String reason, Throwable cause) {
        return new IllegalArgumentException(CONTRACT_POINT + ": " + reason, cause);
    }
}
