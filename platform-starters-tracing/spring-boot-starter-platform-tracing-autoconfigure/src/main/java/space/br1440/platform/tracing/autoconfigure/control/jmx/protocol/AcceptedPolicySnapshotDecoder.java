package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedSettingsWithSequence;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireUtf8Meter;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class AcceptedPolicySnapshotDecoder {

    private static final String CONTRACT_POINT = JmxWireContract.ATTRIBUTE_SETTINGS_STATUS_ACCEPTED_POLICY_SNAPSHOT;
    private static final String ACCEPTED_SEQUENCE = JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_ACCEPTED_SEQUENCE;
    private static final String RUNTIME_INSTANCE_ID = JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_RUNTIME_INSTANCE_ID;
    private static final String SAMPLING_ENABLED = JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_ENABLED;
    private static final String SAMPLING_RATIO = JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_RATIO;
    private static final String SAMPLING_ROUTE_RATIOS = JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_ROUTE_RATIOS;
    private static final String VALIDATION_ENABLED = JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_VALIDATION_ENABLED;
    private static final String VALIDATION_STRICT = JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_VALIDATION_STRICT;
    private static final String EXPORTER_ENABLED = JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_EXPORTER_ENABLED;
    private static final String TRACE_CONTROL_HEADERS_ENABLED = JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_TRACE_CONTROL_HEADERS_ENABLED;
    private static final String DIAGNOSTICS_LOG_LEVEL = JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_DIAGNOSTICS_LOG_LEVEL;
    private static final String ROUTE_PREFIX = JmxWireContract.FIELD_ROUTE_RATIO_ROUTE_PREFIX;
    private static final String ROUTE_RATIO = JmxWireContract.FIELD_ROUTE_RATIO_RATIO;

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
        if (!JmxOpenTypes.ACCEPTED_POLICY_SNAPSHOT.equals(response.getCompositeType())) {
            throw invalid("unexpected CompositeType %s; expected %s".formatted(
                    response.getCompositeType().getTypeName(),
                    JmxOpenTypes.ACCEPTED_POLICY_SNAPSHOT.getTypeName())
            );
        }
    }

    private static void requirePayloadWithinLimit(CompositeData response) {
        if (logicalPayloadSize(response) > JmxWireContract.MAX_LOGICAL_BYTES) {
            throw invalid("logical payload exceeds %s bytes".formatted(JmxWireContract.MAX_LOGICAL_BYTES));
        }
    }

    private static long requireAcceptedSequence(CompositeData response) {
        long acceptedSequence = requireField(response, ACCEPTED_SEQUENCE, Long.class);
        if (acceptedSequence < 0L) {
            throw invalid("%s must be >= 0, got %s".formatted(ACCEPTED_SEQUENCE, acceptedSequence));
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

    private static CompleteTracingSettings decodePolicy(CompositeData response) {
        double samplingRatio = requireRatio(response, SAMPLING_RATIO);
        String diagnosticsLogLevel = requireLogLevel(response);

        TabularData routeRatiosRawData = requireField(response, SAMPLING_ROUTE_RATIOS, TabularData.class);
        Map<String, Double> routeRatios = decodeRouteRatios(routeRatiosRawData);

        return CompleteTracingSettings.builder()
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
    private static double requireRatio(CompositeData response, String field) {
        double ratio = requireField(response, field, Double.class);
        requireValidRatio(ratio, field);
        return ratio;
    }

    private static String requireLogLevel(CompositeData response) {
        String logLevel = requireField(response, DIAGNOSTICS_LOG_LEVEL, String.class);
        if (!CompleteTracingSettings.isRecognizedLogLevel(logLevel)) {
            throw invalid("%s is not a recognized level: %s".formatted(DIAGNOSTICS_LOG_LEVEL, logLevel));
        }

        return logLevel;
    }

    private static long logicalPayloadSize(CompositeData response) {
        long size = FIXED_LOGICAL_PAYLOAD_BYTES;

        Object runtimeId = response.get(RUNTIME_INSTANCE_ID);
        if (runtimeId instanceof String text) {
            size += JmxWireUtf8Meter.utf8Length(text);
        }

        Object logLevel = response.get(DIAGNOSTICS_LOG_LEVEL);
        if (logLevel instanceof String text) {
            size += JmxWireUtf8Meter.utf8Length(text);
        }

        Object routes = response.get(SAMPLING_ROUTE_RATIOS);
        if (routes instanceof TabularData tabular) {
            for (Object rawRow : tabular.values()) {
                if (rawRow instanceof CompositeData row) {
                    Object prefix = row.get(ROUTE_PREFIX);
                    if (prefix instanceof String text) {
                        size += JmxWireUtf8Meter.utf8Length(text) + ROUTE_ENTRY_LOGICAL_OVERHEAD_BYTES;
                    }
                }
            }
        }

        return size;
    }

    private static <T> T requireField(CompositeData cd, String field, Class<T> javaType) {
        Object value = cd.get(field);
        if (value == null) {
            throw invalid("field '%s' is null".formatted(field));
        }

        if (!javaType.isInstance(value)) {
            throw invalid("field '%s' has unexpected type %s".formatted(field, value.getClass().getName()));
        }

        return javaType.cast(value);
    }

    private static void requireValidRatio(double ratio, String fieldName) {
        if (!Double.isFinite(ratio)) {
            throw invalid("%s must be finite, got %s".formatted(fieldName, ratio));
        }

        if (ratio < 0.0 || ratio > 1.0) {
            throw invalid("%s must be within [0.0, 1.0], got %s".formatted(fieldName, ratio));
        }
    }

    private static Map<String, Double> decodeRouteRatios(TabularData tabular) {
        if (tabular.isEmpty()) {
            return Collections.emptyMap();
        }

        if (tabular.size() > JmxWireContract.MAX_ROUTE_ENTRIES) {
            throw invalid("%s has %s entries, exceeding the maximum of %s".formatted(
                    SAMPLING_ROUTE_RATIOS,
                    tabular.size(),
                    JmxWireContract.MAX_ROUTE_ENTRIES)
            );
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
        throw invalid("%s row has unexpected type %s".formatted(SAMPLING_ROUTE_RATIOS, type));
    }

    private static String requireRoutePrefix(CompositeData row) {
        String prefix = requireField(row, ROUTE_PREFIX, String.class);
        if (prefix.isBlank()) {
            throw invalid("route prefix must not be blank");
        }

        long utf8Length = JmxWireUtf8Meter.utf8Length(prefix);
        if (utf8Length > JmxWireContract.MAX_ROUTE_PREFIX_UTF8_BYTES) {
            throw invalid("route prefix UTF-8 length %s is invalid (must be 1..%s bytes)".formatted(
                    utf8Length,
                    JmxWireContract.MAX_ROUTE_PREFIX_UTF8_BYTES)
            );
        }

        if (prefix.chars().anyMatch(Character::isISOControl)) {
            throw invalid("route prefix must not contain ISO control characters");
        }

        return prefix;
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException(CONTRACT_POINT + ": " + reason);
    }
}
