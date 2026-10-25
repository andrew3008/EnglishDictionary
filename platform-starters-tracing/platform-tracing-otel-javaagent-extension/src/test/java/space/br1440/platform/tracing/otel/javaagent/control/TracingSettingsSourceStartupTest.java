package space.br1440.platform.tracing.otel.javaagent.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jakarta.annotation.Nonnull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import space.br1440.platform.tracing.otel.javaagent.configuration.ConfigurationPropertyException;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsSourceAuthority;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class TracingSettingsSourceStartupTest {

    private static final AgentInstanceId AGENT_INSTANCE_ID =
            AgentInstanceId.parse("00000000-0000-0000-0000-000000000001");

    @Test
    void processErrorStartsError() {
        // given
        ExtensionConfig config = config(Map.of(
                ExtensionPropertyNames.SPAN_ATTRIBUTES_VALIDATION_LEVEL,
                "ERROR"
        ));

        // when
        TracingSettingsSource source = TracingSettingsSource.fromStartup(config, AGENT_INSTANCE_ID);

        // then
        assertThat(source.current().validationLevel()).isEqualTo(SpanAttributeValidationLevel.ERROR);
        assertThat(source.current().agentInstanceId()).isEqualTo(AGENT_INSTANCE_ID);
    }

    @Test
    void processDisabledStartsDisabled() {
        // given
        ExtensionConfig config = config(Map.of(
                ExtensionPropertyNames.SPAN_ATTRIBUTES_VALIDATION_LEVEL,
                "DISABLED"
        ));

        // when
        TracingSettingsSource source = TracingSettingsSource.fromStartup(config, AGENT_INSTANCE_ID);

        // then
        assertThat(source.current().validationLevel()).isEqualTo(SpanAttributeValidationLevel.DISABLED);
    }

    @Test
    void absentLevelStartsWarning() {
        // given
        ExtensionConfig config = config(Map.of());

        // when
        TracingSettingsSource source = TracingSettingsSource.fromStartup(config, AGENT_INSTANCE_ID);

        // then
        assertThat(source.current().validationLevel()).isEqualTo(SpanAttributeValidationLevel.WARNING);
    }

    @Test
    void absentControlSettingsUseTypedDefaults() {
        // given
        ExtensionConfig config = config(Map.of());

        // when
        TracingSettingsSource source = TracingSettingsSource.fromStartup(config, AGENT_INSTANCE_ID);

        // then
        assertThat(source.current().exporterEnabled()).isTrue();
        assertThat(source.current().traceControlHeadersEnabled()).isTrue();
        assertThat(source.current().policy().diagnosticsLogLevel()).isEqualTo(AgentLogLevel.INFO);
        assertThat(source.current().sourceAuthority()).isEqualTo(SettingsSourceAuthority.BOOTSTRAP);
    }

    @Test
    void explicitControlSettingsReachStartupPolicy() {
        // given
        ExtensionConfig config = config(Map.of(
                ExtensionPropertyNames.EXPORTER_ENABLED, "false",
                ExtensionPropertyNames.TRACE_CONTROL_HEADERS_ENABLED, "false",
                ExtensionPropertyNames.DIAGNOSTICS_LOG_LEVEL, "debug"
        ));

        // when
        TracingSettingsSource source = TracingSettingsSource.fromStartup(config, AGENT_INSTANCE_ID);

        // then
        assertThat(source.current().exporterEnabled()).isFalse();
        assertThat(source.current().traceControlHeadersEnabled()).isFalse();
        assertThat(source.current().policy().diagnosticsLogLevel()).isEqualTo(AgentLogLevel.DEBUG);
        assertThat(source.current().sourceAuthority()).isEqualTo(SettingsSourceAuthority.BOOTSTRAP);
    }

    @Test
    void unknownDiagnosticsLogLevelFailsFast() {
        // given
        Map<String, String> values = Map.of(
                ExtensionPropertyNames.DIAGNOSTICS_LOG_LEVEL,
                "VERBOSE"
        );

        // when
        Throwable failure = catchThrowable(() -> config(values));

        // then
        assertThat(failure).isInstanceOf(ConfigurationPropertyException.class);
        ConfigurationPropertyException propertyFailure = (ConfigurationPropertyException) failure;
        assertThat(propertyFailure.propertyName()).isEqualTo(ExtensionPropertyNames.DIAGNOSTICS_LOG_LEVEL);
        assertThat(propertyFailure.failure())
                .hasMessage("platform.tracing.diagnostics.log-level must be one of: OFF, ERROR, WARN, INFO, DEBUG, TRACE");
    }

    @Test
    void configuredValueWithoutBootstrapPropertyIsAgentDefault() {
        // given
        ExtensionConfig config = new ExtensionConfig(new MapConfigProperties(Map.of(
                ExtensionPropertyNames.EXPORTER_ENABLED,
                "false"
        )));

        // when
        TracingSettingsSource source = TracingSettingsSource.fromStartup(config, AGENT_INSTANCE_ID);

        // then
        assertThat(source.current().exporterEnabled()).isFalse();
        assertThat(source.current().sourceAuthority()).isEqualTo(SettingsSourceAuthority.BOOTSTRAP);
    }

    @Test
    void leftoverPlatformEnvironmentDoesNotDowngradeError() {
        // given
        ExtensionConfig config = config(Map.of(
                ExtensionPropertyNames.SPAN_ATTRIBUTES_VALIDATION_LEVEL,
                "ERROR",
                "platform.environment",
                "production"
        ));

        // when
        TracingSettingsSource source = TracingSettingsSource.fromStartup(config, AGENT_INSTANCE_ID);

        // then
        assertThat(source.current().validationLevel()).isEqualTo(SpanAttributeValidationLevel.ERROR);
    }

    @Test
    void processDisabledCanBeEnabledLaterWithoutLifetimeBan() {
        // given
        TracingSettingsSource source = TracingSettingsSource.fromStartup(config(Map.of(
                ExtensionPropertyNames.SPAN_ATTRIBUTES_VALIDATION_LEVEL,
                "DISABLED"
        )), AGENT_INSTANCE_ID);

        // when
        SettingsUpdateStatus outcome = AcceptedPolicyMutationAccess.applyValidation(
                source, SpanAttributeValidationLevel.ERROR);

        // then
        assertThat(outcome).isEqualTo(SettingsUpdateStatus.APPLIED);
        assertThat(source.current().validationLevel()).isEqualTo(SpanAttributeValidationLevel.ERROR);
    }

    @Test
    void unknownLevelIsRejectedBeforeSettingsAreCreated() {
        // given
        Map<String, String> values = Map.of(
                ExtensionPropertyNames.SPAN_ATTRIBUTES_VALIDATION_LEVEL,
                "STRICT"
        );

        // when
        Throwable failure = catchThrowable(() -> TracingSettingsSource.fromStartup(
                config(values),
                AGENT_INSTANCE_ID));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("platform.tracing.javaagent.span-attributes.validation.level");
    }

    @Test
    void invalidRouteRatioFailsStartupWithoutExposingValue() {
        // given
        String routePrefix = "/payments";
        String sensitiveValue = "confidential-not-a-ratio";
        ExtensionConfig config = config(Map.of(), Map.of(routePrefix, sensitiveValue));

        // when
        Throwable failure = catchThrowable(() -> TracingSettingsSource.fromStartup(config, AGENT_INSTANCE_ID));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(routePrefix)
                .hasMessageContaining("value must be a number")
                .hasMessageNotContaining(sensitiveValue);
        assertThat(failure.getCause()).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void blankRouteRatioFailsStartupWithKey(String invalidValue) {
        // given
        String routePrefix = "/payments";
        Map<String, String> ratios = new HashMap<>();
        ratios.put(routePrefix, invalidValue);
        ExtensionConfig config = config(Map.of(), ratios);

        // when
        Throwable failure = catchThrowable(() -> TracingSettingsSource.fromStartup(config, AGENT_INSTANCE_ID));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(routePrefix)
                .hasMessageContaining("value must not be blank");
    }

    @Test
    void nullRouteRatioKeyFailsStartupWithoutExposingValue() {
        // given
        String sensitiveValue = "0.314159";
        Map<String, String> ratios = new HashMap<>();
        ratios.put(null, sensitiveValue);
        ExtensionConfig config = config(Map.of(), ratios);

        // when
        Throwable failure = catchThrowable(() -> TracingSettingsSource.fromStartup(config, AGENT_INSTANCE_ID));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key '<null>'")
                .hasMessageContaining("key must not be null")
                .hasMessageNotContaining(sensitiveValue);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankRouteRatioKeyFailsStartupThroughDomainValidation(String routePrefix) {
        // given
        String sensitiveValue = "0.314159";
        ExtensionConfig config = config(Map.of(), Map.of(routePrefix, sensitiveValue));

        // when
        Throwable failure = catchThrowable(() -> TracingSettingsSource.fromStartup(config, AGENT_INSTANCE_ID));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("routePrefix")
                .hasMessageContaining("must not be blank")
                .hasMessageNotContaining(sensitiveValue);
    }

    @Test
    void duplicateRouteRatioKeyFailsStartup() {
        // given
        String routePrefix = "/payments";
        Map<String, String> ratios = new AbstractMap<>() {

            @Override
            @Nonnull
            public Set<Entry<String, String>> entrySet() {
                return Set.of(
                        Map.entry(routePrefix, "0.2"),
                        Map.entry(routePrefix, "0.3")
                );
            }
        };
        ExtensionConfig config = config(Map.of(), ratios);

        // when
        Throwable failure = catchThrowable(() -> TracingSettingsSource.fromStartup(config, AGENT_INSTANCE_ID));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(routePrefix)
                .hasMessageContaining("key is duplicated");
    }

    @Test
    void disabledValidationLevelIsStoredAtStartup() {
        // given
        SpanAttributeValidationLevel level = SpanAttributeValidationLevel.DISABLED;

        // when
        TracingSettingsSource source = TracingSettingsSourceTestFixtures.boundValidation(AgentInstanceId.random(), level);

        // then
        assertThat(source.current().validationLevel()).isEqualTo(SpanAttributeValidationLevel.DISABLED);
    }

    @Test
    void errorValidationLevelIsStoredAtStartup() {
        // given
        SpanAttributeValidationLevel level = SpanAttributeValidationLevel.ERROR;

        // when
        TracingSettingsSource source = TracingSettingsSourceTestFixtures.boundValidation(AgentInstanceId.random(), level);

        // then
        assertThat(source.current().validationLevel()).isEqualTo(SpanAttributeValidationLevel.ERROR);
    }

    private static ExtensionConfig config(Map<String, String> values) {
        return config(values, null);
    }

    private static ExtensionConfig config(
            Map<String, String> values,
            Map<String, String> routeRatios) {
        return new ExtensionConfig(new MapConfigProperties(values, routeRatios));
    }

    private static final class MapConfigProperties implements ConfigProperties {
        private final Map<String, String> data;
        private final Map<String, String> routeRatios;

        private MapConfigProperties(Map<String, String> data) {
            this(data, null);
        }

        private MapConfigProperties(Map<String, String> data, Map<String, String> routeRatios) {
            this.data = new HashMap<>(data);
            this.routeRatios = routeRatios;
        }

        @Override
        public String getString(String name) {
            return data.get(name);
        }

        @Override
        public Boolean getBoolean(String name) {
            String value = data.get(name);
            return value == null ? null : Boolean.parseBoolean(value);
        }

        @Override
        public Integer getInt(String name) {
            return null;
        }

        @Override
        public Long getLong(String name) {
            return null;
        }

        @Override
        public Double getDouble(String name) {
            return null;
        }

        @Override
        public Duration getDuration(String name) {
            return null;
        }

        @Override
        public List<String> getList(String name) {
            return null;
        }

        @Override
        public Map<String, String> getMap(String name) {
            if (routeRatios != null && ExtensionPropertyNames.SAMPLING_ROUTE_RATIOS.equals(name)) {
                return routeRatios;
            }
            return null;
        }
    }
}
