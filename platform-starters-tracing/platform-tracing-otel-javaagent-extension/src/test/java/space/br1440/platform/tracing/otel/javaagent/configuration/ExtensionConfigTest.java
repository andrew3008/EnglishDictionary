package space.br1440.platform.tracing.otel.javaagent.configuration;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtensionConfigTest {

    @Test
    void constructor_rejects_null_config() {
        // when/then
        assertThatNullPointerException().isThrownBy(() -> new ExtensionConfig(null));
    }

    @Nested
    @DisplayName("Accessors return cached final-field instances")
    class CachedInstances {

        @Test
        void sampling_returns_same_instance() {
            // given
            ExtensionConfig cfg = new ExtensionConfig(emptyConfig());

            // when/then
            assertThat(cfg.sampling()).isSameAs(cfg.sampling());
        }

        @Test
        void enriching_returns_same_instance() {
            // given
            ExtensionConfig cfg = new ExtensionConfig(emptyConfig());

            // when/then
            assertThat(cfg.enriching()).isSameAs(cfg.enriching());
        }

        @Test
        void scrubbing_returns_same_instance() {
            // given
            ExtensionConfig cfg = new ExtensionConfig(emptyConfig());

            // when/then
            assertThat(cfg.scrubbing()).isSameAs(cfg.scrubbing());
        }

        @Test
        void metrics_returns_same_instance() {
            // given
            ExtensionConfig cfg = new ExtensionConfig(emptyConfig());

            // when/then
            assertThat(cfg.metrics()).isSameAs(cfg.metrics());
        }

        @Test
        void validation_returns_same_instance() {
            // given
            ExtensionConfig cfg = new ExtensionConfig(emptyConfig());

            // when/then
            assertThat(cfg.validation()).isSameAs(cfg.validation());
        }

        @Test
        void classification_returns_same_instance() {
            // given
            ExtensionConfig cfg = new ExtensionConfig(emptyConfig());

            // when/then
            assertThat(cfg.classification()).isSameAs(cfg.classification());
        }

        @Test
        void watchdog_returns_same_instance() {
            // given
            ExtensionConfig cfg = new ExtensionConfig(emptyConfig());

            // when/then
            assertThat(cfg.watchdog()).isSameAs(cfg.watchdog());
        }

        @Test
        void queue_returns_same_instance() {
            // given
            ExtensionConfig cfg = new ExtensionConfig(emptyConfig());

            // when/then
            assertThat(cfg.queue()).isSameAs(cfg.queue());
        }

        @Test
        void baggage_returns_same_instance() {
            // given
            ExtensionConfig cfg = new ExtensionConfig(emptyConfig());

            // when/then
            assertThat(cfg.baggage()).isSameAs(cfg.baggage());
        }
    }

    @Test
    void sampling_returns_defaults_when_config_is_empty() {
        // given
        ExtensionConfig config = new ExtensionConfig(emptyConfig());

        // then
        assertThat(config.sampling().enabled()).isTrue();
        assertThat(config.sampling().ratio()).isEqualTo(0.1d);
        assertThat(config.sampling().forceRecordValues()).containsExactly("on");
        assertThat(config.sampling().dropPaths())
                .containsExactly("/actuator/health", "/actuator/prometheus", "/actuator/info");
        assertThat(config.sampling().routeRatios()).isEmpty();
    }

    @Test
    void enriching_returns_defaults_when_config_is_empty() {
        // given
        ExtensionConfig config = new ExtensionConfig(emptyConfig());

        // then
        assertThat(config.enriching().enabled()).isTrue();
        assertThat(config.enriching().remoteServicePriority())
                .containsExactly("peer.service", "rpc.service", "server.address");
    }

    @Test
    void scrubbing_returns_defaults_when_config_is_empty() {
        // given
        ExtensionConfig config = new ExtensionConfig(emptyConfig());

        // then
        assertThat(config.scrubbing().enabled()).isTrue();
        assertThat(config.scrubbing().hmacKey().isPresent()).isFalse();
        assertThat(config.scrubbing().missingKeyPolicy()).isEqualTo("mask");
        assertThat(config.scrubbing().rulesConfig()).isNull();
        assertThat(config.scrubbing().rulesExtensions()).isNull();
        assertThat(config.scrubbing().rulesValidationMode()).isEqualTo("LENIENT");
    }

    @Test
    void validation_returns_defaults_when_config_is_empty() {
        // given
        ExtensionConfig config = new ExtensionConfig(emptyConfig());

        // then
        assertThat(config.validation().enabled()).isTrue();
        assertThat(config.validation().requestedLevel()).isEqualTo(SpanAttributeValidationLevel.WARN);
    }

    @Test
    void validation_reads_enabled_false() {
        // given/when
        ExtensionConfig config = new ExtensionConfig(booleanConfig(
                ExtensionPropertyNames.SPAN_ATTRIBUTES_VALIDATION_ENABLED,
                false
        ));

        // then
        assertThat(config.validation().enabled()).isFalse();
        assertThat(config.validation().requestedLevel()).isEqualTo(SpanAttributeValidationLevel.WARN);
    }

    @Test
    void validation_rejects_unknown_level() {
        // given/when/then
        assertThatThrownBy(() -> new ExtensionConfig(stringConfig(
                ExtensionPropertyNames.SPAN_ATTRIBUTES_VALIDATION_LEVEL,
                "STRICT"
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("platform.tracing.javaagent.span-attributes.validation.level");
    }

    @Test
    void classification_returns_defaults_when_config_is_empty() {
        // given
        ExtensionConfig config = new ExtensionConfig(emptyConfig());

        // then
        assertThat(config.classification().enabled()).isTrue();
        assertThat(config.classification().slowThreshold()).isEqualTo(Duration.ofSeconds(5));
        assertThat(config.classification().normalThreshold()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void watchdog_returns_defaults_when_config_is_empty() {
        // given
        ExtensionConfig config = new ExtensionConfig(emptyConfig());

        // then
        assertThat(config.watchdog().enabled()).isFalse();
        assertThat(config.watchdog().spanTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.watchdog().traceTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(config.watchdog().scanInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(config.watchdog().maxTrackedTraces()).isEqualTo(10_000);
        assertThat(config.watchdog().maxTrackedSpansPerTrace()).isEqualTo(1_024);
        assertThat(config.watchdog().maxTrackedSpans()).isEqualTo(60_000);
    }

    @Test
    void watchdogIsDisabledByDefault() {
        // given
        ExtensionConfig config = new ExtensionConfig(emptyConfig());

        // then
        assertThat(config.watchdog().enabled()).isFalse();
    }

    @Test
    void watchdogCanBeExplicitlyEnabled() {
        // given
        ExtensionConfig config = new ExtensionConfig(
                booleanConfig(ExtensionPropertyNames.WATCHDOG_ENABLED, true));

        // then
        assertThat(config.watchdog().enabled()).isTrue();
    }

    @Test
    void watchdogCanBeExplicitlyDisabled() {
        // given
        ExtensionConfig config = new ExtensionConfig(
                booleanConfig(ExtensionPropertyNames.WATCHDOG_ENABLED, false));

        // then
        assertThat(config.watchdog().enabled()).isFalse();
    }

    @Test
    void queue_returns_default_overflow_policy_when_config_is_empty() {
        // given
        ExtensionConfig config = new ExtensionConfig(emptyConfig());

        // then
        assertThat(config.queue().overflowPolicy()).isEqualTo("DROP_OLDEST");
    }

    @Test
    void baggage_returns_defaults_when_config_is_empty() {
        // given
        ExtensionConfig config = new ExtensionConfig(emptyConfig());

        // then
        assertThat(config.baggage().propagationEnabled()).isTrue();
        assertThat(config.baggage().propagationOutboundAllowedKeys())
                .containsExactly("traffic_source", "tenant_class", "platform.correlation.id");
        assertThat(config.baggage().propagationOutboundDenyPatterns()).containsExactly("password", "secret", "token");

        assertThat(config.baggage().processorEnabled()).isTrue();
        assertThat(config.baggage().processorAllowedKeys())
                .containsExactly("traffic_source", "tenant_class", "platform.correlation.id");
        assertThat(config.baggage().processorDenyPatterns()).containsExactly("password", "secret", "token");
    }

    @Test
    void sampling_reads_explicit_ratio() {
        // given
        ExtensionConfig config = new ExtensionConfig(doubleConfig(ExtensionPropertyNames.SAMPLING_RATIO, 0.5d));

        // then
        assertThat(config.sampling().ratio()).isEqualTo(0.5d);
    }

    @Test
    void sampling_reads_explicit_enabled_false() {
        // given
        ExtensionConfig config = new ExtensionConfig(booleanConfig(ExtensionPropertyNames.SAMPLING_ENABLED, false));

        // then
        assertThat(config.sampling().enabled()).isFalse();
    }

    @Test
    void watchdog_reads_explicit_span_timeout() {
        // given
        ExtensionConfig config = new ExtensionConfig(
                durationConfig(ExtensionPropertyNames.WATCHDOG_SPAN_TIMEOUT, Duration.ofSeconds(10)));

        // then
        assertThat(config.watchdog().spanTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void watchdog_rejects_non_positive_scan_interval() {
        // when/then
        assertThatThrownBy(() -> new ExtensionConfig(
                durationConfig(ExtensionPropertyNames.WATCHDOG_SCAN_INTERVAL, Duration.ZERO)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("platform.tracing.watchdog.scan-interval must be greater than zero");
    }

    @Test
    void watchdog_rejects_non_positive_span_timeout() {
        // given
        ConfigProperties config = durationConfig(ExtensionPropertyNames.WATCHDOG_SPAN_TIMEOUT, Duration.ZERO);

        // when / then
        assertThatThrownBy(() -> new ExtensionConfig(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("platform.tracing.watchdog.span-timeout must be greater than zero");
    }

    @Test
    void watchdog_rejects_zero_trace_timeout() {
        // given
        ConfigProperties config = durationConfig(ExtensionPropertyNames.WATCHDOG_TRACE_TIMEOUT, Duration.ZERO);

        // when / then
        assertThatThrownBy(() -> new ExtensionConfig(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("platform.tracing.watchdog.trace-timeout must be greater than zero");
    }

    @Test
    void watchdog_rejects_negative_trace_timeout() {
        // given
        ConfigProperties config = durationConfig(
                ExtensionPropertyNames.WATCHDOG_TRACE_TIMEOUT, Duration.ofSeconds(-1));

        // when / then
        assertThatThrownBy(() -> new ExtensionConfig(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("platform.tracing.watchdog.trace-timeout must be greater than zero");
    }

    @Test
    void watchdog_rejects_zero_max_tracked_traces() {
        // given
        ConfigProperties config = intConfig(ExtensionPropertyNames.WATCHDOG_MAX_TRACKED_TRACES, 0);

        // when / then
        assertThatThrownBy(() -> new ExtensionConfig(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("platform.tracing.watchdog.max-tracked-traces must be greater than zero");
    }

    @Test
    void watchdog_rejects_negative_max_tracked_traces() {
        // given
        ConfigProperties config = intConfig(ExtensionPropertyNames.WATCHDOG_MAX_TRACKED_TRACES, -1);

        // when / then
        assertThatThrownBy(() -> new ExtensionConfig(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("platform.tracing.watchdog.max-tracked-traces must be greater than zero");
    }

    @Test
    void watchdog_rejects_zero_max_tracked_spans_per_trace() {
        // given
        ConfigProperties config = intConfig(ExtensionPropertyNames.WATCHDOG_MAX_TRACKED_SPANS_PER_TRACE, 0);

        // when / then
        assertThatThrownBy(() -> new ExtensionConfig(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("platform.tracing.watchdog.max-tracked-spans-per-trace must be greater than zero");
    }

    @Test
    void watchdog_rejects_negative_max_tracked_spans() {
        // given
        ConfigProperties config = intConfig(ExtensionPropertyNames.WATCHDOG_MAX_TRACKED_SPANS, -1);

        // when / then
        assertThatThrownBy(() -> new ExtensionConfig(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("platform.tracing.watchdog.max-tracked-spans must be greater than zero");
    }

    @Test
    void watchdog_allows_global_span_budget_below_per_trace_budget() {
        // given
        ConfigProperties config = intsConfig(Map.of(
                ExtensionPropertyNames.WATCHDOG_MAX_TRACKED_SPANS_PER_TRACE, 1_024,
                ExtensionPropertyNames.WATCHDOG_MAX_TRACKED_SPANS, 500));

        // when
        ExtensionConfig parsed = new ExtensionConfig(config);

        // then
        assertThat(parsed.watchdog().maxTrackedSpansPerTrace()).isEqualTo(1_024);
        assertThat(parsed.watchdog().maxTrackedSpans()).isEqualTo(500);
    }

    @Test
    void watchdog_rejects_trace_timeout_shorter_than_span_timeout() {
        // when/then
        assertThatThrownBy(() -> new ExtensionConfig(durationsConfig(Map.of(
                ExtensionPropertyNames.WATCHDOG_SPAN_TIMEOUT, Duration.ofSeconds(10),
                ExtensionPropertyNames.WATCHDOG_TRACE_TIMEOUT, Duration.ofSeconds(5)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("platform.tracing.watchdog.trace-timeout must be greater than or equal to "
                        + "platform.tracing.watchdog.span-timeout");
    }

    @Test
    void classification_rejects_non_positive_threshold() {
        // when/then
        assertThatThrownBy(() -> new ExtensionConfig(
                durationConfig(ExtensionPropertyNames.CLASSIFICATION_NORMAL_THRESHOLD, Duration.ofSeconds(-1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("platform.tracing.classification.normal-threshold must be greater than zero");
    }

    @Test
    void classification_rejects_slow_threshold_below_normal_threshold() {
        // when/then
        assertThatThrownBy(() -> new ExtensionConfig(durationsConfig(Map.of(
                ExtensionPropertyNames.CLASSIFICATION_SLOW_THRESHOLD, Duration.ofSeconds(1),
                ExtensionPropertyNames.CLASSIFICATION_NORMAL_THRESHOLD, Duration.ofSeconds(2)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("platform.tracing.classification.slow-threshold must be greater than or equal to "
                        + "platform.tracing.classification.normal-threshold");
    }

    @Test
    void queue_reads_explicit_upstream_policy() {
        // given
        ExtensionConfig config = new ExtensionConfig(
                stringConfig(ExtensionPropertyNames.QUEUE_OVERFLOW_POLICY, "UPSTREAM"));

        // then
        assertThat(config.queue().overflowPolicy()).isEqualTo("UPSTREAM");
    }

    @Test
    void baggage_reads_explicit_propagation_allowlist() {
        // given
        ExtensionConfig config = new ExtensionConfig(listConfig(
                ExtensionPropertyNames.BAGGAGE_PROPAGATION_OUTBOUND_ALLOWED_KEYS,
                List.of("x-tenant-id", "x-request-id")));

        // then
        assertThat(config.baggage().propagationOutboundAllowedKeys())
                .containsExactly("x-tenant-id", "x-request-id");
    }

    @Test
    void baggage_reads_explicit_processor_allowlist() {
        // given
        ExtensionConfig config = new ExtensionConfig(listConfig(
                ExtensionPropertyNames.BAGGAGE_PROCESSOR_ALLOWED_KEYS,
                List.of("x-tenant-id", "x-request-id")));

        // then
        assertThat(config.baggage().processorAllowedKeys())
                .containsExactly("x-tenant-id", "x-request-id");
    }

    @Test
    void scrubbing_reads_explicit_hmac_key() {
        // given
        ExtensionConfig config = new ExtensionConfig(
                stringConfig(ExtensionPropertyNames.SCRUBBING_HMAC_KEY, "secret-key-value"));

        // when
        MaskedSecret hmacKey = config.scrubbing().hmacKey();

        // then
        assertThat(hmacKey.isPresent()).isTrue();
        assertThat(new String(hmacKey.copyBytes(), StandardCharsets.UTF_8))
                .isEqualTo("secret-key-value");
        assertThat(hmacKey.toString())
                .as("toString must never leak the raw secret value")
                .doesNotContain("secret-key-value");
    }

    @Nested
    @DisplayName("Collections returned from domain configs are unmodifiable")
    class CollectionImmutability {

        @Test
        void sampling_forceRecordValues_is_unmodifiable_when_provided() {
            // given
            ExtensionConfig cfg = new ExtensionConfig(
                    listConfig(ExtensionPropertyNames.SAMPLING_FORCE_RECORD_VALUES, List.of("on", "yes")));

            // when
            List<String> result = cfg.sampling().forceRecordValues();

            // then
            assertThatThrownBy(() -> result.add("extra"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void sampling_dropPaths_is_unmodifiable_when_provided() {
            // given
            ExtensionConfig cfg = new ExtensionConfig(
                    listConfig(ExtensionPropertyNames.SAMPLING_DROP_PATHS, List.of("/health")));

            // when
            List<String> result = cfg.sampling().dropPaths();

            // then
            assertThatThrownBy(() -> result.add("/extra"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void baggage_allowlistKeys_is_unmodifiable_when_provided() {
            // given
            ExtensionConfig cfg = new ExtensionConfig(listConfig(
                    ExtensionPropertyNames.BAGGAGE_PROPAGATION_OUTBOUND_ALLOWED_KEYS, List.of("x-tenant")));

            // when
            List<String> result = cfg.baggage().propagationOutboundAllowedKeys();

            // then
            assertThatThrownBy(() -> result.add("extra"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("Nullable values remain null when not configured")
    class NullablePassthrough {

        @Test
        void scrubbing_hmacKey_null_when_absent() {
            // when/then
            assertThat(new ExtensionConfig(emptyConfig()).scrubbing().hmacKey().isPresent()).isFalse();
        }

        @Test
        void scrubbing_rulesConfig_null_when_absent() {
            // when/then
            assertThat(new ExtensionConfig(emptyConfig()).scrubbing().rulesConfig()).isNull();
        }

        @Test
        void scrubbing_rulesExtensions_null_when_absent() {
            // when/then
            assertThat(new ExtensionConfig(emptyConfig()).scrubbing().rulesExtensions()).isNull();
        }

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

    private static ConfigProperties booleanConfig(String key, boolean value) {
        return new ConfigProperties() {
            @Override public String getString(String name) { return null; }
            @Override public Boolean getBoolean(String name) { return key.equals(name) ? value : null; }
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
            @Override public String getString(String name) { return null; }
            @Override public Boolean getBoolean(String name) { return null; }
            @Override public Integer getInt(String name) { return null; }
            @Override public Long getLong(String name) { return null; }
            @Override public Double getDouble(String name) { return key.equals(name) ? value : null; }
            @Override public Duration getDuration(String name) { return null; }
            @Override public List<String> getList(String name) { return null; }
            @Override public Map<String, String> getMap(String name) { return null; }
        };
    }

    private static ConfigProperties durationConfig(String key, Duration value) {
        return new ConfigProperties() {
            @Override public String getString(String name) { return null; }
            @Override public Boolean getBoolean(String name) { return null; }
            @Override public Integer getInt(String name) { return null; }
            @Override public Long getLong(String name) { return null; }
            @Override public Double getDouble(String name) { return null; }
            @Override public Duration getDuration(String name) { return key.equals(name) ? value : null; }
            @Override public List<String> getList(String name) { return null; }
            @Override public Map<String, String> getMap(String name) { return null; }
        };
    }

    private static ConfigProperties durationsConfig(Map<String, Duration> values) {
        return new ConfigProperties() {
            @Override public String getString(String name) { return null; }
            @Override public Boolean getBoolean(String name) { return null; }
            @Override public Integer getInt(String name) { return null; }
            @Override public Long getLong(String name) { return null; }
            @Override public Double getDouble(String name) { return null; }
            @Override public Duration getDuration(String name) { return values.get(name); }
            @Override public List<String> getList(String name) { return null; }
            @Override public Map<String, String> getMap(String name) { return null; }
        };
    }

    private static ConfigProperties intsConfig(Map<String, Integer> values) {
        return new ConfigProperties() {
            @Override public String getString(String name) { return null; }
            @Override public Boolean getBoolean(String name) { return null; }
            @Override public Integer getInt(String name) { return values.get(name); }
            @Override public Long getLong(String name) { return null; }
            @Override public Double getDouble(String name) { return null; }
            @Override public Duration getDuration(String name) { return null; }
            @Override public List<String> getList(String name) { return null; }
            @Override public Map<String, String> getMap(String name) { return null; }
        };
    }

    private static ConfigProperties intConfig(String key, int value) {
        return new ConfigProperties() {
            @Override public String getString(String name) { return null; }
            @Override public Boolean getBoolean(String name) { return null; }
            @Override public Integer getInt(String name) { return key.equals(name) ? value : null; }
            @Override public Long getLong(String name) { return null; }
            @Override public Double getDouble(String name) { return null; }
            @Override public Duration getDuration(String name) { return null; }
            @Override public List<String> getList(String name) { return null; }
            @Override public Map<String, String> getMap(String name) { return null; }
        };
    }

    private static ConfigProperties listConfig(String key, List<String> value) {
        return new ConfigProperties() {
            @Override public String getString(String name) { return null; }
            @Override public Boolean getBoolean(String name) { return null; }
            @Override public Integer getInt(String name) { return null; }
            @Override public Long getLong(String name) { return null; }
            @Override public Double getDouble(String name) { return null; }
            @Override public Duration getDuration(String name) { return null; }
            @Override public List<String> getList(String name) { return key.equals(name) ? value : null; }
            @Override public Map<String, String> getMap(String name) { return null; }
        };
    }
}
