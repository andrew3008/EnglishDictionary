package space.br1440.platform.tracing.otel.javaagent.logging;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLoggingSchemaIdentityTest {

    @Test
    void generated_groovy_identity_matches_java_recomputation_of_actual_schema() throws IOException {
        // given
        Map<String, byte[]> files = PlatformLogSchemaSetHash.readNormalizedFromClasspath(getClass());
        Properties identity = loadProperties("/META-INF/platform-tracing/agent-logging-schema.properties");

        // when
        String javaSetHash = PlatformLogSchemaSetHash.setSha256(files);

        // then
        assertThat(identity.getProperty("schema.hashAlgorithm"))
                .isEqualTo(PlatformLogSchemaSetHash.ALGORITHM_ID);
        assertThat(identity.getProperty("schema.name")).isEqualTo("platform-log-event-v1");
        assertThat(identity.getProperty("schema.semanticVersion")).isEqualTo("1");
        assertThat(identity.getProperty("schema.sha256")).isEqualTo(javaSetHash);
    }

    @Test
    void java_hash_matches_frozen_known_answer() throws IOException {
        // given
        Properties expected = loadProperties("/platform-log-schema-known-answer/expected.properties");
        Map<String, byte[]> files = PlatformLogSchemaSetHash.readNormalizedFromClasspath(
                getClass(),
                List.of("a.json", "b.yaml"),
                "platform-log-schema-known-answer"
        );

        // when
        String actual = PlatformLogSchemaSetHash.setSha256(files);

        // then
        assertThat(expected.getProperty("algorithmId")).isEqualTo(PlatformLogSchemaSetHash.ALGORITHM_ID);
        assertThat(actual).isEqualTo(expected.getProperty("expectedAggregateSha256"));
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
