package space.br1440.platform.datasource.postgres.api.properties;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import space.br1440.platform.datasource.postgres.internal.integration.health.RoutingHealthProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Регрессия: configuration processor разворачивает top-level {@link PoolProperties}
 * в полное дерево {@code pool.write.*} / {@code pool.read.*}. Для typed Enum свойств
 * IDE autocomplete не требует секции {@code hints} — достаточно {@code type} в metadata.
 */
@DisplayName("Configuration metadata generation")
class ConfigurationMetadataGenerationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void generatedMetadataContainsPoolWriteAndReadLeafProperties() throws Exception {
        Map<String, JsonNode> properties = loadPropertiesByName();

        assertThat(properties)
                .containsKeys(
                        PgClusterProperties.PREFIX + ".pool.write.maximum-pool-size",
                        PgClusterProperties.PREFIX + ".pool.read.maximum-pool-size",
                        PgClusterProperties.PREFIX + ".pool.write.writer-validation-mode",
                        PgClusterProperties.PREFIX + ".pool.write.minimum-idle",
                        PgClusterProperties.PREFIX + ".pool.read.minimum-idle");

        assertThat(properties.get(PgClusterProperties.PREFIX + ".pool.write.maximum-pool-size")
                .path("sourceType").asText())
                .isEqualTo(PoolProperties.class.getName());
    }

    @Test
    void generatedMetadataContainsRoutingHealthProperties() throws Exception {
        Map<String, JsonNode> properties = loadPropertiesByName();

        assertThat(properties)
                .containsKeys(
                        RoutingHealthProperties.PREFIX + ".timeout",
                        RoutingHealthProperties.PREFIX + ".query-timeout");
    }

    @Test
    void generatedMetadataTypesEnumPropertiesForIdeAutoCompletion() throws Exception {
        Map<String, JsonNode> properties = loadPropertiesByName();

        // Spring Boot не генерирует hints[] для typed Enum — IDE autocomplete берёт значения из type.
        // См. https://docs.spring.io/spring-boot/specification/configuration-metadata/manual-hints.html
        assertThat(properties.get(PgClusterProperties.PREFIX + ".datasource-routing-mode")
                .path("type").asText())
                .isEqualTo(DataSourceRoutingMode.class.getName());
        assertThat(properties.get(PgClusterProperties.PREFIX + ".read.mode")
                .path("type").asText())
                .isEqualTo(ReadMode.class.getName());
        assertThat(properties.get(PgClusterProperties.PREFIX + ".pool.write.writer-validation-mode")
                .path("type").asText())
                .isEqualTo(WriterValidationMode.class.getName());
    }

    private static Map<String, JsonNode> loadPropertiesByName() throws Exception {
        JsonNode root = loadMetadataRoot();
        Map<String, JsonNode> byName = new HashMap<>();
        for (JsonNode property : root.path("properties")) {
            byName.put(property.path("name").asText(), property);
        }
        return byName;
    }

    private static JsonNode loadMetadataRoot() throws Exception {
        try (InputStream in = ConfigurationMetadataGenerationTest.class.getClassLoader()
                .getResourceAsStream("META-INF/spring-configuration-metadata.json")) {
            assertThat(in).as("META-INF/spring-configuration-metadata.json на classpath после compileJava").isNotNull();
            return MAPPER.readTree(in);
        }
    }
}
