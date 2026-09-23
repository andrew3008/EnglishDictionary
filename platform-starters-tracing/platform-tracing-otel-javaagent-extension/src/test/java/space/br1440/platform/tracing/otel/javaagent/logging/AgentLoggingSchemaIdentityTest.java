package space.br1440.platform.tracing.otel.javaagent.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.junit.jupiter.api.Test;

class AgentLoggingSchemaIdentityTest {

    @Test
    void reviewed_identity_matches_sha256_of_the_schema_file() throws IOException {
        // given
        Properties identity = loadProperties("/META-INF/platform-tracing/agent-logging-schema.properties");

        // when
        String schemaHash = PlatformLogSchemaHash.sha256OfNormalizedSchema(getClass());

        // then
        assertThat(identity.getProperty("schema.hashAlgorithm"))
                .isEqualTo(PlatformLogSchemaHash.ALGORITHM_ID);
        assertThat(identity.getProperty("schema.name")).isEqualTo("platform-log-event-v1");
        assertThat(identity.getProperty("schema.semanticVersion")).isEqualTo("1");
        assertThat(identity.getProperty("schema.sha256")).isEqualTo(schemaHash);
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
}
