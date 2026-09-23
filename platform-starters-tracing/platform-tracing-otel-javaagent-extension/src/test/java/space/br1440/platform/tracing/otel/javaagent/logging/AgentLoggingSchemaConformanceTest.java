package space.br1440.platform.tracing.otel.javaagent.logging;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLoggingSchemaConformanceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant TS = Instant.parse("2026-08-16T19:00:00Z");

    private static JsonSchema schema;
    private static PlatformLogEventEncoder encoder;

    @BeforeAll
    static void loadSchema() throws IOException {
        try (InputStream input = AgentLoggingSchemaConformanceTest.class
                .getResourceAsStream("/platform-log-event.schema.json")) {
            assertThat(input).as("owner schema must be on the test classpath").isNotNull();
            // Draft 2020-12 не проверяет format без явного включения assertions.
            SchemaValidatorsConfig config = SchemaValidatorsConfig.builder()
                    .formatAssertionsEnabled(true)
                    .build();
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(JSON.readTree(input), config);
        }
        encoder = new PlatformLogEventEncoder();
    }

    @Test
    void encode_fallback_matches_schema() {
        // given
        byte[] encoded = encoder.encodeFallback(
                TS,
                AgentLogLevel.ERROR,
                AgentEventCode.BOOT_INSTALLED,
                FallbackReason.BYTE_BUDGET);

        // when
        Set<ValidationMessage> errors = validate(encoded);

        // then
        assertThat(errors).isEmpty();
        assertThat(new String(encoded, StandardCharsets.UTF_8))
                .contains("\"original_event_code\":\"PTA-BOOT-001\"")
                .contains("\"fallback_reason\":\"byte_budget\"");
    }

    @Test
    void over_budget_stacktrace_keeps_the_event_without_the_digest() {
        // given
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) {
            fields.put("f" + i, "v".repeat(AgentLogEvent.MAX_ARGUMENT_CHARS));
        }
        AgentLogEvent event = new AgentLogEvent(
                TS,
                AgentLogLevel.ERROR,
                AgentEventCode.SCRUB_FAILURE,
                getClass().getName(),
                "m".repeat(AgentLogEvent.MAX_ARGUMENT_CHARS),
                fields,
                "d".repeat(4_000));

        // when
        String json = new String(encoder.encode(event), StandardCharsets.UTF_8);

        // then
        assertThat(validate(json.getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(json)
                .contains("\"event_code\":\"PTA-SCRUB-013\"")
                .contains("\"message\":\"" + "m".repeat(AgentLogEvent.MAX_ARGUMENT_CHARS) + "\"")
                .contains("\"f0\"")
                .doesNotContain("error_stacktrace")
                .doesNotContain("fallback_reason");
    }

    @Test
    void worst_case_fields_fall_back_with_the_original_event_code() {
        // given
        Map<String, String> fields = new LinkedHashMap<>();
        String payload = "Q".repeat(AgentLogEvent.MAX_ARGUMENT_CHARS);
        for (int i = 0; i < AgentLogFieldNamePolicy.MAX_FIELDS; i++) {
            fields.put("f" + i, payload);
        }
        AgentLogEvent event = new AgentLogEvent(
                TS,
                AgentLogLevel.WARN,
                AgentEventCode.EXPORT_DROP_OLDEST,
                getClass().getName(),
                "span dropped",
                fields,
                null);

        // when
        String json = new String(encoder.encode(event), StandardCharsets.UTF_8);

        // then
        assertThat(validate(json.getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(json)
                .contains("\"original_event_code\":\"PTA-EXPORT-002\"")
                .contains("\"fallback_reason\":\"byte_budget\"")
                .doesNotContain("invariant_violation")
                .doesNotContain(payload);
    }

    @Test
    void encoded_minimal_info_matches_schema() {
        // given
        AgentLogEvent event = event(AgentLogLevel.INFO, AgentEventCode.BOOT_INSTALLED, Map.of(), null);

        // when
        Set<ValidationMessage> errors = validate(encoder.encode(event));

        // then
        assertThat(errors).isEmpty();
    }

    @Test
    void encoded_warn_matches_schema() {
        // given
        AgentLogEvent event = event(
                AgentLogLevel.WARN,
                AgentEventCode.CTRL_UPDATE,
                Map.of("reason", "rejected"),
                null
        );

        // when
        Set<ValidationMessage> errors = validate(encoder.encode(event));

        // then
        assertThat(errors).isEmpty();
    }

    @Test
    void encoded_error_with_throwable_matches_schema() {
        // given
        AgentLogEvent event = event(
                AgentLogLevel.ERROR,
                AgentEventCode.SCRUB_FAILURE,
                Map.of("exceptionType", "IllegalStateException"),
                "java.lang.IllegalStateException\n\tat <platform-frame>"
        );

        // when
        Set<ValidationMessage> errors = validate(encoder.encode(event));

        // then
        assertThat(errors).isEmpty();
    }

    @Test
    void encoded_event_includes_message_and_optional_fields() {
        // given
        AgentLogEvent event = new AgentLogEvent(
                TS,
                AgentLogLevel.DEBUG,
                AgentEventCode.BOOT_RESOURCE_IDENTITY,
                getClass().getName(),
                "Resource identity resolved",
                Map.of("phase", "before-spring", "kind", "identity"),
                null
        );

        // when
        String json = new String(encoder.encode(event), StandardCharsets.UTF_8);

        // then
        assertThat(validate(json.getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(json)
                .contains("\"timestamp\":\"2026-08-16T19:00:00Z\"")
                .contains("\"message\":\"Resource identity resolved\"")
                .doesNotContain("error_stacktrace")
                .doesNotContain("app_name");
    }

    @Test
    void missing_required_field_is_rejected() throws IOException {
        // given
        JsonNode node = JSON.readTree("""
                {"level":"INFO","event_code":"PTA-BOOT-001","logger":"l"}
                """);

        // when
        Set<ValidationMessage> errors = schema.validate(node);

        // then
        assertThat(errors).isNotEmpty();
    }

    @Test
    void additional_property_is_allowed() throws IOException {
        // given
        JsonNode node = JSON.readTree("""
                {"timestamp":"2026-08-16T19:00:00Z","level":"INFO","logger":"l",\
                "message":"Platform agent logging installed","event_code":"PTA-BOOT-001",\
                "schema_version":"v2"}
                """);

        // when
        Set<ValidationMessage> errors = schema.validate(node);

        // then
        assertThat(errors).isEmpty();
    }

    @Test
    void invalid_level_timestamp_and_event_code_are_rejected() throws IOException {
        // given
        JsonNode off = JSON.readTree("""
                {"timestamp":"2026-08-16T19:00:00Z","level":"OFF","logger":"l",\
                "message":"Platform agent logging installed","event_code":"PTA-BOOT-001"}
                """);
        JsonNode ts = JSON.readTree("""
                {"timestamp":"not-a-timestamp","level":"INFO","logger":"l",\
                "message":"Platform agent logging installed","event_code":"PTA-BOOT-001"}
                """);
        JsonNode code = JSON.readTree("""
                {"timestamp":"2026-08-16T19:00:00Z","level":"INFO","logger":"l",\
                "message":"Platform agent logging installed","event_code":"SP-02"}
                """);

        // when
        // then
        assertThat(schema.validate(off)).isNotEmpty();
        assertThat(schema.validate(ts)).isNotEmpty();
        assertThat(schema.validate(code)).isNotEmpty();
    }

    @Test
    void reserved_field_names_cover_schema_root_properties() throws IOException {
        // given
        JsonNode schemaNode;
        try (InputStream input = AgentLoggingSchemaConformanceTest.class
                .getResourceAsStream("/platform-log-event.schema.json")) {
            schemaNode = JSON.readTree(input);
        }
        Set<String> schemaProperties = new LinkedHashSet<>();
        schemaNode.path("properties").fieldNames().forEachRemaining(schemaProperties::add);

        // when
        Set<String> reserved = AgentLogFieldNamePolicy.reservedFieldNames();

        // then
        assertThat(reserved).containsAll(schemaProperties);
        assertThat(reserved).isEqualTo(schemaProperties);
    }

    @Test
    void published_limits_match_emit_path_constants() throws IOException {
        // given
        JsonNode schemaNode;
        try (InputStream input = AgentLoggingSchemaConformanceTest.class
                .getResourceAsStream("/platform-log-event.schema.json")) {
            schemaNode = JSON.readTree(input);
        }

        // when
        // then
        assertThat(schemaNode.path("properties").path("logger").path("minLength").asInt()).isEqualTo(1);
        assertThat(schemaNode.path("properties").path("logger").has("maxLength")).isFalse();
        assertThat(schemaNode.path("properties").path("message").path("maxLength").asInt())
                .isEqualTo(AgentLogEvent.MAX_ARGUMENT_CHARS);
        assertThat(schemaNode.path("properties").path("fields").path("additionalProperties").path("maxLength").asInt())
                .isEqualTo(AgentLogEvent.MAX_ARGUMENT_CHARS);
        assertThat(schemaNode.path("properties").path("error_stacktrace").path("maxLength").asInt())
                .isEqualTo(BoundedThrowableRenderer.maxDigestChars());
    }

    private static AgentLogEvent event(
            AgentLogLevel severity,
            AgentEventCode eventCode,
            Map<String, String> fields,
            String throwable
    ) {
        return new AgentLogEvent(
                TS,
                severity,
                eventCode,
                AgentLoggingSchemaConformanceTest.class.getName(),
                "Schema conformance event",
                fields,
                throwable
        );
    }

    private static Set<ValidationMessage> validate(byte[] encoded) {
        try {
            String json = new String(encoded, StandardCharsets.UTF_8).trim();
            return schema.validate(JSON.readTree(json));
        } catch (IOException exception) {
            throw new IllegalStateException("Encoded event must be JSON", exception);
        }
    }
}
