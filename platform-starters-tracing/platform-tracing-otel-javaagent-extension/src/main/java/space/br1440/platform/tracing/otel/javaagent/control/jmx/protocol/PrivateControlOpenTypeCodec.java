package space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol.PrivateControlWireTypes.WireField;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsDigest;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.ExpectedSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsSourceAuthority;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsUpdateTrigger;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingLogLevel;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsRejectionReason;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateAttempt;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateResult;

final class PrivateControlOpenTypeCodec {

    private static final int MAX_LOGICAL_REQUEST_BYTES = 32_768;
    private static final int MAX_LOGICAL_ACCEPTED_COMPLETE_BYTES = 32_768;

    DecodedSettingsUpdate decode(CompositeData request) {
        requireValidRequestShape(request);
        verifyProtocolVersion(request);

        String runtimeId = value(request, WireField.EXPECTED_RUNTIME_INSTANCE_ID, String.class);
        long sequence = value(request, WireField.EXPECTED_ACCEPTED_SEQUENCE, Long.class);
        String attemptId = value(request, WireField.ATTEMPT_ID, String.class);
        String authority = token(request, WireField.SOURCE_AUTHORITY);
        String trigger = token(request, WireField.APPLY_TRIGGER);
        String sourceRevision = value(request, WireField.SOURCE_REVISION, String.class);
        String candidateDigest = value(request, WireField.CANDIDATE_DIGEST, String.class);
        CompositeData policyData = value(request, WireField.RUNTIME_POLICY, CompositeData.class);

        requireNonNegativeSequence(sequence);
        requireValidDigestFormat(candidateDigest);
        requirePolicyType(policyData);

        TracingSettings settings = decodePolicySafely(policyData);
        requireWithinLogicalRequestBudget(request, settings);
        requireDigestMatches(candidateDigest, settings);

        return buildDecodedRequest(
                runtimeId, sequence, attemptId, authority, trigger, sourceRevision, settings
        );
    }

    private static void requireValidRequestShape(CompositeData request) {
        if (request == null) {
            throw rejected(SettingsRejectionReason.MISSING_REQUIRED_FIELD, "request is required");
        }

        if (!PrivateControlWireTypes.REQUEST.equals(request.getCompositeType())) {
            throw rejected(SettingsRejectionReason.TYPE_MISMATCH, "unexpected request CompositeType");
        }
    }

    private static void verifyProtocolVersion(CompositeData request) {
        int version = value(request, WireField.PROTOCOL_VERSION, Integer.class);
        if (version != PrivateControlObjectNames.PROTOCOL_VERSION) {
            throw rejected(SettingsRejectionReason.PROTOCOL_VERSION_MISMATCH, "unsupported protocol version");
        }
    }

    private static void requireNonNegativeSequence(long sequence) {
        if (sequence < 0L) {
            throw rejected(SettingsRejectionReason.INVALID_VALUE, "expectedAcceptedSequence is negative");
        }
    }

    private static void requireValidDigestFormat(String candidateDigest) {
        if (!candidateDigest.matches("[0-9a-f]{64}")) {
            throw rejected(SettingsRejectionReason.INVALID_VALUE, "candidateDigest is invalid");
        }
    }

    private static void requirePolicyType(CompositeData policyData) {
        if (!PrivateControlWireTypes.POLICY.equals(policyData.getCompositeType())) {
            throw rejected(SettingsRejectionReason.TYPE_MISMATCH, "unexpected runtimePolicy CompositeType");
        }
    }

    private static TracingSettings decodePolicySafely(CompositeData policyData) {
        try {
            return decodePolicy(policyData);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw rejected(SettingsRejectionReason.INVALID_VALUE, exception.getMessage());
        }
    }

    private static void requireWithinLogicalRequestBudget(CompositeData request, TracingSettings settings) {
        if (logicalSize(request, settings) > MAX_LOGICAL_REQUEST_BYTES) {
            throw rejected(
                    SettingsRejectionReason.LIMIT_EXCEEDED,
                    "Logical request exceeds %d bytes".formatted(MAX_LOGICAL_REQUEST_BYTES)
            );
        }
    }

    private static void requireDigestMatches(String candidateDigest, TracingSettings settings) {
        if (!candidateDigest.equals(TracingSettingsDigest.sha256(settings))) {
            throw rejected(SettingsRejectionReason.INVALID_VALUE, "candidateDigest does not match policy");
        }
    }

    private static DecodedSettingsUpdate buildDecodedRequest(String runtimeId,
                                                               long sequence,
                                                               String attemptId,
                                                               String authority,
                                                               String trigger,
                                                               String sourceRevision,
                                                               TracingSettings settings) {
        try {
            return new DecodedSettingsUpdate(
                    new SettingsUpdateAttempt(
                            attemptId,
                            Instant.now(),
                            SettingsSourceAuthority.valueOf(authority),
                            SettingsUpdateTrigger.valueOf(trigger),
                            sourceRevision),
                    new ExpectedSettingsRevision(runtimeId, sequence),
                    settings
            );
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw rejected(SettingsRejectionReason.INVALID_VALUE, exception.getMessage());
        }
    }

    CompositeData encode(SettingsUpdateResult result, String attemptId) {
        try {
            String[] violations = (result.messageCode() == SettingsRejectionReason.NONE)
                    ? new String[0]
                    : new String[]{result.messageCode().name()};

            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put(WireField.ACCEPTED_SEQUENCE.wireName(), result.accepted().acceptedSequence());
            fields.put(WireField.ATTEMPT_ID.wireName(), attemptId);
            fields.put(WireField.MESSAGE_CODE.wireName(), result.messageCode().name());
            fields.put(WireField.OUTCOME.wireName(), result.outcome().name());
            fields.put(WireField.PAYLOAD_DIGEST.wireName(), result.payloadDigest());
            fields.put(WireField.PROTOCOL_VERSION.wireName(), PrivateControlObjectNames.PROTOCOL_VERSION);
            fields.put(WireField.RUNTIME_INSTANCE_ID.wireName(), result.accepted().runtimeInstanceId());
            fields.put(WireField.VIOLATION_CODES.wireName(), violations);

            return new CompositeDataSupport(PrivateControlWireTypes.RESPONSE, fields);
        } catch (OpenDataException exception) {
            throw new IllegalStateException("Cannot encode private control response", exception);
        }
    }

    CompositeData encodeAcceptedComplete(TracingSettingsRevision revision) {
        Objects.requireNonNull(revision, "revision");

        if (logicalSizeAcceptedComplete(revision) > MAX_LOGICAL_ACCEPTED_COMPLETE_BYTES) {
            throw new IllegalStateException(
                    "AcceptedCompleteSnapshot logical size exceeds %d bytes"
                            .formatted(MAX_LOGICAL_ACCEPTED_COMPLETE_BYTES)
            );
        }

        try {
            TabularData routes = encodeRouteRatios(revision.samplingRouteRatios());

            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put(WireField.ACCEPTED_SEQUENCE.wireName(), revision.acceptedSequence());
            fields.put(WireField.RUNTIME_INSTANCE_ID.wireName(), revision.runtimeInstanceId());
            fields.put(WireField.SAMPLING_ENABLED.wireName(), revision.samplingEnabled());
            fields.put(WireField.SAMPLING_RATIO.wireName(), revision.samplingRatio());
            fields.put(WireField.SAMPLING_ROUTE_RATIOS.wireName(), routes);
            fields.put(WireField.VALIDATION_ENABLED.wireName(), revision.validationEnabled());
            fields.put(WireField.VALIDATION_STRICT.wireName(), revision.validationStrict());
            fields.put(WireField.EXPORTER_ENABLED.wireName(), revision.exporterEnabled());
            fields.put(WireField.TRACE_CONTROL_HEADERS_ENABLED.wireName(), revision.traceControlHeadersEnabled());
            fields.put(WireField.DIAGNOSTICS_LOG_LEVEL.wireName(), revision.diagnosticsLogLevel());

            return new CompositeDataSupport(PrivateControlWireTypes.ACCEPTED_COMPLETE, fields);
        } catch (OpenDataException exception) {
            throw new IllegalStateException("Cannot encode AcceptedCompleteSnapshot", exception);
        }
    }

    private static TabularData encodeRouteRatios(NavigableMap<String, Double> ratios) throws OpenDataException {
        TabularDataSupport table = new TabularDataSupport(PrivateControlWireTypes.ROUTES);
        for (Map.Entry<String, Double> entry : ratios.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(WireField.ROUTE_PREFIX.wireName(), entry.getKey());
            row.put(WireField.ROUTE_RATIO.wireName(), entry.getValue());
            table.put(new CompositeDataSupport(PrivateControlWireTypes.ROUTE_ROW, row));
        }
        return table;
    }

    private static TracingSettings decodePolicy(CompositeData data) {
        TabularData routes = value(data, WireField.SAMPLING_ROUTE_RATIOS, TabularData.class);
        if (!PrivateControlWireTypes.ROUTES.equals(routes.getTabularType())) {
            throw rejected(SettingsRejectionReason.TYPE_MISMATCH, "unexpected route-ratios TabularType");
        }

        if (routes.size() > TracingSettings.MAX_ROUTE_ENTRIES) {
            throw rejected(SettingsRejectionReason.LIMIT_EXCEEDED, "too many route ratios");
        }

        Map<String, Double> decodedRoutes = new TreeMap<>();
        for (Object rawRow : routes.values()) {
            CompositeData row = (CompositeData) rawRow;
            String prefix = value(row, WireField.ROUTE_PREFIX, String.class);
            Double ratio = value(row, WireField.ROUTE_RATIO, Double.class);
            requireValidRoutePrefix(prefix);
            if (decodedRoutes.put(prefix, ratio) != null) {
                throw rejected(SettingsRejectionReason.INVALID_VALUE, "duplicate route prefix");
            }
        }

        return TracingSettings.of(
                value(data, WireField.SAMPLING_ENABLED, Boolean.class),
                value(data, WireField.SAMPLING_RATIO, Double.class),
                decodedRoutes,
                value(data, WireField.VALIDATION_ENABLED, Boolean.class),
                value(data, WireField.VALIDATION_STRICT, Boolean.class),
                value(data, WireField.EXPORTER_ENABLED, Boolean.class),
                value(data, WireField.TRACE_CONTROL_HEADERS_ENABLED, Boolean.class),
                TracingLogLevel.valueOf(token(data, WireField.DIAGNOSTICS_LOG_LEVEL))
        );
    }

    private static final int MAX_ROUTE_PREFIX_LENGTH = 255;

    private static void requireValidRoutePrefix(String prefix) {
        if (prefix.isEmpty() || prefix.length() > MAX_ROUTE_PREFIX_LENGTH || containsControl(prefix)) {
            throw rejected(SettingsRejectionReason.INVALID_VALUE, "invalid route prefix");
        }
    }

    private static int logicalSize(CompositeData request, TracingSettings settings) {
        int size = 64;
        for (String key : request.getCompositeType().keySet()) {
            Object value = request.get(key);
            if (value instanceof String text) {
                size += text.getBytes(StandardCharsets.UTF_8).length;
            }
        }

        for (String routePrefix : settings.samplingRouteRatios().keySet()) {
            size += routePrefix.getBytes(StandardCharsets.UTF_8).length + 16;
        }

        return size;
    }

    private static int logicalSizeAcceptedComplete(TracingSettingsRevision revision) {
        int size = 64;
        size += revision.runtimeInstanceId().getBytes(StandardCharsets.UTF_8).length;
        size += revision.diagnosticsLogLevel().getBytes(StandardCharsets.UTF_8).length;
        for (String routePrefix : revision.samplingRouteRatios().keySet()) {
            size += routePrefix.getBytes(StandardCharsets.UTF_8).length + 16;
        }

        return size;
    }

    private static String token(CompositeData data, WireField field) {
        String value = value(data, field, String.class);
        if (!value.equals(value.trim())) {
            throw rejected(SettingsRejectionReason.INVALID_VALUE, field.wireName() + " must be trimmed");
        }

        return value;
    }

    private static <T> T value(CompositeData data, WireField field, Class<T> type) {
        Object value = data.get(field.wireName());
        if (value == null) {
            throw rejected(SettingsRejectionReason.MISSING_REQUIRED_FIELD, field.wireName() + " is required");
        }

        if (!type.isInstance(value)) {
            throw rejected(SettingsRejectionReason.TYPE_MISMATCH, field.wireName() + " has wrong type");
        }

        return type.cast(value);
    }

    private static boolean containsControl(String value) {
        return value.chars().anyMatch(character -> (character == 0) || Character.isISOControl(character));
    }

    private static PrivateControlDecodeException rejected(SettingsRejectionReason code, String message) {
        return new PrivateControlDecodeException(code, message);
    }
}
