package space.br1440.platform.tracing.otel.javaagent.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;
import space.br1440.platform.tracing.otel.javaagent.logging.CapturedLogEvent;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

class TracingSettingsSourceStartupTest {

    private static final AgentInstanceId AGENT_INSTANCE_ID =
            AgentInstanceId.parse("00000000-0000-0000-0000-000000000001");

    @Test
    void processErrorStartsError() {
        // given/when
        TracingSettingsSource source = TracingSettingsSource.fromStartup(config(Map.of(
                ExtensionPropertyNames.SPAN_ATTRIBUTES_VALIDATION_LEVEL,
                "ERROR"
        )), AGENT_INSTANCE_ID);

        // then
        assertThat(source.current().validationLevel()).isEqualTo(SpanAttributeValidationLevel.ERROR);
        assertThat(source.current().agentInstanceId()).isEqualTo(AGENT_INSTANCE_ID);
    }

    @Test
    void processDisabledStartsDisabled() {
        // given/when
        TracingSettingsSource source = TracingSettingsSource.fromStartup(config(Map.of(
                ExtensionPropertyNames.SPAN_ATTRIBUTES_VALIDATION_LEVEL,
                "DISABLED"
        )), AGENT_INSTANCE_ID);

        // then
        assertThat(source.current().validationLevel()).isEqualTo(SpanAttributeValidationLevel.DISABLED);
    }

    @Test
    void absentLevelStartsWarning() {
        // given/when
        TracingSettingsSource source = TracingSettingsSource.fromStartup(
                config(Map.of()),
                AGENT_INSTANCE_ID);

        // then
        assertThat(source.current().validationLevel()).isEqualTo(SpanAttributeValidationLevel.WARNING);
    }

    @Test
    void leftoverPlatformEnvironmentDoesNotDowngradeError() {
        // given/when
        TracingSettingsSource source = TracingSettingsSource.fromStartup(config(Map.of(
                ExtensionPropertyNames.SPAN_ATTRIBUTES_VALIDATION_LEVEL,
                "ERROR",
                "platform.environment",
                "production"
        )), AGENT_INSTANCE_ID);

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
        // given/when/then
        assertThatThrownBy(() -> TracingSettingsSource.fromStartup(config(Map.of(
                ExtensionPropertyNames.SPAN_ATTRIBUTES_VALIDATION_LEVEL,
                "STRICT"
        )), AGENT_INSTANCE_ID))
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
            ExtensionConfig config = new ExtensionConfig(new MapConfigProperties(Map.of(), ratios));

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
        // given/when
        TracingSettingsSource source = TracingSettingsSource.isolatedValidation(SpanAttributeValidationLevel.DISABLED);

        // then
        assertThat(source.current().validationLevel()).isEqualTo(SpanAttributeValidationLevel.DISABLED);
    }

    @Test
    void isolatedErrorStateIsNotBlockedByRemovedEnvironmentGate() {
        // given/when
        TracingSettingsSource source = TracingSettingsSource.isolatedValidation(SpanAttributeValidationLevel.ERROR);

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
        return new ExtensionConfig(new MapConfigProperties(values));
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
