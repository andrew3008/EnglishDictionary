package space.br1440.platform.tracing.otel.javaagent.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class AgentLoggingSchemaIdentityTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void reviewed_identity_matches_sha256_of_the_schema_file() throws IOException {
        // given
        Properties identity = loadProperties("/META-INF/platform-tracing/agent-logging-schema.properties");

        // when
        String schemaHash = PlatformLogSchemaHash.sha256OfNormalizedSchema(getClass());

        // then
        assertThat(identity.getProperty("schema.hashAlgorithm")).isEqualTo(PlatformLogSchemaHash.ALGORITHM_ID);
        assertThat(identity.getProperty("schema.name")).isEqualTo("platform-log-event-v1");
        assertThat(identity.getProperty("schema.version")).isEqualTo("1");
        assertThat(identity.getProperty("schema.sha256")).isEqualTo(schemaHash);
    }

    @Test
    void schema_name_matches_json_title_and_declared_version() throws IOException {
        // given
        Properties identity = loadProperties("/META-INF/platform-tracing/agent-logging-schema.properties");
        JsonNode schema = loadJson("/platform-log-event.schema.json");

        // when
        String schemaName = identity.getProperty("schema.name");
        String schemaVersion = identity.getProperty("schema.version");

        // then
        assertThat(schemaName)
                .isEqualTo(schema.required("title").textValue())
                .endsWith("-v" + schemaVersion);
    }

    @Test
    void normalized_fixture_matches_the_frozen_sha256() throws IOException {
        // given
        Properties expected = loadProperties("/platform-log-schema-known-answer/expected.properties");
        byte[] raw;
        try (InputStream input = AgentLoggingSchemaIdentityTest.class.getResourceAsStream(
                "/platform-log-schema-known-answer/a.json")) {
            assertThat(input).isNotNull();
            raw = input.readAllBytes();
        }

        // when
        String actual = PlatformLogSchemaHash.sha256Hex(PlatformLogSchemaHash.normalize(raw));

        // then
        assertThat(expected.getProperty("algorithmId")).isEqualTo(PlatformLogSchemaHash.ALGORITHM_ID);
        assertThat(actual).isEqualTo(expected.getProperty("expectedSha256"));
    }

    private static Properties loadProperties(String resource) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = AgentLoggingSchemaIdentityTest.class.getResourceAsStream(resource)) {
            assertThat(input).as(resource).isNotNull();
            properties.load(input);
        }
        return properties;
    }

    @SuppressWarnings("SameParameterValue")
    private static JsonNode loadJson(String resource) throws IOException {
        try (InputStream input = AgentLoggingSchemaIdentityTest.class.getResourceAsStream(resource)) {
            assertThat(input).as(resource).isNotNull();
            return JSON.readTree(input);
        }
    }
}
