package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

import lombok.experimental.UtilityClass;

import space.br1440.platform.tracing.autoconfigure.control.jmx.protocol.PrivateControlWireTypes.WireField;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedSettingsWithSequence;

@UtilityClass
public final class AcceptedCompleteSnapshotDecoder {

    private static final String OPERATION = "readAcceptedComplete";

    private static final int FIXED_LOGICAL_PAYLOAD_BYTES = 64;
    private static final int ROUTE_ENTRY_LOGICAL_OVERHEAD_BYTES = 16;

    public static AcceptedSettingsWithSequence decode(Object raw) {
        CompositeData response = requireCompositeData(raw);
        requireExpectedType(response);
        requirePayloadWithinLimit(response);

        long acceptedSequence = requireAcceptedSequence(response);
        String runtimeInstanceId = requireRuntimeInstanceId(response);
        CompleteTracingSettings snapshot = decodePolicy(response);

        return new AcceptedSettingsWithSequence(acceptedSequence, runtimeInstanceId, snapshot);
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
        long acceptedSequence = requireField(response, WireField.ACCEPTED_SEQUENCE, Long.class);
        if (acceptedSequence < 0L) {
            throw invalid(WireField.ACCEPTED_SEQUENCE.wireName() + " must be >= 0, got " + acceptedSequence);
        }

        return acceptedSequence;
    }

    private static String requireRuntimeInstanceId(CompositeData response) {
        String runtimeInstanceId = requireField(response, WireField.RUNTIME_INSTANCE_ID, String.class);
        if (runtimeInstanceId.isBlank()) {
            throw invalid(WireField.RUNTIME_INSTANCE_ID.wireName() + " must not be blank");
        }

        return runtimeInstanceId;
    }

    private static CompleteTracingSettings decodePolicy(CompositeData response) {
        double samplingRatio = requireRatio(response, WireField.SAMPLING_RATIO);
        String diagnosticsLogLevel = requireLogLevel(response);

        TabularData routeRatiosRawData = requireField(response, WireField.SAMPLING_ROUTE_RATIOS, TabularData.class);
        Map<String, Double> routeRatios = decodeRouteRatios(routeRatiosRawData);

        return CompleteTracingSettings.builder()
                .samplingEnabled(requireField(response, WireField.SAMPLING_ENABLED, Boolean.class))
                .samplingRatio(samplingRatio)
                .samplingRouteRatios(routeRatios)
                .validationEnabled(requireField(response, WireField.VALIDATION_ENABLED, Boolean.class))
                .validationStrict(requireField(response, WireField.VALIDATION_STRICT, Boolean.class))
                .exporterEnabled(requireField(response, WireField.EXPORTER_ENABLED, Boolean.class))
                .traceControlHeadersEnabled(requireField(response, WireField.TRACE_CONTROL_HEADERS_ENABLED, Boolean.class))
                .diagnosticsLogLevel(diagnosticsLogLevel)
                .build();
    }

    @SuppressWarnings("SameParameterValue")
    private static double requireRatio(CompositeData response, WireField field) {
        double ratio = requireField(response, field, Double.class);
        requireValidRatio(ratio, field.wireName());
        return ratio;
    }

    private static String requireLogLevel(CompositeData response) {
        String logLevel = requireField(response, WireField.DIAGNOSTICS_LOG_LEVEL, String.class);
        if (!PrivateControlWireTypes.VALID_LOG_LEVELS.contains(logLevel)) {
            throw invalid(WireField.DIAGNOSTICS_LOG_LEVEL.wireName() + " is not a recognized level: " + logLevel);
        }

        return logLevel;
    }

    private static int logicalPayloadSize(CompositeData response) {
        int size = FIXED_LOGICAL_PAYLOAD_BYTES;

        Object runtimeId = response.get(WireField.RUNTIME_INSTANCE_ID.wireName());
        if (runtimeId instanceof String text) {
            size += text.getBytes(StandardCharsets.UTF_8).length;
        }

        Object logLevel = response.get(WireField.DIAGNOSTICS_LOG_LEVEL.wireName());
        if (logLevel instanceof String text) {
            size += text.getBytes(StandardCharsets.UTF_8).length;
        }

        Object routes = response.get(WireField.SAMPLING_ROUTE_RATIOS.wireName());
        if (routes instanceof TabularData tabular) {
            for (Object rawRow : tabular.values()) {
                if (rawRow instanceof CompositeData row) {
                    Object prefix = row.get(WireField.ROUTE_PREFIX.wireName());
                    if (prefix instanceof String text) {
                        size += text.getBytes(StandardCharsets.UTF_8).length + ROUTE_ENTRY_LOGICAL_OVERHEAD_BYTES;
                    }
                }
            }
        }

        return size;
    }

    private static <T> T requireField(CompositeData cd, WireField field, Class<T> javaType) {
        Object value = cd.get(field.wireName());
        if (value == null) {
            throw invalid("field '" + field.wireName() + "' is null");
        }

        if (!javaType.isInstance(value)) {
            throw invalid("field '" + field.wireName() + "' has unexpected type " + value.getClass().getName());
        }

        return javaType.cast(value);
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
            throw invalid(WireField.SAMPLING_ROUTE_RATIOS.wireName() + " has " + tabular.size()
                    + " entries, exceeding the maximum of " + PrivateControlWireTypes.MAX_ROUTE_ENTRIES);
        }

        Map<String, Double> result = new LinkedHashMap<>();
        for (Object rawRow : tabular.values()) {
            CompositeData row = requireRouteRow(rawRow);
            String prefix = requireRoutePrefix(row);

            double ratio = requireField(row, WireField.ROUTE_RATIO, Double.class);
            requireValidRatio(ratio, WireField.SAMPLING_ROUTE_RATIOS.wireName() + ".entry");

            if (result.put(prefix, ratio) != null) {
                throw invalid("duplicate route prefix in " + WireField.SAMPLING_ROUTE_RATIOS.wireName());
            }
        }

        return Collections.unmodifiableMap(result);
    }

    private static CompositeData requireRouteRow(Object rawRow) {
        if (rawRow instanceof CompositeData row) {
            return row;
        }

        String type = (rawRow == null) ? "null" : rawRow.getClass().getName();
        throw invalid(WireField.SAMPLING_ROUTE_RATIOS.wireName() + " row has unexpected type " + type);
    }

    private static String requireRoutePrefix(CompositeData row) {
        String prefix = requireField(row, WireField.ROUTE_PREFIX, String.class);
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
