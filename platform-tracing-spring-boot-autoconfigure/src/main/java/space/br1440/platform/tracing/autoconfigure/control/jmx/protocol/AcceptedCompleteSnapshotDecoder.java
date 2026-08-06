package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

import lombok.experimental.UtilityClass;

import space.br1440.platform.tracing.autoconfigure.control.policy.CompleteTracingPolicySnapshot;
import space.br1440.platform.tracing.autoconfigure.control.policy.read.AcceptedSnapshotWithSequence;

@UtilityClass
public final class AcceptedCompleteSnapshotDecoder {

    private static final String OPERATION = "readAcceptedComplete";
    private static final String ACCEPTED_SEQUENCE = "acceptedSequence";
    private static final String RUNTIME_INSTANCE_ID = "runtimeInstanceId";
    private static final String SAMPLING_ENABLED = "samplingEnabled";
    private static final String SAMPLING_RATIO = "samplingRatio";
    private static final String SAMPLING_ROUTE_RATIOS = "samplingRouteRatios";
    private static final String VALIDATION_ENABLED = "validationEnabled";
    private static final String VALIDATION_STRICT = "validationStrict";
    private static final String EXPORTER_ENABLED = "exporterEnabled";
    private static final String TRACE_CONTROL_HEADERS_ENABLED = "traceControlHeadersEnabled";
    private static final String DIAGNOSTICS_LOG_LEVEL = "diagnosticsLogLevel";
    private static final String ROUTE_PREFIX = "routePrefix";
    private static final String ROUTE_RATIO = "ratio";

    private static final int FIXED_LOGICAL_PAYLOAD_BYTES = 64;
    private static final int ROUTE_ENTRY_LOGICAL_OVERHEAD_BYTES = 16;

    public static AcceptedSnapshotWithSequence decode(Object raw) {
        CompositeData response = requireCompositeData(raw);
        requireExpectedType(response);
        requirePayloadWithinLimit(response);

        long acceptedSequence = requireAcceptedSequence(response);
        String runtimeInstanceId = requireRuntimeInstanceId(response);
        CompleteTracingPolicySnapshot snapshot = decodePolicy(response);

        return new AcceptedSnapshotWithSequence(acceptedSequence, runtimeInstanceId, snapshot);
    }

    private static CompositeData requireCompositeData(Object raw) {
        if (raw instanceof CompositeData response) {
            return response;
        }

        String dataClassName = (raw == null) ? "null" : raw.getClass().getName();
        throw invalid("expected CompositeData, got " + dataClassName);
    }

    private static void requireExpectedType(CompositeData response) {
        if (!PrivateControlWireTypes.COMPLETE_RESPONSE.equals(response.getCompositeType())) {
            throw invalid("unexpected CompositeType "
                    + response.getCompositeType().getTypeName()
                    + "; expected " + PrivateControlWireTypes.COMPLETE_RESPONSE.getTypeName());
        }
    }

    private static void requirePayloadWithinLimit(CompositeData response) {
        if (logicalPayloadSize(response) > PrivateControlWireTypes.MAX_LOGICAL_ACCEPTED_COMPLETE_BYTES) {
            throw invalid("logical payload exceeds " + PrivateControlWireTypes.MAX_LOGICAL_ACCEPTED_COMPLETE_BYTES + " bytes");
        }
    }

    private static long requireAcceptedSequence(CompositeData response) {
        long acceptedSequence = requireField(response, ACCEPTED_SEQUENCE, Long.class);
        if (acceptedSequence < 0L) {
            throw invalid(ACCEPTED_SEQUENCE + " must be >= 0, got " + acceptedSequence);
        }

        return acceptedSequence;
    }

    private static String requireRuntimeInstanceId(CompositeData response) {
        String runtimeInstanceId = requireField(response, RUNTIME_INSTANCE_ID, String.class);
        if (runtimeInstanceId.isBlank()) {
            throw invalid(RUNTIME_INSTANCE_ID + " must not be blank");
        }

        return runtimeInstanceId;
    }

    private static CompleteTracingPolicySnapshot decodePolicy(CompositeData response) {
        double samplingRatio = requireRatio(response, SAMPLING_RATIO);
        String diagnosticsLogLevel = requireLogLevel(response);

        TabularData routeRatiosRawData = requireField(response, SAMPLING_ROUTE_RATIOS, TabularData.class);
        Map<String, Double> routeRatios = decodeRouteRatios(routeRatiosRawData);

        return CompleteTracingPolicySnapshot.builder()
                .samplingEnabled(requireField(response, SAMPLING_ENABLED, Boolean.class))
                .samplingRatio(samplingRatio)
                .samplingRouteRatios(routeRatios)
                .validationEnabled(requireField(response, VALIDATION_ENABLED, Boolean.class))
                .validationStrict(requireField(response, VALIDATION_STRICT, Boolean.class))
                .exporterEnabled(requireField(response, EXPORTER_ENABLED, Boolean.class))
                .traceControlHeadersEnabled(requireField(response, TRACE_CONTROL_HEADERS_ENABLED, Boolean.class))
                .diagnosticsLogLevel(diagnosticsLogLevel)
                .build();
    }

    @SuppressWarnings("SameParameterValue")
    private static double requireRatio(CompositeData response, String fieldName) {
        double ratio = requireField(response, fieldName, Double.class);
        requireValidRatio(ratio, fieldName);
        return ratio;
    }

    private static String requireLogLevel(CompositeData response) {
        String logLevel = requireField(response, DIAGNOSTICS_LOG_LEVEL, String.class);
        if (!PrivateControlWireTypes.VALID_LOG_LEVELS.contains(logLevel)) {
            throw invalid(DIAGNOSTICS_LOG_LEVEL + " is not a recognized level: " + logLevel);
        }

        return logLevel;
    }

    private static int logicalPayloadSize(CompositeData response) {
        int size = FIXED_LOGICAL_PAYLOAD_BYTES;

        Object runtimeId = response.get(RUNTIME_INSTANCE_ID);
        if (runtimeId instanceof String text) {
            size += text.getBytes(StandardCharsets.UTF_8).length;
        }

        Object logLevel = response.get(DIAGNOSTICS_LOG_LEVEL);
        if (logLevel instanceof String text) {
            size += text.getBytes(StandardCharsets.UTF_8).length;
        }

        Object routes = response.get(SAMPLING_ROUTE_RATIOS);
        if (routes instanceof TabularData tabular) {
            for (Object rawRow : tabular.values()) {
                if (rawRow instanceof CompositeData row) {
                    Object prefix = row.get(ROUTE_PREFIX);
                    if (prefix instanceof String text) {
                        size += text.getBytes(StandardCharsets.UTF_8).length + ROUTE_ENTRY_LOGICAL_OVERHEAD_BYTES;
                    }
                }
            }
        }

        return size;
    }

    private static <T> T requireField(CompositeData cd, String key, Class<T> type) {
        Object value = cd.get(key);
        if (value == null) {
            throw invalid("field '" + key + "' is null");
        }

        if (!type.isInstance(value)) {
            throw invalid("field '" + key + "' has unexpected type " + value.getClass().getName());
        }

        return type.cast(value);
    }

    private static void requireValidRatio(double ratio, String fieldName) {
        if (!Double.isFinite(ratio)) {
            throw invalid(fieldName + " must be finite, got " + ratio);
        }

        if (ratio < 0.0 || ratio > 1.0) {
            throw invalid(fieldName + " must be within [0.0, 1.0], got " + ratio);
        }
    }

    private static Map<String, Double> decodeRouteRatios(TabularData tabular) {
        if (tabular.isEmpty()) {
            return Collections.emptyMap();
        }

        if (tabular.size() > PrivateControlWireTypes.MAX_ROUTE_ENTRIES) {
            throw invalid(SAMPLING_ROUTE_RATIOS + " has " + tabular.size()
                    + " entries, exceeding the maximum of " + PrivateControlWireTypes.MAX_ROUTE_ENTRIES);
        }

        Map<String, Double> result = new LinkedHashMap<>();
        for (Object rawRow : tabular.values()) {
            CompositeData row = requireRouteRow(rawRow);
            String prefix = requireRoutePrefix(row);

            double ratio = requireField(row, ROUTE_RATIO, Double.class);
            requireValidRatio(ratio, SAMPLING_ROUTE_RATIOS + ".entry");

            if (result.put(prefix, ratio) != null) {
                throw invalid("duplicate route prefix in " + SAMPLING_ROUTE_RATIOS);
            }
        }

        return Collections.unmodifiableMap(result);
    }

    private static CompositeData requireRouteRow(Object rawRow) {
        if (rawRow instanceof CompositeData row) {
            return row;
        }

        String type = (rawRow == null) ? "null" : rawRow.getClass().getName();
        throw invalid(SAMPLING_ROUTE_RATIOS + " row has unexpected type " + type);
    }

    private static String requireRoutePrefix(CompositeData row) {
        String prefix = requireField(row, ROUTE_PREFIX, String.class);
        if (prefix.isBlank()) {
            throw invalid("route prefix must not be blank");
        }

        if (prefix.length() > PrivateControlWireTypes.MAX_ROUTE_PREFIX_LENGTH) {
            throw invalid("route prefix length " + prefix.length()
                    + " is invalid (must be 1.." + PrivateControlWireTypes.MAX_ROUTE_PREFIX_LENGTH
                    + " UTF-16 code units)");
        }

        if (prefix.chars().anyMatch(Character::isISOControl)) {
            throw invalid("route prefix must not contain ISO control characters");
        }

        return prefix;
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException(OPERATION + ": " + reason);
    }
}
