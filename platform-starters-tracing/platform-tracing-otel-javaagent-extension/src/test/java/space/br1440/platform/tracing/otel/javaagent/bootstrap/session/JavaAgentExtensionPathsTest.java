package space.br1440.platform.tracing.otel.javaagent.bootstrap.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

class JavaAgentExtensionPathsTest {

    private static final String PROP_KEY = ExtensionPropertyNames.OTEL_JAVAAGENT_EXTENSIONS;

    @AfterEach
    void clearSystemProperty() {
        System.clearProperty(PROP_KEY);
    }

    @Test
    void resolveRawValue_returnsConfigValueWhenPresent() {
        // given
        ConfigProperties config = stringConfig(PROP_KEY, "/opt/ext/platform.jar");

        // when
        String result = JavaAgentExtensionPaths.resolveRawValue(config);

        // then
        assertThat(result).isEqualTo("/opt/ext/platform.jar");
    }

    @Test
    void resolveRawValue_returnsSystemPropertyWhenConfigAbsent() {
        // given
        System.setProperty(PROP_KEY, "/opt/ext/via-sysprop.jar");
        ConfigProperties config = emptyConfig();

        // when
        String result = JavaAgentExtensionPaths.resolveRawValue(config);

        // then
        assertThat(result).isEqualTo("/opt/ext/via-sysprop.jar");
    }

    @Test
    void resolveRawValue_configValueWinsOverSystemProperty() {
        // given
        System.setProperty(PROP_KEY, "/opt/ext/sysprop.jar");
        ConfigProperties config = stringConfig(PROP_KEY, "/opt/ext/config.jar");

        // when
        String result = JavaAgentExtensionPaths.resolveRawValue(config);

        // then
        assertThat(result).isEqualTo("/opt/ext/config.jar");
    }

    @Test
    void resolveRawValue_returnsNullWhenAllSourcesAbsent() {
        // given
        ConfigProperties config = emptyConfig();

        // when
        String result = JavaAgentExtensionPaths.resolveRawValue(config);

        // then
        assertThat(StringUtils.isNullOrBlank(result)).isTrue();
    }

    @Test
    void resolveRawValue_preservesWhitespaceConfigValue() {
        // given
        System.setProperty(PROP_KEY, "/opt/ext/fallback.jar");
        ConfigProperties config = stringConfig(PROP_KEY, "   ");

        // when
        String result = JavaAgentExtensionPaths.resolveRawValue(config);

        // then
        assertThat(result).isEqualTo("   ");
    }

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

    @SuppressWarnings("SameParameterValue")
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
