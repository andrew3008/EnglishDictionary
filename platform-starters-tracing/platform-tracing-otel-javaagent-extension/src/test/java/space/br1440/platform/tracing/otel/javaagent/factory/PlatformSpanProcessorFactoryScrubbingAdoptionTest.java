package space.br1440.platform.tracing.otel.javaagent.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import jakarta.annotation.Nonnull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.configuration.ScrubbingMissingKeyPolicy;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationState;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly.PlatformScrubberFactory;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly.ScrubberFactoryTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.loader.ExtensionRuleLoader;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.SanitizerBootstrapException;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntimeTestFixtures;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

class PlatformSpanProcessorFactoryScrubbingAdoptionTest {

    private static boolean isBootstrapPolicyBoolean(String name) {
        return ExtensionPropertyNames.SAMPLING_ENABLED.equals(name)
                || ExtensionPropertyNames.EXPORTER_ENABLED.equals(name)
                || ExtensionPropertyNames.TRACE_CONTROL_HEADERS_ENABLED.equals(name);
    }

    private static ExtensionConfig scrubbingDisabledExtConfig() {
        return new ExtensionConfig(new ConfigProperties() {
            @Override public String getString(String name) { return null; }
            @Override public Boolean getBoolean(String name) {
                return isBootstrapPolicyBoolean(name) ? null : false;
            }
            @Override public Integer getInt(String name) { return null; }
            @Override public Long getLong(String name) { return null; }
            @Override public Double getDouble(String name) { return null; }
            @Override public Duration getDuration(String name) { return null; }
            @Override public List<String> getList(String name) { return null; }
            @Override public Map<String, String> getMap(String name) { return null; }
        });
    }

    private static ExtensionConfig scrubbingEnabledDefaultsExtConfig() {
        return new ExtensionConfig(new ConfigProperties() {
            @Override public String getString(String name) { return null; }
            @Override public Boolean getBoolean(String name) {
                if (isBootstrapPolicyBoolean(name)) {
                    return null;
                }
                return "platform.tracing.javaagent.scrubbing.enabled".equals(name) ? Boolean.TRUE : Boolean.FALSE;
            }
            @Override public Integer getInt(String name) { return null; }
            @Override public Long getLong(String name) { return null; }
            @Override public Double getDouble(String name) { return null; }
            @Override public Duration getDuration(String name) { return null; }
            @Override public List<String> getList(String name) { return null; }
            @Override public Map<String, String> getMap(String name) { return null; }
        });
    }

    private static ExtensionConfig scrubbingEnabledWithoutBuiltInsExtConfig() {
        return new ExtensionConfig(new ConfigProperties() {
            @Override public String getString(String name) {
                return "platform.tracing.javaagent.scrubbing.built-in-rules".equals(name) ? "" : null;
            }
            @Override public Boolean getBoolean(String name) {
                if (isBootstrapPolicyBoolean(name)) {
                    return null;
                }
                return "platform.tracing.javaagent.scrubbing.enabled".equals(name) ? Boolean.TRUE : Boolean.FALSE;
            }
            @Override public Integer getInt(String name) { return null; }
            @Override public Long getLong(String name) { return null; }
            @Override public Double getDouble(String name) { return null; }
            @Override public Duration getDuration(String name) { return null; }
            @Override public List<String> getList(String name) {
                return "platform.tracing.javaagent.scrubbing.built-in-rules".equals(name) ? List.of() : null;
            }
            @Override public Map<String, String> getMap(String name) { return null; }
        });
    }

    @SuppressWarnings("unchecked")
    private static ScrubbingRuntime getScrubbing(PlatformTracingMBeanRegistrar reg) throws Exception {
        Field runtimePlaneField = PlatformTracingMBeanRegistrar.class.getDeclaredField("runtimePlane");
        runtimePlaneField.setAccessible(true);
        Object runtimePlane = runtimePlaneField.get(reg);

        Field slotsField = runtimePlane.getClass().getDeclaredField("slots");
        slotsField.setAccessible(true);
        Object slots = slotsField.get(runtimePlane);

        Method slotAccessor = slots.getClass().getDeclaredMethod("scrubbing");
        slotAccessor.setAccessible(true);
        OwnerRegistrationSlot<ScrubbingRuntime> slot = (OwnerRegistrationSlot<ScrubbingRuntime>) slotAccessor.invoke(slots);

        OwnerRegistrationState<ScrubbingRuntime> state = slot.capture();
        return state instanceof OwnerRegistrationState.Present<ScrubbingRuntime> present ? present.owner() : null;
    }

    @Nested
    @DisplayName("Scrubbing disabled → no scrubbing owner registered")
    class ScrubbingDisabled {

        @Test
        void scrubbing_disabled_ext_config_no_scrubbing_processor() throws Exception {
            // given
            PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar(AgentInstanceId.random());
            PlatformSpanProcessorFactory factory = new PlatformSpanProcessorFactory(
                    registrar, ScrubbingRuntimeTestFixtures.disabled());

            ExtensionConfig extensionConfig = scrubbingDisabledExtConfig();

            // when
            factory.registerSpanProcessors(
                    SdkTracerProvider.builder(),
                    extensionConfig,
                    TracingSettingsSource.fromStartup(extensionConfig, registrar.agentInstanceId()));

            // then
            assertThat(getScrubbing(registrar))
                    .as("scrubbing.enabled = false → no scrubbing owner registered")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("Scrubbing enabled with defaults → session runtime registered")
    class ScrubbingEnabledWithDefaults {

        @Test
        void scrubbing_enabled_default_rules_registers_processor() throws Exception {
            // given
            PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar(AgentInstanceId.random());
            ExtensionConfig extensionConfig = scrubbingEnabledDefaultsExtConfig();
            PlatformSpanProcessorFactory factory = new PlatformSpanProcessorFactory(
                    registrar, new PlatformScrubberFactory().buildRuntime(extensionConfig)
            );

            // when
            factory.registerSpanProcessors(
                    SdkTracerProvider.builder(),
                    extensionConfig,
                    TracingSettingsSource.fromStartup(extensionConfig, registrar.agentInstanceId()));

            // then
            assertThat(getScrubbing(registrar))
                    .as("scrubbing.enabled = true with default rules → session runtime registered for JMX")
                    .isNotNull()
                    .satisfies(runtime -> {
                        assertThat(runtime.enabled()).isTrue();
                        assertThat(runtime.ruleCount()).isPositive();
                    });
        }

        @Test
        void empty_effective_rule_set_fails_secure_profile() {
            // given
            ExtensionConfig extensionConfig = scrubbingEnabledWithoutBuiltInsExtConfig();
            PlatformScrubberFactory factory = ScrubberFactoryTestFixtures.factory(List::of);

            // when/then
            assertThatThrownBy(() -> {
                try (ScrubbingRuntime runtime = factory.buildRuntime(extensionConfig)) {
                    assertThat(runtime).isNotNull();
                }
            })
                    .isInstanceOf(SanitizerBootstrapException.class)
                    .hasMessage("Scrubbing is enabled, but no effective sanitizing rules were loaded")
                    .extracting(failure -> ((SanitizerBootstrapException) failure).code())
                    .isEqualTo(SanitizerBootstrapException.RULE_SET_EMPTY);

        }

        @Test
        void bundled_rule_metadata_failure_preserves_cause_without_exposing_it_in_the_message() {
            // given
            IllegalStateException metadataFailure = new IllegalStateException("secret-canary");
            PlatformScrubberFactory factory = factoryWithFailingBundledRuleMetadata(metadataFailure);
            ExtensionConfig extensionConfig = scrubbingEnabledDefaultsExtConfig();

            // when/then
            assertThatThrownBy(() -> {
                try (ScrubbingRuntime runtime = factory.buildRuntime(extensionConfig)) {
                    assertThat(runtime).isNotNull();
                }
            })
                    .isInstanceOf(SanitizerBootstrapException.class)
                    .hasMessage("Bundled scrubbing rule metadata validation failed (java.lang.IllegalStateException)")
                    .hasCause(metadataFailure)
                    .satisfies(failure -> {
                        assertThat(((SanitizerBootstrapException) failure).code())
                                .isEqualTo(SanitizerBootstrapException.RULE_METADATA_INVALID);

                        assertThat(failure.getMessage()).doesNotContain("secret-canary");
                    });
        }
    }

    private static PlatformScrubberFactory factoryWithFailingBundledRuleMetadata(RuntimeException metadataFailure) {
        SpanAttributeScrubbingRule unsafeRule = new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override
            public String name() {
                return "unsafe";
            }

            @Override
            public boolean critical() {
                throw metadataFailure;
            }

            @Nonnull
            @Override
            public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
                return ScrubbingDecision.keep();
            }
        };
        return ScrubberFactoryTestFixtures.factory(() -> List.of(unsafeRule));
    }

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
            // given/when/then
            ExtensionConfig cfg = configWithStringProperty("platform.tracing.scrubbing.hmac-key", null);
            assertThat(cfg.scrubbing().hmacKey().isPresent())
                    .as("hmacKey is absent when SCRUBBING_HMAC_KEY not set")
                    .isFalse();
        }

        @Test
        void hmacKey_passthrough_when_configured() {
            // given/when/then
            ExtensionConfig cfg = configWithStringProperty(
                    "platform.tracing.scrubbing.hmac-key", "my-secret-key");
            byte[] hmacKeyBytes = Objects.requireNonNull(cfg.scrubbing().hmacKey().copyBytes());
            assertThat(new String(hmacKeyBytes, StandardCharsets.UTF_8))
                    .as("hmacKey passthrough")
                    .isEqualTo("my-secret-key");
        }

        @Test
        void missingKeyPolicy_default_is_mask() {
            // given/when/then
            ExtensionConfig cfg = configWithStringProperty("unused.key", null);
            assertThat(cfg.scrubbing().missingKeyPolicy())
                    .as("missingKeyPolicy default = 'mask'")
                    .isEqualTo(ExtensionDefaults.DEFAULT_SCRUBBING_MISSING_KEY_POLICY);
        }

        @Test
        void missingKeyPolicy_override_fail_fast() {
            // given/when/then
            ExtensionConfig cfg = configWithStringProperty(
                    "platform.tracing.scrubbing.missing-key-policy", "fail_fast");
            assertThat(cfg.scrubbing().missingKeyPolicy())
                    .as("missingKeyPolicy override = 'fail_fast'")
                    .isEqualTo(ScrubbingMissingKeyPolicy.FAIL_FAST);
        }

        @Test
        void rulesConfig_null_when_not_configured() {
            // given/when/then
            ExtensionConfig cfg = configWithStringProperty("unused.key", null);
            assertThat(cfg.scrubbing().rulesConfig())
                    .as("rulesConfig is null when SCRUBBING_RULES_CONFIG not set")
                    .isNull();
        }

        @Test
        void rulesConfig_passthrough_when_configured() {
            // given/when/then
            ExtensionConfig cfg = configWithStringProperty(
                    "platform.tracing.scrubbing.rules-config", "classpath:tracing/custom-rules.properties");
            assertThat(cfg.scrubbing().rulesConfig())
                    .as("rulesConfig passthrough")
                    .isEqualTo("classpath:tracing/custom-rules.properties");
        }

        @Test
        void rulesExtensions_null_when_not_configured() {
            // given/when/then
            ExtensionConfig cfg = configWithStringProperty("unused.key", null);
            assertThat(cfg.scrubbing().rulesExtensions())
                    .as("rulesExtensions is null when SCRUBBING_RULES_EXTENSIONS not set")
                    .isNull();
        }

        @Test
        void rulesExtensions_passthrough_when_configured() {
            // given/when/then
            ExtensionConfig cfg = configWithStringProperty(
                    "platform.tracing.scrubbing.rules.extensions", "/opt/app/rules.jar");
            assertThat(cfg.scrubbing().rulesExtensions())
                    .as("rulesExtensions passthrough")
                    .isEqualTo("/opt/app/rules.jar");
        }

        @Test
        void rulesValidationMode_default_is_WARNING() {
            // given/when/then
            ExtensionConfig cfg = configWithStringProperty("unused.key", null);
            assertThat(cfg.scrubbing().rulesValidationMode())
                    .as("rulesValidationMode default = WARNING")
                    .isEqualTo(ExtensionRuleLoader.ValidationMode.WARNING);
        }

        @Test
        void rulesValidationMode_override_ERROR() {
            // given/when/then
            ExtensionConfig cfg = configWithStringProperty(
                    "platform.tracing.scrubbing.rules.validation-mode", "ERROR");
            assertThat(cfg.scrubbing().rulesValidationMode())
                    .as("rulesValidationMode override = ERROR")
                    .isEqualTo(ExtensionRuleLoader.ValidationMode.ERROR);
        }

        @Test
        void builtInRules_default_non_empty() {
            // given/when/then
            ExtensionConfig cfg = configWithListProperty("unused.key", null);
            assertThat(cfg.scrubbing().builtInRules())
                    .as("builtInRules default is non-empty (from BuiltInSpanAttributeScrubbingRules.defaultConfigNames())")
                    .isNotEmpty()
                    .containsAll(List.of("password", "jwt", "email"));
        }

        @Test
        void builtInRules_override_preserved() {
            // given/when/then
            ExtensionConfig cfg = configWithListProperty("platform.tracing.javaagent.scrubbing.built-in-rules", List.of("password", "jwt"));
            assertThat(cfg.scrubbing().builtInRules())
                    .as("builtInRules override preserved")
                    .containsExactly("password", "jwt");
        }
    }
}
