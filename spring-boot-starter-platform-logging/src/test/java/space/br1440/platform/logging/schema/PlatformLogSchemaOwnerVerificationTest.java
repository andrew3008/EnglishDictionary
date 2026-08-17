package space.br1440.platform.logging.schema;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
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

class PlatformLogSchemaOwnerVerificationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EVENT_CODE_PATTERN = "^[A-Z]{2,6}-[A-Z]+-[0-9]{3}$";
    private static final List<String> SCHEMA_FILE_NAMES = List.of(
            "platform-log-event-v1.schema.json"
    );

    private static Path schemaDir;
    private static JsonNode schemaNode;
    private static JsonSchema jsonSchema;

    @BeforeAll
    static void loadPreparedSchema() throws IOException {
        String configured = System.getProperty("platformLog.schemaDir");
        assertThat(configured)
                .as("platformLog.schemaDir must point at preparePlatformLogSchema output")
                .isNotBlank();
        schemaDir = Path.of(configured);
        schemaNode = JSON.readTree(schemaDir.resolve("platform-log-event-v1.schema.json").toFile());
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder()
                .formatAssertionsEnabled(true)
                .build();
        jsonSchema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaNode, config);
    }

    @Test
    void prepared_directory_contains_exact_allowlist() throws IOException {
        // given
        Set<String> names = new LinkedHashSet<>();

        // when
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(schemaDir)) {
            for (Path path : stream) {
                names.add(path.getFileName().toString());
            }
        }

        // then
        assertThat(names).containsExactlyInAnyOrderElementsOf(SCHEMA_FILE_NAMES);
    }

    @Test
    void prepared_files_are_utf8_without_bom_and_use_lf() throws IOException {
        // given
        // when
        // then
        for (String name : SCHEMA_FILE_NAMES) {
            byte[] raw = Files.readAllBytes(schemaDir.resolve(name));
            assertThat(raw.length).isGreaterThanOrEqualTo(3);
            assertThat(raw[0] & 0xFF).isNotEqualTo(0xEF);
            String text = new String(raw, StandardCharsets.UTF_8);
            assertThat(text).doesNotContain("\r");
        }
    }

    @Test
    void schema_is_open_root_v1_with_required_message() {
        // given
        // when
        // then
        assertThat(schemaNode.path("title").asText()).isEqualTo("platform-log-event-v1");
        assertThat(schemaNode.path("additionalProperties").asBoolean()).isTrue();
        assertThat(schemaNode.path("properties").path("event_code").path("pattern").asText())
                .isEqualTo(EVENT_CODE_PATTERN);
        assertThat(schemaNode.path("required")).extracting(JsonNode::asText)
                .containsExactly("timestamp", "level", "logger", "message", "event_code");
        assertThat(schemaNode.path("properties").path("logger").path("minLength").asInt()).isEqualTo(1);
        assertThat(schemaNode.path("properties").path("message").path("minLength").asInt()).isEqualTo(1);
        assertThat(schemaNode.path("properties").path("message").path("maxLength").asInt()).isEqualTo(512);
        assertThat(schemaNode.path("properties").path("fields").path("additionalProperties").path("type").asText())
                .isEqualTo("string");
        assertThat(schemaNode.path("properties").path("fields").path("additionalProperties").path("maxLength").asInt())
                .isEqualTo(512);
        assertThat(schemaNode.path("properties").path("error_stacktrace").path("maxLength").asInt())
                .isEqualTo(4119);
    }

    @Test
    void generic_positive_corpus_passes() throws IOException {
        // given
        List<String> positives = List.of(
                "minimal-info.json",
                "warn.json",
                "error-stacktrace.json",
                "optional-fields.json",
                "before-spring-no-app-name.json"
        );

        // when
        // then
        for (String name : positives) {
            Set<ValidationMessage> errors = jsonSchema.validate(readCorpus("positive", name));
            assertThat(errors).as(name).isEmpty();
        }
    }

    @Test
    void generic_negative_corpus_fails() throws IOException {
        // given
        List<String> negatives = List.of(
                "missing-timestamp.json",
                "missing-message.json",
                "invalid-level.json",
                "invalid-timestamp.json",
                "invalid-event-code.json",
                "empty-logger.json"
        );

        // when
        // then
        for (String name : negatives) {
            Set<ValidationMessage> errors = jsonSchema.validate(readCorpus("negative", name));
            assertThat(errors).as(name).isNotEmpty();
        }
    }

    @Test
    void published_length_limits_reject_oversized_wire_values() throws IOException {
        // given
        JsonNode longMessage = JSON.readTree("""
                {"timestamp":"2026-08-16T19:00:00Z","level":"INFO","logger":"l",\
                "message":"%s","event_code":"APP-BOOT-001"}
                """.formatted("x".repeat(513)));
        JsonNode longField = JSON.readTree("""
                {"timestamp":"2026-08-16T19:00:00Z","level":"INFO","logger":"l",\
                "message":"Platform logging started","event_code":"APP-BOOT-001",\
                "fields":{"kind":"%s"}}
                """.formatted("x".repeat(513)));
        JsonNode longDigest = JSON.readTree("""
                {"timestamp":"2026-08-16T19:00:00Z","level":"ERROR","logger":"l",\
                "message":"Platform logging started","event_code":"APP-BOOT-001",\
                "error_stacktrace":"%s"}
                """.formatted("x".repeat(4120)));

        // when
        // then
        assertThat(jsonSchema.validate(longMessage)).isNotEmpty();
        assertThat(jsonSchema.validate(longField)).isNotEmpty();
        assertThat(jsonSchema.validate(longDigest)).isNotEmpty();
    }

    private static JsonNode readCorpus(String kind, String name) throws IOException {
        String resource = "/platform-log-schema-corpus/" + kind + "/" + name;
        try (InputStream input = PlatformLogSchemaOwnerVerificationTest.class.getResourceAsStream(resource)) {
            assertThat(input).as(resource).isNotNull();
            return JSON.readTree(input);
        }
    }
}
