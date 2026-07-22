package space.br1440.platform.tracing.otel.javaagent.configuration;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtensionConfigTest {

    // -- Construction ----------------------------------------------------------------------------

    @Test
    void constructor_rejects_null_config() {
        assertThatNullPointerException().isThrownBy(() -> new ExtensionConfig(null));
    }

    // -- Cached instances (final fields) ---------------------------------------------------------

    @Nested
    @DisplayName("Accessors return cached final-field instances")
    class CachedInstances {

        @Test
        void sampling_returns_same_instance() {
            ExtensionConfig cfg = new ExtensionConfig(emptyConfig());
            assertThat(cfg.sampling()).isSameAs(cfg.sampling());
        }

        @Test
        void enriching_returns_same_instance() {
            ExtensionConfig cfg = new ExtensionConfig(emptyConfig());
            assertThat(cfg.enriching()).isSameAs(cfg.enriching());
        }

        @Test
        void scrubbing_returns_same_instance() {
            ExtensionConfig cfg = new ExtensionConfig(emptyConfig());
            assertThat(cfg.scrubbing()).isSameAs(cfg.scrubbing());
        }

        @Test
        void metrics_returns_same_instance() {
            ExtensionConfig cfg = new ExtensionConfig(emptyConfig());
            assertThat(cfg.metrics()).isSameAs(cfg.metrics());
        }

        @Test
        void validation_returns_same_instance() {
            ExtensionConfig cfg = new ExtensionConfig(emptyConfig());
            assertThat(cfg.validation()).isSameAs(cfg.validation());
        }

        @Test
        void resource_returns_same_instance() {
            ExtensionConfig cfg = new ExtensionConfig(emptyConfig());
            assertThat(cfg.resource()).isSameAs(cfg.resource());
        }

        @Test
        void classification_returns_same_instance() {
            ExtensionConfig cfg = new ExtensionConfig(emptyConfig());
            assertThat(cfg.classification()).isSameAs(cfg.classification());
        }

        @Test
        void watchdog_returns_same_instance() {
            ExtensionConfig cfg = new ExtensionConfig(emptyConfig());
            assertThat(cfg.watchdog()).isSameAs(cfg.watchdog());
        }

        @Test
        void queue_returns_same_instance() {
            ExtensionConfig cfg = new ExtensionConfig(emptyConfig());
            assertThat(cfg.queue()).isSameAs(cfg.queue());
        }

        @Test
        void baggage_returns_same_instance() {
            ExtensionConfig cfg = new ExtensionConfig(emptyConfig());
            assertThat(cfg.baggage()).isSameAs(cfg.baggage());
        }

        @Test
        void sdk_returns_same_instance() {
            ExtensionConfig cfg = new ExtensionConfig(emptyConfig());
            assertThat(cfg.sdk()).isSameAs(cfg.sdk());
        }

        @Test
        void control_returns_same_instance() {
            ExtensionConfig cfg = new ExtensionConfig(emptyConfig());
            assertThat(cfg.control()).isSameAs(cfg.control());
        }
    }

    // -- Defaults from empty config --------------------------------------------------------------

    @Test
    void sampling_returns_defaults_when_config_is_empty() {
        ExtensionConfig config = new ExtensionConfig(emptyConfig());

        assertThat(config.sampling().enabled()).isTrue();
        assertThat(config.sampling().ratio()).isEqualTo(0.1d);
        assertThat(config.sampling().forceRecordValues()).containsExactly("on");
        assertThat(config.sampling().dropPaths())
                .containsExactly("/actuator/health", "/actuator/prometheus", "/actuator/info");
        assertThat(config.sampling().routeRatios()).isEmpty();
    }

    @Test
    void enriching_returns_defaults_when_config_is_empty() {
        ExtensionConfig config = new ExtensionConfig(emptyConfig());

        assertThat(config.enriching().enabled()).isTrue();
        assertThat(config.enriching().remoteServicePriority())
                .containsExactly("peer.service", "rpc.service", "server.address");
    }

    @Test
    void scrubbing_returns_defaults_when_config_is_empty() {
        ExtensionConfig config = new ExtensionConfig(emptyConfig());

        assertThat(config.scrubbing().enabled()).isTrue();
        assertThat(config.scrubbing().hmacKey()).isNull();
        assertThat(config.scrubbing().missingKeyPolicy()).isEqualTo("mask");
        assertThat(config.scrubbing().rulesConfig()).isNull();
        assertThat(config.scrubbing().rulesExtensions()).isNull();
        assertThat(config.scrubbing().rulesValidationMode()).isEqualTo("LENIENT");
    }

    @Test
    void validation_returns_defaults_when_config_is_empty() {
        ExtensionConfig config = new ExtensionConfig(emptyConfig());

        assertThat(config.validation().enabled()).isTrue();
        assertThat(config.validation().strict()).isFalse();
        assertThat(config.validation().strictRuntimeAllowed()).isFalse();
    }

    @Test
    void resource_returns_defaults_when_config_is_empty() {
        ExtensionConfig config = new ExtensionConfig(emptyConfig());

        assertThat(config.resource().policyVersion()).isEqualTo("2026.06.08");
        assertThat(config.resource().normalizeEnvironment()).isTrue();
        assertThat(config.resource().validationMode()).isEqualTo("LENIENT");
        assertThat(config.resource().detectContainerId()).isFalse();
    }

    @Test
    void classification_returns_defaults_when_config_is_empty() {
        ExtensionConfig config = new ExtensionConfig(emptyConfig());

        assertThat(config.classification().enabled()).isTrue();
        assertThat(config.classification().slowThreshold()).isEqualTo(Duration.ofSeconds(5));
        assertThat(config.classification().normalThreshold()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void watchdog_returns_defaults_when_config_is_empty() {
        ExtensionConfig config = new ExtensionConfig(emptyConfig());

        // SP-01: Watchdog is disabled by default.
        assertThat(config.watchdog().enabled()).isFalse();
        assertThat(config.watchdog().spanTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.watchdog().traceTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(config.watchdog().scanInterval()).isEqualTo(Duration.ofSeconds(5));
    }

    // -------------------------------------------------------------------------
    // SP-01 — Watchdog default-off characterization
    // -------------------------------------------------------------------------

    /** SP-01 / Test 1. No watchdog property → disabled by default. */
    @Test
    void watchdogIsDisabledByDefault() {
        ExtensionConfig config = new ExtensionConfig(emptyConfig());
        assertThat(config.watchdog().enabled()).isFalse();
    }

    /** SP-01 / Test 2. Explicit enabled=true → watchdog is enabled. */
    @Test
    void watchdogCanBeExplicitlyEnabled() {
        ExtensionConfig config = new ExtensionConfig(
                booleanConfig(ExtensionPropertyNames.WATCHDOG_ENABLED, true));
        assertThat(config.watchdog().enabled()).isTrue();
    }

    /** SP-01 / Test 3. Explicit enabled=false → watchdog is disabled. */
    @Test
    void watchdogCanBeExplicitlyDisabled() {
        ExtensionConfig config = new ExtensionConfig(
                booleanConfig(ExtensionPropertyNames.WATCHDOG_ENABLED, false));
        assertThat(config.watchdog().enabled()).isFalse();
    }

    @Test
    void queue_returns_default_overflow_policy_when_config_is_empty() {
        ExtensionConfig config = new ExtensionConfig(emptyConfig());

        assertThat(config.queue().overflowPolicy()).isEqualTo("DROP_OLDEST");
    }

    @Test
    void baggage_returns_defaults_when_config_is_empty() {
        ExtensionConfig config = new ExtensionConfig(emptyConfig());

        assertThat(config.baggage().enabled()).isTrue();
        assertThat(config.baggage().allowlistKeys())
                .containsExactly("traffic_source", "tenant_class", "platform.correlation.id");
        assertThat(config.baggage().denyPatterns()).containsExactly("password", "secret", "token");
    }

    @Test
    void sdk_mode_is_null_when_config_is_empty() {
        ExtensionConfig config = new ExtensionConfig(emptyConfig());

        assertThat(config.sdk().mode()).isNull();
    }

    @Test
    void runtimeControlMutationIsDisabledByDefault() {
        assertThat(new ExtensionConfig(emptyConfig()).control().runtimeMutationEnabled()).isFalse();
    }

    @Test
    void runtimeControlMutationCanBeExplicitlyEnabled() {
        ExtensionConfig config = new ExtensionConfig(
                booleanConfig(ExtensionPropertyNames.CONTROL_RUNTIME_MUTATION_ENABLED, true));

        assertThat(config.control().runtimeMutationEnabled()).isTrue();
    }

    // -- Explicit overrides ----------------------------------------------------------------------

    @Test
    void sampling_reads_explicit_ratio() {
        ExtensionConfig config = new ExtensionConfig(doubleConfig(ExtensionPropertyNames.SAMPLING_RATIO, 0.5d));

        assertThat(config.sampling().ratio()).isEqualTo(0.5d);
    }

    @Test
    void sampling_reads_explicit_enabled_false() {
        ExtensionConfig config = new ExtensionConfig(booleanConfig(ExtensionPropertyNames.SAMPLING_ENABLED, false));

        assertThat(config.sampling().enabled()).isFalse();
    }

    @Test
    void watchdog_reads_explicit_span_timeout() {
        ExtensionConfig config = new ExtensionConfig(
                durationConfig(ExtensionPropertyNames.WATCHDOG_SPAN_TIMEOUT, Duration.ofSeconds(10)));

        assertThat(config.watchdog().spanTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void queue_reads_explicit_upstream_policy() {
        ExtensionConfig config = new ExtensionConfig(
                stringConfig(ExtensionPropertyNames.QUEUE_OVERFLOW_POLICY, "UPSTREAM"));

        assertThat(config.queue().overflowPolicy()).isEqualTo("UPSTREAM");
    }

    @Test
    void baggage_reads_explicit_allowlist() {
        ExtensionConfig config = new ExtensionConfig(
                listConfig(ExtensionPropertyNames.BAGGAGE_ALLOWLIST_KEYS, List.of("x-tenant-id", "x-request-id")));

        assertThat(config.baggage().allowlistKeys()).containsExactly("x-tenant-id", "x-request-id");
    }

    @Test
    void scrubbing_reads_explicit_hmac_key() {
        ExtensionConfig config = new ExtensionConfig(
                stringConfig(ExtensionPropertyNames.SCRUBBING_HMAC_KEY, "secret-key-value"));

        assertThat(config.scrubbing().hmacKey()).isEqualTo("secret-key-value");
    }

    @Test
    void sdk_reads_explicit_mode() {
        ExtensionConfig config = new ExtensionConfig(
                stringConfig(ExtensionPropertyNames.SDK_MODE, "agent"));

        assertThat(config.sdk().mode()).isEqualTo("agent");
    }

    // -- Collection immutability -----------------------------------------------------------------

    @Nested
    @DisplayName("Collections returned from domain configs are unmodifiable")
    class CollectionImmutability {

        @Test
        void sampling_forceRecordValues_is_unmodifiable_when_provided() {
            ExtensionConfig cfg = new ExtensionConfig(
                    listConfig(ExtensionPropertyNames.SAMPLING_FORCE_RECORD_VALUES, List.of("on", "yes")));

            List<String> result = cfg.sampling().forceRecordValues();
            assertThatThrownBy(() -> result.add("extra"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void sampling_dropPaths_is_unmodifiable_when_provided() {
            ExtensionConfig cfg = new ExtensionConfig(
                    listConfig(ExtensionPropertyNames.SAMPLING_DROP_PATHS, List.of("/health")));

            List<String> result = cfg.sampling().dropPaths();
            assertThatThrownBy(() -> result.add("/extra"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void baggage_allowlistKeys_is_unmodifiable_when_provided() {
            ExtensionConfig cfg = new ExtensionConfig(
                    listConfig(ExtensionPropertyNames.BAGGAGE_ALLOWLIST_KEYS, List.of("x-tenant")));

            List<String> result = cfg.baggage().allowlistKeys();
            assertThatThrownBy(() -> result.add("extra"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // -- Nullable passthrough preservation -------------------------------------------------------

    @Nested
    @DisplayName("Nullable values remain null when not configured")
    class NullablePassthrough {

        @Test
        void scrubbing_hmacKey_null_when_absent() {
            assertThat(new ExtensionConfig(emptyConfig()).scrubbing().hmacKey()).isNull();
        }

        @Test
        void scrubbing_hashKeyId_null_when_absent() {
            assertThat(new ExtensionConfig(emptyConfig()).scrubbing().hashKeyId()).isNull();
        }

        @Test
        void scrubbing_rulesConfig_null_when_absent() {
            assertThat(new ExtensionConfig(emptyConfig()).scrubbing().rulesConfig()).isNull();
        }

        @Test
        void scrubbing_rulesExtensions_null_when_absent() {
            assertThat(new ExtensionConfig(emptyConfig()).scrubbing().rulesExtensions()).isNull();
        }

        @Test
        void sdk_mode_null_when_absent() {
            assertThat(new ExtensionConfig(emptyConfig()).sdk().mode()).isNull();
        }
    }

    // -- Stub helpers ----------------------------------------------------------------------------

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
