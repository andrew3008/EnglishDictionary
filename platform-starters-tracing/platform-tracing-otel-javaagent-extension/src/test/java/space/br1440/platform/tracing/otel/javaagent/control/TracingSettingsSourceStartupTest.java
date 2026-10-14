package space.br1440.platform.tracing.otel.javaagent.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsSourceAuthority;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.CapturedLogEvent;
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
        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid property 'platform.tracing.diagnostics.log-level'='VERBOSE'. "
                        + "Accepted values: [OFF, ERROR, WARN, INFO, DEBUG, TRACE]");
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
    @ResourceLock(AgentLogCapture.RESOURCE_LOCK)
    void invalidRouteRatioIsSkippedWithoutLoggingTheValue() {
        AgentLogCapture logs = new AgentLogCapture();
        logs.install();
        try {
            Map<String, String> ratios = new LinkedHashMap<>();
            ratios.put("/orders", "0.5");
            ratios.put("/payments", "not-a-ratio");

            // given
            ExtensionConfig config = config(
                    Map.of(
                            ExtensionPropertyNames.SAMPLING_ROUTE_RATIOS,
                            "/orders=0.5,/payments=not-a-ratio"
                    ),
                    ratios);

            // when
            TracingSettingsSource source = TracingSettingsSource.fromStartup(config, AGENT_INSTANCE_ID);

            // then
            assertThat(source.current().samplingRouteRatios())
                    .containsEntry("/orders", 0.5d)
                    .doesNotContainKey("/payments");
            CapturedLogEvent event = logs.events().stream()
                    .filter(candidate -> "INVALID_ROUTE_RATIO".equals(candidate.eventName()))
                    .findFirst()
                    .orElseThrow();
            assertThat(event.level()).isEqualTo(AgentLogLevel.WARN);
            assertThat(event.fields())
                    .containsEntry("name", "/payments")
                    .containsEntry("reason", "not-a-number");
            assertThat(logs.rendered()).doesNotContain("not-a-ratio");
        } finally {
            logs.restore();
        }
    }

    @Test
    void isolatedDisabledStateStartsDisabled() {
        // given
        SpanAttributeValidationLevel level = SpanAttributeValidationLevel.DISABLED;

        // when
        TracingSettingsSource source = TracingSettingsSource.isolatedValidation(level);

        // then
        assertThat(source.current().validationLevel()).isEqualTo(SpanAttributeValidationLevel.DISABLED);
    }

    @Test
    void isolatedErrorStateIsNotBlockedByRemovedEnvironmentGate() {
        // given
        SpanAttributeValidationLevel level = SpanAttributeValidationLevel.ERROR;

        // when
        TracingSettingsSource source = TracingSettingsSource.isolatedValidation(level);

        // then
        assertThat(source.current().validationLevel()).isEqualTo(SpanAttributeValidationLevel.ERROR);
    }

    @Test
    void isolatedSourceRejectsDirectStoreMutation() {
        // given
        TracingSettingsSource source = TracingSettingsSource.isolatedValidation(SpanAttributeValidationLevel.DISABLED);
        TracingSettingsRevision initial = source.current();

        // when
        Throwable failure = catchThrowable(() -> AcceptedPolicyMutationAccess.applyValidation(
                source, SpanAttributeValidationLevel.ERROR));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cannot commit tracing settings to an unbound store");
        assertThat(source.current().acceptedSequence()).isZero();
        assertThat(source.current().policy()).isEqualTo(initial.policy());
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
