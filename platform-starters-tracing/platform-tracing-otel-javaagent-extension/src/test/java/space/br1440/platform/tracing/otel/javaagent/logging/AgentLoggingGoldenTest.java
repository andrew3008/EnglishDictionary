package space.br1440.platform.tracing.otel.javaagent.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventType;
import space.br1440.platform.tracing.otel.javaagent.logging.event.BootstrapLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.event.LogThrottlingLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ResourceIdentityLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.event.SpanTimeoutLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ValidationLogEvent;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
class AgentLoggingGoldenTest {

    private static final Instant FIXED_TIMESTAMP = Instant.parse("2025-01-02T03:04:05.006Z");
    private static final String LOGGER = AgentLoggingGoldenTest.class.getName();
    private static final String FALLBACK_LOGGER = "space.br1440.platform.tracing.otel.javaagent.logging";

    @RegisterExtension
    final AgentLogCapture capture = new AgentLogCapture();

    private PlatformAgentLogger logger;

    @BeforeEach
    void createLogger() {
        logger = PlatformAgentLogger.get(AgentLoggingGoldenTest.class);
    }

    @Test
    void valid_missing_attributes_warn_and_error_match_golden_contract() {
        // given
        ObjectNode warnGolden = golden(
                "WARN",
                LOGGER,
                "Required span attributes are missing",
                "PTA-VALID-003",
                "REQUIRED_SPAN_ATTRIBUTES_MISSING_WARN",
                Map.of()
        );
        ObjectNode errorGolden = golden(
                "ERROR",
                LOGGER,
                "Required span attributes are missing",
                "PTA-VALID-003",
                "REQUIRED_SPAN_ATTRIBUTES_MISSING_ERROR",
                Map.of()
        );

        // when
        logger.log(ValidationLogEvent.REQUIRED_SPAN_ATTRIBUTES_MISSING_WARN);
        logger.log(ValidationLogEvent.REQUIRED_SPAN_ATTRIBUTES_MISSING_ERROR);

        // then
        List<JsonNode> events = capture.jsonEvents();
        assertThat(events).allSatisfy(event -> assertThat(event.has("error_stacktrace")).isFalse());
        assertThat(withoutTimestamps(events)).containsExactly(warnGolden, errorGolden);
    }

    @Test
    void boot_resource_identity_matches_golden_contract() {
        // given
        ObjectNode expected = golden(
                "INFO",
                LOGGER,
                "Resource identity resolved",
                "PTA-BOOT-005",
                "RESOURCE_IDENTITY_RESOLVED",
                Map.of(
                        "service", "orders",
                        "environment", "production",
                        "version", "2025.1"
                )
        );

        // when
        logger.log(ResourceIdentityLogEvent.RESOURCE_IDENTITY_RESOLVED, fields -> fields
                .putString(AgentLogField.VERSION, "2025.1")
                .putString(AgentLogField.SERVICE, "orders")
                .putString(AgentLogField.ENVIRONMENT, "production"));

        // then
        JsonNode event = singleEvent();
        assertThat(event.has("error_stacktrace")).isFalse();
        assertThat(withoutTimestamp(event)).isEqualTo(expected);
    }

    @Test
    void export_span_timeout_matches_golden_contract() {
        // given
        ObjectNode expected = golden(
                "WARN",
                LOGGER,
                "Span timeout rejected span tracking: per-trace tracked-span capacity reached",
                "PTA-EXPORT-008",
                "SPAN_TIMEOUT_PER_TRACE_CAPACITY_REACHED",
                Map.of("rejected", "7", "limit", "128")
        );

        // when
        logger.log(SpanTimeoutLogEvent.SPAN_TIMEOUT_PER_TRACE_CAPACITY_REACHED, fields -> fields
                .putLong(AgentLogField.LIMIT, 128L)
                .putLong(AgentLogField.REJECTED, 7L));

        // then
        JsonNode event = singleEvent();
        assertThat(event.has("error_stacktrace")).isFalse();
        assertThat(withoutTimestamp(event)).isEqualTo(expected);
    }

    @Test
    void rate_markers_match_warn_and_error_golden_contracts() {
        // given
        ObjectNode warnGolden = golden(
                "WARN",
                LOGGER,
                "Agent logging is throttled",
                "PTA-RATE-001",
                "RATE_WARNINGS_THROTTLED",
                Map.of("kind", "warnings")
        );
        ObjectNode errorGolden = golden(
                "ERROR",
                LOGGER,
                "Agent logging is throttled",
                "PTA-RATE-001",
                "RATE_ERRORS_THROTTLED",
                Map.of("kind", "errors")
        );

        // when
        logger.log(LogThrottlingLogEvent.RATE_WARNINGS_THROTTLED, fields -> fields
                .putString(AgentLogField.KIND, "warnings"));
        logger.log(LogThrottlingLogEvent.RATE_ERRORS_THROTTLED, fields -> fields
                .putString(AgentLogField.KIND, "errors"));

        // then
        List<JsonNode> events = capture.jsonEvents();
        assertThat(events).allSatisfy(event -> assertThat(event.has("error_stacktrace")).isFalse());
        assertThat(withoutTimestamps(events)).containsExactly(warnGolden, errorGolden);
    }

    @Test
    void encode_fallback_matches_golden_contract() {
        // given
        ObjectNode expected = golden(
                "INFO",
                FALLBACK_LOGGER,
                "Platform Agent log event could not be encoded safely",
                "PTA-ENCODE-001",
                "ENCODE_FALLBACK",
                Map.of(
                        "original_event_code", "PTA-BOOT-001",
                        "fallback_reason", "invariant_violation"
                )
        );
        expected.put("original_event_name", "EXTENSION_INSTALLED");

        // when
        logger.log(BootstrapLogEvent.EXTENSION_INSTALLED, fields -> fields
                .putString(AgentLogField.NAME, "must-not-be-rendered"));

        // then
        JsonNode event = singleEvent();
        assertThat(event.has("error_stacktrace")).isFalse();
        assertThat(withoutTimestamp(event)).isEqualTo(expected);
    }

    @Test
    void encoded_payload_is_independent_of_typed_field_insertion_order() {
        // given
        AgentLogFieldsWriter forward = fields -> fields
                .putString(AgentLogField.SERVICE, "orders")
                .putString(AgentLogField.ENVIRONMENT, "production")
                .putString(AgentLogField.VERSION, "2025.1");
        AgentLogFieldsWriter reverse = fields -> fields
                .putString(AgentLogField.VERSION, "2025.1")
                .putString(AgentLogField.ENVIRONMENT, "production")
                .putString(AgentLogField.SERVICE, "orders");

        // when
        byte[] forwardPayload = encode(forward);
        byte[] reversePayload = encode(reverse);

        // then
        assertThat(forwardPayload).isEqualTo(reversePayload);
    }

    private JsonNode singleEvent() {
        List<JsonNode> events = capture.jsonEvents();
        assertThat(events).hasSize(1);
        return events.getFirst();
    }

    private static List<JsonNode> withoutTimestamps(List<JsonNode> events) {
        return events.stream().map(AgentLoggingGoldenTest::withoutTimestamp).toList();
    }

    private static JsonNode withoutTimestamp(JsonNode event) {
        ObjectNode normalized = event.deepCopy();
        JsonNode timestamp = normalized.remove("timestamp");
        assertThat(timestamp).isNotNull();
        assertThat(Instant.parse(timestamp.textValue())).isNotNull();
        return normalized;
    }

    private static ObjectNode golden(String level,
                                     String loggerName,
                                     String message,
                                     String eventCode,
                                     String eventName,
                                     Map<String, String> fields) {
        ObjectNode event = JsonNodeFactory.instance.objectNode();
        event.put("level", level);
        event.put("logger", loggerName);
        event.put("message", message);
        event.put("event_code", eventCode);
        event.put("event_name", eventName);
        ObjectNode fieldNode = event.putObject("fields");
        fields.forEach(fieldNode::put);
        return event;
    }

    private static byte[] encode(AgentLogFieldsWriter fieldsWriter) {
        AgentLogEventType eventType = ResourceIdentityLogEvent.RESOURCE_IDENTITY_RESOLVED;
        AgentLogFieldsBuilder fields = new AgentLogFieldsBuilder(eventType);
        fieldsWriter.write(fields);
        AgentLogFieldsBuilder.Snapshot snapshot = fields.snapshot();
        AgentLogRecord event = new AgentLogRecord(
                FIXED_TIMESTAMP,
                eventType.level(),
                eventType.eventCode(),
                eventType.name(),
                LOGGER,
                eventType.message(),
                snapshot.fields(),
                snapshot.errorType(),
                snapshot.throwableDigest()
        );
        return new PlatformLogEventEncoder().encode(event);
    }
}
