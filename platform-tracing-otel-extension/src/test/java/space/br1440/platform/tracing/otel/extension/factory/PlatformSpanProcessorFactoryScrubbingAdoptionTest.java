package space.br1440.platform.tracing.otel.extension.factory;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.extension.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.extension.configuration.ExtensionDefaults;
import space.br1440.platform.tracing.otel.extension.jmx.PlatformTracingJmxRegistrar;
import space.br1440.platform.tracing.otel.extension.processor.ScrubbingSpanProcessor;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link PlatformSpanProcessorFactory} reads scrubbing startup configuration from
 * {@code ScrubbingExtensionConfig} (via {@link ExtensionConfig}) rather than directly from
 * {@link ConfigProperties} (PR-4 migration).
 *
 * <p>Testing strategy:
 * <ul>
 *   <li>Factory-level tests: pass an {@code ExtensionConfig} with known scrubbing settings,
 *       verify {@code PlatformTracingJmxRegistrar} captures the expected processors.</li>
 *   <li>Domain config parity tests: directly verify that {@code ScrubbingExtensionConfig}
 *       surfaces the correct default and override values for every scrubbing property.</li>
 * </ul>
 *
 * <p>The {@code ConfigProperties} stub passed to {@code registerSpanProcessors} is minimal —
 * it returns {@code null} for all keys. This covers the two remaining OTel SPI paths that still
 * use {@code ConfigProperties}: {@code ScrubbingRulesLoader.load(config)} (reads
 * {@code platform.tracing.scrubbing.rules-config}) and
 * {@code JavaAgentExtensionPaths.resolveRawValue(config)} (reads {@code otel.javaagent.extensions}).
 * Both return empty/null for this stub, which is correct for unit tests.
 */
class PlatformSpanProcessorFactoryScrubbingAdoptionTest {

    // -------------------------------------------------------------------------
    // Stubs
    // -------------------------------------------------------------------------

    /**
     * Minimal OTel-only ConfigProperties stub — returns null for everything.
     * After PR-4 migration, scrubbing startup values no longer come from here;
     * this stub only satisfies the two remaining SPI paths inside collectScrubbingRules.
     */
    private static ConfigProperties otelOnlyConfig() {
        return new ConfigProperties() {
            @Override public String getString(String name) { return null; }
            @Override public Boolean getBoolean(String name) { return null; }
            @Override public Integer getInt(String name) { return null; }
            @Override public Long getLong(String name) { return null; }
            @Override public Double getDouble(String name) { return null; }
            @Override public Duration getDuration(String name) { return null; }
            @Override public List<String> getList(String name) { return null; }
            @Override public Map<String, String> getMap(String name) { return null; }
        };
    }

    /** ExtensionConfig with scrubbing.enabled = false, all other booleans also false. */
    private static ExtensionConfig scrubbingDisabledExtConfig() {
        return new ExtensionConfig(new ConfigProperties() {
            @Override public String getString(String name) { return null; }
            @Override public Boolean getBoolean(String name) { return false; }
            @Override public Integer getInt(String name) { return null; }
            @Override public Long getLong(String name) { return null; }
            @Override public Double getDouble(String name) { return null; }
            @Override public Duration getDuration(String name) { return null; }
            @Override public List<String> getList(String name) { return null; }
            @Override public Map<String, String> getMap(String name) { return null; }
        });
    }

    /**
     * ExtensionConfig with scrubbing.enabled = true and null for all list/string properties
     * → default built-in rules are used (non-empty).
     */
    private static ExtensionConfig scrubbingEnabledDefaultsExtConfig() {
        return new ExtensionConfig(new ConfigProperties() {
            @Override public String getString(String name) { return null; }
            @Override public Boolean getBoolean(String name) {
                return "platform.tracing.scrubbing.enabled".equals(name) ? Boolean.TRUE : Boolean.FALSE;
            }
            @Override public Integer getInt(String name) { return null; }
            @Override public Long getLong(String name) { return null; }
            @Override public Double getDouble(String name) { return null; }
            @Override public Duration getDuration(String name) { return null; }
            @Override public List<String> getList(String name) { return null; }
            @Override public Map<String, String> getMap(String name) { return null; }
        });
    }

    // -------------------------------------------------------------------------
    // Reflection helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static <T> T registrarField(PlatformTracingJmxRegistrar reg, String fieldName) throws Exception {
        Field f = PlatformTracingJmxRegistrar.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        return ((AtomicReference<T>) f.get(reg)).get();
    }

    private static ScrubbingSpanProcessor getScrubbing(PlatformTracingJmxRegistrar r) throws Exception {
        return registrarField(r, "registeredScrubbing");
    }

    // -------------------------------------------------------------------------
    // Factory-level tests
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Scrubbing disabled → no ScrubbingSpanProcessor registered")
    class ScrubbingDisabled {

        @Test
        void scrubbing_disabled_ext_config_no_scrubbing_processor() throws Exception {
            PlatformTracingJmxRegistrar registrar = new PlatformTracingJmxRegistrar();
            PlatformSpanProcessorFactory factory = new PlatformSpanProcessorFactory(registrar);

            factory.registerSpanProcessors(
                    SdkTracerProvider.builder(),
                    scrubbingDisabledExtConfig(),
                    otelOnlyConfig());

            assertThat(getScrubbing(registrar))
                    .as("scrubbing.enabled = false → no ScrubbingSpanProcessor registered")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("Scrubbing enabled with defaults → ScrubbingSpanProcessor registered")
    class ScrubbingEnabledWithDefaults {

        @Test
        void scrubbing_enabled_default_rules_registers_processor() throws Exception {
            PlatformTracingJmxRegistrar registrar = new PlatformTracingJmxRegistrar();
            PlatformSpanProcessorFactory factory = new PlatformSpanProcessorFactory(registrar);

            factory.registerSpanProcessors(
                    SdkTracerProvider.builder(),
                    scrubbingEnabledDefaultsExtConfig(),
                    otelOnlyConfig());

            assertThat(getScrubbing(registrar))
                    .as("scrubbing.enabled = true with default rules → ScrubbingSpanProcessor registered")
                    .isNotNull()
                    .isInstanceOf(ScrubbingSpanProcessor.class);
        }
    }

    // -------------------------------------------------------------------------
    // Domain config parity tests — ScrubbingExtensionConfig via ExtensionConfig
    // -------------------------------------------------------------------------

    /**
     * Builds an ExtensionConfig from a ConfigProperties that overrides only the
     * given string property; all others return null.
     */
    private static ExtensionConfig configWithStringProperty(String propertyKey, String value) {
        return new ExtensionConfig(new ConfigProperties() {
            @Override public String getString(String name) {
                return propertyKey.equals(name) ? value : null;
            }
            @Override public Boolean getBoolean(String name) { return null; }
            @Override public Integer getInt(String name) { return null; }
            @Override public Long getLong(String name) { return null; }
            @Override public Double getDouble(String name) { return null; }
            @Override public Duration getDuration(String name) { return null; }
            @Override public List<String> getList(String name) { return null; }
            @Override public Map<String, String> getMap(String name) { return null; }
        });
    }

    /**
     * Builds an ExtensionConfig from a ConfigProperties that overrides only the
     * given list property; all others return null.
     */
    private static ExtensionConfig configWithListProperty(String propertyKey, List<String> value) {
        return new ExtensionConfig(new ConfigProperties() {
            @Override public String getString(String name) { return null; }
            @Override public Boolean getBoolean(String name) { return null; }
            @Override public Integer getInt(String name) { return null; }
            @Override public Long getLong(String name) { return null; }
            @Override public Double getDouble(String name) { return null; }
            @Override public Duration getDuration(String name) { return null; }
            @Override public List<String> getList(String name) {
                return propertyKey.equals(name) ? value : null;
            }
            @Override public Map<String, String> getMap(String name) { return null; }
        });
    }

    @Nested
    @DisplayName("ScrubbingExtensionConfig defaults and nullable passthroughs")
    class ScrubbingConfigParity {

        @Test
        void hmacKey_null_when_not_configured() {
            ExtensionConfig cfg = configWithStringProperty("platform.tracing.scrubbing.hmac-key", null);
            assertThat(cfg.scrubbing().hmacKey())
                    .as("hmacKey is null when SCRUBBING_HMAC_KEY not set")
                    .isNull();
        }

        @Test
        void hmacKey_passthrough_when_configured() {
            ExtensionConfig cfg = configWithStringProperty(
                    "platform.tracing.scrubbing.hmac-key", "my-secret-key");
            assertThat(cfg.scrubbing().hmacKey())
                    .as("hmacKey passthrough")
                    .isEqualTo("my-secret-key");
        }

        @Test
        void missingKeyPolicy_default_is_mask() {
            ExtensionConfig cfg = configWithStringProperty("unused.key", null);
            assertThat(cfg.scrubbing().missingKeyPolicy())
                    .as("missingKeyPolicy default = 'mask'")
                    .isEqualTo(ExtensionDefaults.DEFAULT_SCRUBBING_MISSING_KEY_POLICY);
        }

        @Test
        void missingKeyPolicy_override_fail_fast() {
            ExtensionConfig cfg = configWithStringProperty(
                    "platform.tracing.scrubbing.missing-key-policy", "fail_fast");
            assertThat(cfg.scrubbing().missingKeyPolicy())
                    .as("missingKeyPolicy override = 'fail_fast'")
                    .isEqualTo("fail_fast");
        }

        @Test
        void hashKeyId_null_when_not_configured() {
            ExtensionConfig cfg = configWithStringProperty("unused.key", null);
            assertThat(cfg.scrubbing().hashKeyId())
                    .as("hashKeyId is null when SCRUBBING_HASH_KEY_ID not set")
                    .isNull();
        }

        @Test
        void hashKeyId_passthrough_when_configured() {
            ExtensionConfig cfg = configWithStringProperty(
                    "platform.tracing.scrubbing.hash.key-id", "key-42");
            assertThat(cfg.scrubbing().hashKeyId())
                    .as("hashKeyId passthrough")
                    .isEqualTo("key-42");
        }

        @Test
        void rulesConfig_null_when_not_configured() {
            ExtensionConfig cfg = configWithStringProperty("unused.key", null);
            assertThat(cfg.scrubbing().rulesConfig())
                    .as("rulesConfig is null when SCRUBBING_RULES_CONFIG not set")
                    .isNull();
        }

        @Test
        void rulesConfig_passthrough_when_configured() {
            ExtensionConfig cfg = configWithStringProperty(
                    "platform.tracing.scrubbing.rules-config", "classpath:tracing/custom-rules.properties");
            assertThat(cfg.scrubbing().rulesConfig())
                    .as("rulesConfig passthrough")
                    .isEqualTo("classpath:tracing/custom-rules.properties");
        }

        @Test
        void rulesExtensions_null_when_not_configured() {
            ExtensionConfig cfg = configWithStringProperty("unused.key", null);
            assertThat(cfg.scrubbing().rulesExtensions())
                    .as("rulesExtensions is null when SCRUBBING_RULES_EXTENSIONS not set")
                    .isNull();
        }

        @Test
        void rulesExtensions_passthrough_when_configured() {
            ExtensionConfig cfg = configWithStringProperty(
                    "platform.tracing.scrubbing.rules.extensions", "/opt/app/rules.jar");
            assertThat(cfg.scrubbing().rulesExtensions())
                    .as("rulesExtensions passthrough")
                    .isEqualTo("/opt/app/rules.jar");
        }

        @Test
        void rulesValidationMode_default_is_LENIENT() {
            ExtensionConfig cfg = configWithStringProperty("unused.key", null);
            assertThat(cfg.scrubbing().rulesValidationMode())
                    .as("rulesValidationMode default = 'LENIENT'")
                    .isEqualToIgnoringCase(ExtensionDefaults.DEFAULT_SCRUBBING_VALIDATION_MODE);
        }

        @Test
        void rulesValidationMode_override_STRICT() {
            ExtensionConfig cfg = configWithStringProperty(
                    "platform.tracing.scrubbing.rules.validation-mode", "STRICT");
            assertThat(cfg.scrubbing().rulesValidationMode())
                    .as("rulesValidationMode override = 'STRICT'")
                    .isEqualToIgnoringCase("STRICT");
        }

        @Test
        void builtInRules_default_non_empty() {
            ExtensionConfig cfg = configWithListProperty("unused.key", null);
            assertThat(cfg.scrubbing().builtInRules())
                    .as("builtInRules default is non-empty (from BuiltInSpanAttributeScrubbingRules.defaultConfigNames())")
                    .isNotEmpty()
                    .containsAll(List.of("password", "jwt", "email"));
        }

        @Test
        void builtInRules_override_preserved() {
            ExtensionConfig cfg = configWithListProperty(
                    "platform.tracing.scrubbing.built-in-rules", List.of("password", "jwt"));
            assertThat(cfg.scrubbing().builtInRules())
                    .as("builtInRules override preserved")
                    .containsExactly("password", "jwt");
        }
    }
}
