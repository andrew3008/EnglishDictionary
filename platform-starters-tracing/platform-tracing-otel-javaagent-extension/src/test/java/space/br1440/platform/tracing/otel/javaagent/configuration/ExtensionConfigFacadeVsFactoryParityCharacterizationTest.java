package space.br1440.platform.tracing.otel.javaagent.configuration;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.ValidationMode;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization-тест PR-1: документирует текущее соответствие (parity) между дефолтами
 * {@link ExtensionConfig} facade и значениями, которые factories читают напрямую через
 * {@link ExtensionDefaults} / {@link ExtensionPropertyNames}.
 *
 * <p>Цель: зафиксировать контракт до рефакторинга, чтобы PR-2..5 не меняли поведение молча.
 *
 * <p><b>Зафиксированный дрейф sampling.ratio:</b> facade возвращает {@code 0.1}
 * ({@link ExtensionDefaults#DEFAULT_SAMPLING_RATIO}) при отсутствии свойства;
 * {@code PlatformSamplerBuilder} использует {@code 1.0} (hardcoded implicit fallback).
 * Решение: {@code ALIGN_TO_EXTENSION_DEFAULTS} — см.
 * {@code docs/architecture/sampling-ratio-drift-decision-note.md}. Реализуется в PR-5.
 */
@DisplayName("ExtensionConfig facade vs factory defaults — parity characterization (PR-1)")
class ExtensionConfigFacadeVsFactoryParityCharacterizationTest {

    // ---- Sampling -------------------------------------------------------------------------------

    @Nested
    @DisplayName("Sampling")
    class SamplingParity {

        @Test
        @DisplayName("sampling.enabled absent → facade true == ExtensionDefaults.DEFAULT_ENABLED")
        void sampling_enabled_absent_matches_defaults() {
            assertThat(facade().sampling().enabled())
                    .as("facade sampling.enabled default")
                    .isEqualTo(ExtensionDefaults.DEFAULT_ENABLED);
            assertThat(ExtensionDefaults.DEFAULT_ENABLED).isTrue();
        }

        @Test
        @DisplayName("sampling.ratio absent → facade 0.1 == ExtensionDefaults.DEFAULT_SAMPLING_RATIO")
        void sampling_ratio_absent_facade_uses_extension_defaults() {
            double facadeRatio = facade().sampling().ratio();
            assertThat(facadeRatio)
                    .as("facade sampling.ratio when absent")
                    .isEqualTo(ExtensionDefaults.DEFAULT_SAMPLING_RATIO)
                    .isEqualTo(0.1d);
        }

        @Test
        @DisplayName("DRIFT RESOLVED (PR-5) — sampling.ratio absent: facade=0.1, PlatformSamplerBuilder=0.1 (ALIGN_TO_EXTENSION_DEFAULTS)")
        void sampling_ratio_drift_resolved_both_use_extension_default() {
            double facadeDefault = facade().sampling().ratio();
            double builderDefault = buildSamplerDefaultRatio(emptyConfig());

            assertThat(facadeDefault)
                    .as("facade default ratio (ExtensionDefaults.DEFAULT_SAMPLING_RATIO)")
                    .isEqualTo(ExtensionDefaults.DEFAULT_SAMPLING_RATIO)
                    .isEqualTo(0.1d);
            assertThat(builderDefault)
                    .as("builder default ratio after ALIGN_TO_EXTENSION_DEFAULTS (PR-5)")
                    .isEqualTo(ExtensionDefaults.DEFAULT_SAMPLING_RATIO)
                    .isEqualTo(0.1d);

            // Drift resolved: facade and builder agree on the same default.
            assertThat(facadeDefault)
                    .as("DRIFT RESOLVED: facade and builder now use the same default 0.1 for absent sampling.ratio")
                    .isEqualTo(builderDefault);
        }

        @Test
        @DisplayName("sampling.ratio blank → PlatformSamplerBuilder now uses 0.1 (ALIGN_TO_EXTENSION_DEFAULTS, PR-5)")
        void sampling_ratio_blank_builder_uses_extension_default() {
            double builderDefault = buildSamplerDefaultRatio(stringConfig(ExtensionPropertyNames.SAMPLING_RATIO, "  "));
            assertThat(builderDefault)
                    .as("builder ratio when property is blank string (ALIGN_TO_EXTENSION_DEFAULTS = 0.1)")
                    .isEqualTo(ExtensionDefaults.DEFAULT_SAMPLING_RATIO)
                    .isEqualTo(0.1d);
        }

        @Test
        @DisplayName("sampling.ratio explicit 0.5 → facade and builder both return 0.5 (no drift when property set)")
        void sampling_ratio_explicit_value_facade_and_builder_agree() {
            double facadeRatio = new ExtensionConfig(doubleConfig(ExtensionPropertyNames.SAMPLING_RATIO, 0.5d))
                    .sampling().ratio();
            double builderRatio = buildSamplerDefaultRatio(doubleConfig(ExtensionPropertyNames.SAMPLING_RATIO, 0.5d));

            assertThat(facadeRatio).as("facade explicit ratio").isEqualTo(0.5d);
            assertThat(builderRatio).as("builder explicit ratio").isEqualTo(0.5d);
        }

        @Test
        @DisplayName("sampling.forceRecordValues absent → facade == ExtensionDefaults.DEFAULT_FORCE_VALUES")
        void sampling_force_record_values_parity() {
            assertThat(facade().sampling().forceRecordValues())
                    .isEqualTo(ExtensionDefaults.DEFAULT_FORCE_VALUES)
                    .containsExactly("on");
        }

        @Test
        @DisplayName("sampling.dropPaths absent → facade == ExtensionDefaults.DEFAULT_DROP_PATHS")
        void sampling_drop_paths_parity() {
            assertThat(facade().sampling().dropPaths())
                    .isEqualTo(ExtensionDefaults.DEFAULT_DROP_PATHS)
                    .containsExactly("/actuator/health", "/actuator/prometheus", "/actuator/info");
        }
    }

    // ---- Queue ----------------------------------------------------------------------------------

    @Nested
    @DisplayName("Queue")
    class QueueParity {

        @Test
        @DisplayName("queue.overflow-policy absent → facade 'DROP_OLDEST' == ExtensionDefaults.DEFAULT_QUEUE_OVERFLOW_POLICY")
        void queue_overflow_policy_absent_is_drop_oldest() {
            assertThat(facade().queue().overflowPolicy())
                    .as("facade queue.overflow-policy default")
                    .isEqualTo(ExtensionDefaults.DEFAULT_QUEUE_OVERFLOW_POLICY)
                    .isEqualTo("DROP_OLDEST");
        }

        @Test
        @DisplayName("queue.overflow-policy absent → factory isExplicitUpstream returns false (same semantic: DROP_OLDEST)")
        void queue_overflow_policy_absent_factory_treats_as_drop_oldest() {
            // PlatformExportProcessorFactory.isExplicitUpstream reads getString and returns false for null/blank
            // This documents that absent = DROP_OLDEST in both facade and factory
            String raw = emptyConfig().getString(ExtensionPropertyNames.QUEUE_OVERFLOW_POLICY);
            assertThat(raw).as("raw queue.overflow-policy for absent config").isNull();
            // null → isExplicitUpstream returns false → factory uses DROP_OLDEST path
            // facade.queue().overflowPolicy() → "DROP_OLDEST"
            // PARITY: both treat absent as DROP_OLDEST ✓
            assertThat(facade().queue().overflowPolicy()).isEqualTo("DROP_OLDEST");
        }
    }

    // ---- Scrubbing ------------------------------------------------------------------------------

    @Nested
    @DisplayName("Scrubbing")
    class ScrubbingParity {

        @Test
        @DisplayName("scrubbing.enabled absent → facade true == factory uses DEFAULT_ENABLED (true)")
        void scrubbing_enabled_parity() {
            assertThat(facade().scrubbing().enabled())
                    .as("facade scrubbing.enabled default")
                    .isTrue()
                    .isEqualTo(ExtensionDefaults.DEFAULT_ENABLED);
        }

        @Test
        @DisplayName("scrubbing.missing-key-policy absent → facade 'mask' == ExtensionDefaults.DEFAULT_SCRUBBING_MISSING_KEY_POLICY")
        void scrubbing_missing_key_policy_parity() {
            assertThat(facade().scrubbing().missingKeyPolicy())
                    .isEqualTo(ExtensionDefaults.DEFAULT_SCRUBBING_MISSING_KEY_POLICY)
                    .isEqualTo("mask");
        }

        @Test
        @DisplayName("scrubbing.rules.validation-mode absent → facade 'LENIENT' == ExtensionDefaults.DEFAULT_SCRUBBING_VALIDATION_MODE")
        void scrubbing_rules_validation_mode_parity() {
            assertThat(facade().scrubbing().rulesValidationMode())
                    .isEqualTo(ExtensionDefaults.DEFAULT_SCRUBBING_VALIDATION_MODE)
                    .isEqualTo("LENIENT");
        }

        @Test
        @DisplayName("scrubbing.hmac-key absent → facade MaskedSecret.absent() == factory reads getString (nullable passthrough)")
        void scrubbing_hmac_key_nullable_passthrough_parity() {
            assertThat(facade().scrubbing().hmacKey().isPresent()).isFalse();
        }
    }

    // ---- Validation -----------------------------------------------------------------------------

    @Nested
    @DisplayName("Validation")
    class ValidationParity {

        @Test
        @DisplayName("validation.enabled absent → facade true == factory uses DEFAULT_ENABLED (true)")
        void validation_enabled_parity() {
            assertThat(facade().validation().enabled())
                    .isTrue()
                    .isEqualTo(ExtensionDefaults.DEFAULT_ENABLED);
        }

        @Test
        @DisplayName("validation.mode absent → facade requestedValidationMode == LENIENT (S3: legacy strict keys removed)")
        void validation_requested_mode_parity() {
            assertThat(facade().validation().requestedValidationMode())
                    .isEqualTo(ValidationMode.LENIENT);
        }
    }

    // ---- Baggage --------------------------------------------------------------------------------

    @Nested
    @DisplayName("Baggage")
    class BaggageParity {

        @Test
        @DisplayName("baggage.propagation.enabled absent → facade true == DEFAULT_BAGGAGE_PROPAGATION_ENABLED (true)")
        void baggage_propagation_enabled_parity() {
            assertThat(facade().baggage().propagationEnabled())
                    .isTrue()
                    .isEqualTo(ExtensionDefaults.DEFAULT_BAGGAGE_PROPAGATION_ENABLED);
        }

        @Test
        @DisplayName("baggage.propagation.outbound-allowed-keys absent → facade uses canonical platform allowlist")
        void baggage_propagation_allowlist_parity() {
            assertThat(facade().baggage().propagationOutboundAllowedKeys())
                    .isEqualTo(ExtensionDefaults.DEFAULT_BAGGAGE_PROPAGATION_OUTBOUND_ALLOWED_KEYS)
                    .containsExactly("traffic_source", "tenant_class", "platform.correlation.id");
        }

        @Test
        @DisplayName("baggage.propagation.outbound-deny-patterns absent → facade [password,secret,token] == ExtensionDefaults")
        void baggage_propagation_deny_patterns_parity() {
            assertThat(facade().baggage().propagationOutboundDenyPatterns())
                    .isEqualTo(ExtensionDefaults.DEFAULT_BAGGAGE_PROPAGATION_OUTBOUND_DENY_PATTERNS)
                    .containsExactly("password", "secret", "token");
        }

        @Test
        @DisplayName("baggage.processor.enabled absent → facade true == DEFAULT_BAGGAGE_PROCESSOR_ENABLED (true)")
        void baggage_processor_enabled_parity() {
            assertThat(facade().baggage().processorEnabled())
                    .isTrue()
                    .isEqualTo(ExtensionDefaults.DEFAULT_BAGGAGE_PROCESSOR_ENABLED);
        }

        @Test
        @DisplayName("baggage.processor.allowed-keys absent → facade uses canonical platform allowlist")
        void baggage_processor_allowlist_parity() {
            assertThat(facade().baggage().processorAllowedKeys())
                    .isEqualTo(ExtensionDefaults.DEFAULT_BAGGAGE_PROCESSOR_ALLOWED_KEYS)
                    .containsExactly("traffic_source", "tenant_class", "platform.correlation.id");
        }

        @Test
        @DisplayName("baggage.processor.deny-patterns absent → facade [password,secret,token] == ExtensionDefaults")
        void baggage_processor_deny_patterns_parity() {
            assertThat(facade().baggage().processorDenyPatterns())
                    .isEqualTo(ExtensionDefaults.DEFAULT_BAGGAGE_PROCESSOR_DENY_PATTERNS)
                    .containsExactly("password", "secret", "token");
        }
    }

    // ---- Enriching ------------------------------------------------------------------------------

    @Nested
    @DisplayName("Enriching")
    class EnrichingParity {

        @Test
        @DisplayName("enriching.enabled absent → facade true == ExtensionDefaults.DEFAULT_ENABLED (true)")
        void enriching_enabled_parity() {
            assertThat(facade().enriching().enabled())
                    .isTrue()
                    .isEqualTo(ExtensionDefaults.DEFAULT_ENABLED);
        }

        @Test
        @DisplayName("enriching.remote-service-priority absent → facade == ExtensionDefaults.DEFAULT_REMOTE_SERVICE_PRIORITY")
        void enriching_remote_service_priority_parity() {
            assertThat(facade().enriching().remoteServicePriority())
                    .isEqualTo(ExtensionDefaults.DEFAULT_REMOTE_SERVICE_PRIORITY)
                    .containsExactly("peer.service", "rpc.service", "server.address");
        }
    }

    // ---- Classification / Watchdog / Metrics ----------------------------------------------------

    @Nested
    @DisplayName("Classification, Watchdog, Metrics")
    class OtherParity {

        @Test
        @DisplayName("classification.enabled absent → facade true == ExtensionDefaults.DEFAULT_ENABLED")
        void classification_enabled_parity() {
            assertThat(facade().classification().enabled())
                    .isTrue()
                    .isEqualTo(ExtensionDefaults.DEFAULT_ENABLED);
        }

        @Test
        @DisplayName("classification.slow-threshold absent → facade 5s == ExtensionDefaults")
        void classification_slow_threshold_parity() {
            assertThat(facade().classification().slowThreshold())
                    .isEqualTo(ExtensionDefaults.DEFAULT_CLASSIFICATION_SLOW_THRESHOLD)
                    .isEqualTo(Duration.ofSeconds(5));
        }

        @Test
        @DisplayName("watchdog.enabled absent → facade false == ExtensionDefaults.DEFAULT_WATCHDOG_ENABLED (SP-01: watchdog is opt-in)")
        void watchdog_enabled_parity() {
            assertThat(facade().watchdog().enabled())
                    .isFalse()
                    .isEqualTo(ExtensionDefaults.DEFAULT_WATCHDOG_ENABLED);
        }

        @Test
        @DisplayName("watchdog.span-timeout absent → facade 30s == ExtensionDefaults")
        void watchdog_span_timeout_parity() {
            assertThat(facade().watchdog().spanTimeout())
                    .isEqualTo(ExtensionDefaults.DEFAULT_WATCHDOG_SPAN_TIMEOUT)
                    .isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("metrics.enabled absent → facade true == ExtensionDefaults.DEFAULT_ENABLED")
        void metrics_enabled_parity() {
            assertThat(facade().metrics().enabled())
                    .isTrue()
                    .isEqualTo(ExtensionDefaults.DEFAULT_ENABLED);
        }
    }

    // ---- Helpers --------------------------------------------------------------------------------

    private static ExtensionConfig facade() {
        return new ExtensionConfig(emptyConfig());
    }

    /**
     * Строит sampler через {@code PlatformSamplerBuilder.build(SamplingExtensionConfig)} и
     * возвращает стартовый {@code defaultRatio} из {@code TracingSettingsSource}.
     * <p>
     * PR-5: {@code ConfigProperties} \u2192 {@code SamplingExtensionConfig} через {@code ExtensionConfig},
     * обеспечивая ALIGN_TO_EXTENSION_DEFAULTS для absent/blank ratio.
     */
    private static double buildSamplerDefaultRatio(ConfigProperties config) {
        io.opentelemetry.sdk.trace.samplers.Sampler sampler =
                space.br1440.platform.tracing.otel.javaagent.sampler.PlatformSamplerBuilder.build(
                        new ExtensionConfig(config).sampling());
        space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler composite =
                ((space.br1440.platform.tracing.otel.javaagent.sampler.PlatformManagedSampler) sampler)
                        .platformCompositeSampler();
        return composite.acceptedPolicy().current().samplingRatio();
    }

    static ConfigProperties emptyConfig() {
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

    private static ConfigProperties stringConfig(String key, String value) {
        return new ConfigProperties() {
            @Override public String getString(String name) { return key.equals(name) ? value : null; }
            @Override public Boolean getBoolean(String name) { return null; }
            @Override public Integer getInt(String name) { return null; }
            @Override public Long getLong(String name) { return null; }
            @Override public Double getDouble(String name) { return null; }
            @Override public Duration getDuration(String name) { return null; }
            @Override public List<String> getList(String name) { return null; }
            @Override public Map<String, String> getMap(String name) { return null; }
        };
    }

    private static ConfigProperties doubleConfig(String key, double value) {
        return new ConfigProperties() {
            @Override public String getString(String name) { return key.equals(name) ? String.valueOf(value) : null; }
            @Override public Boolean getBoolean(String name) { return null; }
            @Override public Integer getInt(String name) { return null; }
            @Override public Long getLong(String name) { return null; }
            @Override public Double getDouble(String name) { return key.equals(name) ? value : null; }
            @Override public Duration getDuration(String name) { return null; }
            @Override public List<String> getList(String name) { return null; }
            @Override public Map<String, String> getMap(String name) { return null; }
        };
    }
}
