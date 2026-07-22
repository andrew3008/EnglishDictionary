package space.br1440.platform.tracing.otel.javaagent.configuration.spi;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.utils.Strings;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Тест живёт в том же пакете (.spi), что и тестируемые классы, чтобы иметь доступ
// к package-private ExtensionEnvironmentVariables без расширения его видимости.
class JavaAgentExtensionPathsTest {

    private static final String PROP_KEY = ExtensionPropertyNames.OTEL_JAVAAGENT_EXTENSIONS;
    // ExtensionEnvironmentVariables — package-private, доступен без импорта (тот же пакет .spi)
    private static final String ENV_KEY  = ExtensionEnvironmentVariables.OTEL_JAVAAGENT_EXTENSIONS;

    @AfterEach
    void clearSystemProperty() {
        System.clearProperty(PROP_KEY);
    }

    @Test
    void resolveRawValue_returnsConfigValueWhenPresent() {
        ConfigProperties config = stringConfig(PROP_KEY, "/opt/ext/platform.jar");

        String result = JavaAgentExtensionPaths.resolveRawValue(config);

        assertThat(result).isEqualTo("/opt/ext/platform.jar");
    }

    @Test
    void resolveRawValue_returnsSystemPropertyWhenConfigAbsent() {
        System.setProperty(PROP_KEY, "/opt/ext/via-sysprop.jar");
        ConfigProperties config = emptyConfig();

        String result = JavaAgentExtensionPaths.resolveRawValue(config);

        assertThat(result).isEqualTo("/opt/ext/via-sysprop.jar");
    }

    @Test
    void resolveRawValue_configValueWinsOverSystemProperty() {
        System.setProperty(PROP_KEY, "/opt/ext/sysprop.jar");
        ConfigProperties config = stringConfig(PROP_KEY, "/opt/ext/config.jar");

        String result = JavaAgentExtensionPaths.resolveRawValue(config);

        assertThat(result).isEqualTo("/opt/ext/config.jar");
    }

    @Test
    void resolveRawValue_returnsNullWhenAllSourcesAbsent() {
        ConfigProperties config = emptyConfig();

        String result = JavaAgentExtensionPaths.resolveRawValue(config);

        // env var OTEL_JAVAAGENT_EXTENSIONS отсутствует в тестовой JVM; ожидаем null или blank.
        assertThat(Strings.isBlank(result)).isTrue();
    }

    @Test
    void resolveRawValue_treatsBlankConfigAsAbsent() {
        System.setProperty(PROP_KEY, "/opt/ext/fallback.jar");
        ConfigProperties config = stringConfig(PROP_KEY, "   ");

        String result = JavaAgentExtensionPaths.resolveRawValue(config);

        assertThat(result).isEqualTo("/opt/ext/fallback.jar");
    }

    // -- Стабы ------------------------------------------------------------------------------------

    private static ConfigProperties emptyConfig() {
        return new ConfigProperties() {
            @Override public String getString(String name)           { return null; }
            @Override public Boolean getBoolean(String name)         { return null; }
            @Override public Integer getInt(String name)             { return null; }
            @Override public Long getLong(String name)               { return null; }
            @Override public Double getDouble(String name)           { return null; }
            @Override public Duration getDuration(String name)       { return null; }
            @Override public List<String> getList(String name)       { return null; }
            @Override public Map<String, String> getMap(String name) { return null; }
        };
    }

    private static ConfigProperties stringConfig(String key, String value) {
        return new ConfigProperties() {
            @Override public String getString(String name)           { return key.equals(name) ? value : null; }
            @Override public Boolean getBoolean(String name)         { return null; }
            @Override public Integer getInt(String name)             { return null; }
            @Override public Long getLong(String name)               { return null; }
            @Override public Double getDouble(String name)           { return null; }
            @Override public Duration getDuration(String name)       { return null; }
            @Override public List<String> getList(String name)       { return null; }
            @Override public Map<String, String> getMap(String name) { return null; }
        };
    }
}
