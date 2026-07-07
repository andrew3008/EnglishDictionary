package space.br1440.platform.tracing.otel.extension.factory;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.extension.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.extension.jmx.PlatformTracingJmxRegistrar;
import space.br1440.platform.tracing.otel.extension.processor.MetricsSpanProcessor;
import space.br1440.platform.tracing.otel.extension.processor.PlatformCompositeSpanProcessor;
import space.br1440.platform.tracing.otel.extension.processor.SpanWatchdogProcessor;
import space.br1440.platform.tracing.otel.extension.processor.ValidatingSpanProcessor;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link PlatformSpanProcessorFactory} reads migrated domains from
 * {@link ExtensionConfig} rather than directly from {@link ConfigProperties}.
 * <p>
 * Testing strategy: pass an ExtensionConfig with known values, then verify
 * the JMX registrar captures the expected processors via reflection on
 * {@link PlatformTracingJmxRegistrar}'s AtomicReference fields.
 */
class PlatformSpanProcessorFactoryExtensionConfigAdoptionTest {

    // ---------- helpers ----------

    /** An empty ConfigProperties stub — scrubbing.enabled will be false via ExtensionConfig stub. */
    private static ConfigProperties scrubbingDisabledConfig() {
        return new ConfigProperties() {
            @Override public String getString(String name) { return null; }
            @Override public Boolean getBoolean(String name) {
                // scrubbing.enabled = false so collectScrubbingRules is never called
                if ("platform.tracing.scrubbing.enabled".equals(name)) return false;
                return null;
            }
            @Override public Integer getInt(String name) { return null; }
            @Override public Long getLong(String name) { return null; }
            @Override public Double getDouble(String name) { return null; }
            @Override public Duration getDuration(String name) { return null; }
            @Override public List<String> getList(String name) { return null; }
            @Override public Map<String, String> getMap(String name) { return null; }
        };
    }

    private static ExtensionConfig configWithAllDisabled() {
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

    private static ExtensionConfig configWithMetricsEnabled() {
        return new ExtensionConfig(new ConfigProperties() {
            @Override public String getString(String name) { return null; }
            @Override public Boolean getBoolean(String name) {
                return "platform.tracing.metrics.enabled".equals(name) ? true : false;
            }
            @Override public Integer getInt(String name) { return null; }
            @Override public Long getLong(String name) { return null; }
            @Override public Double getDouble(String name) { return null; }
            @Override public Duration getDuration(String name) { return null; }
            @Override public List<String> getList(String name) { return null; }
            @Override public Map<String, String> getMap(String name) { return null; }
        });
    }

    private static ExtensionConfig configWithWatchdogEnabled() {
        return new ExtensionConfig(new ConfigProperties() {
            @Override public String getString(String name) { return null; }
            @Override public Boolean getBoolean(String name) {
                return "platform.tracing.watchdog.enabled".equals(name) ? true : false;
            }
            @Override public Integer getInt(String name) { return null; }
            @Override public Long getLong(String name) { return null; }
            @Override public Double getDouble(String name) { return null; }
            @Override public Duration getDuration(String name) { return null; }
            @Override public Map<String, String> getMap(String name) { return null; }
            @Override public List<String> getList(String name) { return null; }
        });
    }

    private static ExtensionConfig configWithValidationEnabled() {
        return new ExtensionConfig(new ConfigProperties() {
            @Override public String getString(String name) { return null; }
            @Override public Boolean getBoolean(String name) {
                return "platform.tracing.validation.enabled".equals(name) ? true : false;
            }
            @Override public Integer getInt(String name) { return null; }
            @Override public Long getLong(String name) { return null; }
            @Override public Double getDouble(String name) { return null; }
            @Override public Duration getDuration(String name) { return null; }
            @Override public List<String> getList(String name) { return null; }
            @Override public Map<String, String> getMap(String name) { return null; }
        });
    }

    // ---- reflection helpers to inspect JMX registrar internal state ----

    @SuppressWarnings("unchecked")
    private static <T> T registrarField(PlatformTracingJmxRegistrar reg, String fieldName) throws Exception {
        Field f = PlatformTracingJmxRegistrar.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        return ((AtomicReference<T>) f.get(reg)).get();
    }

    private static MetricsSpanProcessor getMetrics(PlatformTracingJmxRegistrar r) throws Exception {
        return registrarField(r, "registeredMetrics");
    }

    private static SpanWatchdogProcessor getWatchdog(PlatformTracingJmxRegistrar r) throws Exception {
        return registrarField(r, "registeredWatchdog");
    }

    private static ValidatingSpanProcessor getValidating(PlatformTracingJmxRegistrar r) throws Exception {
        return registrarField(r, "registeredValidating");
    }

    private static PlatformCompositeSpanProcessor getComposite(PlatformTracingJmxRegistrar r) throws Exception {
        return registrarField(r, "registeredComposite");
    }

    // ---------- tests ----------

    @Nested
    @DisplayName("All subsystems disabled → no processors registered")
    class AllDisabled {

        @Test
        void no_composite_set_when_all_disabled() throws Exception {
            PlatformTracingJmxRegistrar registrar = new PlatformTracingJmxRegistrar();
            PlatformSpanProcessorFactory factory = new PlatformSpanProcessorFactory(registrar);

            factory.registerSpanProcessors(
                    SdkTracerProvider.builder(),
                    configWithAllDisabled(),
                    scrubbingDisabledConfig());

            assertThat(getComposite(registrar)).as("no composite when all disabled").isNull();
        }

        @Test
        void no_metrics_set_when_all_disabled() throws Exception {
            PlatformTracingJmxRegistrar registrar = new PlatformTracingJmxRegistrar();
            PlatformSpanProcessorFactory factory = new PlatformSpanProcessorFactory(registrar);

            factory.registerSpanProcessors(
                    SdkTracerProvider.builder(),
                    configWithAllDisabled(),
                    scrubbingDisabledConfig());

            assertThat(getMetrics(registrar)).as("no metrics when all disabled").isNull();
        }

        @Test
        void no_watchdog_set_when_all_disabled() throws Exception {
            PlatformTracingJmxRegistrar registrar = new PlatformTracingJmxRegistrar();
            PlatformSpanProcessorFactory factory = new PlatformSpanProcessorFactory(registrar);

            factory.registerSpanProcessors(
                    SdkTracerProvider.builder(),
                    configWithAllDisabled(),
                    scrubbingDisabledConfig());

            assertThat(getWatchdog(registrar)).as("no watchdog when all disabled").isNull();
        }
    }

    @Nested
    @DisplayName("Individual subsystem enabled → correct processor registered")
    class IndividualEnabled {

        @Test
        void metrics_enabled_registers_metrics_processor() throws Exception {
            PlatformTracingJmxRegistrar registrar = new PlatformTracingJmxRegistrar();
            PlatformSpanProcessorFactory factory = new PlatformSpanProcessorFactory(registrar);

            factory.registerSpanProcessors(
                    SdkTracerProvider.builder(),
                    configWithMetricsEnabled(),
                    scrubbingDisabledConfig());

            assertThat(getMetrics(registrar))
                    .as("metrics enabled → MetricsSpanProcessor set on registrar")
                    .isNotNull()
                    .isInstanceOf(MetricsSpanProcessor.class);
        }

        @Test
        void metrics_enabled_registers_composite() throws Exception {
            PlatformTracingJmxRegistrar registrar = new PlatformTracingJmxRegistrar();
            PlatformSpanProcessorFactory factory = new PlatformSpanProcessorFactory(registrar);

            factory.registerSpanProcessors(
                    SdkTracerProvider.builder(),
                    configWithMetricsEnabled(),
                    scrubbingDisabledConfig());

            assertThat(getComposite(registrar))
                    .as("at least one processor → composite set on registrar")
                    .isNotNull();
        }

        @Test
        void watchdog_enabled_registers_watchdog_processor() throws Exception {
            PlatformTracingJmxRegistrar registrar = new PlatformTracingJmxRegistrar();
            PlatformSpanProcessorFactory factory = new PlatformSpanProcessorFactory(registrar);

            factory.registerSpanProcessors(
                    SdkTracerProvider.builder(),
                    configWithWatchdogEnabled(),
                    scrubbingDisabledConfig());

            assertThat(getWatchdog(registrar))
                    .as("watchdog enabled → SpanWatchdogProcessor set on registrar")
                    .isNotNull()
                    .isInstanceOf(SpanWatchdogProcessor.class);
        }

        @Test
        void validation_enabled_registers_validating_processor() throws Exception {
            PlatformTracingJmxRegistrar registrar = new PlatformTracingJmxRegistrar();
            PlatformSpanProcessorFactory factory = new PlatformSpanProcessorFactory(registrar);

            factory.registerSpanProcessors(
                    SdkTracerProvider.builder(),
                    configWithValidationEnabled(),
                    scrubbingDisabledConfig());

            assertThat(getValidating(registrar))
                    .as("validation enabled → ValidatingSpanProcessor set on registrar")
                    .isNotNull()
                    .isInstanceOf(ValidatingSpanProcessor.class);
        }
    }

    @Nested
    @DisplayName("Default config (no properties set) — watchdog not added")
    class DefaultConfig {

        /**
         * SP-01 / Factory Test.
         * Without any explicit watchdog configuration, the factory must not add
         * {@link SpanWatchdogProcessor} to the pipeline — Watchdog is opt-in.
         */
        @Test
        void factoryDoesNotAddWatchdogProcessorByDefault() throws Exception {
            PlatformTracingJmxRegistrar registrar = new PlatformTracingJmxRegistrar();
            PlatformSpanProcessorFactory factory = new PlatformSpanProcessorFactory(registrar);

            // Default ExtensionConfig (no watchdog.enabled property) + scrubbing disabled for test safety.
            ExtensionConfig defaultConfig = new ExtensionConfig(new ConfigProperties() {
                @Override public String getString(String name) { return null; }
                @Override public Boolean getBoolean(String name) {
                    // Scrubbing must be off so collectScrubbingRules is never called.
                    if ("platform.tracing.scrubbing.enabled".equals(name)) return false;
                    return null;
                }
                @Override public Integer getInt(String name) { return null; }
                @Override public Long getLong(String name) { return null; }
                @Override public Double getDouble(String name) { return null; }
                @Override public Duration getDuration(String name) { return null; }
                @Override public List<String> getList(String name) { return null; }
                @Override public Map<String, String> getMap(String name) { return null; }
            });

            factory.registerSpanProcessors(
                    SdkTracerProvider.builder(),
                    defaultConfig,
                    scrubbingDisabledConfig());

            assertThat(getWatchdog(registrar))
                    .as("watchdog must not be registered with default config (SP-01 default-off)")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("Parity: ExtensionConfig values match old direct-read behavior")
    class DefaultParity {

        @Test
        @DisplayName("Default ExtensionConfig has same effective defaults as old ExtensionDefaults")
        void default_watchdog_timeouts_match_extension_defaults() {
            ExtensionConfig cfg = new ExtensionConfig(new ConfigProperties() {
                @Override public String getString(String n) { return null; }
                @Override public Boolean getBoolean(String n) { return null; }
                @Override public Integer getInt(String n) { return null; }
                @Override public Long getLong(String n) { return null; }
                @Override public Double getDouble(String n) { return null; }
                @Override public Duration getDuration(String n) { return null; }
                @Override public List<String> getList(String n) { return null; }
                @Override public Map<String, String> getMap(String n) { return null; }
            });

            assertThat(cfg.watchdog().spanTimeout())
                    .as("watchdog spanTimeout default")
                    .isEqualTo(Duration.ofSeconds(30));
            assertThat(cfg.watchdog().traceTimeout())
                    .as("watchdog traceTimeout default")
                    .isEqualTo(Duration.ofSeconds(60));
            assertThat(cfg.watchdog().scanInterval())
                    .as("watchdog scanInterval default")
                    .isEqualTo(Duration.ofSeconds(5));
            assertThat(cfg.classification().slowThreshold())
                    .as("classification slowThreshold default")
                    .isEqualTo(Duration.ofSeconds(5));
            assertThat(cfg.classification().normalThreshold())
                    .as("classification normalThreshold default")
                    .isEqualTo(Duration.ofSeconds(1));
        }

        @Test
        @DisplayName("Enriching remoteServicePriority default matches old ExtensionDefaults")
        void default_enriching_priority_matches_extension_defaults() {
            ExtensionConfig cfg = new ExtensionConfig(new ConfigProperties() {
                @Override public String getString(String n) { return null; }
                @Override public Boolean getBoolean(String n) { return null; }
                @Override public Integer getInt(String n) { return null; }
                @Override public Long getLong(String n) { return null; }
                @Override public Double getDouble(String n) { return null; }
                @Override public Duration getDuration(String n) { return null; }
                @Override public List<String> getList(String n) { return null; }
                @Override public Map<String, String> getMap(String n) { return null; }
            });

            assertThat(cfg.enriching().remoteServicePriority())
                    .as("enriching.remoteServicePriority default")
                    .containsExactly("peer.service", "rpc.service", "server.address");
        }

        @Test
        @DisplayName("Baggage defaults: disabled, empty allowlist, deny patterns set")
        void default_baggage_matches_extension_defaults() {
            ExtensionConfig cfg = new ExtensionConfig(new ConfigProperties() {
                @Override public String getString(String n) { return null; }
                @Override public Boolean getBoolean(String n) { return null; }
                @Override public Integer getInt(String n) { return null; }
                @Override public Long getLong(String n) { return null; }
                @Override public Double getDouble(String n) { return null; }
                @Override public Duration getDuration(String n) { return null; }
                @Override public List<String> getList(String n) { return null; }
                @Override public Map<String, String> getMap(String n) { return null; }
            });

            assertThat(cfg.baggage().enabled()).as("baggage.enabled default").isFalse();
            assertThat(cfg.baggage().allowlistKeys()).as("baggage.allowlistKeys default").isEmpty();
            assertThat(cfg.baggage().denyPatterns())
                    .as("baggage.denyPatterns default")
                    .containsExactly("password", "secret", "token");
        }
    }
}
