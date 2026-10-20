package space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.TabularData;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsWriter;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsRejectionReason;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateAttempt;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateResult;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireGraphMeter;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireUtf8Meter;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

final class JmxControlOpenTypeCodec {

    DecodedSettingsUpdate decode(CompositeData request) {
        requireValidRequestShape(request);

        String agentInstanceId = value(
                request,
                JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_EXPECTED_AGENT_INSTANCE_ID,
                String.class
        );

        long sequence = value(
                request,
                JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_EXPECTED_ACCEPTED_SEQUENCE,
                Long.class
        );

        String attemptId = value(
                request,
                JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_ATTEMPT_ID,
                String.class
        );

        String writer = token(
                request,
                JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_WRITER
        );

        CompositeData policyData = value(
                request,
                JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_RUNTIME_POLICY,
                CompositeData.class
        );

        requireNonNegativeSequence(sequence);
        requirePolicyType(policyData);

        TracingSettings settings = decodePolicy(policyData);
        requireWithinLogicalRequestBudget(request);

        return buildDecodedRequest(
                agentInstanceId,
                sequence,
                attemptId,
                writer,
                settings);
    }

    CompositeData encode(SettingsUpdateResult result, String attemptId) {
        try {
            String[] violations = result.messageCode() == SettingsRejectionReason.NONE
                    ? new String[0]
                    : new String[]{result.messageCode().name()};

            Map<String, Object> fields = new LinkedHashMap<>();

            fields.put(
                    JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_ACCEPTED_SEQUENCE,
                    result.accepted().acceptedSequence()
            );

            fields.put(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_ATTEMPT_ID, attemptId);

            fields.put(
                    JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_MESSAGE_CODE,
                    result.messageCode().name()
            );

            fields.put(
                    JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_OUTCOME,
                    result.outcome().name()
            );

            fields.put(
                    JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_AGENT_INSTANCE_ID,
                    result.accepted().agentInstanceId().toString()
            );

            fields.put(
                    JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_VIOLATION_CODES,
                    violations
            );

            return new CompositeDataSupport(JmxOpenTypes.APPLY_SNAPSHOT_RESPONSE, fields);
        } catch (OpenDataException exception) {
            throw new IllegalStateException("Cannot encode JMX settings mutation response", exception);
        }
    }

    private static void requireValidRequestShape(CompositeData request) {
        if (request == null) {
            throw rejected(SettingsRejectionReason.MISSING_REQUIRED_FIELD, "Request is required");
        }

        if (request.getClass().getClassLoader() != null) {
            throw rejected(
                    SettingsRejectionReason.TYPE_MISMATCH,
                    "Request must use a bootstrap-defined CompositeData implementation");
        }

        if (!JmxOpenTypes.APPLY_SNAPSHOT_REQUEST.equals(request.getCompositeType())) {
            throw rejected(SettingsRejectionReason.TYPE_MISMATCH, "Unexpected request CompositeType");
        }
    }

    private static void requireNonNegativeSequence(long sequence) {
        if (sequence < 0L) {
            throw rejected(
                    SettingsRejectionReason.INVALID_VALUE,
                    "expectedAcceptedSequence is negative");
        }
    }

    private static void requirePolicyType(CompositeData policyData) {
        if (policyData.getClass().getClassLoader() != null) {
            throw rejected(
                    SettingsRejectionReason.TYPE_MISMATCH,
                    "runtimePolicy must use a bootstrap-defined CompositeData implementation");
        }

        if (!JmxOpenTypes.RUNTIME_POLICY.equals(policyData.getCompositeType())) {
            throw rejected(SettingsRejectionReason.TYPE_MISMATCH, "Unexpected runtimePolicy CompositeType");
        }
    }

    private static TracingSettings decodePolicy(CompositeData data) {
        double samplingRatio = value(
                data,
                JmxWireContract.FIELD_RUNTIME_POLICY_SAMPLING_RATIO,
                Double.class
        );

        if (isInvalidRatio(samplingRatio)) {
            throw rejected(
                    SettingsRejectionReason.INVALID_SAMPLING_RATIO,
                    JmxWireContract.FIELD_RUNTIME_POLICY_SAMPLING_RATIO,
                    "Sampling ratio is invalid"
            );
        }

        TabularData routes = value(
                data,
                JmxWireContract.FIELD_RUNTIME_POLICY_SAMPLING_ROUTE_RATIOS,
                TabularData.class
        );

        if (routes.getClass().getClassLoader() != null) {
            throw rejected(
                    SettingsRejectionReason.TYPE_MISMATCH,
                    "samplingRouteRatios must use a bootstrap-defined TabularData implementation");
        }

        if (!JmxOpenTypes.ROUTE_RATIOS.equals(routes.getTabularType())) {
            throw rejected(
                    SettingsRejectionReason.TYPE_MISMATCH,
                    "Unexpected samplingRouteRatios TabularType");
        }

        if (routes.size() > JmxWireContract.MAX_ROUTE_ENTRIES) {
            throw rejected(SettingsRejectionReason.LIMIT_EXCEEDED, "Too many route ratios");
        }

        Map<String, Double> decodedRoutes = new TreeMap<>();
        for (Object rawRow : routes.values()) {
            if (!(rawRow instanceof CompositeData row)
                    || row.getClass().getClassLoader() != null
                    || !JmxOpenTypes.ROUTE_RATIO.equals(row.getCompositeType())) {
                throw rejected(SettingsRejectionReason.TYPE_MISMATCH, "Unexpected route-ratio row type");
            }

            String prefix = value(
                    row,
                    JmxWireContract.FIELD_ROUTE_RATIO_ROUTE_PREFIX,
                    String.class
            );

            Double ratio = value(
                    row,
                    JmxWireContract.FIELD_ROUTE_RATIO_RATIO,
                    Double.class
            );

            requireValidRoutePrefix(prefix);
            if (isInvalidRatio(ratio)) {
                throw rejected(
                        SettingsRejectionReason.INVALID_ROUTE_RATIO,
                        JmxWireContract.FIELD_ROUTE_RATIO_RATIO,
                        "Route ratio is invalid"
                );
            }

            if (decodedRoutes.put(prefix, ratio) != null) {
                throw rejected(SettingsRejectionReason.INVALID_VALUE, "Duplicate route prefix");
            }
        }

        String rawValidationLevel = token(
                data,
                JmxWireContract.FIELD_RUNTIME_POLICY_VALIDATION_LEVEL
        );
        SpanAttributeValidationLevel validationLevel;
        try {
            validationLevel = SpanAttributeValidationLevel.require(rawValidationLevel);
        } catch (IllegalArgumentException exception) {
            throw rejected(
                    SettingsRejectionReason.INVALID_VALIDATION_LEVEL,
                    JmxWireContract.FIELD_RUNTIME_POLICY_VALIDATION_LEVEL,
                    "Validation level is invalid"
            );
        }

        String rawDiagnosticsLogLevel = token(
                data,
                JmxWireContract.FIELD_RUNTIME_POLICY_DIAGNOSTICS_LOG_LEVEL
        );
        AgentLogLevel diagnosticsLogLevel;
        try {
            diagnosticsLogLevel = AgentLogLevel.valueOf(rawDiagnosticsLogLevel);
        } catch (IllegalArgumentException exception) {
            throw rejected(
                    SettingsRejectionReason.INVALID_DIAGNOSTICS_LOG_LEVEL,
                    JmxWireContract.FIELD_RUNTIME_POLICY_DIAGNOSTICS_LOG_LEVEL,
                    "Diagnostics log level is invalid"
            );
        }

        return TracingSettings.of(
                value(
                        data,
                        JmxWireContract.FIELD_RUNTIME_POLICY_SAMPLING_ENABLED,
                        Boolean.class
                ),

                samplingRatio,

                decodedRoutes,

                validationLevel,

                value(
                        data,
                        JmxWireContract.FIELD_RUNTIME_POLICY_EXPORTER_ENABLED,
                        Boolean.class
                ),

                value(
                        data,
                        JmxWireContract.FIELD_RUNTIME_POLICY_TRACE_CONTROL_HEADERS_ENABLED,
                        Boolean.class
                ),

                diagnosticsLogLevel
        );
    }

    private static void requireWithinLogicalRequestBudget(CompositeData request) {
        try {
            JmxWireGraphMeter.measure(request);
        } catch (IllegalArgumentException violation) {
            throw rejected(
                    SettingsRejectionReason.LIMIT_EXCEEDED,
                    "Logical request exceeds %d bytes".formatted(JmxWireContract.MAX_LOGICAL_BYTES)
            );
        }
    }

    private static DecodedSettingsUpdate buildDecodedRequest(String agentInstanceId,
                                                             long sequence,
                                                             String attemptId,
                                                             String writer,
                                                             TracingSettings settings) {
        try {
            return new DecodedSettingsUpdate(
                    new SettingsUpdateAttempt(
                            attemptId,
                            SettingsWriter.valueOf(writer)
                    ),
                    new SettingsRevision(AgentInstanceId.parse(agentInstanceId), sequence),
                    settings);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw rejected(SettingsRejectionReason.INVALID_VALUE, "Settings update request is invalid");
        }
    }

    private static void requireValidRoutePrefix(String prefix) {
        long utf8Bytes = prefix.length() > JmxWireContract.MAX_ROUTE_PREFIX_UTF8_BYTES
                ? JmxWireContract.MAX_ROUTE_PREFIX_UTF8_BYTES + 1L
                : JmxWireUtf8Meter.utf8Length(prefix);

        if (StringUtils.isNullOrBlank(prefix)
                || utf8Bytes > JmxWireContract.MAX_ROUTE_PREFIX_UTF8_BYTES
                || containsControl(prefix)) {
            throw rejected(SettingsRejectionReason.INVALID_VALUE, "Invalid route prefix");
        }
    }

    private static String token(CompositeData data, String field) {
        String fieldValue = value(data, field, String.class);
        if (!fieldValue.equals(fieldValue.trim())) {
            throw rejected(SettingsRejectionReason.INVALID_VALUE, field + " must be trimmed");
        }
        return fieldValue;
    }

    private static <T> T value(CompositeData data, String field, Class<T> type) {
        Object fieldValue = data.get(field);
        if (fieldValue == null) {
            throw rejected(SettingsRejectionReason.MISSING_REQUIRED_FIELD, field + " is required");
        }

        if (!type.isInstance(fieldValue)) {
            throw rejected(SettingsRejectionReason.TYPE_MISMATCH, field + " has wrong type");
        }

        if (fieldValue instanceof String text
                && boundedUtf8Length(text) > JmxWireContract.MAX_STRING_UTF8_BYTES) {
            throw rejected(SettingsRejectionReason.LIMIT_EXCEEDED, field + " exceeds the UTF-8 size limit");
        }

        return type.cast(fieldValue);
    }

    private static long boundedUtf8Length(String value) {
        if (value.length() > JmxWireContract.MAX_STRING_UTF8_BYTES) {
            return JmxWireContract.MAX_STRING_UTF8_BYTES + 1L;
        }

        return JmxWireUtf8Meter.utf8Length(value);
    }

    private static boolean containsControl(String value) {
        return value.chars().anyMatch(character -> character == 0 || Character.isISOControl(character));
    }

    private static boolean isInvalidRatio(double ratio) {
        return !Double.isFinite(ratio) || ratio < 0.0d || ratio > 1.0d;
    }

    private static JmxControlDecodeException rejected(SettingsRejectionReason code,
                                                      String message) {
        return new JmxControlDecodeException(code, message);
    }

    private static JmxControlDecodeException rejected(SettingsRejectionReason code,
                                                      String propertyName,
                                                      String message) {
        return new JmxControlDecodeException(code, propertyName, message);
    }
}
